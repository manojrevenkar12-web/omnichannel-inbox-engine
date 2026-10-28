package inbox.service

import inbox.domain.Assignment
import inbox.domain.Teammate
import inbox.store.Db
import inbox.store.one
import inbox.store.query
import inbox.store.update
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped
import java.sql.Connection
import java.time.Clock

/** Moves conversations between the AI agent, the human queue and named teammates. */
@ApplicationScoped
class Assign(private val db: Db, private val clock: Clock, private val meters: MeterRegistry) {
    fun team(c: Connection, workspaceId: String): List<Teammate> =
        c.query(
            "SELECT t.id, t.name, t.online, t.capacity, t.last_assigned_seq, " +
                "(SELECT count(*) FROM conversation cv WHERE cv.assignee_id = t.id AND cv.status = 'OPEN') AS open_n " +
                "FROM teammate t WHERE t.workspace_id = ? ORDER BY t.id",
            workspaceId,
        ) { Teammate(it.getString("id"), it.getString("name"), it.getBoolean("online"), it.getInt("capacity"), it.getInt("open_n"), it.getLong("last_assigned_seq")) }

    /** Hand a conversation from the AI to a person (or to the human queue when nobody is free). */
    fun handoff(c: Connection, conversationId: String, reason: String): String? {
        val now = clock.instant()
        val ws = c.one("SELECT workspace_id FROM conversation WHERE id = ? FOR UPDATE", conversationId) { it.getString("workspace_id") } ?: return null
        // lock the team rows of this workspace so two handoffs do not pick the same last free seat
        c.query("SELECT id FROM teammate WHERE workspace_id = ? ORDER BY id FOR UPDATE", ws) { it.getString("id") }
        val chosen = Assignment.choose(team(c, ws))
        if (chosen != null) {
            c.update(
                "UPDATE teammate SET last_assigned_seq = (SELECT coalesce(max(last_assigned_seq), 0) + 1 FROM teammate WHERE workspace_id = ?) WHERE id = ?",
                ws,
                chosen.id,
            )
        }
        c.update(
            "UPDATE conversation SET assignee_kind = ?, assignee_id = ?, agent_due_at = NULL, handoff_reason = ?, handed_off_at = ?, version = version + 1 WHERE id = ?",
            if (chosen != null) "HUMAN" else "QUEUE",
            chosen?.id,
            reason.take(500),
            now,
            conversationId,
        )
        meters.counter("inbox.handoffs", "to", if (chosen != null) "teammate" else "queue").increment()
        return chosen?.id
    }

    fun assignTo(workspaceId: String, conversationId: String, teammateId: String): Boolean =
        db.tx { c ->
            val ok = c.one("SELECT 1 FROM teammate WHERE id = ? AND workspace_id = ?", teammateId, workspaceId) { 1 } != null
            ok &&
                c.update(
                    "UPDATE conversation SET assignee_kind = 'HUMAN', assignee_id = ?, agent_due_at = NULL, version = version + 1 WHERE id = ? AND workspace_id = ? AND status = 'OPEN'",
                    teammateId,
                    conversationId,
                    workspaceId,
                ) == 1
        }

    fun handBackToAi(workspaceId: String, conversationId: String): Boolean =
        db.tx { c ->
            c.update(
                "UPDATE conversation SET assignee_kind = 'AI', assignee_id = NULL, agent_due_at = NULL, version = version + 1 WHERE id = ? AND workspace_id = ? AND status = 'OPEN'",
                conversationId,
                workspaceId,
            ) == 1
        }

    fun resolve(workspaceId: String, conversationId: String): Boolean =
        db.tx { c ->
            c.update(
                "UPDATE conversation SET status = 'RESOLVED', resolved_at = ?, agent_due_at = NULL, version = version + 1 WHERE id = ? AND workspace_id = ? AND status = 'OPEN'",
                clock.instant(),
                conversationId,
                workspaceId,
            ) == 1
        }
}
