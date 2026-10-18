package com.halty.gramalyzerbot.tracking;

import com.halty.gramalyzerbot.tracking.PreviewParser.Preview;
import com.halty.gramalyzerbot.tracking.PreviewParser.PreviewPost;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewParserTest {

    static String fixture(String name) throws IOException {
        try (InputStream in = PreviewParserTest.class.getResourceAsStream("/tracking/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void readsTheChannelHeaderAndCounters() throws IOException {
        Preview p = PreviewParser.preview(fixture("preview.html"), "samplechannel").orElseThrow();
        assertThat(p.title()).isEqualTo("Sample Channel");
        assertThat(p.verified()).isTrue();
        assertThat(p.photoUrl()).isEqualTo("https://cdn4.telesco.pe/file/avatar.jpg");
        assertThat(p.description()).isEqualTo("The best sample channel.\nSecond line of the bio.");
        assertThat(p.subscribers()).isEqualTo(48_200);
        assertThat(p.counters()).containsEntry("photo", 1100).containsEntry("video", 96).containsEntry("link", 412);
    }

    @Test
    void readsPostsWithViewsReactionsAndMedia() throws IOException {
        Preview p = PreviewParser.preview(fixture("preview.html"), "samplechannel").orElseThrow();
        assertThat(p.posts()).hasSize(3);

        PreviewPost text = p.posts().get(0);
        assertThat(text.messageId()).isEqualTo(101);
        assertThat(text.postedAt()).isEqualTo(Instant.parse("2026-10-10T09:15:00Z"));
        assertThat(text.kind()).isEqualTo("TEXT");
        assertThat(text.text()).isEqualTo("Hello world!\nSecond line with #news and a link");
        assertThat(text.hasLink()).isTrue();
        assertThat(text.views()).isEqualTo(15_600);
        assertThat(text.reactions()).isEqualTo(1200 + 305 + 12);
        assertThat(text.reactionBreakdown()).isEqualTo("👍:1200,🔥:305,⭐:12");
        assertThat(text.edited()).isFalse();

        PreviewPost photo = p.posts().get(1);
        assertThat(photo.kind()).isEqualTo("PHOTO");
        assertThat(photo.mediaUrl()).isEqualTo("https://cdn4.telesco.pe/file/photo102.jpg");
        assertThat(photo.forwardedFrom()).isEqualTo("Other Channel");
        assertThat(photo.edited()).isTrue();
        assertThat(photo.views()).isEqualTo(982);
        assertThat(photo.reactions()).isZero();

        PreviewPost video = p.posts().get(2);
        assertThat(video.kind()).isEqualTo("VIDEO");
        assertThat(video.text()).as("the quoted reply is not the post's text").isEqualTo("Watch this 🎬");
        assertThat(video.mediaUrl()).isEqualTo("https://cdn4.telesco.pe/file/thumb103.jpg");
        assertThat(video.views()).isEqualTo(1_050_000);
    }

    @Test
    void pagesWithoutAPreviewAreClassified() throws IOException {
        assertThat(PreviewParser.preview(fixture("page-user.html"), "someperson")).isEmpty();
        assertThat(PreviewParser.page(fixture("page-channel.html"))).isEqualTo(PreviewParser.PageKind.CHANNEL);
        assertThat(PreviewParser.page(fixture("page-user.html"))).isEqualTo(PreviewParser.PageKind.OTHER);
        assertThat(PreviewParser.page(fixture("page-missing.html"))).isEqualTo(PreviewParser.PageKind.MISSING);

        Preview quiet = PreviewParser.fallback(fixture("page-channel.html"), "quiet").orElseThrow();
        assertThat(quiet.title()).isEqualTo("Quiet Channel");
        assertThat(quiet.subscribers()).isEqualTo(12_345);
        assertThat(quiet.posts()).isEmpty();
    }

    @Test
    void countsAreReadTheWayTelegramWritesThem() {
        assertThat(PreviewParser.count("582")).isEqualTo(582);
        assertThat(PreviewParser.count("12.3K")).isEqualTo(12_300);
        assertThat(PreviewParser.count("1,5M")).isEqualTo(1_500_000);
        assertThat(PreviewParser.count("")).isNull();
    }
}
