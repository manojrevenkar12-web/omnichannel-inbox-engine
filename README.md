# omnichannel-inbox-engine

A shared customer inbox for small businesses: WhatsApp, SMS, email and Instagram in one place,
with an AI agent that answers what the business has written down and hands everything else to the
team. Built to be correct where messaging platforms are hard: webhooks that arrive twice, late or
out of order; provider rate limits; sends that time out after the provider accepted them; reply
windows that close; and, from October 2026, a bill for every WhatsApp message.

| Part | Stack | What it does |
|---|---|---|
| `core/` | Kotlin 2.1, Quarkus 3.20, PostgreSQL, plain JDBC | Webhooks, conversations, assignment, the outbox and sender, the console API |
| `agent/` | Python, LangGraph, FastAPI | Answers a customer's turn from the business's knowledge or hands it to a person |
| `web/` | React, TypeScript, Framer Motion | The console the team works in |
| `infra/terraform/` | Terraform, AWS | VPC, RDS, ECS Fargate, ALB, SES to SNS, Secrets Manager, alarms |

## What it does

- **Webhooks are stored, then answered.** Each WhatsApp, Instagram, SMS and email webhook is
  verified, split into units and written with a dedupe key before the 200. Workers apply the units
  with `FOR UPDATE SKIP LOCKED` and retry; a status that arrives before its message waits for it.
- **One answer per turn.** The AI waits until the customer has stopped typing, answers every
  question in the burst at once, and throws its answer away if the customer wrote again meanwhile.
- **The window decides what may be sent.** A free-form message only inside WhatsApp's 24 hours
  (Instagram: 24 hours for the AI, 7 days for people), a template after. The core refuses before the
  provider does; the console shows the window as a fuse and offers templates once it has burnt down.
- **Sending within the limits.** A token bucket per business number (80 per second on WhatsApp,
  halved for a while on 130429), spacing per customer (doubled on 131056), customers in parallel,
  each customer's messages in order.
- **No duplicate when a send times out.** The provider gets our message id as callback data. A
  timed-out send waits for its status webhook and is resent only if none comes; a send left in
  flight by a worker that stopped is recovered the same way.
- **Statuses never move back.** `sent`, `delivered` and `read` merge by rank, whatever order they arrive in.
- **Handover to a person.** Symptoms, complaints, records, prompt injection, questions the
  knowledge does not answer: to the least busy teammate online, or to a queue.
- **Billing per message.** From 2026-10-01 every service message is billable; replies inside the
  72-hour free entry point after a click-to-WhatsApp ad, and utility templates inside an open
  window, are not.

## Measured

Numbers from CI on commit 3173523, where all five jobs pass. The sending drill's counts of what a naive client would have got wrong vary a little from run to run (12–16 duplicates, 70–103 statuses); the zeros do not (the drills write `core/build/drills/*.json`; the agent's
first runs are pinned in `agent/reports/heldout/`).

**Fault drills** against a fake WhatsApp Cloud API that fails the way the real one does:

| Drill | Result |
|---|---|
| Inbound: 180 messages from 60 customers in 53 shuffled, batched webhooks, 8 of them delivered twice, timestamps up to 2 hours late | 0 lost, 0 duplicates, 0 windows wrong; 60 AI turns and 60 replies, not 180 |
| Sending: 500 messages to 300 customers, 3% server errors, 3% accepted but answered too late, duplicated, reordered and early status webhooks, bursts of 3 to one customer, 10 sends to closed windows | 0 lost, 0 duplicates (a blind retry would have sent 16), 0 wrong statuses (last-write-wins would have shown 87 wrong), 0 rejections for a closed window, peak within 80 per second |

**The agent**, on sets nobody tuned it against. Each blind set is written by someone who has read
only `agent/heldout/WRITING_TURNS.md`, run once, and pinned:

| Set | Right action | Replied when a person was needed | Answered when answerable | Unsupported claims sent | Messages vs a per-fragment bot |
|---|---|---|---|---|---|
| turns-1, first run (now a regression set) | 65.0% | 2 of 24 | 53.3% | 0 | 18 vs 89 |
| turns-2, first run (blind) | **85.0%** | 1 of 24 | 73.3% | 0 | 23 vs 90 |
| dev (in-sample, 36 turns) | 97.2% | 0 | 95.7% | 0 | 22 vs 41 |

The turns-1 failures were studied to improve the agent (acknowledgements, role-play injection,
German inflection, answer-kind checks); turns-2 measures the result. Its one wrong reply and
its handovers have not been read, so it stays honest.

**Tests**: 35 Kotlin tests (domain, channel parsers, Quarkus integration with PostgreSQL, drills),
22 Python tests, 13 console unit tests, 4 Playwright end-to-end tests on the real stack, and
Terraform plan tests with a mocked provider. All run in CI.

## Run it

```bash
docker compose up --build        # PostgreSQL, core (8080), agent, console (8081)
```

Sign in at http://localhost:8081 as a demo teammate (Anna or Ben at a dental practice, Nils at a
bike shop). To send customer messages, post signed webhooks like `web/e2e/whatsapp.ts` does.

Fast checks: `scripts/check.sh` (agent and console). The core builds with `./gradlew build` and
needs Docker for Dev Services PostgreSQL.

## Limits, stated plainly

- Never deployed. The Terraform validates and passes plan tests; it has not been applied to an account.
- WhatsApp and SMS sending are implemented; **email and Instagram sending are not** (the core
  receives them and says so when asked to send).
- The provider in tests is a fake built from Meta's documented behaviour, not the real API.
- The agent drafts with a deterministic stand-in unless `ANTHROPIC_API_KEY` is set; the verifier
  is the same either way. Its knowledge is two small example businesses.
- One sender per business number partition; a slow provider response holds up its round. The
  measured peak in the drill is below the 80 per second the bucket allows.

## Where to read next

`docs/adr/` for the decisions, `docs/TECH_LEAD_PLAYBOOK.md` for how the team works,
`docs/AI_IN_THE_SDLC.md` for how AI tools are used and kept honest, `AGENTS.md` for the invariants.
