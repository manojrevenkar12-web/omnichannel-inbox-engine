package inbox.it

import com.fasterxml.jackson.databind.ObjectMapper
import inbox.service.Agents
import inbox.service.EventProcessor
import inbox.service.Sender
import inbox.store.Db
import inbox.store.one
import inbox.store.query
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@QuarkusTest
@QuarkusTestResource(FakeWhatsAppResource::class)
class InboxFlowTest {
    @Inject lateinit var db: Db

    @Inject lateinit var mapper: ObjectMapper

    @Inject lateinit var events: EventProcessor

    @Inject lateinit var sender: Sender

    @Inject lateinit var agents: Agents

    @Inject lateinit var testAgent: TestAgent

    private lateinit var h: Harness
    private lateinit var agent: ScriptedAgent
    private val fake get() = FakeWhatsAppResource.fake
    private val dentalWa = "109000000000001"

    @BeforeEach
    fun setUp() {
        TestData.reset(db)
        fake.reset()
        agent = ScriptedAgent.faq()
        testAgent.script = agent
        h = Harness(db, mapper, events, sender, agents)
    }

    private fun inbound(from: String, id: String, text: String, at: Instant = Instant.now()) {
        fake.customerWrote(from, at)
        h.postWhatsApp(TestData.whatsappBatch(dentalWa, listOf(TestData.whatsappMessage(dentalWa, from, id, text, at)))).then().statusCode(200)
    }

    private fun conversationOf(waId: String): String =
        db.read { c ->
            c.one(
                "SELECT cv.id FROM conversation cv JOIN contact_identity ci ON ci.contact_id = cv.contact_id WHERE ci.address = ? ORDER BY cv.created_at DESC LIMIT 1",
                waId,
            ) { it.getString("id") }
        }!!

    @Test
    fun `webhooks need a valid signature and the subscription handshake checks the token`() {
        h.postWhatsApp(TestData.whatsappBatch(dentalWa, emptyList()), secret = "wrong").then().statusCode(401)
        given().queryParam("hub.mode", "subscribe").queryParam("hub.verify_token", "dev-verify-token").queryParam("hub.challenge", "42")
            .get("/webhooks/whatsapp").then().statusCode(200).body(equalTo("42"))
        given().queryParam("hub.mode", "subscribe").queryParam("hub.verify_token", "nope").queryParam("hub.challenge", "42")
            .get("/webhooks/whatsapp").then().statusCode(403)
    }

    @Test
    fun `a retried webhook stores its message once`() {
        val wa = TestData.waId()
        val batch = TestData.whatsappBatch(dentalWa, listOf(TestData.whatsappMessage(dentalWa, wa, "wamid.retry1", "Hi", Instant.now())))
        h.postWhatsApp(batch).then().statusCode(200).body("stored", equalTo(1))
        h.postWhatsApp(batch).then().statusCode(200).body("duplicates", equalTo(1))
        events.drain()
        val n = db.read { c -> c.one("SELECT count(*) AS n FROM message WHERE provider_message_id = 'wamid.retry1'") { it.getInt("n") } }
        assertEquals(1, n)
    }

    @Test
    fun `the AI answers a burst once, after the customer is quiet, and the answer is delivered`() {
        val wa = TestData.waId()
        val now = Instant.now()
        inbound(wa, "wamid.b1", "Hello", now)
        inbound(wa, "wamid.b2", "my tooth hurts", now.plusSeconds(1))
        inbound(wa, "wamid.b3", "when are you open?", now.plusSeconds(2))
        events.drain()
        Thread.sleep(300) // the configured quiet time in tests is 200 ms
        assertTrue(h.settle(fake))
        val conv = conversationOf(wa)
        val out = db.read { c -> c.query("SELECT author, status, billable FROM message WHERE conversation_id = ? AND direction = 'OUTBOUND'", conv) { Triple(it.getString(1), it.getString(2), it.getBoolean(3)) } }
        assertEquals(1, out.size, "one reply for a burst of three: $out")
        assertEquals("AI", out[0].first)
        assertTrue(out[0].second in setOf("SENT", "DELIVERED", "READ"))
        assertTrue(out[0].third, "service messages are billed per message from October 2026")
        assertEquals(1, agent.turns.size)
        assertEquals(3, agent.turns[0].messages.count { it.author == "CUSTOMER" })
    }

