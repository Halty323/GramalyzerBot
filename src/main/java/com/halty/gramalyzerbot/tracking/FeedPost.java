package com.halty.gramalyzerbot.tracking;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One message as the website's channel viewer draws it, whatever it came from: a tracked channel's public preview,
 * the bot's own record of a connected channel, or the workspace's posting queue.
 *
 * @param views    null when the source can't see views (the Bot API never reports them)
 * @param comments null when comments aren't tracked for the channel
 * @param url      a t.me link to the post, when it has one
 * @param status   "published" or "scheduled"
 */
public record FeedPost(String id, Integer messageId, Instant postedAt, String kind, String text, Integer views,
                       int reactions, List<Reaction> reactionList, Integer comments, String mediaUrl, String url,
                       String forwardedFrom, boolean edited, String status) {

    public record Reaction(String emoji, int count) {}

    public static FeedPost of(TrackedChannel c, TrackedPost p) {
        return new FeedPost("tracked:" + c.getUsername() + ":" + p.getMessageId(), p.getMessageId(), p.getPostedAt(), p.getKind(),
                p.getBody() == null ? "" : p.getBody(), p.getViews(), p.getReactions(), breakdown(p.getReactionBreakdown()), null,
                p.getMediaUrl(), c.isDemo() ? null : "https://t.me/" + c.getUsername() + "/" + p.getMessageId(),
                p.getForwardedFrom(), p.isEdited(), "published");
    }

    /** "🔥:12,👍:5" → [🔥 12, 👍 5], largest first. */
    public static List<Reaction> breakdown(String s) {
        List<Reaction> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String part : s.split(",")) {
            int colon = part.lastIndexOf(':');
            if (colon <= 0) continue;
            try {
                out.add(new Reaction(part.substring(0, colon), Integer.parseInt(part.substring(colon + 1).trim())));
            } catch (NumberFormatException ignored) {
                // a malformed entry; skip it
            }
        }
        out.sort((a, b) -> Integer.compare(b.count(), a.count()));
        return out;
    }
}
