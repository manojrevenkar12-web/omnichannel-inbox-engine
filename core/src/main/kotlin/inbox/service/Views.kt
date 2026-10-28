package inbox.service

import inbox.domain.Author
import inbox.domain.Channel
import inbox.domain.ChannelState
import inbox.domain.EntryPoint
import inbox.domain.WindowPolicy
import inbox.store.Db
import inbox.store.instant
import inbox.store.one
import inbox.store.query
import inbox.store.str
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant

data class WindowView(val channel: String, val open: Boolean, val closesAt: Instant?, val aiMayReply: Boolean, val entryPoint: String, val optedOut: Boolean)

data class ConversationSummary(
    val id: String,
    val contact: String,
    val channels: List<String>,
    val status: String,
    val assigneeKind: String,
    val assigneeId: String?,
    val assigneeName: String?,
    val lastMessage: String?,
    val lastMessageAt: Instant,
    val lastAuthor: String?,
    val unansweredSince: Instant?,
    val window: WindowView?,
)

data class MessageView(
    val id: String,
    val direction: String,
    val channel: String,
    val author: String,
    val authorName: String?,
    val body: String,
    val kind: String,
    val template: String?,
    val status: String,
    val statusAt: Instant?,
    val errorCode: String?,
    val errorTitle: String?,
    val billable: Boolean,
    val chargeCategory: String?,
    val sentAt: Instant,
    val history: List<StatusView>,
)

data class StatusView(val status: String, val at: Instant, val receivedAt: Instant)

data class ConversationDetail(
    val summary: ConversationSummary,
    val windows: List<WindowView>,
    val messages: List<MessageView>,
    val handoffReason: String?,
    val handedOffAt: Instant?,
    val firstHumanResponseAt: Instant?,
    val aiReplies: Int,
    val billableMessages: Int,
)

data class TeammateView(val id: String, val name: String, val online: Boolean, val capacity: Int, val open: Int)

data class TemplateView(val name: String, val language: String, val category: String, val body: String)

data class Metrics(
    val open: Int,
    val withAi: Int,
    val withPeople: Int,
    val waitingInQueue: Int,
    val resolved: Int,
    val handoffs: Int,
    val medianFirstHumanResponseSeconds: Long?,
    val billableMessages: Int,
    val aiMessages: Int,
    val aiMessagesPerConversation: Double?,
    val failedMessages: Int,
)

/** Read models for the console. Every query is scoped to the caller's workspace. */
@ApplicationScoped
class Views(private val db: Db, private val clock: Clock) {
    private fun window(rs: java.sql.ResultSet, now: Instant): WindowView {
        val st =
            ChannelState(
                Channel.valueOf(rs.getString("channel")),
                rs.instant("last_inbound_at"),
                EntryPoint.valueOf(rs.getString("entry_point")),
                rs.instant("entry_at"),
                rs.getBoolean("opted_out"),
            )
        return WindowView(
            st.channel.name,
            WindowPolicy.isOpen(st, now),
            WindowPolicy.closesAt(st),
            WindowPolicy.isOpen(st, now, Author.AI),
            st.entryPoint.name,
            st.optedOut,
        )
    }

    private val windowSql =
        "SELECT cc.channel, cc.last_inbound_at, cc.entry_point, cc.entry_at, coalesce(ci.opted_out, false) AS opted_out " +
            "FROM conversation_channel cc JOIN conversation cv ON cv.id = cc.conversation_id " +
            "LEFT JOIN contact_identity ci ON ci.workspace_id = cv.workspace_id AND ci.channel = cc.channel AND ci.address = cc.customer_address " +
            "WHERE cc.conversation_id = ? ORDER BY cc.last_inbound_at DESC NULLS LAST"

