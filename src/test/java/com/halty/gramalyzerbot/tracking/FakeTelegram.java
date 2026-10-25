package com.halty.gramalyzerbot.tracking;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Imaginary public channels, and the {@code t.me/s/<username>} pages Telegram would show for them: about 20 posts a
 * page, oldest first, {@code ?before=} for older ones, albums as one post under their first message id, deleted posts
 * gone, edited ones marked. Tests publish, edit and delete posts over time and see what the app makes of it.
 */
final class FakeTelegram {

    static final int PAGE = 20;

    private final Map<String, Channel> channels = new HashMap<>();
    private final Map<String, Integer> requests = new HashMap<>();

    Channel channel(String username, String title) {
        Channel c = new Channel(username, title);
        channels.put(username, c);
        return c;
    }

    /** How many pages of a channel were downloaded so far. */
    int requests(String username) { return requests.getOrDefault(username, 0); }

    PreviewClient.Page preview(String username, Integer before) {
        requests.merge(username, 1, Integer::sum);
        Channel c = channels.get(username);
        if (c == null) return new PreviewClient.Page(404, "https://t.me/s/" + username, "");
        return new PreviewClient.Page(200, "https://t.me/s/" + username + (before == null ? "" : "?before=" + before), c.page(before));
    }

    static final class Post {
        final int id;
        final Instant at;
        final String kind;
        String text;
        int views;
        int reactions;
        boolean edited;
        /** Message ids the post takes: an album takes one per picture. */
        final int size;

        Post(int id, Instant at, String kind, String text, int size) {
            this.id = id;
            this.at = at;
            this.kind = kind;
            this.text = text;
            this.size = size;
        }
    }

    static final class Channel {
        final String username;
        final String title;
        int subscribers = 1200;
        private final TreeMap<Integer, Post> posts = new TreeMap<>();
        private int nextId = 1;
        private Instant clock = Instant.parse("2026-10-01T09:00:00Z");

        Channel(String username, String title) {
            this.username = username;
            this.title = title;
        }

        /** Message ids are shared by everything in a channel, so a later post always has a larger one. */
        private Post add(String kind, String text, int size) {
            clock = clock.plus(37, ChronoUnit.MINUTES);
            Post p = new Post(nextId, clock, kind, text, size);
            p.views = 100 + nextId * 3;
            p.reactions = nextId % 7;
            posts.put(nextId, p);
            nextId += size;
            return p;
        }

        Post text(String text) { return add("TEXT", text, 1); }

        Post photo(String caption) { return add("PHOTO", caption, 1); }

        Post album(int pictures, String caption) { return add("ALBUM", caption, pictures); }

        /** n text posts, "<prefix> 1" … "<prefix> n". */
        List<Post> burst(int n, String prefix) {
            List<Post> out = new ArrayList<>();
            for (int i = 1; i <= n; i++) out.add(text(prefix + " " + i));
            return out;
        }

        void edit(int id, String text) {
            Post p = posts.get(id);
            p.text = text;
            p.edited = true;
        }

        void delete(int id) { posts.remove(id); }

        /** The next message id Telegram will hand out. */
        int nextId() { return nextId; }

        String page(Integer before) {
            List<Post> shown = new ArrayList<>((before == null ? posts : posts.headMap(before, false)).values());
            shown = shown.subList(Math.max(0, shown.size() - PAGE), shown.size());
            StringBuilder html = new StringBuilder("<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body><main class=\"tgme_main\">")
                    .append("<section class=\"tgme_channel_history js-message_history\">");
            for (Post p : shown) html.append(render(p));
            html.append("</section><section class=\"tgme_right_column\"><div class=\"tgme_channel_info\"><div class=\"tgme_channel_info_header\">")
                    .append("<div class=\"tgme_channel_info_header_title\"><span dir=\"auto\">").append(esc(title)).append("</span></div></div>")
                    .append("<div class=\"tgme_channel_info_description\">Imaginary channel for tests</div><div class=\"tgme_channel_info_counters\">")
                    .append("<div class=\"tgme_channel_info_counter\"><span class=\"counter_value\">").append(subscribers)
                    .append("</span> <span class=\"counter_type\">subscribers</span></div></div></div></section></main></body></html>");
            return html.toString();
        }

        private String render(Post p) {
            String link = "https://t.me/" + username + "/" + p.id;
            StringBuilder h = new StringBuilder("<div class=\"tgme_widget_message_wrap js-widget_message_wrap\"><div class=\"tgme_widget_message js-widget_message\" data-post=\"")
                    .append(username).append('/').append(p.id).append("\"><div class=\"tgme_widget_message_bubble\">");
            String picture = "<a class=\"tgme_widget_message_photo_wrap\" href=\"" + link + "\" style=\"width:800px;background-image:url('https://cdn4.telesco.pe/file/"
                    + username + "-" + p.id + ".jpg')\"></a>";
            if (p.kind.equals("PHOTO")) h.append(picture);
            if (p.kind.equals("ALBUM")) h.append("<div class=\"tgme_widget_message_grouped_wrap\">").append(picture).append("</div>");
            if (p.text != null && !p.text.isEmpty())
                h.append("<div class=\"tgme_widget_message_text js-message_text\" dir=\"auto\">").append(esc(p.text).replace("\n", "<br/>")).append("</div>");
            if (p.reactions > 0)
                h.append("<div class=\"tgme_widget_message_reactions\"><span class=\"tgme_reaction\"><i class=\"emoji\"><b>👍</b></i>").append(p.reactions).append("</span></div>");
            h.append("<div class=\"tgme_widget_message_footer compact js-message_footer\"><div class=\"tgme_widget_message_info short js-message_info\">")
                    .append("<span class=\"tgme_widget_message_views\">").append(p.views).append("</span><span class=\"tgme_widget_message_meta\">")
                    .append(p.edited ? "edited " : "").append("<a class=\"tgme_widget_message_date\" href=\"").append(link).append("\"><time datetime=\"")
                    .append(p.at).append("\" class=\"time\">").append(p.at.toString(), 11, 16).append("</time></a></span></div></div></div></div></div>");
            return h.toString();
        }

        private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    }
}
