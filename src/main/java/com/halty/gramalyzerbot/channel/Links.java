package com.halty.gramalyzerbot.channel;

/** Deep links to channels and their posts. */
public final class Links {

    private static final long CHANNEL_BASE = 1_000_000_000_000L;

    private Links() {}

    /** Public channels link by @username, private ones by internal id; null when no link exists. */
    public static String post(long chatId, String username, boolean demo, int messageId) {
        if (demo) return null;
        if (username != null && !username.isBlank()) return "https://t.me/" + username + "/" + messageId;
        if (chatId < -CHANNEL_BASE) return "https://t.me/c/" + (-chatId - CHANNEL_BASE) + "/" + messageId;
        return null;
    }

    /** A public channel's page; null for a private or sample one. */
    public static String channel(String username, boolean demo) {
        return demo || username == null || username.isBlank() ? null : "https://t.me/" + username;
    }
}
