package com.halty.gramalyzerbot.tracking;

import com.halty.gramalyzerbot.tracking.PreviewParser.Preview;
import com.halty.gramalyzerbot.tracking.PreviewParser.PreviewPost;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

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
        assertThat(photo.media()).containsExactly(new FeedPost.Media("https://cdn4.telesco.pe/file/photo102.jpg", false, 0.0, 0.0, 1.0, 0.5625));
        assertThat(photo.forwardedFrom()).isEqualTo("Other Channel");
        assertThat(photo.edited()).isTrue();
        assertThat(photo.views()).isEqualTo(982);
        assertThat(photo.reactions()).isZero();

        PreviewPost video = p.posts().get(2);
        assertThat(video.kind()).isEqualTo("VIDEO");
        assertThat(video.text()).as("the quoted reply is not the post's text").isEqualTo("Watch this 🎬");
        assertThat(video.mediaUrl()).isEqualTo("https://cdn4.telesco.pe/file/thumb103.jpg");
        assertThat(video.media()).containsExactly(new FeedPost.Media("https://cdn4.telesco.pe/file/thumb103.jpg", true, null, null, null, null));
        assertThat(text.media()).isEmpty();
        assertThat(video.views()).isEqualTo(1_050_000);
    }

    @Test
    void albumsKeepEveryPictureInTelegramsLayout() throws IOException {
        Preview p = PreviewParser.preview(fixture("preview-album.html"), "albumchannel").orElseThrow();
        assertThat(p.seen()).containsExactly(120, 123);

        PreviewPost album = p.posts().get(0);
        assertThat(album.kind()).isEqualTo("ALBUM");
        assertThat(album.text()).isEqualTo("Three from the trip");
        assertThat(album.mediaUrl()).isEqualTo("https://cdn4.telesco.pe/file/cat.jpg");
        // one wide picture on top, two below it, the second a video; places relative to the album's 453px width
        assertThat(album.media()).containsExactly(
                new FeedPost.Media("https://cdn4.telesco.pe/file/cat.jpg", false, 0.0, 0.0, 1.0, 0.6667),
                new FeedPost.Media("https://cdn4.telesco.pe/file/sponge.jpg", false, 0.0, 0.6711, 0.4967, 0.4437),
                new FeedPost.Media("https://cdn4.telesco.pe/file/kitten-thumb.jpg", true, 0.5011, 0.6711, 0.4989, 0.4437));

        // an item without a place: none of them get one, so the viewer lays them out itself
        assertThat(p.posts().get(1).media()).extracting(FeedPost.Media::url)
                .containsExactly("https://cdn4.telesco.pe/file/a.jpg", "https://cdn4.telesco.pe/file/b.jpg");
        assertThat(p.posts().get(1).media()).allSatisfy(m -> assertThat(m.x()).isNull());
    }

    @Test
    void picturesAreStoredAsJsonAndSurviveOldRows() {
        List<FeedPost.Media> media = List.of(new FeedPost.Media("https://cdn4.telesco.pe/file/a.jpg", false, 0.0, 0.0, 0.5, 0.5),
                new FeedPost.Media("https://cdn4.telesco.pe/file/b.jpg", true, null, null, null, null));
        assertThat(FeedPost.media(FeedPost.media(media))).isEqualTo(media);
        assertThat(FeedPost.media((String) null)).isEmpty();
        assertThat(FeedPost.media("not json")).isEmpty();
        assertThat(FeedPost.media(List.of())).isNull();
        // ten pictures with very long addresses: the ones that fit are kept, in order
        List<FeedPost.Media> huge = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> new FeedPost.Media("https://cdn4.telesco.pe/file/" + "x".repeat(1500) + i + ".jpg", false, null, null, null, null)).toList();
        List<FeedPost.Media> kept = FeedPost.media(FeedPost.media(huge));
        assertThat(kept).hasSizeBetween(1, 9).isEqualTo(huge.subList(0, kept.size()));
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
