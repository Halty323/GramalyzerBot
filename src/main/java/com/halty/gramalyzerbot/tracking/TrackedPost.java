package com.halty.gramalyzerbot.tracking;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One post of a tracked channel. Views and reactions are refreshed while the post is still on the preview page. */
@Entity
@Table(name = "tracked_posts")
@Getter
@Setter
@NoArgsConstructor
public class TrackedPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tracked_channel_id", nullable = false)
    private Long trackedChannelId;

    @Column(name = "message_id", nullable = false)
    private Integer messageId;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    /** A {@link com.halty.gramalyzerbot.analytics.PostKind} name. */
    @Column(nullable = false)
    private String kind;

    @Column(length = 8192)
    private String body;

    /** Null when Telegram didn't show a counter (it always does for channel posts, but be safe). */
    private Integer views;

    /** Views at about a day old: the first reading between 24 and 48 hours after posting, if there was one. */
    @Column(name = "views_24h")
    private Integer views24h;

    @Column(nullable = false)
    private int reactions;

    /** e.g. "🔥:12,👍:5" */
    @Column(name = "reaction_breakdown", length = 512)
    private String reactionBreakdown;

    @Column(name = "media_url", length = 1024)
    private String mediaUrl;

    /** Every photo and video of the post as JSON, see {@link FeedPost#media(String)}. */
    @Column(length = FeedPost.MEDIA_MAX)
    private String media;

    @Column(name = "has_link", nullable = false)
    private boolean hasLink;

    @Column(name = "forwarded_from")
    private String forwardedFrom;

    @Column(nullable = false)
    private boolean edited;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
