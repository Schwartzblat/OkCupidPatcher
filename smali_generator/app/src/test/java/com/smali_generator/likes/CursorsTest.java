package com.smali_generator.likes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CursorsTest {

    @Test public void decodesAPaddedCursor() {
        // base64url("abcdefghijklmnopqrstuvw") -- 23 chars, the observed id length
        assertEquals("abcdefghijklmnopqrstuvw",
                Cursors.decode("YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnc="));
    }

    @Test public void decodesACursorMissingItsPadding() {
        assertEquals("abcdefghijklmnopqrstuvw",
                Cursors.decode("YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnc"));
    }

    @Test public void nullCursorIsNull() {          // final page: after == null
        assertNull(Cursors.decode(null));
    }

    @Test public void blankCursorIsNull() {
        assertNull(Cursors.decode("   "));
    }

    @Test public void nonBase64IsNull() {
        // 24 chars, a whole number of quanta; '.' is the only bad character
        assertNull(Cursors.decode("YWJjZGVmZ2hpamtsbW5vcH.."));
    }

    @Test public void base64UrlPayloadWithEmbeddedSpecialsDecodes() {
        // base64url("abcdefghijklmnop_qrstuv") -- 23 bytes, contains '_'.
        // Real ids captured in-process from non-gated entries on 116.0.0
        // are base64url-shaped, not base62 -- about half of a 20-id sample
        // contained '-' or '_' outside the final quantum. This used to be
        // rejected as "decodes, in the window, but not an id"; it is now a
        // plausible id and must decode.
        assertEquals("abcdefghijklmnop_qrstuv",
                Cursors.decode("YWJjZGVmZ2hpamtsbW5vcF9xcnN0dXY="));
    }

    @Test public void base64UrlPayloadWithEmbeddedHyphenDecodes() {
        // base64url("abcdefghijklmnop-qrstuv") -- 23 bytes, contains '-' (not
        // just '_'). Without this, deleting the `c == '-'` branch of the
        // widened alphabet check would break no test.
        assertEquals("abcdefghijklmnop-qrstuv",
                Cursors.decode("YWJjZGVmZ2hpamtsbW5vcC1xcnN0dXY="));
    }

    @Test public void payloadWithACharacterOutsideBase64UrlIsNull() {
        // base64url("abcdefghijklmnopqrstuv.") -- 23 bytes, contains '.',
        // which is outside the base64url alphabet even after the widening
        // above. Proves the alphabet check still rejects something: deleting
        // the validation (returning raw bytes unchecked) would make this
        // assertion fail.
        assertNull(Cursors.decode("YWJjZGVmZ2hpamtsbW5vcHFyc3R1di4="));
    }

    @Test public void windowBoundariesAreInclusive() {
        assertNull(Cursors.decode("YWFhYWFhYWFhYWFhYWFh"));                     // 15 x 'a'
        assertEquals("aaaaaaaaaaaaaaaa",
                Cursors.decode("YWFhYWFhYWFhYWFhYWFhYQ=="));                    // 16
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                Cursors.decode("YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE="));  // 32
        assertNull(Cursors.decode("YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFh")); // 33
    }

    @Test public void urlSafeCharactersAreAcceptedInTheFinalQuantum() {
        // An all-alphanumeric id can only put '-' or '_' in the last character
        // of its cursor, where the low two bits are ignored padding. The id
        // below ends in 'o', so '8', '-' and '_' all carry the same data bits.
        assertEquals("abcdefghijklmnopqrstuvo",
                Cursors.decode("YWJjZGVmZ2hpamtsbW5vcHFyc3R1dm8"));
        assertEquals("abcdefghijklmnopqrstuvo",
                Cursors.decode("YWJjZGVmZ2hpamtsbW5vcHFyc3R1dm-"));
        assertEquals("abcdefghijklmnopqrstuvo",
                Cursors.decode("YWJjZGVmZ2hpamtsbW5vcHFyc3R1dm_"));
    }

    @Test public void impossibleLengthIsNull() {    // length % 4 == 1 is never valid base64
        assertNull(Cursors.decode("YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnczY"));
    }

    @Test public void surroundingWhitespaceIsTrimmed() {
        assertEquals("abcdefghijklmnopqrstuvw",
                Cursors.decode("  YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnc=\n"));
    }

    @Test public void implausibleLengthIsNull() {
        assertNull(Cursors.decode("YWJj"));               // "abc", too short
    }

    // The cursor minting round trip, against the server's REAL form.
    //
    // This test asserted the opposite until a live sweep caught it: the
    // encoder stripped padding, the server pads, and an unrecognised cursor is
    // not rejected -- the connection silently returns page 1. So the sweep
    // re-read the same twenty people while reporting that it had advanced. A
    // 23-byte id is 31 base64 characters plus one '=', and a live cursor was
    // observed at length 32. Padding stays.
    @Test public void encodeRoundTripsARealObservedCursorExactly() {
        String observed = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnc="; // page-mixed.json's after
        String id = Cursors.decode(observed);
        assertNotNull(id);
        assertEquals(observed, Cursors.encode(id));
    }

    @Test public void encodeEmitsThePaddedServerForm() {
        String cursor = Cursors.encode("abcdefghijklmnopqrstuvw"); // 23 bytes
        assertEquals(32, cursor.length());
        assertTrue(cursor.endsWith("="));
    }

    @Test public void encodeRoundTripsAnIdContainingUrlSafeCharacters() {
        // Covers the '-'/'_' branch of the alphabet, not just plain letters.
        String id = "abcdefghijklmnop-qrstuv";
        String cursor = Cursors.encode(id);
        assertEquals(id, Cursors.decode(cursor));
        // url-safe alphabet, never the standard one.
        assertFalse(cursor.contains("+"));
        assertFalse(cursor.contains("/"));
    }

    // Minting is gated on a live check rather than on our belief about the
    // format, because the format was wrong once and the failure was silent.
    @Test public void mintingIsRefusedUntilAServerCursorConfirmsTheForm() {
        Cursors.resetMintVerification();
        assertFalse(Cursors.canMint());
        Cursors.observeServerCursor("YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnc=");
        assertTrue(Cursors.canMint());
    }

    @Test public void mintingStaysRefusedWhenTheServerFormDiffers() {
        Cursors.resetMintVerification();
        // Same id, unpadded: the form this code used to emit.
        Cursors.observeServerCursor("YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnc");
        assertFalse(Cursors.canMint());
    }

    @org.junit.After public void clearMintVerification() {
        Cursors.resetMintVerification();
    }

    @Test public void encodeOfNullIsNull() {
        assertNull(Cursors.encode(null));
    }

    @Test public void encodeRejectsAnImplausibleLength() {
        assertNull(Cursors.encode("short"));
    }
}
