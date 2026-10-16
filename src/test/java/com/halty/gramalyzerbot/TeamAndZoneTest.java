package com.halty.gramalyzerbot;

import com.halty.gramalyzerbot.channel.Permission;
import com.halty.gramalyzerbot.channel.Role;
import com.halty.gramalyzerbot.channel.TeamRules;
import com.halty.gramalyzerbot.channel.TimeZones;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TeamAndZoneTest {

    @Test
    void rolesAndPermissions() {
        assertTrue(Role.MODERATOR.can(Permission.VIEW));
        assertTrue(Role.MODERATOR.can(Permission.MODERATE_POSTS));
        assertFalse(Role.MODERATOR.can(Permission.CHANGE_SETTINGS));
        assertTrue(Role.ADMIN.can(Permission.IMPORT_DATA));
        assertFalse(Role.ADMIN.can(Permission.MANAGE_ADMINS));
        assertFalse(Role.ADMIN.can(Permission.DISCONNECT));
        for (Permission p : Permission.values()) assertTrue(Role.OWNER.can(p));
    }

    @Test
    void teamRules() {
        assertTrue(TeamRules.canInvite(Role.ADMIN, Role.MODERATOR));
        assertFalse(TeamRules.canInvite(Role.ADMIN, Role.ADMIN));
        assertTrue(TeamRules.canInvite(Role.OWNER, Role.ADMIN));
        assertFalse(TeamRules.canInvite(Role.OWNER, Role.OWNER));
        assertFalse(TeamRules.canInvite(Role.MODERATOR, Role.MODERATOR));

        assertTrue(TeamRules.canRemove(Role.OWNER, Role.ADMIN));
        assertTrue(TeamRules.canRemove(Role.ADMIN, Role.MODERATOR));
        assertFalse(TeamRules.canRemove(Role.ADMIN, Role.ADMIN));
        assertFalse(TeamRules.canRemove(Role.ADMIN, Role.OWNER));
        assertFalse(TeamRules.canRemove(Role.MODERATOR, Role.MODERATOR));

        assertTrue(TeamRules.canChangeRole(Role.OWNER, Role.MODERATOR, Role.ADMIN));
        assertTrue(TeamRules.canChangeRole(Role.OWNER, Role.ADMIN, Role.MODERATOR));
        assertFalse(TeamRules.canChangeRole(Role.ADMIN, Role.MODERATOR, Role.ADMIN));
        assertFalse(TeamRules.canChangeRole(Role.OWNER, Role.MODERATOR, Role.OWNER));
        assertFalse(TeamRules.canChangeRole(Role.OWNER, Role.OWNER, Role.ADMIN));
    }

    @Test
    void resolvesTimeZones() {
        assertEquals(List.of(ZoneId.of("Europe/Berlin")), TimeZones.resolve("berlin"));
        assertEquals(List.of(ZoneId.of("Europe/Berlin")), TimeZones.resolve("  Berlin "));
        assertEquals(List.of(ZoneId.of("America/New_York")), TimeZones.resolve("New York"));
        assertEquals(List.of(ZoneId.of("Europe/Paris")), TimeZones.resolve("Europe/Paris"));
        assertEquals(List.of(ZoneId.of("Europe/Kyiv")), TimeZones.resolve("Kiev"));
        assertEquals(List.of(ZoneId.of("UTC")), TimeZones.resolve("UTC"));
        assertEquals("+02:00", TimeZones.resolve("+2").get(0).getId());
        assertEquals("-05:30", TimeZones.resolve("UTC-5:30").get(0).getId());
        assertTrue(TimeZones.resolve("+25").isEmpty());
        assertTrue(TimeZones.resolve("definitely not a place").isEmpty());
        assertTrue(TimeZones.resolve("").isEmpty());
        assertTrue(TimeZones.POPULAR.stream().allMatch(id -> ZoneId.getAvailableZoneIds().contains(id)));
    }

    @Test
    void labelsZones() {
        Instant summer = Instant.parse("2026-07-01T12:00:00Z");
        assertEquals("Europe/Berlin (UTC+02:00)", TimeZones.label(ZoneId.of("Europe/Berlin"), summer));
        assertEquals("UTC", TimeZones.label(ZoneId.of("UTC"), summer));
        assertEquals("America/New York (UTC-04:00)", TimeZones.label(ZoneId.of("America/New_York"), summer));
        assertEquals(ZoneId.of("UTC"), TimeZones.parseOrUtc("garbage/zone"));
    }
}
