package com.halty.gramalyzerbot.bot.screen;

import com.halty.gramalyzerbot.channel.*;
import com.halty.gramalyzerbot.user.User;
import com.halty.gramalyzerbot.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Works out which channel a person is looking at, and whether they may do what they're asking. */
@Component
@RequiredArgsConstructor
public class Access {

    private final ChannelService channels;
    private final MembershipService membership;
    private final UserService users;

    /** The channel the person is working on: their last choice if still valid, else their first one. */
    public Optional<Channel> active(User u) {
        if (u.getActiveChannelId() != null) {
            Optional<Channel> chosen = channels.forUser(u).stream().filter(c -> c.getId().equals(u.getActiveChannelId())).findFirst();
            if (chosen.isPresent()) return chosen;
            if (u.isBotAdmin()) {
                try {
                    return Optional.of(channels.get(u.getActiveChannelId()));
                } catch (RuntimeException ignored) {
                    // channel was deleted – fall through
                }
            }
        }
        List<Channel> mine = channels.forUser(u);
        if (mine.isEmpty()) return Optional.empty();
        users.setActiveChannel(u, mine.get(0).getId());
        return Optional.of(mine.get(0));
    }

    /** Loads a channel and checks the person has the permission, or throws a friendly error. */
    public Channel require(User u, long channelId, Permission p) {
        Channel ch = channels.get(channelId);
        membership.require(ch, u, p);
        return ch;
    }

    public Role roleOf(User u, Channel ch) {
        return membership.roleOf(ch, u).orElseThrow(() -> new com.halty.gramalyzerbot.common.UserFacingException("🔒 You don't have access to this channel."));
    }
}
