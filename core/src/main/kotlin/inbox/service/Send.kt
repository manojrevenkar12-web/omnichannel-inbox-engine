package inbox.service

import inbox.config.InboxConfig
import inbox.domain.Author
import inbox.domain.Billing
import inbox.domain.Channel
import inbox.domain.ChannelState
import inbox.domain.EntryPoint
import inbox.domain.MessageKind
import inbox.domain.SendDecision
import inbox.domain.TemplateCategory
import inbox.domain.WindowPolicy
import inbox.store.Db
import inbox.store.instant
import inbox.store.one
import inbox.store.str
import inbox.store.update
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import java.sql.Connection
import java.time.Clock

data class SendRequest(
    val text: String? = null,
    val template: String? = null,
    val language: String? = null,
    val channel: Channel? = null,
)

sealed interface SendResult {
    data class Queued(val messageId: String, val billable: Boolean, val chargeCategory: String, val replayed: Boolean) : SendResult

    data class TemplateRequired(val reason: String, val closedAt: java.time.Instant?) : SendResult

    data class Refused(val reason: String) : SendResult

    data class NotFound(val what: String) : SendResult
}

/**
 * Records an outbound message and queues its send in one transaction (an outbox), after the
 * channel's reply rules allow it. The same idempotency key returns the same message, so a client
 * that retries a request after a timeout does not send the customer the message twice.
 */
@ApplicationScoped
class Send(private val db: Db, private val cfg: InboxConfig, private val clock: Clock, private val meters: MeterRegistry) {
    private val billing by lazy { Billing(cfg.whatsapp().serviceBilledFrom()) }

    fun submit(
        workspaceId: String,
        conversationId: String,
        req: SendRequest,
        author: Author,
        authorId: String?,
        idempotencyKey: String?,
    ): SendResult = db.tx { c -> submit(c, workspaceId, conversationId, req, author, authorId, idempotencyKey) }

    fun submit(
        c: Connection,
        workspaceId: String,
        conversationId: String,
        req: SendRequest,
        author: Author,
        authorId: String?,
        idempotencyKey: String?,
    ): SendResult {
        val now = clock.instant()
        if (idempotencyKey != null) {
            c.one("SELECT id, billable, charge_category FROM message WHERE workspace_id = ? AND idempotency_key = ?", workspaceId, idempotencyKey) {
                SendResult.Queued(it.getString("id"), it.getBoolean("billable"), it.str("charge_category") ?: "none", true)
            }?.let { return it }
        }
        c.one("SELECT id FROM conversation WHERE id = ? AND workspace_id = ? FOR UPDATE", conversationId, workspaceId) { it.getString("id") }
            ?: return SendResult.NotFound("conversation")
        // reply on the channel the customer last wrote on, unless the sender chose one
        val ch =
            c.one(
                "SELECT cc.channel, cc.account_id, cc.customer_address, cc.last_inbound_at, cc.entry_point, cc.entry_at, " +
                    "coalesce(ci.opted_out, false) AS opted_out FROM conversation_channel cc " +
                    "JOIN conversation cv ON cv.id = cc.conversation_id " +
                    "LEFT JOIN contact_identity ci ON ci.workspace_id = cv.workspace_id AND ci.channel = cc.channel AND ci.address = cc.customer_address " +
                    "WHERE cc.conversation_id = ? AND (?::text IS NULL OR cc.channel = ?::text) " +
                    "ORDER BY cc.last_inbound_at DESC NULLS LAST LIMIT 1",
                conversationId,
                req.channel?.name,
                req.channel?.name,
            ) {
                Triple(
                    ChannelState(
                        Channel.valueOf(it.getString("channel")),
                        it.instant("last_inbound_at"),
                        EntryPoint.valueOf(it.getString("entry_point")),
                        it.instant("entry_at"),
                        it.getBoolean("opted_out"),
                    ),
                    it.getString("account_id"),
                    it.getString("customer_address"),
                )
            } ?: return SendResult.Refused("the customer has not written on ${req.channel?.name?.lowercase() ?: "any channel"}")
        val (state, accountId, recipient) = ch

        val kind = if (req.template != null) MessageKind.TEMPLATE else MessageKind.FREE_FORM
        var category: TemplateCategory? = null
        val body: String
        if (kind == MessageKind.TEMPLATE) {
            val t =
                c.one(
                    "SELECT body, category FROM template WHERE workspace_id = ? AND name = ? AND (?::text IS NULL OR language = ?::text) LIMIT 1",
                    workspaceId,
                    req.template,
                    req.language,
                    req.language,
                ) { it.getString("body") to TemplateCategory.valueOf(it.getString("category")) } ?: return SendResult.Refused("unknown template ${req.template}")
            body = t.first
            category = t.second
        } else {
            body = req.text?.trim().orEmpty()
            if (body.isEmpty()) return SendResult.Refused("empty message")
            if (body.length > 4096) return SendResult.Refused("message longer than 4096 characters")
        }

        when (val d = WindowPolicy.decide(state, kind, author, now)) {
            SendDecision.Allowed -> Unit
            is SendDecision.TemplateRequired -> {
                meters.counter("inbox.send.refused", "reason", "template_required").increment()
                return SendResult.TemplateRequired(d.reason, d.closedAt)
            }
            is SendDecision.Refused -> {
                meters.counter("inbox.send.refused", "reason", "refused").increment()
                return SendResult.Refused(d.reason)
            }
        }
        val charge = billing.charge(state, kind, category, now)
        val id = Ids.new("msg")
        c.update(
            "INSERT INTO message (id, workspace_id, conversation_id, direction, channel, account_id, author, author_id, body, kind, template_name, " +
                "status, status_at, billable, charge_category, sent_at, idempotency_key) VALUES (?, ?, ?, 'OUTBOUND', ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?, ?, ?)",
            id,
            workspaceId,
            conversationId,
            state.channel,
            accountId,
            author,
            authorId,
            body,
            kind,
            req.template,
            now,
            charge.billable,
            charge.category,
            now,
            idempotencyKey,
        )
        c.update("INSERT INTO outbound_job (message_id, account_id, recipient, not_before) VALUES (?, ?, ?, ?)", id, accountId, recipient, now)
        c.update(
            "UPDATE conversation SET last_message_at = greatest(last_message_at, ?), " +
                "first_human_response_at = CASE WHEN ? = 'HUMAN' AND first_human_response_at IS NULL THEN ? ELSE first_human_response_at END, " +
                "ai_replies = ai_replies + CASE WHEN ? = 'AI' THEN 1 ELSE 0 END, version = version + 1 WHERE id = ?",
            now,
            author,
            now,
            author,
            conversationId,
        )
        meters.counter("inbox.send.queued", "channel", state.channel.name, "author", author.name, "billable", charge.billable.toString()).increment()
        return SendResult.Queued(id, charge.billable, charge.category, false)
    }
}
