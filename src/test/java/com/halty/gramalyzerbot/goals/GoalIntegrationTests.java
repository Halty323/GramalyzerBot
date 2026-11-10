package com.halty.gramalyzerbot.goals;

import com.halty.gramalyzerbot.analytics.PostKind;
import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.screen.DigestBuilder;
import com.halty.gramalyzerbot.bot.screen.GoalScreens;
import com.halty.gramalyzerbot.channel.*;
import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.i18n.Lang;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.post.AnalysisService;
import com.halty.gramalyzerbot.post.Post;
import com.halty.gramalyzerbot.post.PostRepository;
import com.halty.gramalyzerbot.post.PostSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Goals and streaks against a real (H2) database with Telegram faked. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:goals;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "gramalyzer.demo=false", "gramalyzer.api-token=test-only-workspace-token", "telegram.bot.enabled=false", "gramalyzer.tracking.refresh-enabled=false"})
class GoalIntegrationTests {

    @Autowired GoalService goals;
    @Autowired GoalScreens screens;
    @Autowired DigestBuilder digests;
    @Autowired ChannelService channels;
    @Autowired SubscriberSnapshotRepository snapshots;
    @Autowired PostRepository posts;
    @Autowired AnalysisService analysis;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean BotApi bot;

    private final Instant now = Instant.now();
    private Channel ch;

    @BeforeEach
    void reset() {
        for (String table : new String[]{"channel_goals", "posts", "subscriber_snapshots", "audit_log", "channel_members", "channels", "users"})
            jdbc.update("DELETE FROM " + table);
        ch = channels.register(-1001234567890L, "Pixel Pulse", "pixelpulse", 1200);
        // 10 new subscribers a day for two weeks, and three posts a week for a month
        List<SubscriberSnapshot> list = new ArrayList<>();
        for (int i = 14; i >= 1; i--) list.add(new SubscriberSnapshot(ch.getId(), now.minus(Duration.ofDays(i)), 1200 - i * 10));
        snapshots.saveAll(list);
        LocalDate monday = LocalDate.now(ch.zone()).with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        int message = 1;
        for (int w = 1; w <= 4; w++) for (int d = 0; d < 3; d++) {
            Post p = new Post();
            p.setChannelId(ch.getId());
            p.setMessageId(message++);
            p.setPostedAt(monday.minusWeeks(w).plusDays(d).atTime(12, 0).atZone(ch.zone()).toInstant());
            p.setKind(PostKind.TEXT);
            p.setBody("Post " + message);
            p.setSource(PostSource.LIVE);
            posts.save(p);
        }
        analysis.invalidate(ch.getId());
    }

    @Test
    void withoutAGoalThereAreSuggestionsAndTheStreak() {
        GoalService.Status s = goals.status(ch);
        assertNull(s.goal());
        assertEquals(1200, s.subscribers());
        assertEquals(2, s.suggestions().size());
        assertTrue(s.suggestions().stream().allMatch(x -> x.target() > 1200 && x.deadline().isAfter(LocalDate.now(ch.zone()))));
        assertEquals(4, s.streak().weeks());
        goals.set(ch, s.suggestions().get(0).target(), s.suggestions().get(0).deadline(), null);
        assertEquals(GoalMath.State.ON_TRACK, goals.status(ch).goal().state(), "the near suggestion starts on track");
    }

    @Test
    void aGoalShowsItsPaceIsReachedOnceAndCanBeRemoved() {
        LocalDate deadline = LocalDate.now(ch.zone()).plusDays(30);
        goals.set(ch, 1500, deadline, null);
        GoalMath.Progress p = goals.status(ch).goal();
        assertEquals(GoalMath.State.ON_TRACK, p.state());
        assertEquals(10.0, p.perDay(), 0.2);
        assertEquals(0, p.percent());

        assertThrows(UserFacingException.class, () -> goals.set(ch, 1100, deadline, null));
        assertThrows(UserFacingException.class, () -> goals.set(ch, 1500, LocalDate.now(ch.zone()), null));

        assertTrue(goals.reachedNow(ch).isEmpty());
        ch.setSubscribers(1510);
        ch = channels.save(ch);
        assertTrue(goals.reachedNow(ch).isPresent());
        assertTrue(goals.reachedNow(ch).isEmpty(), "only once");
        assertEquals(GoalMath.State.REACHED, goals.status(ch).goal().state());

        goals.clear(ch);
        assertNull(goals.status(ch).goal());
        assertEquals(5, goals.setStreakTarget(ch, 5).getStreakTarget());
        assertEquals(0, goals.status(ch).streak().weeks(), "three posts a week don't make a five-post streak");
        assertThrows(UserFacingException.class, () -> goals.setStreakTarget(ch, 0));
    }

    @Test
    void theScreensAndTheDigestReadInEveryLanguage() {
        Channel empty = channels.register(-1009999999999L, "Secret Club", null, null);
        for (Lang lang : Lang.values()) {
            goals.clear(ch);
            List<String> shown = new ArrayList<>(Tr.in(lang, () -> List.of(screens.home(ch, Role.OWNER).html(), screens.home(empty, Role.OWNER).html(),
                    screens.prompt(ch, 1200).html())));
            goals.set(ch, 1500, LocalDate.now(ch.zone()).plusDays(20), null);
            shown.addAll(Tr.in(lang, () -> List.of(screens.home(ch, Role.OWNER).html(), screens.home(ch, Role.MODERATOR).html(), digests.build(ch).html())));
            for (String html : shown) assertFalse(html.matches("(?s).*\\b(goal|btn|n)\\.[a-z_.A-Z0-9]+.*"), lang + ": " + html);
            if (lang == Lang.EN && System.getenv("SHOW") != null) shown.forEach(h -> System.out.println("=====\n" + h));
            if (lang == Lang.EN) assertTrue(shown.get(3).contains("Target: <b>1,500</b> subscribers"), shown.get(3));
        }
    }
}
