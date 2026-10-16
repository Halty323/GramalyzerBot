package com.halty.gramalyzerbot.post;

import com.halty.gramalyzerbot.analytics.PostKind;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Builds a believable fake channel so people can explore the bot before connecting a real one.
 * The data has planted patterns (Tue/Thu evenings, video, #ai do best) – the engine's tests rely on that.
 */
public final class DemoGenerator {

    public record DemoPost(Instant postedAt, PostKind kind, String text, boolean hasLink, int reactions,
                           int comments, String breakdown) {}

    private static final double[] HOUR_WEIGHT = {1, .5, .3, .2, .2, .5, 1, 3, 4, 6, 4, 4, 5, 5, 4, 4, 4, 5, 7, 7, 6, 5, 3, 2};
    private static final double[] DAY_MULT = {1.0, 1.4, 1.0, 1.3, 1.0, 0.8, 0.7};
    private static final String[] TOPICS = {"ai", "tutorials", "news", "memes", "reviews"};
    private static final double[] TOPIC_WEIGHT = {.25, .2, .25, .15, .15};
    private static final double[] TOPIC_MULT = {1.7, 1.3, 1.0, 0.6, 1.1};
    private static final PostKind[] KINDS = {PostKind.TEXT, PostKind.PHOTO, PostKind.VIDEO, PostKind.POLL, PostKind.ALBUM, PostKind.ANIMATION};
    private static final double[] KIND_WEIGHT = {.35, .25, .15, .05, .1, .1};
    private static final double[] KIND_MULT = {1.0, 1.2, 1.6, 1.3, 1.1, 0.9};
    private static final Map<String, String[]> HEADLINES = Map.of(
            "ai", new String[]{"New model released and the benchmarks look wild", "How a transformer actually predicts the next token",
                    "Open weights are catching up with the big labs", "We tested the latest model on real tasks"},
            "tutorials", new String[]{"Step by step guide to building your first bot", "A practical guide to caching without pain",
                    "Build a small project in one evening, step by step", "Beginner guide: set up your toolchain properly"},
            "news", new String[]{"Breaking: company announces quarterly results", "Industry weekly roundup is out",
                    "Regulators publish the long awaited framework", "Funding round closes for a local startup"},
            "memes", new String[]{"Monday mood again lol", "When the deploy works on the first try",
                    "Me pretending to understand the meeting", "Friday afternoon energy"},
            "reviews", new String[]{"Honest review after two weeks of daily use", "Is the premium plan really worth the price",
                    "We compared three popular tools side by side", "Unboxing and first impressions"});
    private static final String FILLER = " Here is some extra context so the post has a realistic length and a few more details to read through before you react.";

    private DemoGenerator() {}

    public static List<DemoPost> generate(Instant now, ZoneId zone, long seed, int days) {
        Random rng = new Random(seed);
        List<DemoPost> out = new ArrayList<>();
        LocalDate today = now.atZone(zone).toLocalDate();
        for (int back = days - 1; back >= 0; back--) {
            LocalDate date = today.minusDays(back);
            int count = rng.nextDouble() < 0.12 ? 0 : 1 + (rng.nextDouble() < 0.3 ? 1 : 0) + (rng.nextDouble() < 0.08 ? 1 : 0);
            for (int c = 0; c < count; c++) {
                int hour = weighted(rng, HOUR_WEIGHT);
                Instant at = date.atTime(hour, rng.nextInt(60), rng.nextInt(60)).atZone(zone).toInstant();
                if (at.isAfter(now)) continue;
                int ti = weighted(rng, TOPIC_WEIGHT), ki = weighted(rng, KIND_WEIGHT);
                String[] heads = HEADLINES.get(TOPICS[ti]);
                StringBuilder text = new StringBuilder(heads[rng.nextInt(heads.length)]);
                int extra = rng.nextInt(4);
                for (int e = 0; e < extra; e++) text.append(FILLER);
                boolean link = rng.nextDouble() < 0.25;
                if (link) text.append(" https://example.com/post").append(rng.nextInt(1000));
                text.append(" #").append(TOPICS[ti]);

                DayOfWeek dow = date.getDayOfWeek();
                double mult = DAY_MULT[dow.getValue() - 1] * hourMult(hour) * TOPIC_MULT[ti] * KIND_MULT[ki];
                int reactions = (int) Math.round(20 * mult * Math.exp(rng.nextGaussian() * 0.5));
                int comments = (int) Math.round(reactions * 0.12 * Math.exp(rng.nextGaussian() * 0.4));
                int fire = (int) Math.round(reactions * 0.4), heart = (int) Math.round(reactions * 0.2);
                String breakdown = "🔥:" + fire + ",👍:" + (reactions - fire - heart) + ",❤:" + heart;
                out.add(new DemoPost(at, KINDS[ki], text.toString(), link, reactions, comments, breakdown));
            }
        }
        return out;
    }

    private static double hourMult(int h) {
        if (h == 18 || h == 19) return 1.8;
        if (h == 17 || h == 20) return 1.3;
        if (h == 9) return 1.4;
        if (h == 8 || h == 10) return 1.15;
        if (h <= 5) return 0.6;
        return 1.0;
    }

    private static int weighted(Random rng, double[] w) {
        double total = 0;
        for (double x : w) total += x;
        double r = rng.nextDouble() * total;
        for (int i = 0; i < w.length; i++) {
            r -= w[i];
            if (r <= 0) return i;
        }
        return w.length - 1;
    }
}
