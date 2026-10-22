package com.halty.gramalyzerbot.tracking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.halty.gramalyzerbot.analytics.PostKind;
import com.halty.gramalyzerbot.bot.event.Events;
import com.halty.gramalyzerbot.channel.ChannelService;
import com.halty.gramalyzerbot.post.PostService;
import com.halty.gramalyzerbot.workspace.Models;
import com.halty.gramalyzerbot.workspace.WorkspaceService;
import com.halty.gramalyzerbot.workspace.WorkspaceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Imaginary channels that keep publishing, editing and deleting posts, watched through the channel viewer the website
 * and Studio use (and the AI reads its examples from). Telegram's public pages come from {@link FakeTelegram}, which
 * also counts how often they were downloaded.
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:imaginary;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "gramalyzer.demo=false", "gramalyzer.api-token=test-only-workspace-token", "telegram.bot.enabled=false",
        "gramalyzer.tracking.refresh-enabled=false"})
@AutoConfigureMockMvc
class ImaginaryChannelsTests {

    static final String AUTH = "Bearer test-only-workspace-token";
    static final long CHAT = -1004242424242L;

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired WorkspaceService workspace;
    @Autowired WorkspaceStore store;
    @Autowired TrackingService tracking;
    @Autowired ChannelService channels;
    @Autowired PostService posts;
    @MockitoBean PreviewClient client;

    FakeTelegram telegram;

    @BeforeEach
    void reset() throws IOException {
        for (String table : new String[]{"tracked_watchers", "tracked_snapshots", "tracked_posts", "tracked_channels",
                "subscriber_snapshots", "posts", "discussion_roots", "channel_members", "invites", "audit_log", "channels",
                "channel_daily_metrics", "workspace_posts", "workspace_channels"})
            jdbc.update("DELETE FROM " + table);
        telegram = new FakeTelegram();
        when(client.preview(anyString(), any())).thenAnswer(inv -> telegram.preview(inv.getArgument(0), inv.getArgument(1)));
    }

    @Test
    void postsAreReadOnceAndNewOnesShowUpWithinTwoMinutes() throws Exception {
        FakeTelegram.Channel news = telegram.channel("fresh_news", "Fresh news");
        news.burst(30, "News");
        Models.Channel own = workspace.registerChannel(CHAT, "Fresh news", "fresh_news", true);

        JsonNode first = feed(own);
        assertThat(first.path("source").asText()).isEqualTo("preview");
        assertThat(ids(first)).isEqualTo(range(30, 1)); // the first visit reads older pages too
        assertThat(first.path("posts").get(0).path("text").asText()).isEqualTo("News 30");
        assertThat(stored("fresh_news")).isEqualTo(range(1, 30));
        int downloads = telegram.requests("fresh_news");

        // opening the viewer again, or the AI reading the channel, uses what is stored
        news.text("Breaking: something happened");
        feed(own);
        feed(own);
        assertThat(telegram.requests("fresh_news")).isEqualTo(downloads);

        // two minutes later the next visit looks again: one page, since the new post fits on it
        age("fresh_news", Duration.ofMinutes(3));
        JsonNode later = feed(own);
        assertThat(telegram.requests("fresh_news")).isEqualTo(downloads + 1);
        assertThat(later.path("posts").get(0).path("messageId").asInt()).isEqualTo(31);
        assertThat(later.path("posts").get(0).path("text").asText()).isEqualTo("Breaking: something happened");
        assertThat(later.path("posts").size()).isEqualTo(31);
        assertThat(jdbc.queryForObject("SELECT body FROM tracked_posts WHERE message_id=31", String.class))
                .isEqualTo("Breaking: something happened");
    }

    @Test
    void aBurstOfPostsLeavesNoGap() throws Exception {
        FakeTelegram.Channel busy = telegram.channel("busy_feed", "Busy feed");
        busy.burst(30, "Morning");
        Models.Channel own = workspace.registerChannel(CHAT, "Busy feed", "busy_feed", true);
        feed(own);

        // more posts than one page holds come out before anyone looks again
        busy.burst(45, "Evening");
        age("busy_feed", Duration.ofMinutes(3));
        int before = telegram.requests("busy_feed");
        JsonNode feed = feed(own);

        assertThat(stored("busy_feed")).isEqualTo(range(1, 75));
        assertThat(telegram.requests("busy_feed") - before).isEqualTo(3); // pages until they reach post 30, no further
        assertThat(feed.path("posts").size()).isEqualTo(60);
        assertThat(ids(feed)).isEqualTo(range(75, 16));
        assertThat(feed.path("posts").get(0).path("text").asText()).isEqualTo("Evening 45");
    }

