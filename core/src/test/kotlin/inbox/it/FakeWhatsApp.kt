package inbox.it

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import inbox.channels.Signatures
import inbox.service.JsonCustomizer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * A WhatsApp Cloud API that fails the way the real one does, for drills.
 *
 * Sending (`POST /{version}/{phone-number-id}/messages`) enforces the per-number throughput
 * (130429), the per-recipient pair rate (131056) and the 24-hour window for free-form messages
 * (131047). It also fails on purpose: server errors, and requests it accepts but answers too late
 * for the client to see. Every accepted message then gets status webhooks (sent, delivered, read)
 * signed like Meta's, delivered with duplicates, out of order, and sometimes before the send's own
 * response. The ledger records what the customer actually received; drills judge from it.
 */
class FakeWhatsApp(private val appWebhookUrl: String, private val appSecret: String) {
    data class Faults(
        val throughputMps: Int = 80,
        val pairGapMs: Long = 250,
        val serverErrorRate: Double = 0.0,
        val acceptThenTimeoutRate: Double = 0.0,
        val lateResponseMs: Long = 1500,
        val duplicateStatusRate: Double = 0.0,
        val reorderStatusRate: Double = 0.0,
        val earlyStatusRate: Double = 0.0,
        val readRate: Double = 0.6,
        val seed: Int = 7,
    )

    data class Delivered(val wamid: String, val callbackData: String?, val recipient: String, val type: String, val text: String?, val at: Instant)

