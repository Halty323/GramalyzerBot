package com.halty.gramalyzerbot.analytics;

import com.halty.gramalyzerbot.analytics.Report.*;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Finds the best times to post and the content that performs, from reactions/comments.
 *
 * <p>Engagement is heavy-tailed, so everything is computed on log(1 + score). A group's "lift" is
 * exp(shrunkMean(group) − mean(all)) – read it as "× typical engagement". Small groups are pulled
 * towards the overall mean so a lucky streak of three posts doesn't look like a trend.
 *
 * <p>For timing, an additive weekday + hour model fills in cells that have little data, and the
 * observed cell mean is blended in as evidence accumulates.
 */
public final class AnalysisEngine {

    private static final double K_GROUP = 3, K_KEYWORD = 4, K_CELL = 2, K_AXIS = 3;
    private static final int MIN_SUPPORT = 2;
    public static final int MIN_POSTS_FOR_TIMES = 20;
    public static final int MIN_POSTS_FOR_KEYWORDS = 40;

    public Report analyze(List<Sample> all, Options o) {
        Instant from = o.windowDays() > 0 ? o.now().minus(Duration.ofDays(o.windowDays())) : Instant.MIN;
        Instant matureBefore = o.now().minus(o.maturity());

        int inWindow = 0, excluded = 0, immature = 0;
        List<Sample> use = new ArrayList<>();
        for (Sample s : all) {
            if (s.postedAt().isBefore(from) || s.postedAt().isAfter(o.now())) continue;
            inWindow++;
            if (s.excluded()) { excluded++; continue; }
            if (s.postedAt().isAfter(matureBefore)) { immature++; continue; }
            use.add(s);
        }
        use.sort(Comparator.comparing(Sample::postedAt));
        int n = use.size();
        if (n == 0) return Report.empty(inWindow, excluded, immature, List.of(Report.Note.FEW_POSTS));

        double[] score = new double[n], v = new double[n];
        double sumScore = 0, sumV = 0, sumReact = 0, sumComm = 0;
        int commKnown = 0;
        for (int i = 0; i < n; i++) {
            Sample s = use.get(i);
            score[i] = o.metric().score(s.reactions(), s.comments(), s.commentsKnown());
            v[i] = Math.log1p(score[i]);
            sumScore += score[i];
            sumV += v[i];
            sumReact += s.reactions();
            if (s.commentsKnown()) { sumComm += s.comments(); commKnown++; }
        }
        double mu = sumV / n;
        List<Note> notes = new ArrayList<>();
        if (n < MIN_POSTS_FOR_TIMES) notes.add(Note.FEW_POSTS);
        if (sumScore == 0) {
            notes.add(Note.NO_SIGNAL);
            return Report.empty(inWindow, excluded, immature, notes);
        }

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
        List<Slot> all168 = new ArrayList<>();
        for (int d = 0; d < 7; d++)
            for (int h = 0; h < 24; h++)
                all168.add(new Slot(DayOfWeek.of(d + 1), h, grid[d][h], support[d][h]));
        Comparator<Slot> byLift = Comparator.comparingDouble(Slot::lift).reversed()
                .thenComparing(Comparator.comparingInt(Slot::support).reversed());

        List<Slot> supported = all168.stream().filter(s -> s.support() >= MIN_SUPPORT).sorted(byLift).toList();
        List<Slot> topSlots = pick(supported, 5);

        Map<DayOfWeek, List<Slot>> bestPerDay = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek dow : DayOfWeek.values()) {
            List<Slot> c = supported.stream().filter(s -> s.day() == dow && s.lift() >= 1.0).toList();
            List<Slot> best = pick(c, 2);
            if (!best.isEmpty()) bestPerDay.put(dow, best);
        }
        List<Slot> experiments = pick(all168.stream()
                .filter(s -> s.support() == 0 && hourN[s.hour()] >= 3 && s.lift() >= 1.1)
                .sorted(byLift).toList(), 2);

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
        List<Group> linkGroups = groups(links, 3, K_GROUP, v, score, mu);
        if (linkGroups.size() < 2) linkGroups = List.of();

