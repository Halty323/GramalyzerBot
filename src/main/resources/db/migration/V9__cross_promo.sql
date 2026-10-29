-- Cross-promo between channels that use Gramalyzer: a channel opts in with its topic and language, finds partners
-- of a similar size and audience, and swaps posts with them. Each agreed swap gets a promo link in both channels,
-- so both sides see how many people the other's post brought.
CREATE TABLE swap_profiles (
    channel_id BIGINT       PRIMARY KEY,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    topic      VARCHAR(24)  NOT NULL,
    language   VARCHAR(8)   NOT NULL,
    pitch      VARCHAR(300),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE swaps (
    id           BIGSERIAL   PRIMARY KEY,
    from_channel BIGINT      NOT NULL,
    to_channel   BIGINT      NOT NULL,
    -- REQUESTED, ACTIVE, DECLINED, CANCELLED, DONE
    status       VARCHAR(16) NOT NULL,
    -- promo links in each channel: from_link is in from_channel and is posted by to_channel, and the other way round
    from_link    BIGINT,
    to_link      BIGINT,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    responded_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_swaps_from ON swaps (from_channel, status);
CREATE INDEX idx_swaps_to ON swaps (to_channel, status);
