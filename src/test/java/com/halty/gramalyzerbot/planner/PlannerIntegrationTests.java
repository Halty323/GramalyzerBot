package com.halty.gramalyzerbot.planner;

import com.halty.gramalyzerbot.analytics.PostKind;
import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.screen.PlannerScreens;
import com.halty.gramalyzerbot.channel.*;
import com.halty.gramalyzerbot.i18n.Lang;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.post.AnalysisService;
import com.halty.gramalyzerbot.post.Post;
import com.halty.gramalyzerbot.post.PostRepository;
import com.halty.gramalyzerbot.post.PostSource;
import com.halty.gramalyzerbot.workspace.Models;
import com.halty.gramalyzerbot.workspace.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Draft score, free slots, filling drafts and evergreen reposts, against a real (H2) database with Telegram faked. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:planner;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "gramalyzer.demo=false", "gramalyzer.api-token=test-only-workspace-token", "telegram.bot.enabled=false", "gramalyzer.tracking.refresh-enabled=false"})
class PlannerIntegrationTests {

    @Autowired PlannerService planner;
    @Autowired PlannerScreens screens;
    @Autowired ChannelService channels;
    @Autowired AnalysisService analysis;
    @Autowired PostRepository posts;
    @Autowired WorkspaceService workspace;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean BotApi bot;

    private final Instant now = Instant.now();
    private Channel ch;
    private String crm;
    private int message = 1;

    @BeforeEach
    void reset() {
        for (String table : new String[]{"workspace_posts", "workspace_channels", "posts", "subscriber_snapshots", "audit_log",
                "channel_members", "channels", "users"}) jdbc.update("DELETE FROM " + table);
        ch = channels.register(-1001234567890L, "Pixel Pulse", "pixelpulse", 1000);
        crm = planner.crmId(ch).orElseThrow();
        ch = channels.get(ch.getId());
    }

    private Instant day(int back, int hour) {
        return now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(back).atTime(hour, 0).toInstant(ZoneOffset.UTC);
    }

    private Post post(int back, int hour, PostKind kind, String body, int reactions) {
        Post p = new Post();
        p.setChannelId(ch.getId());
        p.setMessageId(message++);
        p.setPostedAt(day(back, hour));
        p.setKind(kind);
        p.setBody(body);
        p.setSource(PostSource.LIVE);
        p.setReactions(reactions);
        return posts.save(p);
    }

    /** Forty recent posts where evening videos do three times better than morning texts. */
    private void history() {
        for (int i = 0; i < 40; i++) {
            boolean video = i % 2 == 0;
            post(i + 1, video ? 18 : 9, video ? PostKind.VIDEO : PostKind.TEXT,
                    (video ? "Speedrun clip " : "Patch notes ") + i, video ? 60 + i % 3 : 20 + i % 3);
        }
        analysis.invalidate(ch.getId());
    }

    @Test
    void aMorningTextDraftIsWeakOnFormatAndTimeAndAnEveningVideoScoresHigher() {
        history();
        Instant morning = now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(2).atTime(9, 0).toInstant(ZoneOffset.UTC);
        DraftScore.Result text = planner.score(ch, new DraftScore.Draft("Patch notes for today", "TEXT", false, morning));
        assertEquals("ready", text.status());
        assertTrue(text.score() < 50, "score " + text.score());
        DraftScore.Factor format = text.factors().stream().filter(f -> f.dimension() == DraftScore.Dimension.FORMAT).findFirst().orElseThrow();
        assertTrue(format.weak());
        assertEquals("VIDEO", format.bestKey());
        assertTrue(text.factors().stream().anyMatch(f -> f.dimension() == DraftScore.Dimension.TIME && f.weak()), text.factors().toString());
        assertTrue(text.potential() > text.expected());

        Instant evening = morning.plus(Duration.ofHours(9));
        DraftScore.Result video = planner.score(ch, new DraftScore.Draft("Speedrun clip of the week", "VIDEO", false, evening));
        assertTrue(video.score() > 50 && video.score() > text.score(), "score " + video.score());
        assertTrue(video.factors().stream().noneMatch(DraftScore.Factor::weak), video.factors().toString());
    }

    @Test
    void aNewChannelIsStillLearningAndRotatesThroughTheDay() {
        post(1, 9, PostKind.TEXT, "Hello", 3);
        assertEquals("learning", planner.score(ch, new DraftScore.Draft("Hi", "TEXT", false, null)).status());
        List<PlannerService.Slot> slots = planner.free(ch, crm, null, 3);
        assertEquals(3, slots.size());
        assertTrue(slots.stream().allMatch(PlannerService.Slot::guess));
        assertTrue(slots.stream().allMatch(s -> List.of(9, 14, 19).contains(s.at().atZone(ZoneOffset.UTC).getHour())));
        // no folklore "best hours": one a day, morning, afternoon and evening in turn, so the channel learns its own
        assertEquals(3, slots.stream().map(s -> s.at().atZone(ZoneOffset.UTC).getHour()).distinct().count());
        assertEquals(3, slots.stream().map(s -> s.at().atZone(ZoneOffset.UTC).toLocalDate()).distinct().count());
    }