        List<Group> keywordGroups = n >= MIN_POSTS_FOR_KEYWORDS ? keywordGroups(use, v, score, mu) : List.of();

        // posting frequency: how many posts that local day
        Map<LocalDate, Integer> perDay = new HashMap<>();
        LocalDate[] dates = new LocalDate[n];
        for (int i = 0; i < n; i++) {
            dates[i] = use.get(i).postedAt().atZone(o.zone()).toLocalDate();
            perDay.merge(dates[i], 1, Integer::sum);
        }
        Map<String, List<Integer>> freq = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int c = perDay.get(dates[i]);
            freq.computeIfAbsent(c >= 3 ? "3+" : String.valueOf(c), k -> new ArrayList<>()).add(i);
        }
        List<Group> freqGroups = groups(freq, 5, K_GROUP, v, score, mu);
        if (freqGroups.size() < 2) freqGroups = List.of();
        freqGroups = freqGroups.stream().sorted(Comparator.comparing(Group::key)).toList();

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

        Confidence conf = n < 30 ? Confidence.LOW : n < 100 ? Confidence.MEDIUM : Confidence.HIGH;
        return new Report(inWindow, n, excluded, immature, conf, notes,
                sumReact / n, commKnown == 0 ? 0 : sumComm / commKnown,
                topSlots, experiments, bestPerDay, days, hours, grid, cellN,
                groups(kinds, 3, K_GROUP, v, score, mu), groups(lengths, 3, K_GROUP, v, score, mu), linkGroups,
                groups(topics, 3, K_GROUP, v, score, mu), keywordGroups, freqGroups, cadence, top);
    }

    /** Greedy pick of the best slots, skipping ones adjacent (same day, ±1h) to an already chosen slot. */
    private static List<Slot> pick(List<Slot> sorted, int max) {
        List<Slot> out = new ArrayList<>();
        for (Slot s : sorted) {
            boolean clash = false;
            for (Slot p : out) {
                int dh = Math.abs(p.hour() - s.hour());
                if (p.day() == s.day() && Math.min(dh, 24 - dh) <= 1) { clash = true; break; }
            }
            if (!clash) out.add(s);
            if (out.size() == max) break;
        }
        return out;
    }

    static String lengthBucket(int len) {
        if (len == 0) return "NONE";
        if (len <= 120) return "SHORT";
        if (len <= 400) return "MEDIUM";
        if (len <= 800) return "LONG";
        return "XLONG";
    }

    private static List<Group> groups(Map<String, List<Integer>> map, int minPosts, double k,
                                      double[] v, double[] score, double mu) {
        List<Group> out = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : map.entrySet()) {
            if (e.getValue().size() < minPosts) continue;
            out.add(group(e.getKey(), e.getValue(), k, v, score, mu));
        }
        out.sort(Comparator.comparingDouble(Group::lift).reversed().thenComparing(Group::key));
        return out;
    }

    private static Group group(String key, List<Integer> idx, double k, double[] v, double[] score, double mu) {
        double sv = 0, ss = 0;
        for (int i : idx) { sv += v[i]; ss += score[i]; }
        int m = idx.size();
        double shrunk = (sv + k * mu) / (m + k);
        return new Group(key, m, Math.exp(shrunk - mu), ss / m);
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

    private static List<Group> keywordGroups(List<Sample> use, double[] v, double[] score, double mu) {
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
            Group g = group(e.getValue(), e.getKey(), K_KEYWORD, v, score, mu);
            if (g.lift() >= 1.1 || g.lift() <= 0.9) out.add(g);
        }
        out.sort(Comparator.comparingDouble(Group::lift).reversed().thenComparing(Group::key));
        if (out.size() <= 60) return out;
        List<Group> capped = new ArrayList<>(out.subList(0, 30));
        capped.addAll(out.subList(out.size() - 30, out.size()));
        return capped;
    }
}
