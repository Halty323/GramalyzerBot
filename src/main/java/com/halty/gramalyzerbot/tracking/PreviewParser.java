package com.halty.gramalyzerbot.tracking;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Telegram's public web pages for a channel: the message preview at {@code t.me/s/<username>} (title, counters
 * and the latest ~20 posts with their views and reactions), or the plain {@code t.me/<username>} page, which is all
 * Telegram shows for usernames that aren't channels.
 * <p>
 * Pure: no network access, so it can be tested against saved pages.
 */
public final class PreviewParser {

    private static final Pattern BACKGROUND = Pattern.compile("background-image:\\s*url\\(['\"]?([^'\")]+)['\"]?\\)");
    private static final Pattern NUMBER = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*([KkMm]?)");
    /** Where Telegram puts an album item, e.g. "left:303px;top:0px;width:150px;height:149px". */
    private static final Pattern PX = Pattern.compile("(?<![\\w-])(left|top|width|height)\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)px");
    /** A picture's shape, e.g. "padding-top:75%" for 4:3. */
    private static final Pattern PADDING = Pattern.compile("padding-top\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)%");

    private PreviewParser() {}

    /**
     * What the public pages say about a channel. Counts are as shown by Telegram, e.g. "12.3K" becomes 12300.
     *
     * @param seen the message ids of every post on the page, even one that couldn't be read: a stored post in their
     *             range that isn't among them was deleted
     */
    public record Preview(String username, String title, String description, String photoUrl, Integer subscribers,
                          boolean verified, Map<String, Integer> counters, List<PreviewPost> posts, List<Integer> seen) {}

    public record PreviewPost(int messageId, Instant postedAt, String kind, String text, Integer views, int reactions,
                              String reactionBreakdown, String mediaUrl, boolean hasLink, String forwardedFrom, boolean edited,
                              List<FeedPost.Media> media) {}

    /** What a username turned out to be when there is no channel preview. */
    public enum PageKind { CHANNEL, GROUP, OTHER, MISSING }

    /** Parses {@code t.me/s/<username>}; empty when the page is not a channel preview (Telegram redirects those). */
    public static Optional<Preview> preview(String html, String username) {
        Document doc = Jsoup.parse(html, "https://t.me/");
        Element info = doc.selectFirst(".tgme_channel_info");
        if (info == null) return Optional.empty();

        String title = text(info.selectFirst(".tgme_channel_info_header_title"));
        if (title.isBlank()) title = username;
        boolean verified = info.selectFirst(".tgme_channel_info_header_title .verified-icon") != null;
        Element img = info.selectFirst(".tgme_page_photo_image img");
        String photo = img == null ? null : absolute(img.attr("src"));
        String description = multiline(info.selectFirst(".tgme_channel_info_description"));

        Map<String, Integer> counters = new LinkedHashMap<>();
        for (Element c : info.select(".tgme_channel_info_counter")) {
            String type = text(c.selectFirst(".counter_type")).toLowerCase(Locale.ROOT);
            Integer value = count(text(c.selectFirst(".counter_value")));
            if (value == null || type.isBlank()) continue;
            counters.put(singular(type), value);
        }
        Integer subscribers = counters.getOrDefault("subscriber", counters.get("member"));

        List<PreviewPost> posts = new ArrayList<>();
        List<Integer> seen = new ArrayList<>();
        for (Element m : doc.select(".tgme_widget_message[data-post]")) {
            id(m).ifPresent(seen::add);
            post(m).ifPresent(posts::add);
        }
        return Optional.of(new Preview(username, title, description.isBlank() ? null : description, photo, subscribers,
                verified, counters, posts, seen));
    }

    /** Classifies the plain {@code t.me/<username>} page, for usernames without a channel preview. */
    public static PageKind page(String html) {
        Document doc = Jsoup.parse(html, "https://t.me/");
        if (doc.selectFirst(".tgme_page_title") == null) return PageKind.MISSING;
        String extra = text(doc.selectFirst(".tgme_page_extra")).toLowerCase(Locale.ROOT);
        if (extra.contains("subscriber")) return PageKind.CHANNEL;
        if (extra.contains("member") || extra.contains("online")) return PageKind.GROUP;
        return PageKind.OTHER;
    }

    /** The subscriber count on the plain page ("12 345 subscribers"), for channels whose preview is unavailable. */
    public static Optional<Preview> fallback(String html, String username) {
        Document doc = Jsoup.parse(html, "https://t.me/");
        if (page(html) != PageKind.CHANNEL) return Optional.empty();
        String title = text(doc.selectFirst(".tgme_page_title"));
        boolean verified = doc.selectFirst(".tgme_page_title .verified-icon") != null;
        Element img = doc.selectFirst(".tgme_page_photo_image");
        String photo = img == null ? null : absolute(img.attr("src"));
        String description = multiline(doc.selectFirst(".tgme_page_description"));
        String digits = text(doc.selectFirst(".tgme_page_extra")).replaceAll("[^0-9]", "");
        Integer subscribers = digits.isEmpty() || digits.length() > 9 ? null : Integer.parseInt(digits);
        return Optional.of(new Preview(username, title.isBlank() ? username : title, description.isBlank() ? null : description,
                photo, subscribers, verified, Map.of(), List.of(), List.of()));
    }