    @Test
    fun `asking for a person hands over to the least busy teammate who is online`() {
        val wa = TestData.waId()
        inbound(wa, "wamid.h1", "I want to talk to a human please")
        events.drain()
        Thread.sleep(300)
        assertTrue(h.settle(fake))
        val (kind, assignee) = db.read { c -> c.one("SELECT assignee_kind, assignee_id FROM conversation WHERE id = ?", conversationOf(wa)) { it.getString(1) to it.getString(2) } }!!
        assertEquals("HUMAN", kind)
        assertTrue(assignee in setOf("tm_anna", "tm_ben"), "cara is offline: $assignee")
    }

    @Test
    fun `a person replies with an idempotency key, and after 24 hours only a template is accepted`() {
        val wa = TestData.waId()
        inbound(wa, "wamid.p1", "ok", Instant.now())
        events.drain()
        val conv = conversationOf(wa)
        val first =
            given().header("Authorization", "Bearer demo-anna").header("Idempotency-Key", "k-1").contentType("application/json")
                .body("""{"text":"See you Thursday at 9."}""").post("/api/conversations/$conv/messages")
        first.then().statusCode(202)
        given().header("Authorization", "Bearer demo-anna").header("Idempotency-Key", "k-1").contentType("application/json")
            .body("""{"text":"See you Thursday at 9."}""").post("/api/conversations/$conv/messages")
            .then().statusCode(200).body("messageId", equalTo(first.jsonPath().getString("messageId")))
        assertTrue(h.settle(fake, agentTurns = false))
        assertEquals(1, fake.ledger.count { it.recipient == wa })

        val old = TestData.waId()
        inbound(old, "wamid.p2", "thanks", Instant.now().minus(Duration.ofHours(25)))
        events.drain()
        val conv2 = conversationOf(old)
        given().header("Authorization", "Bearer demo-anna").contentType("application/json").body("""{"text":"Are you still there?"}""")
            .post("/api/conversations/$conv2/messages").then().statusCode(409).body("error", equalTo("template_required"))
        given().header("Authorization", "Bearer demo-anna").contentType("application/json").body("""{"template":"follow_up","language":"en"}""")
            .post("/api/conversations/$conv2/messages").then().statusCode(202)
        assertTrue(h.settle(fake, agentTurns = false))
        assertEquals(0, fake.rejected("131047"), "no free-form message reached the provider outside the window")
        assertEquals("template", fake.ledger.single { it.recipient == old }.type)
    }

    @Test
    fun `workspaces cannot see each other`() {
        val wa = TestData.waId()
        inbound(wa, "wamid.t1", "Hi")
        events.drain()
        val conv = conversationOf(wa)
        given().header("Authorization", "Bearer demo-nils").get("/api/conversations/$conv").then().statusCode(404)
        given().header("Authorization", "Bearer demo-nils").contentType("application/json").body("""{"text":"hi"}""")
            .post("/api/conversations/$conv/messages").then().statusCode(404)
        given().header("Authorization", "Bearer demo-nils").get("/api/conversations").then().statusCode(200).body("$", hasSize<Any>(0))
        given().header("Authorization", "Bearer demo-anna").get("/api/conversations/$conv").then().statusCode(200).body("summary.id", equalTo(conv))
        given().get("/api/conversations").then().statusCode(401)
    }

    @Test
    fun `health and metrics`() {
        given().get("/q/health/ready").then().statusCode(200)
        given().header("Authorization", "Bearer demo-anna").get("/api/metrics").then().statusCode(200)
        given().get("/q/metrics").then().statusCode(200)
    }
}
