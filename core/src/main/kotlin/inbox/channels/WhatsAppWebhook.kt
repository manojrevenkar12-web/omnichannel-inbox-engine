package inbox.channels

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import inbox.domain.Channel
import inbox.domain.DeliveryStatus
import inbox.domain.EntryPoint
import java.time.Instant

/**
 * Parses WhatsApp Cloud API webhooks (`object: whatsapp_business_account`).
 *
 * Messages are keyed by their `wamid`, statuses by `wamid` and status, so a retried webhook and a
 * webhook that repeats an already reported status produce the same keys. Unknown message types are
 * kept with a placeholder text instead of being dropped: a customer who sends a voice note still
 * opened the reply window.
 */
object WhatsAppWebhook {
    fun parse(mapper: ObjectMapper, raw: String): List<InboundUnit> {
        val root =
            try {
                mapper.readTree(raw)
            } catch (e: Exception) {
                throw InvalidWebhook("not JSON: ${e.message}")
            }
        if (root.path("object").asText() != "whatsapp_business_account") throw InvalidWebhook("not a WhatsApp Business Account webhook")
        val out = mutableListOf<InboundUnit>()
        for (entry in root.path("entry")) {
            for (change in entry.path("changes")) {
                if (change.path("field").asText() != "messages") continue
                val value = change.path("value")
                val phoneNumberId = value.path("metadata").path("phone_number_id").asText()
                if (phoneNumberId.isBlank()) throw InvalidWebhook("missing metadata.phone_number_id")
                val names = value.path("contacts").associate { it.path("wa_id").asText() to it.path("profile").path("name").asText(null) }
                for (m in value.path("messages")) out += message(m, phoneNumberId, names)
                for (s in value.path("statuses")) status(s, phoneNumberId)?.let { out += it }
            }
        }
        return out
    }

    private fun ts(node: JsonNode): Instant {
        val s = node.asText()
        val secs = s.toLongOrNull() ?: throw InvalidWebhook("bad timestamp '$s'")
        return Instant.ofEpochSecond(secs)
    }

    private fun message(m: JsonNode, phoneNumberId: String, names: Map<String, String?>): InboundUnit.Message {
        val id = m.path("id").asText()
        val from = m.path("from").asText()
        if (id.isBlank() || from.isBlank()) throw InvalidWebhook("message without id or from")
        val type = m.path("type").asText()
        val text =
            when (type) {
                "text" -> m.path("text").path("body").asText()
                "button" -> m.path("button").path("text").asText()
                "interactive" -> {
                    val i = m.path("interactive")
                    i.path("button_reply").path("title").asText(null) ?: i.path("list_reply").path("title").asText("")
                }
                "image", "video", "document" -> "[$type] " + m.path(type).path("caption").asText("")
                "audio" -> "[voice message]"
                "location" -> "[location] " + m.path("location").path("name").asText("")
                else -> "[$type]"
            }.trim()
        val referral = m.path("referral")
        val entry = if (referral.path("source_type").asText() == "ad") EntryPoint.CLICK_TO_WHATSAPP_AD else EntryPoint.ORGANIC
        return InboundUnit.Message(
            channel = Channel.WHATSAPP,
            dedupeKey = "wa:msg:$id",
            accountAddress = phoneNumberId,
            providerMessageId = id,
            from = from,
            displayName = names[from],
            text = text,
            sentAt = ts(m.path("timestamp")),
            entryPoint = entry,
            inReplyTo = m.path("context").path("id").asText(null),
        )
    }

    private fun status(s: JsonNode, phoneNumberId: String): InboundUnit.Status? {
        val id = s.path("id").asText()
        val status = DeliveryStatus.fromProvider(s.path("status").asText()) ?: return null
        if (id.isBlank()) throw InvalidWebhook("status without id")
        val err = s.path("errors").firstOrNull()
        return InboundUnit.Status(
            channel = Channel.WHATSAPP,
            dedupeKey = "wa:status:$id:${status.name.lowercase()}",
            accountAddress = phoneNumberId,
            providerMessageId = id,
            status = status,
            at = ts(s.path("timestamp")),
            recipient = s.path("recipient_id").asText(null),
            callbackData = s.path("biz_opaque_callback_data").asText(null),
            errorCode = err?.path("code")?.asText(null),
            errorTitle = err?.path("title")?.asText(null),
        )
    }
}
