package inbox.agent

import com.fasterxml.jackson.databind.ObjectMapper
import inbox.config.InboxConfig
import inbox.domain.AgentAction
import inbox.domain.AgentDecision
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

data class TurnMessage(val author: String, val text: String, val at: Instant)

data class TurnRequest(
    val conversationId: String,
    val workspaceId: String,
    val channel: String,
    val windowOpen: Boolean,
    val aiRepliesSoFar: Int,
    val messages: List<TurnMessage>,
)

/** The agent service's answer as it comes over the wire. */
data class AgentWire(
    val action: String = "handoff",
    val text: String? = null,
    val reason: String = "",
    val confidence: Double = 0.0,
    val sources: List<String> = emptyList(),
)

/** The AI agent behind a conversation. The service decides; the agent only proposes. */
interface AgentClient {
    fun turn(req: TurnRequest): AgentDecision
}

/**
 * Calls the LangGraph agent service (agent/ in this repository) over HTTP. Any failure becomes a
 * handoff to a person: a customer waiting on a broken agent is worse than a customer waiting in
 * the human queue.
 */
@ApplicationScoped
class HttpAgentClient(private val cfg: InboxConfig, private val mapper: ObjectMapper) : AgentClient {
    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(cfg.agent().timeout()).build() }

    override fun turn(req: TurnRequest): AgentDecision =
        try {
            val body =
                mapOf(
                    "conversation_id" to req.conversationId,
                    "workspace_id" to req.workspaceId,
                    "channel" to req.channel.lowercase(),
                    "window_open" to req.windowOpen,
                    "ai_replies_so_far" to req.aiRepliesSoFar,
                    "messages" to req.messages.map { mapOf("author" to it.author.lowercase(), "text" to it.text, "at" to it.at.toString()) },
                )
            val resp =
                http.send(
                    HttpRequest.newBuilder(URI.create(cfg.agent().url().trimEnd('/') + "/v1/turn"))
                        .timeout(cfg.agent().timeout())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            if (resp.statusCode() != 200) {
                AgentDecision(AgentAction.HANDOFF, null, "agent service answered HTTP ${resp.statusCode()}", 0.0)
            } else {
                val w = mapper.readValue(resp.body(), AgentWire::class.java)
                val action =
                    when (w.action.lowercase()) {
                        "reply" -> AgentAction.REPLY
                        "no_reply" -> AgentAction.NO_REPLY
                        else -> AgentAction.HANDOFF
                    }
                AgentDecision(action, w.text, w.reason, w.confidence, w.sources)
            }
        } catch (e: Exception) {
            AgentDecision(AgentAction.HANDOFF, null, "agent service unavailable: ${e.javaClass.simpleName}", 0.0)
        }
}
