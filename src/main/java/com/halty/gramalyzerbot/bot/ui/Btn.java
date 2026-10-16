package com.halty.gramalyzerbot.bot.ui;

import java.nio.charset.StandardCharsets;

/** An inline button: either a callback button (data) or a link button (url). */
public record Btn(String text, String data, String url) {

    public static Btn cb(String text, String data) {
        if (data.getBytes(StandardCharsets.UTF_8).length > 64)
            throw new IllegalArgumentException("callback data over 64 bytes: " + data);
        return new Btn(text, data, null);
    }

    public static Btn link(String text, String url) { return new Btn(text, null, url); }

    public boolean isLink() { return url != null; }
}
