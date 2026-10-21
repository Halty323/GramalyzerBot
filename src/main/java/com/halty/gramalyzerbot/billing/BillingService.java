package com.halty.gramalyzerbot.billing;

import com.halty.gramalyzerbot.channel.Channel;
import com.halty.gramalyzerbot.channel.ChannelMember;
import com.halty.gramalyzerbot.channel.ChannelMemberRepository;
import com.halty.gramalyzerbot.channel.ChannelRepository;
import com.halty.gramalyzerbot.channel.Role;
import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.user.User;
import com.halty.gramalyzerbot.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Who is on which plan, what that allows, and the bookkeeping for Telegram Stars payments. Telegram does the charging;
 * this only checks orders, records payments and refunds, and works out limits.
 * <p>
 * A plan stays active until {@code plan_until}. Telegram renews a subscription on its own when that date comes and sends
 * another successful payment, which moves the date on by 30 days. Nothing needs to run on a schedule: a plan that wasn't
 * renewed simply reads as {@link Plan#FREE} from then on.
 */
@Service
@Slf4j
public class BillingService {

    /** Telegram Stars subscriptions always renew every 30 days. */
    public static final int PERIOD_SECONDS = 2_592_000;
    /** Telegram renews when the plan runs out; this covers a renewal that arrives a little late. */
    static final Duration GRACE = Duration.ofDays(1);
    private static final String PAYLOAD_PREFIX = "plan:";

    private final BillingProperties props;
    private final UserRepository users;
    private final PaymentRepository payments;
    private final ChannelMemberRepository members;
    private final ChannelRepository channels;
    private final int watchlistWithoutBilling;

    public BillingService(BillingProperties props, UserRepository users, PaymentRepository payments,
                          ChannelMemberRepository members, ChannelRepository channels,
                          @Value("${gramalyzer.tracking.max-per-person:20}") int watchlistWithoutBilling) {
        this.props = props;
        this.users = users;
        this.payments = payments;
        this.members = members;
        this.channels = channels;
        this.watchlistWithoutBilling = watchlistWithoutBilling;
    }

    /** What a pay button sells: a plan, to one person. */
    public record Order(Plan plan, long telegramId) {}

    /** A recorded payment. {@code replacedCharge} is another running subscription that should now be cancelled. */
    public record Paid(User user, Plan plan, Instant until, boolean renewal, String replacedCharge) {}

    /** A recorded refund. {@code endedCharge} is the subscription to stop when the refund ended the plan. */
    public record Refunded(User user, Payment payment, boolean planEnded, String endedCharge) {}

    public boolean enabled() { return props.enabled(); }

    public int stars(Plan p) { return props.stars(p); }

    // ------------------------------------------------------------------ plans and limits

    /** The plan in force right now. */
    public Plan plan(User u) {
        Plan p = u.getPlan() == null ? Plan.FREE : u.getPlan();
        if (!p.paid() || u.getPlanUntil() == null) return Plan.FREE;
        Instant end = u.isPlanCanceled() ? u.getPlanUntil() : u.getPlanUntil().plus(GRACE);
        return end.isAfter(Instant.now()) ? p : Plan.FREE;
    }

    /** How many of their own channels a person can use; unlimited for bot admins and when billing is off. */
    public int channelLimit(User u) {
        if (!enabled() || u.isBotAdmin()) return Integer.MAX_VALUE;
        return plan(u).channels();
    }

    public int watchlistLimit(User u) {
        if (!enabled()) return watchlistWithoutBilling;
        if (u.isBotAdmin()) return Integer.MAX_VALUE;
        return plan(u).watchlist();
    }

    /** The real (non-demo) channels this person owns here, oldest connection first: the order plan limits go by. */
    public List<Channel> owned(User u) {
        List<ChannelMember> mine = members.findAllByUserId(u.getId()).stream()
                .filter(m -> m.getRole() == Role.OWNER)
                .sorted(Comparator.comparing(ChannelMember::getAddedAt).thenComparing(ChannelMember::getId))
                .toList();
        Map<Long, Channel> byId = new HashMap<>();
        for (Channel c : channels.findAllById(mine.stream().map(ChannelMember::getChannelId).toList())) byId.put(c.getId(), c);
        List<Channel> out = new ArrayList<>();
        for (ChannelMember m : mine) {
            Channel c = byId.get(m.getChannelId());
            if (c != null && !c.isDemo()) out.add(c);
        }
        return out;
    }

    /** Throws a friendly error if connecting this chat would take the person over their plan. Reconnecting is always fine. */
    public void requireRoomFor(User u, long telegramChatId) {
        int limit = channelLimit(u);
        if (limit == Integer.MAX_VALUE) return;
        List<Channel> owned = owned(u);
        if (owned.stream().anyMatch(c -> c.getTelegramChatId() == telegramChatId)) return;
        if (owned.size() >= limit) throw new UserFacingException("err.plan_channels", limit, plan(u));
    }

    /**
     * If this channel is beyond what its owner's plan covers (they connected more, then the plan ended), the number of
     * channels the plan does cover; empty when the channel works normally. Covered channels are the oldest ones.
     */
    public OptionalInt pausedLimit(Channel ch) {
        if (!enabled() || ch.isDemo()) return OptionalInt.empty();
        Optional<User> owner = members.findAllByChannelId(ch.getId()).stream()
                .filter(m -> m.getRole() == Role.OWNER)
                .findFirst()
                .flatMap(m -> users.findById(m.getUserId()));
        if (owner.isEmpty()) return OptionalInt.empty();
        int limit = channelLimit(owner.get());
        if (limit == Integer.MAX_VALUE) return OptionalInt.empty();
        List<Channel> owned = owned(owner.get());
        for (int i = 0; i < owned.size(); i++)
            if (owned.get(i).getId().equals(ch.getId())) return i < limit ? OptionalInt.empty() : OptionalInt.of(limit);
        return OptionalInt.empty();
    }

    public boolean covers(Channel ch) { return pausedLimit(ch).isEmpty(); }

    // ------------------------------------------------------------------ orders

    /** The invoice payload (max 128 bytes) that ties a payment to a plan and a person. */
    public String payload(Plan p, User u) { return PAYLOAD_PREFIX + p.name() + ":" + u.getTelegramId(); }

    public Optional<Order> parse(String payload) {
        if (payload == null || !payload.startsWith(PAYLOAD_PREFIX)) return Optional.empty();
        String[] parts = payload.substring(PAYLOAD_PREFIX.length()).split(":");
        if (parts.length != 2) return Optional.empty();
        try {
            Plan p = Plan.valueOf(parts[0]);
            return p.paid() ? Optional.of(new Order(p, Long.parseLong(parts[1]))) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Answers Telegram's last check before it charges someone: the text key of the reason to refuse, or empty to go ahead.
     * The amount itself isn't checked: Telegram signs the invoice, and renewals keep the price they started with.
     */
    public Optional<String> refusal(long fromTelegramId, String currency, String payload) {
        if (!enabled()) return Optional.of("pay.err.off");
        Optional<Order> o = parse(payload);
        if (o.isEmpty() || o.get().telegramId() != fromTelegramId || !"XTR".equals(currency)) return Optional.of("pay.err.invalid");
        Optional<User> u = users.findByTelegramId(fromTelegramId);
        if (u.isEmpty() || u.get().isBanned()) return Optional.of("pay.err.invalid");
        return Optional.empty();
    }

    // ------------------------------------------------------------------ payments and refunds

    /**
     * Records a successful payment and extends the person's plan. Empty if Telegram sent the same payment twice or it
     * isn't one of ours.
     *
     * @param expiresAt       the subscription's paid-up date from Telegram (epoch seconds), or null
     * @param recurring       the payment belongs to a subscription
     * @param firstRecurring  it's the first payment of that subscription
     */
    @Transactional
    public Optional<Paid> paid(long telegramId, String payload, int stars, String chargeId, Integer expiresAt,
                               boolean recurring, boolean firstRecurring) {
        if (payments.findByChargeId(chargeId).isPresent()) return Optional.empty();
        Optional<Order> order = parse(payload);
        Optional<User> found = users.findByTelegramId(telegramId);
        if (order.isEmpty() || found.isEmpty()) {
            log.warn("Payment {} of {} Stars from {} has an unknown payload \"{}\"", chargeId, stars, telegramId, payload);
            return Optional.empty();
        }
        User u = found.get();
        Plan bought = order.get().plan();
        Instant now = Instant.now();
        Instant until = expiresAt != null ? Instant.ofEpochSecond(expiresAt) : now.plusSeconds(PERIOD_SECONDS);
        boolean renewal = recurring && !firstRecurring;
        payments.save(new Payment(u.getId(), bought, stars, chargeId, renewal, now, until));

        Plan current = plan(u);
        String replaced = null;
        if (renewal && current.paid() && current != bought) {
            // An older subscription for another plan renewed (cancelling it must have failed): keep the better plan.
            if (current.ordinal() > bought.ordinal()) {
                log.warn("User {} renewed {} while on {}; keeping {}", telegramId, bought, current, current);
                return Optional.of(new Paid(u, current, u.getPlanUntil(), true, null));
            }
        }
        if (!renewal) {
            if (u.getPlanChargeId() != null && current.paid() && !u.isPlanCanceled()) replaced = u.getPlanChargeId();
            u.setPlanChargeId(chargeId);
            u.setPlanCanceled(false);
        }
        u.setPlan(bought);
        u.setPlanUntil(until);
        users.save(u);
        return Optional.of(new Paid(u, bought, until, renewal, replaced));
    }

    /** Marks a payment refunded; if it paid for the current period, the plan ends now. Empty if unknown or already done. */
    @Transactional
    public Optional<Refunded> refunded(String chargeId) {
        Optional<Payment> found = payments.findByChargeId(chargeId);
        if (found.isEmpty() || found.get().getRefundedAt() != null) return Optional.empty();
        Payment p = found.get();
        p.setRefundedAt(Instant.now());
        payments.save(p);
        User u = users.findById(p.getUserId()).orElseThrow();
        boolean ends = u.getPlan() == p.getPlan() && u.getPlanUntil() != null && !u.getPlanUntil().isAfter(p.getPaidUntil());
        String subscription = null;
        if (ends) {
            subscription = u.isPlanCanceled() ? null : u.getPlanChargeId();
            u.setPlan(Plan.FREE);
            u.setPlanUntil(null);
            u.setPlanChargeId(null);
            u.setPlanCanceled(false);
            users.save(u);
        }
        return Optional.of(new Refunded(u, p, ends, subscription));
    }

    /** Remembers that the person stopped (or restarted) automatic renewal; Telegram has already been told. */
    @Transactional
    public void setCanceled(User u, boolean canceled) {
        u.setPlanCanceled(canceled);
        users.save(u);
    }

    public Optional<Payment> payment(String chargeId) { return payments.findByChargeId(chargeId); }

    public List<Payment> recent(User u) { return payments.findTop5ByUserIdOrderByPaidAtDesc(u.getId()); }

    /** Payments and Stars received (minus refunds) in the last 30 days, for the admin panel. */
    public long[] lastMonth() {
        Instant from = Instant.now().minus(Duration.ofDays(30));
        return new long[]{payments.countByRefundedAtIsNullAndPaidAtAfter(from), payments.starsSince(from)};
    }
}
