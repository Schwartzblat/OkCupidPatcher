package com.smali_generator.likes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

/**
 * The targeted resolver: name the specific cards that have no id, rather than
 * re-walking every sort in the hope that a boundary lands on one of them.
 */
public class TargetedResolveTest {

    @Before public void mintingIsVerified() {
        Cursors.resetMintVerification();
        Cursors.observeServerCursor(Cursors.encode("abcdefghijklmnopqrstuvw"));
        Log.setSink(null);
    }

    // ---- the pure arithmetic -------------------------------------------

    /** Ids known at offsets 0, 20 and 40 only. */
    private static TargetedResolve.Ids knownAt(final Integer... offsets) {
        final Map<Integer, String> ids = new HashMap<Integer, String>();
        for (Integer o : offsets) {
            ids.put(o, "id" + o);
        }
        return new TargetedResolve.Ids() {
            @Override public String idAt(int offset) {
                return ids.get(Integer.valueOf(offset));
            }
        };
    }

    /**
     * To make a window of 20 end exactly on offset 60, the anchor must sit at
     * 40. With 40 known, that is the answer -- not 20 and not 0, which would
     * each cost an extra request to walk through.
     */
    @Test public void anchorsOnTheNearestKnownOffsetInTheTargetsResidue() {
        assertEquals(40, TargetedResolve.anchorOffsetFor(60, 20, knownAt(0, 20, 40)));
    }

    /** Only a farther anchor is known: the walk is longer, but still possible. */
    @Test public void fallsBackToAFartherAnchorInTheSameResidue() {
        assertEquals(0, TargetedResolve.anchorOffsetFor(60, 20, knownAt(0)));
    }

    /** An anchor in a different residue can never put a boundary on the target. */
    @Test public void refusesAnAnchorOutsideTheTargetsResidue() {
        assertEquals(-1, TargetedResolve.anchorOffsetFor(60, 20, knownAt(37, 51)));
    }

    /**
     * Nothing is twenty places before a card inside the first window, which is
     * exactly the case a brand-new like is in.
     */
    @Test public void refusesATargetInsideTheFirstWindow() {
        assertEquals(-1, TargetedResolve.anchorOffsetFor(7, 20, knownAt(0, 20, 40)));
    }

    // ---- against the simulated server ----------------------------------

    private static final int PEOPLE = 123;

    /** A store that knows everyone's position, and everyone's id except {@code unknownPaths}. */
    private static SweepStore storeKnowing(FakeLikesServer server, String... unknownPaths) {
        List<String> unknown = Arrays.asList(unknownPaths);
        SweepStore store = new SweepStore();
        List<Integer> order = server.orders.get(FakeLikesServer.PRIMARY);
        for (int pos = 0; pos < order.size(); pos++) {
            FakeLikesServer.Person p = server.people.get(order.get(pos).intValue());
            boolean known = !unknown.contains(p.path);
            store.upsert(p.path, known ? p.id : null, null, pos, 1000L);
        }
        return store;
    }

    /**
     * The common case after a full pass: one card has no id, its position is
     * on disk, and the person twenty places before it is already known. That
     * is a single request -- not a re-walk of ten sorts.
     */
    @Test public void namesACardInOneRequestWhenItsAnchorIsAlreadyKnown() {
        FakeLikesServer server = new FakeLikesServer(FakeLikesServer.roster(PEOPLE), true, 11L);
        String target = server.at(FakeLikesServer.PRIMARY, 60).path;
        SweepStore store = storeKnowing(server, target);

        TargetedResolve.Result result = TargetedResolve.resolve(
                Arrays.asList(target), store, FakeLikesServer.PRIMARY, FakeLikesServer.SORTS,
                server, FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, 60);

        assertEquals(1, result.requests);
        assertEquals(1, result.named);
        assertEquals(server.at(FakeLikesServer.PRIMARY, 60).id, store.get(target).realId);
    }

