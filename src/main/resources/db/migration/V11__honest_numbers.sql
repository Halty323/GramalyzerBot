-- "Automatic" success metric: views after 24 hours when the channel's posts have them (public channels), otherwise
-- reactions + 3 × comments. Channels still on the old default move to it; a metric someone chose stays.
ALTER TABLE channels ALTER COLUMN metric SET DEFAULT 'AUTO';
UPDATE channels SET metric = 'AUTO' WHERE metric = 'ENGAGEMENT';

-- A watched channel's views at about a day old. The latest count keeps growing for weeks, so comparing an old post's
-- latest count with a new one's says more about their ages than about the posts.
ALTER TABLE tracked_posts ADD COLUMN views_24h INTEGER;

-- Where a channel shows up when its owner searches Telegram for a word, as they noted it. Telegram publishes no
-- ranking data, so these notes are the only way to see whether a new title or username actually helps.
CREATE TABLE search_positions (
    id          BIGSERIAL PRIMARY KEY,
    channel_id  BIGINT       NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    word        VARCHAR(64)  NOT NULL,
    -- 1 = the first result; NULL = not in the results at all
    position    INTEGER,
    checked_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX search_positions_channel ON search_positions (channel_id, checked_at);
