package com.halty.gramalyzerbot.gaps;

import com.halty.gramalyzerbot.analytics.AnalysisEngine;
import com.halty.gramalyzerbot.analytics.Baseline;
import com.halty.gramalyzerbot.analytics.Sample;
import com.halty.gramalyzerbot.analytics.TextFeatures;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.Links;
import com.halty.gramalyzerbot.post.AnalysisService;
import com.halty.gramalyzerbot.tracking.TrackedChannel;
import com.halty.gramalyzerbot.tracking.TrackedPost;
import com.halty.gramalyzerbot.tracking.TrackedPostRepository;
import com.halty.gramalyzerbot.tracking.TrackedStats;
import com.halty.gramalyzerbot.tracking.TrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Where watched channels win and the user's channel doesn't: formats, posting hours, lengths and topics that do well
 * above their usual for competitors, but that the user rarely posts or that don't work as well for them.
 * <p>
 * Channels of different sizes can't be compared by raw views, so each post is scored against the posts around it in
 * its own channel ("lift", 1.0 = a typical post there at the time): a channel's growth or a slow month doesn't turn
 * into a finding. Competitors are scored by views at about a day old where those were seen, since a post's latest
 * count keeps growing for weeks; the user's channel by views when the public page shows them, otherwise by reactions
 * and comments. Sponsored posts are left out on both sides.
 */
@Service
@RequiredArgsConstructor
public class GapService {

    /** Posts this fresh are still collecting views and reactions. */
    static final Duration SETTLED = Duration.ofHours(24);
    static final Duration LOOK_BACK = Duration.ofDays(60);
    static final int PER_RIVAL = 200;
    static final int MIN_THEIRS = 15;
    static final int MIN_YOURS = 8;
    static final double WIN = 1.15;
    /** A single outlier shouldn't make a bucket look like a winner. */
    static final double CAP = 4;
    /** Words in more than this share of posts are signatures and boilerplate, not topics. */
    static final double BOILERPLATE = 0.35;
    static final int HOURS_BLOCK = 4;
    /** Two findings sharing this share of posts are one. */
    static final double SAME_POSTS = 0.8;
    static final int SHOWN_GAPS = 6;
    static final int SHOWN_EDGES = 3;

    public enum Dimension { FORMAT, HOURS, LENGTH, TOPIC }

    /** The user's channel among {@link Item#channel()}s, which are otherwise competitors' usernames. */
    private static final String YOU = "";

    /** One post, reduced to what the comparison needs. */
    record Item(String channel, String kind, Instant at, int length, Set<String> topics, double value, String preview, String url) {}

    /**
     * @param key        a post kind, the first hour of a {@value #HOURS_BLOCK}-hour block, a length bucket, or a word
     *                   ("#tag" for hashtags)
     * @param missing    true when the user rarely posts this; false when they do, but it works worse for them
     * @param theirShare share of competitors' posts, 0–1
     * @param example    competitors' best post of this kind
     */
    public record Gap(Dimension dimension, String key, boolean missing, double theirLift, Double yourLift, double theirShare,
                      double yourShare, int theirPosts, int yourPosts, int channels, Example example) {}

    /** @param channel the competitor's username */
    public record Example(String channel, String preview, String url, double lift) {}

    public record Rival(String username, String title, Integer subscribers, Integer avgViews, Double err, Double postsPerDay,
                        String topFormat, int posts) {}

    public record You(String title, Integer subscribers, Double postsPerDay, String topFormat, int posts) {}

    /** @param status no_competitors, learning or ready; @param measure VIEWS or ENGAGEMENT, how the user's posts are scored */
    public record Report(String status, String measure, You you, List<Rival> rivals, List<Gap> gaps, List<Gap> edges,
                         int theirPosts, int yourPosts) {}

    private final AnalysisService analysis;
    private final TrackingService tracking;
    private final TrackedPostRepository trackedPosts;

    /** The channel against a watchlist; a sample channel watching only sample channels gets sample competitors in its field. */
    public Report report(Channel ch, String owner) {
        List<TrackedChannel> watched = tracking.watched(owner).stream()
                .filter(c -> ch.getUsername() == null || !c.getUsername().equalsIgnoreCase(ch.getUsername())).toList();
        Instant now = Instant.now();
        Map<TrackedChannel, List<TrackedPost>> rivals = new LinkedHashMap<>();
        // a sample channel compared with sample channels about other subjects would show nonsense
        if (ch.isDemo() && watched.stream().allMatch(TrackedChannel::isDemo)) {
            rivals.putAll(DemoRivals.make(now, ch.zone()));
        } else {
            for (TrackedChannel c : watched) {
                List<TrackedPost> list = trackedPosts.findAllByTrackedChannelIdAndPostedAtAfterOrderByPostedAtDesc(c.getId(), now.minus(LOOK_BACK));
                rivals.put(c, list.subList(0, Math.min(PER_RIVAL, list.size())));
            }
        }
        return compare(ch, analysis.samples(ch), rivals, now);
    }

