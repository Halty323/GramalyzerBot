package com.halty.gramalyzerbot.post;

import com.halty.gramalyzerbot.analytics.PostKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "posts")
@Getter
@Setter
@NoArgsConstructor
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "message_id", nullable = false)
    private Integer messageId;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PostKind kind;

    @Column(length = 8192)
    private String body;

    @Column(name = "has_link", nullable = false)
    private boolean hasLink;

    @Column(name = "media_group_id")
    private String mediaGroupId;

    @Column(nullable = false)
    private int reactions;

    /** e.g. "🔥:12,👍:5" */
    @Column(name = "reaction_breakdown")
    private String reactionBreakdown;

    @Column(nullable = false)
    private int comments;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PostSource source;

    /** Left out of every analysis (ads, giveaways, one-off announcements). */
    @Column(nullable = false)
    private boolean excluded;

    @Column(name = "manual_topic")
    private String manualTopic;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
