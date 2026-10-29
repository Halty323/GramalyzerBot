package com.halty.gramalyzerbot.crosspromo;

import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.event.Events;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.ChannelService;
import com.halty.gramalyzerbot.channel.MembershipService;
import com.halty.gramalyzerbot.channel.Role;
import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.growth.GrowthService;
import com.halty.gramalyzerbot.i18n.Lang;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.user.User;
import com.halty.gramalyzerbot.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Cross-promo matching and swaps, against a real (H2) database with Telegram faked. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:crosspromo;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "gramalyzer.demo=false", "gramalyzer.api-token=test-only-workspace-token", "telegram.bot.enabled=false", "gramalyzer.tracking.refresh-enabled=false"})
class CrossPromoIntegrationTests {

    @Autowired SwapService swaps;
    @Autowired GrowthService growth;
    @Autowired ChannelService channels;
    @Autowired MembershipService membership;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.halty.gramalyzerbot.bot.screen.CrossPromoScreens screens;
    @MockitoBean BotApi bot;

    private final AtomicInteger links = new AtomicInteger();
    private long nextChat = -1001000000000L;
    private User alice, bob;

    @BeforeEach
    void reset() {
        for (String table : new String[]{"swaps", "swap_profiles", "member_events", "promo_links", "posts", "channel_members", "channels", "users"})
            jdbc.update("DELETE FROM " + table);
        when(bot.createInviteLink(anyLong(), anyString())).thenAnswer(i -> Optional.of("https://t.me/+swap" + links.incrementAndGet()));
        alice = users.save(new User(11L, "alice", "Alice"));
        bob = users.save(new User(22L, "bob", "Bob"));
    }

    private Channel channel(String title, String username, int subscribers, User owner, Topic topic, String language) {
        Channel ch = channels.register(nextChat--, title, username, subscribers);
        if (owner != null) membership.add(ch, owner, Role.OWNER, null);
        swaps.saveProfile(ch, topic, language, title + " in one line", true);
        return ch;
    }

    @Test
    void partnersShareTheLanguageHaveACloseTopicAndASimilarSize() {
        Channel mine = channel("Pixel Pulse", "pixelpulse", 1000, alice, Topic.TECH, "en");
        Channel twin = channel("Byte Size", "bytesize", 900, bob, Topic.TECH, "en");
        Channel related = channel("Star Notes", "starnotes", 1500, null, Topic.SCIENCE, "en");
        channel("Tiny Tech", "tinytech", 100, null, Topic.TECH, "en");          // a tenth of the size
        channel("Код щодня", "kod", 1000, null, Topic.TECH, "uk");             // another language
        channel("Pasta Daily", "pasta", 1000, null, Topic.FOOD, "en");          // unrelated topic
        channel("Pixel Pulse 2", "pixelpulse2", 1000, alice, Topic.TECH, "en"); // the same owner

        List<SwapService.Match> found = swaps.matches(mine);
        assertEquals(List.of(twin.getId(), related.getId()), found.stream().map(SwapService.Match::channelId).toList());
        assertTrue(found.get(0).score() > found.get(1).score());
        assertEquals("Byte Size in one line", found.get(0).pitch());

        swaps.saveProfile(mine, Topic.TECH, "en", null, false);
        assertTrue(swaps.matches(mine).isEmpty(), "a paused channel finds nobody");
        assertThrows(UserFacingException.class, () -> swaps.propose(mine, twin.getId()));
    }

    @Test
    void anAcceptedSwapGivesBothChannelsALinkAndCountsWhoEachPostBrought() {
        Channel mine = channel("Pixel Pulse", "pixelpulse", 1000, alice, Topic.TECH, "en");
        Channel theirs = channel("Byte Size", "bytesize", 900, bob, Topic.TECH, "en");
        Channel far = channel("Pasta Daily", "pasta", 1000, null, Topic.FOOD, "en");

        Swap s = swaps.propose(mine, theirs.getId());
        assertEquals(Swap.Status.REQUESTED, s.getStatus());
        verify(bot, timeout(2000)).send(eq(22L), any()); // Bob hears about it
        SwapService.SwapView asked = swaps.swapView(theirs, s.getId());
        assertTrue(asked.incoming());
        assertNull(asked.partnerUrl(), "who they are stays hidden until a yes");
        assertNull(asked.partnerContact());

        assertEquals("swap.err.already", assertThrows(UserFacingException.class, () -> swaps.propose(theirs, mine.getId())).key());
        assertEquals("swap.err.not_a_match", assertThrows(UserFacingException.class, () -> swaps.propose(mine, far.getId())).key());
        assertEquals("swap.err.answered", assertThrows(UserFacingException.class, () -> swaps.accept(mine, s.getId())).key());

        swaps.accept(theirs, s.getId());
        verify(bot, timeout(2000)).send(eq(11L), any()); // and Alice hears the answer
        SwapService.SwapView view = swaps.swapView(mine, s.getId());
        assertEquals(Swap.Status.ACTIVE, view.status());
        assertEquals("https://t.me/bytesize", view.partnerUrl());
        assertEquals("@bob", view.partnerContact());
        assertEquals("Swap: Byte Size", view.mine().name());
        assertEquals("Swap: Pixel Pulse", view.theirs().name());
        assertTrue(swaps.matches(mine).isEmpty(), "a channel with a running swap isn't suggested again");

        // two people join Pixel Pulse through the link Byte Size posted; one leaves again
        growth.record(new Events.MemberChange(mine.getTelegramChatId(), "channel", 501, false, false, true, view.mine().url(), Instant.now()));
        growth.record(new Events.MemberChange(mine.getTelegramChatId(), "channel", 502, false, false, true, view.mine().url(), Instant.now()));
        growth.record(new Events.MemberChange(mine.getTelegramChatId(), "channel", 502, false, true, false, null, Instant.now()));
        assertEquals(2, swaps.swapView(mine, s.getId()).mine().joined());
        assertEquals(1, swaps.swapView(mine, s.getId()).mine().stayed());
        assertEquals(2, swaps.swapView(theirs, s.getId()).theirs().joined(), "Byte Size sees what its post brought");

        swaps.end(theirs, s.getId());
        assertEquals(Swap.Status.DONE, swaps.swapView(mine, s.getId()).status());
        assertEquals(1, swaps.matches(mine).size(), "after the swap they can find each other again");
    }

