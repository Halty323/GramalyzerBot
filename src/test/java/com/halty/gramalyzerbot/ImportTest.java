package com.halty.gramalyzerbot;

import com.halty.gramalyzerbot.analytics.PostKind;
import com.halty.gramalyzerbot.analytics.Sample;
import com.halty.gramalyzerbot.post.Albums;
import com.halty.gramalyzerbot.post.ExportParser;
import com.halty.gramalyzerbot.post.ExportParser.ExportedPost;
import com.halty.gramalyzerbot.post.MiniJson;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ImportTest {

    @Test
    void parsesJsonValues() throws IOException {
        MiniJson j = new MiniJson(new StringReader("\uFEFF {\"a\": [1, -2.5, \"x\\u00e9\\n\\ud83d\\ude00\", true, null], \"b\": {}}"));
        Object v = j.readValue();
        Map<?, ?> m = (Map<?, ?>) v;
        List<?> a = (List<?>) m.get("a");
        assertEquals(1L, a.get(0));
        assertEquals(-2.5, (Double) a.get(1), 1e-9);
        assertEquals("xé\n😀", a.get(2));
        assertEquals(Boolean.TRUE, a.get(3));
        assertNull(a.get(4));
        assertTrue(((Map<?, ?>) m.get("b")).isEmpty());
    }

    @Test
    void rejectsBrokenJson() {
        assertThrows(IOException.class, () -> new MiniJson(new StringReader("{\"a\": [1, 2")).readValue());
        assertThrows(IOException.class, () -> new MiniJson(new StringReader("{\"a\" 1}")).readValue());
        assertThrows(IOException.class, () -> new MiniJson(new StringReader("[\"abc")).readValue());
    }

    private static final String EXPORT = """
            {"name":"Tech Digest","type":"public_channel","id":1234567890,"messages":[
              {"id":1,"type":"service","date_unixtime":"1700000000","action":"create_channel"},
              {"id":2,"type":"message","date":"2024-01-01T10:00:00","date_unixtime":"1704103200",
               "text":["Hello ",{"type":"hashtag","text":"#AI"}],
               "text_entities":[{"type":"plain","text":"Hello "},{"type":"hashtag","text":"#AI"},{"type":"link","text":"https://x.io"}],
               "reactions":[{"type":"emoji","count":5,"emoji":"👍"},{"type":"emoji","count":2,"emoji":"🔥"}]},
              {"id":3,"type":"message","date_unixtime":"1704110000","photo":"photos/a.jpg","text":"","text_entities":[]},
              {"id":4,"type":"message","date_unixtime":"1704110000","photo":"photos/b.jpg","text":"caption here","text_entities":[{"type":"plain","text":"caption here"}],
               "reactions":[{"type":"emoji","count":1,"emoji":"❤"}]},
              {"id":5,"type":"message","date_unixtime":"1704120000","media_type":"video_file","file":"v.mp4","text":"","text_entities":[]},
              {"id":6,"type":"message","date_unixtime":"1704130000","text":"","text_entities":[]}
            ]}""";

    @Test
    void parsesTelegramExport() throws IOException {
        List<ExportedPost> posts = new ArrayList<>();
        ExportParser.Header h = ExportParser.parse(new ByteArrayInputStream(EXPORT.getBytes(StandardCharsets.UTF_8)), posts::add);
        assertEquals(1234567890L, h.id().longValue());
        assertEquals("public_channel", h.type());
        assertEquals(4, posts.size()); // service + empty text message dropped
        ExportedPost a = posts.get(0);
        assertEquals(2, a.messageId());
        assertEquals(Instant.ofEpochSecond(1704103200L), a.date());
        assertEquals(PostKind.TEXT, a.kind());
        assertEquals("Hello #AIhttps://x.io", a.text());
        assertTrue(a.hasLink());
        assertEquals(7, a.reactions());
        assertEquals("👍:5,🔥:2", a.breakdown());
        assertEquals(PostKind.PHOTO, posts.get(1).kind());
        assertEquals(PostKind.VIDEO, posts.get(3).kind());
        // same-second photos form one album, the video later does not
        assertEquals(posts.get(1).mediaGroupId(), posts.get(2).mediaGroupId());
        assertFalse(posts.get(2).mediaGroupId().equals(posts.get(3).mediaGroupId()));
    }

    @Test
    void collapsesAlbums() {
        Instant t = Instant.parse("2026-01-01T10:00:00Z");
        List<Sample> in = List.of(
                new Sample(1, 10, t, PostKind.PHOTO, "", false, List.of(), 3, 1, true, false, "g1"),
                new Sample(2, 11, t, PostKind.PHOTO, "cap #a", true, List.of("a"), 4, 0, true, false, "g1"),
                new Sample(3, 12, t.plusSeconds(60), PostKind.PHOTO, "solo", false, List.of(), 9, 0, true, false, "g2"),
                new Sample(4, 13, t.plusSeconds(120), PostKind.TEXT, "txt", false, List.of(), 1, 0, true, false, null));
        List<Sample> out = Albums.collapse(in);
        assertEquals(3, out.size());
        Sample album = out.get(0);
        assertEquals(PostKind.ALBUM, album.kind());
        assertEquals(7, album.reactions());
        assertEquals("cap #a", album.text());
        assertTrue(album.hasLink());
        assertEquals(List.of("a"), album.topics());
        assertEquals(PostKind.PHOTO, out.get(1).kind());
    }
}
