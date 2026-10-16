package com.halty.gramalyzerbot.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import static com.halty.gramalyzerbot.workspace.Models.*;

@Component
public class PostingWorker {
    private static final Logger log = LoggerFactory.getLogger(PostingWorker.class);
    private final WorkspaceStore store; private final TelegramGateway gateway; private final boolean enabled;
    private volatile boolean ready;
    private ApplicationEventPublisher events;
    @org.springframework.beans.factory.annotation.Autowired void setEvents(ApplicationEventPublisher events) {this.events=events;}
    @org.springframework.beans.factory.annotation.Autowired
    public PostingWorker(WorkspaceStore store,ObjectProvider<TelegramGateway> gateway,@Value("${telegram.bot.enabled:false}") boolean enabled,@Value("${gramalyzer.demo:false}") boolean demo) {this.store=store;this.gateway=gateway.getIfAvailable();this.enabled=enabled&&!demo;}
    public PostingWorker(WorkspaceStore store,TelegramGateway gateway) {this.store=store;this.gateway=gateway;this.enabled=true;this.ready=true;}
    @EventListener(ApplicationReadyEvent.class) public void recover() {if(enabled) store.recoverInterrupted(); ready=true;}
    @Scheduled(fixedDelayString="${gramalyzer.delivery-interval-ms:15000}") public void tick() {
        if(!enabled||!ready||gateway==null) return;
        for(Post post:store.due()) {
            Channel c=store.channel(post.channelId()).orElse(null);
            if(c==null||c.paused()||c.telegramChatId()==null||!store.claim(post.id())) continue;
            try {long messageId=gateway.publish(c,post); store.delivered(post.id(),messageId); announce(c,post,messageId);}
            catch(TelegramApiRequestException e) {boolean uncertain=e.getErrorCode()==null||e.getErrorCode()>=500; store.deliveryFailed(post.id(),uncertain,uncertain?"Delivery outcome is unknown. Check Telegram before creating a replacement.":"Telegram rejected this post. Check permissions and message length before rescheduling.");}
            catch(IllegalArgumentException e) {store.deliveryFailed(post.id(),false,"Message and signature exceed Telegram's limit. Shorten the post before rescheduling.");}
            catch(Exception e) {store.deliveryFailed(post.id(),true,"Delivery outcome is unknown. Check Telegram before creating a replacement.");}
        }
    }
    /** Lets the analytics side know about the post: the bot does not receive channel updates for its own messages. */
    private void announce(Channel c,Post post,long messageId) {
        if(events==null||c.telegramChatId()==null) return;
        try {events.publishEvent(new WorkspaceEvents.PostPublished(c.id(),c.telegramChatId(),messageId,post.body(),java.time.Instant.now()));}
        catch(RuntimeException e) {log.warn("Post {} was delivered but could not be announced to the analytics module", post.id(), e);}
    }
    @Scheduled(fixedDelayString="${gramalyzer.metrics-interval-ms:3600000}") public void refreshSubscribers() {
        if(!enabled||!ready||gateway==null) return;
        for(Channel channel:store.channels()) if(channel.telegramChatId()!=null) {
            try {store.recordSubscribers(channel.id(),gateway.subscribers(channel.telegramChatId()));}
            catch(Exception ignored) {/* Preserve the last successful observation; do not fabricate a zero count. */}
        }
    }
}
