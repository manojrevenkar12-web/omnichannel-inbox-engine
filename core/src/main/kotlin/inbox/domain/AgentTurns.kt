package inbox.domain

import java.time.Duration
import java.time.Instant

/** What the AI agent decided for a turn. */
enum class AgentAction { REPLY, HANDOFF, NO_REPLY }

data class AgentDecision(
    val action: AgentAction,
    val text: String?,
    val reason: String,
    val confidence: Double,
    val sources: List<String> = emptyList(),
)

/**
 * When the AI agent answers, and whether its answer is sent.
 *
 * Customers write in bursts ("hi" / "my order" / "where is it?"). Answering each fragment makes
 * three billed messages and three half answers, so a turn is due only after the customer has been
 * quiet for [quiet]. One turn produces at most one message. The agent stops when a person owns the
 * conversation, and hands over when it is unsure, when the window closed, or when it already sent
 * [maxRepliesPerConversation] replies without resolving anything.
 */
class AgentTurns(
    val quiet: Duration,
    private val minConfidence: Double,
    private val maxRepliesPerConversation: Int,
) {
    fun dueAt(lastInboundArrivedAt: Instant): Instant = lastInboundArrivedAt + quiet

    sealed interface Outcome {
        data class Send(val text: String) : Outcome

        data class Handoff(val reason: String) : Outcome

        data object Nothing : Outcome
    }

    fun apply(decision: AgentDecision, windowOpen: Boolean, aiRepliesSoFar: Int): Outcome =
        when {
            decision.action == AgentAction.HANDOFF -> Outcome.Handoff(decision.reason)
            decision.action == AgentAction.NO_REPLY -> Outcome.Nothing
            !windowOpen -> Outcome.Handoff("the reply window closed before the agent answered")
            decision.text.isNullOrBlank() -> Outcome.Handoff("the agent produced no text")
            decision.confidence < minConfidence -> Outcome.Handoff("agent confidence ${"%.2f".format(decision.confidence)} below $minConfidence")
            aiRepliesSoFar >= maxRepliesPerConversation -> Outcome.Handoff("the agent already sent $aiRepliesSoFar replies without resolving it")
            else -> Outcome.Send(decision.text.orEmpty())
        }
}
