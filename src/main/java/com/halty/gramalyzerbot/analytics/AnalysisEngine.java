package com.halty.gramalyzerbot.analytics;

import com.halty.gramalyzerbot.analytics.Report.*;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Finds the best times to post and the content that performs, from views or reactions/comments.
 *
 * <p>Results are heavy-tailed, so everything is computed on log(1 + score), and every post is measured against the
 * channel's posts around it ({@link Baseline}) rather than its all-time average: growth, slumps and Telegram-wide reach
 * changes then cancel out instead of masquerading as "what works". A group's "lift" is
 * exp(shrunkMean(group) − mean(all)) – read it as "× a typical post". Small groups are pulled towards the overall mean
 * so a lucky streak of three posts doesn't look like a trend, and every lift carries a 90% range.
 *
 * <p>For timing, an additive weekday + hour model fills in cells that have little data, and the observed cell mean is
 * blended in as evidence accumulates. Until a channel has {@value #MIN_POSTS_FOR_HOURS} posts, best times are picked
 * among 4-hour blocks: with fewer posts, the best of 168 single hours is mostly luck.
 *
 * <p>Sponsored posts are left out: they say nothing about what the channel's own readers like.
 */
public final class AnalysisEngine {

    private static final double K_GROUP = 3, K_KEYWORD = 4, K_CELL = 2, K_AXIS = 3;
    private static final double Z90 = 1.645;
    private static final int MIN_SUPPORT = 2, MIN_BLOCK_SUPPORT = 3, MIN_DAYS = 5;
    public static final int MIN_POSTS_FOR_TIMES = 20;
    public static final int MIN_POSTS_FOR_HOURS = 100;
    public static final int MIN_POSTS_FOR_KEYWORDS = 40;
    public static final int MIN_POSTS_FOR_MODEL = 10;

    public Report analyze(List<Sample> all, Options o) {
        Metric metric = o.metric().resolve(all, o.now());
        Instant from = o.windowDays() > 0 ? o.now().minus(Duration.ofDays(o.windowDays())) : Instant.MIN;
        Instant matureBefore = o.now().minus(o.maturity());

        int inWindow = 0, excluded = 0, immature = 0, ads = 0;
        List<Sample> use = new ArrayList<>();
        for (Sample s : all) {
            if (s.postedAt().isBefore(from) || s.postedAt().isAfter(o.now())) continue;
            inWindow++;
            if (s.excluded()) { excluded++; continue; }
            if (TextFeatures.isAd(s.text())) { excluded++; ads++; continue; }
            if (s.postedAt().isAfter(matureBefore) || !metric.scores(s)) { immature++; continue; }
            use.add(s);
        }
        use.sort(Comparator.comparing(Sample::postedAt));
        int n = use.size();
        if (n == 0) return Report.empty(inWindow, excluded, immature, List.of(Report.Note.FEW_POSTS), metric, ads);

        double[] score = new double[n], raw = new double[n];
        double sumScore = 0, sumReact = 0, sumComm = 0;
        int commKnown = 0;
        for (int i = 0; i < n; i++) {
            Sample s = use.get(i);
            score[i] = metric.score(s);
            raw[i] = Math.log1p(score[i]);
            sumScore += score[i];
            sumReact += s.reactions();
            if (s.commentsKnown()) { sumComm += s.comments(); commKnown++; }
        }
        List<Note> notes = new ArrayList<>();
        if (n < MIN_POSTS_FOR_TIMES) notes.add(Note.FEW_POSTS);
        if (sumScore == 0) {
            notes.add(Note.NO_SIGNAL);
            return Report.empty(inWindow, excluded, immature, notes, metric, ads);
        }
        // each post against the posts around it
        double[] base = Baseline.medians(raw);
        double[] v = new double[n];
        double sumV = 0;
        for (int i = 0; i < n; i++) {
            v[i] = raw[i] - base[i];
            sumV += v[i];
        }
        double mu = sumV / n;
        double ss = 0;
        for (double x : v) ss += (x - mu) * (x - mu);
        double sd = n > 1 ? Math.sqrt(ss / (n - 1)) : 0;

        // ---------- when ----------
        int[] day = new int[n], hour = new int[n];
        double[][] cellSum = new double[7][24];
        int[][] cellN = new int[7][24];
        double[] daySum = new double[7], hourSum = new double[24];
        int[] dayN = new int[7], hourN = new int[24];
        for (int i = 0; i < n; i++) {
            ZonedDateTime t = use.get(i).postedAt().atZone(o.zone());
            day[i] = t.getDayOfWeek().getValue() - 1;
            hour[i] = t.getHour();
            cellSum[day[i]][hour[i]] += v[i];
            cellN[day[i]][hour[i]]++;
            daySum[day[i]] += v[i];
            dayN[day[i]]++;
            hourSum[hour[i]] += v[i];
            hourN[hour[i]]++;
        }
        double[] dayMean = new double[7], hourMean = new double[24];
        for (int d = 0; d < 7; d++) dayMean[d] = (daySum[d] + K_AXIS * mu) / (dayN[d] + K_AXIS);
        for (int h = 0; h < 24; h++) {
            int a = (h + 23) % 24, b = (h + 1) % 24;
            double hs = 0.25 * hourSum[a] + 0.5 * hourSum[h] + 0.25 * hourSum[b];
            double hn = 0.25 * hourN[a] + 0.5 * hourN[h] + 0.25 * hourN[b];
            hourMean[h] = (hs + K_AXIS * mu) / (hn + K_AXIS);
        }
        double[][] grid = new double[7][24];
        int[][] support = new int[7][24];
        for (int d = 0; d < 7; d++) {
            for (int h = 0; h < 24; h++) {
                double pred = dayMean[d] + hourMean[h] - mu;
                double est = (cellSum[d][h] + K_CELL * pred) / (cellN[d][h] + K_CELL);
                grid[d][h] = Math.exp(est - mu);
                support[d][h] = cellN[d][(h + 23) % 24] + cellN[d][h] + cellN[d][(h + 1) % 24];
            }
        }
        Comparator<Slot> byLift = Comparator.comparingDouble(Slot::lift).reversed()
                .thenComparing(Comparator.comparingInt(Slot::support).reversed());
        List<Slot> candidates = new ArrayList<>();
        List<Slot> untried = new ArrayList<>();
        int minSupport;
        if (n >= MIN_POSTS_FOR_HOURS) {
            minSupport = MIN_SUPPORT;
            for (int d = 0; d < 7; d++)
                for (int h = 0; h < 24; h++) {
                    Slot s = new Slot(DayOfWeek.of(d + 1), h, grid[d][h], support[d][h]);
                    candidates.add(s);
                    if (s.support() == 0 && hourN[h] >= 3) untried.add(s);
                }
        } else {
            minSupport = MIN_BLOCK_SUPPORT;
            int blocks = 24 / PostModel.BLOCK_HOURS;
            double[] blockSum = new double[blocks];
            int[] blockN = new int[blocks];
            for (int h = 0; h < 24; h++) { blockSum[h / PostModel.BLOCK_HOURS] += hourSum[h]; blockN[h / PostModel.BLOCK_HOURS] += hourN[h]; }
            for (int d = 0; d < 7; d++)
                for (int b = 0; b < blocks; b++) {
                    int start = b * PostModel.BLOCK_HOURS;
                    double sum = 0;
                    int cnt = 0, bestHour = start + 1, bestN = 0, bestAny = 0;
                    for (int h = start; h < start + PostModel.BLOCK_HOURS; h++) {
                        sum += cellSum[d][h];
                        cnt += cellN[d][h];
                        // post at the hour the channel already uses in this block: that is what the evidence is about
                        int weight = cellN[d][h] * 100 + hourN[h];
                        if (weight > bestN * 100 + bestAny) { bestHour = h; bestN = cellN[d][h]; bestAny = hourN[h]; }
                    }
                    double blockMean = (blockSum[b] + K_AXIS * mu) / (blockN[b] + K_AXIS);
                    double pred = dayMean[d] + blockMean - mu;
                    double est = (sum + K_CELL * pred) / (cnt + K_CELL);
                    Slot s = new Slot(DayOfWeek.of(d + 1), bestHour, Math.exp(est - mu), cnt, start, PostModel.BLOCK_HOURS);
                    candidates.add(s);
                    if (cnt == 0 && blockN[b] >= 3) untried.add(s);
                }
        }
        List<Slot> supported = candidates.stream().filter(s -> s.support() >= minSupport).sorted(byLift).toList();
        List<Slot> topSlots = pick(supported, 5);

        Map<DayOfWeek, List<Slot>> bestPerDay = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek dow : DayOfWeek.values()) {
            List<Slot> c = supported.stream().filter(s -> s.day() == dow && s.lift() >= 1.0).toList();
            List<Slot> best = pick(c, 2);
            if (!best.isEmpty()) bestPerDay.put(dow, best);
        }
        List<Slot> experiments = pick(untried.stream().filter(s -> s.lift() >= 1.1).sorted(byLift).toList(), 2);

        List<DayStat> days = new ArrayList<>();
        for (int d = 0; d < 7; d++) days.add(new DayStat(DayOfWeek.of(d + 1), dayN[d], Math.exp(dayMean[d] - mu)));
        List<HourStat> hours = new ArrayList<>();
        for (int h = 0; h < 24; h++) hours.add(new HourStat(h, hourN[h], Math.exp(hourMean[h] - mu)));

        int[] sortedHours = hourN.clone();
        Arrays.sort(sortedHours);
        int top3 = sortedHours[23] + sortedHours[22] + sortedHours[21];
        if (n >= MIN_POSTS_FOR_TIMES && top3 >= 0.8 * n) notes.add(Note.LOW_TIME_VARIETY);

        // ---------- what ----------
        Map<String, List<Integer>> kinds = new HashMap<>(), lengths = new HashMap<>(), links = new HashMap<>(),
                topics = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Sample s = use.get(i);
            kinds.computeIfAbsent(s.kind().name(), k -> new ArrayList<>()).add(i);
            lengths.computeIfAbsent(lengthBucket(s.textLength()), k -> new ArrayList<>()).add(i);
            links.computeIfAbsent(s.hasLink() ? "LINK" : "NOLINK", k -> new ArrayList<>()).add(i);
            for (String t : new LinkedHashSet<>(s.topics())) topics.computeIfAbsent(t, k -> new ArrayList<>()).add(i);
        }
        List<Group> linkGroups = groups(links, 3, K_GROUP, v, score, mu, sd);
        if (linkGroups.size() < 2) linkGroups = List.of();

        List<Group> keywordGroups = n >= MIN_POSTS_FOR_KEYWORDS ? keywordGroups(use, v, score, mu, sd) : List.of();

        // posting frequency: a day's total against what one post that day would usually get
        Map<LocalDate, List<Integer>> perDay = new TreeMap<>();
        for (int i = 0; i < n; i++) perDay.computeIfAbsent(use.get(i).postedAt().atZone(o.zone()).toLocalDate(), k -> new ArrayList<>()).add(i);
        List<Group> freqGroups = frequency(perDay, score, base);

        // cadence
        Instant first = use.get(0).postedAt(), last = use.get(n - 1).postedAt();
        double weeks = Math.max(1.0, Duration.between(first, last).toHours() / 168.0);
        double longestGap = 0;
        for (int i = 1; i < n; i++)
            longestGap = Math.max(longestGap, Duration.between(use.get(i - 1).postedAt(), use.get(i).postedAt()).toHours() / 24.0);
        DayOfWeek mostPosted = null, bestDay = null;
        int maxN = -1;
        double bestLift = -1;
        for (DayStat ds : days) {
            if (ds.posts() > maxN) { maxN = ds.posts(); mostPosted = ds.day(); }
            if (ds.posts() >= 3 && ds.lift() > bestLift) { bestLift = ds.lift(); bestDay = ds.day(); }
        }
        Cadence cadence = new Cadence(n / weeks, mostPosted, bestDay, longestGap, last);

        List<Scored> top = new ArrayList<>();
        for (int i = 0; i < n; i++) top.add(new Scored(use.get(i), score[i]));
        top.sort(Comparator.comparingDouble(Scored::score).reversed());
        if (top.size() > 50) top = new ArrayList<>(top.subList(0, 50));

        PostModel model = n >= MIN_POSTS_FOR_MODEL ? PostModel.fit(use, v, o.zone()) : null;
        Backtest.Check check = Backtest.run(use, v, o.zone());

        Confidence conf = n < 30 ? Confidence.LOW : n < 100 ? Confidence.MEDIUM : Confidence.HIGH;
        return new Report(inWindow, n, excluded, immature, conf, notes,
                sumReact / n, commKnown == 0 ? 0 : sumComm / commKnown,
                topSlots, experiments, bestPerDay, days, hours, grid, cellN,
                groups(kinds, 3, K_GROUP, v, score, mu, sd), groups(lengths, 3, K_GROUP, v, score, mu, sd), linkGroups,
                groups(topics, 3, K_GROUP, v, score, mu, sd), keywordGroups, freqGroups, cadence, top,
                metric, ads, model, check);
    }

    /**
     * Days grouped by how many posts went out ("1", "2", "3+"): each day's total result against what a single post
     * would usually have got then, as a lift over a one-post day. Posting more always lowers views per post; what
     * matters is whether the day as a whole gains.
     */
    private static List<Group> frequency(Map<LocalDate, List<Integer>> perDay, double[] score, double[] base) {
        Map<String, List<Double>> w = new HashMap<>();
        Map<String, Double> totals = new HashMap<>();
        for (List<Integer> idx : perDay.values()) {
            double total = 0, typical = 0;
            for (int i : idx) { total += score[i]; typical += Math.expm1(base[i]); }
            typical /= idx.size();
            String key = idx.size() >= 3 ? "3+" : String.valueOf(idx.size());
            w.computeIfAbsent(key, k -> new ArrayList<>()).add(Math.log1p(total) - Math.log1p(typical));
            totals.merge(key, total, Double::sum);
        }
        List<Double> everyDay = w.values().stream().flatMap(List::stream).toList();
        if (w.values().stream().filter(l -> l.size() >= MIN_DAYS).count() < 2) return List.of();
        double overall = everyDay.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double ss = 0;
        for (double x : everyDay) ss += (x - overall) * (x - overall);
        double sd = everyDay.size() > 1 ? Math.sqrt(ss / (everyDay.size() - 1)) : 0;
        Map<String, double[]> shrunk = new HashMap<>();
        w.forEach((k, l) -> {
            if (l.size() < MIN_DAYS) return;
            double sum = l.stream().mapToDouble(Double::doubleValue).sum();
            shrunk.put(k, new double[]{(sum + K_GROUP * overall) / (l.size() + K_GROUP), sd / Math.sqrt(l.size() + K_GROUP)});
        });
        double ref = shrunk.containsKey("1") ? shrunk.get("1")[0] : overall;
        List<Group> out = new ArrayList<>();
        shrunk.forEach((k, m) -> out.add(new Group(k, w.get(k).size(), Math.exp(m[0] - ref), totals.get(k) / w.get(k).size(),
                Math.exp(m[0] - ref - Z90 * m[1]), Math.exp(m[0] - ref + Z90 * m[1]))));
        out.sort(Comparator.comparing(Group::key));
        return out;
    }

    /** Greedy pick of the best slots, skipping ones adjacent (same day, ±1h) to an already chosen slot. */
    private static List<Slot> pick(List<Slot> sorted, int max) {
        List<Slot> out = new ArrayList<>();
        for (Slot s : sorted) {
            boolean clash = false;
            for (Slot p : out) {
                int dh = Math.abs(p.hour() - s.hour());
                boolean near = s.span() > 1 ? p.from() == s.from() : Math.min(dh, 24 - dh) <= 1;
                if (p.day() == s.day() && near) { clash = true; break; }
            }
            if (!clash) out.add(s);
            if (out.size() == max) break;
        }
        return out;
    }

    public static String lengthBucket(int len) {
        if (len == 0) return "NONE";
        if (len <= 120) return "SHORT";
        if (len <= 400) return "MEDIUM";
        if (len <= 800) return "LONG";
        return "XLONG";
    }

    private static List<Group> groups(Map<String, List<Integer>> map, int minPosts, double k,
                                      double[] v, double[] score, double mu, double sd) {
        List<Group> out = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : map.entrySet()) {
            if (e.getValue().size() < minPosts) continue;
            out.add(group(e.getKey(), e.getValue(), k, v, score, mu, sd));
        }
        out.sort(Comparator.comparingDouble(Group::lift).reversed().thenComparing(Group::key));
        return out;
    }

    private static Group group(String key, List<Integer> idx, double k, double[] v, double[] score, double mu, double sd) {
        double sv = 0, ss = 0;
        for (int i : idx) { sv += v[i]; ss += score[i]; }
        int m = idx.size();
        double shrunk = (sv + k * mu) / (m + k);
        double se = sd / Math.sqrt(m + k);
        return new Group(key, m, Math.exp(shrunk - mu), ss / m, Math.exp(shrunk - mu - Z90 * se), Math.exp(shrunk - mu + Z90 * se));
    }

    /** A word must appear in at least this many of {@code n} posts to be judged. */
    public static int minKeywordPosts(int n) { return Math.max(4, (int) Math.ceil(0.02 * n)); }

    /** The spelling used most often; ties go to the shorter, then alphabetically first one. */
    private static String commonestForm(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .min(Comparator.<Map.Entry<String, Integer>>comparingInt(e -> -e.getValue())
                        .thenComparingInt(e -> e.getKey().length())
                        .thenComparing(Map.Entry::getKey))
                .orElseThrow().getKey();
    }

    private static List<Group> keywordGroups(List<Sample> use, double[] v, double[] score, double mu, double sd) {
        int n = use.size();
        // posts per word stem, and how often each spelling of that stem occurs (the commonest one is shown)
        Map<String, List<Integer>> df = new HashMap<>();
        Map<String, Map<String, Integer>> forms = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Sample s = use.get(i);
            Set<String> own = new HashSet<>();
            for (String t : s.topics()) own.add(TextFeatures.stem(t));
            Set<String> seen = new HashSet<>();
            for (String kw : TextFeatures.keywords(s.text())) {
                String st = TextFeatures.stem(kw);
                if (own.contains(st)) continue;
                forms.computeIfAbsent(st, x -> new HashMap<>()).merge(kw, 1, Integer::sum);
                if (seen.add(st)) df.computeIfAbsent(st, x -> new ArrayList<>()).add(i);
            }
        }
        int min = minKeywordPosts(n), max = (int) Math.floor(0.4 * n);
        // words that always appear together (a repeated phrase) say the same thing: keep the longest one
        Map<List<Integer>, String> representative = new HashMap<>();
        for (Map.Entry<String, List<Integer>> e : df.entrySet()) {
            int c = e.getValue().size();
            if (c < min || c > max) continue;
            representative.merge(e.getValue(), commonestForm(forms.get(e.getKey())),
                    (a, b) -> a.length() != b.length() ? (a.length() > b.length() ? a : b) : (a.compareTo(b) <= 0 ? a : b));
        }
        List<Group> out = new ArrayList<>();
        for (Map.Entry<List<Integer>, String> e : representative.entrySet()) {
            Group g = group(e.getValue(), e.getKey(), K_KEYWORD, v, score, mu, sd);
            if (g.lift() >= 1.1 || g.lift() <= 0.9) out.add(g);
        }
        out.sort(Comparator.comparingDouble(Group::lift).reversed().thenComparing(Group::key));
        if (out.size() <= 60) return out;
        List<Group> capped = new ArrayList<>(out.subList(0, 30));
        capped.addAll(out.subList(out.size() - 30, out.size()));
        return capped;
    }
}
