package inbox.domain

import java.time.Duration
import java.time.Instant

/**
 * Token bucket for a sender's throughput (WhatsApp: 80 messages per second per business number by
 * default). Time is passed in, so the bucket is a pure function of the calls made on it.
 *
 * When the provider still answers "too many requests" (130429), [penalise] halves the rate for a
 * while: the provider's limit is the truth, our configured number is a belief about it.
 */
class TokenBucket(private var ratePerSecond: Double, private val burst: Double = ratePerSecond) {
    private var tokens = burst
    private var last: Instant? = null
    private var penaltyUntil: Instant? = null
    private val configuredRate = ratePerSecond

    val rate: Double get() = ratePerSecond

    private fun refill(now: Instant) {
        penaltyUntil?.let {
            if (!now.isBefore(it)) {
                ratePerSecond = configuredRate
                penaltyUntil = null
            }
        }
        val prev = last
        if (prev != null && now.isAfter(prev)) {
            val seconds = Duration.between(prev, now).toNanos() / 1e9
            tokens = minOf(burst, tokens + seconds * ratePerSecond)
        }
        last = now
    }

    /** Take one token if there is one. */
    fun tryTake(now: Instant): Boolean {
        refill(now)
        if (tokens >= 1.0) {
            tokens -= 1.0
            return true
        }
        return false
    }

    /** How long until a token is available (zero if one is available now). */
    fun waitFor(now: Instant): Duration {
        refill(now)
        if (tokens >= 1.0) return Duration.ZERO
        val nanos = ((1.0 - tokens) / ratePerSecond * 1e9).toLong()
        return Duration.ofNanos(maxOf(nanos, 1))
    }

    fun penalise(now: Instant, cooldown: Duration) {
        refill(now)
        ratePerSecond = maxOf(1.0, ratePerSecond / 2)
        tokens = 0.0
        penaltyUntil = now + cooldown
    }
}

/**
 * Spacing between messages to the same recipient. WhatsApp enforces a pair rate limit (error
 * 131056) when a business sends to one user too fast; Meta documents it as roughly one message
 * every six seconds. The next allowed time is stored per recipient in the database, so every
 * sender instance sees it.
 */
object PairSpacing {
    fun nextAllowed(lastSentAt: Instant?, spacing: Duration, now: Instant): Instant =
        if (lastSentAt == null) now else maxOf(now, lastSentAt + spacing)

    fun penalty(now: Instant, spacing: Duration): Instant = now + spacing.multipliedBy(2)
}
