-- Core schema. NUMERIC everywhere for prices/thresholds — never a float type (L1.2).
-- Internal FKs point at Alpaca's asset_id UUID, not the ticker string: tickers get
-- reused after a delisting (L2.2), so the ticker column is display data, not identity.

CREATE TABLE users (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email      TEXT NOT NULL UNIQUE,
    tz         TEXT NOT NULL DEFAULT 'Asia/Kuala_Lumpur',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE symbols (
    asset_id       UUID PRIMARY KEY,
    symbol         TEXT NOT NULL,
    name           TEXT NOT NULL,
    exchange       TEXT NOT NULL,
    tradable       BOOLEAN NOT NULL,
    fractionable   BOOLEAN NOT NULL DEFAULT false,
    last_synced_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_symbols_symbol ON symbols (symbol);
CREATE INDEX idx_symbols_tradable ON symbols (tradable) WHERE tradable = true;

CREATE TABLE watchlists (
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name    TEXT NOT NULL
);

CREATE INDEX idx_watchlists_user_id ON watchlists (user_id);

CREATE TABLE watchlist_items (
    watchlist_id UUID NOT NULL REFERENCES watchlists (id) ON DELETE CASCADE,
    asset_id     UUID NOT NULL REFERENCES symbols (asset_id),
    PRIMARY KEY (watchlist_id, asset_id)
);

CREATE TABLE alerts (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    asset_id         UUID NOT NULL REFERENCES symbols (asset_id),
    condition        TEXT NOT NULL,
    threshold        NUMERIC(18, 6) NOT NULL,
    status           TEXT NOT NULL DEFAULT 'ACTIVE',
    cooldown_seconds INTEGER NOT NULL DEFAULT 3600,
    last_fired_at    TIMESTAMPTZ,
    armed            BOOLEAN NOT NULL DEFAULT true,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_alerts_asset_id ON alerts (asset_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_alerts_user_id ON alerts (user_id);

CREATE TABLE notifications (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_id   UUID NOT NULL REFERENCES alerts (id) ON DELETE CASCADE,
    channel    TEXT NOT NULL,
    status     TEXT NOT NULL DEFAULT 'PENDING',
    payload    TEXT NOT NULL,
    attempt    INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at    TIMESTAMPTZ
);

CREATE INDEX idx_notifications_status ON notifications (status) WHERE status = 'PENDING';

-- ShedLock (Step 4 leader election) — table shape required by shedlock-provider-jdbc-template.
CREATE TABLE shedlock (
    name       VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP NOT NULL,
    locked_at  TIMESTAMP NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
