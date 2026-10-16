package com.halty.gramalyzerbot.analytics;

import java.time.Instant;
import java.util.List;

/** One channel post, reduced to what the analysis needs. */
public record Sample(
        long id,
        int messageId,
        Instant postedAt,
        PostKind kind,
        String text,
        boolean hasLink,
        List<String> topics,
        int reactions,
        int comments,
        boolean commentsKnown,
        boolean excluded,
        String mediaGroupId) {

    public Sample {
        text = text == null ? "" : text;
        topics = topics == null ? List.of() : List.copyOf(topics);
    }

    public int textLength() { return text.codePointCount(0, text.length()); }
}
