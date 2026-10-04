package com.smali_generator.likes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;

public class ResolverPolicyTest {

    @Test public void capAndDelayMatchTheSpec() {
        assertEquals(60, LikesIdentityResolver.MAX_REQUESTS);
        assertEquals(400L, LikesIdentityResolver.DELAY_MS);
    }

    // Review Focus #4: a second tap must not start a parallel traversal.
    @Test public void onlyOneResolutionRunsAtATime() {
        assertFalse(LikesIdentityResolver.isRunning());
        assertTrue(LikesIdentityResolver.beginIfIdle());
        assertTrue(LikesIdentityResolver.isRunning());
        assertFalse(LikesIdentityResolver.beginIfIdle());   // second tap refused
        LikesIdentityResolver.finish();
        assertFalse(LikesIdentityResolver.isRunning());
        assertTrue(LikesIdentityResolver.beginIfIdle());    // reusable after
        LikesIdentityResolver.finish();
    }

    @Test public void sortNamesDropTheApolloSentinel() {
        java.util.List<String> sorts = LikesIdentityResolver.usableSortNames(
                java.util.Arrays.asList("AGE_ASCENDING", "UNKNOWN__", "VIEWED_ME"));
        assertEquals(java.util.Arrays.asList("AGE_ASCENDING", "VIEWED_ME"), sorts);
    }

    // Mis-binding regression through the resolver path: the server's final
    // node was dropped by the parser, so the cursor must bind to nothing.
    @Test public void absorbDoesNotBindCursorToEarlierSurvivor() {
        IdentityStore store = IdentityStore.newForTest();
        PageParser.Page page = PageParser.parse(
                "{\"data\":{\"me\":{\"likesIncomingWithPreviews\":{\"data\":["
                        + "{\"primaryImage\":{\"square225\":\"https://c.example.com/photos/7/8/absorbA.jpeg?h=8\"}},"
                        + "{\"primaryImage\":{\"square225\":\"https://c.example.com/fuzzyphotos/z.jpeg?h=8\"}}],"
                        + "\"pageInfo\":{\"after\":\"YWJjZGVmZ2hpamtsbW5vcHFyc3R1dm8\",\"hasMore\":true,\"total\":2}}}}}");
        LikesIdentityResolver.absorb(page, store);
        assertNull(store.realIdFor("/photos/7/8/absorbA.jpeg"));
    }

    @Test public void absorbBindsCursorToTheTrueFinalEntry() {
        IdentityStore store = IdentityStore.newForTest();
        PageParser.Page page = PageParser.parse(
                "{\"data\":{\"me\":{\"likesIncomingWithPreviews\":{\"data\":["
                        + "{\"primaryImage\":{\"square225\":\"https://c.example.com/photos/7/8/absorbB.jpeg?h=8\"}}],"
                        + "\"pageInfo\":{\"after\":\"YWJjZGVmZ2hpamtsbW5vcHFyc3R1dm8\",\"hasMore\":true,\"total\":1}}}}}");
        LikesIdentityResolver.absorb(page, store);
        assertEquals(Cursors.decode("YWJjZGVmZ2hpamtsbW5vcHFyc3R1dm8"),
                store.realIdFor("/photos/7/8/absorbB.jpeg"));
    }

    // ---- walk policy, with the fetcher and the sleeper injected ----

    private static final java.util.List<String> SORTS =
            java.util.Arrays.asList("AGE_ASCENDING", "VIEWED_ME");
    private static final String NOISE = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dm8";

    // A frontier probe is a (sort, cursor) pair, and pairs run out once every
    // combination has been tried -- a page that keeps failing never hands back
    // a new cursor to extend the frontier with. These two tests need to keep
    // being offered a probe regardless of whether any fetch ever succeeds, so
    // they supply enough DISTINCT sort names that sorts alone outlast the cap.
    private static final java.util.List<String> MANY_SORTS;
    static {
        java.util.List<String> s = new ArrayList<String>();
        for (int i = 0; i < LikesIdentityResolver.MAX_REQUESTS + 5; i++) {
            s.add("sort" + i);
        }
        MANY_SORTS = s;
    }

