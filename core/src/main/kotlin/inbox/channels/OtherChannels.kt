package inbox.channels

import com.fasterxml.jackson.databind.ObjectMapper
import inbox.domain.Channel
import inbox.domain.DeliveryStatus
import java.time.Instant

/** Instagram messaging webhooks (`object: instagram`): messages only. */
object InstagramWebhook {
    fun parse(mapper: ObjectMapper, raw: String): List<InboundUnit> {
        val root = runCatching { mapper.readTree(raw) }.getOrElse { throw InvalidWebhook("not JSON") }
        if (root.path("object").asText() != "instagram") throw InvalidWebhook("not an Instagram webhook")
        val out = mutableListOf<InboundUnit>()
        for (entry in root.path("entry")) {
            for (ev in entry.path("messaging")) {
                val msg = ev.path("message")
                if (msg.isMissingNode || msg.path("is_echo").asBoolean(false)) continue
                val mid = msg.path("mid").asText()
                if (mid.isBlank()) throw InvalidWebhook("message without mid")
                out +=
                    InboundUnit.Message(
                        channel = Channel.INSTAGRAM,
                        dedupeKey = "ig:msg:$mid",
                        accountAddress = ev.path("recipient").path("id").asText(),
                        providerMessageId = mid,
                        from = ev.path("sender").path("id").asText(),
                        displayName = null,
                        text = msg.path("text").asText(if (msg.has("attachments")) "[attachment]" else ""),
                        sentAt = Instant.ofEpochMilli(ev.path("timestamp").asLong()),
                    )
            }
        }
        return out
    }
}

/**
 * SMS callbacks in the Twilio format (form fields). Inbound messages carry no provider timestamp,
 * so the arrival time stands in; SMS has no reply window for it to matter.
 */
object SmsWebhook {
    fun parse(form: Map<String, String>, receivedAt: Instant): InboundUnit {
        val sid = form["MessageSid"] ?: form["SmsSid"] ?: throw InvalidWebhook("missing MessageSid")
        val statusField = form["MessageStatus"] ?: form["SmsStatus"]
        val to = form["To"] ?: throw InvalidWebhook("missing To")
        if (statusField != null && statusField != "received") {
            val status = DeliveryStatus.fromProvider(statusField) ?: DeliveryStatus.SENT.takeIf { statusField == "queued" || statusField == "sending" }
            if (status != null) {
                return InboundUnit.Status(
                    channel = Channel.SMS,
                    dedupeKey = "sms:status:$sid:${status.name.lowercase()}",
                    accountAddress = form["From"] ?: "",
                    providerMessageId = sid,
                    status = status,
                    at = receivedAt,
                    recipient = to,
                    errorCode = form["ErrorCode"],
                )
            }
        }
        return InboundUnit.Message(
            channel = Channel.SMS,
            dedupeKey = "sms:msg:$sid",
            accountAddress = to,
            providerMessageId = sid,
            from = form["From"] ?: throw InvalidWebhook("missing From"),
            displayName = null,
            text = form["Body"] ?: "",
            sentAt = receivedAt,
        )
    }

    private val STOP = setOf("stop", "stopall", "unsubscribe", "cancel", "end", "quit", "stopp")

    fun isOptOut(text: String): Boolean = text.trim().lowercase() in STOP
}

/**
 * Inbound email delivered by Amazon SES to an SNS topic. The SNS envelope carries the SES
 * notification; SES includes the raw MIME message when the receipt rule asks for it. Only
 * `text/plain` content is read here; HTML-only mail keeps its subject.
 */
object EmailWebhook {
    data class Envelope(val type: String, val subscribeUrl: String?, val topicArn: String?, val message: String?)

    fun envelope(mapper: ObjectMapper, raw: String): Envelope {
        val n = runCatching { mapper.readTree(raw) }.getOrElse { throw InvalidWebhook("not JSON") }
        return Envelope(n.path("Type").asText(), n.path("SubscribeURL").asText(null), n.path("TopicArn").asText(null), n.path("Message").asText(null))
    }

    fun parse(mapper: ObjectMapper, sesNotification: String): InboundUnit.Message {
        val n = runCatching { mapper.readTree(sesNotification) }.getOrElse { throw InvalidWebhook("SES notification is not JSON") }
        val mail = n.path("mail")
        val headers = mail.path("commonHeaders")
        val id = mail.path("messageId").asText()
        if (id.isBlank()) throw InvalidWebhook("SES notification without mail.messageId")
        val from = headers.path("from").firstOrNull()?.asText() ?: mail.path("source").asText()
        val to = mail.path("destination").firstOrNull()?.asText() ?: throw InvalidWebhook("no destination")
        val body = plainText(n.path("content").asText(""))
        return InboundUnit.Message(
            channel = Channel.EMAIL,
            dedupeKey = "email:msg:$id",
            accountAddress = to.lowercase(),
            providerMessageId = id,
            from = address(from),
            displayName = displayName(from),
            text = body.ifBlank { headers.path("subject").asText("") },
            sentAt = runCatching { Instant.parse(mail.path("timestamp").asText()) }.getOrElse { throw InvalidWebhook("bad mail.timestamp") },
            subject = headers.path("subject").asText(null),
            inReplyTo = header(n.path("content").asText(""), "In-Reply-To"),
        )
    }

    fun address(from: String): String = Regex("<([^>]+)>").find(from)?.groupValues?.get(1)?.lowercase() ?: from.trim().lowercase()

    fun displayName(from: String): String? = from.substringBefore("<", "").trim().trim('"').ifBlank { null }

    private fun header(mime: String, name: String): String? =
        mime.lineSequence().takeWhile { it.isNotBlank() }.firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(":")?.trim()

    private val QUOTE_HEADERS = listOf(Regex("^On .+ wrote:$"), Regex("^Am .+ schrieb .+:$"), Regex("^-+ ?Original Message ?-+$", RegexOption.IGNORE_CASE))

    /** The first text/plain part of a MIME message, without quoted history. */
    fun plainText(mime: String): String {
        if (mime.isBlank()) return ""
        val boundary = Regex("boundary=\"?([^\";\\r\\n]+)\"?", RegexOption.IGNORE_CASE).find(mime)?.groupValues?.get(1)
        val part =
            if (boundary == null) {
                mime.substringAfter("\r\n\r\n", mime.substringAfter("\n\n", ""))
            } else {
                mime.split("--$boundary")
                    .firstOrNull { it.contains("text/plain", ignoreCase = true) }
                    ?.let { p -> p.substringAfter("\r\n\r\n", p.substringAfter("\n\n", "")) }
                    ?: ""
            }
        return part.lineSequence()
            .takeWhile { line -> !line.startsWith(">") && QUOTE_HEADERS.none { it.matches(line.trim()) } }
            .joinToString("\n")
            .trim()
    }
}
