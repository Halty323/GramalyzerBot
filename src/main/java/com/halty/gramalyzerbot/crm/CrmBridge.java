package com.halty.gramalyzerbot.crm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.ChannelService;
import com.halty.gramalyzerbot.post.AnalysisService;
import com.halty.gramalyzerbot.post.PostService;
import com.halty.gramalyzerbot.workspace.Models;
import com.halty.gramalyzerbot.workspace.WorkspaceEvents;
import com.halty.gramalyzerbot.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator;

import java.time.Instant;
import java.util.Optional;

/**
 * The seam between the CRM workspace (queue, settings, history; talks to the website over {@code /api}) and
 * the analytics bot (collects reactions, answers in Telegram DMs).
 * <ul>
 *   <li>Both sides describe the same Telegram channel. They are matched by Telegram chat id and then linked
 *       through {@code channels.workspace_channel_id}.</li>
 *   <li>The CRM learns about channels and their posts from the same Telegram updates as the bot; see {@link #observe}.</li>
 *   <li>Timezone is the one setting both sides expose; changing it on either side changes it on the other.</li>
 *   <li>Posts that the CRM queue publishes are handed to the analysis.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CrmBridge {

    private final WorkspaceService workspace;
    private final ChannelService channels;
    private final PostService posts;
    private final AnalysisService analysis;
    private final BotApi bot;
    private final ObjectMapper mapper;

    // ------------------------------------------------------------------ Telegram → CRM

    /**
     * Keeps the CRM's channel list and post history in step with Telegram: the bot being added to or removed
     * from a channel, and new channel posts. Runs for every update, before the analytics bot handles it.
     */
    public void observe(Update update) {
        if (update.hasMyChatMember()) {
            var change = update.getMyChatMember();
            var chat = change.getChat();
            if (!"channel".equals(chat.getType())) return;
            boolean canPost = change.getNewChatMember() instanceof ChatMemberAdministrator admin
                    && Boolean.TRUE.equals(admin.getCanPostMessages());
            String title = chat.getTitle() == null || chat.getTitle().isBlank() ? "Channel " + chat.getId() : chat.getTitle();
            workspace.registerChannel(chat.getId(), title, chat.getUserName(), canPost);
            countSubscribers(chat.getId());
            channels.byChatId(chat.getId()).ifPresent(this::link);
        } else if (update.getMessageReactionCount() != null) {
            var rc = update.getMessageReactionCount();
            long total = 0;
            if (rc.getReactions() != null) for (var r : rc.getReactions()) total += r.getTotalCount() == null ? 0 : r.getTotalCount();
            workspace.recordReactions(rc.getChat().getId(), rc.getMessageId(), total);
        } else if (update.hasChannelPost()) {
            var post = update.getChannelPost();
            workspace.channelPost(post.getChatId(), post.getMessageId(),
                    post.getText() != null ? post.getText() : post.getCaption(), post.getDate());
        }
    }

    // ------------------------------------------------------------------ linking

    /** A newly seen channel shows its real member count straight away instead of 0 until the hourly refresh. */
    private void countSubscribers(long chatId) {
        bot.memberCount(chatId).ifPresent(n -> workspace.recordSubscribers(chatId, n));
    }

    /** Points the analytics channel at its CRM twin (matched by chat id), if the CRM knows the channel. */
    public Channel link(Channel ch) {
        if (ch.isDemo() || ch.getWorkspaceChannelId() != null) return ch;
        Optional<Models.Channel> twin = workspace.byTelegramChat(ch.getTelegramChatId());
        if (twin.isEmpty()) return ch;
        ch.setWorkspaceChannelId(twin.get().id());
        Channel saved = channels.save(ch);
        if (saved.isTimezoneConfirmed()) pushTimezone(saved); // an explicit choice made in the bot beats the CRM default
        return saved;
    }

    /** A channel connected through the bot also shows up in the CRM, even if the CRM never saw the bot being added. */
    public Channel ensureInCrm(Channel ch) {
        if (ch.isDemo()) return ch;
        try {
            if (workspace.byTelegramChat(ch.getTelegramChatId()).isEmpty()) {
                workspace.registerChannel(ch.getTelegramChatId(), ch.getTitle(), ch.getUsername(), bot.canPostTo(ch.getTelegramChatId()));
                countSubscribers(ch.getTelegramChatId());
            }
            return link(ch);
        } catch (RuntimeException e) {
            // The CRM being unhappy must never stop someone from connecting their channel in Telegram.
            log.warn("Could not register channel {} in the CRM: {}", ch.getId(), e.getMessage());
            return ch;
        }
    }

    /** The analytics channel behind a CRM channel, linking the two on the way if that hasn't happened yet. */
    public Optional<Channel> analyticsFor(Models.Channel crm) {
        Optional<Channel> linked = channels.byWorkspaceId(crm.id());
        if (linked.isPresent()) return linked;
        if (crm.telegramChatId() == null) return Optional.empty();
        return channels.byChatId(crm.telegramChatId()).map(this::link);
    }

    // ------------------------------------------------------------------ settings

    /** Bot → CRM: the channel's owner picked a timezone in Telegram. */
    public void pushTimezone(Channel ch) {
        if (ch.getWorkspaceChannelId() == null) return;
        try {
            workspace.settings(ch.getWorkspaceChannelId(), mapper.createObjectNode().put("timezone", ch.getTimezone()));
        } catch (RuntimeException e) {
            log.warn("Could not copy the timezone of channel {} to the CRM: {}", ch.getId(), e.getMessage());
        }
    }

    /** CRM → bot: someone changed the timezone on the website. */
    @EventListener
    public void onTimezoneChanged(WorkspaceEvents.TimezoneChanged e) {
        try {
            channels.byWorkspaceId(e.channelId()).ifPresent(ch -> {
                if (e.timezone().equals(ch.getTimezone()) && ch.isTimezoneConfirmed()) return;
                ch.setTimezone(e.timezone());
                ch.setTimezoneConfirmed(true);
                channels.save(ch);
                analysis.invalidate(ch.getId());
            });
        } catch (RuntimeException ex) {
            log.warn("Could not copy the timezone of CRM channel {} to the bot: {}", e.channelId(), ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ posts

    /** The CRM queue published a post: the bot doesn't receive updates for its own channel messages. */
    @EventListener
    public void onPostPublished(WorkspaceEvents.PostPublished e) {
        try {
            posts.recordPublished(e.telegramChatId(), (int) e.messageId(), e.text(), e.publishedAt() == null ? Instant.now() : e.publishedAt());
        } catch (RuntimeException ex) {
            log.warn("Could not add the published post {}:{} to the analysis: {}", e.telegramChatId(), e.messageId(), ex.getMessage());
        }
    }
}
