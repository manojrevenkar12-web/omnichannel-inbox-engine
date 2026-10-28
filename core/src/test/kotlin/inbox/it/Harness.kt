package inbox.it

import com.fasterxml.jackson.databind.ObjectMapper
import inbox.channels.Signatures
import inbox.service.Agents
import inbox.service.EventProcessor
import inbox.service.Sender
import inbox.store.Db
import inbox.store.one
import io.restassured.RestAssured.given
import io.restassured.response.Response

/** Drives the inbox like production does, but deterministically: workers are called, not scheduled. */
class Harness(
    private val db: Db,
    private val mapper: ObjectMapper,
    private val events: EventProcessor,
    private val sender: Sender,
    private val agents: Agents,
) {
    fun postWhatsApp(payload: Any, secret: String = "dev-app-secret"): Response {
        val body = mapper.writeValueAsString(payload)
        return given()
            .contentType("application/json")
            .header("X-Hub-Signature-256", Signatures.metaSignature(secret, body.toByteArray()))
            .body(body)
            .post("/webhooks/whatsapp")
    }

    fun pending(): Pair<Long, Long> =
        db.read { c ->
            val ev = c.one("SELECT count(*) AS n FROM inbound_event WHERE processed_at IS NULL") { it.getLong("n") } ?: 0
            val ob = c.one("SELECT count(*) AS n FROM outbound_job WHERE state IN ('PENDING', 'AMBIGUOUS', 'IN_FLIGHT')") { it.getLong("n") } ?: 0
            ev to ob
        }

    /**
     * Run every worker until nothing is left: no unprocessed webhook fact, no unsent message, no
     * status webhook still on its way from the provider. Returns false on timeout.
     */
    fun settle(fake: FakeWhatsApp?, timeoutMs: Long = 120_000, agentTurns: Boolean = true): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            events.drain()
            if (agentTurns) agents.drain()
            sender.round()
            if (fake != null) {
                val idleBy = System.currentTimeMillis() + 5_000
                while (!fake.idle() && System.currentTimeMillis() < idleBy) Thread.sleep(10)
            }
            events.drain()
            val (ev, ob) = pending()
            val agentsDue = db.read { c -> c.one("SELECT count(*) AS n FROM conversation WHERE agent_due_at IS NOT NULL AND assignee_kind = 'AI'") { it.getLong("n") } ?: 0 }
            if (ev == 0L && ob == 0L && (fake == null || fake.idle()) && (!agentTurns || agentsDue == 0L)) return true
            Thread.sleep(20)
        }
        return false
    }
}
