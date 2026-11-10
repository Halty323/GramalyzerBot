-- A channel's subscriber goal: reach a number by a date. One at a time; setting a new one replaces it.
CREATE TABLE channel_goals (
    channel_id   BIGINT  PRIMARY KEY REFERENCES channels (id) ON DELETE CASCADE,
    target       INTEGER NOT NULL,
    deadline     DATE    NOT NULL,
    -- the subscriber count when the goal was set, so progress starts at 0%
    start_count  INTEGER NOT NULL,
    started_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    reached_at   TIMESTAMP WITH TIME ZONE,
    set_by       BIGINT
);

-- Posts a week that keep the posting streak going, and when the team was last told the streak is at risk.
ALTER TABLE channels ADD COLUMN streak_target INTEGER NOT NULL DEFAULT 3;
ALTER TABLE channels ADD COLUMN last_streak_nudge_at TIMESTAMP WITH TIME ZONE;
