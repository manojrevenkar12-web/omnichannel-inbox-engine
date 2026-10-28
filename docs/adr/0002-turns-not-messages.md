# 0002: The AI answers turns, not messages

Status: accepted

## Context

Customers on WhatsApp write in fragments: "Hi", "quick question", "when are you open on Friday?".
A bot that answers each message sends three replies, the first two useless. From October 2026
every service message on WhatsApp is billed, so each of those replies also costs money.

## Decision

An inbound message sets `agent_due_at = now + quiet` (4 s in production) and bumps the
conversation's `version`. The worker calls the agent only for conversations whose quiet time has
passed, with every customer message since the business last wrote. The agent answers the whole
turn once. If a new message arrived while the agent was thinking, the version no longer matches
and the answer is thrown away; the next turn includes the new message.

## Consequences

- On the blind set turns-2, 23 messages for 60 turns where a per-fragment bot sends 90.
- Replies come a few seconds later than they could. The quiet time is a setting, per workspace
  if needed.
