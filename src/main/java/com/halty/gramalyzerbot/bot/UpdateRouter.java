package com.halty.gramalyzerbot.bot;

import com.halty.gramalyzerbot.bot.event.Events;
import com.halty.gramalyzerbot.bot.flow.BillingFlow;
import com.halty.gramalyzerbot.bot.flow.CallbackFlow;
import com.halty.gramalyzerbot.bot.flow.GrowthFlow;
import com.halty.gramalyzerbot.bot.flow.MembershipFlow;
import com.halty.gramalyzerbot.bot.flow.PrivateFlow;
import com.halty.gramalyzerbot.post.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Sends each kind of event to the code that handles it. */
@Component
@RequiredArgsConstructor
public class UpdateRouter {

    private final PostService posts;
    private final PrivateFlow privateFlow;
    private final CallbackFlow callbackFlow;
    private final MembershipFlow membershipFlow;
    private final BillingFlow billingFlow;
    private final GrowthFlow growthFlow;

    public void route(Object event) {
        if (event instanceof Events.PrivateMessage m) privateFlow.handle(m);
        else if (event instanceof Events.Callback c) callbackFlow.handle(c);
        else if (event instanceof Events.ChannelPost p) posts.onChannelPost(p);
        else if (event instanceof Events.Reactions r) posts.onReactions(r);
        else if (event instanceof Events.GroupMessage g) posts.onGroupMessage(g);
        else if (event instanceof Events.BotMembership b) membershipFlow.handle(b);
        else if (event instanceof Events.MemberChange m) growthFlow.handle(m);
        else if (event instanceof Events.PreCheckout q) billingFlow.preCheckout(q);
        else if (event instanceof Events.Payment p) billingFlow.paid(p);
        else if (event instanceof Events.Refund r) billingFlow.refunded(r);
    }
}
