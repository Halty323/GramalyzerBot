package com.halty.gramalyzerbot.common;

/** An error whose message is safe and useful to show to the person using the bot. */
public class UserFacingException extends RuntimeException {

    public UserFacingException(String message) { super(message); }
}
