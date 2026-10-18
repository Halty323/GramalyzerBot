package com.halty.gramalyzerbot.tracking;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrackedStatsTest {

    static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    static TrackedPost post(int id, Duration age, int views, int reactions, String body, String kind) {
        TrackedPost p = new TrackedPost();
        p.setMessageId(id);
        p.setPostedAt(NOW.minus(age));
        p.setViews(views);
        p.setReactions(reactions);
        p.setBody(body);
        p.setKind(kind);
        return p;
    }

    @Test
    void freshPostsDoNotDragDownAverageViews() {
        List<TrackedPost> posts = List.of(
                post(1, Duration.ofDays(3), 1000, 10, "#news one", "TEXT"),
                post(2, Duration.ofDays(2), 3000, 30, "#news two", "PHOTO"),
                post(3, Duration.ofHours(1), 50, 1, "fresh", "TEXT"));
        TrackedStats s = TrackedStats.of(10_000, posts, List.of(), NOW, ZoneOffset.UTC);
        assertThat(s.avgViews()).isEqualTo(2000);
        assertThat(s.er()).isEqualTo(20.0);
        assertThat(s.reactionRate()).isEqualTo(1.0);
        assertThat(s.sample()).isEqualTo(2);
        assertThat(s.lastPost()).isEqualTo(NOW.minus(Duration.ofHours(1)));
        assertThat(s.hashtags()).extracting(TrackedStats.Tag::tag).containsExactly("news");
        assertThat(s.top().get(0).getMessageId()).isEqualTo(2);
        assertThat(s.kinds()).extracting(TrackedStats.Share::kind).containsExactly("TEXT", "PHOTO");
        assertThat(s.postsPerDay()).isEqualTo(1.0);
    }

    @Test
    void growthComesFromSnapshots() {
        List<TrackedSnapshot> history = new ArrayList<>();
        LocalDate today = NOW.atZone(ZoneOffset.UTC).toLocalDate();
        for (int back = 10; back >= 0; back--) {
            TrackedSnapshot s = new TrackedSnapshot(1L, today.minusDays(back));
            s.setSubscribers(1000 + (10 - back) * 10);
            history.add(s);
        }
        TrackedStats s = TrackedStats.of(1100, List.of(), history, NOW, ZoneOffset.UTC);
        assertThat(s.change7()).isEqualTo(70);
        assertThat(s.change30()).as("only 10 days of history").isNull();
        assertThat(s.avgViews()).isNull();
        assertThat(s.postsPerDay()).isNull();
    }

    @Test
    void usernamesAreReadFromWhateverPeoplePaste() {
        assertThat(TrackingService.username("@Durov")).isEqualTo("durov");
        assertThat(TrackingService.username("https://t.me/s/telegram/123")).isEqualTo("telegram");
        assertThat(TrackingService.username("t.me/some_channel")).isEqualTo("some_channel");
        assertThat(TrackingService.username("tg://resolve?domain=news_today")).isEqualTo("news_today");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TrackingService.username("https://t.me/+AbCdEf123"))
                .isInstanceOf(TrackingException.class).extracting(e -> ((TrackingException) e).code()).isEqualTo(TrackingException.Code.PRIVATE_LINK);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TrackingService.username("hi"))
                .isInstanceOf(TrackingException.class).extracting(e -> ((TrackingException) e).code()).isEqualTo(TrackingException.Code.INVALID_USERNAME);
    }
}
