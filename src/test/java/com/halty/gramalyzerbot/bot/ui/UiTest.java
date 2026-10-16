package com.halty.gramalyzerbot.bot.ui;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UiTest {

    @Test
    void escapesHtml() { assertEquals("a &lt;b&gt; &amp; c", Fmt.esc("a <b> & c")); }

    @Test
    void numbersAndLifts() {
        assertEquals("12,480", Fmt.num(12480));
        assertEquals("+132", Fmt.signed(132));
        assertEquals("−5", Fmt.signed(-5));
        assertEquals("1.8×", Fmt.lift(1.8));
        assertEquals("18:00–19:00", Fmt.hourRange(18));
        assertEquals("23:00–00:00", Fmt.hourRange(23));
        assertEquals("Tue", Fmt.dayShort(java.time.DayOfWeek.TUESDAY));
    }

    @Test
    void barsAndProgress() {
        assertEquals("█████", Fmt.bar(10, 10, 5));
        assertEquals("██▌", Fmt.bar(5, 10, 5));
        assertEquals("", Fmt.bar(0, 10, 5));
        assertEquals("▰▰▱▱▱", Fmt.progress(2, 5, 5));
        assertEquals("▰▰▰", Fmt.progress(9, 3, 3));
        assertEquals("▁▅█", Fmt.sparkline(List.of(1, 5, 9)));
    }

    @Test
    void agoAndTrunc() {
        Instant now = Instant.parse("2026-10-15T12:00:00Z");
        assertEquals("just now", Fmt.ago(now.minusSeconds(20), now));
        assertEquals("5m ago", Fmt.ago(now.minusSeconds(300), now));
        assertEquals("3h ago", Fmt.ago(now.minusSeconds(3 * 3600), now));
        assertEquals("5d ago", Fmt.ago(now.minusSeconds(5 * 86400), now));
        assertEquals("never", Fmt.ago(null, now));
        assertEquals("hello wor…", Fmt.trunc("hello   world and more", 10));
        assertEquals("short", Fmt.trunc("short", 10));
    }

    @Test
    void heatmapShapeAndLevels() {
        double[][] lift = new double[7][24];
        int[][] posts = new int[7][24];
        for (int d = 0; d < 7; d++) for (int h = 0; h < 24; h++) { lift[d][h] = 1.0; }
        for (int h = 0; h < 24; h++) { posts[1][h] = 1; lift[1][h] = h == 18 || h == 19 ? 2.0 : 0.6; }
        posts[2][10] = 1; lift[2][10] = 1.0;
        String s = Heatmap.render(lift, posts);
        String[] lines = s.split("\n");
        assertEquals(8, lines.length);
        assertTrue(lines[0].startsWith("    00    06    12    18"));
        assertTrue(lines[1].startsWith("Mon ⬜⬜⬜⬜⬜⬜⬜⬜⬜⬜⬜⬜"));
        assertTrue(lines[2].contains("🟩"), lines[2]);
        assertTrue(lines[2].contains("🟥"), lines[2]);
        assertEquals(12, lines[2].substring(4).codePointCount(0, lines[2].substring(4).length()));
    }

    @Test
    void viewAndCallbacks() {
        View v = View.of("Hello").row(Btn.cb("A", Cb.of("nav", "home", 12)), Btn.link("B", "https://t.me/x")).row();
        assertEquals("Hello", v.html());
        assertEquals(1, v.rows().size());
        assertTrue(v.rows().get(0).get(1).isLink());
        assertThrows(IllegalArgumentException.class, () -> Btn.cb("x", "y".repeat(65)));
        Cb.Args a = Cb.parse("set:tz:12:America/Argentina/Buenos_Aires");
        assertEquals("set", a.get(0));
        assertEquals(12L, a.getLong(2));
        assertEquals("America/Argentina/Buenos_Aires", a.rest(3));
        assertEquals("", a.get(9));
    }
}