    // ------------------------------------------------------------------ posts

    /** The message id in {@code data-post="channel/123"}. */
    private static Optional<Integer> id(Element m) {
        String ref = m.attr("data-post");
        int slash = ref.lastIndexOf('/');
        if (slash < 0) return Optional.empty();
        try {
            return Optional.of(Integer.parseInt(ref.substring(slash + 1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<PreviewPost> post(Element m) {
        Optional<Integer> ref = id(m);
        if (ref.isEmpty()) return Optional.empty();
        int id = ref.get();
        Element time = m.selectFirst(".tgme_widget_message_date time[datetime]");
        if (time == null) return Optional.empty();
        Instant at;
        try {
            at = OffsetDateTime.parse(time.attr("datetime")).toInstant();
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        // the post's own text, not the quoted text of a post it replies to
        Element body = null;
        for (Element t : m.select(".tgme_widget_message_text")) {
            if (t.closest(".tgme_widget_message_reply") == null) {
                body = t;
                break;
            }
        }
        String text = multiline(body);
        boolean link = m.selectFirst(".tgme_widget_message_link_preview") != null;
        if (!link && body != null) {
            for (Element a : body.select("a[href]")) {
                String href = a.attr("href");
                if (href.startsWith("http") && !href.startsWith("https://t.me/") && !href.contains("?q=%23")) {
                    link = true;
                    break;
                }
            }
        }
        Integer views = count(text(m.selectFirst(".tgme_widget_message_views")));

        int total = 0;
        StringBuilder breakdown = new StringBuilder();
        for (Element r : m.select(".tgme_widget_message_reactions .tgme_reaction")) {
            Integer n = count(r.ownText());
            if (n == null) continue;
            String emoji = r.hasClass("tgme_reaction_paid") ? "⭐" : emojiOf(r);
            total += n;
            if (breakdown.length() + emoji.length() + 12 > 500) continue;
            if (!breakdown.isEmpty()) breakdown.append(',');
            breakdown.append(emoji).append(':').append(n);
        }

        Element meta = m.selectFirst(".tgme_widget_message_meta");
        boolean edited = meta != null && meta.ownText().toLowerCase(Locale.ROOT).contains("edited");
        String forwarded = text(m.selectFirst(".tgme_widget_message_forwarded_from_name"));

        return Optional.of(new PreviewPost(id, at, kind(m), text, views, total, breakdown.isEmpty() ? null : breakdown.toString(),
                media(m), link, forwarded.isBlank() ? null : forwarded, edited, items(m)));
    }

    /** Same names as {@link com.halty.gramalyzerbot.analytics.PostKind}. */
    static String kind(Element m) {
        if (m.selectFirst(".tgme_widget_message_grouped_wrap") != null) return "ALBUM";
        if (m.selectFirst(".tgme_widget_message_roundvideo_player") != null) return "VIDEO_NOTE";
        if (m.selectFirst(".tgme_widget_message_video_player") != null) {
            Element d = m.selectFirst(".message_video_duration");
            return d != null && d.text().equalsIgnoreCase("gif") ? "ANIMATION" : "VIDEO";
        }
        if (m.selectFirst(".tgme_widget_message_photo_wrap") != null) return "PHOTO";
        if (m.selectFirst(".tgme_widget_message_voice_player") != null) return "VOICE";
        if (m.selectFirst(".tgme_widget_message_audio_player, .tgme_widget_message_document_wrap.audio") != null) return "AUDIO";
        if (m.selectFirst(".tgme_widget_message_document") != null) return "DOCUMENT";
        if (m.selectFirst(".tgme_widget_message_poll") != null) return "POLL";
        if (m.selectFirst(".tgme_widget_message_sticker_wrap") != null) return "STICKER";
        return "TEXT";
    }

    /** The first picture of a post: photo, video thumbnail or link preview image. */
    private static String media(Element m) {
        for (String selector : new String[]{".tgme_widget_message_photo_wrap", ".tgme_widget_message_video_thumb",
                ".tgme_widget_message_roundvideo_thumb", ".link_preview_image", ".link_preview_right_image"}) {
            Element e = m.selectFirst(selector);
            if (e == null) continue;
            Matcher bg = BACKGROUND.matcher(e.attr("style"));
            if (bg.find()) return absolute(bg.group(1));
        }
        Element sticker = m.selectFirst(".tgme_widget_message_sticker[data-webp]");
        return sticker == null ? null : absolute(sticker.attr("data-webp"));
    }

    /**
     * The photos and videos of a post. An album's items come with the places Telegram gives them in its mosaic, in
     * pixels on the page and here relative to the album's width; a single photo or video comes with its shape.
     */
    static List<FeedPost.Media> items(Element m) {
        Element group = m.selectFirst(".tgme_widget_message_grouped_wrap");
        if (group != null) {
            Elements wraps = group.select(".grouped_media_wrap");
            if (wraps.isEmpty()) wraps = group.select(".tgme_widget_message_photo_wrap, .tgme_widget_message_video_player");
            Map<String, Double> layer = px(group.selectFirst(".tgme_widget_message_grouped_layer"));
            double width = layer.getOrDefault("width", px(group).getOrDefault("width", 0.0));
            List<FeedPost.Media> out = new ArrayList<>();
            boolean placed = width > 0;
            for (Element w : wraps) {
                String url = picture(w);
                if (url == null) continue;
                Map<String, Double> at = px(w);
                placed &= at.size() == 4;
                out.add(new FeedPost.Media(url, video(w), rel(at.get("left"), width), rel(at.get("top"), width),
                        rel(at.get("width"), width), rel(at.get("height"), width)));
            }
            // a layout is only worth something if it covers every item
            if (!placed) out = out.stream().map(i -> new FeedPost.Media(i.url(), i.video(), null, null, null, null)).toList();
            return out;
        }
        Element single = m.selectFirst(".tgme_widget_message_photo_wrap, .tgme_widget_message_video_player");
        if (single == null || single.closest(".tgme_widget_message_link_preview") != null) return List.of();
        String url = picture(single);
        if (url == null) return List.of();
        Double h = null;
        for (Element e : single.select("[style*=padding-top]")) {
            Matcher shape = PADDING.matcher(e.attr("style"));
            if (shape.find()) {
                h = rel(Double.parseDouble(shape.group(1)), 100);
                break;
            }
        }
        return List.of(h == null ? new FeedPost.Media(url, video(single), null, null, null, null)
                : new FeedPost.Media(url, video(single), 0.0, 0.0, 1.0, h));
    }

    /** The picture of an album item or single photo: its own background, or a video's thumbnail inside it. */
    private static String picture(Element e) {
        for (Element candidate : e.select("[style*=background-image]")) {
            Matcher bg = BACKGROUND.matcher(candidate.attr("style"));
            if (bg.find()) return absolute(bg.group(1));
        }
        return null;
    }

    private static boolean video(Element e) {
        return e.hasClass("tgme_widget_message_video_player") || e.selectFirst(".tgme_widget_message_video_thumb, video") != null;
    }

    private static Map<String, Double> px(Element e) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (e == null) return out;
        Matcher px = PX.matcher(e.attr("style"));
        while (px.find()) out.putIfAbsent(px.group(1), Double.parseDouble(px.group(2)));
        return out;
    }

    private static Double rel(Double value, double of) {
        return value == null || of <= 0 ? null : Math.round(value / of * 10000) / 10000.0;
    }

    private static String emojiOf(Element r) {
        Element b = r.selectFirst("i.emoji b, b");
        if (b != null && !b.text().isBlank()) return b.text().trim();
        Element i = r.selectFirst("i.emoji");
        if (i != null && !i.text().isBlank()) return i.text().trim();
        return "❤";
    }

    // ------------------------------------------------------------------ text helpers

    /** "12.3K" → 12300, "1,5M" → 1500000, "582" → 582; null when there is no number. */
    static Integer count(String raw) {
        if (raw == null) return null;
        Matcher n = NUMBER.matcher(raw.replace("\u00a0", " ").trim());
        if (!n.find()) return null;
        double value;
        try {
            value = Double.parseDouble(n.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
        String unit = n.group(2).toUpperCase(Locale.ROOT);
        if (unit.equals("K")) value *= 1_000;
        else if (unit.equals("M")) value *= 1_000_000;
        return (int) Math.min(Integer.MAX_VALUE, Math.round(value));
    }

    private static String singular(String type) {
        String t = type.trim();
        return t.endsWith("s") ? t.substring(0, t.length() - 1) : t;
    }

    private static String text(Element e) { return e == null ? "" : e.text().trim(); }

    /** Text with line breaks kept (Telegram renders them as {@code <br>}). */
    private static String multiline(Element e) {
        if (e == null) return "";
        Element copy = e.clone();
        for (Element br : copy.select("br")) br.replaceWith(new TextNode("\n"));
        Elements blocks = copy.select("p, div");
        for (Element b : blocks) b.appendChild(new TextNode("\n"));
        return copy.wholeText().replace("\u00a0", " ").replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
    }

    private static String absolute(String url) {
        if (url == null || url.isBlank()) return null;
        if (url.startsWith("//")) return "https:" + url;
        return url.startsWith("http") ? url : null;
    }
}