    private static String page(String path, String after) {
        return "{\"data\":{\"me\":{\"likesIncomingWithPreviews\":{\"data\":["
                + "{\"primaryImage\":{\"square225\":\"https://c.example.com" + path + "?h=8\"}}],"
                + "\"pageInfo\":{\"after\":" + (after == null ? "null" : "\"" + after + "\"")
                + ",\"hasMore\":true,\"total\":9}}}}}";
    }

    private static final class CountingSleeper implements LikesIdentityResolver.Sleeper {
        int calls;
        @Override public void sleep(long ms) {
            assertEquals(LikesIdentityResolver.DELAY_MS, ms);
            calls++;
        }
    }

    @Test public void aNullPathIsAFailureToStartNotARace() {
        assertEquals(LikesIdentityResolver.Start.FAILED,
                LikesIdentityResolver.resolve(null, null, null));
    }

    @Test public void threeConsecutiveFailuresAbort() {
        final int[] fetches = {0};
        CountingSleeper failSleeper = new CountingSleeper();
        LikesIdentityResolver.Outcome out = LikesIdentityResolver.walk(IdentityStore.newForTest(),
                "/photos/walk/fail.jpeg", null, MANY_SORTS,
                new LikesIdentityResolver.Fetcher() {
                    @Override public String fetch(Frontier.Probe p) {
                        fetches[0]++;
                        return null;
                    }
                }, failSleeper);
        assertEquals(LikesIdentityResolver.Outcome.ABORTED_FAILURES, out);
        assertEquals(3, fetches[0]);
        // Spaced even through an outage -- that is exactly when hammering the
        // service is worst. The aborting failure returns before sleeping, so
        // 2 sleeps cover the 3 fetches (after fetch 1, after fetch 2).
        assertEquals(2, failSleeper.calls);
    }

    @Test public void aSuccessResetsTheFailureCount() {
        final int[] fetches = {0};
        CountingSleeper resetSleeper = new CountingSleeper();
        LikesIdentityResolver.Outcome out = LikesIdentityResolver.walk(IdentityStore.newForTest(),
                "/photos/walk/reset.jpeg", null, MANY_SORTS,
                new LikesIdentityResolver.Fetcher() {
                    @Override public String fetch(Frontier.Probe p) {
                        int n = ++fetches[0];
                        // fail, fail, ok, fail, fail, ok ... never three in a row
                        return n % 3 == 0 ? page("/photos/walk/n" + n + ".jpeg", "c" + n) : null;
                    }
                }, resetSleeper);
        assertEquals(LikesIdentityResolver.Outcome.GAVE_UP, out);
        assertEquals(LikesIdentityResolver.MAX_REQUESTS, fetches[0]);
        assertEquals(fetches[0], resetSleeper.calls);  // a failed fetch sleeps too
    }

    @Test public void stopsAsSoonAsTheTargetResolves() {
        final String target = "/photos/walk/target.jpeg";
        final int[] fetches = {0};
        CountingSleeper sleeper = new CountingSleeper();
        LikesIdentityResolver.Outcome out = LikesIdentityResolver.walk(IdentityStore.newForTest(),
                target, null, SORTS,
                new LikesIdentityResolver.Fetcher() {
                    @Override public String fetch(Frontier.Probe p) {
                        int n = ++fetches[0];
                        return n == 2 ? page(target, NOISE)
                                      : page("/photos/walk/other" + n + ".jpeg", "c" + n);
                    }
                }, sleeper);
        assertEquals(LikesIdentityResolver.Outcome.RESOLVED, out);
        assertEquals(2, fetches[0]);
        assertEquals(1, sleeper.calls);         // slept between requests, not after the last
    }

    @Test public void neverIssuesMoreProbesThanTheCap() {
        final int[] fetches = {0};
        LikesIdentityResolver.Outcome out = LikesIdentityResolver.walk(IdentityStore.newForTest(),
                "/photos/walk/never.jpeg", java.util.Arrays.asList("s1", "s2", "s3"), SORTS,
                new LikesIdentityResolver.Fetcher() {
                    @Override public String fetch(Frontier.Probe p) {
                        int n = ++fetches[0];
                        return page("/photos/walk/cap" + n + ".jpeg", "cur" + n);
                    }
                }, new CountingSleeper());
        assertEquals(LikesIdentityResolver.Outcome.GAVE_UP, out);
        assertEquals(LikesIdentityResolver.MAX_REQUESTS, fetches[0]);
    }

