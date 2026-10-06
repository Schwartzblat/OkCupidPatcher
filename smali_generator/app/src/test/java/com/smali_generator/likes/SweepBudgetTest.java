package com.smali_generator.likes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * What a pass is allowed to cost. Every number here was measured against the
 * simulated server before it was pinned, and the ones marked "was N" are what
 * the blind ladder actually spent on the same input.
 */
public class SweepBudgetTest {

    private static final int PEOPLE = 123;

    @Before public void mintingIsVerified() {
        Cursors.resetMintVerification();
        Cursors.observeServerCursor(Cursors.encode("abcdefghijklmnopqrstuvw"));
        Log.setSink(null);
    }

    /** A store holding everyone's position, and everyone's id except the given offsets. */
    private static SweepStore storeMissing(FakeLikesServer server, int... missingOffsets) {
        SweepStore store = new SweepStore();
        List<Integer> order = server.orders.get(FakeLikesServer.PRIMARY);
        for (int pos = 0; pos < order.size(); pos++) {
            FakeLikesServer.Person p = server.people.get(order.get(pos).intValue());
            boolean missing = false;
            for (int m : missingOffsets) {
                if (m == pos) {
                    missing = true;
                    break;
                }
            }
            store.upsert(p.path, missing ? null : p.id, null, pos, 1000L);
            if (!missing) {
                store.rememberName(p.path, "name" + pos, 1000L);
            }
        }
        return store;
    }

    /**
     * Nothing is twenty places before offset 0, so no anchored walk in the
     * swept sort can ever name a brand-new like. Spending requests to discover
     * that every time one arrives was the single biggest waste in a pass.
     *
     * <p>Was 121 requests, 0 ids.
     */
    @Test public void anchoredWalksSpendNothingWhenTheOnlyUnnamedCardIsInTheFirstWindow() {
        FakeLikesServer server = new FakeLikesServer(FakeLikesServer.roster(PEOPLE), true, 11L);
        SweepStore store = storeMissing(server, 0);

        int gained = LikesSweep.expandByAnchoredWalks(FakeLikesServer.PRIMARY, server,
                FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, store);

        assertEquals(0, server.requests);
        assertEquals(0, gained);
    }

    /** Was 121 requests. Nothing is missing, so nothing is worth asking for. */
    @Test public void anchoredWalksSpendNothingWhenEveryCardIsNamed() {
        FakeLikesServer server = new FakeLikesServer(FakeLikesServer.roster(PEOPLE), true, 11L);
        SweepStore store = storeMissing(server);

        LikesSweep.expandByAnchoredWalks(FakeLikesServer.PRIMARY, server,
                FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, store);

        assertEquals(0, server.requests);
    }

    /**
     * One card missing at offset 60: a walk anchored at 40 ends its first
     * window exactly on it. One request -- and then the walk stops, instead of
     * running to the end of the list.
     */
    @Test public void anchoredWalksStopOnceTheirResiduesTargetsAreNamed() {
        FakeLikesServer server = new FakeLikesServer(FakeLikesServer.roster(PEOPLE), true, 11L);
        SweepStore store = storeMissing(server, 60);

        int gained = LikesSweep.expandByAnchoredWalks(FakeLikesServer.PRIMARY, server,
                FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, store);

        assertEquals(1, gained);
        assertEquals(1, server.requests);
    }

    /** Was 63 requests across nine sorts, all of them after full coverage. */
    @Test public void crossSortExpansionSpendsNothingWhenEveryCardHasAnId() {
        FakeLikesServer server = new FakeLikesServer(FakeLikesServer.roster(PEOPLE), true, 11L);
        SweepStore store = storeMissing(server);

        LikesSweep.expandAcrossSorts(FakeLikesServer.SORTS, FakeLikesServer.PRIMARY, server,
                FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, store);

        assertEquals(0, server.requests);
    }

    /**
     * The cross-sort walks exist to seed residues; once every card has an id
     * there is nothing left to seed, so the loop must not keep walking the
     * remaining sorts.
     */
    @Test public void crossSortExpansionStopsAtTheSortThatCompletesCoverage() {
        FakeLikesServer server = new FakeLikesServer(FakeLikesServer.roster(PEOPLE), true, 11L);
        SweepStore store = storeMissing(server, 7);
        server.watched = store;

        LikesSweep.expandAcrossSorts(FakeLikesServer.SORTS, FakeLikesServer.PRIMARY, server,
                FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, store);

        assertEquals("kept walking after the last card was identified",
                0, server.requestsAfterFullCoverage);
    }

    /**
     * The headline: one new like on an otherwise complete store. Was 364
     * requests (2.4 minutes of background traffic) to name one card.
     */
    @Test public void oneNewLikeCostsAHandfulOfRequests() {
        FakeLikesServer server = new FakeLikesServer(FakeLikesServer.roster(PEOPLE), true, 11L);
        SweepStore store = storeMissing(server, 0);

        LikesSweep.Result result = LikesSweep.sweep(FakeLikesServer.PRIMARY, server,
                FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, store);
        assertEquals(LikesSweep.StopReason.REACHED_KNOWN, result.reason);

        int gained = LikesSweep.expand(FakeLikesServer.PRIMARY, FakeLikesServer.SORTS, server,
                server, FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, store);

        assertEquals(1, gained);
        assertEquals(PEOPLE, store.countWithId());
        assertTrue("one new like cost " + server.requests + " request(s)", server.requests <= 20);
    }

    /**
     * A cold start still has to do real work -- every unknown card needs its
     * own boundary, so 117 of them cannot cost less than 117 requests -- but
     * it must stay near that floor rather than at five times it.
     *
     * <p>Was 609 requests (4.1 minutes), and that excluded the per-target
     * seek phase.
     */
    @Test public void aColdStartStaysNearTheOneRequestPerCardFloor() {
        FakeLikesServer views = new FakeLikesServer(FakeLikesServer.roster(PEOPLE), true, 11L);
        FakeLikesServer noViews = new FakeLikesServer(views.people, false, 11L);
        SweepStore store = new SweepStore();

        LikesSweep.sweep(FakeLikesServer.PRIMARY, views, FakeLikesServer.NO_SLEEP,
                FakeLikesServer.CLOCK, store);
        LikesSweep.expand(FakeLikesServer.PRIMARY, FakeLikesServer.SORTS, views, noViews,
                FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, store);

        int total = views.requests + noViews.requests;
        assertEquals("every card identified", PEOPLE, store.countWithId());
        assertTrue("cold start cost " + total + " requests", total <= 300);
    }
}
