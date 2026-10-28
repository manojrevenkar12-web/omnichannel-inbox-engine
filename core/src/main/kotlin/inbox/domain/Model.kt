package inbox.domain

import java.time.Instant

/** The channels a customer can write on. Each has its own reply rules (see [WindowPolicy]). */
enum class Channel { WHATSAPP, INSTAGRAM, SMS, EMAIL }

enum class Direction { INBOUND, OUTBOUND }

/** Who wrote a message. AI and human replies follow different channel rules. */
enum class Author { CUSTOMER, AI, HUMAN, SYSTEM }

/** How the conversation reached the business: a free entry point changes billing on WhatsApp. */
enum class EntryPoint { ORGANIC, CLICK_TO_WHATSAPP_AD, FACEBOOK_PAGE_BUTTON }

/**
 * Delivery status of an outbound message.
 *
 * Providers report statuses by webhook, and webhooks arrive late, twice and out of order: a
 * `delivered` can arrive before its `sent`, and a retried `sent` can arrive after `read`. The
 * current status therefore only ever moves forward ([merge]); every report is still kept in the
 * history.
 */
enum class DeliveryStatus(val rank: Int, val terminal: Boolean) {
    QUEUED(0, false),
    ACCEPTED(1, false), // the provider took the request and gave us its message id
    SENT(2, false),
    DELIVERED(3, false),
    READ(4, true),
    FAILED(5, true),
    ;

    /**
     * The status after [reported] arrives on a message currently in this status.
     *
     * Forward moves win. A failure overrides anything short of delivery (a provider can accept,
     * report `sent`, and still fail); once a message was delivered, a late `failed` is noise.
     */
    fun merge(reported: DeliveryStatus): DeliveryStatus =
        when {
            this == reported -> this
            this == FAILED -> FAILED
            this == READ -> READ
            reported == FAILED -> if (rank >= DELIVERED.rank) this else FAILED
            reported.rank > rank -> reported
            else -> this
        }

    companion object {
        fun fromProvider(value: String): DeliveryStatus? =
            when (value.lowercase()) {
                "accepted" -> ACCEPTED
                "sent" -> SENT
                "delivered" -> DELIVERED
                "read" -> READ
                "failed", "undelivered" -> FAILED
                else -> null
            }
    }
}

/** What an outbound message is, for the channel's reply rules and for billing. */
enum class MessageKind { FREE_FORM, TEMPLATE }

enum class TemplateCategory { MARKETING, UTILITY, AUTHENTICATION }

data class Template(val name: String, val language: String, val category: TemplateCategory, val body: String)

/** A customer's reply window on one channel: the last inbound message by provider timestamp. */
data class ChannelState(
    val channel: Channel,
    val lastInboundAt: Instant?,
    val entryPoint: EntryPoint = EntryPoint.ORGANIC,
    val entryAt: Instant? = null,
    val optedOut: Boolean = false,
)
