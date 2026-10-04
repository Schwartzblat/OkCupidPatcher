package com.smali_generator.likes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;

import org.junit.Test;

public class ObservationCacheTest {

    private static final String SORT = "AGE_ASCENDING";

    private static String page(String[] paths, String after) {
        StringBuilder data = new StringBuilder();
        for (int i = 0; i < paths.length; i++) {
            if (i > 0) {
                data.append(',');
            }
            data.append("{\"primaryImage\":{\"square225\":\"https://c.example.com")
                    .append(paths[i]).append("?h=8\"}}");
        }
        return "{\"data\":{\"me\":{\"likesIncomingWithPreviews\":{\"data\":[" + data
                + "],\"pageInfo\":{\"after\":" + (after == null ? "null" : "\"" + after + "\"")
                + ",\"hasMore\":true,\"total\":999}}}}}";
    }

    private static void absorb(ObservationCache cache, String sort, String requestCursor, String json) {
        PageParser.Page p = PageParser.parse(json);
        BoundaryBinder.Decision d = BoundaryBinder.decide(p.after, p.rawEntries);
        cache.absorb(sort, requestCursor, p, d);
    }

    @Test public void page1RootsAbsoluteOffsetsAtZero() {
        ObservationCache cache = new ObservationCache();
        absorb(cache, SORT, null, page(new String[] {"/photos/1/1/a.jpeg", "/photos/1/1/b.jpeg"}, null));
        assertEquals(Integer.valueOf(0), cache.offsetOf(SORT, "/photos/1/1/a.jpeg"));
        assertEquals(Integer.valueOf(1), cache.offsetOf(SORT, "/photos/1/1/b.jpeg"));
    }

    @Test public void aKnownBoundaryIdPlacesTheNextWindow() {
        ObservationCache cache = new ObservationCache();
        // Page 1: two entries, boundary cursor names the second one's id.
        String cursor = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnc"; // decodes to a 23-char id
        String id = Cursors.decode(cursor);
        absorb(cache, SORT, null, page(new String[] {"/photos/1/1/a.jpeg", "/photos/1/1/b.jpeg"}, cursor));
        assertEquals(Integer.valueOf(1), cache.offsetOf(SORT, "/photos/1/1/b.jpeg"));
        assertEquals(id, idOrNull(cache, 1));

        // Page 2, fetched WITH that cursor, starts right after it -- offset 2.
        absorb(cache, SORT, cursor, page(new String[] {"/photos/1/1/c.jpeg"}, null));
        assertEquals(Integer.valueOf(2), cache.offsetOf(SORT, "/photos/1/1/c.jpeg"));
    }

    private static String idOrNull(ObservationCache cache, int offset) {
        return cache.idAtOffset(SORT, offset);
    }

    @Test public void twoWindowsSharingAnEntryStitchIntoOneRun() {
        ObservationCache cache = new ObservationCache();
        // Window A, unanchored (its cursor's id is unknown to us), covering
        // local positions 0..1 of SOME absolute run.
        absorb(cache, SORT, "unmintedCursorXXXXXXXXX",
                page(new String[] {"/photos/s/1.jpeg", "/photos/s/2.jpeg"}, null));
        // Not placeable yet: the cursor's id is unknown.
        assertNull(cache.offsetOf(SORT, "/photos/s/1.jpeg"));

        // Window B is page 1 (offset 0) and happens to share "/photos/s/2.jpeg"
        // at its own local index 3 -- enough to retroactively place window A.
        absorb(cache, SORT, null,
                page(new String[] {"/photos/s/0.jpeg", "/photos/s/0b.jpeg", "/photos/s/0c.jpeg",
                        "/photos/s/2.jpeg"}, null));
        assertEquals(Integer.valueOf(3), cache.offsetOf(SORT, "/photos/s/2.jpeg"));

        // Re-absorbing window A now stitches: its "/photos/s/2.jpeg" is at
        // local index 1, so window A's start = 3 - 1 = 2, placing
        // "/photos/s/1.jpeg" (local index 0) at absolute offset 2.
        absorb(cache, SORT, "unmintedCursorXXXXXXXXX",
                page(new String[] {"/photos/s/1.jpeg", "/photos/s/2.jpeg"}, null));
        assertEquals(Integer.valueOf(2), cache.offsetOf(SORT, "/photos/s/1.jpeg"));
    }

    @Test public void windowLengthTracksTheWidestWindowSeen() {
        ObservationCache cache = new ObservationCache();
        assertNull(cache.windowLength(SORT));
        absorb(cache, SORT, null, page(new String[] {"/photos/1/1/a.jpeg"}, null));
        assertEquals(Integer.valueOf(1), cache.windowLength(SORT));
        absorb(cache, "VIEWED_ME", null,
                page(new String[] {"/photos/1/1/a.jpeg", "/photos/1/1/b.jpeg", "/photos/1/1/c.jpeg"}, null));
        // A different sort's window does not affect this one.
        assertEquals(Integer.valueOf(1), cache.windowLength(SORT));
        assertEquals(Integer.valueOf(3), cache.windowLength("VIEWED_ME"));
    }

    @Test public void attributesSurviveEvenWhenTheWindowCannotBePlaced() {
        ObservationCache cache = new ObservationCache();
        String json = "{\"data\":{\"me\":{\"likesIncomingWithPreviews\":{\"data\":["
                + "{\"primaryImage\":{\"square225\":\"https://c.example.com/photos/1/1/k.jpeg?h=8\"},"
                + "\"matchHighlights\":{\"age\":30}}],"
                + "\"pageInfo\":{\"after\":null,\"hasMore\":true,\"total\":1}}}}}";
        PageParser.Page p = PageParser.parse(json);
        BoundaryBinder.Decision d = BoundaryBinder.decide(p.after, p.rawEntries);
        cache.absorb(SORT, "someUnplaceableCursorXXX", p, d);
        PageParser.Attributes a = cache.attributesOf("/photos/1/1/k.jpeg");
        assertEquals(Integer.valueOf(30), a.age);
        // But no offset: the window itself never got placed.
        assertNull(cache.offsetOf(SORT, "/photos/1/1/k.jpeg"));
    }

    @Test public void unknownSortReturnsNullEverywhere() {
        ObservationCache cache = new ObservationCache();
        assertNull(cache.offsetOf("NOPE", "/x"));
        assertNull(cache.idAtOffset("NOPE", 0));
        assertNull(cache.windowLength("NOPE"));
    }

    @Test public void candidatesListsEveryKnownIdWithItsOffset() {
        ObservationCache cache = new ObservationCache();
        absorb(cache, SORT, null, page(new String[] {"/photos/1/1/a.jpeg"},
                "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnc"));
        java.util.List<ObservationCache.Candidate> cands = cache.candidates(SORT);
        assertEquals(1, cands.size());
        assertEquals(0, cands.get(0).offset);
    }
}