    @Test
    void editsAndDeletionsReachTheStoredPosts() throws Exception {
        FakeTelegram.Channel c = telegram.channel("fix_it", "Fix it");
        c.burst(25, "Post");
        Models.Channel own = workspace.registerChannel(CHAT, "Fix it", "fix_it", true);
        feed(own);

        c.edit(24, "Post 24, now without the typo");
        c.delete(22);
        c.delete(3); // an old one, on the second page
        age("fix_it", Duration.ofMinutes(3));
        JsonNode feed = feed(own);

        JsonNode edited = post(feed, 24);
        assertThat(edited.path("text").asText()).isEqualTo("Post 24, now without the typo");
        assertThat(edited.path("edited").asBoolean()).isTrue();
        assertThat(ids(feed)).doesNotContain(22);
        assertThat(stored("fix_it")).doesNotContain(22);
        // older pages are only read when needed, so a post deleted further back stays until the page holding it is read
        assertThat(stored("fix_it")).contains(3);
        assertThat(feed.path("posts").size()).isEqualTo(24);
    }

    @Test
    void ownChannelsAreKeptAndRefreshedForGood() throws Exception {
        telegram.channel("keeper", "Keeper").burst(5, "Kept");
        telegram.channel("passerby", "Passerby").burst(5, "Seen once");
        telegram.channel("keeper_renamed", "Keeper").burst(6, "Kept");
        Models.Channel own = workspace.registerChannel(CHAT, "Keeper", "keeper", true);
        feed(own);
        tracking.lookup("passerby"); // somebody only looked it up on the website

        assertThat(tracking.forgetUnwatched(Duration.ZERO)).isEqualTo(1);
        assertThat(tracking.find("keeper")).isPresent();
        assertThat(tracking.find("passerby")).isEmpty();
        assertThat(tracking.watchedAnywhere()).extracting(TrackedChannel::getUsername).containsExactly("keeper");
        assertThat(stored("keeper")).hasSize(5);

        // the channel got a new username: the old one is somebody else's now and is let go
        tracking.own(own.id(), "keeper_renamed");
        assertThat(tracking.watchedAnywhere()).extracting(TrackedChannel::getUsername).containsExactly("keeper_renamed");
        tracking.forgetUnwatched(Duration.ZERO);
        assertThat(tracking.find("keeper")).isEmpty();
    }

    @Test
    void aPostTheBotHearsAboutIsOnTopRightAway() throws Exception {
        FakeTelegram.Channel live = telegram.channel("live_room", "Live room");
        live.burst(12, "Earlier");
        channels.register(CHAT, "Live room", "live_room", 500);
        Models.Channel own = workspace.registerChannel(CHAT, "Live room", "live_room", true);
        feed(own);
        int downloads = telegram.requests("live_room");

        // Telegram sends the bot the post the moment it is published, before anyone reads the public page again
        int id = live.nextId();
        posts.onChannelPost(new Events.ChannelPost(CHAT, id, Instant.now(), false, PostKind.TEXT, "Hot off the press", false, null));
        JsonNode now = feed(own);
        assertThat(telegram.requests("live_room")).isEqualTo(downloads);
        assertThat(now.path("posts").get(0).path("messageId").asInt()).isEqualTo(id);
        assertThat(now.path("posts").get(0).path("text").asText()).isEqualTo("Hot off the press");
        assertThat(now.path("posts").get(0).path("views").isNull()).isTrue();
        assertThat(now.path("posts").size()).isEqualTo(13);

        // once the public page shows it too, it is there once, with its views
        live.text("Hot off the press");
        age("live_room", Duration.ofMinutes(3));
        JsonNode later = feed(own);
        assertThat(ids(later).stream().filter(i -> i == id)).hasSize(1);
        assertThat(post(later, id).path("views").asInt()).isPositive();
        assertThat(later.path("posts").size()).isEqualTo(13);
    }

