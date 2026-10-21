-- Paid plans in the bot, billed as Telegram Stars subscriptions.
-- One column per statement: H2 (demo profile and tests) does not accept comma-separated ADD COLUMN actions.
ALTER TABLE users ADD COLUMN plan VARCHAR(16) NOT NULL DEFAULT 'FREE';
-- the plan is paid up to here; Telegram renews it then unless the person cancelled
ALTER TABLE users ADD COLUMN plan_until TIMESTAMP WITH TIME ZONE;
-- the first payment of the running subscription: Telegram's handle for cancelling or resuming it
ALTER TABLE users ADD COLUMN plan_charge_id VARCHAR(255);
ALTER TABLE users ADD COLUMN plan_canceled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE payments (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    plan        VARCHAR(16)  NOT NULL,
    stars       INTEGER      NOT NULL,
    charge_id   VARCHAR(255) NOT NULL UNIQUE,
    renewal     BOOLEAN      NOT NULL DEFAULT FALSE,
    paid_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    paid_until  TIMESTAMP WITH TIME ZONE NOT NULL,
    refunded_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_payments_user ON payments (user_id, paid_at);
