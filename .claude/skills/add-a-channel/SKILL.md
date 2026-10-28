---
name: add-a-channel
description: Add a messaging channel (for example Telegram or Messenger) to the inbox core; use when a new provider's webhooks and sending must be supported.
---

# Add a channel

1. **Webhook parsing** in `core/src/main/kotlin/inbox/channels/`: verify the provider's signature
   (constant-time, see `Signatures.kt`), turn a request into `InboundUnit`s, and give each a
   dedupe key that is stable across the provider's retries (`<channel>:msg:<provider id>`).
2. **Endpoint** in `api/Webhooks.kt`: verify, store with `Ingest.accept`, answer 200. Nothing else.
3. **Window and billing**: add the channel's rule to `domain/WindowPolicy.kt` and `Billing.kt` with
   unit tests in `DomainTest.kt` (open, closing, closed, opted out).
4. **Sending**: a `ProviderClient` in `providers/Providers.kt` that maps the provider's errors to
   `ProviderResult` kinds (throughput, pair rate, window closed, retryable, permanent) and passes our
   message id as callback data if the provider supports it.
5. **Tests**: parser tests with a recorded payload; a QuarkusTest that a retried webhook is stored
   once; extend the fault drill if the provider has its own rate limits.
6. **Console**: add the name to `web/src/shared/format.ts` and the window length to `theme/tokens.ts`.
