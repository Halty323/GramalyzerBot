package com.halty.gramalyzerbot.bot.flow;

import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.ui.View;
import com.halty.gramalyzerbot.channel.ChannelService;
import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.settings.BotSettings;
import com.halty.gramalyzerbot.user.User;
import com.halty.gramalyzerbot.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Registers the person behind an update and enforces bans and the "who may start" switch. */
@Component
@RequiredArgsConstructor
public class Sessions {

    private final UserService users;
    private final ChannelService channels;
    private final BotSettings settings;
    private final BotApi gateway;

    /** Returns the user, or empty (after saying so) if they're banned. */
    public Optional<User> open(long telegramId, String username, String firstName, String lang, long chatId) {
        User u = users.touch(telegramId, username, firstName, lang);
        if (u.isBanned()) {
            gateway.send(chatId, View.of("🚫 You've been blocked from using this bot."));
            return Optional.empty();
        }
        return Optional.of(u);
    }

    /** Throws a friendly error if this person isn't allowed to connect channels or create demos. */
    public void requireMayCreate(User u) {
        if (u.isBotAdmin() || settings.publicRegistration() || !channels.forUser(u).isEmpty()) return;
        throw new UserFacingException("🔒 This bot is private right now. Ask its owner for an invite link.");
    }
}
