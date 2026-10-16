package com.halty.gramalyzerbot.bot;

import com.halty.gramalyzerbot.analytics.PostKind;
import com.halty.gramalyzerbot.analytics.TextFeatures;
import com.halty.gramalyzerbot.bot.event.Events;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberUpdated;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.messageorigin.MessageOriginChannel;
import org.telegram.telegrambots.meta.api.objects.reactions.ReactionTypeEmoji;

import java.time.Instant;
import java.util.Optional;

/**
 * Translates Telegram's {@link Update} into the small {@link Events} the bot understands. This is the
 * only place (besides {@link BotApi}) that knows the shape of the Telegram library's types.
 */
@Component
public class UpdateMapper {

    public Optional<Object> map(Update u) {
        if (u.getCallbackQuery() != null) return Optional.ofNullable(callback(u.getCallbackQuery()));
        if (u.getMessage() != null) return Optional.ofNullable(message(u.getMessage()));
        if (u.getChannelPost() != null) return Optional.of(channelPost(u.getChannelPost(), false));
        if (u.getEditedChannelPost() != null) return Optional.of(channelPost(u.getEditedChannelPost(), true));
        if (u.getMyChatMember() != null) return Optional.ofNullable(membership(u.getMyChatMember()));
        if (u.getMessageReactionCount() != null) {
            var rc = u.getMessageReactionCount();
            int total = 0;
            StringBuilder sb = new StringBuilder();
            for (var r : rc.getReactions()) {
                int c = r.getTotalCount();
                total += c;
                String label = r.getType() instanceof ReactionTypeEmoji e ? e.getEmoji() : "⭐";
                if (sb.length() > 0) sb.append(',');
                sb.append(label).append(':').append(c);
            }
            String breakdown = sb.length() > 500 ? sb.substring(0, 500) : sb.toString();
            return Optional.of(new Events.Reactions(rc.getChat().getId(), rc.getMessageId(), total, breakdown));
        }
        return Optional.empty();
    }

    private Object callback(CallbackQuery q) {
        var msg = q.getMessage();
        if (msg == null || q.getData() == null) return null;
        var from = q.getFrom();
        return new Events.Callback(q.getId(), from.getId(), from.getFirstName(), from.getUserName(), from.getLanguageCode(),
                msg.getChatId(), msg.getMessageId(), q.getData());
    }

    private Object message(Message m) {
        String type = m.getChat().getType();
        if ("private".equals(type)) {
            var from = m.getFrom();
            if (from == null || Boolean.TRUE.equals(from.getIsBot())) return null;
            String text = m.getText() != null ? m.getText() : m.getCaption();
            Events.DocumentInfo doc = null;
            if (m.getDocument() != null) {
                var d = m.getDocument();
                doc = new Events.DocumentInfo(d.getFileId(), d.getFileName(), d.getFileSize() == null ? 0 : d.getFileSize());
            }
            Events.Forward fwd = null;
            if (m.getForwardOrigin() instanceof MessageOriginChannel oc) {
                var c = oc.getChat();
                fwd = new Events.Forward(c.getId(), oc.getMessageId(), c.getTitle(), c.getUserName());
            }
            return new Events.PrivateMessage(from.getId(), from.getFirstName(), from.getUserName(), from.getLanguageCode(),
                    m.getChatId(), m.getMessageId(), text, doc, fwd);
        }
        if ("group".equals(type) || "supergroup".equals(type)) {
            Long originChat = null;
            Integer originMsg = null;
            if (m.getForwardOrigin() instanceof MessageOriginChannel oc) {
                originChat = oc.getChat().getId();
                originMsg = oc.getMessageId();
            }
            Integer replyTo = m.getReplyToMessage() == null ? null : m.getReplyToMessage().getMessageId();
            return new Events.GroupMessage(m.getChatId(), m.getMessageId(), m.getMessageThreadId(), replyTo,
                    Boolean.TRUE.equals(m.getIsAutomaticForward()), originChat, originMsg);
        }
        return null;
    }

    private Events.ChannelPost channelPost(Message m, boolean edited) {
        String text = m.getText() != null ? m.getText() : (m.getCaption() != null ? m.getCaption() : "");
        boolean link = TextFeatures.hasLink(text);
        var entities = m.getEntities() != null ? m.getEntities() : m.getCaptionEntities();
        if (!link && entities != null) {
            for (var en : entities) {
                if ("url".equals(en.getType()) || "text_link".equals(en.getType())) { link = true; break; }
            }
        }
        return new Events.ChannelPost(m.getChatId(), m.getMessageId(), Instant.ofEpochSecond(m.getDate()), edited,
                kindOf(m), text, link, m.getMediaGroupId());
    }

    private static PostKind kindOf(Message m) {
        if (m.getPoll() != null) return PostKind.POLL;
        if (m.getSticker() != null) return PostKind.STICKER;
        if (m.getAnimation() != null) return PostKind.ANIMATION;
        if (m.getVideoNote() != null) return PostKind.VIDEO_NOTE;
        if (m.getVideo() != null) return PostKind.VIDEO;
        if (m.getPhoto() != null && !m.getPhoto().isEmpty()) return PostKind.PHOTO;
        if (m.getVoice() != null) return PostKind.VOICE;
        if (m.getAudio() != null) return PostKind.AUDIO;
        if (m.getDocument() != null) return PostKind.DOCUMENT;
        if (m.getText() != null) return PostKind.TEXT;
        return PostKind.OTHER;
    }

    private Object membership(ChatMemberUpdated u) {
        var chat = u.getChat();
        var by = u.getFrom();
        return new Events.BotMembership(chat.getId(), chat.getType(), chat.getTitle(), chat.getUserName(), by.getId(),
                by.getFirstName(), by.getUserName(), u.getNewChatMember().getStatus());
    }
}