    Report compare(Channel ch, List<Sample> samples, Map<TrackedChannel, List<TrackedPost>> rivals, Instant now) {
        ZoneId zone = ch.zone();
        List<Sample> settled = samples.stream().filter(s -> !s.excluded() && !TextFeatures.isAd(s.text()) && s.postedAt().isBefore(now.minus(SETTLED))).toList();
        long withViews = settled.stream().filter(s -> s.views() != null).count();
        boolean byViews = !settled.isEmpty() && withViews >= settled.size() * 0.7;
        List<Item> yours = lifted(settled.stream().filter(s -> !byViews || s.views() != null).map(s -> new Item(YOU, s.kind().name(), s.postedAt(),
                s.textLength(), topics(s.text()), byViews ? s.views() : s.reactions() + 3.0 * s.comments(), preview(s.text()),
                Links.post(ch.getTelegramChatId(), ch.getUsername(), ch.isDemo(), s.messageId()))).toList());
        You you = new You(ch.getTitle(), ch.getSubscribers(), perDay(samples.stream().map(Sample::postedAt).toList(), now),
                topFormat(samples.stream().map(s -> s.kind().name()).toList()), yours.size());

        List<Rival> summaries = new ArrayList<>();
        List<Item> theirs = new ArrayList<>();
        for (var e : rivals.entrySet()) {
            TrackedChannel c = e.getKey();
            List<TrackedPost> list = e.getValue();
            TrackedStats stats = TrackedStats.of(c.getSubscribers(), list, List.of(), now, zone);
            summaries.add(new Rival(c.getUsername(), c.getTitle(), c.getSubscribers(), stats.avgViews(), stats.err(), stats.postsPerDay(),
                    stats.kinds().isEmpty() ? null : stats.kinds().getFirst().kind(), list.size()));
            List<TrackedPost> usable = list.stream()
                    .filter(p -> p.getViews() != null && p.getForwardedFrom() == null && !TextFeatures.isAd(p.getBody()) && p.getPostedAt().isBefore(now.minus(SETTLED)))
                    .toList();
            boolean dayOld = !usable.isEmpty() && usable.stream().filter(p -> p.getViews24h() != null).count() >= usable.size() * 0.7;
            theirs.addAll(lifted(usable.stream().filter(p -> !dayOld || p.getViews24h() != null)
                    .map(p -> new Item(c.getUsername(), p.getKind(), p.getPostedAt(), p.getBody() == null ? 0 : p.getBody().codePointCount(0, p.getBody().length()),
                            topics(p.getBody()), dayOld ? p.getViews24h() : p.getViews(), preview(p.getBody()), Links.post(0, c.getUsername(), c.isDemo(), p.getMessageId())))
                    .toList()));
        }
        String measure = byViews ? "VIEWS" : "ENGAGEMENT";
        if (rivals.isEmpty()) return new Report("no_competitors", measure, you, List.of(), List.of(), List.of(), 0, yours.size());
        if (theirs.size() < MIN_THEIRS || yours.size() < MIN_YOURS)
            return new Report("learning", measure, you, summaries, List.of(), List.of(), theirs.size(), yours.size());

        List<Gap> buckets = new ArrayList<>();
        Map<Gap, Set<Item>> members = new IdentityHashMap<>();
        buckets.addAll(buckets(Dimension.FORMAT, theirs, yours, i -> Set.of(i.kind()), members));
        buckets.addAll(buckets(Dimension.HOURS, theirs, yours, i -> Set.of(String.valueOf(i.at().atZone(zone).getHour() / HOURS_BLOCK * HOURS_BLOCK)), members));
        buckets.addAll(buckets(Dimension.LENGTH, theirs, yours, i -> Set.of(AnalysisEngine.lengthBucket(i.length())), members));
        Set<String> boilerplate = boilerplate(concat(theirs, yours));
        buckets.addAll(buckets(Dimension.TOPIC, theirs, yours, i -> i.topics().stream().filter(t -> !boilerplate.contains(t)).collect(Collectors.toSet()), members));
        Map<String, String> words = surfaceForms(theirs, yours);

        List<Gap> gaps = pick(buckets.stream().filter(GapService::isGap).sorted(Comparator.comparingDouble(GapService::weight).reversed()).toList(), SHOWN_GAPS, members);
        List<Gap> edges = pick(buckets.stream().filter(GapService::isEdge)
                .sorted(Comparator.comparingDouble((Gap g) -> g.yourLift() * Math.sqrt(g.yourPosts())).reversed()).toList(), SHOWN_EDGES, members);
        return new Report("ready", measure, you, summaries, named(gaps, words), named(edges, words), theirs.size(), yours.size());
    }

