package com.halty.gramalyzerbot.growth;

import com.halty.gramalyzerbot.analytics.PostKind;
import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.event.Events;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.ChannelService;
import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.post.Post;
import com.halty.gramalyzerbot.post.PostRepository;
import com.halty.gramalyzerbot.post.PostSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** Joins and leaves per post, promo links and leave waves, against a real (H2) database with Telegram faked. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:growth;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "gramalyzer.demo=false", "gramalyzer.api-token=test-only-workspace-token", "telegram.bot.enabled=false", "gramalyzer.tracking.refresh-enabled=false"})
class GrowthIntegrationTests {

    @Autowired GrowthService growth;
    @Autowired ChannelService channels;
    @Autowired PostRepository posts;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.halty.gramalyzerbot.bot.screen.GrowthScreens screens;
    @Autowired CitationService citations;
    @Autowired com.halty.gramalyzerbot.tracking.TrackedChannelRepository trackedChannels;
    @Autowired com.halty.gramalyzerbot.tracking.TrackedPostRepository trackedPosts;
    @MockitoBean BotApi bot;

    private static final long CHAT = -1001234567890L;
    private final Instant now = Instant.now();
    private Channel ch;
    private int nextMessage = 1;

    @BeforeEach
    void reset() {
        for (String table : new String[]{"member_events", "promo_links", "posts", "tracked_watchers", "tracked_posts", "tracked_snapshots", "tracked_channels", "channels"}) jdbc.update("DELETE FROM " + table);
        ch = channels.register(CHAT, "Pixel Pulse", "pixelpulse", 1000);
    }

    private Post post(Instant at, String text) {
        Post p = new Post();
        p.setChannelId(ch.getId());
        p.setMessageId(nextMessage++);
        p.setPostedAt(at);
        p.setKind(PostKind.TEXT);
        p.setBody(text);
        p.setSource(PostSource.LIVE);
        return posts.save(p);
    }

    private Optional<GrowthService.Wave> join(long user, Instant at, String link) {
        return growth.record(new Events.MemberChange(CHAT, "channel", user, false, false, true, link, at));
    }

    private Optional<GrowthService.Wave> leave(long user, Instant at) {
        return growth.record(new Events.MemberChange(CHAT, "channel", user, false, true, false, null, at));
    }

    private Instant ago(int minutes) { return now.minus(Duration.ofMinutes(minutes)); }

    @Test
    void eachPostGetsTheJoinsAndLeavesUntilTheNextPost() {
        post(ago(600), "A guide people loved");
        post(ago(300), "An ad nobody wanted");
        for (int i = 0; i < 6; i++) join(100 + i, ago(590 - i * 10), null);
        for (int i = 0; i < 4; i++) leave(200 + i, ago(290 - i * 5));
        join(300, ago(280), null);

        GrowthService.Report r = growth.report(ch, 30);
        assertTrue(r.tracking());
        assertEquals(7, r.joined());
        assertEquals(4, r.left());
        assertEquals(3, r.net());
        assertEquals("A guide people loved", r.gainers().getFirst().preview());
        assertEquals(6, r.gainers().getFirst().joined());
        assertEquals(5.0, r.gainers().getFirst().hours());
        assertEquals("An ad nobody wanted", r.losers().getFirst().preview());
        assertEquals(4, r.losers().getFirst().left());
        assertEquals("https://t.me/pixelpulse/1", r.gainers().getFirst().url());
        assertEquals(r.joined(), r.daily().stream().mapToInt(GrowthService.Day::joined).sum());
    }

    @Test
    void nothingIsCountedBeforeTheFirstJoinOrLeave() {
        post(ago(60), "Hello");
        GrowthService.Report r = growth.report(ch, 30);
        assertFalse(r.tracking());
        assertTrue(r.daily().isEmpty());
        assertTrue(r.gainers().isEmpty());
    }

