package com.halty.gramalyzerbot.gaps;

import com.halty.gramalyzerbot.tracking.TrackedChannel;
import com.halty.gramalyzerbot.tracking.TrackedPost;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Three made-up competitors for a sample channel whose watchlist is empty, built in memory and never saved. Their
 * posts carry clear patterns (videos, tutorials and checklists, evening posts and longer texts do better), so the
 * comparison has something to show.
 */
final class DemoRivals {

    private record Sample(String username, String title, int subscribers, double reach, String[] posts) {}

    private static final List<Sample> SAMPLES = List.of(
            new Sample("techdigest_demo", "Daily Tech Digest", 38_400, 0.27, new String[]{
                    "Video tutorial: set up your first home server in an evening", "Checklist before you buy a used laptop",
                    "The week in gadgets: five launches worth a look", "Why your phone battery drains overnight",
                    "Tutorial: automate boring tasks with a few lines of code", "Opinion: the best features nobody uses"}),
            new Sample("creatortoolkit_demo", "Creator Toolkit", 16_900, 0.36, new String[]{
                    "Checklist: everything to prepare before a launch", "Behind the scenes of our studio setup",
                    "Tutorial: edit a short video on your phone", "Ten free tools we use every day",
                    "Question of the week: what slows you down?", "A quick tip for cleaner thumbnails"}),
            new Sample("growthlab_demo", "Growth Lab", 61_200, 0.21, new String[]{
                    "Case study: how a small channel doubled in a month", "Checklist for a post that gets shared",
                    "Tutorial: read your analytics in five minutes", "Monday news roundup for creators",
                    "Myths about posting times, tested", "Our favourite posts from readers this week"}));

    private static final String[] KINDS = {"TEXT", "PHOTO", "PHOTO", "VIDEO", "TEXT", "ALBUM", "PHOTO", "VIDEO"};

    private DemoRivals() {}

    static Map<TrackedChannel, List<TrackedPost>> make(Instant now, ZoneId zone) {
        Map<TrackedChannel, List<TrackedPost>> out = new LinkedHashMap<>();
        long id = -1;
        for (Sample s : SAMPLES) {
            Random rng = new Random(s.username().hashCode());
            TrackedChannel c = new TrackedChannel(s.username(), s.title());
            c.setId(id--);
            c.setSubscribers(s.subscribers());
            c.setDemo(true);
            List<TrackedPost> posts = new ArrayList<>();
            int message = 500 + rng.nextInt(2000);
            for (int back = 40; back >= 1; back--) {
                int count = 1 + rng.nextInt(2);
                for (int i = 0; i < count; i++) {
                    Instant at = now.atZone(zone).truncatedTo(ChronoUnit.DAYS).minusDays(back)
                            .plusHours(8 + rng.nextInt(14)).plusMinutes(rng.nextInt(60)).toInstant();
                    String kind = KINDS[rng.nextInt(KINDS.length)];
                    String line = s.posts()[rng.nextInt(s.posts().length)];
                    boolean longer = rng.nextDouble() < 0.4;
                    String body = line + (longer ? "\n\n" + "Here is the full story, step by step, with what we learned along the way. ".repeat(6) : "");
                    int hour = at.atZone(zone).getHour();
                    double boost = (kind.equals("VIDEO") ? 1.6 : 1)
                            * (line.startsWith("Tutorial") || line.startsWith("Video tutorial") || line.startsWith("Checklist") ? 1.7 : 1)
                            * (hour >= 16 && hour < 20 ? 1.35 : 1)
                            * (longer ? 1.2 : 1)
                            * (0.8 + rng.nextDouble() * 0.4);
                    TrackedPost p = new TrackedPost();
                    p.setTrackedChannelId(c.getId());
                    p.setMessageId(message++);
                    p.setPostedAt(at);
                    p.setKind(kind);
                    p.setBody(body);
                    p.setViews((int) Math.round(s.subscribers() * s.reach() * boost));
                    p.setReactions((int) Math.round(p.getViews() * 0.015));
                    p.setUpdatedAt(now.minus(Duration.ofHours(1)));
                    posts.add(p);
                }
            }
            out.put(c, posts);
        }
        return out;
    }
}
