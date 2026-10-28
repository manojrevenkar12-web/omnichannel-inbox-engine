package inbox.providers

import com.fasterxml.jackson.databind.ObjectMapper
import inbox.config.InboxConfig
import inbox.domain.Channel
import jakarta.enterprise.context.ApplicationScoped
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.util.Base64

data class Outbound(
    val messageId: String,
    val channel: Channel,
    val accountAddress: String,
    val recipient: String,
    val text: String?,
    val template: String?,
    val language: String?,
)

/** What a provider said about one send. */
sealed interface ProviderResult {
    data class Accepted(val providerMessageId: String) : ProviderResult

    /** The provider refused; [kind] decides what the sender does next. */
    data class Rejected(val kind: Kind, val code: String, val title: String) : ProviderResult

    /**
     * We do not know whether the provider took the message: the request timed out or the
     * connection broke after it was written. Sending it again may give the customer two copies.
     */
    data class Ambiguous(val reason: String) : ProviderResult

    enum class Kind { THROUGHPUT, PAIR_RATE, WINDOW_CLOSED, RETRYABLE, PERMANENT }
}

interface ProviderClient {
    fun send(o: Outbound): ProviderResult
}

/**
 * WhatsApp Cloud API: `POST /{version}/{phone-number-id}/messages`.
 *
 * Every send carries our message id in `biz_opaque_callback_data`, which WhatsApp echoes in the
 * status webhooks of that message. That is how a send whose response was lost is recognised when
 * its status arrives, instead of being sent a second time.
 */
@ApplicationScoped
class WhatsAppClient(private val cfg: InboxConfig, private val mapper: ObjectMapper) : ProviderClient {
    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(cfg.sender().requestTimeout()).build() }

    fun body(o: Outbound): Map<String, Any?> {
        val base = mutableMapOf<String, Any?>("messaging_product" to "whatsapp", "recipient_type" to "individual", "to" to o.recipient, "biz_opaque_callback_data" to o.messageId)
        if (o.template != null) {
            base["type"] = "template"
            base["template"] = mapOf("name" to o.template, "language" to mapOf("code" to (o.language ?: "en")))
        } else {
            base["type"] = "text"
            base["text"] = mapOf("body" to o.text, "preview_url" to false)
        }
        return base
    }

    override fun send(o: Outbound): ProviderResult {
        val w = cfg.whatsapp()
        val req =
            HttpRequest.newBuilder(URI.create("${w.graphUrl().trimEnd('/')}/${w.apiVersion()}/${o.accountAddress}/messages"))
                .timeout(cfg.sender().requestTimeout())
                .header("Authorization", "Bearer ${w.accessToken()}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body(o))))
                .build()
        val resp =
            try {
                http.send(req, HttpResponse.BodyHandlers.ofString())
            } catch (e: HttpTimeoutException) {
                return ProviderResult.Ambiguous("timeout after ${cfg.sender().requestTimeout()}")
            } catch (e: IOException) {
                return ProviderResult.Ambiguous("connection: ${e.message}")
            }
        return interpret(resp.statusCode(), resp.body())
    }

    fun interpret(status: Int, body: String): ProviderResult {
        val json = runCatching { mapper.readTree(body) }.getOrNull()
        if (status in 200..299) {
            val id = json?.path("messages")?.firstOrNull()?.path("id")?.asText()
            return if (id.isNullOrBlank()) ProviderResult.Ambiguous("2xx without a message id") else ProviderResult.Accepted(id)
        }
        val err = json?.path("error")
        val code = err?.path("code")?.asText()?.takeIf { it.isNotBlank() } ?: status.toString()
        val title = err?.path("message")?.asText() ?: "HTTP $status"
        val kind =
            when {
                code == "130429" || code == "131057" -> ProviderResult.Kind.THROUGHPUT
                code == "131056" -> ProviderResult.Kind.PAIR_RATE
                code == "131047" -> ProviderResult.Kind.WINDOW_CLOSED
                status == 429 || status >= 500 || code == "131000" || code == "1" || code == "2" -> ProviderResult.Kind.RETRYABLE
                else -> ProviderResult.Kind.PERMANENT
            }
        return ProviderResult.Rejected(kind, code, title)
    }
}

/** SMS in the Twilio REST format (form-encoded, basic auth). */
@ApplicationScoped
class SmsClient(private val cfg: InboxConfig, private val mapper: ObjectMapper) : ProviderClient {
    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(cfg.sender().requestTimeout()).build() }

    override fun send(o: Outbound): ProviderResult {
        val s = cfg.sms()
        val form =
            mapOf("To" to o.recipient, "From" to o.accountAddress, "Body" to (o.text ?: ""), "StatusCallback" to s.publicUrl())
                .entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, Charsets.UTF_8) }
        val auth = Base64.getEncoder().encodeToString("${s.accountSid()}:${s.authToken()}".toByteArray())
        val req =
            HttpRequest.newBuilder(URI.create("${s.apiUrl().trimEnd('/')}/2010-04-01/Accounts/${s.accountSid()}/Messages.json"))
                .timeout(cfg.sender().requestTimeout())
                .header("Authorization", "Basic $auth")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
        val resp =
            try {
                http.send(req, HttpResponse.BodyHandlers.ofString())
            } catch (e: HttpTimeoutException) {
                return ProviderResult.Ambiguous("timeout")
            } catch (e: IOException) {
                return ProviderResult.Ambiguous("connection: ${e.message}")
            }
        val json = runCatching { mapper.readTree(resp.body()) }.getOrNull()
        return when {
            resp.statusCode() in 200..299 -> json?.path("sid")?.asText()?.takeIf { it.isNotBlank() }?.let { ProviderResult.Accepted(it) } ?: ProviderResult.Ambiguous("no sid")
            resp.statusCode() == 429 || resp.statusCode() >= 500 -> ProviderResult.Rejected(ProviderResult.Kind.RETRYABLE, resp.statusCode().toString(), "HTTP ${resp.statusCode()}")
            else -> ProviderResult.Rejected(ProviderResult.Kind.PERMANENT, json?.path("code")?.asText() ?: resp.statusCode().toString(), json?.path("message")?.asText() ?: "rejected")
        }
    }
}

/**
 * Channels whose sending is not implemented in this repository (email via SES, Instagram send API)
 * refuse plainly instead of pretending: the message is marked failed with this reason.
 */
@ApplicationScoped
class UnsupportedClient : ProviderClient {
    override fun send(o: Outbound): ProviderResult =
        ProviderResult.Rejected(ProviderResult.Kind.PERMANENT, "not_implemented", "sending on ${o.channel.name.lowercase()} is not implemented in this service")
}

@ApplicationScoped
class Providers(private val whatsapp: WhatsAppClient, private val sms: SmsClient, private val unsupported: UnsupportedClient) {
    fun forChannel(channel: Channel): ProviderClient =
        when (channel) {
            Channel.WHATSAPP -> whatsapp
            Channel.SMS -> sms
            Channel.EMAIL, Channel.INSTAGRAM -> unsupported
        }
}
