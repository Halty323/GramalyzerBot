package com.halty.gramalyzerbot.tracking;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The demo workspace's watchlist: three made-up public channels with a month of posts and subscriber history, so the
 * viewer and the watchlist can be explored without reaching Telegram. Real usernames typed in demo mode are still
 * looked up for real.
 */
@Component
@ConditionalOnProperty(name = "gramalyzer.demo", havingValue = "true")
public class DemoTracking implements ApplicationRunner {

    private record Sample(String username, String title, String description, int subscribers, double growthPerDay,
                          double reach, String[] posts, String[] tags) {}

    private static final List<Sample> SAMPLES = List.of(
            new Sample("pixelpulse_demo", "Pixel Pulse", "Games, patches and the occasional hot take. New posts every evening 🎮",
                    48210, 61, 0.31,
                    new String[]{"Patch notes are out and the balance changes are huge", "Our top 5 indie games this month",
                            "Leaked trailer? We take a closer look", "Weekend sale roundup: what is actually worth it",
                            "Speedrunner breaks the record again", "Which console are you playing on tonight?",
                            "Behind the scenes with a small studio", "Retro corner: the soundtrack that still slaps"},
                    new String[]{"news", "indie", "sale", "retro"}),
            new Sample("morningmarkets_demo", "Morning Markets", "A calm five-minute read on markets before your first coffee ☕",
                    126400, 140, 0.18,
                    new String[]{"Markets open flat as investors wait for the data", "Three charts that explain this week",
                            "What the rate decision means for your savings", "Earnings season: the winners and the surprises",
                            "Oil slips, tech climbs: the morning brief", "A simple explainer on bond yields",
                            "Your questions answered: index funds", "Weekly wrap: the numbers that mattered"},
                    new String[]{"brief", "charts", "explainer", "wrap"}),
            new Sample("wandernotes_demo", "Wander Notes", "Slow travel, cheap flights and places worth the detour 🧭",
                    21950, 34, 0.42,
                    new String[]{"A quiet village most travellers drive past", "Cheap flight alert for the weekend",
                            "How we pack for two weeks in one backpack", "Street food you should not miss",
                            "Sunrise from the old fortress walls", "Five tips for your first night train",
                            "Photo dump from the mountains", "Hidden beaches without the crowds"},
                    new String[]{"deals", "photos", "tips", "food"}));

    private static final String[] KINDS = {"TEXT", "PHOTO", "PHOTO", "VIDEO", "ALBUM", "TEXT", "PHOTO", "POLL"};
    private static final String[] EMOJI = {"👍", "🔥", "❤", "😂", "🤯", "👏"};

    private final TrackedChannelRepository channels;
    private final TrackedPostRepository posts;
    private final TrackedSnapshotRepository snapshots;
    private final TrackedWatcherRepository watchers;

    public DemoTracking(TrackedChannelRepository channels, TrackedPostRepository posts,
                        TrackedSnapshotRepository snapshots, TrackedWatcherRepository watchers) {
        this.channels = channels;
        this.posts = posts;
        this.snapshots = snapshots;
        this.watchers = watchers;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (watchers.countByOwner(TrackingService.WEB) > 0) return;
        for (Sample s : SAMPLES) {
            if (channels.findByUsername(s.username()).isPresent()) continue;
            seed(s, s.username().hashCode());
        }
    }

    private void seed(Sample s, long seed) {
        Random rng = new Random(seed);
        Instant now = Instant.now();
        TrackedChannel c = new TrackedChannel(s.username(), s.title());
        c.setDescription(s.description());
        c.setSubscribers(s.subscribers());
        c.setDemo(true);
        c.setVerified(s.subscribers() > 100_000);
        c.setPhotos(300 + rng.nextInt(900));
        c.setVideos(40 + rng.nextInt(200));
        c.setFiles(rng.nextInt(40));
        c.setLinks(200 + rng.nextInt(600));
        c.setFetchedAt(now);
        c = channels.save(c);

        List<TrackedPost> batch = new ArrayList<>();
        int message = 2000 + rng.nextInt(3000);
        for (int back = 29; back >= 0; back--) {
            int count = 1 + rng.nextInt(3);
            for (int i = 0; i < count; i++) {
                Instant at = now.truncatedTo(ChronoUnit.DAYS).minus(back, ChronoUnit.DAYS)
                        .plus(7 + rng.nextInt(15), ChronoUnit.HOURS).plus(rng.nextInt(60), ChronoUnit.MINUTES);
                if (at.isAfter(now)) continue;
                double age = Math.min(1, (now.toEpochMilli() - at.toEpochMilli()) / 86_400_000.0 * 1.6);
                String kind = KINDS[rng.nextInt(KINDS.length)];
                double boost = (kind.equals("VIDEO") ? 1.35 : kind.equals("ALBUM") ? 1.15 : 1.0) * (0.75 + rng.nextDouble() * 0.6);
                int views = (int) Math.round(s.subscribers() * s.reach() * boost * (0.25 + 0.75 * age));
                int reactions = (int) Math.round(views * (0.008 + rng.nextDouble() * 0.02));

                TrackedPost p = new TrackedPost();
                p.setTrackedChannelId(c.getId());
                p.setMessageId(message++);
                p.setPostedAt(at);
                p.setKind(kind);
                String tag = s.tags()[rng.nextInt(s.tags().length)];
                p.setBody(s.posts()[rng.nextInt(s.posts().length)] + "\n\nSample post for exploring the viewer. #" + tag);
                p.setViews(views);
                p.setReactions(reactions);
                p.setReactionBreakdown(breakdown(rng, reactions));
                p.setHasLink(rng.nextDouble() < 0.2);
                p.setEdited(rng.nextDouble() < 0.1);
                p.setUpdatedAt(now);
                batch.add(p);
            }
        }
        posts.saveAll(batch);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<TrackedSnapshot> history = new ArrayList<>();
        for (int back = 29; back >= 0; back--) {
            TrackedSnapshot snap = new TrackedSnapshot(c.getId(), today.minusDays(back));
            snap.setSubscribers((int) Math.round(s.subscribers() - back * s.growthPerDay() + (rng.nextDouble() - 0.5) * s.growthPerDay() * 2));
            snap.setAvgViews((int) Math.round(s.subscribers() * s.reach() * (0.9 + rng.nextDouble() * 0.2)));
            history.add(snap);
        }
        history.get(history.size() - 1).setSubscribers(s.subscribers());
        snapshots.saveAll(history);
        watchers.save(new TrackedWatcher(c.getId(), TrackingService.WEB));
    }

    private static String breakdown(Random rng, int total) {
        StringBuilder sb = new StringBuilder();
        int left = total;
        for (int i = 0; i < 3 && left > 0; i++) {
            int n = i == 2 ? left : (int) Math.round(left * (0.45 + rng.nextDouble() * 0.3));
            if (n <= 0) continue;
            if (!sb.isEmpty()) sb.append(',');
            sb.append(EMOJI[(i * 2 + rng.nextInt(2)) % EMOJI.length]).append(':').append(n);
            left -= n;
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
