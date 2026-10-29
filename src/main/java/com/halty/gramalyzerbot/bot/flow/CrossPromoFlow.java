package com.halty.gramalyzerbot.bot.flow;

import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.screen.CrossPromoScreens;
import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.ChannelService;
import com.halty.gramalyzerbot.channel.MembershipService;
import com.halty.gramalyzerbot.channel.Role;
import com.halty.gramalyzerbot.crosspromo.SwapService;
import com.halty.gramalyzerbot.i18n.Lang;
import com.halty.gramalyzerbot.i18n.Tr;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Tells a channel's admins about swap requests and answers, with the buttons to act on them. */
@Component
@RequiredArgsConstructor
@Slf4j
public class CrossPromoFlow {

    private final ChannelService channels;
    private final MembershipService membership;
    private final SwapService swaps;
    private final CrossPromoScreens screens;
    private final BotApi gateway;

    /** Runs once the change is saved, so nobody hears about a request that was rolled back. */
    @TransactionalEventListener(fallbackExecution = true)
    public void notify(SwapService.Notice n) {
        try {
            Channel ch = channels.get(n.recipientChannel());
            SwapService.SwapView view = swaps.swapView(ch, n.swap().getId());
            for (MembershipService.MemberRow r : membership.team(ch)) {
                Role role = r.member().getRole();
                if (!role.atLeast(Role.ADMIN) || r.user().isBanned()) continue;
                gateway.send(r.user().getTelegramId(), Tr.in(Lang.of(r.user()), () -> screens.swap(ch, role, view, n.kind())));
            }
        } catch (RuntimeException e) {
            log.warn("Couldn't tell channel {} about swap {}: {}", n.recipientChannel(), n.swap().getId(), e.toString());
        }
    }
}