    @Test
    void groupsBotsAndUnknownChannelsAreIgnored() {
        growth.record(new Events.MemberChange(CHAT, "supergroup", 1, false, false, true, null, now));
        growth.record(new Events.MemberChange(CHAT, "channel", 2, true, false, true, null, now));
        growth.record(new Events.MemberChange(-1009L, "channel", 3, false, false, true, null, now));
        growth.record(new Events.MemberChange(CHAT, "channel", 4, false, true, true, null, now)); // promoted, still a member
        assertFalse(growth.report(ch, 30).tracking());
    }

    @Test
    void promoLinksCountWhoJoinedAndWhoStayed() {
        when(bot.createInviteLink(eq(CHAT), anyString())).thenReturn(Optional.of("https://t.me/+shoutout"));
        PromoLink l = growth.create(ch, "Shoutout in @techdaily", 20.0, "$");
        assertEquals("https://t.me/+shoutout", l.getUrl());

        join(1, ago(50), "https://t.me/+shoutout");
        join(2, ago(40), "https://t.me/+shoutout");
        join(3, ago(30), "https://t.me/+shoutout");
        join(4, ago(30), "https://t.me/+someone_elses");
        leave(2, ago(10));

        GrowthService.LinkStat s = growth.linkStats(ch).getFirst();
        assertEquals(3, s.joined());
        assertEquals(1, s.left());
        assertEquals(2, s.stayed());
        assertEquals(10.0, s.costPerSubscriber());
        GrowthService.Report r = growth.report(ch, 30);
        assertEquals(4, r.joined());
        assertEquals(3, r.fromLinks());
    }

    @Test
    void promoLinksShowWhoWasStillThereAWeekAndAMonthLater() {
        when(bot.createInviteLink(eq(CHAT), anyString())).thenReturn(Optional.of("https://t.me/+bought"));
        growth.create(ch, "Paid post in @bigchannel", 40.0, "$");
        Instant longAgo = now.minus(Duration.ofDays(40));
        for (int i = 0; i < 4; i++) join(10 + i, longAgo.plus(Duration.ofMinutes(i)), "https://t.me/+bought");
        leave(10, longAgo.plus(Duration.ofDays(2)));   // gone within the week
        leave(11, longAgo.plus(Duration.ofDays(20)));  // stayed a week, gone within the month
        join(20, now.minus(Duration.ofDays(3)), "https://t.me/+bought"); // too recent to judge

        GrowthService.LinkStat s = growth.linkStats(ch).getFirst();
        assertEquals(5, s.joined());
        assertEquals(0.75, s.kept7());
        assertEquals(0.5, s.kept30());
        assertEquals(40.0 / 3, s.costPerSubscriber(), 0.01);
    }

    @Test
    void otherChannelsThatForwardOrMentionUsAreListed() {
        var quoter = trackedChannels.save(new com.halty.gramalyzerbot.tracking.TrackedChannel("techdaily", "Tech Daily"));
        var self = trackedChannels.save(new com.halty.gramalyzerbot.tracking.TrackedChannel("pixelpulse", "Pixel Pulse"));
        tracked(quoter.getId(), 1, "Great thread, see @PixelPulse", null);
        tracked(quoter.getId(), 2, "forwarded", "Pixel Pulse");
        tracked(quoter.getId(), 3, "unrelated", null);
        tracked(self.getId(), 4, "follow t.me/pixelpulse", null);

        var list = citations.citations(ch);
        assertEquals(1, list.size(), "the channel quoting itself doesn't count");
        assertEquals("techdaily", list.getFirst().username());
        assertEquals(1, list.getFirst().forwards());
        assertEquals(1, list.getFirst().mentions());
    }

    private void tracked(long channel, int message, String body, String forwardedFrom) {
        var p = new com.halty.gramalyzerbot.tracking.TrackedPost();
        p.setTrackedChannelId(channel);
        p.setMessageId(message);
        p.setPostedAt(ago(60 * message));
        p.setKind("TEXT");
        p.setBody(body);
        p.setForwardedFrom(forwardedFrom);
        p.setUpdatedAt(now);
        trackedPosts.save(p);
    }

