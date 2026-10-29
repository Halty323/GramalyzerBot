package com.halty.gramalyzerbot.workspace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static com.halty.gramalyzerbot.workspace.Models.*;

/**
 * Pictures, videos and GIFs for queued posts. Telegram takes uploads up to 10 MB for photos and 50 MB for anything else
 * a bot sends, so those are the limits here too.
 */
@Repository
public class WorkspaceMedia {

    public static final long MAX_PHOTO = 10L * 1024 * 1024;
    public static final long MAX_OTHER = 50L * 1024 * 1024;
    private static final Map<String, String> KINDS = Map.of(
            "image/jpeg", "photo", "image/png", "photo", "image/webp", "photo",
            "image/gif", "animation", "video/mp4", "video", "video/quicktime", "video", "video/webm", "video");

    public record File(MediaRef ref, String contentType, byte[] data) {}

    private final JdbcTemplate jdbc;
    private final WorkspaceStore store;

    public WorkspaceMedia(JdbcTemplate jdbc, WorkspaceStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    /** photo, video or animation for a content type we can post; null otherwise. */
    public static String kindOf(String contentType) {
        return contentType == null ? null : KINDS.get(contentType.toLowerCase(Locale.ROOT).split(";")[0].strip());
    }

    public static long limitFor(String kind) { return "photo".equals(kind) ? MAX_PHOTO : MAX_OTHER; }

    public MediaRef save(String contentType, String name, byte[] data) {
        String kind = kindOf(contentType);
        if (kind == null) throw new WorkspaceService.Problem(415, "Upload a JPEG, PNG, WebP or GIF picture, or an MP4 video.");
        if (data.length == 0) throw new WorkspaceService.Problem(400, "The file is empty.");
        if (data.length > limitFor(kind)) throw new WorkspaceService.Problem(413, "photo".equals(kind) ? "Pictures can be up to 10 MB." : "Videos and GIFs can be up to 50 MB.");
        String clean = name == null || name.isBlank() ? kind : name.replaceAll("[\\\\/\\r\\n\"]", "_").strip();
        if (clean.length() > 120) clean = clean.substring(clean.length() - 120);
        String id = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO workspace_media(id,kind,content_type,name,size,data) VALUES (?,?,?,?,?,?)",
                id, kind, contentType.split(";")[0].strip().toLowerCase(Locale.ROOT), clean, data.length, data);
        return new MediaRef(id, kind, clean);
    }

    public Optional<MediaRef> ref(String id) {
        return jdbc.query("SELECT id,kind,name FROM workspace_media WHERE id=?", (rs, row) -> new MediaRef(rs.getString(1), rs.getString(2), rs.getString(3)), id)
                .stream().findFirst();
    }

    public Optional<File> file(String id) {
        return jdbc.query("SELECT id,kind,name,content_type,data FROM workspace_media WHERE id=?",
                (rs, row) -> new File(new MediaRef(rs.getString(1), rs.getString(2), rs.getString(3)), rs.getString(4), rs.getBytes(5)), id).stream().findFirst();
    }

    /** Uploads older than a day that no post uses (the editor was closed without saving, or a post was deleted). */
    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT10M")
    public void removeUnused() {
        Set<String> used = new HashSet<>();
        for (Post p : store.posts()) for (MediaRef m : p.media()) used.add(m.id());
        OffsetDateTime before = OffsetDateTime.ofInstant(Instant.now().minus(Duration.ofDays(1)), ZoneOffset.UTC);
        for (String id : jdbc.queryForList("SELECT id FROM workspace_media WHERE created_at<?", String.class, before))
            if (!used.contains(id)) jdbc.update("DELETE FROM workspace_media WHERE id=?", id);
    }
}
