package com.halty.gramalyzerbot.views;

import com.halty.gramalyzerbot.analytics.Metric;
import com.halty.gramalyzerbot.analytics.PostKind;
import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.screen.ViewSpeedScreens;
import com.halty.gramalyzerbot.channel.*;
import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.i18n.Lang;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.post.AnalysisService;
import com.halty.gramalyzerbot.post.DemoService;
import com.halty.gramalyzerbot.post.Post;
import com.halty.gramalyzerbot.post.PostRepository;
import com.halty.gramalyzerbot.post.PostSource;
import com.halty.gramalyzerbot.tracking.PreviewParser.PreviewPost;
import com.halty.gramalyzerbot.user.User;
import com.halty.gramalyzerbot.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Views at 1, 6 and 24 hours, the usual pace and pace alerts, against a real (H2) database with Telegram faked. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:views;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "gramalyzer.demo=false", "gramalyzer.api-token=test-only-workspace-token", "telegram.bot.enabled=false", "gramalyzer.tracking.refresh-enabled=false"})
class ViewSpeedIntegrationTests {

    @Autowired ViewSpeedService speed;
    @Autowired ViewSpeedScreens screens;
    @Autowired ChannelService channels;
    @Autowired MembershipService membership;
    @Autowired SettingsService settings;
    @Autowired AnalysisService analysis;
    @Autowired DemoService demo;
    @Autowired PostRepository posts;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean BotApi bot;

    private final Instant now = Instant.now();
    private Channel ch;
    private User owner;
    private int nextMessage = 1;

    @BeforeEach
    void reset() {
        for (String table : new String[]{"posts", "subscriber_snapshots", "audit_log", "channel_members", "channels", "users"}) jdbc.update("DELETE FROM " + table);
        ch = channels.register(-1001234567890L, "Pixel Pulse", "pixelpulse", 1000);
        owner = users.save(new User(11L, "alice", "Alice"));
        membership.add(ch, owner, Role.OWNER, null);
    }

    private Post post(Instant at, Integer h1, Integer h6, Integer h24) {
        Post p = new Post();
        p.setChannelId(ch.getId());
        p.setMessageId(nextMessage++);
        p.setPostedAt(at);
        p.setKind(PostKind.TEXT);
        p.setBody("Post " + p.getMessageId());
        p.setSource(PostSource.LIVE);
        p.setViews1h(h1);
        p.setViews6h(h6);
        p.setViews24h(h24);
        if (h24 != null) {
            p.setViews(h24 + 20);
            p.setViewsCheckedAt(at.plus(Duration.ofDays(2)));
        }
        return posts.save(p);
    }

    /** Six older posts that usually get 100 views in an hour, 200 in six and 300 in a day. */
    private void history() {
        for (int i = 0; i < 6; i++) post(now.minus(Duration.ofDays(10 - i)), 95 + i * 2, 195 + i * 2, 295 + i * 2);
    }

    private static PreviewPost seen(Post p, int views) {
        return new PreviewPost(p.getMessageId(), p.getPostedAt(), "text", p.getBody(), views, 0, null, null, false, null, false, List.of());
    }

    private Post reload(Post p) { return posts.findById(p.getId()).orElseThrow(); }

    @Test
    void aPostBehindTheUsualPaceAtOneHourSetsOffOneAlert() {
        history();
        Post fresh = post(now.minus(Duration.ofMinutes(65)), null, null, null);
        assertTrue(speed.due(ch, now));

        List<ViewSpeedService.Alert> alerts = speed.record(ch, List.of(seen(fresh, 39)), now);
        assertEquals(1, alerts.size());
        ViewSpeedService.Alert a = alerts.getFirst();
        assertTrue(a.lagging());
        assertEquals(ViewSpeedService.Mark.H1, a.mark());
        assertEquals(36, a.views(), "39 views at 65 minutes is about 36 at the hour mark");
        assertEquals(100, a.usual());
        verify(bot, timeout(2000)).send(eq(11L), any());
        assertEquals("LAG", reload(fresh).getPaceAlert());
        assertFalse(speed.due(ch, now));

        // later readings fill the other marks but never alert twice
        Post later = reload(fresh);
        later.setPostedAt(now.minus(Duration.ofMinutes(6 * 60 + 5)));
        later.setViewsCheckedAt(now.minus(Duration.ofMinutes(10)));
        posts.save(later);
        assertTrue(speed.record(ch, List.of(seen(later, 60)), now).isEmpty());
        assertNotNull(reload(fresh).getViews6h());
    }

