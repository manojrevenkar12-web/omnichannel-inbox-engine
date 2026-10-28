package inbox.service

import inbox.agent.AgentClient
import inbox.agent.TurnMessage
import inbox.agent.TurnRequest
import inbox.config.InboxConfig
import inbox.domain.AgentTurns
import inbox.domain.Author
import inbox.domain.Channel
import inbox.domain.ChannelState
import inbox.domain.EntryPoint
import inbox.domain.WindowPolicy
import inbox.store.Db
import inbox.store.instant
import inbox.store.one
import inbox.store.query
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock

/**
 * Runs the AI agent's due turns. A turn is claimed by clearing its due time; the agent is called
 * outside any transaction; the result is applied only if the customer has not written again in
 * the meantime (otherwise the newer turn answers everything at once and this reply is dropped).
 */
@ApplicationScoped
class Agents(
    private val db: Db,
    private val cfg: InboxConfig,
    private val agent: AgentClient,
    private val send: Send,
    private val assign: Assign,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    private val turns by lazy { AgentTurns(cfg.agent().quiet(), cfg.agent().minConfidence(), cfg.agent().maxReplies()) }

    data class Claimed(val id: String, val workspaceId: String, val aiReplies: Int, val version: Long)

    data class Report(val replies: Int, val handoffs: Int, val stale: Int, val nothing: Int)

    fun drain(limit: Int = 50): Report {
        val now = clock.instant()
        val claimed =
            db.tx { c ->
                val rows =
                    c.query(
                        "SELECT id, workspace_id, ai_replies, version FROM conversation WHERE agent_due_at <= ? AND assignee_kind = 'AI' AND status = 'OPEN' " +
                            "ORDER BY agent_due_at LIMIT ? FOR UPDATE SKIP LOCKED",
                        now,
                        limit,
                    ) { Claimed(it.getString("id"), it.getString("workspace_id"), it.getInt("ai_replies"), it.getLong("version")) }
                rows.forEach { c.update("UPDATE conversation SET agent_due_at = NULL WHERE id = ?", it.id) }
                rows
            }
        var replies = 0
        var handoffs = 0
        var stale = 0
        var nothing = 0
        for (cv in claimed) {
            when (turn(cv)) {
                "reply" -> replies++
                "handoff" -> handoffs++
                "stale" -> stale++
                else -> nothing++
            }
        }
        return Report(replies, handoffs, stale, nothing)
    }

    private fun turn(cv: Claimed): String {
        val now = clock.instant()
        val (state, messages) =
            db.read { c ->
                val st =
                    c.one(
                        "SELECT channel, last_inbound_at, entry_point, entry_at FROM conversation_channel WHERE conversation_id = ? ORDER BY last_inbound_at DESC NULLS LAST LIMIT 1",
                        cv.id,
                    ) { ChannelState(Channel.valueOf(it.getString("channel")), it.instant("last_inbound_at"), EntryPoint.valueOf(it.getString("entry_point")), it.instant("entry_at")) }
                val msgs =
                    c.query(
                        "SELECT author, body, sent_at FROM (SELECT author, body, sent_at, created_at FROM message WHERE conversation_id = ? ORDER BY sent_at DESC, created_at DESC LIMIT 20) t ORDER BY sent_at, created_at",
                        cv.id,
                    ) { TurnMessage(it.getString("author"), it.getString("body"), it.instant("sent_at") ?: now) }
                st to msgs
            }
        if (state == null) return "nothing"
        val windowOpen = WindowPolicy.isOpen(state, now, Author.AI)
        val decision = agent.turn(TurnRequest(cv.id, cv.workspaceId, state.channel.name, windowOpen, cv.aiReplies, messages))
        return db.tx { c ->
            // the customer wrote again while the agent was thinking: the next turn answers both
            val current = c.one("SELECT version, assignee_kind, agent_due_at FROM conversation WHERE id = ? FOR UPDATE", cv.id) {
                Triple(it.getLong("version"), it.getString("assignee_kind"), it.instant("agent_due_at"))
            }
            if (current == null || current.second != "AI" || current.first != cv.version || current.third != null) {
                meters.counter("inbox.agent.turns", "outcome", "stale").increment()
                return@tx "stale"
            }
            when (val o = turns.apply(decision, windowOpen, cv.aiReplies)) {
                is AgentTurns.Outcome.Send ->
                    when (val r = send.submit(c, cv.workspaceId, cv.id, SendRequest(text = o.text), Author.AI, null, null)) {
                        is SendResult.Queued -> {
                            meters.counter("inbox.agent.turns", "outcome", "reply").increment()
                            "reply"
                        }
                        else -> {
                            assign.handoff(c, cv.id, "the agent's reply was not allowed: $r")
                            meters.counter("inbox.agent.turns", "outcome", "handoff").increment()
                            "handoff"
                        }
                    }
                is AgentTurns.Outcome.Handoff -> {
                    assign.handoff(c, cv.id, o.reason)
                    meters.counter("inbox.agent.turns", "outcome", "handoff").increment()
                    "handoff"
                }
                AgentTurns.Outcome.Nothing -> {
                    meters.counter("inbox.agent.turns", "outcome", "nothing").increment()
                    "nothing"
                }
            }
        }
    }
}
