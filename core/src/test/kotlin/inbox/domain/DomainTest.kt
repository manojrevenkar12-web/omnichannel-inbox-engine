package inbox.domain

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeliveryStatusTest {
    @Test
    fun `status only moves forward whatever order reports arrive in`() {
        val reports = listOf(DeliveryStatus.DELIVERED, DeliveryStatus.SENT, DeliveryStatus.READ, DeliveryStatus.SENT, DeliveryStatus.DELIVERED)
        val final = reports.fold(DeliveryStatus.ACCEPTED) { s, r -> s.merge(r) }
        assertEquals(DeliveryStatus.READ, final)
    }

    @Test
    fun `failure overrides sent but not delivered`() {
        assertEquals(DeliveryStatus.FAILED, DeliveryStatus.SENT.merge(DeliveryStatus.FAILED))
        assertEquals(DeliveryStatus.FAILED, DeliveryStatus.ACCEPTED.merge(DeliveryStatus.FAILED))
        assertEquals(DeliveryStatus.DELIVERED, DeliveryStatus.DELIVERED.merge(DeliveryStatus.FAILED))
        assertEquals(DeliveryStatus.FAILED, DeliveryStatus.FAILED.merge(DeliveryStatus.READ))
    }

    @Test
    fun `every permutation of a status stream ends at the furthest status`() {
        val stream = listOf(DeliveryStatus.SENT, DeliveryStatus.DELIVERED, DeliveryStatus.READ)
        fun perms(l: List<DeliveryStatus>): List<List<DeliveryStatus>> = if (l.size <= 1) listOf(l) else l.flatMap { x -> perms(l - x).map { listOf(x) + it } }
        for (p in perms(stream)) assertEquals(DeliveryStatus.READ, p.fold(DeliveryStatus.ACCEPTED) { s, r -> s.merge(r) }, "order $p")
    }

    @Test
    fun `provider vocabulary maps to statuses`() {
        assertEquals(DeliveryStatus.FAILED, DeliveryStatus.fromProvider("undelivered"))
        assertEquals(DeliveryStatus.READ, DeliveryStatus.fromProvider("READ"))
        assertNull(DeliveryStatus.fromProvider("deleted"))
    }
}

class WindowPolicyTest {
    private val t0 = Instant.parse("2026-10-20T10:00:00Z")

    private fun wa(last: Instant?) = ChannelState(Channel.WHATSAPP, last)

    @Test
    fun `whatsapp free-form only within 24 hours of the customer's last message`() {
        assertEquals(SendDecision.Allowed, WindowPolicy.decide(wa(t0), MessageKind.FREE_FORM, Author.HUMAN, t0 + Duration.ofHours(23)))
        val closed = WindowPolicy.decide(wa(t0), MessageKind.FREE_FORM, Author.HUMAN, t0 + Duration.ofHours(24))
        assertIs<SendDecision.TemplateRequired>(closed)
        assertEquals(t0 + Duration.ofHours(24), closed.closedAt)
        assertEquals(SendDecision.Allowed, WindowPolicy.decide(wa(t0), MessageKind.TEMPLATE, Author.HUMAN, t0 + Duration.ofDays(3)))
    }

    @Test
    fun `an AI agent never sends templates and never starts a conversation`() {
        assertIs<SendDecision.Refused>(WindowPolicy.decide(wa(t0), MessageKind.FREE_FORM, Author.AI, t0 + Duration.ofHours(25)))
        assertIs<SendDecision.Refused>(WindowPolicy.decide(wa(null), MessageKind.FREE_FORM, Author.AI, t0))
        assertIs<SendDecision.TemplateRequired>(WindowPolicy.decide(wa(null), MessageKind.FREE_FORM, Author.HUMAN, t0))
    }

    @Test
    fun `instagram gives people seven days and the AI one`() {
        val ig = ChannelState(Channel.INSTAGRAM, t0)
        val day3 = t0 + Duration.ofDays(3)
        assertEquals(SendDecision.Allowed, WindowPolicy.decide(ig, MessageKind.FREE_FORM, Author.HUMAN, day3))
        assertIs<SendDecision.Refused>(WindowPolicy.decide(ig, MessageKind.FREE_FORM, Author.AI, day3))
        assertIs<SendDecision.Refused>(WindowPolicy.decide(ig, MessageKind.FREE_FORM, Author.HUMAN, t0 + Duration.ofDays(8)))
        assertIs<SendDecision.Refused>(WindowPolicy.decide(ig, MessageKind.TEMPLATE, Author.HUMAN, t0))
    }

    @Test
    fun `sms and email have no window but an opt-out stops everything`() {
        assertEquals(SendDecision.Allowed, WindowPolicy.decide(ChannelState(Channel.SMS, null), MessageKind.FREE_FORM, Author.AI, t0))
        assertEquals(SendDecision.Allowed, WindowPolicy.decide(ChannelState(Channel.EMAIL, null), MessageKind.FREE_FORM, Author.HUMAN, t0))
        assertIs<SendDecision.Refused>(WindowPolicy.decide(ChannelState(Channel.SMS, t0, optedOut = true), MessageKind.FREE_FORM, Author.HUMAN, t0))
    }
}

class BillingTest {
    private val from = Instant.parse("2026-10-01T00:00:00Z")
    private val billing = Billing(from)
    private val open = Instant.parse("2026-10-20T10:00:00Z")

