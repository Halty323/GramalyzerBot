package com.halty.gramalyzerbot.planner;

import com.halty.gramalyzerbot.analytics.Backtest;
import com.halty.gramalyzerbot.analytics.PostModel;
import com.halty.gramalyzerbot.analytics.Report;
import com.halty.gramalyzerbot.analytics.TextFeatures;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/**
 * How a draft is likely to do on its channel, from what worked there before.
 * <p>
 * Format, length, link and time of day are read off the channel's {@link PostModel}, which fits them together, so a
 * trait that merely travels with another (short posts that happen to be videos) isn't counted twice. Each factor
 * carries a 90% range, and a better option is only suggested when the data clearly prefers it. The expected result
 * goes on a 1–99 scale (50 = typical, 100 = twice that), but only when the model has proven itself on the channel's
 * newest posts ({@link Backtest}); otherwise the factors are shown without a score.
 * <p>
 * Topics (hashtags) and words are shown for context but never counted or suggested: a hashtag labels a subject, and
 * adding one to a post doesn't bring the readers that subject does. Telegram has no feed for it to boost.
 */
public final class DraftScore {

    public enum Dimension { FORMAT, LENGTH, LINK, TIME, TOPIC, KEYWORDS }

    /** @param kind a {@link com.halty.gramalyzerbot.analytics.PostKind} name; @param at null when not chosen yet */
    public record Draft(String text, String kind, boolean link, Instant at) {}

    /**
     * @param key     what the draft has (a kind, a length bucket, LINK/NOLINK, the first hour of a 4-hour block, topics, words)
     * @param low     the 90% range of {@code lift}
     * @param bestKey the channel's best choice on this dimension, when the data clearly prefers it to the draft's
     * @param counted whether the factor is part of the expected result (topics and words are context only)
     */
    public record Factor(Dimension dimension, String key, double lift, double low, double high, String bestKey, Double bestLift,
                         boolean weak, boolean counted) {}

    /**
     * @param status    learning (too few posts), ready (the score has predicted this channel's newest posts) or
     *                  unproven (factors only: the score didn't beat chance on the newest posts, or there were too few to check)
     * @param potential the expected lift with every weak factor fixed
     * @param low       the 90% range of {@code expected}
     */
    public record Result(String status, int score, double expected, double potential, double low, double high,
                         List<Factor> factors, int postsRead, Backtest.Check check) {}

    static final int MIN_POSTS = 10;
    /** A better option must beat the draft's by at least this much… */
    static final double BETTER = 0.15;
    /** …and by this many standard errors of the difference. */
    static final double SURE = 1.28;
    /** One block of the week rests on few posts, so its effect is never taken past these. */
    private static final double LOWEST = 0.5, HIGHEST = 2;

    private DraftScore() {}

    public static Result score(Report r, Draft d, ZoneId zone) {
        PostModel m = r.model();
        if (!r.hasSignal() || r.eligible() < MIN_POSTS || m == null)
            return new Result("learning", 50, 1, 1, 1, 1, List.of(), r.eligible(), r.check());
        String text = d.text() == null ? "" : d.text();
        Integer hour = d.at() == null ? null : d.at().atZone(zone).getHour();
        Map<PostModel.Dim, String> f = PostModel.features(d.kind(), text.codePointCount(0, text.length()), d.link(), hour);

        List<Factor> factors = new ArrayList<>();
        double log = 0, fixedLog = 0, var = 0;
        for (var e : f.entrySet()) {
            Optional<PostModel.Effect> own = m.effect(e.getKey(), e.getValue());
            if (own.isEmpty()) continue; // never tried on this channel: nothing to say
            PostModel.Effect ef = own.get();
            double b = clamp(m.beta(e.getKey(), e.getValue()));
            Optional<PostModel.Effect> best = m.effects(e.getKey()).stream().filter(x -> x.posts() >= 3).findFirst();
            boolean weak = best.isPresent() && !best.get().key().equals(ef.key())
                    && best.get().lift() >= ef.lift() + BETTER
                    && (Math.log(best.get().lift()) - Math.log(ef.lift())) / m.diffSe(e.getKey(), best.get().key(), ef.key()) >= SURE;
            log += b;
            fixedLog += weak ? clamp(Math.log(best.get().lift())) : b;
            factors.add(new Factor(dimension(e.getKey()), ef.key(), round(ef.lift()), round(ef.low()), round(ef.high()),
                    weak ? best.get().key() : null, weak ? round(best.get().lift()) : null, weak, true));
        }
        double se = m.predictSe(f);

        List<String> tags = TextFeatures.hashtags(text);
        List<Report.Group> used = r.topics().stream().filter(g -> tags.contains(g.key())).toList();
        if (!used.isEmpty()) factors.add(context(Dimension.TOPIC, used.stream().map(g -> "#" + g.key()).toList(), used));

        Set<String> stems = new HashSet<>();
        for (String w : TextFeatures.keywords(text)) stems.add(TextFeatures.stem(w));
        List<Report.Group> words = r.keywords().stream().filter(g -> stems.contains(TextFeatures.stem(g.key()))).toList();
        if (!words.isEmpty()) factors.add(context(Dimension.KEYWORDS, words.stream().map(Report.Group::key).toList(), words));

        double expected = bound(Math.exp(log)), potential = bound(Math.exp(fixedLog));
        int score = (int) Math.round(Math.max(1, Math.min(99, 50 + 50 * Math.log(expected) / Math.log(2))));
        String status = r.check().proven() ? "ready" : "unproven";
        return new Result(status, score, round(expected), round(Math.max(expected, potential)),
                round(bound(Math.exp(log - 1.645 * se))), round(bound(Math.exp(log + 1.645 * se))), factors, r.eligible(), r.check());
    }

    private static Factor context(Dimension d, List<String> keys, List<Report.Group> groups) {
        double lift = groups.stream().mapToDouble(Report.Group::lift).average().orElse(1);
        double low = groups.stream().mapToDouble(Report.Group::low).min().orElse(lift);
        double high = groups.stream().mapToDouble(Report.Group::high).max().orElse(lift);
        return new Factor(d, String.join(d == Dimension.TOPIC ? " " : ", ", keys), round(lift), round(low), round(high), null, null, false, false);
    }

    private static Dimension dimension(PostModel.Dim d) {
        return switch (d) {
            case FORMAT -> Dimension.FORMAT;
            case LENGTH -> Dimension.LENGTH;
            case LINK -> Dimension.LINK;
            case TIME -> Dimension.TIME;
        };
    }

    private static double clamp(double log) { return Math.max(Math.log(LOWEST), Math.min(Math.log(HIGHEST), log)); }

    private static double bound(double x) { return Math.max(0.3, Math.min(3, x)); }

    private static double round(double x) { return Math.round(x * 100) / 100.0; }
}
