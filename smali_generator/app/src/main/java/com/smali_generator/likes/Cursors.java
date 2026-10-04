package com.smali_generator.likes;

/**
 * The pagination cursor is base64url of a user id, which is itself
 * base64url-shaped (23 characters of {@code [A-Za-z0-9_-]}) -- not base62 as
 * originally written up; see {@link #decode} for how that was corrected.
 * Decoding is the whole mechanism this feature rests on; everything else is
 * plumbing.
 */
public final class Cursors {

    /** null = not yet checked against a real cursor. */
    private static volatile Boolean mintVerified = null;

    private static final int MIN_ID = 16;   // observed ids are 23; a window
    private static final int MAX_ID = 32;   // rather than a pin, so a format
                                            // change skips a binding instead
                                            // of killing the feature
    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private Cursors() {
    }

    /**
     * The inverse of {@link #decode}: base64url of {@code id}, with no
     * padding -- the exact form observed in a server {@code pageInfo.after}
     * value (see {@code CursorsRoundTripTest}, which proves this against a
     * real captured cursor before anything relies on it). Returns null for a
     * null or implausible-length id rather than minting something the server
     * could never have produced itself.
     */
    public static String encode(String id) {
        if (id == null) {
            return null;
        }
        int len = id.length();
        if (len < MIN_ID || len > MAX_ID) {
            return null;
        }
        byte[] bytes = new byte[len];
        for (int i = 0; i < len; i++) {
            char c = id.charAt(i);
            if (c > 0xFF) {
                return null;             // not a byte-per-char id; refuse rather than mangle
            }
            bytes[i] = (byte) c;
        }
        String standard = base64Encode(bytes);
        // Padding is KEPT. The server's own cursors are padded -- a 23-byte id
        // encodes to 31 base64 characters plus one '=', and a live cursor was
        // observed at length 32 with a non-base64url character at the end.
        // Stripping it produced a form the server did not recognise, and an
        // unrecognised cursor is silently ignored: the connection returns page
        // 1 again, so a sweep re-reads the same twenty people and looks like it
        // is advancing. Do not "tidy" this.
        return standard.replace('+', '-').replace('/', '_');
    }

    /**
     * Feed a real server-issued cursor to confirm our encoder reproduces the
     * server's exact form before anything relies on minting.
     *
     * The convention is not guessable from the id alone: a base62 id never
     * produces the 62/63 sextets, so '+'/'-' and '/'/'_' never appear and the
     * alphabet question is undecidable from our side. Padding is decidable, and
     * it was wrong once. So rather than assume, we check against the real thing
     * the first time we see one.
     */
    public static void observeServerCursor(String serverCursor) {
        if (serverCursor == null || mintVerified != null) {
            return;
        }
        String id = decode(serverCursor);
        boolean ok = id != null && serverCursor.equals(encode(id));
        mintVerified = Boolean.valueOf(ok);
        Log.w(ok ? "Cursors: minting verified against a server cursor"
                 : "Cursors: minted form does not match the server's -- minting disabled");
    }

    /** True once a real server cursor has confirmed the minted form matches. */
    public static boolean canMint() {
        return Boolean.TRUE.equals(mintVerified);
    }

    /** Test seam: forget what we learned about the server's cursor form. */
    static void resetMintVerification() {
        mintVerified = null;
    }

    private static String base64Encode(byte[] bytes) {
        StringBuilder out = new StringBuilder(((bytes.length + 2) / 3) * 4);
        int i = 0;
        for (; i + 3 <= bytes.length; i += 3) {
            int chunk = ((bytes[i] & 0xFF) << 16) | ((bytes[i + 1] & 0xFF) << 8) | (bytes[i + 2] & 0xFF);
            out.append(ALPHABET.charAt((chunk >> 18) & 0x3F));
            out.append(ALPHABET.charAt((chunk >> 12) & 0x3F));
            out.append(ALPHABET.charAt((chunk >> 6) & 0x3F));
            out.append(ALPHABET.charAt(chunk & 0x3F));
        }
        int remaining = bytes.length - i;
        if (remaining == 1) {
            int chunk = (bytes[i] & 0xFF) << 16;
            out.append(ALPHABET.charAt((chunk >> 18) & 0x3F));
            out.append(ALPHABET.charAt((chunk >> 12) & 0x3F));
            out.append("==");
        } else if (remaining == 2) {
            int chunk = ((bytes[i] & 0xFF) << 16) | ((bytes[i + 1] & 0xFF) << 8);
            out.append(ALPHABET.charAt((chunk >> 18) & 0x3F));
            out.append(ALPHABET.charAt((chunk >> 12) & 0x3F));
            out.append(ALPHABET.charAt((chunk >> 6) & 0x3F));
            out.append('=');
        }
        return out.toString();
    }

    public static String decode(String cursor) {
        if (cursor == null) {
            return null;
        }
        String trimmed = cursor.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String padded = trimmed.replace('-', '+').replace('_', '/');
        int remainder = padded.length() % 4;
        if (remainder == 2) {
            padded = padded + "==";
        } else if (remainder == 3) {
            padded = padded + "=";
        } else if (remainder == 1) {
            return null;                    // never valid base64
        }
        byte[] raw = base64(padded);
        if (raw == null || raw.length < MIN_ID || raw.length > MAX_ID) {
            return null;
        }
        StringBuilder out = new StringBuilder(raw.length);
        for (byte b : raw) {
            char c = (char) (b & 0xFF);
            // Corrected from base62 to base64url on empirical evidence from
            // 116.0.0: ids captured in-process from non-gated "You Like"
            // entries -- the same id space a decoded cursor should point
            // into -- came back 23 characters long like the written report
            // recorded, but roughly half of them contain '-' or '_'. The
            // report's base62-only sample was simply too small; the real
            // alphabet is base64url, not base62.
            boolean base64url = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!base64url) {
                return null;
            }
            out.append(c);
        }
        return out.toString();
    }

    // Self-contained so the core stays free of Android and JDK-version-specific
    // base64 APIs.
    private static byte[] base64(String in) {
        int pad = 0;
        while (pad < 2 && in.endsWith("=")) {
            in = in.substring(0, in.length() - 1);
            pad++;
        }
        if (in.length() % 4 == 1) {
            return null;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (int i = 0; i < in.length(); i++) {
            int v = ALPHABET.indexOf(in.charAt(i));
            if (v < 0) {
                return null;
            }
            buffer = (buffer << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }
}
