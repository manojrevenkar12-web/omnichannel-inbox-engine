package inbox.domain

import java.time.Duration
import java.time.Instant

/** The answer to "may this message be sent now?" with the reason, shown to the person sending it. */
sealed interface SendDecision {
    data object Allowed : SendDecision

    /** Free-form text is not allowed now, but an approved template is. */
    data class TemplateRequired(val reason: String, val closedAt: Instant?) : SendDecision

    data class Refused(val reason: String) : SendDecision
}

/**
 * Channel reply rules, decided before a message is queued so a send never fails at the provider
 * for a reason the platform knew about.
 *
 * * WhatsApp: free-form messages only within 24 hours of the customer's last message (the customer
 *   service window); after that, only approved templates (the provider answers 131047 otherwise).
 *   The window runs from the provider timestamp of the last inbound message, not from when its
 *   webhook arrived: a webhook delayed by an hour must not keep the window open for an hour more.
 * * Instagram: 24 hours for everyone; a human may answer for 7 days with the human-agent tag; an AI
 *   may not use that tag.
 * * SMS: always, unless the customer opted out (STOP).
 * * Email: always.
 */
object WindowPolicy {
    val WHATSAPP_WINDOW: Duration = Duration.ofHours(24)
    val INSTAGRAM_WINDOW: Duration = Duration.ofHours(24)
    val INSTAGRAM_HUMAN_AGENT_WINDOW: Duration = Duration.ofDays(7)

    fun closesAt(state: ChannelState, author: Author = Author.HUMAN): Instant? {
        val last = state.lastInboundAt ?: return null
        return when (state.channel) {
            Channel.WHATSAPP -> last + WHATSAPP_WINDOW
            Channel.INSTAGRAM -> last + if (author == Author.HUMAN) INSTAGRAM_HUMAN_AGENT_WINDOW else INSTAGRAM_WINDOW
            Channel.SMS, Channel.EMAIL -> null
        }
    }

    fun isOpen(state: ChannelState, now: Instant, author: Author = Author.HUMAN): Boolean =
        when (state.channel) {
            Channel.SMS -> !state.optedOut
            Channel.EMAIL -> true
            else -> closesAt(state, author)?.let { now.isBefore(it) } ?: false
        }

    fun decide(state: ChannelState, kind: MessageKind, author: Author, now: Instant): SendDecision {
        if (state.optedOut) return SendDecision.Refused("the customer opted out of ${state.channel.name.lowercase()}")
        return when (state.channel) {
            Channel.EMAIL -> SendDecision.Allowed
            Channel.SMS -> SendDecision.Allowed
            Channel.WHATSAPP ->
                when {
                    kind == MessageKind.TEMPLATE -> SendDecision.Allowed
                    state.lastInboundAt == null ->
                        if (author == Author.AI) {
                            SendDecision.Refused("the customer has not written on WhatsApp; an AI agent does not start conversations")
                        } else {
                            SendDecision.TemplateRequired("the customer has not written on WhatsApp yet", null)
                        }
                    isOpen(state, now) -> SendDecision.Allowed
                    author == Author.AI -> SendDecision.Refused("the 24-hour window closed; an AI agent does not send templates")
                    else -> SendDecision.TemplateRequired("the 24-hour window closed at ${closesAt(state)}", closesAt(state))
                }
            Channel.INSTAGRAM ->
                when {
                    kind == MessageKind.TEMPLATE -> SendDecision.Refused("Instagram has no message templates")
                    isOpen(state, now, author) -> SendDecision.Allowed
                    author == Author.AI -> SendDecision.Refused("the 24-hour window closed; only a person may answer with the human-agent tag")
                    else -> SendDecision.Refused("the 7-day human-agent window closed at ${closesAt(state, author)}")
                }
        }
    }
}