    @Test public void sleepsBetweenEveryRequest() {
        final int[] fetches = {0};
        CountingSleeper sleeper = new CountingSleeper();
        LikesIdentityResolver.walk(IdentityStore.newForTest(), "/photos/walk/sleepy.jpeg", null, SORTS,
                new LikesIdentityResolver.Fetcher() {
                    @Override public String fetch(Frontier.Probe p) {
                        int n = ++fetches[0];
                        return page("/photos/walk/sl" + n + ".jpeg", "cu" + n);
                    }
                }, sleeper);
        assertEquals(fetches[0], sleeper.calls);
    }

    @Test public void aThrowingFetcherPropagatesToTheThreadGuard() {
        // walk itself does not swallow: resolve()'s thread body is the one guard.
        try {
            LikesIdentityResolver.walk(IdentityStore.newForTest(), "/photos/walk/boom.jpeg", null, SORTS,
                    new LikesIdentityResolver.Fetcher() {
                        @Override public String fetch(Frontier.Probe p) {
                            throw new IllegalStateException("boom");
                        }
                    }, new CountingSleeper());
            org.junit.Assert.fail("expected the throwable to propagate");
        } catch (IllegalStateException expected) {
        }
    }

    @Test public void resolveSurvivesAThrowingCallbackAndReleasesTheGuard() throws Exception {
        // No transport is captured on the JVM, so the thread reports NOT_READY.
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final LikesIdentityResolver.Outcome[] seen = {null};
        assertEquals(LikesIdentityResolver.Start.STARTED,
                LikesIdentityResolver.resolve("/photos/walk/cb.jpeg", null,
                new LikesIdentityResolver.Callback() {
                    @Override public void onOutcome(LikesIdentityResolver.Outcome o) {
                        seen[0] = o;
                        done.countDown();
                        throw new RuntimeException("callback blew up");
                    }
                }));
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
        for (int i = 0; i < 100 && LikesIdentityResolver.isRunning(); i++) {
            Thread.sleep(20);
        }
        assertFalse(LikesIdentityResolver.isRunning());
        assertEquals(LikesIdentityResolver.Outcome.NOT_READY, seen[0]);
    }

    // Exercises resolve()'s OWN thread-level guard (not walk()'s bare
    // propagation, pinned above): an uncaught throwable here would otherwise
    // take the whole app down, since it runs on a worker thread.
    @Test public void resolveCatchesAThrowingFetcherAndReportsError() throws Exception {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final LikesIdentityResolver.Outcome[] seen = {null};
        assertEquals(LikesIdentityResolver.Start.STARTED, LikesIdentityResolver.resolveWith(
                "/photos/walk/thread-boom.jpeg", null, SORTS,
                new LikesIdentityResolver.Fetcher() {
                    @Override public String fetch(Frontier.Probe p) {
                        throw new IllegalStateException("boom on the worker thread");
                    }
                }, new CountingSleeper(),
                new java.util.concurrent.Callable<Boolean>() {
                    @Override public Boolean call() { return true; }
                },
                new LikesIdentityResolver.Callback() {
                    @Override public void onOutcome(LikesIdentityResolver.Outcome o) {
                        seen[0] = o;
                        done.countDown();
                    }
                }));
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(LikesIdentityResolver.Outcome.ERROR, seen[0]);
        for (int i = 0; i < 100 && LikesIdentityResolver.isRunning(); i++) {
            Thread.sleep(20);
        }
        assertFalse(LikesIdentityResolver.isRunning());
    }

    @Test public void resolveReportsALostRaceInsteadOfSilence() {
        assertTrue(LikesIdentityResolver.beginIfIdle());
        try {
            assertEquals(LikesIdentityResolver.Start.BUSY,
                    LikesIdentityResolver.resolve("/photos/walk/x.jpeg", null, null));
        } finally {
            LikesIdentityResolver.finish();
        }
    }
}
