package com.halty.gramalyzerbot.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static com.halty.gramalyzerbot.workspace.Models.*;

@Service
public class WorkspaceService {
    public static class Problem extends RuntimeException {public final int status; public Problem(int status, String message) {super(message); this.status=status;}}
    private final WorkspaceStore store; private final ObjectMapper mapper; private final boolean demo; private final boolean botEnabled;
    public WorkspaceService(WorkspaceStore store, ObjectMapper mapper, @Value("${gramalyzer.demo:false}") boolean demo, @Value("${telegram.bot.enabled:false}") boolean botEnabled) {this.store=store; this.mapper=mapper; this.demo=demo; this.botEnabled=botEnabled&&!demo;}
    public Workspace workspace() {return new Workspace(store.channels(), store.posts(), demo, store.history(), botEnabled);}
    public Channel channel(String id) {return store.channel(id).orElseThrow(() -> new Problem(404,"Channel not found."));}
    public Post post(String id) {return store.post(id).orElseThrow(() -> new Problem(404,"Post not found."));}
    public synchronized Channel settings(String id, ObjectNode input) {
        Channel c = channel(id);
        Set<String> allowed = Set.of("paused", "timezone", "notifications", "autoDelete", "signature");
        input.fieldNames().forEachRemaining(key -> {if (!allowed.contains(key)) throw new Problem(400,"Unknown channel setting: " + key);});
        for (String key : List.of("paused","notifications","autoDelete")) if (input.has(key) && !input.get(key).isBoolean()) throw new Problem(400,"Expected a boolean for " + key);
        if (input.has("timezone")) {try {if (!input.get("timezone").isTextual()) throw new Exception(); ZoneId.of(input.get("timezone").asText());} catch(Exception e) {throw new Problem(400,"Invalid timezone.");}}
        if (input.has("signature") && (!input.get("signature").isTextual() || input.get("signature").asText().length()>200)) throw new Problem(400,"Signature must be at most 200 characters.");
        ObjectNode data = mapper.valueToTree(c); data.setAll(input);
        return store.saveChannel(mapper.convertValue(data, Channel.class));
    }
    public Post save(String id, PostInput input) {
        Channel c = channel(input.channelId());
        if ("scheduled".equals(input.status()) && !input.scheduledAt().isAfter(Instant.now())) throw new Problem(400,"Choose a future date for a scheduled post.");
        if (input.body().length() + (c.signature().isBlank() ? 0 : c.signature().length() + 2) > 4096) throw new Problem(400,"Message and channel signature together must fit in 4096 characters.");
        Post previous = id == null ? null : post(id);
        if (previous != null && "published".equals(previous.status())) throw new Problem(409,"Published posts cannot be changed here.");
        Post p = new Post(id == null ? UUID.randomUUID().toString() : id, input.channelId(), input.title().trim(), input.body().trim(), input.status(), input.scheduledAt(), 0, 0, 0, input.category() == null ? "Community" : input.category(), "pending", null, null);
        return id == null ? store.insert(p) : store.update(p);
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
    public void channelPost(long chatId, int messageId, String text, long timestamp) {
        Channel c = store.telegramChannel(chatId).orElse(null); if (c == null) return;
        String id = "telegram:"+chatId+":"+messageId; if (store.post(id).isPresent() || store.hasReceipt(c.id(),messageId)) return;
        String body = text == null ? "Media post" : text;
        try {store.insert(new Post(id, c.id(), body.substring(0, Math.min(100, body.length())), body, "published", Instant.ofEpochSecond(timestamp), 0, 0, 0, "Telegram", "sent", (long) messageId, null));}
        catch(org.springframework.dao.DuplicateKeyException ignored) {/* A delivery receipt or repeated update already recorded the post. */}
    }
}