    @Test
    fun `service messages are billed per message from October 2026`() {
        val st = ChannelState(Channel.WHATSAPP, open)
        assertTrue(billing.charge(st, MessageKind.FREE_FORM, null, open + Duration.ofMinutes(5)).billable)
        val before = ChannelState(Channel.WHATSAPP, Instant.parse("2026-09-20T10:00:00Z"))
        assertFalse(billing.charge(before, MessageKind.FREE_FORM, null, Instant.parse("2026-09-20T10:05:00Z")).billable)
    }

    @Test
    fun `free entry points are free for 72 hours`() {
        val st = ChannelState(Channel.WHATSAPP, open, EntryPoint.CLICK_TO_WHATSAPP_AD, open)
        assertFalse(billing.charge(st, MessageKind.FREE_FORM, null, open + Duration.ofHours(10)).billable)
        assertTrue(billing.charge(st, MessageKind.TEMPLATE, TemplateCategory.MARKETING, open + Duration.ofHours(80)).billable)
    }

    @Test
    fun `utility templates inside the window are free, marketing ones are not`() {
        val st = ChannelState(Channel.WHATSAPP, open)
        assertFalse(billing.charge(st, MessageKind.TEMPLATE, TemplateCategory.UTILITY, open + Duration.ofHours(1)).billable)
        assertTrue(billing.charge(st, MessageKind.TEMPLATE, TemplateCategory.UTILITY, open + Duration.ofHours(30)).billable)
        assertTrue(billing.charge(st, MessageKind.TEMPLATE, TemplateCategory.MARKETING, open + Duration.ofHours(1)).billable)
        assertFalse(billing.charge(ChannelState(Channel.SMS, open), MessageKind.FREE_FORM, null, open).billable)
    }
}

class LimitsTest {
    private val t0 = Instant.parse("2026-10-20T10:00:00Z")

    @Test
    fun `token bucket allows the burst then the configured rate`() {
        val b = TokenBucket(80.0)
        var taken = 0
        repeat(200) { if (b.tryTake(t0)) taken++ }
        assertEquals(80, taken)
        assertFalse(b.tryTake(t0))
        var later = 0
        // one more second at 80 per second
        for (i in 1..1000) if (b.tryTake(t0 + Duration.ofMillis(i.toLong()))) later++
        assertTrue(later in 79..81, "took $later in one second")
    }

    @Test
    fun `burst plus refill never exceeds the limit in any sliding second`() {
        val b = TokenBucket(72.0, 8.0) // the sender's split of an 80 per second limit
        val taken = mutableListOf<Long>()
        for (ms in 0L..5_000L) while (b.tryTake(t0 + Duration.ofMillis(ms))) taken += ms
        var j = 0
        var peak = 0
        for (i in taken.indices) {
            while (taken[i] - taken[j] >= 1000) j++
            peak = maxOf(peak, i - j + 1)
        }
        assertTrue(peak <= 80, "peak $peak in one second")
        assertTrue(taken.size >= 72 * 5, "throughput ${taken.size} in five seconds")
    }

    @Test
    fun `a throughput rejection halves the rate until the cooldown ends`() {
        val b = TokenBucket(80.0)
        b.penalise(t0, Duration.ofSeconds(10))
        assertEquals(40.0, b.rate)
        b.tryTake(t0 + Duration.ofSeconds(11))
        assertEquals(80.0, b.rate)
        assertTrue(b.waitFor(t0 + Duration.ofSeconds(11)) >= Duration.ZERO)
    }

    @Test
    fun `pair spacing`() {
        val sp = Duration.ofSeconds(6)
        assertEquals(t0, PairSpacing.nextAllowed(null, sp, t0))
        assertEquals(t0 + sp, PairSpacing.nextAllowed(t0, sp, t0 + Duration.ofSeconds(1)))
        assertEquals(t0 + Duration.ofSeconds(12), PairSpacing.penalty(t0, sp))
    }
}

class AssignmentAndAgentTest {
    @Test
    fun `least loaded online teammate, round robin on ties, never someone offline or full`() {
        val team =
            listOf(
                Teammate("a", "A", true, 3, 2, 5),
                Teammate("b", "B", true, 3, 1, 9),
                Teammate("c", "C", true, 3, 1, 4),
                Teammate("d", "D", false, 3, 0, 0),
                Teammate("e", "E", true, 1, 1, 0),
            )
        assertEquals("c", Assignment.choose(team)?.id)
        assertNull(Assignment.choose(team.filter { !it.online || it.openConversations >= it.capacity }))
    }

    private val turns = AgentTurns(Duration.ofSeconds(4), 0.6, 3)

    @Test
    fun `the agent hands over when unsure, when the window closed or after too many replies`() {
        val ok = AgentDecision(AgentAction.REPLY, "We open at 8.", "faq", 0.9)
        assertEquals(AgentTurns.Outcome.Send("We open at 8."), turns.apply(ok, true, 0))
        assertIs<AgentTurns.Outcome.Handoff>(turns.apply(ok, false, 0))
        assertIs<AgentTurns.Outcome.Handoff>(turns.apply(ok.copy(confidence = 0.3), true, 0))
        assertIs<AgentTurns.Outcome.Handoff>(turns.apply(ok, true, 3))
        assertIs<AgentTurns.Outcome.Handoff>(turns.apply(ok.copy(text = " "), true, 0))
        assertEquals(AgentTurns.Outcome.Nothing, turns.apply(ok.copy(action = AgentAction.NO_REPLY), true, 0))
    }

    @Test
    fun `a turn is due after the customer is quiet`() {
        val t = Instant.parse("2026-10-20T10:00:00Z")
        assertEquals(t + Duration.ofSeconds(4), turns.dueAt(t))
    }
}
