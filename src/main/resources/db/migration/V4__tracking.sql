-- Public channels people keep an eye on without the bot being a member: read from Telegram's public web preview
-- (t.me/s/<username>) and, when the bot is running, from getChat / getChatMemberCount.
-- Users also get a language of their own choosing, which wins over the one their Telegram app reports.
ALTER TABLE users ADD COLUMN language VARCHAR(8);

CREATE TABLE tracked_channels (
    id              BIGSERIAL PRIMARY KEY,
    -- lower-case, without "@"
    username        VARCHAR(64)   NOT NULL UNIQUE,
    title           VARCHAR(255)  NOT NULL,
    description     VARCHAR(2048),
    photo_url       VARCHAR(1024),
    subscribers     INTEGER,
    verified        BOOLEAN       NOT NULL DEFAULT FALSE,
    photos          INTEGER,
    videos          INTEGER,
    files           INTEGER,
    links           INTEGER,
    telegram_chat_id BIGINT,
    demo            BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    fetched_at      TIMESTAMP WITH TIME ZONE,
    last_error      VARCHAR(255)
);

CREATE TABLE tracked_posts (
    id                  BIGSERIAL PRIMARY KEY,
    tracked_channel_id  BIGINT        NOT NULL,
    message_id          INTEGER       NOT NULL,
    posted_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    kind                VARCHAR(16)   NOT NULL,
    body                VARCHAR(8192),
    views               INTEGER,
    reactions           INTEGER       NOT NULL DEFAULT 0,
    reaction_breakdown  VARCHAR(512),
    media_url           VARCHAR(1024),
    has_link            BOOLEAN       NOT NULL DEFAULT FALSE,
    forwarded_from      VARCHAR(255),
    edited              BOOLEAN       NOT NULL DEFAULT FALSE,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_tracked_post UNIQUE (tracked_channel_id, message_id)
);
CREATE INDEX idx_tracked_posts_time ON tracked_posts (tracked_channel_id, posted_at);

-- one row per channel and UTC day; the day's last refresh wins
CREATE TABLE tracked_snapshots (
    id                  BIGSERIAL PRIMARY KEY,
    tracked_channel_id  BIGINT   NOT NULL,
    snapshot_date       DATE     NOT NULL,
    subscribers         INTEGER,
    avg_views           INTEGER,
    CONSTRAINT uq_tracked_snapshot UNIQUE (tracked_channel_id, snapshot_date)
);

-- who keeps an eye on a channel: "web" for the website's workspace, "tg:<users.id>" for a person in the bot
CREATE TABLE tracked_watchers (
    id                  BIGSERIAL PRIMARY KEY,
    tracked_channel_id  BIGINT       NOT NULL,
    owner               VARCHAR(40)  NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_tracked_watcher UNIQUE (tracked_channel_id, owner)
);
CREATE INDEX idx_tracked_watchers_owner ON tracked_watchers (owner);
