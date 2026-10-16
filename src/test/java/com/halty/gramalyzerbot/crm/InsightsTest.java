package com.halty.gramalyzerbot.crm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.halty.gramalyzerbot.analytics.AnalysisEngine;
import com.halty.gramalyzerbot.analytics.Metric;
import com.halty.gramalyzerbot.analytics.Options;
import com.halty.gramalyzerbot.analytics.Report;
import com.halty.gramalyzerbot.analytics.Sample;
import com.halty.gramalyzerbot.analytics.TextFeatures;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.SubscriberService;
import com.halty.gramalyzerbot.post.DemoGenerator;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InsightsTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z"); // a Thursday, 14:00 in Berlin

    private static Channel channel() {
        Channel ch = new Channel();
        ch.setTelegramChatId(-1001234567890L);
        ch.setTitle("Insights test");
        ch.setUsername("insights_test");
        ch.setTimezone("Europe/Berlin");
        return ch;
    }

    private static Report report(int days) {
        List<Sample> samples = new ArrayList<>();
        long id = 1;
        for (DemoGenerator.DemoPost p : DemoGenerator.generate(NOW, BERLIN, 42, days)) {
            samples.add(new Sample(id, (int) id, p.postedAt(), p.kind(), p.text(), p.hasLink(),
                    TextFeatures.hashtags(p.text()), p.reactions(), p.comments(), true, false, null));
            id++;
        }
        return new AnalysisEngine().analyze(samples, new Options(BERLIN, Metric.ENGAGEMENT, 0, NOW, Duration.ofHours(24)));
    }

    private static final SubscriberService.Growth GROWTH = new SubscriberService.Growth(12_000, 300, 30, List.of(11_700, 12_000));

    // ---------------------------------------------------------------- upcoming slots

    @Test
    void nextOccurrenceIsTheComingWeekdayAtThatLocalHour() {
        Instant at = Insights.nextOccurrence(DayOfWeek.TUESDAY, 18, BERLIN, NOW);
        ZonedDateTime local = at.atZone(BERLIN);
        assertEquals(DayOfWeek.TUESDAY, local.getDayOfWeek());
        assertEquals(18, local.getHour());
        assertEquals(0, local.getMinute());
        assertTrue(at.isAfter(NOW));
        assertTrue(at.isBefore(NOW.plus(Duration.ofDays(7))));
    }

    @Test
    void laterTodayCountsButAnHourThatAlreadyPassedMovesToNextWeek() {
        Instant later = Insights.nextOccurrence(DayOfWeek.THURSDAY, 18, BERLIN, NOW);
        assertEquals(NOW.atZone(BERLIN).toLocalDate(), later.atZone(BERLIN).toLocalDate());

        Instant earlier = Insights.nextOccurrence(DayOfWeek.THURSDAY, 9, BERLIN, NOW);
        assertEquals(NOW.atZone(BERLIN).toLocalDate().plusWeeks(1), earlier.atZone(BERLIN).toLocalDate());
    }

    @Test
    void aSlotLessThanTenMinutesAwayIsTooLateToScheduleSoItMovesToNextWeek() {
        Instant soon = Instant.parse("2026-10-15T15:55:00Z"); // 17:55 in Berlin
        Instant at = Insights.nextOccurrence(DayOfWeek.THURSDAY, 18, BERLIN, soon);
        assertEquals(soon.atZone(BERLIN).toLocalDate().plusWeeks(1), at.atZone(BERLIN).toLocalDate());
    }

    @Test
    void aNonExistentLocalTimeDuringTheSpringClockChangeStillYieldsALaterInstant() {
        Instant beforeChange = Instant.parse("2026-03-28T12:00:00Z"); // Berlin skips 02:00–03:00 on Sunday 29 March
        Instant at = Insights.nextOccurrence(DayOfWeek.SUNDAY, 2, BERLIN, beforeChange);
        assertTrue(at.isAfter(beforeChange));
        assertEquals(DayOfWeek.SUNDAY, at.atZone(BERLIN).getDayOfWeek());
    }

    // ---------------------------------------------------------------- the report as JSON

    @Test
    void aChannelWithPlentyOfPostsGetsTimesSuggestionsAndContentInsights() throws Exception {
        Insights in = Insights.of("tech", channel(), report(300), GROWTH, NOW);

        assertEquals("ready", in.status());
        assertEquals("tech", in.channelId());
        assertEquals("Europe/Berlin", in.timezone());
        assertEquals(0, in.postsNeeded());
        assertFalse(in.bestTimes().isEmpty());
        assertTrue(in.bestTimes().get(0).day().equals("TUESDAY") || in.bestTimes().get(0).day().equals("THURSDAY"),
                "planted best days are Tuesday and Thursday, got " + in.bestTimes().get(0));
        assertTrue(in.bestTimes().get(0).lift() > 1.0);

        // suggestions are upcoming and in chronological order
        assertFalse(in.suggestions().isEmpty());
        Instant previous = NOW;
        for (Insights.Suggestion s : in.suggestions()) {
            assertTrue(s.at().isAfter(previous) || s.at().equals(previous));
            previous = s.at();
        }
        assertTrue(in.suggestions().get(0).at().isAfter(NOW));

        assertEquals(7, in.heatmap().lift().length);
        assertEquals(24, in.heatmap().lift()[0].length);
        assertEquals(7, in.heatmap().posts().length);
        assertFalse(in.content().kinds().isEmpty());
        assertEquals("VIDEO", in.content().kinds().get(0).key());
        assertFalse(in.topPosts().isEmpty());
        assertTrue(in.topPosts().get(0).preview().codePointCount(0, in.topPosts().get(0).preview().length()) <= 140);
        assertEquals(Integer.valueOf(12_000), in.audience().current());

        // it must survive Jackson: no NaN/Infinity, no unsupported types
        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(in);
        assertFalse(json.contains("NaN") || json.contains("Infinity"));
        assertTrue(json.contains("\"status\":\"ready\""));
    }

    @Test
    void aChannelWithTooFewPostsIsStillLearningAndDoesNotGuess() {
        Insights in = Insights.of("tech", channel(), report(5), GROWTH, NOW);

        assertEquals("learning", in.status());
        assertTrue(in.postsNeeded() > 0);
        assertTrue(in.bestTimes().isEmpty());
        assertTrue(in.suggestions().isEmpty());
        assertTrue(in.content().topics().isEmpty());
        assertTrue(in.topPosts().isEmpty());
        assertEquals(7, in.heatmap().lift().length);
    }

    @Test
    void aChannelTheBotDoesNotServeIsReportedAsNotConnected() {
        Insights in = Insights.notConnected("tech", "Europe/Berlin");
        assertEquals("not_connected", in.status());
        assertEquals("Europe/Berlin", in.timezone());
        assertTrue(in.bestTimes().isEmpty());
        assertEquals(AnalysisEngine.MIN_POSTS_FOR_TIMES, in.postsNeeded());
    }
}
