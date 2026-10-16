package com.halty.gramalyzerbot.bot.ui;

import com.halty.gramalyzerbot.analytics.*;
import com.halty.gramalyzerbot.post.DemoGenerator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlaybookTest {

    @Test
    void playbookFromDemoData() {
        Instant now = Instant.parse("2026-10-15T12:00:00Z");
        ZoneId zone = ZoneId.of("Europe/Berlin");
        List<Sample> samples = new ArrayList<>();
        long id = 1;
        for (DemoGenerator.DemoPost p : DemoGenerator.generate(now, zone, 7, 240)) {
            samples.add(new Sample(id, (int) id++, p.postedAt(), p.kind(), p.text(), p.hasLink(),
                    TextFeatures.hashtags(p.text()), p.reactions(), p.comments(), true, false, null));
        }
        Report r = new AnalysisEngine().analyze(samples, new Options(zone, Metric.ENGAGEMENT, 0, now, Duration.ofHours(24)));
        List<String> lines = Playbook.lines(r);
        assertTrue(lines.size() >= 3, lines.toString());
        assertTrue(lines.get(0).startsWith("⏰ <b>Post on</b>"), lines.get(0));
        String all = String.join("\n", lines);
        assertTrue(all.contains("#ai"), all);
        assertTrue(all.contains("🧊") && all.contains("#memes"), all);
        for (String l : lines) assertFalse(l.contains("NaN") || l.contains("null"), l);
    }

    @Test
    void emptyWhenTooLittleData() {
        Report r = new AnalysisEngine().analyze(List.of(), Options.of(ZoneId.of("UTC"), Metric.ENGAGEMENT, 90));
        assertTrue(Playbook.lines(r).isEmpty());
    }

    @Test
    void labels() {
        assertEquals("🎞 Video", Labels.kind("VIDEO"));
        assertEquals("🎞", Labels.kindEmoji("VIDEO"));
        assertEquals("last 90 days", Labels.window(90));
        assertEquals("all time", Labels.window(0));
        assertEquals("All", Labels.windowShort(0));
        assertEquals("#a&lt;b", Labels.topic("a<b"));
    }
}
