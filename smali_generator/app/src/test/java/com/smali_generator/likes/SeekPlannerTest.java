package com.smali_generator.likes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class SeekPlannerTest {

    /**
     * The planner refuses to mint a cursor until a real server cursor has
     * proved the minted form matches (see {@link Cursors#canMint}). That flag
     * is process-wide, so arrange it here rather than inheriting whatever an
     * earlier test class happened to leave behind -- these tests passed only
     * by running after one that enabled it.
     */
    @Before public void mintingIsVerified() {
        Cursors.resetMintVerification();
        Cursors.observeServerCursor(Cursors.encode("abcdefghijklmnopqrstuvw"));
    }

    private static final List<String> SORTS = Arrays.asList("AGE_ASCENDING", "VIEWED_ME");
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

    // ---- exact seek: the off-by-one-sensitive arithmetic ----

    /**
     * Window length 4, target at absolute offset 1 (local index 1 of the
     * first page-1 window: a,TARGET,c,d). The next window ending exactly on
     * the target must start right after the entry at absolute offset
     * (1 - 4) = -3, which does not exist -- too close to page 1 to seek
     * exactly yet. A planner off by one (using length-1 instead of length,
     * or vice versa) would instead compute -4 or -2; this still rejects
     * either, so the REAL proof is the positive-offset case below.
     */
    @Test public void tooCloseToPageOneYieldsNoExactSeek() {
        ObservationCache cache = new ObservationCache();
        absorb(cache, SORT, null, page(new String[] {"/photos/a.jpeg", "/photos/target.jpeg",
                "/photos/c.jpeg", "/photos/d.jpeg"}, null));
        assertNull(SeekPlanner.plan("/photos/target.jpeg", SORTS, cache));
    }

    /**
     * The decisive off-by-one proof: window length 4, two consecutive
     * windows chained through a known boundary id, so the target is seen at
     * ABSOLUTE offset 5 (second window, local index 1: entries at absolute
     * offsets 4,5,6,7). An anchor id is deliberately placed at absolute
     * offset 1 (= 5 - 4, i.e. target.offset - length). exactSeek must mint
     * THAT id's cursor. If the arithmetic used (length - 1) instead of
     * length, it would look for the id at offset 2 instead -- which is a
     * DIFFERENT known id in this fixture -- and the test would fail by
     * asserting the wrong cursor.
     */
    @Test public void exactSeekAnchorsExactlyWindowLengthBeforeTheTarget() {
        ObservationCache cache = new ObservationCache();
        String boundaryCursor = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnc"; // decodes to a real 23-char id
        String boundaryId = Cursors.decode(boundaryCursor);

        // Page 1 (offsets 0..3): id at offset 1 ("the decoy") is a REAL,
        // non-gated id captured for free; id at offset 3 (the boundary) is
        // boundaryId.
        absorb(cache, SORT, null, page(new String[] {"/photos/off0.jpeg", "/photos/off1.jpeg",
                "/photos/off2.jpeg", "/photos/off3.jpeg"}, boundaryCursor));
        // off1 needs a REAL id of its own for exactSeek to find at offset 1 --
        // synthesize that via a second absorb carrying a non-gated entry.
        String off1Json = "{\"data\":{\"me\":{\"likesIncomingWithPreviews\":{\"data\":["
                + "{\"primaryImage\":{\"square225\":\"https://c.example.com/photos/off0.jpeg?h=8\"}},"
                + "{\"user\":{\"id\":\"realoffsetone0000000001\","
                + "\"primaryImage\":{\"square225\":\"https://c.example.com/photos/off1.jpeg?h=8\"}}},"
                + "{\"primaryImage\":{\"square225\":\"https://c.example.com/photos/off2.jpeg?h=8\"}},"
                + "{\"primaryImage\":{\"square225\":\"https://c.example.com/photos/off3.jpeg?h=8\"}}],"
                + "\"pageInfo\":{\"after\":\"" + boundaryCursor + "\",\"hasMore\":true,\"total\":999}}}}}";
        absorb(cache, SORT, null, off1Json);
        assertEquals(Integer.valueOf(1), cache.offsetOf(SORT, "/photos/off1.jpeg"));
        assertEquals("realoffsetone0000000001", cache.idAtOffset(SORT, 1));

        // Page 2, fetched with the boundary cursor, starts at absolute offset
        // 4. Target sits at local index 1 -> absolute offset 5.
        absorb(cache, SORT, boundaryCursor,
                page(new String[] {"/photos/off4.jpeg", "/photos/target.jpeg",
                        "/photos/off6.jpeg", "/photos/off7.jpeg"}, null));
        assertEquals(Integer.valueOf(5), cache.offsetOf(SORT, "/photos/target.jpeg"));
        assertEquals(Integer.valueOf(4), cache.windowLength(SORT));

        SeekPlanner.Plan plan = SeekPlanner.plan("/photos/target.jpeg", SORTS, cache);
        assertNotNull(plan);
        assertEquals(SORT, plan.sort);
        assertEquals(Cursors.encode("realoffsetone0000000001"), plan.cursor);
    }

    @Test public void noExactAnchorAtAllFallsThroughToBringIntoView() {
        ObservationCache cache = new ObservationCache();
        assertNull(SeekPlanner.plan("/photos/unknown.jpeg", SORTS, cache));
    }

    // ---- bring into view: nearest-by-key ----

    @Test public void bringsIntoViewUsingTheNearestKnownAgeWhenNoOffsetIsKnown() {
        ObservationCache cache = new ObservationCache();
        // The target's own attributes, learned somewhere (unplaceable window
        // is fine -- attributes do not need an offset).
        String targetJson = "{\"data\":{\"me\":{\"likesIncomingWithPreviews\":{\"data\":["
                + "{\"primaryImage\":{\"square225\":\"https://c.example.com/photos/target.jpeg?h=8\"},"
                + "\"matchHighlights\":{\"age\":30}}],"
                + "\"pageInfo\":{\"after\":null,\"hasMore\":true,\"total\":999}}}}}";
        absorb(cache, SORT, "someUnplaceableCursorXXX", targetJson);
        assertNull(cache.offsetOf(SORT, "/photos/target.jpeg"));

        // Two known, placed candidates in AGE_ASCENDING: age 25 (far) and
        // age 29 (closer to 30).
        String candidatesJson = "{\"data\":{\"me\":{\"likesIncomingWithPreviews\":{\"data\":["
                + "{\"user\":{\"id\":\"farrealid00000000000001\","
                + "\"primaryImage\":{\"square225\":\"https://c.example.com/photos/far.jpeg?h=8\"}},"
                + "\"matchHighlights\":{\"age\":25}},"
                + "{\"user\":{\"id\":\"nearrealid0000000000001\","
                + "\"primaryImage\":{\"square225\":\"https://c.example.com/photos/near.jpeg?h=8\"}},"
                + "\"matchHighlights\":{\"age\":29}}],"
                + "\"pageInfo\":{\"after\":null,\"hasMore\":true,\"total\":999}}}}}";
        absorb(cache, SORT, null, candidatesJson);

        SeekPlanner.Plan plan = SeekPlanner.plan("/photos/target.jpeg", SORTS, cache);
        assertNotNull(plan);
        assertEquals(SORT, plan.sort);
        assertEquals(Cursors.encode("nearrealid0000000000001"), plan.cursor);
    }

    @Test public void noAttributesAtAllMeansNoPlan() {
        ObservationCache cache = new ObservationCache();
        assertNull(SeekPlanner.plan("/photos/nobody-knows-this-one.jpeg", SORTS, cache));
    }
}
