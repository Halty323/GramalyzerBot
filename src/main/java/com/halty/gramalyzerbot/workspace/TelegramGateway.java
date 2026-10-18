package com.halty.gramalyzerbot.workspace;
import static com.halty.gramalyzerbot.workspace.Models.*;
public interface TelegramGateway {
    /** A chat's current profile photo; uniqueId changes whenever the photo does, fileId is what download() takes. */
    record PhotoRef(String uniqueId, String fileId) {}
    long publish(Channel channel, Post post) throws Exception;
    long subscribers(long chatId) throws Exception;
    /** Null when the chat has no profile photo. */
    PhotoRef photo(long chatId) throws Exception;
    byte[] download(String fileId) throws Exception;
}
