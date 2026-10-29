-- Pictures, videos and GIFs uploaded for posts in the website's queue. Kept in the database so a backup of it is
-- a backup of everything; uploads that no post uses are removed after a day.
CREATE TABLE workspace_media (
    id           VARCHAR(40)  PRIMARY KEY,
    kind         VARCHAR(16)  NOT NULL,
    content_type VARCHAR(80)  NOT NULL,
    name         VARCHAR(120) NOT NULL,
    size         BIGINT       NOT NULL,
    data         BYTEA        NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
