package com.halty.gramalyzerbot.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static com.halty.gramalyzerbot.workspace.Models.*;

@Repository
public class WorkspaceStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public WorkspaceStore(JdbcTemplate jdbc, ObjectMapper mapper) {this.jdbc = jdbc; this.mapper = mapper;}
    private <T> T read(String data, Class<T> type) {try {return mapper.readValue(data, type);} catch(Exception e) {throw new IllegalStateException("Stored workspace data is invalid", e);}}
    private String json(Object value) {try {return mapper.writeValueAsString(value);} catch(Exception e) {throw new IllegalStateException(e);}}
    public List<Channel> channels() {return jdbc.query("SELECT data FROM workspace_channels ORDER BY created_at, id", (rs, row) -> read(rs.getString(1), Channel.class));}
    public Optional<Channel> channel(String id) {return channels().stream().filter(c -> c.id().equals(id)).findFirst();}
    public Optional<Channel> telegramChannel(long chatId) {return channels().stream().filter(c -> Objects.equals(c.telegramChatId(), chatId)).findFirst();}
    private Post mapPost(ResultSet rs, int row) throws SQLException {
        Post p = read(rs.getString("data"), Post.class);
        return new Post(p.id(), p.channelId(), p.title(), p.body(), rs.getString("status"), p.scheduledAt(), p.views(), p.reactions(), p.shares(), p.category(), rs.getString("delivery_state"), rs.getObject("telegram_message_id", Long.class), rs.getString("delivery_error"));
    }
    public List<Post> posts() {return jdbc.query("SELECT * FROM workspace_posts ORDER BY created_at DESC, id", this::mapPost);}
    public Optional<Post> post(String id) {return jdbc.query("SELECT * FROM workspace_posts WHERE id=?", this::mapPost, id).stream().findFirst();}
    public Channel saveChannel(Channel channel) {
        int updated = jdbc.update("UPDATE workspace_channels SET telegram_chat_id=?, data=? WHERE id=?", channel.telegramChatId(), json(channel), channel.id());
        if (updated == 0) jdbc.update("INSERT INTO workspace_channels(id, telegram_chat_id, data) VALUES (?, ?, ?)", channel.id(), channel.telegramChatId(), json(channel));
        return channel;
    }
    public Post insert(Post post) {jdbc.update("INSERT INTO workspace_posts(id,channel_id,data,status,scheduled_at,delivery_state,telegram_message_id) VALUES (?,?,?,?,?,?,?)", post.id(), post.channelId(), json(post), post.status(), OffsetDateTime.ofInstant(post.scheduledAt(), ZoneOffset.UTC), post.deliveryState(), post.telegramMessageId()); return post;}
    public Post update(Post post) {
        int n = jdbc.update("UPDATE workspace_posts SET channel_id=?,data=?,status=?,scheduled_at=?,delivery_state='pending',delivery_error=NULL WHERE id=? AND delivery_state NOT IN ('sending','uncertain','sent')", post.channelId(), json(post), post.status(), OffsetDateTime.ofInstant(post.scheduledAt(), ZoneOffset.UTC), post.id());
        if (n == 0) throw new WorkspaceService.Problem(409, "This post is already being delivered or needs a delivery review.");
        return post;
    }
    public boolean delete(String id) {return jdbc.update("DELETE FROM workspace_posts WHERE id=? AND status<>'published' AND delivery_state NOT IN ('sending','uncertain','sent')", id) > 0;}
    public List<Post> due() {return jdbc.query("SELECT * FROM workspace_posts WHERE status='scheduled' AND delivery_state='pending' AND scheduled_at<=CURRENT_TIMESTAMP ORDER BY scheduled_at", this::mapPost);}
    public boolean claim(String id) {return jdbc.update("UPDATE workspace_posts SET delivery_state='sending' WHERE id=? AND status='scheduled' AND delivery_state='pending' AND scheduled_at<=CURRENT_TIMESTAMP", id) == 1;}
    @Transactional
    public void delivered(String id, long messageId) {
        Post p=post(id).orElseThrow(); Channel c=channel(p.channelId()).orElseThrow();
        jdbc.update("DELETE FROM workspace_posts WHERE id=?", "telegram:"+c.telegramChatId()+":"+messageId);
        jdbc.update("UPDATE workspace_posts SET status='published',delivery_state='sent',telegram_message_id=?,delivery_error=NULL WHERE id=? AND delivery_state='sending'", messageId, id);
    }
    public boolean hasReceipt(String channelId,long messageId) {return !jdbc.queryForList("SELECT id FROM workspace_posts WHERE channel_id=? AND telegram_message_id=?",String.class,channelId,messageId).isEmpty();}
    public void deliveryFailed(String id, boolean uncertain, String error) {jdbc.update("UPDATE workspace_posts SET delivery_state=?,delivery_error=? WHERE id=? AND delivery_state='sending'", uncertain ? "uncertain" : "failed", error, id);}
    public void recoverInterrupted() {jdbc.update("UPDATE workspace_posts SET delivery_state='uncertain',delivery_error='Delivery was interrupted. Check Telegram before creating a replacement.' WHERE delivery_state='sending'");}
    /** Telegram's reaction total for a channel message, copied onto the CRM post that was published as that message. */
    public int recordReactions(String channelId, long messageId, long reactions) {
        int n = 0;
        for (Post p : jdbc.query("SELECT * FROM workspace_posts WHERE channel_id=? AND telegram_message_id=?", this::mapPost, channelId, messageId)) {
            ObjectNode data = mapper.valueToTree(p); data.put("reactions", reactions);
            n += jdbc.update("UPDATE workspace_posts SET data=? WHERE id=?", json(data), p.id());
        }
        return n;
    }
    public List<History> history() {return jdbc.query("SELECT channel_id,metric_date,subscribers FROM channel_daily_metrics ORDER BY metric_date", (rs, row) -> new History(rs.getString(1), rs.getDate(2).toLocalDate().toString(), rs.getLong(3)));}
    @Transactional
    public void recordSubscribers(String id, long count) {
        Channel c = channel(id).orElseThrow();
        ObjectNode data = mapper.valueToTree(c); data.put("subscribers", count);
        saveChannel(mapper.convertValue(data, Channel.class));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (jdbc.update("UPDATE channel_daily_metrics SET subscribers=? WHERE channel_id=? AND metric_date=?", count, id, today) == 0) jdbc.update("INSERT INTO channel_daily_metrics VALUES (?,?,?)", id, today, count);
    }
}