    fun list(workspaceId: String, view: String, me: String, limit: Int = 100, id: String? = null): List<ConversationSummary> {
        val now = clock.instant()
        val filter =
            if (id != null) "cv.id = ?" else when (view) {
                "mine" -> "cv.status = 'OPEN' AND cv.assignee_id = ?"
                "ai" -> "cv.status = 'OPEN' AND cv.assignee_kind = 'AI'"
                "queue" -> "cv.status = 'OPEN' AND cv.assignee_kind IN ('QUEUE', 'NONE')"
                "resolved" -> "cv.status = 'RESOLVED'"
                else -> "cv.status = 'OPEN'"
            }
        return db.read { c ->
            val args = mutableListOf<Any?>(workspaceId)
            if (id != null) args += id else if (view == "mine") args += me
            args += limit
            c.query(
                "SELECT cv.id, cv.status, cv.assignee_kind, cv.assignee_id, t.name AS assignee_name, cv.last_message_at, " +
                    "coalesce(ct.display_name, (SELECT address FROM contact_identity WHERE contact_id = ct.id LIMIT 1)) AS contact, " +
                    "(SELECT body FROM message m WHERE m.conversation_id = cv.id ORDER BY m.sent_at DESC, m.created_at DESC LIMIT 1) AS last_body, " +
                    "(SELECT author FROM message m WHERE m.conversation_id = cv.id ORDER BY m.sent_at DESC, m.created_at DESC LIMIT 1) AS last_author, " +
                    "(SELECT min(m.sent_at) FROM message m WHERE m.conversation_id = cv.id AND m.direction = 'INBOUND' AND m.sent_at > " +
                    "  coalesce((SELECT max(o.sent_at) FROM message o WHERE o.conversation_id = cv.id AND o.direction = 'OUTBOUND'), 'epoch'::timestamptz)) AS unanswered_since " +
                    "FROM conversation cv JOIN contact ct ON ct.id = cv.contact_id LEFT JOIN teammate t ON t.id = cv.assignee_id " +
                    "WHERE cv.workspace_id = ? AND $filter ORDER BY cv.last_message_at DESC LIMIT ?",
                *args.toTypedArray(),
            ) { rs ->
                ConversationSummary(
                    rs.getString("id"), rs.str("contact") ?: "unknown", emptyList(), rs.getString("status"), rs.getString("assignee_kind"),
                    rs.str("assignee_id"), rs.str("assignee_name"), rs.str("last_body"), rs.instant("last_message_at") ?: now, rs.str("last_author"),
                    rs.instant("unanswered_since"), null,
                )
            }.map { s ->
                val windows = c.query(windowSql, s.id) { window(it, now) }
                s.copy(channels = windows.map { it.channel }, window = windows.firstOrNull())
            }
        }
    }

    fun detail(workspaceId: String, id: String, me: String): ConversationDetail? {
        val now = clock.instant()
        val summary = list(workspaceId, "all", me, 1, id).firstOrNull() ?: return null
        return db.read { c ->
            val windows = c.query(windowSql, id) { window(it, now) }
            val history =
                c.query(
                    "SELECT s.message_id, s.status, s.at, s.received_at FROM message_status s JOIN message m ON m.id = s.message_id WHERE m.conversation_id = ? ORDER BY s.id",
                    id,
                ) { it.getString("message_id") to StatusView(it.getString("status"), it.instant("at") ?: now, it.instant("received_at") ?: now) }
                    .groupBy({ it.first }, { it.second })
            val messages =
                c.query(
                    "SELECT m.*, t.name AS author_name FROM message m LEFT JOIN teammate t ON t.id = m.author_id WHERE m.conversation_id = ? ORDER BY m.sent_at, m.created_at",
                    id,
                ) {
                    MessageView(
                        it.getString("id"), it.getString("direction"), it.getString("channel"), it.getString("author"), it.str("author_name"),
                        it.getString("body"), it.getString("kind"), it.str("template_name"), it.getString("status"), it.instant("status_at"),
                        it.str("error_code"), it.str("error_title"), it.getBoolean("billable"), it.str("charge_category"), it.instant("sent_at") ?: now,
                        history[it.getString("id")] ?: emptyList(),
                    )
                }
            val meta =
                c.one("SELECT handoff_reason, handed_off_at, first_human_response_at, ai_replies FROM conversation WHERE id = ?", id) {
                    listOf(it.str("handoff_reason"), it.instant("handed_off_at"), it.instant("first_human_response_at"), it.getInt("ai_replies"))
                }!!
            ConversationDetail(
                summary.copy(channels = windows.map { it.channel }, window = windows.firstOrNull()),
                windows,
                messages,
                meta[0] as String?,
                meta[1] as Instant?,
                meta[2] as Instant?,
                meta[3] as Int,
                messages.count { it.billable },
            )
        }
    }

