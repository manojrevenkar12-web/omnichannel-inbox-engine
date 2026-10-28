# Working in this repository

Four parts: `core/` (Kotlin, Quarkus, PostgreSQL), `agent/` (Python, LangGraph), `web/` (React),
`infra/terraform/` (AWS). Decisions are in `docs/adr/`.

## Fast feedback

- `scripts/check.sh` runs what can run anywhere: the agent's ruff, mypy, pytest and dev eval, and
  the console's typecheck, unit tests and build.
- The core builds and tests in CI (`./gradlew build` with Dev Services PostgreSQL), including the
  fault drills, whose numbers appear as notices on the run.
- `cd web && npm run e2e` needs the core, the agent and `web/e2e/fake-graph.mjs` running.

## Invariants (each has a test or a drill)

1. A webhook is stored with its dedupe key before it is answered 200; nothing else happens first.
2. A message and its outbound job are written in one transaction.
3. A send that timed out is never resent before `ambiguous-wait`; its status webhook settles it.
4. Delivery statuses merge by rank and never move back; windows only move forward.
5. No free-form message is sent outside the channel's window; the core refuses it before the provider does.
6. Rate limits are respected per business number (throughput) and per customer (pair spacing).
7. Every console query is scoped to the caller's workspace.
8. The agent sends nothing a cited knowledge entry does not support; symptoms, complaints,
   records, injection and requests for a person go to a person.
9. Settings live in `application.properties` / `config`, not in code.

## Held-out rule

Never change the agent to pass `agent/heldout/<set>`. A set's first run
(`agent/reports/heldout/<set>/first_run.json`) is never rewritten. If you read a set's failures,
write `regression` to its `STATUS` and have someone who has not seen the code write a new set from
`agent/heldout/WRITING_TURNS.md` only.
