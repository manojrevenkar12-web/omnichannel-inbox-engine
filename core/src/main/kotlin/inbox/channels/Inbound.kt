package inbox.channels

import inbox.domain.Channel
import inbox.domain.DeliveryStatus
import inbox.domain.EntryPoint
import java.time.Instant

/**
 * One fact from a provider webhook, normalised across channels.
 *
 * A single webhook can carry several facts (WhatsApp batches messages and statuses), and the same
 * fact can arrive in several webhooks (retries). Each unit therefore has a [dedupeKey] that is
 * identical every time the provider reports the same fact; storing units under a unique key is
 * what makes ingestion idempotent.
 */
sealed interface InboundUnit {
    val channel: Channel
    val dedupeKey: String
    val accountAddress: String

    data class Message(
        override val channel: Channel,
        override val dedupeKey: String,
        override val accountAddress: String,
        val providerMessageId: String,
        val from: String,
        val displayName: String?,
        val text: String,
        val sentAt: Instant,
        val entryPoint: EntryPoint = EntryPoint.ORGANIC,
        val subject: String? = null,
        val inReplyTo: String? = null,
    ) : InboundUnit

    data class Status(
        override val channel: Channel,
        override val dedupeKey: String,
        override val accountAddress: String,
        val providerMessageId: String,
        val status: DeliveryStatus,
        val at: Instant,
        val recipient: String?,
        val callbackData: String? = null,
        val errorCode: String? = null,
        val errorTitle: String? = null,
    ) : InboundUnit
}

class InvalidWebhook(message: String) : RuntimeException(message)
