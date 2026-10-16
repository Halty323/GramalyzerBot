package com.halty.gramalyzerbot.analytics;

/** What a channel post is made of. Albums are collapsed into a single ALBUM post. */
public enum PostKind {
    TEXT, PHOTO, VIDEO, ANIMATION, AUDIO, VOICE, VIDEO_NOTE, DOCUMENT, POLL, ALBUM, STICKER, OTHER
}
