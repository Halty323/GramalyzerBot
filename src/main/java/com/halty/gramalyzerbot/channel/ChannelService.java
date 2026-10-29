package com.halty.gramalyzerbot.channel;

import com.halty.gramalyzerbot.common.UserFacingException;
import com.halty.gramalyzerbot.config.BotProperties;
import com.halty.gramalyzerbot.crosspromo.SwapProfileRepository;
import com.halty.gramalyzerbot.crosspromo.SwapRepository;
import com.halty.gramalyzerbot.growth.MemberEventRepository;
import com.halty.gramalyzerbot.growth.PromoLinkRepository;
import com.halty.gramalyzerbot.post.DiscussionRootRepository;
import com.halty.gramalyzerbot.post.PostRepository;
import com.halty.gramalyzerbot.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ChannelService {

    private final ChannelRepository channels;
    private final ChannelMemberRepository members;
    private final InviteRepository invites;
    private final PostRepository posts;
    private final DiscussionRootRepository roots;
    private final SubscriberSnapshotRepository snapshots;
    private final AuditRepository audit;
    private final MemberEventRepository memberEvents;
    private final PromoLinkRepository promoLinks;
    private final SwapProfileRepository swapProfiles;
    private final SwapRepository swaps;
    private final BotProperties props;

    public Optional<Channel> byChatId(long chatId) { return channels.findByTelegramChatId(chatId); }

    /** Looks a channel up by the id the CRM uses for it. */
    public Optional<Channel> byWorkspaceId(String workspaceChannelId) { return channels.findByWorkspaceChannelId(workspaceChannelId); }

    public Channel get(long id) {
        return channels.findById(id).orElseThrow(() -> new UserFacingException("err.channel_gone"));
    }

    /** Creates the channel on first sight, otherwise refreshes its title and marks the bot as present. */
    @Transactional
    public Channel register(long chatId, String title, String username, Integer subscribers) {
        Channel ch = channels.findByTelegramChatId(chatId).orElseGet(() -> {
            Channel c = new Channel();
            c.setTelegramChatId(chatId);
            c.setConnectedAt(Instant.now());
            c.setTimezone(props.defaultTimezone());
            return c;
        });
        ch.setTitle(title == null || title.isBlank() ? "Channel " + chatId : title);
        ch.setUsername(username);
        ch.setActive(true);
        if (subscribers != null) {
            ch.setSubscribers(subscribers);
            ch.setSubscribersCheckedAt(Instant.now());
        }
        return channels.save(ch);
    }

    @Transactional
    public Channel save(Channel c) { return channels.save(c); }

    @Transactional
    public void markInactive(long chatId) {
        channels.findByTelegramChatId(chatId).ifPresent(c -> {
            c.setActive(false);
            channels.save(c);
        });
    }

    public List<Channel> forUser(User u) {
        List<Long> ids = new ArrayList<>();
        for (ChannelMember m : members.findAllByUserId(u.getId())) ids.add(m.getChannelId());
        List<Channel> out = new ArrayList<>(channels.findAllById(ids));
        out.sort((a, b) -> a.getTitle().compareToIgnoreCase(b.getTitle()));
        return out;
    }

    public List<Channel> trackable() { return channels.findAllByDemoFalseAndActiveTrue(); }

    public List<Channel> page(int page, int size) {
        Page<Channel> p = channels.findAllByOrderByIdDesc(PageRequest.of(page, size));
        return p.getContent();
    }

    public long count() { return channels.countByDemoFalse(); }

    /** Forget everything collected for a channel but keep its settings and team. */
    @Transactional
    public void resetData(Channel c) {
        posts.deleteAllByChannel(c.getId());
        roots.deleteAllByChannelId(c.getId());
        snapshots.deleteAllByChannelId(c.getId());
        memberEvents.deleteAllByChannelId(c.getId());
        c.setHistoryImported(false);
        channels.save(c);
    }

    /** Remove the channel and all of its data. */
    @Transactional
    public void deleteAll(Channel c) {
        posts.deleteAllByChannel(c.getId());
        roots.deleteAllByChannelId(c.getId());
        snapshots.deleteAllByChannelId(c.getId());
        memberEvents.deleteAllByChannelId(c.getId());
        promoLinks.deleteAllByChannelId(c.getId());
        swapProfiles.deleteAllByChannelId(c.getId());
        swaps.deleteAllByFromChannel(c.getId());
        swaps.deleteAllByToChannel(c.getId());
        invites.deleteAllByChannelId(c.getId());
        audit.deleteAllByChannelId(c.getId());
        members.deleteAllByChannelId(c.getId());
        channels.delete(c);
    }
}
