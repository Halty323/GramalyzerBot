package com.halty.gramalyzerbot.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Profile photos of connected channels, fetched through the bot and kept in memory. The browser only ever sees our own URL. */
@Component
public class ChannelPhotos {
    private static final Logger log = LoggerFactory.getLogger(ChannelPhotos.class);
    /** tag is Telegram's unique id of the photo, so it doubles as an ETag. */
    public record Photo(String tag, byte[] bytes) {}
    private record Entry(Photo photo, Instant until) {}
    static final Duration FRESH=Duration.ofHours(1), MISSING=Duration.ofMinutes(10), RETRY=Duration.ofMinutes(1);
    private final TelegramGateway gateway; private final InstantSource clock;
    private final Map<Long,Entry> cache=new ConcurrentHashMap<>(); private final Map<Long,Object> locks=new ConcurrentHashMap<>();
    @Autowired public ChannelPhotos(ObjectProvider<TelegramGateway> gateway) {this(gateway.getIfAvailable(),InstantSource.system());}
    ChannelPhotos(TelegramGateway gateway,InstantSource clock) {this.gateway=gateway;this.clock=clock;}
    /** Re-checks the photo once FRESH runs out, but downloads it again only when it changed; on errors serves the last copy. */
    public Optional<Photo> photo(long chatId) {
        if(gateway==null) return Optional.empty();
        synchronized(locks.computeIfAbsent(chatId,k->new Object())) {
            Instant now=clock.instant(); Entry cached=cache.get(chatId);
            if(cached!=null&&now.isBefore(cached.until())) return Optional.ofNullable(cached.photo());
            Photo photo=cached==null?null:cached.photo();
            try {
                var current=gateway.photo(chatId);
                if(current==null) photo=null;
                else if(photo==null||!photo.tag().equals(current.uniqueId())) photo=new Photo(current.uniqueId(),gateway.download(current.fileId()));
                cache.put(chatId,new Entry(photo,now.plus(photo==null?MISSING:FRESH)));
            } catch(Exception e) {
                log.debug("Profile photo of chat {} unavailable: {}",chatId,e.getMessage());
                cache.put(chatId,new Entry(photo,now.plus(RETRY)));
            }
            return Optional.ofNullable(photo);
        }
    }
}
