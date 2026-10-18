package com.halty.gramalyzerbot.tracking;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The basic statistics of a tracked channel, worked out from the posts its public preview showed and the daily
 * snapshots taken while it was tracked.
 *
 * @param change7      subscriber change over the last 7 days of snapshots; null until there is a snapshot that old
 * @param avgViews     mean views of recent posts that are at least {@link #SETTLED} old (fresher posts are still
 *                     collecting views); all recent posts when none is that old yet
 * @param er           engagement rate in %: average views ÷ subscribers
 * @param reactionRate reactions per 100 views
 * @param hours        posts per hour of day, in the zone the stats were computed for
 * @param days         posts per weekday, Monday first
 * @param sample       how many posts the numbers are based on
 */
public record TrackedStats(Integer subscribers, Integer change7, Integer change30, List<Point> series,
                           Integer avgViews, Double er, Double avgReactions, Double reactionRate, Double postsPerDay,
                           Instant lastPost, List<Share> kinds, List<Tag> hashtags, int[] hours, int[] days,
                           @JsonIgnore List<TrackedPost> top, int sample) {

    public static final Duration SETTLED = Duration.ofHours(12);
    private static final int RECENT = 30;
    private static final Pattern HASHTAG = Pattern.compile("#([\\p{L}\\p{N}_]{2,40})");

    public record Point(LocalDate day, Integer subscribers, Integer avgViews) {}

    public record Share(String kind, int posts, Integer avgViews) {}

    public record Tag(String tag, int posts, Integer avgViews) {}

    /** @param posts any order; @param snapshots oldest first */
    public static TrackedStats of(Integer subscribers, List<TrackedPost> posts, List<TrackedSnapshot> snapshots,
                                  Instant now, ZoneId zone) {
        List<TrackedPost> newest = posts.stream().sorted(Comparator.comparing(TrackedPost::getPostedAt).reversed()).toList();
        List<TrackedPost> recent = newest.subList(0, Math.min(RECENT, newest.size()));

        List<TrackedPost> settled = recent.stream().filter(p -> p.getViews() != null && !p.getPostedAt().isAfter(now.minus(SETTLED))).toList();
        List<TrackedPost> counted = settled.isEmpty() ? recent.stream().filter(p -> p.getViews() != null).toList() : settled;
        Integer avgViews = counted.isEmpty() ? null : (int) Math.round(counted.stream().mapToInt(TrackedPost::getViews).average().orElse(0));
        Double er = avgViews == null || subscribers == null || subscribers == 0 ? null : round(avgViews * 100.0 / subscribers);
        Double avgReactions = recent.isEmpty() ? null : round(recent.stream().mapToInt(TrackedPost::getReactions).average().orElse(0));
        long views = counted.stream().mapToLong(TrackedPost::getViews).sum();
        long reactions = counted.stream().mapToLong(TrackedPost::getReactions).sum();
        Double reactionRate = views == 0 ? null : round(reactions * 100.0 / views);

        Double postsPerDay = null;
        Instant lastPost = newest.isEmpty() ? null : newest.get(0).getPostedAt();
        if (!newest.isEmpty()) {
            Instant from = now.minus(Duration.ofDays(30));
            List<TrackedPost> month = newest.stream().filter(p -> p.getPostedAt().isAfter(from)).toList();
            if (!month.isEmpty()) {
                // the preview only reaches back so far: measure over the span we actually saw, at least a day
                Instant oldest = newest.get(newest.size() - 1).getPostedAt();
                double span = Math.max(1, Math.min(30, Duration.between(oldest.isAfter(from) ? oldest : from, now).toHours() / 24.0));
                postsPerDay = round(month.size() / span);
            } else {
                postsPerDay = 0.0;
            }
        }

        Map<String, List<TrackedPost>> byKind = new LinkedHashMap<>();
        for (TrackedPost p : recent) byKind.computeIfAbsent(p.getKind(), k -> new ArrayList<>()).add(p);
        List<Share> kinds = byKind.entrySet().stream()
                .map(e -> new Share(e.getKey(), e.getValue().size(), mean(e.getValue())))
                .sorted(Comparator.comparingInt(Share::posts).reversed()).toList();

        Map<String, List<TrackedPost>> byTag = new HashMap<>();
        for (TrackedPost p : newest) {
            if (p.getBody() == null) continue;
            Set<String> seen = new HashSet<>();
            Matcher m = HASHTAG.matcher(p.getBody());
            while (m.find()) {
                String tag = m.group(1).toLowerCase(Locale.ROOT);
                if (seen.add(tag)) byTag.computeIfAbsent(tag, k -> new ArrayList<>()).add(p);
            }
        }
        List<Tag> hashtags = byTag.entrySet().stream().filter(e -> e.getValue().size() >= 2)
                .map(e -> new Tag(e.getKey(), e.getValue().size(), mean(e.getValue())))
                .sorted(Comparator.comparingInt(Tag::posts).reversed().thenComparing(Tag::tag)).limit(8).toList();

        int[] hours = new int[24];
        int[] days = new int[7];
        for (TrackedPost p : newest) {
            ZonedDateTime local = p.getPostedAt().atZone(zone);
            hours[local.getHour()]++;
            days[local.getDayOfWeek().getValue() - 1]++;
        }

        List<TrackedPost> top = newest.stream().filter(p -> p.getViews() != null)
                .sorted(Comparator.comparingInt(TrackedPost::getViews).reversed()).limit(5).toList();

        List<Point> series = snapshots.stream().map(s -> new Point(s.getDay(), s.getSubscribers(), s.getAvgViews())).toList();
        LocalDate today = now.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        return new TrackedStats(subscribers, change(snapshots, subscribers, today, 7), change(snapshots, subscribers, today, 30),
                series, avgViews, er, avgReactions, reactionRate, postsPerDay, lastPost, kinds, hashtags, hours, days, top, counted.size());
    }

    /** Current subscribers minus the oldest snapshot at least {@code days} back… or, failing that, the oldest one if it's most of the way there. */
    static Integer change(List<TrackedSnapshot> snapshots, Integer current, LocalDate today, int days) {
        if (current == null) return null;
        LocalDate target = today.minusDays(days);
        TrackedSnapshot base = null;
        for (TrackedSnapshot s : snapshots) {
            if (s.getSubscribers() == null) continue;
            if (!s.getDay().isAfter(target)) base = s;
        }
        if (base == null) {
            Optional<TrackedSnapshot> oldest = snapshots.stream().filter(s -> s.getSubscribers() != null).findFirst();
            if (oldest.isEmpty() || ChronoUnit.DAYS.between(oldest.get().getDay(), today) < Math.max(2, days * 0.7)) return null;
            base = oldest.get();
        }
        return current - base.getSubscribers();
    }

    private static Integer mean(List<TrackedPost> posts) {
        OptionalDouble avg = posts.stream().filter(p -> p.getViews() != null).mapToInt(TrackedPost::getViews).average();
        return avg.isPresent() ? (int) Math.round(avg.getAsDouble()) : null;
    }

    private static double round(double x) { return Math.round(x * 100) / 100.0; }
}
