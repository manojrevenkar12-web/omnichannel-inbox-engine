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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fault drills against the fake WhatsApp Cloud API. Each drill writes its numbers to
 * build/drills/<name>.json (CI shows the headline) and then asserts the invariants.
 */
@QuarkusTest
@QuarkusTestResource(FakeWhatsAppResource::class)
class DrillTest {
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
    private val rank = mapOf("SENT" to 1, "DELIVERED" to 2, "READ" to 3)

    @BeforeEach
    fun setUp() {
        TestData.reset(db)
        fake.reset()
        agent = ScriptedAgent.faq()
        testAgent.script = agent
        h = Harness(db, mapper, events, sender, agents)
    }

    private fun report(name: String, numbers: Map<String, Any?>, headline: String) {
        val dir = File("build/drills").apply { mkdirs() }
        File(dir, "$name.json").writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(numbers + ("headline" to headline)))
    }

    @Test
    fun `inbound chaos - retried, batched, shuffled and late webhooks`() {
        val rnd = Random(11)
        val customers = List(60) { TestData.waId() }
        val now = Instant.now().minusSeconds(5)
        // each customer writes three messages; the newest is the one that sets the window
        val messages =
            customers.flatMap { wa ->
                fake.customerWrote(wa, now)
                listOf(
                    TestData.whatsappMessage(dentalWa, wa, "wamid.in.$wa.1", "Hi", now.minus(Duration.ofHours(2))),
                    TestData.whatsappMessage(dentalWa, wa, "wamid.in.$wa.2", "I have a question", now.minus(Duration.ofHours(1))),
                    TestData.whatsappMessage(dentalWa, wa, "wamid.in.$wa.3", "when are you open?", now),
                )
            }.shuffled(rnd)
        // Meta batches several messages into one webhook and retries webhooks it thinks failed
        val batches = messages.chunked(4).map { TestData.whatsappBatch(dentalWa, it) }
        val deliveries = (batches + batches.filter { rnd.nextDouble() < 0.35 }).shuffled(rnd)
        val t0 = System.nanoTime()
        for (b in deliveries) h.postWhatsApp(b).then().statusCode(200)
        val ackMs = (System.nanoTime() - t0) / 1e6 / deliveries.size
        events.drain()
        Thread.sleep(300) // the quiet time before an AI turn is 200 ms in tests
        assertTrue(h.settle(fake), "the inbox settled")

        val stored = db.read { c -> c.one("SELECT count(*) AS n FROM message WHERE direction = 'INBOUND'") { it.getInt("n") } } ?: 0
        val conversations = db.read { c -> c.one("SELECT count(*) AS n FROM conversation") { it.getInt("n") } } ?: 0
        val windowWrong =
            db.read { c ->
                c.query(
                    "SELECT customer_address, last_inbound_at FROM conversation_channel WHERE channel = 'WHATSAPP'",
                ) { it.getString(1) to it.getTimestamp(2).toInstant().epochSecond }
            }.count { (_, at) -> at != now.epochSecond }
        val replies = db.read { c -> c.query("SELECT conversation_id FROM message WHERE direction = 'OUTBOUND'") { it.getString(1) } }
        val numbers =
            mapOf(
                "customers" to customers.size,
                "messages_sent_by_customers" to messages.size,
                "webhook_deliveries" to deliveries.size,
                "redelivered_webhooks" to deliveries.size - batches.size,
                "messages_stored" to stored,
                "lost" to messages.size - stored.coerceAtMost(messages.size),
                "duplicates" to (stored - messages.size).coerceAtLeast(0),
                "conversations" to conversations,
                "windows_not_at_newest_message" to windowWrong,
                "agent_turns" to agent.turns.size,
                "ai_replies" to replies.size,
                "conversations_with_more_than_one_reply" to replies.groupingBy { it }.eachCount().count { it.value > 1 },
                "mean_webhook_ack_ms" to Math.round(ackMs * 10) / 10.0,
            )
        report(
            "inbound",
            numbers,
            "${messages.size} messages in ${deliveries.size} shuffled webhooks (${deliveries.size - batches.size} redelivered): " +
                "lost ${numbers["lost"]}, duplicates ${numbers["duplicates"]}, windows wrong $windowWrong; " +
                "${agent.turns.size} AI turns and ${replies.size} replies for ${customers.size} customers, not ${messages.size}.",
        )
        assertEquals(messages.size, stored)
        assertEquals(customers.size, conversations)
        assertEquals(0, windowWrong)
        assertEquals(customers.size, agent.turns.size)
        assertEquals(customers.size, replies.size)
    }

    @Test
    fun `sending under provider limits and failures`() {
        fake.reset(
            FakeWhatsApp.Faults(
                serverErrorRate = 0.03,
                acceptThenTimeoutRate = 0.03,
                duplicateStatusRate = 0.15,
                reorderStatusRate = 0.15,
                earlyStatusRate = 0.10,
                seed = 23,
            ),
        )
        val customers = List(300) { TestData.waId() }
        val closed = List(10) { TestData.waId() }
        val now = Instant.now()
        val inbound =
            customers.mapIndexed { i, wa ->
                fake.customerWrote(wa, now)
                TestData.whatsappMessage(dentalWa, wa, "wamid.s.$i", "ok", now)
            } +
                closed.mapIndexed { i, wa ->
                    fake.customerWrote(wa, now.minus(Duration.ofHours(30)))
                    TestData.whatsappMessage(dentalWa, wa, "wamid.c.$i", "thanks", now.minus(Duration.ofHours(30)))
                }
        inbound.chunked(20).forEach { h.postWhatsApp(TestData.whatsappBatch(dentalWa, it)).then().statusCode(200) }
        events.drain()
        val conv =
            db.read { c ->
                c.query(
                    "SELECT ci.address, cv.id FROM conversation cv JOIN contact_identity ci ON ci.contact_id = cv.contact_id",
                ) { it.getString(1) to it.getString(2) }
            }.toMap()

        // one message to each of 300 customers (a campaign the throughput limit has to pace), and
        // three at once to a hundred of them (a burst the pair rate limit has to space out)
        val ids = mutableListOf<String>()
        for (round in 1..3) {
            for (wa in if (round == 1) customers else customers.take(100)) {
                val r =
                    given().header("Authorization", "Bearer demo-anna").header("Idempotency-Key", "drill-$wa-$round").contentType("application/json")
                        .body("""{"text":"Message $round about your appointment."}""").post("/api/conversations/${conv[wa]}/messages")
                r.then().statusCode(202)
                ids += r.jsonPath().getString("messageId")
            }
        }
        var refused = 0
        for (wa in closed) {
            val code =
                given().header("Authorization", "Bearer demo-anna").contentType("application/json").body("""{"text":"Are you still there?"}""")
                    .post("/api/conversations/${conv[wa]}/messages").statusCode
            if (code == 409) refused++
        }
        val started = System.nanoTime()
        assertTrue(h.settle(fake, timeoutMs = 180_000, agentTurns = false), "the inbox settled")
        val seconds = (System.nanoTime() - started) / 1e9

        val byCallback = fake.ledger.groupBy { it.callbackData }
        val lost = ids.count { byCallback[it].isNullOrEmpty() }
        val duplicates = ids.sumOf { ((byCallback[it]?.size ?: 0) - 1).coerceAtLeast(0) }
        val wamidOf = fake.ledger.associate { it.callbackData to it.wamid }
        val dbStatus = db.read { c -> c.query("SELECT id, status FROM message WHERE direction = 'OUTBOUND'") { it.getString(1) to it.getString(2) } }.toMap()
        var mismatches = 0
        var lastWriteWinsWrong = 0
        for (id in ids) {
            val order = fake.statusOrder[wamidOf[id]]?.map { it.uppercase() } ?: continue
            val truth = order.maxBy { rank[it] ?: 0 }
            if (dbStatus[id] != truth) mismatches++
            if (order.last() != truth) lastWriteWinsWrong++
        }
        val numbers =
            mapOf(
                "messages" to ids.size,
                "customers" to customers.size,
                "lost" to lost,
                "duplicates_delivered" to duplicates,
                "status_mismatches" to mismatches,
                "statuses_last_write_wins_would_get_wrong" to lastWriteWinsWrong,
                "accepted_but_answered_too_late" to fake.acceptedLate.get(),
                "duplicates_a_blind_retry_would_have_sent" to fake.acceptedLate.get(),
                "server_errors_retried" to fake.serverErrors.get(),
                "rejected_130429_throughput" to fake.rejected("130429"),
                "rejected_131056_pair_rate" to fake.rejected("131056"),
                "rejected_131047_window" to fake.rejected("131047"),
                "peak_messages_per_second" to fake.peakMps(),
                "closed_window_sends_refused_before_the_provider" to refused,
                "seconds_to_drain" to Math.round(seconds * 10) / 10.0,
            )
        report(
            "sending",
            numbers,
            "${ids.size} messages to ${customers.size} customers under 3% 5xx and 3% accept-then-timeout: lost $lost, duplicates $duplicates " +
                "(a blind retry: ${fake.acceptedLate.get()}), status mismatches $mismatches (last-write-wins: $lastWriteWinsWrong), " +
                "peak ${fake.peakMps()}/s, 131047 rejections ${fake.rejected("131047")}, $refused closed-window sends refused before sending.",
        )
        assertEquals(0, lost)
        assertEquals(0, duplicates)
        assertEquals(0, mismatches)
        assertEquals(0, fake.rejected("131047"))
        assertTrue(fake.peakMps() <= 80)
        assertEquals(closed.size, refused)
    }
}
