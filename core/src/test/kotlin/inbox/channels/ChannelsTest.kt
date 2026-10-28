package inbox.channels

import inbox.domain.Channel
import inbox.domain.DeliveryStatus
import inbox.domain.EntryPoint
import inbox.service.JsonCustomizer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WhatsAppWebhookTest {
    private val mapper = JsonCustomizer.standalone()

    private val batch =
        """
        {"object":"whatsapp_business_account","entry":[{"id":"WABA","changes":[{"field":"messages","value":{
          "messaging_product":"whatsapp","metadata":{"display_phone_number":"493055501000","phone_number_id":"109000000000001"},
          "contacts":[{"profile":{"name":"Jonas Weber"},"wa_id":"491701234567"}],
          "messages":[
            {"from":"491701234567","id":"wamid.A","timestamp":"1792490400","type":"text","text":{"body":"Hallo, haben Sie morgen noch einen Termin?"}},
            {"from":"491701234567","id":"wamid.B","timestamp":"1792490460","type":"image","image":{"caption":"mein Zahn"},
             "referral":{"source_type":"ad","source_id":"ad1"}},
            {"from":"491701234567","id":"wamid.C","timestamp":"1792490470","type":"interactive","interactive":{"type":"button_reply","button_reply":{"id":"b1","title":"Ja"}}}
          ],
          "statuses":[
            {"id":"wamid.OUT1","status":"delivered","timestamp":"1792490500","recipient_id":"491701234567","biz_opaque_callback_data":"msg_123"},
            {"id":"wamid.OUT2","status":"failed","timestamp":"1792490510","recipient_id":"491701234567","errors":[{"code":131047,"title":"Re-engagement message"}]},
            {"id":"wamid.OUT3","status":"deleted","timestamp":"1792490520","recipient_id":"491701234567"}
          ]}}]}]}
        """.trimIndent()

    @Test
    fun `a batch becomes one fact per message and status, with stable keys`() {
        val units = WhatsAppWebhook.parse(mapper, batch)
        assertEquals(5, units.size) // the unknown 'deleted' status is ignored
        val a = units[0]
        assertIs<InboundUnit.Message>(a)
        assertEquals("wa:msg:wamid.A", a.dedupeKey)
        assertEquals("Jonas Weber", a.displayName)
        assertEquals(Instant.ofEpochSecond(1792490400), a.sentAt)
        val b = units[1] as InboundUnit.Message
        assertEquals("[image] mein Zahn", b.text)
        assertEquals(EntryPoint.CLICK_TO_WHATSAPP_AD, b.entryPoint)
        assertEquals("Ja", (units[2] as InboundUnit.Message).text)
        val s1 = units[3] as InboundUnit.Status
        assertEquals(DeliveryStatus.DELIVERED, s1.status)
        assertEquals("msg_123", s1.callbackData)
        assertEquals("wa:status:wamid.OUT1:delivered", s1.dedupeKey)
        val s2 = units[4] as InboundUnit.Status
        assertEquals("131047", s2.errorCode)
        // the same payload parses to the same keys every time: retries deduplicate
        assertEquals(units.map { it.dedupeKey }, WhatsAppWebhook.parse(mapper, batch).map { it.dedupeKey })
    }

    @Test
    fun `malformed payloads are refused`() {
        assertFailsWith<InvalidWebhook> { WhatsAppWebhook.parse(mapper, "{") }
        assertFailsWith<InvalidWebhook> { WhatsAppWebhook.parse(mapper, """{"object":"page"}""") }
        assertFailsWith<InvalidWebhook> {
            WhatsAppWebhook.parse(mapper, """{"object":"whatsapp_business_account","entry":[{"changes":[{"field":"messages","value":{"metadata":{}}}]}]}""")
        }
    }
}

