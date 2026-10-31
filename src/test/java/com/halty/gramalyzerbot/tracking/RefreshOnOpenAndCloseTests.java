package com.halty.gramalyzerbot.tracking;

import com.halty.gramalyzerbot.refresh.ChannelRefresh;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every watched channel is read again when the server or an app opens or closes; one that was just read is not, and a
 * shutdown that runs out of time leaves the rest for the next start.
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:refresh;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "gramalyzer.demo=false", "gramalyzer.api-token=test-only-workspace-token", "telegram.bot.enabled=false",
        "gramalyzer.refresh.pause=PT0S", "gramalyzer.refresh.cooldown=PT0S"})
@AutoConfigureMockMvc
class RefreshOnOpenAndCloseTests {

    static final String AUTH = "Bearer test-only-workspace-token";

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired TrackingService tracking;
    @Autowired ChannelRefresh refresh;
    @MockitoBean PreviewClient client;

    FakeTelegram telegram;

    @BeforeEach
    void reset() throws IOException {
        for (String table : new String[]{"tracked_watchers", "tracked_snapshots", "tracked_posts", "tracked_channels"})
            jdbc.update("DELETE FROM " + table);
        telegram = new FakeTelegram();
        when(client.preview(anyString(), any())).thenAnswer(inv -> telegram.preview(inv.getArgument(0), inv.getArgument(1)));
        for (String name : new String[]{"first_watch", "second_watch", "third_watch"}) {
            telegram.channel(name, name).burst(5, "Post");
            tracking.track("tg:1", name);
        }
    }

    @Test
    void everyStaleChannelIsReadAgainAndFreshOnesAreNot() {
        age("first_watch", Duration.ofHours(3));
        age("second_watch", Duration.ofMinutes(30));
        telegram.channel("first_watch", "first_watch").burst(6, "New");
        int third = telegram.requests("third_watch");

        ChannelRefresh.Run run = refresh.runNow("test", Instant.MAX);

        assertThat(run.channels()).isEqualTo(3);
        assertThat(run.refreshed()).isEqualTo(3);
        assertThat(run.failed()).isZero();
        assertThat(telegram.requests("third_watch")).isEqualTo(third); // read a moment ago
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tracked_posts p JOIN tracked_channels c ON c.id = p.tracked_channel_id "
                + "WHERE c.username = 'first_watch'", Integer.class)).isEqualTo(6);
        assertThat(fetchedAt("second_watch")).isAfter(Instant.now().minusSeconds(30));

        // closing right after opening reads nothing again
        int before = telegram.requests("first_watch") + telegram.requests("second_watch");
        refresh.runNow("again", Instant.MAX);
        assertThat(telegram.requests("first_watch") + telegram.requests("second_watch")).isEqualTo(before);
    }

    @Test
    void aShutdownOutOfTimeLeavesTheRestForNextStart() {
        for (String name : new String[]{"first_watch", "second_watch", "third_watch"}) age(name, Duration.ofHours(1));
        ChannelRefresh.Run run = refresh.runNow("stop", Instant.now().minusSeconds(1));
        assertThat(run.left()).isEqualTo(3);
        assertThat(run.refreshed()).isZero();
    }

    @Test
    void aBrokenChannelDoesNotStopTheOthers() throws IOException {
        age("first_watch", Duration.ofHours(2));
        age("second_watch", Duration.ofHours(1));
        when(client.preview(anyString(), any())).thenAnswer(inv -> {
            if ("first_watch".equals(inv.getArgument(0))) throw new IOException("connection reset");
            return telegram.preview(inv.getArgument(0), inv.getArgument(1));
        });
        ChannelRefresh.Run run = refresh.runNow("test", Instant.MAX);
        assertThat(run.failed()).isEqualTo(1);
        assertThat(run.refreshed()).isEqualTo(2);
        assertThat(fetchedAt("second_watch")).isAfter(Instant.now().minusSeconds(30));
    }

    @Test
    void appsAskForARefreshWhenTheyOpenOrClose() throws Exception {
        age("second_watch", Duration.ofHours(1));
        http.perform(post("/api/refresh?reason=studio-open")).andExpect(status().isUnauthorized());
        http.perform(post("/api/refresh?reason=studio-open").header("Authorization", AUTH)).andExpect(status().isAccepted());
        for (int i = 0; i < 100 && (refresh.status().last() == null || !"studio-open".equals(refresh.status().last().reason())); i++)
            Thread.sleep(100);
        http.perform(get("/api/refresh").header("Authorization", AUTH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.last.reason").value("studio-open"))
                .andExpect(jsonPath("$.last.channels").value(3));
        assertThat(fetchedAt("second_watch")).isAfter(Instant.now().minusSeconds(30));
    }

    private void age(String username, Duration by) {
        jdbc.update("UPDATE tracked_channels SET fetched_at = ? WHERE username = ?", Timestamp.from(Instant.now().minus(by)), username);
    }

    private Instant fetchedAt(String username) {
        return jdbc.queryForObject("SELECT fetched_at FROM tracked_channels WHERE username = ?", Timestamp.class, username).toInstant();
    }
}
