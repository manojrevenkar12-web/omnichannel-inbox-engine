# 0004: Delivery statuses merge by rank, never by arrival

Status: accepted

## Context

Meta sends `sent`, `delivered` and `read` as separate webhooks, retried and out of order. A
`delivered` can arrive after `read`, and a status can arrive before our send call returns.

## Decision

`DeliveryStatus` has a rank (QUEUED < ACCEPTED < SENT < DELIVERED < READ; FAILED is terminal) and
`merge` keeps the higher. Every report is kept in `message_status` for audit. A status that
arrives before we know the provider's message id is matched by our own id in the callback data.

## Consequences

In the sending drill, a last-write-wins update would have shown the wrong status for 70 of 500
messages. The merge shows 0 wrong.