    fun team(workspaceId: String): List<TeammateView> =
        db.read { c ->
            c.query(
                "SELECT t.id, t.name, t.online, t.capacity, (SELECT count(*) FROM conversation cv WHERE cv.assignee_id = t.id AND cv.status = 'OPEN') AS open_n " +
                    "FROM teammate t WHERE t.workspace_id = ? ORDER BY t.name",
                workspaceId,
            ) { TeammateView(it.getString("id"), it.getString("name"), it.getBoolean("online"), it.getInt("capacity"), it.getInt("open_n")) }
        }

    fun templates(workspaceId: String): List<TemplateView> =
        db.read { c ->
            c.query("SELECT name, language, category, body FROM template WHERE workspace_id = ? ORDER BY name, language", workspaceId) {
                TemplateView(it.getString("name"), it.getString("language"), it.getString("category"), it.getString("body"))
            }
        }

    fun metrics(workspaceId: String): Metrics =
        db.read { c ->
            val m =
                c.one(
                    "SELECT count(*) FILTER (WHERE billable) AS billable_n, count(*) FILTER (WHERE author = 'AI') AS ai_msg_n, " +
                        "count(*) FILTER (WHERE status = 'FAILED') AS failed_n FROM message WHERE workspace_id = ? AND direction = 'OUTBOUND'",
                    workspaceId,
                ) { Triple(it.getInt("billable_n"), it.getInt("ai_msg_n"), it.getInt("failed_n")) }!!
            c.one(
                "SELECT count(*) FILTER (WHERE status = 'OPEN') AS open_n, " +
                    "count(*) FILTER (WHERE status = 'OPEN' AND assignee_kind = 'AI') AS ai_n, " +
                    "count(*) FILTER (WHERE status = 'OPEN' AND assignee_kind = 'HUMAN') AS human_n, " +
                    "count(*) FILTER (WHERE status = 'OPEN' AND assignee_kind IN ('QUEUE', 'NONE')) AS queue_n, " +
                    "count(*) FILTER (WHERE status = 'RESOLVED') AS resolved_n, " +
                    "count(*) FILTER (WHERE handed_off_at IS NOT NULL) AS handoff_n, " +
                    "percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM first_human_response_at - handed_off_at)) " +
                    "  FILTER (WHERE first_human_response_at IS NOT NULL AND handed_off_at IS NOT NULL AND first_human_response_at >= handed_off_at) AS frt, " +
                    "count(*) FILTER (WHERE ai_replies > 0) AS ai_conv_n " +
                    "FROM conversation WHERE workspace_id = ?",
                workspaceId,
            ) {
                val aiConv = it.getInt("ai_conv_n")
                val frt = it.getDouble("frt").let { v -> if (it.wasNull()) null else v.toLong() }
                Metrics(
                    it.getInt("open_n"), it.getInt("ai_n"), it.getInt("human_n"), it.getInt("queue_n"), it.getInt("resolved_n"), it.getInt("handoff_n"),
                    frt, m.first, m.second, if (aiConv > 0) m.second.toDouble() / aiConv else null, m.third,
                )
            }!!
        }
}
