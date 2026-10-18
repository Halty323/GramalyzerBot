package com.halty.gramalyzerbot.common;

import com.halty.gramalyzerbot.i18n.Tr;

import java.util.Locale;

/**
 * An error whose message is safe and useful to show to the person using the bot. It carries a text key (see
 * {@link Tr}); the message is put together in the language of whoever is being answered when it is shown. Enum
 * arguments such as a role are shown by their label ({@code lbl.role.ADMIN}).
 */
public class UserFacingException extends RuntimeException {

    private final String key;
    private final Object[] args;

    public UserFacingException(String key, Object... args) {
        super(key);
        this.key = key;
        this.args = args;
    }

    public String key() { return key; }

    @Override
    public String getMessage() {
        Object[] shown = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof Enum<?> e) {
                String label = "lbl." + e.getDeclaringClass().getSimpleName().toLowerCase(Locale.ROOT) + "." + e.name();
                shown[i] = Tr.has(label) ? Tr.t(label) : e.name();
            } else {
                shown[i] = a;
            }
        }
        return Tr.t(key, shown);
    }
}
