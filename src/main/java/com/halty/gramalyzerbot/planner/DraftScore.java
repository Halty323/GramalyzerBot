package com.halty.gramalyzerbot.planner;

import com.halty.gramalyzerbot.analytics.AnalysisEngine;
import com.halty.gramalyzerbot.analytics.Report;
import com.halty.gramalyzerbot.analytics.TextFeatures;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * How a draft is likely to do on its channel, from what worked there before: its format, length, link, time slot,
 * hashtags and words, each read off the channel's analysis as a lift (1.0 = a typical post). The expected lift
 * combines them, the weaker signals with less weight, and the score puts it on 1–99 (50 = typical, 100 = twice that).
 */
public final class DraftScore {

    public enum Dimension { FORMAT, LENGTH, LINK, TIME, HASHTAGS, KEYWORDS }

    /** @param kind a {@link com.halty.gramalyzerbot.analytics.PostKind} name; @param at null when not chosen yet */
    public record Draft(String text, String kind, boolean link, Instant at) {}

    /**
     * @param key     what the draft has (a kind, a length bucket, LINK/NOLINK, "MONDAY 18", hashtags, words)
     * @param bestKey the channel's best choice on this dimension, when it's clearly better than the draft's
     */
    public record Factor(Dimension dimension, String key, double lift, String bestKey, Double bestLift, boolean weak) {}

    /** @param status learning (too few posts to judge) or ready; @param potential the expected lift with every weak factor fixed */
    public record Result(String status, int score, double expected, double potential, List<Factor> factors, int postsRead) {}

    static final int MIN_POSTS = 10;
    /** A factor is worth fixing when the channel's best option beats it by this much. */
    static final double BETTER = 0.15;
    /** One weekday/hour cell rests on few posts, so time counts half and no single factor moves the result past these. */
    private static final double LOWEST = 0.5, HIGHEST = 2;
    private static final Map<Dimension, Double> WEIGHT = Map.of(Dimension.FORMAT, 1.0, Dimension.TIME, 0.5, Dimension.LENGTH, 0.7,
            Dimension.LINK, 0.5, Dimension.HASHTAGS, 0.5, Dimension.KEYWORDS, 0.5);

    private DraftScore() {}

    public static Result score(Report r, Draft d, ZoneId zone) {
        if (!r.hasSignal() || r.eligible() < MIN_POSTS) return new Result("learning", 50, 1, 1, List.of(), r.eligible());
        String text = d.text() == null ? "" : d.text();
        List<Factor> factors = new ArrayList<>();
        group(r.kinds(), d.kind()).ifPresent(g -> factors.add(compare(Dimension.FORMAT, g.key(), g.lift(), best(r.kinds()))));
        group(r.lengths(), AnalysisEngine.lengthBucket(text.codePointCount(0, text.length())))
                .ifPresent(g -> factors.add(compare(Dimension.LENGTH, g.key(), g.lift(), best(r.lengths()))));
        group(r.links(), d.link() ? "LINK" : "NOLINK").ifPresent(g -> factors.add(compare(Dimension.LINK, g.key(), g.lift(), best(r.links()))));

        if (d.at() != null) {
            ZonedDateTime local = d.at().atZone(zone);
            int day = local.getDayOfWeek().getValue() - 1, hour = local.getHour();
            Optional<Report.Group> top = r.topSlots().stream().findFirst()
                    .map(s -> new Report.Group(s.day().name() + " " + s.hour(), s.support(), s.lift(), 0));
            factors.add(compare(Dimension.TIME, local.getDayOfWeek().name() + " " + hour, r.grid()[day][hour], top));
        }

        List<String> tags = TextFeatures.hashtags(text);
        List<Report.Group> used = r.topics().stream().filter(g -> tags.contains(g.key())).toList();
        Optional<Report.Group> winner = r.topics().stream().filter(g -> g.lift() >= 1.2 && !tags.contains(g.key()))
                .max(Comparator.comparingDouble(Report.Group::lift));
        if (!used.isEmpty()) {
            double lift = used.stream().mapToDouble(Report.Group::lift).average().orElse(1);
            factors.add(compare(Dimension.HASHTAGS, String.join(" ", used.stream().map(g -> "#" + g.key()).toList()), lift,
                    winner.map(w -> new Report.Group("#" + w.key(), w.posts(), w.lift(), 0))));
        } else if (winner.isPresent()) {
            factors.add(compare(Dimension.HASHTAGS, "", 1, winner.map(w -> new Report.Group("#" + w.key(), w.posts(), w.lift(), 0))));
        }

        Set<String> stems = new HashSet<>();
        for (String w : TextFeatures.keywords(text)) stems.add(TextFeatures.stem(w));
        List<Report.Group> words = r.keywords().stream().filter(g -> stems.contains(TextFeatures.stem(g.key()))).toList();
        if (!words.isEmpty()) {
            factors.add(new Factor(Dimension.KEYWORDS, String.join(", ", words.stream().map(Report.Group::key).toList()),
                    round(words.stream().mapToDouble(Report.Group::lift).average().orElse(1)), null, null, false));
        }

        double expected = combine(factors, false), potential = combine(factors, true);
        int score = (int) Math.round(Math.max(1, Math.min(99, 50 + 50 * Math.log(expected) / Math.log(2))));
        return new Result("ready", score, round(expected), round(Math.max(expected, potential)), factors, r.eligible());
    }

    private static Optional<Report.Group> group(List<Report.Group> groups, String key) {
        return groups.stream().filter(g -> g.key().equals(key)).findFirst();
    }

    private static Optional<Report.Group> best(List<Report.Group> groups) {
        return groups.stream().filter(g -> g.posts() >= 3).max(Comparator.comparingDouble(Report.Group::lift));
    }

    private static Factor compare(Dimension d, String key, double lift, Optional<Report.Group> best) {
        boolean weak = best.isPresent() && !best.get().key().equals(key) && best.get().lift() >= lift + BETTER;
        return new Factor(d, key, round(lift), weak ? best.get().key() : null, weak ? round(best.get().lift()) : null, weak);
    }

    /** exp(Σ weight · ln lift), with each weak factor at its best option when {@code fixed}. */
    private static double combine(List<Factor> factors, boolean fixed) {
        double log = 0;
        for (Factor f : factors) {
            double lift = fixed && f.weak() ? f.bestLift() : f.lift();
            log += WEIGHT.get(f.dimension()) * Math.log(Math.max(LOWEST, Math.min(HIGHEST, lift)));
        }
        return Math.max(0.3, Math.min(3, Math.exp(log)));
    }

    private static double round(double x) { return Math.round(x * 100) / 100.0; }
}
