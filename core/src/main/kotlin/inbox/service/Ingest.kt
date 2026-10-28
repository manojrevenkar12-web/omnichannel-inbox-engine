package inbox.service

import com.fasterxml.jackson.databind.ObjectMapper
import inbox.channels.InboundUnit
import inbox.store.Db
import inbox.store.update
import io.micrometer.core.instrument.MeterRegistry
import jakarta.enterprise.context.ApplicationScoped

/** A stored webhook fact: exactly one of the two fields is set. */
data class StoredUnit(val message: InboundUnit.Message? = null, val status: InboundUnit.Status? = null) {
    fun unit(): InboundUnit = message ?: status ?: error("empty unit")

    companion object {
        fun of(u: InboundUnit): StoredUnit =
            when (u) {
                is InboundUnit.Message -> StoredUnit(message = u)
                is InboundUnit.Status -> StoredUnit(status = u)
            }
    }
}

data class IngestResult(val stored: Int, val duplicates: Int)

/**
 * The webhook endpoint's only job: store each fact once and acknowledge quickly. Providers retry
 * webhooks that are not acknowledged fast enough (WhatsApp for up to seven days), so doing the
 * work later in [EventProcessor] keeps slow work from turning into duplicate deliveries.
 */
@ApplicationScoped
class Ingest(private val db: Db, private val mapper: ObjectMapper, private val meters: MeterRegistry) {
    fun accept(units: List<InboundUnit>): IngestResult {
        if (units.isEmpty()) return IngestResult(0, 0)
        val stored =
            db.tx { c ->
                units.sumOf { u ->
                    c.update(
                        "INSERT INTO inbound_event (dedupe_key, channel, unit) VALUES (?, ?, ?::jsonb) ON CONFLICT (dedupe_key) DO NOTHING",
                        u.dedupeKey,
                        u.channel,
                        mapper.writeValueAsString(StoredUnit.of(u)),
                    )
                }
            }
        val dupes = units.size - stored
        meters.counter("inbox.webhook.facts", "result", "stored").increment(stored.toDouble())
        meters.counter("inbox.webhook.facts", "result", "duplicate").increment(dupes.toDouble())
        return IngestResult(stored, dupes)
    }
}
