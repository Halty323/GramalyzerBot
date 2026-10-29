package com.halty.gramalyzerbot.bot.flow;

import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.event.Events;
import com.halty.gramalyzerbot.bot.screen.GrowthScreens;
import com.halty.gramalyzerbot.bot.ui.View;
import com.halty.gramalyzerbot.billing.BillingService;
import com.halty.gramalyzerbot.channel.MembershipService;
import com.halty.gramalyzerbot.growth.GrowthService;
import com.halty.gramalyzerbot.i18n.Lang;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/** Subscribers joining and leaving own channels; tells the team when people leave in a wave. */
@Component
@RequiredArgsConstructor
public class GrowthFlow {

    private final GrowthService growth;
    private final GrowthScreens screens;
    private final MembershipService membership;
    private final BillingService billing;
    private final BotApi gateway;

    public void handle(Events.MemberChange e) {
        growth.record(e).ifPresent(wave -> {
            if (!billing.covers(wave.channel())) return;
            Map<Lang, View> byLang = new EnumMap<>(Lang.class);
            for (User u : membership.recipients(wave.channel(), true))
                gateway.send(u.getTelegramId(), byLang.computeIfAbsent(Lang.of(u), l -> Tr.in(l, () -> screens.wave(wave))));
        });
    }
}
