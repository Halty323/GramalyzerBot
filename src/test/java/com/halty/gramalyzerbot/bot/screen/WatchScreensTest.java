package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.bot.ui.Btn;
import com.halty.gramalyzerbot.bot.ui.View;
import com.halty.gramalyzerbot.i18n.Lang;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.tracking.TrackedChannel;
import com.halty.gramalyzerbot.tracking.TrackedPost;
import com.halty.gramalyzerbot.tracking.TrackedStats;
import com.halty.gramalyzerbot.tracking.TrackingException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WatchScreensTest {

    private final WatchScreens screens = new WatchScreens();

    private static TrackedChannel channel() {
        TrackedChannel c = new TrackedChannel("durov", "Durov's <Channel>");
        c.setId(7L);
        c.setSubscribers(1_250_000);
        c.setDescription("Thoughts & notes");
        c.setFetchedAt(Instant.now().minus(Duration.ofMinutes(3)));
        return c;
    }

    private static List<TrackedPost> posts(Instant now) {
        List<TrackedPost> out = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            TrackedPost p = new TrackedPost();
            p.setMessageId(100 + i);
            p.setPostedAt(now.minus(Duration.ofHours(20 + i * 24L)));
            p.setKind(i % 3 == 0 ? "PHOTO" : "TEXT");
            p.setBody("Post number " + i + " #news");
            p.setViews(300_000 + i * 10_000);
            p.setReactions(2_000 + i * 50);
            p.setUpdatedAt(now);
            out.add(p);
        }
        return out;
    }

    private static String buttons(View v) {
        return String.join(" ", v.rows().stream().flatMap(List::stream).map(b -> b.data() != null ? b.data() : b.url()).toList());
    }

    @Test
    void cardShowsTheBasicsAndEscapes() {
        Instant now = Instant.now();
        TrackedChannel c = channel();
        TrackedStats s = TrackedStats.of(c.getSubscribers(), posts(now), List.of(), now, ZoneOffset.UTC);
        View v = Tr.in(Lang.EN, () -> screens.card(c, s, false, "✅ Added"));
        String html = v.html();
        assertTrue(html.startsWith("✅ Added"), html);
        assertTrue(html.contains("Durov's &lt;Channel&gt;"), html);
        assertTrue(html.contains("@durov"), html);
        assertTrue(html.contains("1,250,000"), html);
        assertTrue(html.contains("Average views"), html);
        assertTrue(html.contains("#news"), html);
        assertFalse(html.contains("null") || html.contains("NaN"), html);
        String data = buttons(v);
        assertTrue(data.contains("wl:addid:7"), data);
        assertTrue(data.contains("https://t.me/durov"), data);

        String watching = buttons(screens.card(c, s, true));
        assertTrue(watching.contains("wl:rm:7"), watching);
    }

    @Test
    void cardWithoutPostsSaysWhy() {
        TrackedChannel c = channel();
        TrackedStats s = TrackedStats.of(c.getSubscribers(), List.of(), List.of(), Instant.now(), ZoneOffset.UTC);
        String html = Tr.in(Lang.EN, () -> screens.card(c, s, true).html());
        assertTrue(html.contains("doesn't show this channel's posts"), html);
    }

    @Test
    void listAndTop() {
        Instant now = Instant.now();
        TrackedChannel c = channel();
        TrackedStats s = TrackedStats.of(c.getSubscribers(), posts(now), List.of(), now, ZoneOffset.UTC);
        View list = Tr.in(Lang.RU, () -> screens.list(List.of(new WatchScreens.Row(c, s))));
        assertTrue(buttons(list).contains("wl:show:7"));
        assertTrue(list.rows().get(0).get(0).text().contains("1.3M"), list.rows().get(0).get(0).text());

        View empty = Tr.in(Lang.EN, () -> screens.list(List.of()));
        assertTrue(empty.html().contains("Nothing here yet"), empty.html());

        View top = Tr.in(Lang.EN, () -> screens.top(c, s));
        assertTrue(top.html().contains("🥇"), top.html());
        assertTrue(buttons(top).contains("https://t.me/durov/"), buttons(top));
    }

    @Test
    void errorsAreTranslated() {
        String en = Tr.in(Lang.EN, () -> WatchScreens.error(new TrackingException(TrackingException.Code.PRIVATE_LINK)));
        String uk = Tr.in(Lang.UK, () -> WatchScreens.error(new TrackingException(TrackingException.Code.PRIVATE_LINK)));
        assertTrue(en.contains("private"), en);
        assertNotEquals(en, uk);
        assertEquals(Tr.in(Lang.EN, () -> Tr.t("watch.err.unreachable")), Tr.in(Lang.EN, () -> WatchScreens.error("something_new")));
    }
}
