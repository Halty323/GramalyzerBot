-- Who joins and leaves an own channel, and through which promo link. Telegram reports each change to an admin bot
-- (the chat_member update); people are stored as a one-way hash, never as their Telegram id.
CREATE TABLE member_events (
    id            BIGSERIAL PRIMARY KEY,
    channel_id    BIGINT    NOT NULL,
    at            TIMESTAMP WITH TIME ZONE NOT NULL,
    joined        BOOLEAN   NOT NULL,
    member        BIGINT    NOT NULL,
    promo_link_id BIGINT
);
CREATE INDEX idx_member_events_channel ON member_events (channel_id, at);
CREATE INDEX idx_member_events_member ON member_events (channel_id, member);

-- Invite links the bot made for one promo each (an ad, a shoutout, a link in a bio), with what it cost.
CREATE TABLE promo_links (
    id         BIGSERIAL    PRIMARY KEY,
    channel_id BIGINT       NOT NULL,
    name       VARCHAR(32)  NOT NULL,
    url        VARCHAR(255) NOT NULL UNIQUE,
    cost       DOUBLE PRECISION,
    currency   VARCHAR(8),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_promo_links_channel ON promo_links (channel_id);

-- when the owners were last told that people are leaving in a wave
ALTER TABLE channels ADD COLUMN last_leave_alert_at TIMESTAMP WITH TIME ZONE;
