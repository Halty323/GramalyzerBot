package com.halty.gramalyzerbot.workspace;

import java.time.Instant;

/**
 * Things the rest of the application (the analytics bot) may want to know about the CRM workspace.
 * They travel over Spring's event bus, so the {@code workspace} package never depends on the analytics code.
 */
public final class WorkspaceEvents {
    private WorkspaceEvents() {}

    /** The CRM changed a channel's timezone. */
    public record TimezoneChanged(String channelId, Long telegramChatId, String timezone) {}

    /** The posting worker delivered a queued post to Telegram. {@code text} is the post body without the signature. */
    public record PostPublished(String channelId, long telegramChatId, long messageId, String text, Instant publishedAt) {}
}
