-- Channel analytics: channels, team, collected posts, subscriber snapshots, audit log and bot settings.
-- Users gain roles, bans and the small conversation state used by multi-step prompts.
-- One column per statement: H2 (demo profile and tests) does not accept comma-separated ADD COLUMN actions.
ALTER TABLE users ADD COLUMN bot_admin BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN banned BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN language_code VARCHAR(16);
ALTER TABLE users ADD COLUMN active_channel_id BIGINT;
ALTER TABLE users ADD COLUMN state VARCHAR(32);
ALTER TABLE users ADD COLUMN state_arg VARCHAR(64);
ALTER TABLE users ADD COLUMN state_expires_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE channels (
    id                      BIGSERIAL PRIMARY KEY,
    telegram_chat_id        BIGINT       NOT NULL UNIQUE,
    title                   VARCHAR(255) NOT NULL,
    username                VARCHAR(64),
    timezone                VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    timezone_confirmed      BOOLEAN      NOT NULL DEFAULT FALSE,
    window_days             INTEGER      NOT NULL DEFAULT 90,
    metric                  VARCHAR(16)  NOT NULL DEFAULT 'ENGAGEMENT',
    digest_day              VARCHAR(16)  NOT NULL DEFAULT 'MONDAY',
    digest_hour             INTEGER      NOT NULL DEFAULT 9,
    nudge_lead_minutes      INTEGER      NOT NULL DEFAULT 30,
    nudge_gap_hours         INTEGER      NOT NULL DEFAULT 6,
    subscribers             INTEGER,
    subscribers_checked_at  TIMESTAMP WITH TIME ZONE,
    discussion_chat_id      BIGINT,
    -- the CRM channel (workspace_channels.id) this channel belongs to; filled in once the CRM has seen the chat
    workspace_channel_id    VARCHAR(80),
    demo                    BOOLEAN      NOT NULL DEFAULT FALSE,
    active                  BOOLEAN      NOT NULL DEFAULT TRUE,
    history_imported        BOOLEAN      NOT NULL DEFAULT FALSE,
    connected_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    last_post_event_at      TIMESTAMP WITH TIME ZONE,
    last_reaction_event_at  TIMESTAMP WITH TIME ZONE,
    last_digest_at          TIMESTAMP WITH TIME ZONE,
    last_nudge_at           TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX uq_channels_workspace ON channels (workspace_channel_id);

CREATE TABLE channel_members (
    id             BIGSERIAL PRIMARY KEY,
    channel_id     BIGINT      NOT NULL,
    user_id        BIGINT      NOT NULL,
    role           VARCHAR(16) NOT NULL,
    notify_digest  BOOLEAN     NOT NULL DEFAULT TRUE,
    notify_nudges  BOOLEAN     NOT NULL DEFAULT FALSE,
    added_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    added_by       BIGINT,
    CONSTRAINT uq_member UNIQUE (channel_id, user_id)
);
CREATE INDEX idx_members_user ON channel_members (user_id);

CREATE TABLE invites (
    id          BIGSERIAL PRIMARY KEY,
    token       VARCHAR(32) NOT NULL UNIQUE,
    channel_id  BIGINT      NOT NULL,
    role        VARCHAR(16) NOT NULL,
    created_by  BIGINT      NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    max_uses    INTEGER     NOT NULL DEFAULT 1,
    uses        INTEGER     NOT NULL DEFAULT 0,
    revoked     BOOLEAN     NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_invites_channel ON invites (channel_id);

CREATE TABLE posts (
    id                  BIGSERIAL PRIMARY KEY,
    channel_id          BIGINT      NOT NULL,
    message_id          INTEGER     NOT NULL,
    posted_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    edited_at           TIMESTAMP WITH TIME ZONE,
    kind                VARCHAR(16) NOT NULL,
    body                VARCHAR(8192),
    has_link            BOOLEAN     NOT NULL DEFAULT FALSE,
    media_group_id      VARCHAR(64),
    reactions           INTEGER     NOT NULL DEFAULT 0,
    reaction_breakdown  VARCHAR(512),
    comments            INTEGER     NOT NULL DEFAULT 0,
    source              VARCHAR(8)  NOT NULL,
    excluded            BOOLEAN     NOT NULL DEFAULT FALSE,
    manual_topic        VARCHAR(64),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_post UNIQUE (channel_id, message_id)
);
CREATE INDEX idx_posts_channel_time ON posts (channel_id, posted_at);

-- Maps the auto-forwarded copy of a channel post inside the linked discussion group to the post,
-- so comments (replies in that thread) can be counted per post.
CREATE TABLE discussion_roots (
    id                BIGSERIAL PRIMARY KEY,
    group_chat_id     BIGINT  NOT NULL,
    group_message_id  INTEGER NOT NULL,
    channel_id        BIGINT  NOT NULL,
    post_message_id   INTEGER NOT NULL,
    CONSTRAINT uq_root UNIQUE (group_chat_id, group_message_id)
);

CREATE TABLE subscriber_snapshots (
    id            BIGSERIAL PRIMARY KEY,
    channel_id    BIGINT  NOT NULL,
    taken_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    member_count  INTEGER NOT NULL
);
CREATE INDEX idx_snapshots_channel_time ON subscriber_snapshots (channel_id, taken_at);

CREATE TABLE audit_log (
    id             BIGSERIAL PRIMARY KEY,
    channel_id     BIGINT      NOT NULL,
    actor_user_id  BIGINT,
    action         VARCHAR(48) NOT NULL,
    details        VARCHAR(255),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_audit_channel_time ON audit_log (channel_id, created_at);

CREATE TABLE bot_settings (
    setting_key    VARCHAR(64) PRIMARY KEY,
    setting_value  VARCHAR(1024)
);
