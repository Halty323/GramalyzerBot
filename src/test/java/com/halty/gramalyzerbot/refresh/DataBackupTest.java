package com.halty.gramalyzerbot.refresh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

class DataBackupTest {

    @Test
    void copiesTheBuiltInDatabaseAndKeepsTheNewestFew(@TempDir Path dir) throws Exception {
        String url = "jdbc:h2:file:" + dir.resolve("db/gramalyzer") + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        jdbc.execute("CREATE TABLE notes (body VARCHAR(20))");
        jdbc.update("INSERT INTO notes VALUES ('keep me')");
        Path backups = dir.resolve("backups");
        // stale ones from earlier days
        Files.createDirectories(backups);
        Files.writeString(backups.resolve("gramalyzer-20260101-000000-daily.zip"), "old");
        Files.writeString(backups.resolve("gramalyzer-20260102-000000-daily.zip"), "old");

        DataBackup backup = new DataBackup(jdbc, url, true, backups.toString(), 2);
        Path file = backup.backup("stop");

        assertThat(file).exists();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            assertThat(zip.stream().map(e -> e.getName())).anyMatch(n -> n.endsWith(".mv.db"));
        }
        assertThat(names(backups)).containsExactlyInAnyOrder("gramalyzer-20260102-000000-daily.zip", file.getFileName().toString());
    }

    @Test
    void leavesPostgresAlone() {
        DataBackup backup = new DataBackup(null, "jdbc:postgresql://db:5432/gramalyzer", true, "unused", 7);
        backup.stop(); // nothing to do, and no NullPointerException from the missing JdbcTemplate
    }

    private static Stream<String> names(Path dir) throws IOException {
        return Files.list(dir).map(p -> p.getFileName().toString()).toList().stream();
    }
}
