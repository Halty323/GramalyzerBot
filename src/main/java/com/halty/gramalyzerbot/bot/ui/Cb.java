package com.halty.gramalyzerbot.bot.ui;

import java.util.Arrays;

/** Callback-data protocol: parts joined with ':' (e.g. {@code nav:times:12:90}). */
public final class Cb {

    private Cb() {}

    public static String of(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    public record Args(String[] parts) {
        public String get(int i) { return i < parts.length ? parts[i] : ""; }

        public long getLong(int i) { return Long.parseLong(get(i)); }

        public int getInt(int i) { return Integer.parseInt(get(i)); }

        public int size() { return parts.length; }

        /** Everything from index i onwards, re-joined (for values that may themselves contain ':'). */
        public String rest(int i) { return String.join(":", Arrays.copyOfRange(parts, Math.min(i, parts.length), parts.length)); }
    }

    public static Args parse(String data) { return new Args(data == null ? new String[0] : data.split(":")); }
}
