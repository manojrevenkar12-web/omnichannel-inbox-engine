package inbox.domain

import java.time.Duration
import java.time.Instant

/** Whether and why a single outbound message is charged by the channel. */
data class Charge(val billable: Boolean, val category: String, val reason: String)

/**
 * WhatsApp charges per message. Templates are charged by category; a utility template sent while
 * the customer service window is open is free. Service (free-form) messages are charged per
 * message from [serviceBilledFrom] (October 2026), whether a person, an AI agent or an automation
 * sent them. Conversations that started from a free entry point (a Click-to-WhatsApp ad or a
 * Facebook Page button) are free for 72 hours. Other channels are not charged per message here.
 *
 * The point for an AI agent: every message it sends costs money now, so three short replies cost
 * three times one complete reply. See [inbox.domain.AgentTurns].
 */
class Billing(private val serviceBilledFrom: Instant) {
    fun charge(state: ChannelState, kind: MessageKind, template: TemplateCategory?, at: Instant): Charge {
        if (state.channel != Channel.WHATSAPP) return Charge(false, "none", "not charged per message")
        val freeEntry = state.entryPoint != EntryPoint.ORGANIC && state.entryAt != null && at.isBefore(state.entryAt + FREE_ENTRY)
        if (freeEntry) return Charge(false, "free_entry_point", "within 72 hours of a free entry point")
        val windowOpen = WindowPolicy.isOpen(state, at)
        return when (kind) {
            MessageKind.TEMPLATE ->
                when {
                    template == TemplateCategory.UTILITY && windowOpen -> Charge(false, "utility", "utility template inside the service window")
                    else -> Charge(true, (template ?: TemplateCategory.MARKETING).name.lowercase(), "template")
                }
            MessageKind.FREE_FORM ->
                if (at.isBefore(serviceBilledFrom)) {
                    Charge(false, "service", "service messages were free before $serviceBilledFrom")
                } else {
                    Charge(true, "service", "service message")
                }
        }
    }

    companion object {
        val FREE_ENTRY: Duration = Duration.ofHours(72)
    }
}
