package com.halty.gramalyzerbot.tracking;

/**
 * Why a public channel can't be looked up or tracked. The website gets {@link #code} next to the English message so it
 * can show the reason in the viewer's language; the bot shows its own translation of the code.
 */
public class TrackingException extends RuntimeException {

    public enum Code {
        INVALID_USERNAME(400, "That doesn't look like a channel username. Usernames are 5–32 letters, digits or underscores."),
        PRIVATE_LINK(400, "That's a private invite link. Only public channels (with a @username) can be tracked."),
        NOT_FOUND(404, "There's no public channel with that username."),
        NOT_CHANNEL(400, "That username belongs to a person, bot or group, not a channel."),
        UNREACHABLE(502, "Telegram couldn't be reached. Try again in a moment."),
        LIMIT(409, "You're already tracking the maximum number of channels. Stop tracking one first."),
        NOT_TRACKED(404, "That channel isn't on your watchlist.");

        public final int status;
        public final String message;

        Code(int status, String message) {
            this.status = status;
            this.message = message;
        }

        /** The key the website and the bot translate, e.g. "not_found". */
        public String key() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    private final Code code;

    public TrackingException(Code code) {
        super(code.message);
        this.code = code;
    }

    public TrackingException(Code code, Throwable cause) {
        super(code.message, cause);
        this.code = code;
    }

    public Code code() { return code; }
}
