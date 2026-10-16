package com.halty.gramalyzerbot.post;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny pull-style JSON reader. It exists so a multi-megabyte Telegram export can be consumed one
 * message at a time without loading the whole file (and without depending on a specific Jackson).
 * Values are returned as Map / List / String / Long / Double / Boolean / null.
 */
public final class MiniJson {

    private static final int MAX_DEPTH = 100;

    private final Reader in;
    private final char[] buf = new char[16384];
    private int len, pos;
    private long offset;

    public MiniJson(Reader in) throws IOException {
        this.in = in;
        if (peek() == 0xFEFF) read(); // UTF-8 BOM
    }

    private int read() throws IOException {
        if (pos >= len) {
            len = in.read(buf, 0, buf.length);
            pos = 0;
            if (len <= 0) { len = 0; return -1; }
        }
        offset++;
        return buf[pos++];
    }

    private int peek() throws IOException {
        if (pos >= len) {
            len = in.read(buf, 0, buf.length);
            pos = 0;
            if (len <= 0) { len = 0; return -1; }
        }
        return buf[pos];
    }

    private void skipWs() throws IOException {
        int c;
        while ((c = peek()) == ' ' || c == '\n' || c == '\r' || c == '\t') read();
    }

    private IOException err(String msg) { return new IOException("Invalid JSON at char " + offset + ": " + msg); }

    public int peekNonWs() throws IOException { skipWs(); return peek(); }

    public void expect(char c) throws IOException {
        skipWs();
        int r = read();
        if (r != c) throw err("expected '" + c + "'");
    }

    public boolean consumeIf(char c) throws IOException {
        skipWs();
        if (peek() == c) { read(); return true; }
        return false;
    }

    public String readString() throws IOException {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = read();
            if (c < 0) throw err("unterminated string");
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append((char) c); continue; }
            int e = read();
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    int cp = 0;
                    for (int i = 0; i < 4; i++) {
                        int h = Character.digit(read(), 16);
                        if (h < 0) throw err("bad unicode escape");
                        cp = cp * 16 + h;
                    }
                    sb.append((char) cp);
                }
                default -> throw err("bad escape");
            }
        }
    }

    public Object readValue() throws IOException { return readValue(0); }

    private Object readValue(int depth) throws IOException {
        if (depth > MAX_DEPTH) throw err("too deeply nested");
        skipWs();
        int c = peek();
        if (c == '{') return readObject(depth);
        if (c == '[') return readArray(depth);
        if (c == '"') return readString();
        if (c == 't') { literal("true"); return Boolean.TRUE; }
        if (c == 'f') { literal("false"); return Boolean.FALSE; }
        if (c == 'n') { literal("null"); return null; }
        if (c == '-' || (c >= '0' && c <= '9')) return readNumber();
        throw err("unexpected character");
    }

    private Map<String, Object> readObject(int depth) throws IOException {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        if (consumeIf('}')) return map;
        do {
            String key = readString();
            expect(':');
            map.put(key, readValue(depth + 1));
        } while (consumeIf(','));
        expect('}');
        return map;
    }

    private List<Object> readArray(int depth) throws IOException {
        expect('[');
        List<Object> list = new ArrayList<>();
        if (consumeIf(']')) return list;
        do {
            list.add(readValue(depth + 1));
        } while (consumeIf(','));
        expect(']');
        return list;
    }

    private void literal(String word) throws IOException {
        for (int i = 0; i < word.length(); i++) if (read() != word.charAt(i)) throw err("expected " + word);
    }

    private Object readNumber() throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean isDouble = false;
        int c;
        while ((c = peek()) == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
            if (c == '.' || c == 'e' || c == 'E') isDouble = true;
            sb.append((char) read());
        }
        try {
            return isDouble ? (Object) Double.parseDouble(sb.toString()) : (Object) Long.parseLong(sb.toString());
        } catch (NumberFormatException ex) {
            throw err("bad number " + sb);
        }
    }
}
