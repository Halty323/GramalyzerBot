-- How fast each own post gathers views. The Bot API has no view counts, so they're read from a public channel's web
-- preview (t.me/s/<username>): views now, and views 1, 6 and 24 hours after posting (filled in from the readings
-- either side of each mark). pace_alert remembers the one alert a post can get: LAG (well behind the channel's usual
-- pace) or FAST (well ahead).
ALTER TABLE posts ADD COLUMN views INTEGER;
ALTER TABLE posts ADD COLUMN views_checked_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE posts ADD COLUMN views_1h INTEGER;
ALTER TABLE posts ADD COLUMN views_6h INTEGER;
ALTER TABLE posts ADD COLUMN views_24h INTEGER;
ALTER TABLE posts ADD COLUMN pace_alert VARCHAR(8);
