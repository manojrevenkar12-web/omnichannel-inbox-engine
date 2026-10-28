# 0001: PostgreSQL is the queue: a webhook inbox and a send outbox

Status: accepted

## Context

Meta, Twilio and SNS retry a webhook that does not get a 200 quickly, and drop it after a while.
They also deliver the same event more than once, batch several into one request, and send them out
of order. Sending has the opposite problem: the provider limits us (80 messages per second per
WhatsApp number, one message every few seconds per customer) and sometimes accepts a message but
answers too late for us to know.

## Decision

- A webhook is verified, split into units and written to `inbound_event` with a unique
  `dedupe_key` (`wa:msg:<id>`, `wa:status:<id>:<status>`) in one insert. Then it answers 200. No
  business logic runs before the 200.
- Workers claim units with `FOR UPDATE SKIP LOCKED`, apply each in a savepoint, and retry with
  backoff. A status that arrives before its message is deferred, then parked after 12 attempts.
- A reply is one transaction: the `message` row and its `outbound_job`. The sender claims jobs the
  same way, partitioned by business number (`hashtext(account) % instances`) so the token bucket
  for a number lives in one process.
- A send that times out becomes `AMBIGUOUS`. The status webhook for it carries our message id in
  `biz_opaque_callback_data` and settles it. Only after `ambiguous-wait` of silence is it sent again.

## Consequences

- No SQS or Kafka to run for this volume. One database to back up and reason about.
- The fault drills (`core/src/test/kotlin/inbox/it/DrillTest.kt`) hold this to account: 500 sends
  under 3% server errors and 3% accepted-but-late responses lose and double nothing; a blind
  retry would have sent 14 duplicates.
- Scaling the sender means more partitions, not more copies of one worker. When one database
  becomes the limit, the inbox table is the first thing to move to a log (Kinesis or Kafka), with
  the same dedupe key.