    private val mapper: ObjectMapper = JsonCustomizer.standalone()
    private val http: HttpClient = HttpClient.newHttpClient()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 200)
    private val webhookPool: ScheduledExecutorService = Executors.newScheduledThreadPool(4)
    private val seq = AtomicInteger()

    @Volatile var faults = Faults()
        set(value) {
            field = value
            rnd = Random(value.seed)
        }

    @Volatile private var rnd = Random(7)

    val ledger: MutableList<Delivered> = Collections.synchronizedList(mutableListOf())
    val rejections = ConcurrentHashMap<String, AtomicInteger>()
    val acceptedLate = AtomicInteger()
    val serverErrors = AtomicInteger()

    /** Every status webhook in the order it was sent, per message. */
    val statusOrder = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    private val acceptTimes = ConcurrentLinkedQueue<Long>()
    private val lastToRecipient = ConcurrentHashMap<String, Long>()
    private val lastInbound = ConcurrentHashMap<String, Instant>()
    private val pendingWebhooks = AtomicInteger()

    val port: Int get() = server.address.port

    fun start(): FakeWhatsApp {
        server.executor = Executors.newFixedThreadPool(32)
        server.createContext("/") { ex -> handle(ex) }
        server.start()
        return this
    }

    fun stop() {
        server.stop(0)
        webhookPool.shutdownNow()
    }

    fun reset(f: Faults = Faults()) {
        faults = f
        ledger.clear()
        rejections.clear()
        acceptedLate.set(0)
        serverErrors.set(0)
        statusOrder.clear()
        acceptTimes.clear()
        lastToRecipient.clear()
        lastInbound.clear()
    }

    /** The drill tells the provider when a customer wrote, so it can enforce the window itself. */
    fun customerWrote(waId: String, at: Instant) {
        lastInbound.merge(waId, at) { a, b -> if (a.isAfter(b)) a else b }
    }

    fun idle(): Boolean = pendingWebhooks.get() == 0

    fun rejected(code: String): Int = rejections[code]?.get() ?: 0

    /** Highest number of accepted sends in any one-second window. */
    fun peakMps(): Int {
        val times = acceptTimes.toList().sorted()
        var best = 0
        var j = 0
        for (i in times.indices) {
            while (times[i] - times[j] >= 1000) j++
            best = maxOf(best, i - j + 1)
        }
        return best
    }

    private fun reply(ex: HttpExchange, status: Int, body: Any) {
        val bytes = mapper.writeValueAsBytes(body)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun error(ex: HttpExchange, http: Int, code: Int, msg: String) {
        rejections.computeIfAbsent(code.toString()) { AtomicInteger() }.incrementAndGet()
        reply(ex, http, mapOf("error" to mapOf("message" to msg, "type" to "OAuthException", "code" to code)))
    }

    private fun handle(ex: HttpExchange) {
        try {
            if (ex.requestMethod != "POST" || !ex.requestURI.path.endsWith("/messages")) return reply(ex, 404, mapOf("error" to "not found"))
            val phoneNumberId = ex.requestURI.path.split("/").dropLast(1).last()
            val req = mapper.readTree(ex.requestBody.readAllBytes())
            val to = req.path("to").asText()
            val type = req.path("type").asText()
            val now = System.currentTimeMillis()
            val f = faults
            val r = synchronized(this) { rnd.nextDouble() }
            if (r < f.serverErrorRate) {
                serverErrors.incrementAndGet()
                return error(ex, 500, 131000, "Something went wrong")
            }
            synchronized(this) {
                // throughput: accepted sends in the last second
                while (acceptTimes.isNotEmpty() && now - acceptTimes.peek() >= 1000) acceptTimes.poll()
                val window = acceptTimes.count { now - it < 1000 }
                if (window >= f.throughputMps) return error(ex, 400, 130429, "Rate limit hit")
                val last = lastToRecipient[to]
                if (last != null && now - last < f.pairGapMs) return error(ex, 400, 131056, "(Business Account, Consumer Account) pair rate limit hit")
                if (type == "text") {
                    val inbound = lastInbound[to]
                    if (inbound == null || Duration.between(inbound, Instant.ofEpochMilli(now)) >= Duration.ofHours(24)) {
                        return error(ex, 400, 131047, "Re-engagement message")
                    }
                }
                acceptTimes.add(now)
                lastToRecipient[to] = now
            }
            val wamid = "wamid.fake" + seq.incrementAndGet()
            val callback = req.path("biz_opaque_callback_data").asText(null)
            ledger += Delivered(wamid, callback, to, type, req.path("text").path("body").asText(null), Instant.ofEpochMilli(now))
            val (late, early) = synchronized(this) { (rnd.nextDouble() < f.acceptThenTimeoutRate) to (rnd.nextDouble() < f.earlyStatusRate) }
            scheduleStatuses(phoneNumberId, wamid, callback, to, early)
            if (late) {
                acceptedLate.incrementAndGet()
                Thread.sleep(f.lateResponseMs)
            }
            reply(ex, 200, mapOf("messaging_product" to "whatsapp", "contacts" to listOf(mapOf("input" to to, "wa_id" to to)), "messages" to listOf(mapOf("id" to wamid))))
        } catch (e: Exception) {
            runCatching { reply(ex, 500, mapOf("error" to (e.message ?: "fake failure"))) }
        } finally {
            ex.close()
        }
    }

    private fun scheduleStatuses(phoneNumberId: String, wamid: String, callback: String?, to: String, early: Boolean) {
        val f = faults
        val chain = mutableListOf("sent", "delivered")
        synchronized(this) {
            if (rnd.nextDouble() < f.readRate) chain += "read"
            if (rnd.nextDouble() < f.reorderStatusRate) chain.shuffle(rnd)
            if (rnd.nextDouble() < f.duplicateStatusRate) chain += chain[rnd.nextInt(chain.size)]
        }
        var delay = if (early) 0L else 30L
        for (s in chain) {
            pendingWebhooks.incrementAndGet()
            val d = delay
            webhookPool.schedule({
                try {
                    postStatus(phoneNumberId, wamid, callback, to, s)
                } finally {
                    pendingWebhooks.decrementAndGet()
                }
            }, d, TimeUnit.MILLISECONDS)
            delay += 15
        }
    }

    private fun postStatus(phoneNumberId: String, wamid: String, callback: String?, to: String, status: String) {
        val st = mutableMapOf<String, Any>("id" to wamid, "status" to status, "timestamp" to Instant.now().epochSecond.toString(), "recipient_id" to to)
        if (callback != null) st["biz_opaque_callback_data"] = callback
        val payload =
            mapOf(
                "object" to "whatsapp_business_account",
                "entry" to listOf(mapOf("id" to "WABA", "changes" to listOf(mapOf("field" to "messages", "value" to mapOf(
                    "messaging_product" to "whatsapp",
                    "metadata" to mapOf("display_phone_number" to "493055501000", "phone_number_id" to phoneNumberId),
                    "statuses" to listOf(st),
                ))))),
            )
        statusOrder.computeIfAbsent(wamid) { ConcurrentLinkedQueue() }.add(status)
        postWebhook(mapper.writeValueAsString(payload))
    }

    /** Signed POST to the app's webhook, retried like Meta does (here: a few quick retries). */
    fun postWebhook(body: String): Int {
        val sig = Signatures.metaSignature(appSecret, body.toByteArray())
        repeat(5) {
            val resp =
                runCatching {
                    http.send(
                        HttpRequest.newBuilder(URI.create(appWebhookUrl))
                            .header("Content-Type", "application/json")
                            .header("X-Hub-Signature-256", sig)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                }.getOrNull()
            if (resp != null && resp.statusCode() == 200) return 200
            Thread.sleep(50)
        }
        return 0
    }
}
