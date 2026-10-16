package com.halty.gramalyzerbot.bot.flow;

import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.screen.StartScreens;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.ChannelService;
import com.halty.gramalyzerbot.channel.MembershipService;
import com.halty.gramalyzerbot.channel.SubscriberService;
import com.halty.gramalyzerbot.crm.CrmBridge;
import com.halty.gramalyzerbot.user.User;
import com.halty.gramalyzerbot.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The one place that turns "this person administers that channel and I'm in it" into a connected channel. */
@Component
@RequiredArgsConstructor
public class ChannelConnector {

    private final ChannelService channels;
    private final MembershipService membership;
    private final SubscriberService subscribers;
    private final UserService users;
    private final StartScreens screens;
    private final BotApi gateway;
    private final CrmBridge crm;

    public Channel connect(User user, long chatId, String title, String username) {
        Channel ch = channels.register(chatId, title, username, null);
        membership.claim(ch, user); // throws if somebody else already owns it here
        ch = crm.ensureInCrm(ch);   // the website lists every channel the bot serves
        users.setActiveChannel(user, ch.getId());
        users.clearState(user);
        subscribers.refresh(ch);
        gateway.send(user.getTelegramId(), screens.connected(ch));
        return ch;
    }
}