    /**
     * Two cards in the same residue, twenty apart, neither known: the walk
     * from the nearest usable anchor names both on its way through -- two
     * requests for two cards, which is the floor (one boundary per response).
     */
    @Test public void chainsThroughAnUnknownAnchorNamingBothCards() {
        FakeLikesServer server = new FakeLikesServer(FakeLikesServer.roster(PEOPLE), true, 11L);
        String nearer = server.at(FakeLikesServer.PRIMARY, 40).path;
        String farther = server.at(FakeLikesServer.PRIMARY, 60).path;
        SweepStore store = storeKnowing(server, nearer, farther);

        TargetedResolve.Result result = TargetedResolve.resolve(
                Arrays.asList(farther, nearer), store, FakeLikesServer.PRIMARY,
                FakeLikesServer.SORTS, server, FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, 60);

        assertEquals(2, result.requests);
        assertEquals(2, result.named);
        assertNotNull(store.get(nearer).realId);
        assertNotNull(store.get(farther).realId);
    }

    /**
     * A brand-new like sits at offset 0 of the swept sort, where no anchor can
     * reach it. The resolver must go and find it in another order -- in a
     * handful of requests, not the ~360 a full ladder costs.
     */
    @Test public void walksAnotherSortToReachACardAtTheHeadOfTheSweptSort() {
        FakeLikesServer server = new FakeLikesServer(FakeLikesServer.roster(PEOPLE), true, 11L);
        String target = server.at(FakeLikesServer.PRIMARY, 0).path;
        SweepStore store = storeKnowing(server, target);

        TargetedResolve.Result result = TargetedResolve.resolve(
                Arrays.asList(target), store, FakeLikesServer.PRIMARY, FakeLikesServer.SORTS,
                server, FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, 60);

        assertEquals(1, result.named);
        assertNotNull(store.get(target).realId);
        assertTrue("one new like must cost a handful of requests, was " + result.requests,
                result.requests <= 15);
    }

    /** The budget is the only bound on an unreachable target, and it is honoured. */
    @Test public void stopsAtTheBudget() {
        FakeLikesServer server = new FakeLikesServer(FakeLikesServer.roster(PEOPLE), true, 11L);
        // Every card unknown: nothing can be anchored on, so the resolver
        // explores until the budget runs out.
        SweepStore store = new SweepStore();
        List<Integer> order = server.orders.get(FakeLikesServer.PRIMARY);
        List<String> targets = new ArrayList<String>();
        for (int pos = 0; pos < order.size(); pos++) {
            FakeLikesServer.Person p = server.people.get(order.get(pos).intValue());
            store.upsert(p.path, null, null, pos, 1000L);
            targets.add(p.path);
        }

        TargetedResolve.Result result = TargetedResolve.resolve(
                targets, store, FakeLikesServer.PRIMARY, FakeLikesServer.SORTS,
                server, FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, 10);

        assertTrue("budget exceeded: " + result.requests, result.requests <= 10);
    }

    /** Nothing to do must cost nothing. */
    @Test public void spendsNothingWhenEveryTargetIsAlreadyNamed() {
        FakeLikesServer server = new FakeLikesServer(FakeLikesServer.roster(PEOPLE), true, 11L);
        SweepStore store = storeKnowing(server);

        TargetedResolve.Result result = TargetedResolve.resolve(
                Arrays.asList(server.at(FakeLikesServer.PRIMARY, 5).path), store,
                FakeLikesServer.PRIMARY, FakeLikesServer.SORTS, server,
                FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, 60);

        assertEquals(0, result.requests);
        assertEquals(0, result.named);
    }

    /** A minted cursor the server does not recognise must not be mistaken for progress. */
    @Test public void doesNotMintWhenMintingIsUnverified() {
        Cursors.resetMintVerification();
        FakeLikesServer server = new FakeLikesServer(FakeLikesServer.roster(PEOPLE), true, 11L);
        String target = server.at(FakeLikesServer.PRIMARY, 60).path;
        SweepStore store = storeKnowing(server, target);

        TargetedResolve.Result result = TargetedResolve.resolve(
                Arrays.asList(target), store, FakeLikesServer.PRIMARY, FakeLikesServer.SORTS,
                server, FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, 60);

        assertEquals(0, result.requests);
        assertNull(store.get(target).realId);
    }
}