    // ------------------------------------------------------------------ buckets

    private List<Gap> buckets(Dimension d, List<Item> theirs, List<Item> yours, java.util.function.Function<Item, Set<String>> keys,
                              Map<Gap, Set<Item>> members) {
        Map<String, List<Item>> them = group(theirs, keys);
        Map<String, List<Item>> us = group(yours, keys);
        List<Gap> out = new ArrayList<>();
        int min = d == Dimension.TOPIC ? 3 : 4;
        for (var e : them.entrySet()) {
            List<Item> t = e.getValue();
            if (t.size() < min) continue;
            List<Item> y = us.getOrDefault(e.getKey(), List.of());
            Item best = t.stream().max(Comparator.comparingDouble(Item::value)).orElseThrow();
            Gap g = new Gap(d, e.getKey(), false, round(mean(t)), y.isEmpty() ? null : round(mean(y)),
                    round((double) t.size() / theirs.size()), round((double) y.size() / yours.size()), t.size(), y.size(),
                    (int) t.stream().map(Item::channel).distinct().count(),
                    new Example(best.channel(), best.preview(), best.url(), round(best.value())));
            members.put(g, Collections.newSetFromMap(new IdentityHashMap<>()));
            members.get(g).addAll(t);
            members.get(g).addAll(y);
            out.add(g);
        }
        // the user's own strong buckets that competitors barely use
        for (var e : us.entrySet()) {
            if (them.containsKey(e.getKey()) && them.get(e.getKey()).size() >= min) continue;
            List<Item> y = e.getValue();
            if (y.size() < 3) continue;
            List<Item> t = them.getOrDefault(e.getKey(), List.of());
            Gap g = new Gap(d, e.getKey(), false, t.isEmpty() ? 0 : round(mean(t)), round(mean(y)),
                    round((double) t.size() / theirs.size()), round((double) y.size() / yours.size()), t.size(), y.size(),
                    (int) t.stream().map(Item::channel).distinct().count(), null);
            members.put(g, Collections.newSetFromMap(new IdentityHashMap<>()));
            members.get(g).addAll(t);
            members.get(g).addAll(y);
            out.add(g);
        }
        return out;
    }

    private static boolean isGap(Gap g) {
        if (g.theirPosts() < (g.dimension() == Dimension.TOPIC ? 3 : 4) || g.theirLift() < WIN) return false;
        return rarely(g) || g.yourPosts() >= 3 && g.yourLift() != null && g.yourLift() < 1 && g.yourLift() <= g.theirLift() - 0.3;
    }

    private static boolean rarely(Gap g) { return g.yourPosts() < 2 || g.yourShare() < g.theirShare() * 0.5; }

    private static boolean isEdge(Gap g) {
        return g.yourPosts() >= 3 && g.yourLift() != null && g.yourLift() >= WIN && (g.theirPosts() < 3 || g.theirLift() <= g.yourLift() - 0.3);
    }

    private static double weight(Gap g) { return (g.theirLift() - 1) * Math.sqrt(g.theirPosts()) * (rarely(g) ? 1 : 0.8); }

    /**
     * At most {@code max}, no more than half of them from one dimension (so topics don't crowd out formats and hours),
     * and none made of mostly the same posts as one already picked: "videos" and "tutorial" are one finding when all
     * the tutorials are videos.
     */
    private static List<Gap> pick(List<Gap> ranked, int max, Map<Gap, Set<Item>> members) {
        List<Gap> out = new ArrayList<>();
        Map<Dimension, Integer> per = new EnumMap<>(Dimension.class);
        int cap = Math.max(2, max / 2);
        for (Gap g : ranked) {
            if (per.getOrDefault(g.dimension(), 0) >= cap) continue;
            if (out.stream().anyMatch(o -> overlap(members.get(o), members.get(g)) >= SAME_POSTS)) continue;
            // two words from the same few posts ("checklist", "launch") read as one finding with the same example
            if (g.dimension() == Dimension.TOPIC && g.example() != null
                    && out.stream().anyMatch(o -> o.dimension() == Dimension.TOPIC && g.example().equals(o.example()))) continue;
            per.merge(g.dimension(), 1, Integer::sum);
            out.add(g);
            if (out.size() == max) break;
        }
        return out;
    }

