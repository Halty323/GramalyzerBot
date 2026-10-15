package com.halty.gramalyzerbot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import java.net.*;
import java.time.Duration;

@Configuration
@ConditionalOnProperty(name="telegram.bot.enabled", havingValue="true")
public class TelegramClientConfig {
    @Bean public OkHttpClient telegramHttpClient() {
        var builder=new OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(Duration.ofSeconds(60));
        String configured=System.getenv("HTTPS_PROXY");
        if(configured==null) configured=System.getenv("https_proxy");
        if(configured!=null&&!configured.isBlank()) {
            URI proxy=URI.create(configured);
            if(proxy.getUserInfo()!=null) throw new IllegalStateException("Configure proxy authentication securely in the runtime.");
            builder.proxy(new Proxy(Proxy.Type.HTTP,new InetSocketAddress(proxy.getHost(),proxy.getPort()==-1?80:proxy.getPort())));
        }
        return builder.build();
    }
    @Bean public TelegramClient telegramClient(@Value("${telegram.bot.token}") String token,OkHttpClient http) {
        if(token.isBlank()) throw new IllegalStateException("TELEGRAM_BOT_TOKEN is required when polling is enabled.");
        return new OkHttpTelegramClient(http,token);
    }
    @Bean public TelegramBotsLongPollingApplication telegramBotsApplication(ObjectMapper mapper,OkHttpClient http) {
        return new TelegramBotsLongPollingApplication(()->mapper,()->http);
    }
}
