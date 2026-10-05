package com.smali_generator.likes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Seeding the per-process cache from what the sweep already has on disk.
 * This is what turns a cold-start resolve from a blind walk into arithmetic:
 * {@link SeekPlanner}'s exact seek needs an id placed at {@code target
 * offset - window}, and {@link SweepStore} has had one all along.
 */
public class ObservationCacheSeedTest {

    private static final String SORT = "DESC_TIMESTAMP";

    private static String id(int i) {
        return String.format("realid%016d", i);
    }

    private static PageParser.Attributes attrs(Integer age, Double score) {
        return new PageParser.Attributes(age, score, null, null, null, null);
    }

    @Before public void primeMinting() {
        // Self-consistent: feeding back our own encoding proves the round trip
        // without pinning which padding form the server uses.
        Cursors.resetMintVerification();
        Cursors.observeServerCursor(Cursors.encode(id(1)));
    }

    @After public void clearMinting() {
        Cursors.resetMintVerification();
    }

    /** n cards at positions 0..n-1; every one named except those in unnamed. */
    private static SweepStore storeOf(int n, int... unnamed) {
        SweepStore store = new SweepStore();
        for (int i = 0; i < n; i++) {
            boolean skip = false;
            for (int u : unnamed) {
                if (u == i) {
                    skip = true;
                }
            }
            store.upsert("/p" + i + ".jpeg", skip ? null : id(i), attrs(20 + (i % 30), 0.5 + i / 1000.0),
                    i, 1L);
        }
        return store;
    }

    @Test public void seedPlacesEveryRecordAtItsStoredOffset() {
        ObservationCache cache = new ObservationCache();
        cache.seed(SORT, storeOf(40).all(), 20);

        assertEquals(Integer.valueOf(0), cache.offsetOf(SORT, "/p0.jpeg"));
        assertEquals(Integer.valueOf(39), cache.offsetOf(SORT, "/p39.jpeg"));
    }

    @Test public void seedMakesIdsReachableByOffset() {
        ObservationCache cache = new ObservationCache();
        cache.seed(SORT, storeOf(40).all(), 20);

        assertEquals(id(25), cache.idAtOffset(SORT, 25));
    }

    @Test public void seedCarriesAttributesForTheKeyBasedSearch() {
        ObservationCache cache = new ObservationCache();
        cache.seed(SORT, storeOf(5).all(), 20);

        PageParser.Attributes a = cache.attributesOf("/p3.jpeg");
        assertNotNull(a);
        assertEquals(Integer.valueOf(23), a.age);
    }

    @Test public void seedRecordsTheWindowLength() {
        ObservationCache cache = new ObservationCache();
        cache.seed(SORT, storeOf(5).all(), 20);
        assertEquals(Integer.valueOf(20), cache.windowLength(SORT));
    }

    @Test public void anUnnamedCardIsStillPlaced() {
        // The target's own offset is half the exact-seek arithmetic; without
        // it the planner cannot compute which anchor to mint.
        ObservationCache cache = new ObservationCache();
        cache.seed(SORT, storeOf(40, 30).all(), 20);

        assertEquals(Integer.valueOf(30), cache.offsetOf(SORT, "/p30.jpeg"));
        assertNull(cache.idAtOffset(SORT, 30));
    }

    @Test public void seedNeverOverwritesSomethingAlreadyObserved() {
        // A real response is current; a stored position can be stale.
        ObservationCache cache = new ObservationCache();
        cache.seed(SORT, storeOf(3).all(), 20);
        assertEquals(Integer.valueOf(1), cache.offsetOf(SORT, "/p1.jpeg"));

        SweepStore moved = new SweepStore();
        moved.upsert("/p1.jpeg", id(1), attrs(21, 0.5), 99, 2L);
        cache.seed(SORT, moved.all(), 20);

        assertEquals(Integer.valueOf(1), cache.offsetOf(SORT, "/p1.jpeg"));
    }

    @Test public void seedIgnoresANullStoreOrSort() {
        ObservationCache cache = new ObservationCache();
        cache.seed(null, storeOf(3).all(), 20);
        cache.seed(SORT, null, 20);
        assertEquals(0, cache.size());
    }

    // ---- the point of the whole exercise -----------------------------------

    @Test public void aSeededCacheTurnsAColdResolveIntoAOneShotExactSeek() {
        ObservationCache cache = new ObservationCache();
        cache.seed(SORT, storeOf(60, 45).all(), 20);

        SeekPlanner.Plan plan = SeekPlanner.plan("/p45.jpeg",
                java.util.Collections.singletonList(SORT), cache);

        assertNotNull("no plan from a fully seeded cache", plan);
        assertEquals(SORT, plan.sort);
        // Anchor exactly one window before the target, so the window the
        // server returns ENDS on it and the cursor names it.
        assertEquals(id(25), Cursors.decode(plan.cursor));
    }

    @Test public void aUniformPositionShiftStillLandsTheExactSeek() {
        // Every new like inserts at offset 0 in DESC_TIMESTAMP and pushes
        // everyone down equally, so stored offsets go stale together. The
        // anchor and the target move by the same amount and the arithmetic
        // survives -- which is why seeding from disk is safe.
        SweepStore shifted = new SweepStore();
        for (int i = 0; i < 60; i++) {
            shifted.upsert("/p" + i + ".jpeg", i == 45 ? null : id(i),
                    attrs(20 + (i % 30), 0.5), i + 7, 1L);
        }
        ObservationCache cache = new ObservationCache();
        cache.seed(SORT, shifted.all(), 20);

        SeekPlanner.Plan plan = SeekPlanner.plan("/p45.jpeg",
                java.util.Collections.singletonList(SORT), cache);

        assertNotNull(plan);
        assertEquals(id(25), Cursors.decode(plan.cursor));
    }

    @Test public void aTargetInsideTheFirstWindowHasNoExactAnchor() {
        // Nothing sits twenty places before offset 0, which is exactly why a
        // brand-new like cannot be named from DESC_TIMESTAMP alone.
        ObservationCache cache = new ObservationCache();
        cache.seed(SORT, storeOf(60, 3).all(), 20);

        SeekPlanner.Plan plan = SeekPlanner.plan("/p3.jpeg",
                java.util.Collections.singletonList(SORT), cache);

        assertTrue("expected no exact seek for an offset below the window",
                plan == null || !id(-17).equals(Cursors.decode(plan.cursor)));
    }
}