    /** Topic stems shown as the word people actually wrote; marks whether each gap is "missing" or "weaker". */
    private static List<Gap> named(List<Gap> gaps, Map<String, String> words) {
        return gaps.stream().map(g -> new Gap(g.dimension(), g.dimension() == Dimension.TOPIC ? words.getOrDefault(g.key(), g.key()) : g.key(),
                rarely(g), g.theirLift(), g.yourLift(), g.theirShare(), g.yourShare(), g.theirPosts(), g.yourPosts(), g.channels(), g.example())).toList();
    }

    private static Map<String, List<Item>> group(List<Item> items, java.util.function.Function<Item, Set<String>> keys) {
        Map<String, List<Item>> out = new HashMap<>();
        for (Item i : items) for (String k : keys.apply(i)) out.computeIfAbsent(k, x -> new ArrayList<>()).add(i);
        return out;
    }

    // ------------------------------------------------------------------ posts

    /**
     * Each post's value over the median of the posts around it in its channel, capped. Neighbours are also about the
     * same age, which matters for competitors whose posts were read once at very different ages.
     */
    private static List<Item> lifted(List<Item> items) {
        Map<String, List<Item>> byChannel = items.stream().collect(Collectors.groupingBy(Item::channel));
        List<Item> out = new ArrayList<>();
        for (List<Item> unsorted : byChannel.values()) {
            List<Item> list = unsorted.stream().sorted(Comparator.comparing(Item::at)).toList();
            double[] ratios = Baseline.ratios(list.stream().mapToDouble(Item::value).toArray());
            for (int k = 0; k < list.size(); k++) {
                Item i = list.get(k);
                out.add(new Item(i.channel(), i.kind(), i.at(), i.length(), i.topics(), Math.min(CAP, ratios[k]), i.preview(), i.url()));
            }
        }
        return out;
    }

    /** Word stems and hashtags ("#tag") of a post. */
    static Set<String> topics(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : TextFeatures.keywords(text)) out.add(TextFeatures.stem(w));
        for (String t : TextFeatures.hashtags(text)) out.add("#" + t);
        return out;
    }

    private static Set<String> boilerplate(List<Item> theirs) {
        Map<String, List<Item>> byChannel = theirs.stream().collect(Collectors.groupingBy(Item::channel));
        Set<String> out = new HashSet<>();
        for (List<Item> list : byChannel.values()) {
            Map<String, Integer> df = new HashMap<>();
            for (Item i : list) for (String t : i.topics()) df.merge(t, 1, Integer::sum);
            df.forEach((t, n) -> {if (n > list.size() * BOILERPLATE) out.add(t);});
        }
        return out;
    }

    /** For each stem, the full word written most often. */
    private static Map<String, String> surfaceForms(List<Item> theirs, List<Item> yours) {
        Map<String, Map<String, Integer>> counts = new HashMap<>();
        for (Item i : theirs) count(counts, i.preview());
        for (Item i : yours) count(counts, i.preview());
        Map<String, String> out = new HashMap<>();
        counts.forEach((stem, forms) -> out.put(stem, forms.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder()))).orElseThrow().getKey()));
        return out;
    }

    private static void count(Map<String, Map<String, Integer>> counts, String text) {
        for (String w : TextFeatures.keywords(text)) counts.computeIfAbsent(TextFeatures.stem(w), k -> new HashMap<>()).merge(w, 1, Integer::sum);
    }

    private static String preview(String text) {
        if (text == null) return "";
        String s = text.strip().replaceAll("\\s+", " ");
        return s.codePointCount(0, s.length()) <= 160 ? s : s.substring(0, s.offsetByCodePoints(0, 160)) + "…";
    }

    private static Double perDay(List<Instant> times, Instant now) {
        long month = times.stream().filter(t -> t.isAfter(now.minus(Duration.ofDays(30)))).count();
        return times.isEmpty() ? null : round(month / 30.0);
    }

    private static String topFormat(List<String> kinds) {
        return kinds.stream().collect(Collectors.groupingBy(k -> k, Collectors.counting())).entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /** Shared posts over the smaller set's size. */
    private static double overlap(Set<Item> a, Set<Item> b) {
        Set<Item> small = a.size() <= b.size() ? a : b, big = small == a ? b : a;
        return small.isEmpty() ? 0 : (double) small.stream().filter(big::contains).count() / small.size();
    }

    private static List<Item> concat(List<Item> a, List<Item> b) {
        List<Item> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static double mean(List<Item> items) { return items.stream().mapToDouble(Item::value).average().orElse(0); }

    private static double round(double x) { return Math.round(x * 100) / 100.0; }
}
