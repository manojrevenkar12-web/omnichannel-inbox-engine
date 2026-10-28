package inbox.service

import inbox.config.InboxConfig
import inbox.domain.Channel
import inbox.domain.PairSpacing
import inbox.domain.TokenBucket
import inbox.providers.Outbound
import inbox.providers.ProviderResult
import inbox.providers.Providers
import inbox.store.Db
import inbox.store.instant
import inbox.store.one
import inbox.store.query
import inbox.store.str
import inbox.store.update
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Drains the outbox: sends queued messages within the provider's limits.
 *
 * * Throughput: a token bucket per business number (80 msg/s on WhatsApp by default), halved for a
 *   while when the provider still says 130429.
 * * Pair rate: messages to one customer are spaced ([InboxConfig.Sender.pairSpacing]); a 131056
 *   doubles the spacing for that customer.
 * * Ambiguous sends (timeout, broken connection) are not retried at once. The status webhook of
 *   that message, matched by its callback data, settles it; only after
 *   [InboxConfig.Sender.ambiguousWait] of silence is it sent again.
 * * Different customers are sent in parallel ([InboxConfig.Sender.concurrency]); one customer's
 *   messages in order.
 * * Each business number is sent by exactly one instance (`hashtext(account) % instances`), so the
 *   bucket can live in memory without two instances spending the same budget.
 */
@ApplicationScoped
class Sender(private val db: Db, private val cfg: InboxConfig, private val providers: Providers, private val clock: Clock, private val meters: MeterRegistry) {
    private val buckets = ConcurrentHashMap<String, TokenBucket>()
    private val pool: ExecutorService = Executors.newFixedThreadPool(cfg.sender().concurrency()) { r -> Thread(r, "inbox-sender").apply { isDaemon = true } }

    @PreDestroy
    fun stop() {
        pool.shutdownNow()
    }

    data class Job(
        val messageId: String,
        val accountId: String,
        val accountAddress: String,
        val throughput: Int,
        val channel: Channel,
        val recipient: String,
        val state: String,
        val attempts: Int,
        val body: String,
        val template: String?,
        val providerMessageId: String?,
        val workspaceId: String,
    )

    data class Report(val sent: Int, val rescheduled: Int, val failed: Int, val ambiguous: Int)

    fun drain(maxRounds: Int = 1000): Report {
        var total = Report(0, 0, 0, 0)
        repeat(maxRounds) {
            val r = round()
            total = Report(total.sent + r.sent, total.rescheduled + r.rescheduled, total.failed + r.failed, total.ambiguous + r.ambiguous)
            if (r.sent + r.failed + r.ambiguous == 0) return total
        }
        return total
    }

    /** One pass over due jobs. Returns what happened, so callers can tell when nothing is left to do. */
    fun round(): Report {
        val s = cfg.sender()
        val now = clock.instant()
        val jobs =
            db.tx { c ->
                val claimed =
                    c.query(
                        "SELECT j.message_id, j.account_id, a.address, a.throughput_mps, a.channel, j.recipient, j.state, j.attempts, " +
                            "m.body, m.template_name, m.provider_message_id, m.workspace_id " +
                            "FROM outbound_job j JOIN channel_account a ON a.id = j.account_id JOIN message m ON m.id = j.message_id " +
                            "WHERE j.state IN ('PENDING', 'AMBIGUOUS') AND j.not_before <= ? AND mod(abs(hashtext(j.account_id)::bigint), ?) = ? " +
                            "ORDER BY j.not_before, j.message_id LIMIT ? FOR UPDATE OF j SKIP LOCKED",
                        now,
                        s.instances(),
                        s.instance(),
                        s.batch(),
                    ) {
                        Job(
                            it.getString("message_id"), it.getString("account_id"), it.getString("address"), it.getInt("throughput_mps"),
                            Channel.valueOf(it.getString("channel")), it.getString("recipient"), it.getString("state"), it.getInt("attempts"),
                            it.getString("body"), it.str("template_name"), it.str("provider_message_id"), it.getString("workspace_id"),
                        )
                    }
                claimed.forEach { c.update("UPDATE outbound_job SET state = 'IN_FLIGHT', updated_at = ? WHERE message_id = ?", now, it.messageId) }
                claimed
            }
        var sent = 0
        var rescheduled = 0
        var failed = 0
        var ambiguous = 0
        // Customers in parallel, each customer's messages in order: one slow send must not hold up
        // the number's whole budget, and two sends to one customer must not race the pair limit.
        val outcomes =
            jobs.groupBy { it.recipient }.values
                .map { mine -> pool.submit<List<String>> { mine.map { process(it) } } }
                .flatMap { it.get() }
        for (o in outcomes) {
            when (o) {
                "sent" -> sent++
                "failed" -> failed++
                "ambiguous" -> ambiguous++
                else -> rescheduled++
            }
        }
        return Report(sent, rescheduled, failed, ambiguous)
    }

    private fun reschedule(j: Job, at: Instant, reason: String, countAttempt: Boolean) {
        db.tx { c ->
            c.update(
                "UPDATE outbound_job SET state = 'PENDING', not_before = ?, attempts = attempts + ?, last_error = ?, updated_at = now() WHERE message_id = ? AND state = 'IN_FLIGHT'",
                at,
                if (countAttempt) 1 else 0,
                reason,
                j.messageId,
            )
        }
        meters.counter("inbox.send.rescheduled", "reason", reason.substringBefore(':')).increment()
    }

