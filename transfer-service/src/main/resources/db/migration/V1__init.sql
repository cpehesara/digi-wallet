CREATE TABLE transfers (
    transfer_id     uuid        PRIMARY KEY,
    from_wallet     uuid        NOT NULL,
    to_wallet       uuid        NOT NULL,
    amount_minor    bigint      NOT NULL CHECK (amount_minor > 0),
    state           text        NOT NULL,
    failure_reason  text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE idempotency_keys (
    scope         text        NOT NULL,
    idem_key      text        NOT NULL,
    request_hash  text        NOT NULL,
    status        text        NOT NULL,
    response_code int,
    response_body text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL,
    PRIMARY KEY (scope, idem_key)
);

CREATE TABLE outbox (
    id            bigserial   PRIMARY KEY,
    event_id      uuid        NOT NULL UNIQUE,
    aggregate_id  uuid        NOT NULL,
    seq           bigint      NOT NULL,
    topic         text        NOT NULL,
    payload       text        NOT NULL,
    attempts      int         NOT NULL DEFAULT 0,
    status        text        NOT NULL DEFAULT 'PENDING',
    created_at    timestamptz NOT NULL DEFAULT now(),
    published_at  timestamptz
);
CREATE INDEX idx_outbox_pending ON outbox (id) WHERE status = 'PENDING';