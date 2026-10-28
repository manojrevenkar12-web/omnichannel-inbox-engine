package inbox.service

import inbox.config.InboxConfig
import inbox.store.Db
import inbox.store.one
import inbox.store.update
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.jboss.logging.Logger
import java.time.Clock

@ApplicationScoped
class Clocks {
    @Produces
    @Singleton
    fun clock(): Clock = Clock.systemUTC()
}

data class Caller(val teammateId: String, val name: String, val workspaceId: String)

/** Teammates authenticate with a bearer token; only its SHA-256 is stored. */
@ApplicationScoped
class Auth(private val db: Db) {
    fun caller(authorization: String?): Caller? {
        val token = authorization?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return db.read { c ->
            c.one("SELECT id, name, workspace_id FROM teammate WHERE token_hash = ?", Ids.sha256(token)) {
                Caller(it.getString("id"), it.getString("name"), it.getString("workspace_id"))
            }
        }
    }
}

/**
 * Demo workspaces for local runs, the console and the tests: a dental practice with WhatsApp,
 * SMS, email and Instagram, three teammates, and a second business to prove tenants are apart.
 * Tokens are `demo-<name>`; nothing here runs unless `inbox.demo.seed` is true.
 */
@ApplicationScoped
class DemoSeed(private val db: Db, private val cfg: InboxConfig) {
    private val log = Logger.getLogger(DemoSeed::class.java)

    fun onStart(
        @Observes ev: StartupEvent,
    ) {
        if (cfg.demo().seed()) seed()
    }

    fun seed() {
        db.tx { c ->
            c.update("INSERT INTO workspace (id, name, ai_enabled) VALUES ('ws_dental', 'Praxis Dr. Lindqvist', true) ON CONFLICT DO NOTHING")
            c.update("INSERT INTO workspace (id, name, ai_enabled) VALUES ('ws_bikes', 'Nordlicht Bikes', true) ON CONFLICT DO NOTHING")
            val accounts =
                listOf(
                    listOf("acc_dental_wa", "ws_dental", "WHATSAPP", "109000000000001", "+49 30 5550 1000", 80),
                    listOf("acc_dental_sms", "ws_dental", "SMS", "+4930555010", "+49 30 5550 10", 10),
                    listOf("acc_dental_mail", "ws_dental", "EMAIL", "praxis@lindqvist-dental.example", "praxis@lindqvist-dental.example", 14),
                    listOf("acc_dental_ig", "ws_dental", "INSTAGRAM", "17841400000000001", "@praxis.lindqvist", 20),
                    listOf("acc_bikes_wa", "ws_bikes", "WHATSAPP", "109000000000002", "+49 40 5550 2000", 80),
                )
            for (a in accounts) {
                c.update(
                    "INSERT INTO channel_account (id, workspace_id, channel, address, display, throughput_mps) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                    *a.toTypedArray(),
                )
            }
            val team =
                listOf(
                    listOf("tm_anna", "ws_dental", "Anna Becker", "demo-anna", true, 6),
                    listOf("tm_ben", "ws_dental", "Ben Okafor", "demo-ben", true, 6),
                    listOf("tm_cara", "ws_dental", "Cara Wolff", "demo-cara", false, 6),
                    listOf("tm_nils", "ws_bikes", "Nils Hansen", "demo-nils", true, 8),
                )
            for (t in team) {
                c.update(
                    "INSERT INTO teammate (id, workspace_id, name, token_hash, online, capacity) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                    t[0], t[1], t[2], Ids.sha256(t[3] as String), t[4], t[5],
                )
            }
            val templates =
                listOf(
                    listOf("ws_dental", "appointment_reminder", "en", "UTILITY", "Reminder: your appointment at Praxis Dr. Lindqvist is tomorrow. Reply here if you need to move it."),
                    listOf("ws_dental", "appointment_reminder", "de", "UTILITY", "Erinnerung: Ihr Termin in der Praxis Dr. Lindqvist ist morgen. Antworten Sie hier, falls Sie ihn verschieben möchten."),
                    listOf("ws_dental", "follow_up", "en", "UTILITY", "Hello again from Praxis Dr. Lindqvist. We could not finish our conversation; reply to continue."),
                    listOf("ws_dental", "checkup_offer", "en", "MARKETING", "Time for your yearly check-up? Book a slot this month and get a free cleaning."),
                    listOf("ws_bikes", "order_update", "en", "UTILITY", "Your bike order has an update. Reply to this message for details."),
                )
            for (t in templates) {
                c.update("INSERT INTO template (workspace_id, name, language, category, body) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING", *t.toTypedArray())
            }
            c.one("SELECT count(*) AS n FROM teammate") { it.getInt("n") }
        }.also { log.infof("demo workspaces seeded (%d teammates)", it) }
    }
}

/** Background workers. Tests switch them off and drive each drain directly. */
@ApplicationScoped
class Workers(
    private val cfg: InboxConfig,
    private val events: EventProcessor,
    private val sender: Sender,
    private val agents: Agents,
) {
    @Scheduled(every = "0.5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun processEvents() {
        if (cfg.workers().enabled()) events.drain()
    }

    @Scheduled(every = "0.2s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun send() {
        if (cfg.workers().enabled()) sender.drain(20)
    }

    @Scheduled(every = "0.5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun agentTurns() {
        if (cfg.workers().enabled()) agents.drain()
    }
}