    private fun fail(j: Job, code: String, title: String) {
        val now = clock.instant()
        db.tx { c ->
            c.update("UPDATE outbound_job SET state = 'FAILED', last_error = ?, updated_at = ? WHERE message_id = ?", "$code $title", now, j.messageId)
            c.update("UPDATE message SET status = 'FAILED', status_at = ?, error_code = ?, error_title = ? WHERE id = ? AND status NOT IN ('DELIVERED', 'READ')", now, code, title, j.messageId)
        }
        meters.counter("inbox.send.failed", "code", code).increment()
    }

    private fun process(j: Job): String {
        val s = cfg.sender()
        val now = clock.instant()
        // an ambiguous send that a status webhook settled while we waited
        if (j.state == "AMBIGUOUS" && j.providerMessageId != null) {
            db.tx { c -> c.update("UPDATE outbound_job SET state = 'DONE', updated_at = ? WHERE message_id = ?", now, j.messageId) }
            meters.counter("inbox.send.ambiguous_settled_by_webhook").increment()
            return "settled"
        }
        if (j.state == "AMBIGUOUS") meters.counter("inbox.send.ambiguous_resent").increment()
        if (j.attempts >= s.maxAttempts()) {
            fail(j, "max_attempts", "gave up after ${j.attempts} attempts")
            return "failed"
        }
        // pair spacing, shared through the database
        val nextAllowed =
            db.read { c ->
                c.one(
                    "SELECT next_allowed_at FROM contact_identity WHERE workspace_id = ? AND channel = ? AND address = ?",
                    j.workspaceId,
                    j.channel,
                    j.recipient,
                ) { it.instant("next_allowed_at") }
            }
        if (nextAllowed != null && now.isBefore(nextAllowed)) {
            reschedule(j, nextAllowed, "pair_spacing", false)
            return "rescheduled"
        }
        // burst + one second of refill = our share of the provider's limit, in any sliding second
        val bucket =
            buckets.computeIfAbsent(j.accountId) {
                val budget = j.throughput * (1 - s.headroom())
                val burst = maxOf(1.0, budget * s.burstShare())
                TokenBucket(budget - burst, burst)
            }
        val taken = synchronized(bucket) { bucket.tryTake(now) }
        if (!taken) {
            val wait = synchronized(bucket) { bucket.waitFor(now) }
            reschedule(j, now + wait, "throughput", false)
            return "rescheduled"
        }
        val result =
            providers.forChannel(j.channel).send(
                Outbound(j.messageId, j.channel, j.accountAddress, j.recipient, if (j.template == null) j.body else null, j.template, null),
            )
        val after = clock.instant()
        return when (result) {
            is ProviderResult.Accepted -> {
                db.tx { c ->
                    c.update("UPDATE outbound_job SET state = 'DONE', attempts = attempts + 1, updated_at = ? WHERE message_id = ?", after, j.messageId)
                    // keep a provider id a status webhook may already have attached; a status never moves back
                    c.update(
                        "UPDATE message SET provider_message_id = coalesce(provider_message_id, ?), " +
                            "status = CASE WHEN status IN ('QUEUED') THEN 'ACCEPTED' ELSE status END, " +
                            "status_at = CASE WHEN status IN ('QUEUED') THEN ? ELSE status_at END WHERE id = ?",
                        result.providerMessageId,
                        after,
                        j.messageId,
                    )
                    c.update(
                        "UPDATE contact_identity SET last_sent_at = ?, next_allowed_at = ? WHERE workspace_id = ? AND channel = ? AND address = ?",
                        after,
                        PairSpacing.nextAllowed(after, s.pairSpacing(), after),
                        j.workspaceId,
                        j.channel,
                        j.recipient,
                    )
                }
                meters.counter("inbox.send.accepted", "channel", j.channel.name).increment()
                "sent"
            }
            is ProviderResult.Ambiguous -> {
                db.tx { c ->
                    c.update(
                        "UPDATE outbound_job SET state = 'AMBIGUOUS', not_before = ?, attempts = attempts + 1, last_error = ?, updated_at = ? WHERE message_id = ? AND state = 'IN_FLIGHT'",
                        after + s.ambiguousWait(),
                        result.reason,
                        after,
                        j.messageId,
                    )
                }
                meters.counter("inbox.send.ambiguous").increment()
                "ambiguous"
            }
            is ProviderResult.Rejected ->
                when (result.kind) {
                    ProviderResult.Kind.THROUGHPUT -> {
                        synchronized(bucket) { bucket.penalise(after, Duration.ofSeconds(10)) }
                        reschedule(j, after + Duration.ofMillis(200), "throughput_${result.code}", false)
                        "rescheduled"
                    }
                    ProviderResult.Kind.PAIR_RATE -> {
                        val until = PairSpacing.penalty(after, s.pairSpacing())
                        db.tx { c ->
                            c.update(
                                "UPDATE contact_identity SET next_allowed_at = ? WHERE workspace_id = ? AND channel = ? AND address = ?",
                                until,
                                j.workspaceId,
                                j.channel,
                                j.recipient,
                            )
                        }
                        reschedule(j, until, "pair_rate_${result.code}", false)
                        "rescheduled"
                    }
                    ProviderResult.Kind.RETRYABLE -> {
                        val backoff = Duration.ofMillis(minOf(30_000L, 200L shl minOf(j.attempts, 7)))
                        reschedule(j, after + backoff, "retryable_${result.code}", true)
                        "rescheduled"
                    }
                    ProviderResult.Kind.WINDOW_CLOSED, ProviderResult.Kind.PERMANENT -> {
                        fail(j, result.code, result.title)
                        "failed"
                    }
                }
        }
    }

    /** Outbox state, for operations. */
    fun backlog(): Map<String, Long> =
        db.read { c -> c.query("SELECT state, count(*) AS n FROM outbound_job GROUP BY state") { it.getString("state") to it.getLong("n") }.toMap() }
}
