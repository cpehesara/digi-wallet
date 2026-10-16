CREATE TABLE wallets(
    wallet_id uuid PRIMARY KEY,
    owner_name text NOT NULL,
    currency char(3) NOT NULL DEFAULT 'LKR',
    status text NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE wallet_balance(
    wallet_id uuid PRIMARY KEY REFERENCES wallets(wallet_id),
    balance_minor bigint NOT NULL DEFAULT 0,
    last_seq bigint NOT NULL DEFAULT 0
);

CREATE TABLE ledger_events (
    event_id uuid PRIMARY KEY,
    wallet_id uuid NOT NULL REFERENCES wallets(wallet_id),
    seq bigint NOT NULL,
    type text NOT NULL,
    amount_minor bigint NOT NULL CHECK(amount_minor > 0),
    currency char(3) NOT NULL DEFAULT 'LKR',
    trace_id text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (wallet_id,seq)
);

CREATE FUNCTION forbid_ledger_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_events is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_events_immutable
    BEFORE UPDATE OR DELETE ON ledger_events
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_change();

CREATE TABLE idempotency_keys (
    scope text NOT NULL,
    idem_key text NOT NULL,
    request_hash text NOT NULL,
    status text NOT NULL,
    response_code int,
    response_body text,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (scope, idem_key)
);

CREATE TABLE outbox(
    id bigserial PRIMARY KEY,
    event_id uuid NOT NULL UNIQUE,
    aggregate_id uuid NOT NULL,
    seq bigint NOT NULL,
    topic text NOT NULL,
    payload text NOT NULL,
    attempts int NOT NULL DEFAULT 0,
    status text NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
CREATE INDEX idx_outbox_pending ON outbox (id) WHERE status = 'PENDING';

CREATE TABLE inbox (
    consumer text NOT NULL,
    message_id text NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, message_id)
);

CREATE TABLE notification_log (
    id bigserial PRIMARY KEY,
    message_id text NOT NULL UNIQUE,
    body text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);