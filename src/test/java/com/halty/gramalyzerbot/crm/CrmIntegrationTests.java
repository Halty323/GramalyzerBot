package com.halty.gramalyzerbot.crm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.ChannelService;
import com.halty.gramalyzerbot.post.DemoService;
import com.halty.gramalyzerbot.post.PostService;
import com.halty.gramalyzerbot.workspace.Models;
import com.halty.gramalyzerbot.workspace.WorkspaceEvents;
import com.halty.gramalyzerbot.workspace.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The CRM workspace and the analytics bot working on one database, with Telegram switched off. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:crm;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "gramalyzer.demo=false", "gramalyzer.api-token=test-only-workspace-token", "telegram.bot.enabled=false"})
@AutoConfigureMockMvc
class CrmIntegrationTests {

    static final String AUTH = "Bearer test-only-workspace-token";
    static final long CHAT = -1009876543210L;

    @Autowired MockMvc http;
    @Autowired WorkspaceService workspace;
    @Autowired ChannelService channels;
    @Autowired PostService posts;
    @Autowired DemoService demo;
    @Autowired CrmBridge bridge;
    @Autowired ApplicationEventPublisher events;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    Models.Channel crm;

    @BeforeEach
    void reset() {
        for (String table : new String[]{"subscriber_snapshots", "posts", "discussion_roots", "channel_members", "invites",
                "audit_log", "channels", "channel_daily_metrics", "workspace_posts", "workspace_channels"})
            jdbc.update("DELETE FROM " + table);
        crm = workspace.registerChannel(CHAT, "Analytics test", "analytics_test", true);
    }

    private String insightsUrl() { return "/api/channels/" + crm.id() + "/insights"; }

    @Test
    void insightsAreProtectedLikeTheRestOfTheApi() throws Exception {
        http.perform(get(insightsUrl())).andExpect(status().isUnauthorized());
        http.perform(get("/api/channels/does-not-exist/insights").header("Authorization", AUTH))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Channel not found."));
    }

    @Test
    void aCrmChannelTheBotHasNotConnectedIsReportedAsSuch() throws Exception {
        http.perform(get(insightsUrl()).header("Authorization", AUTH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.channelId").value(crm.id()))
                .andExpect(jsonPath("$.status").value("not_connected"));
    }

    @Test
    void channelsAreMatchedByChatIdAndLinkedOnFirstUse() throws Exception {
        channels.register(CHAT, "Analytics test", "analytics_test", 1000);
        http.perform(get(insightsUrl()).header("Authorization", AUTH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("learning"))
                .andExpect(jsonPath("$.postsNeeded").value(20));
        assertThat(channels.byChatId(CHAT).orElseThrow().getWorkspaceChannelId()).isEqualTo(crm.id());
    }

    @Test
    void enoughHistoryGivesBestTimesAndUpcomingSuggestions() throws Exception {
        Channel ch = channels.register(CHAT, "Analytics test", "analytics_test", 5000);
        ch.setTimezone("Europe/Berlin");
        ch = channels.save(ch);
        demo.populate(ch, 42, 5000);

        String body = http.perform(get(insightsUrl()).header("Authorization", AUTH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready"))
                .andReturn().getResponse().getContentAsString();
        JsonNode json = mapper.readTree(body);
        assertThat(json.path("bestTimes").size()).isGreaterThan(0);
        assertThat(json.path("heatmap").path("lift").size()).isEqualTo(7);
        assertThat(json.path("heatmap").path("lift").get(0).size()).isEqualTo(24);
        Instant first = Instant.parse(json.path("suggestions").get(0).path("at").asText());
        assertThat(first).isAfter(Instant.now());
    }

    @Test
    void postsPublishedByTheCrmQueueReachTheAnalysisOnce() {
        Channel ch = channels.register(CHAT, "Analytics test", "analytics_test", 1000);
        events.publishEvent(new WorkspaceEvents.PostPublished(crm.id(), CHAT, 501, "Fresh #news with https://example.com", Instant.now()));
        events.publishEvent(new WorkspaceEvents.PostPublished(crm.id(), CHAT, 501, "Fresh #news with https://example.com", Instant.now()));
        assertThat(posts.count(ch)).isEqualTo(1);
        assertThat(posts.latest(ch).orElseThrow().isHasLink()).isTrue();
    }

    @Test
    void reactionAndMemberCountsFromTelegramShowUpOnTheWebsite() {
        workspace.channelPost(CHAT, 77, "Hello channel", Instant.now().getEpochSecond());
        workspace.recordReactions(CHAT, 77, 42);
        workspace.recordReactions(CHAT, 999, 5); // a message the CRM never saw: ignored
        workspace.recordSubscribers(CHAT, 1234);
        assertThat(workspace.post("telegram:" + CHAT + ":77").reactions()).isEqualTo(42);
        assertThat(workspace.post("telegram:" + CHAT + ":77").status()).isEqualTo("published");
        assertThat(workspace.channel(crm.id()).subscribers()).isEqualTo(1234);
    }

    @Test
    void timezoneChangesOnEitherSideReachTheOther() throws Exception {
        Channel ch = bridge.link(channels.register(CHAT, "Analytics test", "analytics_test", 1000));
        assertThat(ch.getWorkspaceChannelId()).isEqualTo(crm.id());

        http.perform(patch("/api/channels/" + crm.id()).header("Authorization", AUTH).contentType("application/json")
                .content("{\"timezone\":\"America/New_York\"}")).andExpect(status().isOk());
        Channel afterCrm = channels.byChatId(CHAT).orElseThrow();
        assertThat(afterCrm.getTimezone()).isEqualTo("America/New_York");
        assertThat(afterCrm.isTimezoneConfirmed()).isTrue();

        afterCrm.setTimezone("Asia/Tokyo");
        bridge.pushTimezone(channels.save(afterCrm));
        assertThat(workspace.channel(crm.id()).timezone()).isEqualTo("Asia/Tokyo");
    }

    @Test
    void aChannelConnectedOnlyThroughTheBotAppearsInTheCrm() {
        long other = -1005555555555L;
        Channel ch = bridge.ensureInCrm(channels.register(other, "Only in the bot", "only_in_bot", 10));
        Models.Channel created = workspace.byTelegramChat(other).orElseThrow();
        assertThat(ch.getWorkspaceChannelId()).isEqualTo(created.id());
        assertThat(created.paused()).isTrue(); // posting rights could not be confirmed (Telegram is off in tests)
    }
}
