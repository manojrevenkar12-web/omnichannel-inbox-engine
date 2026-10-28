-- The inbox's record. PostgreSQL is the source of truth for every conversation, message,
-- delivery status and pending piece of work; workers coordinate through row locks
-- (FOR UPDATE SKIP LOCKED), not through process memory.

CREATE TABLE workspace (
    id          text PRIMARY KEY,
    name        text NOT NULL,
    ai_enabled  boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- A business's address on a channel: a WhatsApp phone number id, an email address, an SMS number.
CREATE TABLE channel_account (
    id              text PRIMARY KEY,
    workspace_id    text NOT NULL REFERENCES workspace(id),
    channel         text NOT NULL,
    address         text NOT NULL,
    display         text NOT NULL,
    throughput_mps  integer NOT NULL DEFAULT 80,
    UNIQUE (channel, address)
);

CREATE TABLE teammate (
    id                text PRIMARY KEY,
    workspace_id      text NOT NULL REFERENCES workspace(id),
    name              text NOT NULL,
    token_hash        text NOT NULL UNIQUE,
    online            boolean NOT NULL DEFAULT true,
    capacity          integer NOT NULL DEFAULT 8,
    last_assigned_seq bigint NOT NULL DEFAULT 0
);

CREATE TABLE template (
    workspace_id  text NOT NULL REFERENCES workspace(id),
    name          text NOT NULL,
    language      text NOT NULL,
    category      text NOT NULL,
    body          text NOT NULL,
    PRIMARY KEY (workspace_id, name, language)
);

CREATE TABLE contact (
    id            text PRIMARY KEY,
    workspace_id  text NOT NULL REFERENCES workspace(id),
    display_name  text,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- How a contact is reached on each channel; also holds per-recipient send spacing and opt-out.
CREATE TABLE contact_identity (
    workspace_id     text NOT NULL REFERENCES workspace(id),
    channel          text NOT NULL,
    address          text NOT NULL,
    contact_id       text NOT NULL REFERENCES contact(id),
    opted_out        boolean NOT NULL DEFAULT false,
    last_sent_at     timestamptz,
    next_allowed_at  timestamptz,
    PRIMARY KEY (workspace_id, channel, address)
);

CREATE TABLE conversation (
    id                       text PRIMARY KEY,
    workspace_id             text NOT NULL REFERENCES workspace(id),
    contact_id               text NOT NULL REFERENCES contact(id),
    status                   text NOT NULL DEFAULT 'OPEN',
    assignee_kind            text NOT NULL DEFAULT 'NONE',
    assignee_id              text REFERENCES teammate(id),
    ai_replies               integer NOT NULL DEFAULT 0,
    agent_due_at             timestamptz,
    handoff_reason           text,
    handed_off_at            timestamptz,
    first_human_response_at  timestamptz,
    last_message_at          timestamptz NOT NULL DEFAULT now(),
    created_at               timestamptz NOT NULL DEFAULT now(),
    resolved_at              timestamptz,
    version                  bigint NOT NULL DEFAULT 0
);
-- one open conversation per contact: concurrent inbound messages cannot open two
CREATE UNIQUE INDEX conversation_one_open ON conversation (contact_id) WHERE status = 'OPEN';
CREATE INDEX conversation_agent_due ON conversation (agent_due_at) WHERE agent_due_at IS NOT NULL;
CREATE INDEX conversation_workspace ON conversation (workspace_id, status, last_message_at DESC);

-- Reply window per channel of a conversation, from provider timestamps.
CREATE TABLE conversation_channel (
    conversation_id          text NOT NULL REFERENCES conversation(id),
    channel                  text NOT NULL,
    account_id               text NOT NULL REFERENCES channel_account(id),
    customer_address         text NOT NULL,
    last_inbound_at          timestamptz,
    last_inbound_arrived_at  timestamptz,
    entry_point              text NOT NULL DEFAULT 'ORGANIC',
    entry_at                 timestamptz,
    PRIMARY KEY (conversation_id, channel)
);

CREATE TABLE message (
    id                   text PRIMARY KEY,
    workspace_id         text NOT NULL REFERENCES workspace(id),
    conversation_id      text NOT NULL REFERENCES conversation(id),
    direction            text NOT NULL,
    channel              text NOT NULL,
    account_id           text NOT NULL REFERENCES channel_account(id),
    author               text NOT NULL,
    author_id            text,
    body                 text NOT NULL,
    kind                 text NOT NULL DEFAULT 'FREE_FORM',
    template_name        text,
    provider_message_id  text,
    status               text NOT NULL,
    status_at            timestamptz,
    error_code           text,
    error_title          text,
    billable             boolean NOT NULL DEFAULT false,
    charge_category      text,
    sent_at              timestamptz NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    idempotency_key      text
);
CREATE UNIQUE INDEX message_provider_id ON message (channel, provider_message_id) WHERE provider_message_id IS NOT NULL;
CREATE UNIQUE INDEX message_idempotency ON message (workspace_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX message_conversation ON message (conversation_id, sent_at, created_at);

-- Every status report as received, including the ones that did not change the current status.
CREATE TABLE message_status (
    id           bigserial PRIMARY KEY,
    message_id   text NOT NULL REFERENCES message(id),
    status       text NOT NULL,
    at           timestamptz NOT NULL,
    error_code   text,
    received_at  timestamptz NOT NULL DEFAULT now()
);

-- Webhook inbox: one row per fact, under a key that is the same every time the provider repeats
-- the fact. Acknowledged to the provider as soon as it is stored; processed by workers.
CREATE TABLE inbound_event (
    id               bigserial PRIMARY KEY,
    dedupe_key       text NOT NULL UNIQUE,
    channel          text NOT NULL,
    unit             jsonb NOT NULL,
    received_at      timestamptz NOT NULL DEFAULT now(),
    processed_at     timestamptz,
    attempts         integer NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz NOT NULL DEFAULT now(),
    outcome          text,
    last_error       text
);
CREATE INDEX inbound_event_pending ON inbound_event (next_attempt_at, id) WHERE processed_at IS NULL;

-- Outbox of sends. A message is queued in the same transaction that records it.
CREATE TABLE outbound_job (
    message_id    text PRIMARY KEY REFERENCES message(id),
    account_id    text NOT NULL REFERENCES channel_account(id),
    recipient     text NOT NULL,
    state         text NOT NULL DEFAULT 'PENDING',
    not_before    timestamptz NOT NULL DEFAULT now(),
    attempts      integer NOT NULL DEFAULT 0,
    last_error    text,
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX outbound_job_due ON outbound_job (not_before) WHERE state IN ('PENDING', 'AMBIGUOUS');
