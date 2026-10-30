package com.halty.gramalyzerbot.analytics;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The fixes from the growth research: compare posts with their neighbours, skip ads, fit traits together, prove before scoring. */
class HonestNumbersTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    private static Sample post(long id, Instant at, PostKind kind, String text, int reactions) {
        return new Sample(id, (int) id, at, kind, text, false, TextFeatures.hashtags(text), reactions, 0, false, false, null);
    }

    private static Options opts() {
        return new Options(UTC, Metric.REACTIONS, 0, NOW, Duration.ofHours(24));
    }

    @Test
    void neighbourMediansLeaveThePostOut() {
        double[] m = Baseline.medians(new double[]{1, 2, 3, 100, 5, 6, 7}, 2);
        assertEquals(4, m[3], "a viral post doesn't raise its own baseline: 2, 3, 5, 6");
        assertEquals(4, m[0], "at the start the window shifts inwards: 2, 3, 100, 5");
        assertArrayEquals(new double[]{5}, Baseline.medians(new double[]{5}, 2));
    }

    @Test
    void aFormatThatArrivedWhileTheChannelGrewIsNotCreditedWithTheGrowth() {
        // reactions grow 4× over 120 days; the channel only starts posting videos in the second half,
        // and they do exactly as well as the text posts around them
        Random rnd = new Random(7);
        List<Sample> posts = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            Instant at = NOW.minus(Duration.ofDays(120 - i)).plus(Duration.ofHours(rnd.nextInt(12) + 8));
            double usual = 20 * Math.pow(4, i / 119.0);
            PostKind kind = i >= 60 && i % 2 == 0 ? PostKind.VIDEO : PostKind.TEXT;
            posts.add(post(i + 1, at, kind, "post " + i, (int) Math.round(usual * (0.8 + 0.4 * rnd.nextDouble()))));
        }
        Report r = new AnalysisEngine().analyze(posts, opts());
        Report.Group video = r.kinds().stream().filter(g -> g.key().equals("VIDEO")).findFirst().orElseThrow();
        double videos = posts.stream().filter(p -> p.kind() == PostKind.VIDEO).mapToInt(Sample::reactions).average().orElse(0);
        double all = posts.stream().mapToInt(Sample::reactions).average().orElse(0);
        assertTrue(videos / all > 1.3, "against the all-time average videos would look like a win: " + videos / all);
        assertTrue(video.lift() > 0.9 && video.lift() < 1.1, "video lift " + video.lift());
    }

    @Test
    void sponsoredPostsAreRecognisedAndLeftOut() {
        assertTrue(TextFeatures.isAd("Great VPN deal! #реклама"));
        assertTrue(TextFeatures.isAd("Реклама. ООО «Ромашка», ИНН 7700000000"));
        assertTrue(TextFeatures.isAd("Try it today erid: 2VtzqwXYZab"));
        assertTrue(TextFeatures.isAd("New phones #ad"));
        assertFalse(TextFeatures.isAd("We discussed advertising budgets #adventures"));
        assertFalse(TextFeatures.isAd("The Reklamation process explained"));
        assertFalse(TextFeatures.isAd(null));

        List<Sample> posts = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String text = i % 5 == 0 ? "Buy this #ad" : "regular post " + i;
            posts.add(post(i + 1, NOW.minus(Duration.ofDays(40 - i)), PostKind.TEXT, text, 10 + i % 3));
        }
        Report r = new AnalysisEngine().analyze(posts, opts());
        assertEquals(8, r.ads());
        assertEquals(32, r.eligible());
    }

    @Test
    void traitsThatTravelTogetherAreNotCountedTwice() {
        // every video is short, every text is long; videos get twice the reactions
        Random rnd = new Random(3);
        List<Sample> posts = new ArrayList<>();
        double[] v = new double[200];
        for (int i = 0; i < 200; i++) {
            boolean video = i % 2 == 0;
            String text = video ? "short" : "x".repeat(900);
            posts.add(post(i + 1, NOW.minus(Duration.ofHours(200 - i)), video ? PostKind.VIDEO : PostKind.TEXT, text, 0));
            v[i] = (video ? Math.log(2) / 2 : -Math.log(2) / 2) + rnd.nextGaussian() * 0.1;
        }
        PostModel m = PostModel.fit(posts, v, UTC);
        double video = m.predict(PostModel.features("VIDEO", 5, false, null));
        double text = m.predict(PostModel.features("TEXT", 900, false, null));
        assertEquals(2, Math.exp(video - text), 0.3, "one doubling, not two multiplied together");
    }

    @Test
    void theScoreHasToPredictNewerPostsBeforeItIsShown() {
        int realProven = 0, noiseProven = 0, runs = 40;
        for (int seed = 0; seed < runs; seed++) {
            Random rnd = new Random(seed);
            List<Sample> posts = new ArrayList<>();
            double[] real = new double[120], noise = new double[120];
            for (int i = 0; i < 120; i++) {
                boolean video = rnd.nextBoolean();
                posts.add(post(i + 1, NOW.minus(Duration.ofHours(120 - i)), video ? PostKind.VIDEO : PostKind.TEXT, "p", 0));
                real[i] = (video ? 0.7 : 0) + rnd.nextGaussian() * 0.3;
                noise[i] = rnd.nextGaussian() * 0.3;
            }
            Backtest.Check c = Backtest.run(posts, real, UTC);
            assertEquals(30, c.tested());
            if (c.proven()) realProven++;
            if (Backtest.run(posts, noise, UTC).proven()) noiseProven++;
        }
        assertTrue(realProven >= runs * 0.9, "a real effect is found: " + realProven + "/" + runs);
        assertTrue(noiseProven <= runs * 0.2, "noise rarely passes (about 1 in 10 by design): " + noiseProven + "/" + runs);
        assertEquals("untested", Backtest.run(List.of(), new double[0], UTC).status());
    }

    @Test
    void fewPostsGiveFourHourBlocksNotExactHours() {
        Random rnd = new Random(5);
        List<Sample> posts = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            Instant at = NOW.minus(Duration.ofDays(60 - i)).truncatedTo(java.time.temporal.ChronoUnit.DAYS).plus(Duration.ofHours(8 + rnd.nextInt(12)));
            posts.add(post(i + 1, at, PostKind.TEXT, "p" + i, 10 + rnd.nextInt(5)));
        }
        Report r = new AnalysisEngine().analyze(posts, opts());
        assertFalse(r.topSlots().isEmpty());
        assertTrue(r.topSlots().stream().allMatch(s -> s.span() == PostModel.BLOCK_HOURS && s.from() % PostModel.BLOCK_HOURS == 0), r.topSlots().toString());
    }
}
