package inbox.api

import com.fasterxml.jackson.databind.ObjectMapper
import inbox.channels.EmailWebhook
import inbox.channels.InstagramWebhook
import inbox.channels.InvalidWebhook
import inbox.channels.Signatures
import inbox.channels.SmsWebhook
import inbox.channels.WhatsAppWebhook
import inbox.config.InboxConfig
import inbox.service.Ingest
import io.micrometer.core.instrument.MeterRegistry
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger
import java.time.Clock

/**
 * Provider webhooks. Each endpoint checks the provider's signature on the raw body, splits the
 * payload into facts, stores them once, and answers 200 before doing any other work.
 */
@Path("/webhooks")
class Webhooks(
    private val cfg: InboxConfig,
    private val mapper: ObjectMapper,
    private val ingest: Ingest,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    private val log = Logger.getLogger(Webhooks::class.java)

    private fun rejected(reason: String, status: Response.Status): Response {
        meters.counter("inbox.webhook.rejected", "reason", reason).increment()
        return Response.status(status).entity(mapOf("error" to reason)).type(MediaType.APPLICATION_JSON).build()
    }

    /** Meta's subscription handshake. */
    @GET
    @Path("/whatsapp")
    @Produces(MediaType.TEXT_PLAIN)
    fun verify(
        @QueryParam("hub.mode") mode: String?,
        @QueryParam("hub.verify_token") token: String?,
        @QueryParam("hub.challenge") challenge: String?,
    ): Response =
        if (mode == "subscribe" && token == cfg.whatsapp().verifyToken() && challenge != null) {
            Response.ok(challenge).build()
        } else {
            Response.status(Response.Status.FORBIDDEN).build()
        }

    @POST
    @Path("/whatsapp")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun whatsapp(
        body: String,
        @HeaderParam("X-Hub-Signature-256") signature: String?,
    ): Response {
        if (!Signatures.verifyMeta(cfg.whatsapp().appSecret(), body.toByteArray(Charsets.UTF_8), signature)) return rejected("bad_signature", Response.Status.UNAUTHORIZED)
        return try {
            Response.ok(ingest.accept(WhatsAppWebhook.parse(mapper, body))).build()
        } catch (e: InvalidWebhook) {
            log.warnf("invalid WhatsApp webhook: %s", e.message)
            rejected("invalid", Response.Status.BAD_REQUEST)
        }
    }

    @POST
    @Path("/instagram")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun instagram(
        body: String,
        @HeaderParam("X-Hub-Signature-256") signature: String?,
    ): Response {
        if (!Signatures.verifyMeta(cfg.instagram().appSecret(), body.toByteArray(Charsets.UTF_8), signature)) return rejected("bad_signature", Response.Status.UNAUTHORIZED)
        return try {
            Response.ok(ingest.accept(InstagramWebhook.parse(mapper, body))).build()
        } catch (e: InvalidWebhook) {
            rejected("invalid", Response.Status.BAD_REQUEST)
        }
    }

    @POST
    @Path("/sms")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    fun sms(
        form: MultivaluedMap<String, String>,
        @HeaderParam("X-Twilio-Signature") signature: String?,
    ): Response {
        val params = form.keys.associateWith { form.getFirst(it) ?: "" }
        if (!Signatures.verifySms(cfg.sms().authToken(), cfg.sms().publicUrl(), params, signature)) return rejected("bad_signature", Response.Status.UNAUTHORIZED)
        return try {
            Response.ok(ingest.accept(listOf(SmsWebhook.parse(params, clock.instant())))).build()
        } catch (e: InvalidWebhook) {
            rejected("invalid", Response.Status.BAD_REQUEST)
        }
    }

    /**
     * SES inbound mail through SNS. The endpoint is reached with a secret in the query string and
     * only accepts the configured topic; a subscription confirmation is logged for an operator to
     * confirm, never fetched automatically.
     */
    @POST
    @Path("/email")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    fun email(
        body: String,
        @QueryParam("secret") secret: String?,
    ): Response {
        if (secret != cfg.email().secret()) return rejected("bad_secret", Response.Status.UNAUTHORIZED)
        return try {
            val env = EmailWebhook.envelope(mapper, body)
            if (env.topicArn != cfg.email().topicArn()) return rejected("unknown_topic", Response.Status.FORBIDDEN)
            when (env.type) {
                "SubscriptionConfirmation" -> {
                    log.infof("SNS subscription confirmation for %s: confirm at %s", env.topicArn, env.subscribeUrl)
                    Response.ok(mapOf("confirm" to "manual")).build()
                }
                "Notification" -> Response.ok(ingest.accept(listOf(EmailWebhook.parse(mapper, env.message ?: throw InvalidWebhook("empty SNS message"))))).build()
                else -> rejected("unsupported_type", Response.Status.BAD_REQUEST)
            }
        } catch (e: InvalidWebhook) {
            rejected("invalid", Response.Status.BAD_REQUEST)
        }
    }
}
