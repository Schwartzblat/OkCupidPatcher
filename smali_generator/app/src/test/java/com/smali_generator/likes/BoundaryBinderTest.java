package com.smali_generator.likes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.smali_generator.likes.BoundaryBinder.CardEntry;
import com.smali_generator.likes.BoundaryBinder.Decision;
import com.smali_generator.likes.BoundaryBinder.SkipReason;

public class BoundaryBinderTest {

    // A real, base64url cursor that decodes to "abcdefghijklmnopqrstuvo"
    // (reused from CursorsTest's urlSafeCharactersAreAcceptedInTheFinalQuantum).
    private static final String GOOD_CURSOR = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dm8";
    private static final String DECODED_ID = "abcdefghijklmnopqrstuvo";

    private static CardEntry card(String id, String path) {
        return new CardEntry(true, id, path);
    }

    private static CardEntry notACard() {
        return new CardEntry(false, null, null);
    }

    // --- The I1 regression: a skipped final entry must never fall back to an
    // earlier, successfully-bound one. ---

    @Test public void finalEntrySkippedDoesNotFallBackToAnEarlierCard() {
        List<CardEntry> entries = Arrays.asList(
                card("placeholder_1", "/photos/001/aaa.jpeg"),  // would-be "last good" card
                notACard());                                     // the page's REAL last entry
        Decision d = BoundaryBinder.decide(GOOD_CURSOR, entries);
        assertFalse(d.bound);
        assertEquals(SkipReason.FINAL_ENTRY_NOT_A_CARD, d.reason);
        // The specific wrong behaviour this guards against: binding the
        // earlier card's path instead of skipping outright.
        assertNull(d.path);
        assertNull(d.id);
    }

    @Test public void finalEntryHasNoPathDoesNotFallBackToAnEarlierCard() {
        List<CardEntry> entries = Arrays.asList(
                card("placeholder_1", "/photos/001/aaa.jpeg"),
                card("placeholder_2", null));                    // a card, but no photo
        Decision d = BoundaryBinder.decide(GOOD_CURSOR, entries);
        assertFalse(d.bound);
        assertEquals(SkipReason.FINAL_ENTRY_NO_PATH, d.reason);
        assertNull(d.path);
    }

    // --- Each remaining skip reason, pinned individually. ---

    @Test public void emptyPageIsSkipped() {
        Decision d = BoundaryBinder.decide(GOOD_CURSOR, Collections.<CardEntry>emptyList());
        assertFalse(d.bound);
        assertEquals(SkipReason.EMPTY_PAGE, d.reason);
    }

    @Test public void nullCursorIsSkipped() {
        List<CardEntry> entries = Collections.singletonList(card("placeholder_1", "/photos/001/aaa.jpeg"));
        Decision d = BoundaryBinder.decide(null, entries);
        assertFalse(d.bound);
        assertEquals(SkipReason.NO_CURSOR, d.reason);
    }

    @Test public void undecodableCursorIsSkipped() {
        List<CardEntry> entries = Collections.singletonList(card("placeholder_1", "/photos/001/aaa.jpeg"));
        Decision d = BoundaryBinder.decide("not-a-real-cursor!!", entries);
        assertFalse(d.bound);
        assertEquals(SkipReason.UNDECODABLE_CURSOR, d.reason);
    }

    @Test public void idMismatchIsSkipped() {
        // A real (non-placeholder) id on the final entry that disagrees with
        // what the cursor decoded to.
        List<CardEntry> entries = Collections.singletonList(
                card("some-other-real-id", "/photos/001/aaa.jpeg"));
        Decision d = BoundaryBinder.decide(GOOD_CURSOR, entries);
        assertFalse(d.bound);
        assertEquals(SkipReason.ID_MISMATCH, d.reason);
    }

    // --- The deliberate policy for a null id on the final entry: bind. ---

    @Test public void nullIdOnFinalEntryStillBinds() {
        // A null id carries nothing to contradict the cursor with, and the
        // final entry is still the correct element -- unlike the main loop
        // (which skips a card with no id because it cannot be stored under
        // one), the boundary decision has an id to use: the cursor's own.
        // Binding is the deliberate policy; silently matching the loop's
        // "skip if id is null" would throw away a free identity for no
        // reason the cursor's pairing assumption gives us.
        List<CardEntry> entries = Collections.singletonList(card(null, "/photos/001/aaa.jpeg"));
        Decision d = BoundaryBinder.decide(GOOD_CURSOR, entries);
        assertTrue(d.bound);
        assertEquals("/photos/001/aaa.jpeg", d.path);
        assertEquals(DECODED_ID, d.id);
        assertNull(d.reason);
    }

    // --- Successful binds, including the placeholder-id case. ---

    @Test public void placeholderIdOnFinalEntryBinds() {
        // A placeholder id cannot be compared to a real decoded id -- that is
        // exactly the case this feature exists to resolve.
        List<CardEntry> entries = Arrays.asList(
                notACard(),
                card("placeholder_7", "/photos/001/aaa.jpeg"));
        Decision d = BoundaryBinder.decide(GOOD_CURSOR, entries);
        assertTrue(d.bound);
        assertEquals("/photos/001/aaa.jpeg", d.path);
        assertEquals(DECODED_ID, d.id);
    }

    @Test public void matchingRealIdOnFinalEntryBinds() {
        List<CardEntry> entries = Collections.singletonList(card(DECODED_ID, "/photos/001/aaa.jpeg"));
        Decision d = BoundaryBinder.decide(GOOD_CURSOR, entries);
        assertTrue(d.bound);
        assertEquals("/photos/001/aaa.jpeg", d.path);
        assertEquals(DECODED_ID, d.id);
    }
}
