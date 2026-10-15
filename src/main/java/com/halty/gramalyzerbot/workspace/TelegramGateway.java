package com.halty.gramalyzerbot.workspace;
import static com.halty.gramalyzerbot.workspace.Models.*;
public interface TelegramGateway {
    long publish(Channel channel, Post post) throws Exception;
    long subscribers(long chatId) throws Exception;
}