    @Test
    void requestsCanBeDeclinedOrTakenBackAndAreCapped() {
        Channel mine = channel("Pixel Pulse", "pixelpulse", 1000, alice, Topic.TECH, "en");
        Channel[] others = new Channel[6];
        for (int i = 0; i < others.length; i++) others[i] = channel("Partner " + i, "partner" + i, 1000, null, Topic.TECH, "en");

        Swap declined = swaps.propose(mine, others[0].getId());
        swaps.decline(others[0], declined.getId());
        assertEquals(Swap.Status.DECLINED, swaps.swap(mine, declined.getId()).getStatus());
        Swap cancelled = swaps.propose(mine, others[0].getId());
        swaps.cancel(mine, cancelled.getId());
        assertEquals(Swap.Status.CANCELLED, swaps.swap(others[0], cancelled.getId()).getStatus());

        for (int i = 0; i < SwapService.MAX_OUTGOING; i++) swaps.propose(mine, others[i].getId());
        assertEquals("swap.err.too_many", assertThrows(UserFacingException.class, () -> swaps.propose(mine, others[5].getId())).key());
        assertEquals(SwapService.MAX_OUTGOING + 2, swaps.swaps(mine).size());

        channels.deleteAll(others[1]);
        assertEquals(SwapService.MAX_OUTGOING + 1, swaps.swaps(mine).size());
    }

    @Test
    void aSampleChannelGetsSamplePartnersARunningSwapAndARequest() {
        Channel demo = channels.register(-4_000_000_000_000_000_123L, "🌌 Demo channel", "demo_channel", 12_480);
        demo.setDemo(true);
        Channel saved = channels.save(demo);

        assertTrue(swaps.profile(saved).isPresent());
        List<SwapService.SwapView> list = swaps.swaps(saved);
        assertEquals(2, list.size());
        assertTrue(list.get(0).incoming());
        assertEquals(Swap.Status.ACTIVE, list.get(1).status());
        assertTrue(list.get(1).mine().joined() > 0);
        assertFalse(growth.report(saved, 30).daily().isEmpty(), "the sample growth data is still there");

        List<SwapService.Match> found = swaps.matches(saved);
        assertFalse(found.isEmpty());
        Swap s = swaps.propose(saved, found.get(0).channelId());
        assertEquals(Swap.Status.ACTIVE, s.getStatus(), "sample partners say yes straight away");
        verify(bot, never()).createInviteLink(anyLong(), anyString());

        // every screen reads in every language, with no key left untranslated
        for (Lang lang : Lang.values()) {
            List<String> shown = Tr.in(lang, () -> List.of(screens.home(saved, Role.OWNER).html(), screens.matches(saved).html(),
                    screens.topics(saved, Topic.TECH).html(), screens.languages(saved, Topic.TECH, "en").html(),
                    screens.swap(saved, Role.OWNER, swaps.swapView(saved, s.getId())).html(),
                    screens.swap(saved, Role.OWNER, list.get(0), SwapService.Notice.Kind.REQUESTED).html()));
            for (String html : shown) assertFalse(html.matches("(?s).*\\b(swap|topic|btn|toast)\\.[a-z_.A-Z]+.*"), lang + ": " + html);
        }
    }

    @Test
    void scoreWeighsTopicThenSizeThenEngagement() {
        assertEquals(1.0, SwapService.score(1, 1.0, 1.0), 1e-9);
        assertEquals(0.45 + 0.175 + 0.1, SwapService.score(1, null, null), 1e-9);
        assertEquals(0.5, SwapService.closeness(500, 1000));
        assertNull(SwapService.closeness(null, 1000));
    }
}
