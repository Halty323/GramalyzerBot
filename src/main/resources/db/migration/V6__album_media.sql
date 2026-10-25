-- Every photo and video of a tracked post, with its place in the album (JSON). media_url keeps the first one.
ALTER TABLE tracked_posts ADD COLUMN media VARCHAR(8000);
