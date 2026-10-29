package com.halty.gramalyzerbot.bot.flow;

import com.halty.gramalyzerbot.billing.BillingService;
import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.screen.ViewSpeedScreens;
import com.halty.gramalyzerbot.channel.MembershipService;
import com.halty.gramalyzerbot.channel.Role;
import com.halty.gramalyzerbot.i18n.Lang;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.user.User;
import com.halty.gramalyzerbot.views.ViewSpeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Tells team members who get the weekly digest when a new post lags behind or flies ahead of the channel's usual pace. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ViewSpeedFlow {

    private final MembershipService membership;
    private final BillingService billing;
    private final ViewSpeedScreens screens;
    private final BotApi gateway;

    @TransactionalEventListener(fallbackExecution = true)
    public void alert(ViewSpeedService.Alert a) {
        try {
            if (!billing.covers(a.channel())) return;
            for (User u : membership.recipients(a.channel(), false)) {
                Role role = membership.roleOf(a.channel(), u).orElse(Role.MODERATOR);
                gateway.send(u.getTelegramId(), Tr.in(Lang.of(u), () -> screens.alert(a, role)));
            }
        } catch (RuntimeException e) {
            log.warn("Couldn't send the pace alert for post {} of channel {}: {}", a.post().getMessageId(), a.channel().getId(), e.toString());
        }
    }
}