    @Test
    void freeSlotsAreTheBestHoursKeptApartFromTheQueue() {
        history();
        List<PlannerService.Slot> slots = planner.free(ch, crm, null, 4);
        assertEquals(4, slots.size());
        assertTrue(slots.stream().allMatch(s -> s.at().atZone(ZoneOffset.UTC).getHour() == 18 && !s.guess()), slots.toString());
        assertEquals(4, slots.stream().map(s -> s.at().atZone(ZoneOffset.UTC).toLocalDate()).distinct().count(), "one post a day, like the channel");

        workspace.save(null, new Models.PostInput(crm, "Queued", "Already planned", "scheduled", slots.getFirst().at(), "Community"));
        List<PlannerService.Slot> after = planner.free(ch, crm, null, 4);
        assertTrue(after.stream().noneMatch(s -> s.at().equals(slots.getFirst().at())), "a queued post takes its slot");
    }

    @Test
    void fillingPutsTheOldestDraftOnTheBestSlotAndOnlySchedulesWhenAsked() {
        history();
        Models.Post first = workspace.save(null, new Models.PostInput(crm, "First draft", "One", "draft", now.plus(Duration.ofDays(30)), "Community"));
        Models.Post second = workspace.save(null, new Models.PostInput(crm, "Second draft", "Two", "draft", now.plus(Duration.ofDays(30)), "Community"));

        List<PlannerService.Assignment> preview = planner.fill(ch, crm, null, false);
        assertEquals(2, preview.size());
        assertEquals("draft", workspace.post(first.id()).status(), "a preview changes nothing");

        List<PlannerService.Assignment> done = planner.fill(ch, crm, List.of(second.id()), true);
        assertEquals(1, done.size());
        Models.Post scheduled = workspace.post(second.id());
        assertEquals("scheduled", scheduled.status());
        assertEquals(done.getFirst().at(), scheduled.scheduledAt());
        assertEquals("draft", workspace.post(first.id()).status());
    }

    @Test
    void anOldHitIsOfferedOnceAndItsAlbumIsRepostedSilentlyAtAFreeSlot() {
        history();
        Post hit = post(120, 18, PostKind.PHOTO, "The guide everyone saved", 400);
        hit.setMediaGroupId("g1");
        posts.save(hit);
        Post part = post(120, 18, PostKind.PHOTO, "", 0);
        part.setMediaGroupId("g1");
        posts.save(part);
        post(110, 9, PostKind.TEXT, "An ordinary old post", 20);
        post(10, 18, PostKind.VIDEO, "A fresh hit, too new to repeat", 500);
        analysis.invalidate(ch.getId());

        List<PlannerService.Evergreen> list = planner.evergreen(ch, crm);
        assertEquals(1, list.size(), list.toString());
        PlannerService.Evergreen e = list.getFirst();
        assertEquals(hit.getMessageId(), e.messageId());
        assertNull(e.queuedAt());
        assertEquals("https://t.me/pixelpulse/" + hit.getMessageId(), e.url());

        Models.Post repost = planner.repost(ch, crm, e.messageId());
        assertEquals(List.of(hit.getMessageId(), part.getMessageId()), repost.repostOf());
        assertTrue(repost.silent());
        assertEquals("scheduled", repost.status());
        assertEquals("Evergreen", repost.category());
        assertTrue(repost.title().startsWith("🔁 The guide"));
        assertTrue(planner.free(ch, crm, null, 10).stream().noneMatch(s -> s.at().equals(repost.scheduledAt())));

        assertEquals(repost.scheduledAt(), planner.evergreen(ch, crm).getFirst().queuedAt());
        WorkspaceService.Problem twice = assertThrows(WorkspaceService.Problem.class, () -> planner.repost(ch, crm, e.messageId()));
        assertEquals(409, twice.status);
        assertThrows(WorkspaceService.Problem.class, () -> planner.repost(ch, crm, 9999));
    }

    @Test
    void aRepostCantCarryNewMediaOrButtons() {
        WorkspaceService.Problem p = assertThrows(WorkspaceService.Problem.class, () -> workspace.save(null, new Models.PostInput(crm, "🔁 x", "", "scheduled",
                now.plus(Duration.ofDays(1)), "Evergreen", null, List.of(new Models.LinkButton("Shop", "https://shop.example")), true, false, List.of(5))));
        assertEquals(400, p.status);
    }

    @Test
    void theScreensReadInEveryLanguage() {
        history();
        post(120, 18, PostKind.PHOTO, "The guide everyone saved", 400);
        analysis.invalidate(ch.getId());
        Channel empty = channels.register(-1009999999999L, "Secret Club", null, 500);
        for (Lang lang : Lang.values()) {
            List<String> shown = Tr.in(lang, () -> List.of(screens.prompt(ch).html(),
                    screens.score(ch, "Patch notes with a link https://example.com #news", "TEXT").html(),
                    screens.score(empty, "Hi", "TEXT").html(),
                    screens.evergreen(ch, Role.OWNER).html(), screens.evergreen(empty, Role.OWNER).html()));
            for (String html : shown) assertFalse(html.matches("(?s).*\\b(plan|draft|ever|btn|lbl|kind|len|link)\\.[a-z_.A-Z0-9]+.*"), lang + ": " + html);
        }
    }
}