    @Test
    void anAlbumIsOnePostWithItsCaption() throws Exception {
        // a private channel: no public page, so the viewer shows what the bot collected
        channels.register(CHAT, "Private pictures", null, 40);
        Models.Channel own = workspace.registerChannel(CHAT, "Private pictures", null, true);
        Instant at = Instant.parse("2026-10-20T12:00:00Z");
        hear(10, at, PostKind.TEXT, "A plain post", null);
        // Telegram sends an album as one message per picture; here the caption is on the second one
        hear(11, at.plusSeconds(3600), PostKind.PHOTO, null, "album-1");
        hear(12, at.plusSeconds(3600), PostKind.PHOTO, "Three pictures from the trip", "album-1");
        hear(13, at.plusSeconds(3600), PostKind.PHOTO, null, "album-1");

        JsonNode feed = feed(own);
        assertThat(feed.path("source").asText()).isEqualTo("bot");
        assertThat(feed.path("posts").size()).isEqualTo(2);
        JsonNode album = feed.path("posts").get(0);
        assertThat(album.path("messageId").asInt()).isEqualTo(11);
        assertThat(album.path("kind").asText()).isEqualTo("ALBUM");
        assertThat(album.path("text").asText()).isEqualTo("Three pictures from the trip");
        assertThat(feed.path("posts").get(1).path("text").asText()).isEqualTo("A plain post");

        // the website's own post list gets one post for the album too, named after its caption
        long ts = at.getEpochSecond() + 3600;
        workspace.channelPost(CHAT, 11, null, ts, "album-1", false);
        workspace.channelPost(CHAT, 12, "Three pictures from the trip", ts, "album-1", false);
        workspace.channelPost(CHAT, 13, null, ts, "album-1", false);
        List<Models.Post> saved = store.posts().stream().filter(p -> p.channelId().equals(own.id())).toList();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).title()).isEqualTo("Three pictures from the trip");
        assertThat(saved.get(0).body()).isEqualTo("Three pictures from the trip");
    }

    @Test
    void editsInTheChannelReachTheWebsitesPostList() throws Exception {
        Models.Channel own = workspace.registerChannel(CHAT, "Editors", null, true);
        long ts = Instant.parse("2026-10-20T12:00:00Z").getEpochSecond();
        workspace.channelPost(CHAT, 20, "Teh first post", ts, null, false);
        workspace.channelPost(CHAT, 20, "The first post", ts, null, true);
        assertThat(store.post("telegram:" + CHAT + ":20").orElseThrow().body()).isEqualTo("The first post");
        assertThat(store.post("telegram:" + CHAT + ":20").orElseThrow().title()).isEqualTo("The first post");

        // a post the website's queue published keeps the title its author gave it
        store.insert(new Models.Post("queued-1", own.id(), "Launch announcement", "We launch on Monday", "published",
                Instant.parse("2026-10-20T13:00:00Z"), 0, 0, 0, "News", "sent", 21L, null));
        workspace.channelPost(CHAT, 21, "We launch on Tuesday", ts, null, true);
        Models.Post queued = store.post("queued-1").orElseThrow();
        assertThat(queued.body()).isEqualTo("We launch on Tuesday");
        assertThat(queued.title()).isEqualTo("Launch announcement");
        assertThat(queued.deliveryState()).isEqualTo("sent");
        assertThat(store.posts()).hasSize(2);
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode feed(Models.Channel c) throws Exception {
        String body = http.perform(get("/api/channels/" + c.id() + "/feed").param("tz", "Europe/Kyiv").header("Authorization", AUTH))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    private static List<Integer> ids(JsonNode feed) {
        List<Integer> out = new ArrayList<>();
        for (JsonNode p : feed.path("posts")) out.add(p.path("messageId").asInt());
        return out;
    }

    private static JsonNode post(JsonNode feed, int messageId) {
        for (JsonNode p : feed.path("posts")) if (p.path("messageId").asInt() == messageId) return p;
        throw new AssertionError("post " + messageId + " is not in the feed");
    }

    private static List<Integer> range(int from, int to) {
        return from <= to ? IntStream.rangeClosed(from, to).boxed().toList()
                : IntStream.rangeClosed(to, from).map(i -> from + to - i).boxed().toList();
    }

    /** Message ids stored for a public channel, oldest first. */
    private List<Integer> stored(String username) {
        return jdbc.queryForList("SELECT p.message_id FROM tracked_posts p JOIN tracked_channels c ON c.id = p.tracked_channel_id "
                + "WHERE c.username = ? ORDER BY p.message_id", Integer.class, username);
    }

    /** Pretends the last look at a channel's page was a while ago. */
    private void age(String username, Duration by) {
        jdbc.update("UPDATE tracked_channels SET fetched_at = ? WHERE username = ?", Timestamp.from(Instant.now().minus(by)), username);
    }

    private void hear(int messageId, Instant at, PostKind kind, String text, String album) {
        posts.onChannelPost(new Events.ChannelPost(CHAT, messageId, at, false, kind, text, false, album));
    }
}
