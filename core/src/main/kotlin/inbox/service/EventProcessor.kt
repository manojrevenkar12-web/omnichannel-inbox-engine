package inbox.service

import com.fasterxml.jackson.databind.ObjectMapper
import inbox.channels.InboundUnit
import inbox.channels.SmsWebhook
import inbox.config.InboxConfig
import inbox.domain.Channel
import inbox.domain.DeliveryStatus
import inbox.domain.EntryPoint
import inbox.store.Db
import inbox.store.one
import inbox.store.query
import inbox.store.str
import inbox.store.update
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Applies stored webhook facts to conversations and messages.
 *
 * * Messages: find or create the contact (a WhatsApp number and an SMS number with the same
 *   digits are one contact), find or open the conversation, store the message once, move the reply
 *   window by the message's provider timestamp, and schedule the AI agent's turn.
 * * Statuses: move the message's status forward only ([DeliveryStatus.merge]); keep every report.
 *   A status can arrive before the send that caused it has recorded the provider's message id. It
 *   is then matched by the callback data we attached to the send (our message id), or retried later.
 *
 * Facts are claimed with `FOR UPDATE SKIP LOCKED`, so any number of processors can run.
 */
@ApplicationScoped
class EventProcessor(
    private val db: Db,
    private val mapper: ObjectMapper,
    private val cfg: InboxConfig,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    data class Outcome(val processed: Int, val deferred: Int)

    fun drain(batch: Int = 200): Outcome {
        var processed = 0
        var deferred = 0
        while (true) {
            val r = db.tx { c -> processBatch(c, batch) }
            processed += r.processed
            deferred += r.deferred
            if (r.processed + r.deferred < batch) return Outcome(processed, deferred)
        }
    }

    private data class Pending(val id: Long, val attempts: Int, val unit: StoredUnit)

    private fun processBatch(c: Connection, batch: Int): Outcome {
        val now = clock.instant()
        val pending =
            c.query(
                "SELECT id, attempts, unit::text AS unit FROM inbound_event WHERE processed_at IS NULL AND next_attempt_at <= ? " +
                    "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                now,
                batch,
            ) { Pending(it.getLong("id"), it.getInt("attempts"), mapper.readValue(it.getString("unit"), StoredUnit::class.java)) }
        var processed = 0
        var deferred = 0
        for (p in pending) {
            val save = c.setSavepoint()
            try {
                val outcome =
                    when (val u = p.unit.unit()) {
                        is InboundUnit.Message -> applyMessage(c, u, now)
                        is InboundUnit.Status -> applyStatus(c, u, now)
                    }
                if (outcome == DEFER && p.attempts < MAX_ATTEMPTS) {
                    val wait = Duration.ofMillis(minOf(60_000L, 250L shl minOf(p.attempts, 8)))
                    c.update("UPDATE inbound_event SET attempts = attempts + 1, next_attempt_at = ? WHERE id = ?", now + wait, p.id)
                    deferred++
                } else {
                    val result = if (outcome == DEFER) "orphan_status" else outcome
                    c.update("UPDATE inbound_event SET processed_at = ?, outcome = ?, attempts = attempts + 1 WHERE id = ?", now, result, p.id)
                    meters.counter("inbox.events.processed", "outcome", result).increment()
                    processed++
                }
            } catch (e: Exception) {
                c.rollback(save)
                c.update(
                    "UPDATE inbound_event SET attempts = attempts + 1, next_attempt_at = ?, last_error = ? WHERE id = ?",
                    now + Duration.ofSeconds(30),
                    (e.message ?: e.javaClass.simpleName).take(500),
                    p.id,
                )
                meters.counter("inbox.events.errors").increment()
                deferred++
            }
        }
        return Outcome(processed, deferred)
    }

    // ------------------------------------------------------------------ messages

    private data class Account(val id: String, val workspaceId: String, val aiEnabled: Boolean)

    private fun account(c: Connection, channel: Channel, address: String): Account? =
        c.one(
            "SELECT a.id, a.workspace_id, w.ai_enabled FROM channel_account a JOIN workspace w ON w.id = a.workspace_id WHERE a.channel = ? AND a.address = ?",
            channel,
            address,
        ) { Account(it.getString("id"), it.getString("workspace_id"), it.getBoolean("ai_enabled")) }

    private fun contactFor(c: Connection, ws: String, u: InboundUnit.Message): String {
        c.one("SELECT contact_id FROM contact_identity WHERE workspace_id = ? AND channel = ? AND address = ?", ws, u.channel, u.from) {
            it.getString("contact_id")
        }?.let { return it }
        // one person, one contact: a phone number seen on another phone channel is the same customer
        val linked =
            if (u.channel == Channel.WHATSAPP || u.channel == Channel.SMS) {
                val digits = Ids.phoneDigits(u.from)
                c.query(
                    "SELECT contact_id, address FROM contact_identity WHERE workspace_id = ? AND channel IN ('WHATSAPP', 'SMS')",
                    ws,
                ) { it.getString("contact_id") to it.getString("address") }
                    .firstOrNull { (_, addr) -> digits.isNotEmpty() && Ids.phoneDigits(addr) == digits }
                    ?.first
            } else {
                null
            }
        val contactId = linked ?: Ids.new("ct").also { c.update("INSERT INTO contact (id, workspace_id, display_name) VALUES (?, ?, ?)", it, ws, u.displayName) }
        c.update(
            "INSERT INTO contact_identity (workspace_id, channel, address, contact_id) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
            ws,
            u.channel,
            u.from,
            contactId,
        )
        return c.one("SELECT contact_id FROM contact_identity WHERE workspace_id = ? AND channel = ? AND address = ?", ws, u.channel, u.from) {
            it.getString("contact_id")
        } ?: contactId
    }

    private fun openConversation(c: Connection, ws: String, contactId: String, at: Instant): String {
        val existing = c.one("SELECT id FROM conversation WHERE contact_id = ? AND status = 'OPEN' FOR UPDATE", contactId) { it.getString("id") }
        if (existing != null) return existing
        c.update(
            "INSERT INTO conversation (id, workspace_id, contact_id, last_message_at) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (contact_id) WHERE status = 'OPEN' DO NOTHING",
            Ids.new("cv"),
            ws,
            contactId,
            at,
        )
        return c.one("SELECT id FROM conversation WHERE contact_id = ? AND status = 'OPEN' FOR UPDATE", contactId) { it.getString("id") }
            ?: error("conversation for $contactId vanished")
    }

    private fun applyMessage(c: Connection, u: InboundUnit.Message, now: Instant): String {
        val acc = account(c, u.channel, u.accountAddress) ?: return "unknown_account"
        if (c.one("SELECT 1 FROM message WHERE channel = ? AND provider_message_id = ?", u.channel, u.providerMessageId) { 1 } != null) {
            return "duplicate_message"
        }
        val contactId = contactFor(c, acc.workspaceId, u)
        if (u.displayName != null) c.update("UPDATE contact SET display_name = coalesce(display_name, ?) WHERE id = ?", u.displayName, contactId)
        val optOut = u.channel == Channel.SMS && SmsWebhook.isOptOut(u.text)
        if (optOut) {
            c.update("UPDATE contact_identity SET opted_out = true WHERE workspace_id = ? AND channel = ? AND address = ?", acc.workspaceId, u.channel, u.from)
        }
        val convId = openConversation(c, acc.workspaceId, contactId, u.sentAt)
        c.update(
            "INSERT INTO message (id, workspace_id, conversation_id, direction, channel, account_id, author, body, provider_message_id, status, status_at, sent_at) " +
                "VALUES (?, ?, ?, 'INBOUND', ?, ?, 'CUSTOMER', ?, ?, 'DELIVERED', ?, ?)",
            Ids.new("msg"),
            acc.workspaceId,
            convId,
            u.channel,
            acc.id,
            if (u.subject != null && u.channel == Channel.EMAIL) "${u.subject}\n\n${u.text}".trim() else u.text,
            u.providerMessageId,
            u.sentAt,
            u.sentAt,
        )
        // the window moves with the provider's timestamp, never backwards: a late webhook for an older
        // message does not reopen or extend it
        val entry = if (u.entryPoint != EntryPoint.ORGANIC) u.entryPoint else null
        c.update(
            "INSERT INTO conversation_channel (conversation_id, channel, account_id, customer_address, last_inbound_at, last_inbound_arrived_at, entry_point, entry_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (conversation_id, channel) DO UPDATE SET " +
                "last_inbound_at = greatest(conversation_channel.last_inbound_at, excluded.last_inbound_at), " +
                "last_inbound_arrived_at = excluded.last_inbound_arrived_at, " +
                "account_id = excluded.account_id, customer_address = excluded.customer_address, " +
                "entry_point = CASE WHEN excluded.entry_point <> 'ORGANIC' THEN excluded.entry_point ELSE conversation_channel.entry_point END, " +
                "entry_at = CASE WHEN excluded.entry_point <> 'ORGANIC' THEN excluded.entry_at ELSE conversation_channel.entry_at END",
            convId,
            u.channel,
            acc.id,
            u.from,
            u.sentAt,
            now,
            entry ?: EntryPoint.ORGANIC,
            if (entry != null) u.sentAt else null,
        )
        val assignee = c.one("SELECT assignee_kind FROM conversation WHERE id = ?", convId) { it.getString("assignee_kind") } ?: "NONE"
        val nextAssignee = if (assignee == "NONE" && acc.aiEnabled && !optOut) "AI" else assignee
        val due = if (nextAssignee == "AI" && !optOut) now + cfg.agent().quiet() else null
        c.update(
            "UPDATE conversation SET last_message_at = greatest(last_message_at, ?), assignee_kind = ?, agent_due_at = ?::timestamptz, version = version + 1 WHERE id = ?",
            u.sentAt,
            nextAssignee,
            due,
            convId,
        )
        return if (optOut) "opt_out" else "message"
    }

    // ------------------------------------------------------------------ statuses

    private fun applyStatus(c: Connection, u: InboundUnit.Status, now: Instant): String {
        var row =
            c.one("SELECT id, status, provider_message_id FROM message WHERE channel = ? AND provider_message_id = ? FOR UPDATE", u.channel, u.providerMessageId) {
                Triple(it.getString("id"), DeliveryStatus.valueOf(it.getString("status")), it.str("provider_message_id"))
            }
        if (row == null && u.callbackData != null) {
            // the status beat the send's own response: match it by the id we attached to the send
            row =
                c.one("SELECT id, status, provider_message_id FROM message WHERE id = ? AND channel = ? FOR UPDATE", u.callbackData, u.channel) {
                    Triple(it.getString("id"), DeliveryStatus.valueOf(it.getString("status")), it.str("provider_message_id"))
                }
            if (row != null && row.third == null) {
                c.update("UPDATE message SET provider_message_id = ? WHERE id = ?", u.providerMessageId, row.first)
            }
        }
        if (row == null) return DEFER
        val (id, current, _) = row
        c.update("INSERT INTO message_status (message_id, status, at, error_code) VALUES (?, ?, ?, ?)", id, u.status, u.at, u.errorCode)
        // a provider status is proof the send was accepted: settle a send that timed out on our side
        c.update(
            "UPDATE outbound_job SET state = 'DONE', updated_at = ? WHERE message_id = ? AND state IN ('AMBIGUOUS', 'IN_FLIGHT', 'PENDING')",
            now,
            id,
        )
        val merged = current.merge(u.status)
        if (merged == current) {
            meters.counter("inbox.status.ignored").increment()
            return "status_kept"
        }
        c.update(
            "UPDATE message SET status = ?, status_at = ?, error_code = coalesce(?::text, error_code), error_title = coalesce(?::text, error_title) WHERE id = ?",
            merged,
            u.at,
            if (merged == DeliveryStatus.FAILED) u.errorCode else null,
            if (merged == DeliveryStatus.FAILED) u.errorTitle else null,
            id,
        )
        return "status_" + merged.name.lowercase()
    }

    companion object {
        const val DEFER = "defer"
        const val MAX_ATTEMPTS = 12
    }

    /** Pending and parked facts, for operations. */
    fun backlog(): Map<String, Long> =
        db.read { c ->
            c.query(
                "SELECT CASE WHEN processed_at IS NULL THEN 'pending' ELSE coalesce(outcome, 'done') END AS k, count(*) AS n FROM inbound_event GROUP BY 1",
            ) { it.getString("k") to it.getLong("n") }.toMap()
        }
}
