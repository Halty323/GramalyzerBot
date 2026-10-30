package com.halty.gramalyzerbot.gaps;

import com.halty.gramalyzerbot.analytics.PostKind;
import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.screen.GapScreens;
import com.halty.gramalyzerbot.bot.screen.SearchCheckScreens;
import com.halty.gramalyzerbot.channel.*;
import com.halty.gramalyzerbot.i18n.Lang;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.post.DemoService;
import com.halty.gramalyzerbot.post.Post;
import com.halty.gramalyzerbot.post.PostRepository;
import com.halty.gramalyzerbot.post.PostSource;
import com.halty.gramalyzerbot.tracking.*;
import com.halty.gramalyzerbot.user.User;
import com.halty.gramalyzerbot.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The channel against a watchlist, against a real (H2) database with Telegram faked. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:gaps;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "gramalyzer.demo=false", "gramalyzer.api-token=test-only-workspace-token", "telegram.bot.enabled=false", "gramalyzer.tracking.refresh-enabled=false"})
class GapIntegrationTests {

    @Autowired GapService gaps;
    @Autowired GapScreens gapScreens;
    @Autowired SearchCheckScreens searchScreens;
    @Autowired ChannelService channels;
    @Autowired MembershipService membership;
    @Autowired DemoService demo;
    @Autowired PostRepository posts;
    @Autowired TrackedChannelRepository trackedChannels;
    @Autowired TrackedPostRepository trackedPosts;
    @Autowired TrackedWatcherRepository watchers;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean BotApi bot;

    private final Instant now = Instant.now();
    private Channel ch;
    private User owner;

    @BeforeEach
    void reset() {
        for (String table : new String[]{"posts", "tracked_watchers", "tracked_posts", "tracked_snapshots", "tracked_channels",
                "subscriber_snapshots", "audit_log", "channel_members", "channels", "users"}) jdbc.update("DELETE FROM " + table);
        ch = channels.register(-1001234567890L, "Pixel Pulse", "pixelpulse", 1000);
        owner = users.save(new User(11L, "alice", "Alice"));
        membership.add(ch, owner, Role.OWNER, null);
    }

    /** Thirty morning text posts with steady reactions. */
    private void ownPosts() {
        for (int i = 0; i < 30; i++) {
            Post p = new Post();
            p.setChannelId(ch.getId());
            p.setMessageId(i + 1);
            p.setPostedAt(day(i + 2, 9));
            p.setKind(PostKind.TEXT);
            p.setBody("Morning note " + i + " about indie games and patches");
            p.setSource(PostSource.LIVE);
            p.setReactions(20 + i % 5);
            posts.save(p);
        }
    }

    private Instant day(int back, int hour) {
        return now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(back).atTime(hour, 0).toInstant(ZoneOffset.UTC);
    }

    /** A competitor whose videos and evening posts get twice its usual views. */
    private void rival(String username, int subscribers) {
        TrackedChannel c = new TrackedChannel(username, username.toUpperCase());
        c.setSubscribers(subscribers);
        c = trackedChannels.save(c);
        for (int i = 0; i < 24; i++) {
            boolean video = i % 3 == 0;
            int hour = i % 2 == 0 ? 18 : 10;
            TrackedPost p = new TrackedPost();
            p.setTrackedChannelId(c.getId());
            p.setMessageId(100 + i);
            p.setPostedAt(day(i + 2, hour));
            p.setKind(video ? "VIDEO" : "TEXT");
            p.setBody((video ? "Speedrun tutorial " : "Daily patch notes ") + i);
            p.setViews((int) (subscribers * 0.2 * (video ? 2 : 1) * (hour == 18 ? 1.6 : 1)));
            p.setUpdatedAt(now);
            trackedPosts.save(p);
        }
        watchers.save(new TrackedWatcher(c.getId(), TrackingService.owner(owner)));
    }

    @Test
    void videosAndEveningsThatWinForCompetitorsShowAsGapsWithExamples() {
        ownPosts();
        rival("gamerdaily", 20_000);
        rival("pressstart", 90_000);

        GapService.Report r = gaps.report(ch, TrackingService.owner(owner));
        assertEquals("ready", r.status());
        assertEquals("ENGAGEMENT", r.measure(), "a channel without views is scored by reactions");
        assertEquals(2, r.rivals().size());
        GapService.Gap video = r.gaps().stream().filter(g -> g.dimension() == GapService.Dimension.FORMAT && g.key().equals("VIDEO")).findFirst().orElseThrow();
        assertTrue(video.missing());
        assertTrue(video.theirLift() > 1.5, "lift " + video.theirLift());
        assertEquals(0, video.yourPosts());
        assertEquals(2, video.channels());
        assertTrue(video.example().preview().startsWith("Speedrun tutorial"));
        assertEquals("https://t.me/" + video.example().channel() + "/" + video.example().url().replaceAll(".*/", ""), video.example().url());
        assertTrue(r.gaps().stream().anyMatch(g -> g.dimension() == GapService.Dimension.HOURS && g.key().equals("16")), r.gaps().toString());
        assertTrue(r.gaps().stream().noneMatch(g -> g.dimension() == GapService.Dimension.TOPIC && g.key().equals("tutorial")),
                "every tutorial is a video, so it's the same finding as videos");
        assertTrue(r.gaps().stream().noneMatch(g -> g.dimension() == GapService.Dimension.TOPIC && g.key().startsWith("patch")),
                "words in most of a channel's posts are boilerplate");
    }

    @Test
    void anEmptyWatchlistAsksForCompetitorsAndASampleChannelGetsSampleOnes() {
        ownPosts();
        assertEquals("no_competitors", gaps.report(ch, TrackingService.owner(owner)).status());
        rival("tinychannel", 5_000);
        jdbc.update("DELETE FROM tracked_posts WHERE message_id > 105");
        assertEquals("learning", gaps.report(ch, TrackingService.owner(owner)).status());

        Channel d = channels.register(-4_000_000_000_000_000_321L, "🌌 Demo channel", "demo_channel", 12_480);
        d.setDemo(true);
        Channel sample = channels.save(d);
        demo.populate(sample, 7, 12_480);
        GapService.Report r = gaps.report(sample, "tg:999");
        assertEquals("ready", r.status());
        assertEquals(3, r.rivals().size());
        assertFalse(r.gaps().isEmpty());
    }

    @Test
    void theScreensReadInEveryLanguage() {
        ownPosts();
        rival("gamerdaily", 20_000);
        rival("pressstart", 90_000);
        Channel empty = channels.register(-1009999999999L, "Secret Club", null, 500);
        for (Lang lang : Lang.values()) {
            List<String> shown = Tr.in(lang, () -> List.of(gapScreens.list(ch, owner).html(), gapScreens.list(empty, owner).html(),
                    searchScreens.show(ch).html(), searchScreens.show(empty).html()));
            for (String html : shown) assertFalse(html.matches("(?s).*\\b(gap|seo|btn|lbl)\\.[a-z_.A-Z0-9]+.*"), lang + ": " + html);
        }
    }
}