class SignaturesTest {
    @Test
    fun `meta signature over the raw body`() {
        val body = """{"object":"whatsapp_business_account"}""".toByteArray()
        val sig = Signatures.metaSignature("secret", body)
        assertTrue(sig.startsWith("sha256="))
        assertTrue(Signatures.verifyMeta("secret", body, sig))
        assertFalse(Signatures.verifyMeta("other", body, sig))
        assertFalse(Signatures.verifyMeta("secret", body + byteArrayOf(32), sig))
        assertFalse(Signatures.verifyMeta("secret", body, null))
    }

    @Test
    fun `sms signature over url and sorted params`() {
        val p = mapOf("To" to "+4930555010", "From" to "+491701234567", "Body" to "STOP", "MessageSid" to "SM1")
        val sig = Signatures.smsSignature("tok", "https://inbox.example/webhooks/sms", p)
        assertTrue(Signatures.verifySms("tok", "https://inbox.example/webhooks/sms", p, sig))
        assertFalse(Signatures.verifySms("tok", "https://inbox.example/webhooks/sms", p + ("Body" to "START"), sig))
    }
}

class OtherChannelsTest {
    private val mapper = JsonCustomizer.standalone()
    private val now = Instant.parse("2026-10-20T10:00:00Z")

    @Test
    fun `sms inbound, status callbacks and opt-out words`() {
        val m = SmsWebhook.parse(mapOf("MessageSid" to "SM1", "From" to "+491701234567", "To" to "+4930555010", "Body" to "Stop"), now)
        assertIs<InboundUnit.Message>(m)
        assertTrue(SmsWebhook.isOptOut(m.text))
        val s = SmsWebhook.parse(mapOf("MessageSid" to "SM2", "From" to "+4930555010", "To" to "+491701234567", "MessageStatus" to "undelivered", "ErrorCode" to "30003"), now)
        assertIs<InboundUnit.Status>(s)
        assertEquals(DeliveryStatus.FAILED, s.status)
        assertFalse(SmsWebhook.isOptOut("please stop by tomorrow"))
    }

    @Test
    fun `instagram messages, echoes skipped`() {
        val raw =
            """{"object":"instagram","entry":[{"id":"1784","time":1,"messaging":[
               {"sender":{"id":"u1"},"recipient":{"id":"17841400000000001"},"timestamp":1792490400000,"message":{"mid":"m1","text":"Do you do whitening?"}},
               {"sender":{"id":"17841400000000001"},"recipient":{"id":"u1"},"timestamp":1792490401000,"message":{"mid":"m2","text":"yes","is_echo":true}}]}]}"""
        val units = InstagramWebhook.parse(mapper, raw)
        assertEquals(1, units.size)
        assertEquals(Channel.INSTAGRAM, units[0].channel)
    }

    @Test
    fun `ses mail through sns keeps the reply and drops the quoted history`() {
        val mime = "From: Jonas <jonas@example.com>\r\nIn-Reply-To: <abc@lindqvist>\r\nContent-Type: text/plain\r\n\r\nThanks, Thursday works.\r\n\r\nOn Mon, 19 Oct 2026 at 09:00 Praxis wrote:\r\n> Would Thursday work?"
        val ses =
            mapper.writeValueAsString(
                mapOf(
                    "mail" to
                        mapOf(
                            "messageId" to "ses-1",
                            "timestamp" to "2026-10-20T10:00:00Z",
                            "source" to "jonas@example.com",
                            "destination" to listOf("praxis@lindqvist-dental.example"),
                            "commonHeaders" to mapOf("from" to listOf("Jonas Weber <Jonas@Example.com>"), "subject" to "Re: Termin"),
                        ),
                    "content" to mime,
                ),
            )
        val m = EmailWebhook.parse(mapper, ses)
        assertEquals("jonas@example.com", m.from)
        assertEquals("Jonas Weber", m.displayName)
        assertEquals("Thanks, Thursday works.", m.text)
        assertEquals("<abc@lindqvist>", m.inReplyTo)
        assertEquals("email:msg:ses-1", m.dedupeKey)
    }
}
