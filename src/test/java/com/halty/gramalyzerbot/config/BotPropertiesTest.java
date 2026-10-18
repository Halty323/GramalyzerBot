package com.halty.gramalyzerbot.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BotPropertiesTest {

    private static BotProperties with(String... admins) {
        return new BotProperties("token", List.of(admins), null, null);
    }

    @Test
    void usernamesAndIdsCanBeMixed() {
        BotProperties p = with("@Halty", " 123456789 ", "https://t.me/other_admin", "plainname");
        assertEquals(Set.of(123456789L), p.adminIds());
        assertEquals(Set.of("halty", "other_admin", "plainname"), p.adminHandles());
    }

    @Test
    void usernamesMatchCaseInsensitivelyAndIdsMatchExactly() {
        BotProperties p = with("@Halty", "123456789");
        assertTrue(p.isAdmin(1L, "halty"));
        assertTrue(p.isAdmin(1L, "HALTY"));
        assertTrue(p.isAdmin(123456789L, null));
        assertFalse(p.isAdmin(2L, "someone_else"));
        assertFalse(p.isAdmin(2L, null));
    }

    @Test
    void nothingConfiguredMeansNoAdmins() {
        BotProperties p = new BotProperties("token", null, null, null);
        assertTrue(p.admins().isEmpty());
        assertFalse(p.isAdmin(123456789L, "halty"));
        assertEquals(List.of(), with("", "  ").admins());
    }

    @Test
    void typosAreRejectedAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> with("@ab"));        // usernames have at least 5 characters
        assertThrows(IllegalArgumentException.class, () -> with("hal ty"));
        assertThrows(IllegalArgumentException.class, () -> with("@"));
    }
}
