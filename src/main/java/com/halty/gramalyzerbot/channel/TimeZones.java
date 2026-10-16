package com.halty.gramalyzerbot.channel;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns whatever a person types ("berlin", "New York", "+2", "Europe/Paris") into time zones. */
public final class TimeZones {

    private static final Pattern OFFSET = Pattern.compile("^(?:utc|gmt)?\\s*([+-])\\s*(\\d{1,2})(?::?(\\d{2}))?$");
    private static final List<String> REGIONS = List.of("Europe/", "America/", "Asia/", "Africa/", "Australia/", "Pacific/", "Atlantic/", "Indian/");
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("germany", "Europe/Berlin"), Map.entry("deutschland", "Europe/Berlin"),
            Map.entry("uk", "Europe/London"), Map.entry("england", "Europe/London"),
            Map.entry("france", "Europe/Paris"), Map.entry("spain", "Europe/Madrid"),
            Map.entry("italy", "Europe/Rome"), Map.entry("poland", "Europe/Warsaw"),
            Map.entry("ukraine", "Europe/Kyiv"), Map.entry("kiev", "Europe/Kyiv"),
            Map.entry("russia", "Europe/Moscow"), Map.entry("turkey", "Europe/Istanbul"),
            Map.entry("india", "Asia/Kolkata"), Map.entry("japan", "Asia/Tokyo"));

    public static final List<String> POPULAR = List.of("Europe/Berlin", "Europe/London", "Europe/Kyiv", "Europe/Moscow",
            "America/New_York", "America/Los_Angeles", "Asia/Dubai", "Asia/Kolkata", "Asia/Tokyo", "UTC");

    private TimeZones() {}

    public static List<ZoneId> resolve(String input) {
        if (input == null) return List.of();
        String q = input.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty() || q.length() > 64) return List.of();
        if (q.equals("utc") || q.equals("gmt") || q.equals("z")) return List.of(ZoneId.of("UTC"));

        Matcher m = OFFSET.matcher(q);
        if (m.matches()) {
            int h = Integer.parseInt(m.group(2));
            int min = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            if (h > 18 || min > 59) return List.of();
            int sign = m.group(1).equals("-") ? -1 : 1;
            try {
                return List.of(ZoneOffset.ofHoursMinutes(sign * h, sign * min));
            } catch (DateTimeException e) {
                return List.of();
            }
        }
        if (ALIASES.containsKey(q)) return List.of(ZoneId.of(ALIASES.get(q)));

        String norm = normalise(q);
        List<ZoneId> exact = new ArrayList<>(), partial = new ArrayList<>();
        for (String id : ZoneId.getAvailableZoneIds()) {
            if (id.startsWith("Etc/") || id.startsWith("SystemV/") || id.startsWith("US/") || !id.contains("/")) continue;
            String full = normalise(id);
            String city = normalise(id.substring(id.lastIndexOf('/') + 1));
            if (full.equals(norm) || city.equals(norm)) exact.add(ZoneId.of(id));
            else if (city.contains(norm) || full.contains(norm)) partial.add(ZoneId.of(id));
        }
        List<ZoneId> pool = exact.isEmpty() ? partial : exact;
        pool.sort(Comparator.comparing((ZoneId z) -> REGIONS.stream().noneMatch(r -> z.getId().startsWith(r)))
                .thenComparing(ZoneId::getId));
        return pool.size() > 6 ? new ArrayList<>(pool.subList(0, 6)) : pool;
    }

    private static String normalise(String s) { return s.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').trim(); }

    public static ZoneId parseOrUtc(String id) {
        try {
            return ZoneId.of(id);
        } catch (DateTimeException | NullPointerException e) {
            return ZoneId.of("UTC");
        }
    }

    /** "Europe/Berlin (UTC+02:00)" */
    public static String label(ZoneId zone, Instant at) {
        if (zone.getId().equals("UTC")) return "UTC";
        ZoneOffset off = zone.getRules().getOffset(at);
        String o = off.getTotalSeconds() == 0 ? "UTC" : "UTC" + off.getId();
        String name = zone.getId().replace('_', ' ');
        return zone instanceof ZoneOffset ? o : name + " (" + o + ")";
    }

    public static String cityName(ZoneId zone) {
        String id = zone.getId();
        return id.contains("/") ? id.substring(id.lastIndexOf('/') + 1).replace('_', ' ') : zone.getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }
}
