package com.halty.gramalyzerbot.bot.ui;

import java.util.ArrayList;
import java.util.List;

/** A message body (Telegram HTML) plus inline keyboard rows. Built fluently by the screens. */
public final class View {

    private final StringBuilder html = new StringBuilder();
    private final List<List<Btn>> rows = new ArrayList<>();

    public static View of(String firstLine) { return new View().line(firstLine); }

    public View line(String s) { html.append(s).append('\n'); return this; }

    public View blank() { html.append('\n'); return this; }

    public View raw(String s) { html.append(s); return this; }

    public View row(Btn... buttons) {
        if (buttons.length > 0) rows.add(List.of(buttons));
        return this;
    }

    public View rowIf(boolean cond, Btn... buttons) { return cond ? row(buttons) : this; }

    public View rows(List<List<Btn>> more) { rows.addAll(more); return this; }

    public String html() {
        String s = html.toString().stripTrailing();
        return s.isEmpty() ? "…" : s;
    }

    public List<List<Btn>> rows() { return rows; }
}
