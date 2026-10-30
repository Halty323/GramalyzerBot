package com.halty.gramalyzerbot.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static com.halty.gramalyzerbot.workspace.Models.*;

@Service
public class WorkspaceService {
    public static class Problem extends RuntimeException {public final int status; public Problem(int status, String message) {super(message); this.status=status;}}
    private final WorkspaceStore store; private final WorkspaceMedia files; private final ObjectMapper mapper; private final ApplicationEventPublisher events; private final boolean demo; private final boolean botEnabled;
    public WorkspaceService(WorkspaceStore store, WorkspaceMedia files, ObjectMapper mapper, ApplicationEventPublisher events, @Value("${gramalyzer.demo:false}") boolean demo, @Value("${telegram.bot.enabled:false}") boolean botEnabled) {this.store=store; this.files=files; this.mapper=mapper; this.events=events; this.demo=demo; this.botEnabled=botEnabled&&!demo;}
    public Workspace workspace() {return new Workspace(store.channels(), store.posts(), demo, store.history(), botEnabled);}
    public Channel channel(String id) {return store.channel(id).orElseThrow(() -> new Problem(404,"Channel not found."));}
    public Optional<Channel> byTelegramChat(long chatId) {return store.telegramChannel(chatId);}
    public Post post(String id) {return store.post(id).orElseThrow(() -> new Problem(404,"Post not found."));}
    public synchronized Channel settings(String id, ObjectNode input) {
        Channel c = channel(id);
        Set<String> allowed = Set.of("paused", "timezone", "notifications", "autoDelete", "signature");
        input.fieldNames().forEachRemaining(key -> {if (!allowed.contains(key)) throw new Problem(400,"Unknown channel setting: " + key);});
        for (String key : List.of("paused","notifications","autoDelete")) if (input.has(key) && !input.get(key).isBoolean()) throw new Problem(400,"Expected a boolean for " + key);
        if (input.has("timezone")) {try {if (!input.get("timezone").isTextual()) throw new Exception(); ZoneId.of(input.get("timezone").asText());} catch(Exception e) {throw new Problem(400,"Invalid timezone.");}}
        if (input.has("signature") && (!input.get("signature").isTextual() || input.get("signature").asText().length()>200)) throw new Problem(400,"Signature must be at most 200 characters.");
        ObjectNode data = mapper.valueToTree(c); data.setAll(input);
        Channel saved = store.saveChannel(mapper.convertValue(data, Channel.class));
        if (!saved.timezone().equals(c.timezone())) events.publishEvent(new WorkspaceEvents.TimezoneChanged(saved.id(), saved.telegramChatId(), saved.timezone()));
        return saved;
    }
    public Post save(String id, PostInput input) {
        Channel c = channel(input.channelId());
        if ("scheduled".equals(input.status()) && !input.scheduledAt().isAfter(Instant.now())) throw new Problem(400,"Choose a future date for a scheduled post.");
        Post previous = id == null ? null : post(id);
        if (previous != null && "published".equals(previous.status())) throw new Problem(409,"Published posts cannot be changed here.");
        List<MediaRef> media = input.media() != null ? checkedMedia(input.media()) : previous == null ? List.of() : previous.media();
        List<LinkButton> buttons = input.buttons() != null ? checkedButtons(input.buttons()) : previous == null ? List.of() : previous.buttons();
        boolean silent = input.silent() != null ? input.silent() : previous != null && previous.silent();
        boolean pin = input.pin() != null ? input.pin() : previous != null && previous.pin();
        List<Integer> repostOf = input.repostOf() != null ? input.repostOf() : previous == null ? List.of() : previous.repostOf();
        String body = input.body().trim();
        if (!repostOf.isEmpty() && (!media.isEmpty() || !buttons.isEmpty())) throw new Problem(400,"A repost is sent exactly as it was posted, so it can't get new pictures or buttons.");
        if (body.isEmpty() && media.isEmpty() && repostOf.isEmpty()) throw new Problem(400,"Write a message or add a picture or video.");
        int length = body.length() + (c.signature().isBlank() ? 0 : c.signature().length() + 2);
        if (repostOf.isEmpty() && media.isEmpty() && length > 4096) throw new Problem(400,"Message and channel signature together must fit in 4096 characters.");
        if (!media.isEmpty() && length > 1024) throw new Problem(400,"With pictures or a video, Telegram allows captions of up to 1024 characters, signature included.");
        if (media.size() > 1 && !buttons.isEmpty()) throw new Problem(400,"Telegram can't put buttons under an album. Keep one picture or video, or remove the buttons.");
        if (media.size() > 1 && media.stream().anyMatch(m -> "animation".equals(m.kind()))) throw new Problem(400,"A GIF can't be part of an album. Post it on its own.");
        Post p = new Post(id == null ? UUID.randomUUID().toString() : id, input.channelId(), input.title().trim(), body, input.status(), input.scheduledAt(), 0, 0, 0, input.category() == null ? "Community" : input.category(), "pending", null, null, media, buttons, silent, pin, repostOf);
        return id == null ? store.insert(p) : store.update(p);
    }
    /** Uploaded files as stored (their kind and name come from the upload, not from what the client says). */
    private List<MediaRef> checkedMedia(List<MediaRef> media) {
        if (media.size() > 10) throw new Problem(400,"A post can have up to 10 pictures or videos.");
        List<MediaRef> out = new ArrayList<>();
        for (MediaRef m : media) out.add(files.ref(m.id()).orElseThrow(() -> new Problem(400,"An attached file is no longer available. Add it again.")));
        if (out.stream().map(MediaRef::id).distinct().count() < out.size()) throw new Problem(400,"The same file is attached twice.");
        return out;
    }
    private static List<LinkButton> checkedButtons(List<LinkButton> buttons) {
        if (buttons.size() > 8) throw new Problem(400,"A post can have up to 8 buttons.");
        List<LinkButton> out = new ArrayList<>();
        for (LinkButton b : buttons) {
            String text = b.text().strip(), url = b.url().strip();
            if (text.isEmpty() || text.length() > 40) throw new Problem(400,"Button labels need 1 to 40 characters.");
            if (!url.matches("(?i)(https?://|tg://)[^\\s]+")) throw new Problem(400,"Button links must start with https:// or tg://.");
            out.add(new LinkButton(text, url));
        }
        return out;
    }
    public void delete(String id) {Post p = post(id); if ("published".equals(p.status()) || !store.delete(id)) throw new Problem(409,"This post is published or its delivery needs a review.");}
    public synchronized Channel registerChannel(long chatId, String title, String username, boolean canPost) {
        Channel previous = store.telegramChannel(chatId).orElse(null);
        Channel c = new Channel(previous == null ? UUID.randomUUID().toString() : previous.id(), title,
            username == null ? "" : "@" + username, title.substring(0, Math.min(2, title.length())), "purple",
            previous == null ? 0 : previous.subscribers(), 0, 0, 0, "Connected through GramalyzerBot",
            !canPost || previous != null && previous.paused(), previous == null ? "Europe/Berlin" : previous.timezone(),
            previous == null || previous.notifications(), previous != null && previous.autoDelete(), previous == null ? "" : previous.signature(), chatId);
        return store.saveChannel(c);
    }
    /** Latest member count from Telegram, for a channel the CRM knows. */
    public void recordSubscribers(long chatId, long count) {store.telegramChannel(chatId).ifPresent(c -> store.recordSubscribers(c.id(), count));}
    /** Latest reaction total of one channel message. */
    public void recordReactions(long chatId, long messageId, long reactions) {store.telegramChannel(chatId).ifPresent(c -> store.recordReactions(c.id(), messageId, reactions));}
    public void channelPost(long chatId, int messageId, String text, long timestamp) {channelPost(chatId, messageId, text, timestamp, null, false);}
    /**
     * A post that appeared in (or was edited in) a channel the CRM knows. An album arrives as one message per picture, and
     * only one of them carries the caption: it is kept as one post, under the album's id, with whichever caption it has.
     * An edit changes the text of the post it belongs to; one the CRM never saw is left alone.
     */
    public void channelPost(long chatId, int messageId, String text, long timestamp, String album, boolean edited) {
        Channel c = store.telegramChannel(chatId).orElse(null); if (c == null) return;
        String caption = text == null || text.isBlank() ? null : text;
        String id = album == null ? "telegram:"+chatId+":"+messageId : "telegram:"+chatId+":album:"+album;
        Optional<Post> known = store.post(id);
        if (known.isEmpty() && album == null) known = store.byTelegramMessage(c.id(), messageId).stream().findFirst();
        if (known.isPresent()) {
            Post p = known.get();
            boolean ours = p.id().startsWith("telegram:");
            // an album item without a caption says nothing new; an edit, or the caption arriving with a later item, does
            if (caption == null ? !edited || album != null : !edited && !MEDIA.equals(p.body())) return;
            String body = caption == null ? MEDIA : caption;
            if (body.equals(p.body())) return;
            store.replace(p.withText(ours ? title(body) : p.title(), body));
            return;
        }
        if (edited || store.hasReceipt(c.id(),messageId)) return;
        String body = caption == null ? MEDIA : caption;
        try {store.insert(new Post(id, c.id(), title(body), body, "published", Instant.ofEpochSecond(timestamp), 0, 0, 0, "Telegram", "sent", (long) messageId, null));}
        catch(org.springframework.dao.DuplicateKeyException ignored) {/* A delivery receipt or repeated update already recorded the post. */}
    }
    /** What a post without text (a picture, a video) is called in the post list. */
    private static final String MEDIA = "Media post";
    private static String title(String body) {return body.substring(0, Math.min(100, body.length()));}
}
