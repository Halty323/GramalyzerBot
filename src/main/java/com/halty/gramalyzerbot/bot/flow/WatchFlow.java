package com.halty.gramalyzerbot.bot.flow;

import com.halty.gramalyzerbot.billing.BillingService;
import com.halty.gramalyzerbot.bot.BotApi;
import com.halty.gramalyzerbot.bot.screen.WatchScreens;
import com.halty.gramalyzerbot.bot.ui.View;
import com.halty.gramalyzerbot.i18n.Tr;
import com.halty.gramalyzerbot.tracking.TrackedChannel;
import com.halty.gramalyzerbot.tracking.TrackingService;
import com.halty.gramalyzerbot.user.User;
import com.halty.gramalyzerbot.user.UserService;
import com.halty.gramalyzerbot.user.UserState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

/**
 * The watchlist in the bot: public channels a person keeps an eye on without adding the bot to them. Stats are shown
 * in UTC because a watched channel has no timezone of its own. A {@link com.halty.gramalyzerbot.tracking.TrackingException}
 * is left to the caller, which shows its translated reason.
 */
@Component
@RequiredArgsConstructor
public class WatchFlow {

    private static final Duration PROMPT_TTL = Duration.ofMinutes(15);
    private static final Pattern LINK = Pattern.compile("^(?:https?://)?(?:www\\.)?(?:t\\.me|telegram\\.me|telegram\\.dog)/\\S+$|^tg://resolve\\?\\S+$",
            Pattern.CASE_INSENSITIVE);

    private final TrackingService tracking;
    private final BillingService billing;
    private final UserService users;
    private final WatchScreens screens;
    private final BotApi gateway;

    /** A pasted t.me or tg:// link, which can only mean a channel someone wants to look at. */
    public static boolean looksLikeChannelLink(String text) { return text != null && LINK.matcher(text.trim()).matches(); }

    public View list(User u) {
        return screens.list(tracking.watched(TrackingService.owner(u)).stream()
                .map(c -> new WatchScreens.Row(c, tracking.stats(c, ZoneOffset.UTC))).toList());
    }

    public View card(User u, TrackedChannel c, String note) {
        return screens.card(c, tracking.stats(c, ZoneOffset.UTC), tracking.watching(TrackingService.owner(u), c), note);
    }

    public View top(TrackedChannel c) { return screens.top(c, tracking.stats(c, ZoneOffset.UTC)); }

    public void showList(User u, long chat) { gateway.send(chat, list(u)); }

    /** Asks for a username; the answer arrives as a message in {@link UserState#AWAIT_TRACK}. */
    public View askForChannel(User u) {
        users.setState(u, UserState.AWAIT_TRACK, "", PROMPT_TTL);
        return screens.addPrompt();
    }

    public void prompt(User u, long chat) { gateway.send(chat, askForChannel(u)); }

    /** Looks the channel up, puts it on the person's watchlist and shows its card. A bad username keeps the prompt open. */
    public void track(User u, long chat, String input) {
        String username = TrackingService.username(input);
        gateway.typing(chat);
        TrackedChannel c = add(u, username);
        users.clearState(u);
        gateway.send(chat, card(u, c, Tr.t("watch.added")));
    }

    /** Puts a channel on the person's watchlist, within what their plan allows. */
    public TrackedChannel add(User u, String username) {
        return tracking.track(TrackingService.owner(u), username, billing.watchlistLimit(u));
    }

    public void untrack(User u, long chat, String input) {
        if (input == null || input.isBlank()) {
            showList(u, chat);
            return;
        }
        tracking.untrack(TrackingService.owner(u), input);
        gateway.send(chat, View.of(Tr.t("watch.removed")));
        showList(u, chat);
    }
}
