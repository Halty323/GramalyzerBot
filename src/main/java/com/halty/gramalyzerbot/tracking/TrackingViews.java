package com.halty.gramalyzerbot.tracking;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What {@code /api/tracked} returns. Mirrored by {@code TrackedSummary} and {@code TrackedDetail} in the website's shared/types.ts. */
public final class TrackingViews {

    private TrackingViews() {}

    /**
     * One row of a watchlist.
     *
     * @param error  why the last refresh failed ({@link TrackingException.Code#key()}), or null
     * @param series daily subscriber counts while tracked, oldest first (at most 30)
     */
    public record Summary(String username, String title, String description, String photoUrl, boolean verified,
                          Integer subscribers, Integer change7, Integer avgViews, Double er, Double postsPerDay,
                          Instant lastPost, Instant fetchedAt, String error, boolean demo, boolean tracked,
                          List<Integer> series, String url) {}

    /** Everything about one channel: the summary, Telegram's media counters, the stats and the latest posts. */
    public record Detail(Summary channel, Map<String, Integer> counters, TrackedStats stats, List<FeedPost> top,
                         List<FeedPost> posts) {}

    public static Summary summary(TrackedChannel c, TrackedStats s, boolean tracked) {
        List<Integer> series = s.series().stream().map(TrackedStats.Point::subscribers).filter(java.util.Objects::nonNull).toList();
        if (series.size() > 30) series = series.subList(series.size() - 30, series.size());
        return new Summary(c.getUsername(), c.getTitle(), c.getDescription(), c.getPhotoUrl(), c.isVerified(), c.getSubscribers(),
                s.change7(), s.avgViews(), s.er(), s.postsPerDay(), s.lastPost(), c.getFetchedAt(), c.getLastError(), c.isDemo(), tracked,
                series, c.isDemo() ? null : "https://t.me/" + c.getUsername());
    }

    public static Detail detail(TrackedChannel c, TrackedStats s, List<TrackedPost> posts, boolean tracked) {
        Map<String, Integer> counters = new LinkedHashMap<>();
        if (c.getPhotos() != null) counters.put("photos", c.getPhotos());
        if (c.getVideos() != null) counters.put("videos", c.getVideos());
        if (c.getFiles() != null) counters.put("files", c.getFiles());
        if (c.getLinks() != null) counters.put("links", c.getLinks());
        List<FeedPost> feed = posts.stream().sorted((a, b) -> b.getPostedAt().compareTo(a.getPostedAt())).map(p -> FeedPost.of(c, p)).toList();
        List<FeedPost> top = s.top().stream().map(p -> FeedPost.of(c, p)).toList();
        return new Detail(summary(c, s, tracked), counters, s, top, feed);
    }
}