    @Test
    void revokingKeepsTheCountsAndProblemsAreExplained() {
        when(bot.createInviteLink(eq(CHAT), anyString())).thenReturn(Optional.of("https://t.me/+bio"));
        when(bot.revokeInviteLink(CHAT, "https://t.me/+bio")).thenReturn(true);
        PromoLink l = growth.create(ch, "Link in my bio", null, null);
        join(1, ago(5), "https://t.me/+bio");
        growth.revoke(ch, l.getId());
        GrowthService.LinkStat s = growth.linkStats(ch).getFirst();
        assertTrue(s.revoked());
        assertEquals(1, s.joined());
        assertNull(s.costPerSubscriber());

        when(bot.createInviteLink(eq(CHAT), anyString())).thenReturn(Optional.empty());
        assertEquals("promo.err.rights", assertThrows(UserFacingException.class, () -> growth.create(ch, "Ads", 5.0, "$")).key());
        assertEquals("promo.err.name", assertThrows(UserFacingException.class, () -> growth.create(ch, "  ", null, null)).key());
        assertEquals("promo.err.gone", assertThrows(UserFacingException.class, () -> growth.revoke(ch, 999_999)).key());
    }

    @Test
    void aWaveOfLeavesIsReportedOnceAndNotAgainForHalfADay() {
        post(ago(20), "Hot take");
        Optional<GrowthService.Wave> wave = Optional.empty();
        for (int i = 0; i < 5 && wave.isEmpty(); i++) wave = leave(i, ago(15 - i));
        assertTrue(wave.isPresent());
        assertEquals(5, wave.get().left());
        assertEquals("Hot take", wave.get().lastPost().getBody());
        for (int i = 5; i < 12; i++) assertTrue(leave(i, ago(5)).isEmpty());
    }

    @Test
    void wavesNeedEnoughPeopleAndFewJoins() {
        assertFalse(GrowthService.isWave(4, 0, 100));
        assertTrue(GrowthService.isWave(5, 1, 100));
        assertFalse(GrowthService.isWave(8, 3, 100));
        assertFalse(GrowthService.isWave(20, 0, 10_000)); // 0.5% of 10,000 is 50
        assertTrue(GrowthService.isWave(50, 2, 10_000));
    }

    @Test
    void peopleAreStoredAsAHashThatDiffersPerChannel() {
        assertEquals(GrowthService.member(1, 42), GrowthService.member(1, 42));
        assertNotEquals(GrowthService.member(1, 42), GrowthService.member(2, 42));
        join(42, ago(1), null);
        Long stored = jdbc.queryForObject("SELECT member FROM member_events", Long.class);
        assertNotEquals(42L, stored);
    }

    @Test
    void deletingAChannelForgetsItsGrowthData() {
        join(1, ago(1), null);
        channels.deleteAll(ch);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM member_events", Integer.class));
    }

    @Test
    void theBotShowsJoinsLeavesAndLinksInEveryLanguage() {
        when(bot.createInviteLink(eq(CHAT), anyString())).thenReturn(Optional.of("https://t.me/+ads"));
        post(ago(120), "Big news");
        for (int i = 0; i < 3; i++) join(i, ago(100 - i), null);
        join(9, ago(90), "https://t.me/+ads");
        growth.create(ch, "Ads <test>", 9.0, "$");
        for (com.halty.gramalyzerbot.i18n.Lang lang : com.halty.gramalyzerbot.i18n.Lang.values()) {
            String text = com.halty.gramalyzerbot.i18n.Tr.in(lang, () -> screens.growth(ch, 30).html() + "\n"
                    + screens.promo(ch, com.halty.gramalyzerbot.channel.Role.OWNER).html());
            assertTrue(text.contains("<b>4</b>"), lang + ": " + text);
            assertTrue(text.contains("https://t.me/+ads"), lang + ": " + text);
            assertTrue(text.contains("Ads &lt;test&gt;"), lang + ": " + text);
            assertFalse(text.contains("{0}"), lang + ": " + text);
        }
    }
}
