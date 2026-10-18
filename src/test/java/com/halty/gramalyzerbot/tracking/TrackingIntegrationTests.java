package com.halty.gramalyzerbot.tracking;

import com.halty.gramalyzerbot.workspace.Models;
import com.halty.gramalyzerbot.workspace.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The website's watchlist, with Telegram's public pages replaced by saved copies. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:tracking;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "gramalyzer.demo=false", "gramalyzer.api-token=test-only-workspace-token", "telegram.bot.enabled=false",
        "gramalyzer.tracking.refresh-enabled=false"})
@AutoConfigureMockMvc
class TrackingIntegrationTests {

    static final String AUTH = "Bearer test-only-workspace-token";

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkspaceService workspace;
    @MockitoBean PreviewClient client;

    @BeforeEach
    void reset() throws IOException {
        for (String table : new String[]{"tracked_watchers", "tracked_snapshots", "tracked_posts", "tracked_channels",
                "workspace_posts", "workspace_channels"})
            jdbc.update("DELETE FROM " + table);
        when(client.preview(eq("samplechannel"), any())).thenReturn(
                new PreviewClient.Page(200, "https://t.me/s/samplechannel", PreviewParserTest.fixture("preview.html")));
        when(client.preview(eq("someperson"), any())).thenReturn(
                new PreviewClient.Page(200, "https://t.me/someperson", PreviewParserTest.fixture("page-user.html")));
        when(client.preview(eq("nosuchchannel"), any())).thenReturn(
                new PreviewClient.Page(200, "https://t.me/nosuchchannel", PreviewParserTest.fixture("page-missing.html")));
        when(client.preview(eq("offline_channel"), any())).thenThrow(new IOException("connection reset"));
    }

    @Test
    void theWatchlistIsProtectedLikeTheRestOfTheApi() throws Exception {
        http.perform(get("/api/tracked")).andExpect(status().isUnauthorized());
    }

    @Test
    void anyPublicChannelCanBeTrackedByUsername() throws Exception {
        http.perform(post("/api/tracked").header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"https://t.me/SampleChannel\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channel.username").value("samplechannel"))
                .andExpect(jsonPath("$.channel.title").value("Sample Channel"))
                .andExpect(jsonPath("$.channel.subscribers").value(48200))
                .andExpect(jsonPath("$.channel.tracked").value(true))
                .andExpect(jsonPath("$.counters.photos").value(1100))
                .andExpect(jsonPath("$.posts.length()").value(3))
                .andExpect(jsonPath("$.posts[0].messageId").value(103))
                .andExpect(jsonPath("$.posts[0].views").value(1050000))
                .andExpect(jsonPath("$.posts[2].reactionList[0].emoji").value("👍"))
                .andExpect(jsonPath("$.stats.avgViews").isNumber())
                .andExpect(jsonPath("$.top[0].messageId").value(103));

        // tracking it again changes nothing
        http.perform(post("/api/tracked").header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"@samplechannel\"}")).andExpect(status().isCreated());
        http.perform(get("/api/tracked").header("Authorization", AUTH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("samplechannel"))
                .andExpect(jsonPath("$[0].series[0]").value(48200));

        http.perform(delete("/api/tracked/samplechannel").header("Authorization", AUTH)).andExpect(status().isNoContent());
        http.perform(get("/api/tracked").header("Authorization", AUTH)).andExpect(jsonPath("$.length()").value(0));
        http.perform(delete("/api/tracked/samplechannel").header("Authorization", AUTH))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("not_tracked"));
    }

    @Test
    void lookingAChannelUpDoesNotTrackIt() throws Exception {
        http.perform(get("/api/tracked/samplechannel").header("Authorization", AUTH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.channel.tracked").value(false));
        http.perform(get("/api/tracked").header("Authorization", AUTH)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void reasonsComeWithACodeTheWebsiteCanTranslate() throws Exception {
        http.perform(get("/api/tracked/someperson").header("Authorization", AUTH))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("not_channel"));
        http.perform(get("/api/tracked/nosuchchannel").header("Authorization", AUTH))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("not_found"));
        http.perform(get("/api/tracked/offline_channel").header("Authorization", AUTH))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("unreachable"));
        http.perform(post("/api/tracked").header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"https://t.me/+AbCdEf123\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("private_link"));
        http.perform(post("/api/tracked").header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"no\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_username"));
    }

    @Test
    void ownPublicChannelsShowTheirPreviewInTheViewer() throws Exception {
        Models.Channel own = workspace.registerChannel(-1001234567890L, "Sample Channel", "samplechannel", true);
        http.perform(get("/api/channels/" + own.id() + "/feed").param("tz", "Europe/Kyiv").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("preview"))
                .andExpect(jsonPath("$.posts[0].views").value(1050000))
                .andExpect(jsonPath("$.posts[0].url").value("https://t.me/samplechannel/103"))
                .andExpect(jsonPath("$.stats.hours.length()").value(24));
    }

    @Test
    void privateOwnChannelsFallBackToTheirPublishedPosts() throws Exception {
        when(client.preview(anyString(), any())).thenThrow(new IOException("should not be asked"));
        Models.Channel own = workspace.registerChannel(-1009999999999L, "Private room", null, true);
        workspace.channelPost(-1009999999999L, 7, "Hello from the private room", 1_760_000_000L);
        http.perform(get("/api/channels/" + own.id() + "/feed").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("workspace"))
                .andExpect(jsonPath("$.posts[0].text").value("Hello from the private room"))
                .andExpect(jsonPath("$.posts[0].views").doesNotExist());
    }
}
