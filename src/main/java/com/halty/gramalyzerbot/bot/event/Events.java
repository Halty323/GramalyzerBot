package com.halty.gramalyzerbot.bot.event;

import com.halty.gramalyzerbot.analytics.PostKind;

import java.time.Instant;

/**
 * What the bot cares about in an incoming Telegram update, in plain Java. {@code UpdateMapper} is the
 * only place that translates from the Telegram library's types, so the rest of the code never sees them.
 */
public final class Events {

    private Events() {}

    public record ChannelPost(long chatId, int messageId, Instant date, boolean edited, PostKind kind, String text,
                              boolean hasLink, String mediaGroupId) {}

    /** Reaction totals of a channel post. {@code breakdown} looks like "🔥:12,👍:5". */
    public record Reactions(long chatId, int messageId, int total, String breakdown) {}

    /** A message in a group/supergroup – only interesting as a comment under a channel post. */
    public record GroupMessage(long chatId, int messageId, Integer threadId, Integer replyToId,
                               boolean automaticForward, Long originChatId, Integer originMessageId) {}

    /** The bot's own membership in a chat changed (added/removed), and who did it. */
    public record BotMembership(long chatId, String chatType, String title, String username, long byUserId,
                                String byFirstName, String byUsername, String newStatus) {

        public boolean isNowAdmin() { return "administrator".equals(newStatus); }

        public boolean isGone() { return "left".equals(newStatus) || "kicked".equals(newStatus); }
    }

    public record DocumentInfo(String fileId, String name, long size) {}

    /** A post forwarded from a channel (used to link a channel by forwarding one of its posts). */
    public record Forward(long chatId, int messageId, String title, String username) {}

    public record PrivateMessage(long userId, String firstName, String username, String lang, long chatId,
                                 int messageId, String text, DocumentInfo document, Forward forward) {}

    public record Callback(String id, long userId, String firstName, String username, String lang, long chatId,
                           int messageId, String data) {}
}
