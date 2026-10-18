package com.halty.gramalyzerbot.user;

/** Names of the multi-step prompts a person can be in the middle of. */
public final class UserState {

    public static final String AWAIT_TIMEZONE = "AWAIT_TZ";
    public static final String AWAIT_IMPORT = "AWAIT_IMPORT";
    public static final String AWAIT_CONNECT = "AWAIT_CONNECT";
    public static final String AWAIT_TOPIC = "AWAIT_TOPIC";
    public static final String AWAIT_BAN = "AWAIT_BAN";
    public static final String AWAIT_TRACK = "AWAIT_TRACK";

    private UserState() {}
}
