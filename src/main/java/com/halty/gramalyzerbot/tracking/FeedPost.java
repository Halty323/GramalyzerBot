package com.halty.gramalyzerbot.tracking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One message as the website's channel viewer draws it, whatever it came from: a tracked channel's public preview,
 * the bot's own record of a connected channel, or the workspace's posting queue.
 *
 * @param views    null when the source can't see views (the Bot API never reports them)
 * @param comments null when comments aren't tracked for the channel
 * @param mediaUrl the post's first picture, also for posts {@code media} has nothing for (a link preview, a sticker)
 * @param media    every photo and video of the post, an album's in Telegram's order; empty when the source has no pictures
 * @param url      a t.me link to the post, when it has one
 * @param status   "published" or "scheduled"
 */
public record FeedPost(String id, Integer messageId, Instant postedAt, String kind, String text, Integer views,
                       int reactions, List<Reaction> reactionList, Integer comments, String mediaUrl, List<Media> media,
                       String url, String forwardedFrom, boolean edited, String status) {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** The size of {@code tracked_posts.media}. */
    static final int MEDIA_MAX = 8000;

    public record Reaction(String emoji, int count) {}

    /**
     * A photo, or a video's thumbnail, with its place in the album as Telegram lays it out. Places are relative to the
     * album's width: {@code x} and {@code w} go from 0 to 1, {@code y} and {@code h} use the same unit, so a square
     * picture as wide as the album has {@code h} 1. A single picture is at 0, 0 with width 1. They are all null when
     * the page didn't say; the viewer then picks a layout itself.
     */
    public record Media(String url, boolean video, Double x, Double y, Double w, Double h) {}

    public static FeedPost of(TrackedChannel c, TrackedPost p) {
        return new FeedPost("tracked:" + c.getUsername() + ":" + p.getMessageId(), p.getMessageId(), p.getPostedAt(), p.getKind(),
                p.getBody() == null ? "" : p.getBody(), p.getViews(), p.getReactions(), breakdown(p.getReactionBreakdown()), null,
                p.getMediaUrl(), media(p.getMedia()), c.isDemo() ? null : "https://t.me/" + c.getUsername() + "/" + p.getMessageId(),
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

    /**
     * For {@code tracked_posts.media}: JSON, with the last pictures left out if all of them don't fit. A post without
     * pictures gets "[]", so null is left for posts stored before pictures were read like this.
     */
    static String media(List<Media> media) {
        if (media == null || media.isEmpty()) return "[]";
        try {
            for (int n = media.size(); n > 0; n--) {
                String json = JSON.writeValueAsString(media.subList(0, n));
                if (json.length() <= MEDIA_MAX) return json;
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return "[]";
    }

    static List<Media> media(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, new TypeReference<List<Media>>() {});
        } catch (JsonProcessingException e) {
            return List.of(); // written by an older version; the next refresh rewrites it
        }
    }
}
