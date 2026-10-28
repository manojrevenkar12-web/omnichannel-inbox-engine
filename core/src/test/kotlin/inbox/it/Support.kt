package inbox.it

import inbox.agent.AgentClient
import inbox.agent.TurnRequest
import inbox.domain.AgentAction
import inbox.domain.AgentDecision
import inbox.store.Db
import inbox.store.update
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.time.Instant
import java.util.Collections

/** Starts the fake WhatsApp API and points the app at it. */
class FakeWhatsAppResource : QuarkusTestResourceLifecycleManager {
    override fun start(): Map<String, String> {
        fake = FakeWhatsApp("http://127.0.0.1:8081/webhooks/whatsapp", "dev-app-secret").start()
        return mapOf("inbox.whatsapp.graph-url" to "http://127.0.0.1:${fake.port}")
    }

    override fun stop() {
        fake.stop()
    }

    companion object {
        lateinit var fake: FakeWhatsApp
    }
}

/** A scripted AI agent: answers from a function of the last customer message, records every turn. */
class ScriptedAgent(private val script: (String) -> AgentDecision) : AgentClient {
    val turns: MutableList<TurnRequest> = Collections.synchronizedList(mutableListOf())

    override fun turn(req: TurnRequest): AgentDecision {
        turns += req
        val last = req.messages.lastOrNull { it.author == "CUSTOMER" }?.text ?: ""
        return script(last)
    }

    companion object {
        fun faq(): ScriptedAgent =
            ScriptedAgent { text ->
                when {
                    text.contains("human", true) || text.contains("Mensch", true) -> AgentDecision(AgentAction.HANDOFF, null, "asked for a person", 0.95)
                    text.contains("refund", true) || text.contains("complain", true) -> AgentDecision(AgentAction.HANDOFF, null, "complaint", 0.9)
                    text.contains("?") -> AgentDecision(AgentAction.REPLY, "We are open Monday to Friday, 8:00 to 18:00.", "faq:opening-hours", 0.92)
                    else -> AgentDecision(AgentAction.NO_REPLY, null, "nothing to answer yet", 0.8)
                }
            }
    }
}

object TestData {
    /** Remove conversations and messages; keep the demo workspaces, teammates and templates. */
    fun reset(db: Db) {
        db.tx { c ->
            c.update(
                "TRUNCATE inbound_event, outbound_job, message_status, message, conversation_channel, conversation, contact_identity, contact RESTART IDENTITY CASCADE",
            )
            c.update("UPDATE teammate SET online = (id <> 'tm_cara'), last_assigned_seq = 0")
        }
    }

    private var counter = 0

    fun waId(): String = "4917" + (10_000_000 + ++counter + (System.nanoTime() % 1000).toInt()).toString()

    fun whatsappMessage(phoneNumberId: String, from: String, id: String, text: String, at: Instant, name: String? = null, adReferral: Boolean = false): Map<String, Any> {
        val msg =
            mutableMapOf<String, Any>(
                "from" to from,
                "id" to id,
                "timestamp" to at.epochSecond.toString(),
                "type" to "text",
                "text" to mapOf("body" to text),
            )
        if (adReferral) msg["referral"] = mapOf("source_type" to "ad", "source_id" to "ad-42")
        return msg + ("_name" to (name ?: "Customer $from"))
    }

    fun whatsappBatch(phoneNumberId: String, messages: List<Map<String, Any>>): Map<String, Any> =
        mapOf(
            "object" to "whatsapp_business_account",
            "entry" to
                listOf(
                    mapOf(
                        "id" to "WABA",
                        "changes" to
                            listOf(
                                mapOf(
                                    "field" to "messages",
                                    "value" to
                                        mapOf(
                                            "messaging_product" to "whatsapp",
                                            "metadata" to mapOf("display_phone_number" to "493055501000", "phone_number_id" to phoneNumberId),
                                            "contacts" to messages.map { mapOf("wa_id" to it["from"], "profile" to mapOf("name" to it["_name"])) }.distinct(),
                                            "messages" to messages.map { it - "_name" },
                                        ),
                                ),
                            ),
                    ),
                ),
        )
}
