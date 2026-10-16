package com.halty.gramalyzerbot.analytics;

import com.halty.gramalyzerbot.analytics.Report.Group;
import com.halty.gramalyzerbot.analytics.Report.Slot;
import com.halty.gramalyzerbot.post.DemoGenerator;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisEngineTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private static List<Sample> demo(int days) {
        List<Sample> out = new ArrayList<>();
        long id = 1;
        for (DemoGenerator.DemoPost p : DemoGenerator.generate(NOW, BERLIN, 42, days)) {
            out.add(new Sample(id, (int) id, p.postedAt(), p.kind(), p.text(), p.hasLink(),
                    TextFeatures.hashtags(p.text()), p.reactions(), p.comments(), true, false, null));
            id++;
        }
        return out;
    }

    private static Options opts(Metric m, int window) {
        return new Options(BERLIN, m, window, NOW, Duration.ofHours(24));
    }

    @Test
    void recoversPlantedBestTimes() {
        Report r = new AnalysisEngine().analyze(demo(300), opts(Metric.ENGAGEMENT, 0));
        assertTrue(r.eligible() > 250, "eligible=" + r.eligible());
        assertEquals(Report.Confidence.HIGH, r.confidence());
        Slot best = r.topSlots().get(0);
        assertTrue(best.day() == DayOfWeek.TUESDAY || best.day() == DayOfWeek.THURSDAY, "best day " + best);
        assertTrue(best.hour() >= 17 && best.hour() <= 20, "best hour " + best);
        assertTrue(best.lift() > 1.3, "lift " + best.lift());
        assertTrue(r.topSlots().size() >= 3);
    }

    @Test
    void recoversPlantedContentSignals() {
        Report r = new AnalysisEngine().analyze(demo(300), opts(Metric.ENGAGEMENT, 0));
        assertEquals("VIDEO", r.kinds().get(0).key());
        assertEquals("ai", r.topics().get(0).key());
        assertEquals("memes", r.topics().get(r.topics().size() - 1).key());
        assertTrue(r.topics().get(0).lift() > 1.2);
        assertTrue(r.topics().get(r.topics().size() - 1).lift() < 0.85);
    }

    @Test
    void keywordsSeparateTopicsWithoutHashtags() {
        Report r = new AnalysisEngine().analyze(demo(300), opts(Metric.ENGAGEMENT, 0));
        List<String> winners = r.keywords().stream().filter(g -> g.lift() > 1).map(Group::key).toList();
        List<String> losers = r.keywords().stream().filter(g -> g.lift() < 1).map(Group::key).toList();
        assertTrue(winners.contains("benchmarks") || winners.contains("transformer") || winners.contains("model"), "winners " + winners);
        // words that always appear together are collapsed into one entry
        assertFalse(winners.contains("actually") && winners.contains("transformer"), "winners " + winners);
        assertFalse(r.keywords().stream().anyMatch(g -> g.key().equals("with") || g.key().equals("when")));
        assertTrue(losers.contains("monday") || losers.contains("meeting") || losers.contains("friday")
                || losers.contains("deploy") || losers.contains("energy"), "losers " + losers);
    }

    @Test
    void windowLimitsPosts() {
        Report all = new AnalysisEngine().analyze(demo(300), opts(Metric.ENGAGEMENT, 0));
        Report w30 = new AnalysisEngine().analyze(demo(300), opts(Metric.ENGAGEMENT, 30));
        assertTrue(w30.eligible() < 50 && w30.eligible() > 10, "w30=" + w30.eligible());
        assertTrue(all.eligible() > w30.eligible());
        assertTrue(w30.keywords().isEmpty());
    }

    @Test
    void youngPostsAndExcludedPostsAreSkipped() {
        List<Sample> list = new ArrayList<>(demo(60));
        list.add(new Sample(9001, 9001, NOW.minusSeconds(3600), PostKind.TEXT, "fresh", false, List.of(), 500, 0, false, false, null));
        list.add(new Sample(9002, 9002, NOW.minus(Duration.ofDays(3)), PostKind.TEXT, "ad", false, List.of(), 5000, 0, false, true, null));
        Report r = new AnalysisEngine().analyze(list, opts(Metric.ENGAGEMENT, 0));
        assertEquals(1, r.immature());
        assertEquals(1, r.excluded());
        assertTrue(r.topPosts().stream().noneMatch(s -> s.sample().id() >= 9001));
    }

    @Test
    void noSignalAndEmptyAreReportedNotCrashed() {
        Report none = new AnalysisEngine().analyze(List.of(), opts(Metric.ENGAGEMENT, 0));
        assertEquals(0, none.eligible());
        List<Sample> zeros = new ArrayList<>();
        for (int i = 0; i < 30; i++)
            zeros.add(new Sample(i, i, NOW.minus(Duration.ofDays(2 + i)), PostKind.TEXT, "x", false, List.of(), 0, 0, true, false, null));
        Report r = new AnalysisEngine().analyze(zeros, opts(Metric.ENGAGEMENT, 0));
        assertTrue(r.notes().contains(Report.Note.NO_SIGNAL));
        assertFalse(r.hasSignal());
    }

    @Test
    void commentsMetricIgnoresUnknownComments() {
        assertEquals(10.0, Metric.REACTIONS.score(10, 5, true), 1e-9);
        assertEquals(25.0, Metric.ENGAGEMENT.score(10, 5, true), 1e-9);
        assertEquals(10.0, Metric.ENGAGEMENT.score(10, 5, false), 1e-9);
        assertEquals(0.0, Metric.COMMENTS.score(10, 5, false), 1e-9);
    }

    @Test
    void flagsLowTimeVariety() {
        List<Sample> list = new ArrayList<>();
        for (int i = 0; i < 40; i++)
            list.add(new Sample(i, i, Instant.parse("2026-09-01T16:00:00Z").plus(Duration.ofDays(i % 30)).plusSeconds(i),
                    PostKind.TEXT, "post " + i, false, List.of(), 5 + i % 7, 0, false, false, null));
        Report r = new AnalysisEngine().analyze(list, opts(Metric.REACTIONS, 0));
        assertTrue(r.notes().contains(Report.Note.LOW_TIME_VARIETY));
    }

    @Test
    void textFeatures() {
        assertEquals(List.of("ai", "ünï"), TextFeatures.hashtags("Hello #AI and #ünï, #ai again; not a#tag"));
        assertTrue(TextFeatures.hasLink("see https://x.io/a"));
        assertFalse(TextFeatures.hasLink("no links here"));
        var kw = TextFeatures.keywords("Check https://x.io @bob #tag Benchmarks of the NEW model 2024 ok");
        assertTrue(kw.contains("benchmarks") && kw.contains("model") && kw.contains("check"));
        assertFalse(kw.contains("2024") || kw.contains("bob") || kw.contains("tag") || kw.contains("new"));
        var stop = TextFeatures.keywords("This works with that, nicht wirklich dieser Plan, только кошки");
        assertTrue(stop.contains("works") && stop.contains("wirklich") && stop.contains("кошки"));
        assertFalse(stop.contains("this") || stop.contains("with") || stop.contains("that") || stop.contains("nicht") || stop.contains("dieser") || stop.contains("только"));
    }
}
