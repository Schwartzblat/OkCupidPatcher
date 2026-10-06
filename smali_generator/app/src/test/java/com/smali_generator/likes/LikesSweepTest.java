package com.smali_generator.likes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class LikesSweepTest {

    private static final String SORT = "DESC_TIMESTAMP";

    /**
     * Minting is refused until a server cursor has proved the minted form
     * matches, and that flag is process-wide. Arrange it rather than
     * inheriting whatever another test class left behind.
     */
    @Before public void mintingIsVerified() {
        Cursors.resetMintVerification();
        Cursors.observeServerCursor(Cursors.encode("abcdefghijklmnopqrstuvw"));
    }

    /** A gated (anonymous) entry: no "user" object, just a primaryImage. */
    private static String gated(String path) {
        return "{\"primaryImage\":{\"square225\":\"https://c.example.com" + path + "?h=8\"}}";
    }

    /** A non-gated entry: a real id travels with it already, for free. */
    private static String nonGated(String path, String id) {
        return "{\"user\":{\"id\":\"" + id + "\",\"primaryImage\":"
                + "{\"square225\":\"https://c.example.com" + path + "?h=8\"}}}";
    }

    private static String page(String nodesJoined, String after) {
        return "{\"data\":{\"me\":{\"likesIncomingWithPreviews\":{\"data\":[" + nodesJoined
                + "],\"pageInfo\":{\"after\":" + (after == null ? "null" : "\"" + after + "\"")
                + ",\"hasMore\":" + (after != null) + ",\"total\":999}}}}}";
    }

    private static String join(String... nodes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nodes.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(nodes[i]);
        }
        return sb.toString();
    }

    /** A map-backed fetcher: cursor -> response body, plus a log of every cursor it was asked for. */
    private static final class ScriptedFetcher implements LikesSweep.Fetcher {
        final java.util.Map<String, String> byCursor = new java.util.HashMap<String, String>();
        final List<String> cursorsAsked = new ArrayList<String>();

        @Override public String fetch(String sort, String cursor) {
            cursorsAsked.add(cursor);
            return byCursor.get(cursor);
        }
    }

    private static final LikesSweep.Sleeper NO_SLEEP = new LikesSweep.Sleeper() {
        @Override public void sleep(long ms) {
        }
    };

    private static final LikesSweep.Clock FIXED_CLOCK = new LikesSweep.Clock() {
        @Override public long now() {
            return 42L;
        }
    };

    // ---- cursor chaining ----

    /**
     * Window 1 (cursor null): two entries, the second gated but named by the
     * boundary cursor. Window 2 must be fetched with the cursor MINTED from
     * that boundary id (decode then re-encode), not the literal string
     * `after` happened to be -- a mutation that skipped minting and reused
     * `after` directly would still pass this (they are equal here), which is
     * exactly why {@link #sweepMintsRatherThanReusingAfterVerbatim} exists
     * separately to pin that.
     */
    @Test public void sweepChainsTheMintedCursorIntoTheNextRequest() {
        String boundaryId = "boundaryid00000000000001";
        String boundaryCursor = Cursors.encode(boundaryId);

        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.byCursor.put(null, page(join(gated("/photos/a.jpeg"), gated("/photos/b.jpeg")),
                boundaryCursor));
        fetcher.byCursor.put(boundaryCursor, page(join(gated("/photos/c.jpeg"), gated("/photos/d.jpeg")),
                null));

        SweepStore store = new SweepStore();
        LikesSweep.Result result = LikesSweep.sweep(SORT, fetcher, NO_SLEEP, FIXED_CLOCK, store);

        assertEquals(LikesSweep.StopReason.END_OF_LIST, result.reason);
        assertEquals(2, result.requests);
        assertEquals(Arrays.asList(null, boundaryCursor), fetcher.cursorsAsked);
        assertEquals(4, store.size());
    }

    /** Pins minting specifically: the cursor sent is the re-encode of the decoded boundary id. */
    @Test public void sweepMintsRatherThanReusingAfterVerbatim() {
        String boundaryId = "mintedid00000000000000001";
        String boundaryCursor = Cursors.encode(boundaryId);
        String mintedAgain = Cursors.encode(Cursors.decode(boundaryCursor));
        assertEquals(boundaryCursor, mintedAgain);   // the round trip this whole feature rests on

        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.byCursor.put(null, page(join(gated("/photos/a.jpeg"), gated("/photos/b.jpeg")),
                boundaryCursor));
        fetcher.byCursor.put(mintedAgain, page(join(gated("/photos/c.jpeg")), null));

        SweepStore store = new SweepStore();
        LikesSweep.Result result = LikesSweep.sweep(SORT, fetcher, NO_SLEEP, FIXED_CLOCK, store);

        assertEquals(LikesSweep.StopReason.END_OF_LIST, result.reason);
        assertEquals(mintedAgain, fetcher.cursorsAsked.get(1));
    }

    /**
     * Mutation check on the chaining arithmetic: window 1 has THREE raw
     * entries, one of them a skipped non-card node (a null element), so
     * {@code page.entries} has 2 but {@code page.rawEntries} has 3. The
     * recorded position of window 2's first entry must be 3 (rawEntries
     * count), not 2 (entries count) -- a mutation from {@code
     * page.rawEntries.size()} to {@code page.entries.size()} in the
     * position-advance line would make this assert 2 and fail.
     */
    @Test public void positionAdvancesByRawEntryCountIncludingSkippedNodes() {
        String boundaryId = "skipwindowid0000000000001";
        String boundaryCursor = Cursors.encode(boundaryId);

        ScriptedFetcher fetcher = new ScriptedFetcher();
        // "null" is a raw node that becomes a skipped CardEntry, not a PageParser.Entry.
        fetcher.byCursor.put(null, page(join("null", gated("/photos/a.jpeg"), gated("/photos/b.jpeg")),
                boundaryCursor));
        fetcher.byCursor.put(boundaryCursor, page(join(gated("/photos/c.jpeg")), null));

        SweepStore store = new SweepStore();
        LikesSweep.sweep(SORT, fetcher, NO_SLEEP, FIXED_CLOCK, store);

        assertEquals(3, store.get("/photos/c.jpeg").position);
    }

    // ---- stop rule ----

    @Test public void sweepStopsAsSoonAsAWindowIsEntirelyAlreadyKnown() {
        ScriptedFetcher fetcher = new ScriptedFetcher();
        String after = Cursors.encode("wouldcontinueid000000001");
        fetcher.byCursor.put(null, page(join(gated("/photos/a.jpeg"), gated("/photos/b.jpeg")), after));

        SweepStore store = new SweepStore();
        store.upsert("/photos/a.jpeg", null, null, 0, 1L);
        store.upsert("/photos/b.jpeg", null, null, 1, 1L);

        LikesSweep.Result result = LikesSweep.sweep(SORT, fetcher, NO_SLEEP, FIXED_CLOCK, store);

        assertEquals(LikesSweep.StopReason.REACHED_KNOWN, result.reason);
        assertEquals(1, result.requests);               // never asked for the second window
    }

    /**
     * Mutation check on the stop rule: ONE of the two entries is already
     * known, the other is new. A mutated stop rule using "any known" instead
     * of "all known" would stop here too; this pins that it must continue.
     */
    @Test public void sweepContinuesWhenOnlyPartOfTheWindowIsAlreadyKnown() {
        String boundaryId = "partialknownid00000000001";
        String boundaryCursor = Cursors.encode(boundaryId);

        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.byCursor.put(null, page(join(gated("/photos/a.jpeg"), gated("/photos/b.jpeg")),
                boundaryCursor));
        fetcher.byCursor.put(boundaryCursor, page(join(gated("/photos/c.jpeg")), null));

        SweepStore store = new SweepStore();
        store.upsert("/photos/a.jpeg", null, null, 0, 1L);     // only "a" is already known

        LikesSweep.Result result = LikesSweep.sweep(SORT, fetcher, NO_SLEEP, FIXED_CLOCK, store);

        assertEquals(LikesSweep.StopReason.END_OF_LIST, result.reason);
        assertEquals(2, result.requests);
    }

    @Test public void sweepStopsAtEndOfListWhenThereIsNoFurtherCursor() {
        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.byCursor.put(null, page(join(gated("/photos/a.jpeg")), null));

        LikesSweep.Result result = LikesSweep.sweep(SORT, fetcher, NO_SLEEP, FIXED_CLOCK, new SweepStore());

        assertEquals(LikesSweep.StopReason.END_OF_LIST, result.reason);
        assertEquals(1, result.requests);
    }

    @Test public void sweepStopsAtTheRequestCapRatherThanRunningForever() {
        LikesSweep.Fetcher fetcher = new LikesSweep.Fetcher() {
            private int counter = 0;

            @Override public String fetch(String sort, String cursor) {
                counter++;
                String id = "cap-id-" + String.format("%016d", counter);   // 23 chars total
                String nextCursor = Cursors.encode(id);
                return page(gated("/photos/n" + counter + ".jpeg"), nextCursor);
            }
        };

        LikesSweep.Result result = LikesSweep.sweep(SORT, fetcher, NO_SLEEP, FIXED_CLOCK, new SweepStore());

        assertEquals(LikesSweep.StopReason.CAP, result.reason);
        assertEquals(LikesSweep.MAX_REQUESTS_PER_SWEEP, result.requests);
    }

    @Test public void sweepAbortsAfterConsecutiveFailuresWithoutHittingTheCap() {
        LikesSweep.Fetcher alwaysFails = new LikesSweep.Fetcher() {
            @Override public String fetch(String sort, String cursor) {
                return null;
            }
        };

        LikesSweep.Result result = LikesSweep.sweep(SORT, alwaysFails, NO_SLEEP, FIXED_CLOCK, new SweepStore());

        assertEquals(LikesSweep.StopReason.FAILURES, result.reason);
        assertEquals(LikesSweep.MAX_CONSECUTIVE_FAILURES, result.requests);
    }

    // ---- identity bookkeeping ----

    @Test public void aBoundaryBoundIdAndANonGatedIdAreBothRecorded() {
        String boundaryId = "boundaryrecordedid0000001";
        String boundaryCursor = Cursors.encode(boundaryId);

        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.byCursor.put(null, page(join(
                nonGated("/photos/free.jpeg", "nongatedid00000000000001"),
                gated("/photos/boundary.jpeg")), boundaryCursor));
        fetcher.byCursor.put(boundaryCursor, page(join(gated("/photos/last.jpeg")), null));

        SweepStore store = new SweepStore();
        LikesSweep.Result result = LikesSweep.sweep(SORT, fetcher, NO_SLEEP, FIXED_CLOCK, store);

        assertEquals(2, result.entriesWithId);
        assertEquals("nongatedid00000000000001", store.get("/photos/free.jpeg").realId);
        assertEquals(boundaryId, store.get("/photos/boundary.jpeg").realId);
        assertNull(store.get("/photos/last.jpeg").realId);
    }

    // ---- sort selection ----

    @Test public void pickSweepSortMatchesCaseInsensitively() {
        assertEquals("desc_timestamp", LikesSweep.pickSweepSort(Arrays.asList("AGE_ASCENDING", "desc_timestamp")));
    }

    @Test public void pickSweepSortReturnsNullWhenAbsent() {
        assertNull(LikesSweep.pickSweepSort(Arrays.asList("AGE_ASCENDING", "VIEWED_ME")));
        assertNull(LikesSweep.pickSweepSort(null));
    }

    // ---- priming the in-memory store from disk ----

    @Test public void primeIdentityStoreFeedsEveryKnownIdIntoIdentityStore() {
        IdentityStore identity = IdentityStore.get();
        SweepStore disk = new SweepStore();
        String path = "/photos/prime-test-" + System.nanoTime() + ".jpeg";
        disk.upsert(path, "primedid0000000000000001", null, 0, 1L);

        LikesSweep.primeIdentityStore(disk);

        assertEquals("primedid0000000000000001", identity.realIdFor(path));
    }

    // ---- expanding across the other sorts ----

    /** A fetcher that answers differently per sort, which the expansion depends on. */
    private static LikesSweep.Fetcher bySort(final java.util.Map<String, java.util.Map<String, String>> script) {
        return new LikesSweep.Fetcher() {
            @Override public String fetch(String sort, String cursor) {
                java.util.Map<String, String> pages = script.get(sort);
                return pages == null ? null : pages.get(cursor);
            }
        };
    }

    private static java.util.Map<String, String> pages(String first, String firstAfter) {
        java.util.Map<String, String> m = new java.util.HashMap<String, String>();
        m.put(null, first);
        m.put(firstAfter, page("", null));        // the empty page that ends a walk
        return m;
    }

    @Test public void anotherSortNamesSomeoneTheSweptSortNeverCould() {
        String c1 = "/photos/1/1/c1.jpeg", c2 = "/photos/1/1/c2.jpeg", c3 = "/photos/1/1/c3.jpeg";
        String afterC3 = Cursors.encode("realid0000000000000003");
        String afterC2 = Cursors.encode("realid0000000000000002");

        java.util.Map<String, java.util.Map<String, String>> script =
                new java.util.HashMap<String, java.util.Map<String, String>>();
        // A ends on c3, so only c3 is nameable from A.
        script.put("A", pages(page(join(gated(c1), gated(c2), gated(c3)), afterC3), afterC3));
        // B holds the same three people in another order, ending on c2.
        script.put("B", pages(page(join(gated(c3), gated(c1), gated(c2)), afterC2), afterC2));
        LikesSweep.Fetcher fetcher = bySort(script);

        SweepStore store = new SweepStore();
        LikesSweep.sweep("A", fetcher, NO_SLEEP, FIXED_CLOCK, store);
        assertEquals("realid0000000000000003", store.get(c3).realId);
        assertNull(store.get(c2).realId);

        int gained = LikesSweep.expandAcrossSorts(
                java.util.Arrays.asList("A", "B"), "A", fetcher, NO_SLEEP, FIXED_CLOCK, store);

        assertEquals(1, gained);
        assertEquals("realid0000000000000002", store.get(c2).realId);
    }

    @Test public void asecondarySortNeverMovesThePositionsTheSweptSortRecorded() {
        String c1 = "/photos/1/1/c1.jpeg", c2 = "/photos/1/1/c2.jpeg", c3 = "/photos/1/1/c3.jpeg";
        String afterC3 = Cursors.encode("realid0000000000000003");
        String afterC2 = Cursors.encode("realid0000000000000002");

        java.util.Map<String, java.util.Map<String, String>> script =
                new java.util.HashMap<String, java.util.Map<String, String>>();
        script.put("A", pages(page(join(gated(c1), gated(c2), gated(c3)), afterC3), afterC3));
        script.put("B", pages(page(join(gated(c3), gated(c1), gated(c2)), afterC2), afterC2));
        LikesSweep.Fetcher fetcher = bySort(script);

        SweepStore store = new SweepStore();
        LikesSweep.sweep("A", fetcher, NO_SLEEP, FIXED_CLOCK, store);
        assertEquals(0, store.get(c1).position);
        assertEquals(2, store.get(c3).position);

        LikesSweep.expandAcrossSorts(
                java.util.Arrays.asList("A", "B"), "A", fetcher, NO_SLEEP, FIXED_CLOCK, store);

        // B puts c3 first and c1 second; the stored order must still be A's.
        assertEquals(0, store.get(c1).position);
        assertEquals(2, store.get(c3).position);
    }

    @Test public void expansionSkipsTheSortAlreadySwept() {
        String c1 = "/photos/1/1/c1.jpeg";
        String afterC1 = Cursors.encode("realid0000000000000001");
        java.util.Map<String, java.util.Map<String, String>> script =
                new java.util.HashMap<String, java.util.Map<String, String>>();
        script.put("A", pages(page(gated(c1), afterC1), afterC1));
        final List<String> sortsAsked = new ArrayList<String>();
        final LikesSweep.Fetcher inner = bySort(script);
        LikesSweep.Fetcher recording = new LikesSweep.Fetcher() {
            @Override public String fetch(String sort, String cursor) {
                sortsAsked.add(sort);
                return inner.fetch(sort, cursor);
            }
        };

        SweepStore store = new SweepStore();
        LikesSweep.expandAcrossSorts(java.util.Arrays.asList("A"), "A", recording, NO_SLEEP,
                FIXED_CLOCK, store);

        assertTrue(sortsAsked.isEmpty());
    }

    @Test public void aWalkHuntingIdsKeepsGoingPastWindowsWhoseCardsAreAllKnown() {
        String c1 = "/photos/1/1/c1.jpeg", c2 = "/photos/1/1/c2.jpeg";
        String c3 = "/photos/1/1/c3.jpeg", c4 = "/photos/1/1/c4.jpeg";
        String afterC2 = Cursors.encode("realid0000000000000002");
        String afterC4 = Cursors.encode("realid0000000000000004");

        java.util.Map<String, String> walk = new java.util.HashMap<String, String>();
        walk.put(null, page(join(gated(c1), gated(c2)), afterC2));
        walk.put(afterC2, page(join(gated(c3), gated(c4)), afterC4));
        walk.put(afterC4, page("", null));
        java.util.Map<String, java.util.Map<String, String>> script =
                new java.util.HashMap<String, java.util.Map<String, String>>();
        script.put("B", walk);
        LikesSweep.Fetcher fetcher = bySort(script);

        // Every card is already known, so the incremental stop fires on the
        // first window -- which is exactly what a secondary walk must not do,
        // because the ids it is after live on the windows beyond it.
        SweepStore stopping = new SweepStore();
        SweepStore hunting = new SweepStore();
        for (SweepStore store : new SweepStore[] {stopping, hunting}) {
            store.upsert(c1, null, null, 0, 1L);
            store.upsert(c2, null, null, 1, 1L);
            store.upsert(c3, null, null, 2, 1L);
            store.upsert(c4, null, null, 3, 1L);
        }

        LikesSweep.Result stopped = LikesSweep.sweep("B", fetcher, NO_SLEEP, FIXED_CLOCK, stopping,
                false, true);
        assertEquals(LikesSweep.StopReason.REACHED_KNOWN, stopped.reason);
        assertNull("the second window was never reached", stopping.get(c4).realId);

        LikesSweep.Result hunted = LikesSweep.sweep("B", fetcher, NO_SLEEP, FIXED_CLOCK, hunting,
                false, false);
        assertEquals(LikesSweep.StopReason.END_OF_LIST, hunted.reason);
        assertEquals("realid0000000000000004", hunting.get(c4).realId);
    }

    @Test public void aNullPrimarySortMeansWalkEverySortIncludingTheSweptOne() {
        String c1 = "/photos/1/1/c1.jpeg";
        String afterC1 = Cursors.encode("realid0000000000000001");
        java.util.Map<String, java.util.Map<String, String>> script =
                new java.util.HashMap<String, java.util.Map<String, String>>();
        script.put("A", pages(page(gated(c1), afterC1), afterC1));
        final List<String> sortsAsked = new ArrayList<String>();
        final LikesSweep.Fetcher inner = bySort(script);
        LikesSweep.Fetcher recording = new LikesSweep.Fetcher() {
            @Override public String fetch(String sort, String cursor) {
                sortsAsked.add(sort);
                return inner.fetch(sort, cursor);
            }
        };

        // With views off, "A" is a different ordering from the one already
        // walked, so it must not be skipped.
        LikesSweep.expandAcrossSorts(java.util.Arrays.asList("A"), null, recording, NO_SLEEP,
                FIXED_CLOCK, new SweepStore());

        assertTrue(sortsAsked.contains("A"));
    }

    // ---- anchored walks ----

    @Test public void aWalkResumedFromAnAnchorKeepsTheSortsOwnPositionFrame() {
        String c5 = "/photos/1/1/c5.jpeg", c6 = "/photos/1/1/c6.jpeg";
        String anchorCursor = Cursors.encode("realid0000000000000004");
        String afterC6 = Cursors.encode("realid0000000000000006");

        java.util.Map<String, String> walk = new java.util.HashMap<String, String>();
        walk.put(anchorCursor, page(join(gated(c5), gated(c6)), afterC6));
        walk.put(afterC6, page("", null));
        java.util.Map<String, java.util.Map<String, String>> script =
                new java.util.HashMap<String, java.util.Map<String, String>>();
        script.put(SORT, walk);

        SweepStore store = new SweepStore();
        LikesSweep.sweep(SORT, bySort(script), NO_SLEEP, FIXED_CLOCK, store, true, false,
                anchorCursor, 5);

        assertEquals(5, store.get(c5).position);
        assertEquals(6, store.get(c6).position);
        assertEquals("realid0000000000000006", store.get(c6).realId);
    }

    /**
     * An anchored walk is aimed at a card that is actually missing, not fired
     * at every residue that happens to own an anchor.
     *
     * <p>The one unnamed card here sits at position 50, residue 10, and
     * nothing is known at 30 or 10 -- so no walk in this sort can put a
     * boundary on it, and the right number of requests is zero. The earlier
     * strategy bucketed the *known* ids by residue instead and started a walk
     * from the earliest of each, which spent two walks here and 121 requests
     * on the measured 123-card list, naming nothing either time.
     */
    @Test public void anchoredWalksAreAimedAtMissingCardsNotAtEveryAnchoredResidue() {
        SweepStore store = new SweepStore();
        store.upsert("/photos/1/1/a19.jpeg", "realid0000000000000019", null, 19, 1L);
        store.upsert("/photos/1/1/a39.jpeg", "realid0000000000000039", null, 39, 1L);
        store.upsert("/photos/1/1/a07.jpeg", "realid0000000000000007", null, 7, 1L);
        store.upsert("/photos/1/1/plain.jpeg", null, null, 50, 1L);

        final List<String> startedFrom = new ArrayList<String>();
        LikesSweep.Fetcher recording = new LikesSweep.Fetcher() {
            @Override public String fetch(String sort, String cursor) {
                startedFrom.add(cursor);
                return page("", null);
            }
        };

        LikesSweep.expandByAnchoredWalks(SORT, recording, NO_SLEEP, FIXED_CLOCK, store);

        assertTrue("walked without a reachable target: " + startedFrom, startedFrom.isEmpty());
    }

    /**
     * Nearest anchor, not earliest: both 7 and 27 share the target's residue,
     * and starting from 27 reaches it in one window where starting from 7
     * would spend two.
     */
    @Test public void anchoredWalkStartsFromTheNearestAnchorInTheTargetsResidue() {
        SweepStore store = new SweepStore();
        store.upsert("/photos/1/1/a07.jpeg", "realid0000000000000007", null, 7, 1L);
        store.upsert("/photos/1/1/a27.jpeg", "realid0000000000000027", null, 27, 1L);
        store.upsert("/photos/1/1/plain.jpeg", null, null, 47, 1L);

        final List<String> startedFrom = new ArrayList<String>();
        LikesSweep.Fetcher recording = new LikesSweep.Fetcher() {
            @Override public String fetch(String sort, String cursor) {
                startedFrom.add(cursor);
                return page("", null);
            }
        };

        LikesSweep.expandByAnchoredWalks(SORT, recording, NO_SLEEP, FIXED_CLOCK, store);

        assertEquals(1, startedFrom.size());
        assertEquals(Cursors.encode("realid0000000000000027"), startedFrom.get(0));
    }

    /**
     * Minting has to be verified from the very first window, not only from a
     * window the walk happens to continue past.
     *
     * <p>A steady-state pass stops on its first window with {@code
     * REACHED_KNOWN}, and that return used to come before the cursor was ever
     * compared against our own encoder -- so {@link Cursors#canMint} was
     * still false when the expansion ran, every aimed strategy refused
     * itself, and the pass fell through to the blind walks it exists to
     * avoid. Seen on device: "aiming did not finish" logged 0.6 s into a
     * pass, with "minting verified" arriving only afterwards.
     */
    @Test public void mintingIsVerifiedFromAWindowTheWalkStopsOn() {
        Cursors.resetMintVerification();
        String boundaryId = "boundaryid00000000000001";
        String boundaryCursor = Cursors.encode(boundaryId);

        ScriptedFetcher fetcher = new ScriptedFetcher();
        fetcher.byCursor.put(null, page(join(gated("/photos/a.jpeg")), boundaryCursor));

        SweepStore store = new SweepStore();
        store.upsert("/photos/a.jpeg", null, null, 0, 1L);     // already known -> stops at once

        LikesSweep.Result result = LikesSweep.sweep(SORT, fetcher, NO_SLEEP, FIXED_CLOCK, store);

        assertEquals(LikesSweep.StopReason.REACHED_KNOWN, result.reason);
        assertTrue("a pass that stops on a known window still saw a real cursor",
                Cursors.canMint());
    }

    @Test public void anchoredWalksAreRefusedWhileMintingIsUnverified() {
        Cursors.resetMintVerification();
        SweepStore store = new SweepStore();
        store.upsert("/photos/1/1/a19.jpeg", "realid0000000000000019", null, 19, 1L);

        final List<String> asked = new ArrayList<String>();
        LikesSweep.Fetcher recording = new LikesSweep.Fetcher() {
            @Override public String fetch(String sort, String cursor) {
                asked.add(cursor);
                return page("", null);
            }
        };

        assertEquals(0, LikesSweep.expandByAnchoredWalks(SORT, recording, NO_SLEEP, FIXED_CLOCK, store));
        assertTrue(asked.isEmpty());
    }

    @Test public void aWalkReportsEveryOffsetWithoutStoringTheOtherSortsOrder() {
        String c1 = "/photos/1/1/c1.jpeg", c2 = "/photos/1/1/c2.jpeg";
        String afterC2 = Cursors.encode("realid0000000000000002");
        java.util.Map<String, java.util.Map<String, String>> script =
                new java.util.HashMap<String, java.util.Map<String, String>>();
        script.put("B", pages(page(join(gated(c1), gated(c2)), afterC2), afterC2));

        SweepStore store = new SweepStore();
        store.upsert(c1, null, null, 70, 1L);       // the swept sort put c1 at 70
        store.upsert(c2, null, null, 71, 1L);

        final java.util.Map<String, Integer> seen = new java.util.HashMap<String, Integer>();
        LikesSweep.sweep("B", bySort(script), NO_SLEEP, FIXED_CLOCK, store, false, false, null, 0,
                new LikesSweep.PositionSink() {
                    @Override public void at(String photoPath, int position) {
                        seen.put(photoPath, Integer.valueOf(position));
                    }
                });

        assertEquals(Integer.valueOf(0), seen.get(c1));
        assertEquals(Integer.valueOf(1), seen.get(c2));
        // B's order was reported, never written over the swept sort's.
        assertEquals(70, store.get(c1).position);
        assertEquals(71, store.get(c2).position);
    }

    @Test public void anchoredWalksInOtherSortsStopOnceEveryCardHasAnId() {
        SweepStore store = new SweepStore();
        store.upsert("/photos/1/1/a.jpeg", "realid0000000000000001", null, 0, 1L);

        final List<String> asked = new ArrayList<String>();
        LikesSweep.Fetcher recording = new LikesSweep.Fetcher() {
            @Override public String fetch(String sort, String cursor) {
                asked.add(sort);
                return page("", null);
            }
        };

        int gained = LikesSweep.expandByAnchoredWalksInOtherSorts(
                java.util.Arrays.asList("A", "B"), "A", recording, NO_SLEEP, FIXED_CLOCK, store);

        assertEquals(0, gained);
        assertTrue("nothing to buy once every card is named", asked.isEmpty());
    }

    // ---- when a new nameless card arrives ----

    @Test public void anExpansionIsNotSpentTwiceOnTheSameStubbornCards() {
        LikesSweep.resetExpansionMemory();
        SweepStore store = new SweepStore();
        store.upsert("/photos/1/1/stubborn.jpeg", null, null, 3, 1L);

        assertTrue("an unnamed card has not been tried yet",
                LikesSweep.hasUntriedUnnamed(store));
        LikesSweep.rememberUnnamed(store);
        assertFalse("the same card must not buy a second expansion",
                LikesSweep.hasUntriedUnnamed(store));
    }

    @Test public void aNewNamelessCardBuysAnotherExpansion() {
        LikesSweep.resetExpansionMemory();
        SweepStore store = new SweepStore();
        store.upsert("/photos/1/1/stubborn.jpeg", null, null, 3, 1L);
        LikesSweep.rememberUnnamed(store);
        assertFalse(LikesSweep.hasUntriedUnnamed(store));

        // Exactly the case the sweep exists for: a like arrives, and the new
        // card has no name.
        store.upsert("/photos/1/1/arrived.jpeg", null, null, 0, 2L);

        assertTrue(LikesSweep.hasUntriedUnnamed(store));
    }

    @Test public void aNamedCardNeverBuysAnExpansion() {
        LikesSweep.resetExpansionMemory();
        SweepStore store = new SweepStore();
        store.upsert("/photos/1/1/x.jpeg", "realid0000000000000001", null, 19, 1L);
        store.rememberName("/photos/1/1/x.jpeg", "Roni", 2L);

        assertFalse(LikesSweep.hasUntriedUnnamed(store));
    }
}
