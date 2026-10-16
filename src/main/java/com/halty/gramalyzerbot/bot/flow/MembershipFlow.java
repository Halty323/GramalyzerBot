package com.halty.gramalyzerbot.bot.flow;

import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.event.Events;
import com.halty.gramalyzerbot.bot.ui.Btn;
import com.halty.gramalyzerbot.bot.ui.Fmt;
import com.halty.gramalyzerbot.bot.ui.View;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.ChannelService;
import com.halty.gramalyzerbot.channel.MembershipService;
import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Reacts to the bot being added to, demoted in or removed from a channel. */
@Component
@RequiredArgsConstructor
@Slf4j
public class MembershipFlow {

    private final Sessions sessions;
    private final ChannelConnector connector;
    private final ChannelService channels;
    private final MembershipService membership;
    private final BotApi gateway;

    public void handle(Events.BotMembership b) {
        if (!"channel".equals(b.chatType())) return;

        if (b.isNowAdmin()) {
            onAdded(b);
        } else {
            // removed, kicked, or demoted to plain member: no more posts will reach us
            Optional<Channel> ch = channels.byChatId(b.chatId());
            if (ch.isEmpty() || !ch.get().isActive()) return;
            channels.markInactive(b.chatId());
            for (User u : membership.recipients(ch.get(), false)) {
                gateway.send(u.getTelegramId(), View.of("⚠️ <b>I can no longer see " + Fmt.esc(ch.get().getTitle()) + ".</b>")
                        .line("I was removed as an admin, so I've stopped collecting posts. Add me again to resume – your data is safe.")
                        .row(Btn.cb("➕ How to add me", "ch:connect")));
            }
        }
    }

    private void onAdded(Events.BotMembership b) {
        Optional<Channel> known = channels.byChatId(b.chatId());
        if (known.isPresent()) {
            // Either someone just edited my admin rights (nothing to do), or I was re-added after being removed.
            if (known.get().isActive()) return;
            Channel back = channels.register(b.chatId(), b.title(), b.username(), null);
            for (User u : membership.recipients(back, false))
                gateway.send(u.getTelegramId(), View.of("✅ <b>I'm back in " + Fmt.esc(back.getTitle()) + "</b> and collecting posts again."));
            return;
        }
        Optional<User> opt = quietOpen(b);
        if (opt.isEmpty()) {
            gateway.leave(b.chatId());
            return;
        }
        User user = opt.get();
        try {
            sessions.requireMayCreate(user);
            connector.connect(user, b.chatId(), b.title(), b.username());
        } catch (UserFacingException e) {
            // e.g. the channel is already owned by someone else, or the bot is private
            gateway.send(user.getTelegramId(), View.of(e.getMessage()));
            if (channels.byChatId(b.chatId()).isEmpty()) gateway.leave(b.chatId());
        } catch (RuntimeException e) {
            log.error("Could not connect channel {}", b.chatId(), e);
        }
    }

    /** Registers the person who added the bot without replying to them if they're banned. */
    private Optional<User> quietOpen(Events.BotMembership b) {
        return sessions.open(b.byUserId(), b.byUsername(), b.byFirstName(), null, b.byUserId());
    }
}
