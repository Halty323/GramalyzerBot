package com.halty.gramalyzerbot.post;

import com.halty.gramalyzerbot.analytics.PostKind;
import com.halty.gramalyzerbot.analytics.TextFeatures;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reads the {@code result.json} that Telegram Desktop produces for "Export chat history" and turns
 * every ordinary message into an {@link ExportedPost}. Fields the export doesn't carry are simply left
 * at their defaults.
 */
public final class ExportParser {

    public record Header(Long id, String name, String type) {}

    public record ExportedPost(int messageId, Instant date, PostKind kind, String text, boolean hasLink,
                               int reactions, String breakdown, String mediaGroupId) {}

    private ExportParser() {}

    public static Header parse(InputStream in, Consumer<ExportedPost> sink) throws IOException {
        MiniJson j = new MiniJson(new InputStreamReader(in, StandardCharsets.UTF_8));
        Long id = null;
        String name = null, type = null;
        j.expect('{');
        State state = new State();
        if (!j.consumeIf('}')) {
            do {
                String key = j.readString();
                j.expect(':');
                if (key.equals("messages")) {
                    j.expect('[');
                    if (!j.consumeIf(']')) {
                        do {
                            Object m = j.readValue();
                            if (m instanceof Map<?, ?> map) {
                                ExportedPost p = toPost(map, state);
                                if (p != null) sink.accept(p);
                            }
                        } while (j.consumeIf(','));
                        j.expect(']');
                    }
                } else {
                    Object v = j.readValue();
                    if (key.equals("id") && v instanceof Long l) id = l;
                    if (key.equals("name") && v instanceof String s) name = s;
                    if (key.equals("type") && v instanceof String s) type = s;
                }
            } while (j.consumeIf(','));
            j.expect('}');
        }
        return new Header(id, name, type);
    }

    /** Remembers the previous media message so consecutive media posted in the same second become one album. */
    private static final class State {
        Instant lastMediaDate;
        String lastGroup;
    }

    static ExportedPost toPost(Map<?, ?> m, State st) {
        if (!"message".equals(m.get("type"))) return null;
        if (!(m.get("id") instanceof Long idL) || idL > Integer.MAX_VALUE) return null;
        Instant date = parseDate(m);
        if (date == null) return null;

        StringBuilder text = new StringBuilder();
        boolean link = false;
        if (m.get("text_entities") instanceof List<?> ents) {
            for (Object o : ents) {
                if (o instanceof Map<?, ?> e) {
                    if (e.get("text") instanceof String s) text.append(s);
                    Object t = e.get("type");
                    if ("link".equals(t) || "text_link".equals(t)) link = true;
                }
            }
        } else if (m.get("text") instanceof String s) {
            text.append(s);
        }
        String body = text.toString();
        link = link || TextFeatures.hasLink(body);

        PostKind kind = kindOf(m);
        if (kind == PostKind.TEXT && body.isBlank()) return null;

        int reactions = 0;
        StringBuilder breakdown = new StringBuilder();
        if (m.get("reactions") instanceof List<?> rs) {
            for (Object o : rs) {
                if (!(o instanceof Map<?, ?> r) || !(r.get("count") instanceof Long c)) continue;
                reactions += c.intValue();
                Object emoji = r.get("emoji");
                if (breakdown.length() > 0) breakdown.append(',');
                breakdown.append(emoji instanceof String e ? e : "⭐").append(':').append(c);
            }
        }

        String group = null;
        if (isMedia(kind)) {
            if (date.equals(st.lastMediaDate) && st.lastGroup != null) group = st.lastGroup;
            else group = "x" + idL;
            st.lastMediaDate = date;
            st.lastGroup = group;
        } else {
            st.lastMediaDate = null;
            st.lastGroup = null;
        }
        return new ExportedPost(idL.intValue(), date, kind, body, link, reactions, breakdown.toString(), group);
    }

    private static boolean isMedia(PostKind k) {
        return k == PostKind.PHOTO || k == PostKind.VIDEO || k == PostKind.DOCUMENT || k == PostKind.AUDIO;
    }

    private static Instant parseDate(Map<?, ?> m) {
        Object u = m.get("date_unixtime");
        try {
            if (u instanceof String s) return Instant.ofEpochSecond(Long.parseLong(s.trim()));
            if (u instanceof Long l) return Instant.ofEpochSecond(l);
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return null;
    }

    private static PostKind kindOf(Map<?, ?> m) {
        if (m.containsKey("poll")) return PostKind.POLL;
        if (m.containsKey("photo")) return PostKind.PHOTO;
        Object mt = m.get("media_type");
        if (mt instanceof String s) {
            return switch (s) {
                case "video_file" -> PostKind.VIDEO;
                case "animation" -> PostKind.ANIMATION;
                case "voice_message" -> PostKind.VOICE;
                case "audio_file" -> PostKind.AUDIO;
                case "sticker" -> PostKind.STICKER;
                case "video_message" -> PostKind.VIDEO_NOTE;
                default -> PostKind.OTHER;
            };
        }
        if (m.containsKey("file")) return PostKind.DOCUMENT;
        if (m.containsKey("location_information") || m.containsKey("contact_information")) return PostKind.OTHER;
        return PostKind.TEXT;
    }
}
