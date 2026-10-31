package com.halty.gramalyzerbot.refresh;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Copies the built-in (H2 file) database to a zip once a day and once more on every shutdown, right after the last
 * channel refresh, keeping the newest few. A copy taken while the server runs is consistent, so a broken disk or a bad
 * update costs at most a day. PostgreSQL setups back up with pg_dump instead; this does nothing there.
 * Restore: stop the server, unzip the newest backup into the data folder (it holds gramalyzer.mv.db), start it again.
 */
@Component
@Slf4j
public class DataBackup implements SmartLifecycle {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final Path dir;
    private final int keep;
    private volatile boolean running;

    public DataBackup(JdbcTemplate jdbc, @Value("${spring.datasource.url:}") String url,
                      @Value("${gramalyzer.backup.enabled:true}") boolean enabled,
                      @Value("${gramalyzer.backup.dir:./.data/backups}") String dir,
                      @Value("${gramalyzer.backup.keep:7}") int keep) {
        this.jdbc = jdbc;
        this.enabled = enabled && url.startsWith("jdbc:h2:file:");
        this.dir = Path.of(dir);
        this.keep = Math.max(1, keep);
    }

    @Scheduled(fixedDelayString = "${gramalyzer.backup.every:PT24H}", initialDelayString = "PT15M")
    public void daily() {
        if (enabled) backup("daily");
    }

    /** Writes one backup and drops the oldest beyond {@code keep}. Returns the file, or null when it failed. */
    public Path backup(String why) {
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("gramalyzer-" + LocalDateTime.now().format(STAMP) + "-" + why + ".zip").toAbsolutePath();
            jdbc.execute("BACKUP TO '" + file.toString().replace("'", "''") + "'");
            prune();
            log.info("Database backed up to {}", file);
            return file;
        } catch (IOException | RuntimeException e) {
            log.warn("Database backup failed: {}", e.toString());
            return null;
        }
    }

    private void prune() throws IOException {
        List<Path> all;
        try (Stream<Path> files = Files.list(dir)) {
            all = files.filter(f -> f.getFileName().toString().matches("gramalyzer-\\d{8}-\\d{6}-[a-z]+\\.zip"))
                    .sorted(Comparator.comparing((Path f) -> f.getFileName().toString().substring(11, 26)).reversed()).toList();
        }
        for (Path old : all.subList(Math.min(keep, all.size()), all.size())) Files.deleteIfExists(old);
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (enabled) backup("stop");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Just after {@link ChannelRefresh}, so the copy holds what the last refresh read. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }
}