    @Test
    void aPostWellAheadIsFlyingAndOneReadFarFromAnyMarkFillsNothing() {
        history();
        Post hot = post(now.minus(Duration.ofMinutes(62)), null, null, null);
        Post old = post(now.minus(Duration.ofHours(3)), null, null, null);

        List<ViewSpeedService.Alert> alerts = speed.record(ch, List.of(seen(hot, 260), seen(old, 150)), now);
        assertEquals(1, alerts.size());
        assertFalse(alerts.getFirst().lagging());
        assertEquals("FAST", reload(hot).getPaceAlert());
        assertNull(reload(old).getViews1h(), "the only reading is two hours past the mark, too far to guess from");
        assertEquals(150, reload(old).getViews());

        ViewSpeedService.Report r = speed.report(ch);
        assertTrue(r.available());
        assertEquals(101, r.usual().h1(), "the hot post now counts towards the usual pace too");
        assertEquals(200, r.usual().h6());
        assertEquals(300, r.usual().h24());
        assertEquals("fast", r.posts().stream().filter(p -> p.messageId() == hot.getMessageId()).findFirst().orElseThrow().status());
    }

    @Test
    void theViewsMetricRanksByDayOneViewsAndNeedsAPublicChannel() {
        history();
        Channel saved = settings.setMetric(ch, owner, Metric.VIEWS);
        assertEquals(Metric.VIEWS, saved.getMetric());
        assertTrue(analysis.samples(saved).stream().allMatch(s -> s.views() != null));

        Channel hidden = channels.register(-1009999999999L, "Secret Club", null, 500);
        membership.add(hidden, owner, Role.OWNER, null);
        assertEquals("met.err.views", assertThrows(UserFacingException.class, () -> settings.setMetric(hidden, owner, Metric.VIEWS)).key());
        assertFalse(speed.report(hidden).available());
    }

    @Test
    void aSampleChannelGetsViewsAndEveryScreenReadsInEveryLanguage() {
        Channel d = channels.register(-4_000_000_000_000_000_321L, "🌌 Demo channel", "demo_channel", 12_480);
        d.setDemo(true);
        Channel sample = channels.save(d);
        demo.populate(sample, 7, 12_480);

        ViewSpeedService.Report r = speed.report(sample);
        assertTrue(r.available());
        assertNotNull(r.usual().h24());
        assertFalse(r.posts().isEmpty());

        history();
        Post fresh = post(now.minus(Duration.ofMinutes(61)), null, null, null);
        ViewSpeedService.Alert lag = speed.record(ch, List.of(seen(fresh, 20)), now).getFirst();
        for (Lang lang : Lang.values()) {
            List<String> shown = Tr.in(lang, () -> List.of(screens.list(sample, Role.OWNER).html(), screens.list(ch, Role.OWNER).html(),
                    screens.alert(lag, Role.OWNER).html()));
            for (String html : shown) assertFalse(html.matches("(?s).*\\b(speed|btn|toast|lbl)\\.[a-z_.A-Z0-9]+.*"), lang + ": " + html);
        }
    }

    @Test
    void marksAreFilledOnlyFromReadingsCloseToThem() {
        Post p = new Post();
        p.setPostedAt(now.minus(Duration.ofMinutes(70)));
        assertEquals(List.of(ViewSpeedService.Mark.H1), ViewSpeedService.fill(p, 70, now));
        assertEquals(60, p.getViews1h());

        Post q = new Post();
        q.setPostedAt(now.minus(Duration.ofHours(2)));
        assertTrue(ViewSpeedService.fill(q, 70, now).isEmpty());
    }
}
