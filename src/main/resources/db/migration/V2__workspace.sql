CREATE TABLE workspace_channels (
    id VARCHAR(80) PRIMARY KEY,
    telegram_chat_id BIGINT UNIQUE,
    data TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE workspace_posts (
    id VARCHAR(80) PRIMARY KEY,
    channel_id VARCHAR(80) NOT NULL REFERENCES workspace_channels(id),
    data TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    delivery_state VARCHAR(20) NOT NULL DEFAULT 'pending',
    telegram_message_id BIGINT,
    delivery_error VARCHAR(300),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (channel_id, telegram_message_id)
);
CREATE INDEX workspace_posts_due ON workspace_posts(status, delivery_state, scheduled_at);
CREATE TABLE channel_daily_metrics (
    channel_id VARCHAR(80) NOT NULL REFERENCES workspace_channels(id),
    metric_date DATE NOT NULL,
    subscribers BIGINT NOT NULL,
    PRIMARY KEY (channel_id, metric_date)
);
