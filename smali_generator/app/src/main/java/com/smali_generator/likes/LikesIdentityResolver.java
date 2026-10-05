package com.smali_generator.likes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Walks (sort, cursor) pairs until the wanted card is identified.
 *
 * Runs only from an explicit tap -- this is the fallback path now that
 * {@link LikesSweep} does most identification automatically and for free.
 * Nothing here is speculative or scheduled: each request is a deliberate
 * consequence of a user action, and the cap bounds how far one action can go.
 *
 * <p>{@link #RUNNING} (via {@link #beginIfIdle}/{@link #finish}) is also the
 * mutual-exclusion guard {@link LikesSweep} uses for its own walk, so a tap
 * and a sweep can never have requests in flight at the same time -- "one
 * request in flight" is a single process-wide invariant, not one kept
 * separately per feature.
 */
public final class LikesIdentityResolver {

    // Temporary device-diagnostic switch for the seek-planner investigation;
    // false is the shipped posture (no extra log spam per probe).
    private static final boolean DIAGNOSTIC_LOGGING = false;

    public static final int MAX_REQUESTS = 60;
    public static final long DELAY_MS = 400L;
    public static final int MAX_CONSECUTIVE_FAILURES = 3;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean LOGGED_UNCONFIGURED = new AtomicBoolean();

    private static volatile List<String> sortNames = new ArrayList<String>();
    private static volatile String operationName;
    private static volatile String document;

    private LikesIdentityResolver() {
    }

    /** Supplied by the device shell once, from the finder-discovered classes. */
    public static void configure(List<String> sorts, String opName, String doc) {
        sortNames = usableSortNames(sorts);
        operationName = opName;
        document = doc;
    }

    /** Whether {@link #configure} has already run (tap or sweep, whichever got there first). */
    public static boolean isConfigured() {
        return GraphQlTransport.isReady() && document != null;
    }

    /** The sort orders {@link #configure} was given, for {@link LikesSweep} to pick its walk order from. */
    public static List<String> configuredSorts() {
        return sortNames;
    }

    /**
     * Issues one raw page request outside the tap-driven walk -- the seam
     * {@link LikesSweep} uses to talk to the same endpoint, through the same
     * borrowed client and the same registered document, without duplicating
     * any transport plumbing. Returns null exactly as a failed fetch inside
     * {@link #walk} would -- not configured, not ready, or the request itself
     * failed are indistinguishable to the caller, which is the correct
     * posture: none of them should ever throw into the sweep's own code.
     */
    public static String fetchPage(String sort, String cursor) {
        return fetchPage(sort, cursor, true);
    }

    /**
     * As above, with the query's own {@code includeViews} variable exposed.
     *
     * <p>Turning it off drops the "viewed you" entries from the list, which
     * shifts everyone still in it to a different offset -- and the offsets
     * are what decide whose identity a page cursor gives up. So the same sort
     * walked with views off is, for this purpose, a fresh ordering rather
     * than a repeat. It is a variable of the registered document, not a
     * change to it.
     */
    public static String fetchPage(String sort, String cursor, boolean includeViews) {
        if (!isConfigured()) {
            return null;
        }
        try {
            return GraphQlTransport.post(operationName, document,
                    variables(new Frontier.Probe(sort, cursor), includeViews));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Lazily configures the resolver (and, by extension, {@link LikesSweep})
     * by reading the sort enum and the query document straight out of the
     * app, exactly as {@code OpenRealProfile}'s own first-tap configuration
     * does and for the same reason: this must never run from a hook's
     * {@code load()} (before {@code Application.onCreate}, it can poison a
     * class for the app's own later use), only from a real runtime event --
     * a tap or, here, a Likes page actually finishing a load. Safe to call
     * repeatedly: a no-op once {@link #isConfigured()} is already true, and a
     * retry (not a permanent failure) otherwise.
     */
    public static synchronized boolean ensureConfigured() {
        if (isConfigured()) {
            return true;
        }
        try {
            ClassLoader cl = LikesIdentityResolver.class.getClassLoader();
            Class<?> sortEnum = Class.forName("{{LIKES_SORT_ENUM_CLASS_NAME}}", true, cl);
            List<String> sorts = new ArrayList<String>();
            Object[] constants = sortEnum.getEnumConstants();
            for (Object c : constants != null ? constants : new Object[0]) {
                sorts.add(((Enum<?>) c).name());
            }

            Class<?> docHolder = Class.forName("{{LIKES_QUERY_DOC_CLASS_NAME}}", true, cl);
            java.lang.reflect.Method getDoc = docHolder.getMethod("{{LIKES_QUERY_DOC_METHOD_NAME}}");
            Object receiver = java.lang.reflect.Modifier.isStatic(getDoc.getModifiers())
                    ? null : companionInstanceOf(docHolder);
            String doc = (String) getDoc.invoke(receiver);

            // The document opens with "query <OperationName>(", the same
            // path segment the endpoint expects.
            String opName = doc.substring(doc.indexOf(' ') + 1, doc.indexOf('(')).trim();

            configure(sorts, opName, doc);
            return true;
        } catch (Throwable t) {
            if (LOGGED_UNCONFIGURED.compareAndSet(false, true)) {
                safeLog("LikesIdentityResolver: not configured -- " + t.getClass().getName());
            }
            return false;
        }
    }

    /** The getter lives on a Kotlin companion; its instance is a static field of the outer class. */
    private static Object companionInstanceOf(Class<?> type) throws Exception {
        Class<?> outer = type.getDeclaringClass();
        if (outer != null) {
            for (java.lang.reflect.Field f : outer.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && type.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f.get(null);
                }
            }
        }
        throw new IllegalStateException("no companion instance for " + type.getName());
    }

    public static List<String> usableSortNames(List<String> raw) {
        List<String> out = new ArrayList<String>();
        for (String name : raw) {
            if (name != null && !name.startsWith("UNKNOWN")) {
                out.add(name);          // Apollo's sentinel is not a sort order
            }
        }
        return out;
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static boolean beginIfIdle() {
        return RUNNING.compareAndSet(false, true);
    }

    public static void finish() {
        RUNNING.set(false);
    }

    /** How one traversal ended. Every value is surfaced to the user by the caller. */
    public enum Outcome {
        RESOLVED,           // the card's id is now known
        GAVE_UP,            // the request cap or the frontier ran out
        ABORTED_FAILURES,   // MAX_CONSECUTIVE_FAILURES bad responses in a row
        NOT_READY,          // no captured transport / no query document yet
        INTERRUPTED,
        ERROR               // an unexpected throwable; logged by class only
    }

    /** Whether {@code resolve} started a traversal, and if not, why. */
    public enum Start {
        STARTED,
        BUSY,               // another traversal holds the guard: a lost race
        FAILED              // nothing could be started (no path, or the thread would not start)
    }

    public interface Callback {
        void onOutcome(Outcome outcome);
    }

    /** One request. Default: the app's own client. Null result = a failed request. */
    interface Fetcher {
        String fetch(Frontier.Probe probe);
    }

    /** Default: the real Thread.sleep. */
    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    private static final Fetcher REAL_FETCHER = new Fetcher() {
        @Override public String fetch(Frontier.Probe probe) {
            return GraphQlTransport.post(operationName, document, variables(probe));
        }
    };

    private static final Sleeper REAL_SLEEPER = new Sleeper() {
        @Override public void sleep(long ms) throws InterruptedException {
            Thread.sleep(ms);
        }
    };

    /**
     * @param photoPath   the card to identify
     * @param seedCursors cursors the app already received; supplied by the caller so
     *                    the core never depends on the hook package
     * @param callback    told how the traversal ended, exactly once, from the
     *                    traversal thread
     * @return anything but STARTED means the callback is never invoked, so the
     *         caller must surface that itself
     */
    public static Start resolve(final String photoPath, final List<String> seedCursors,
                                final Callback callback) {
        return resolveWith(photoPath, seedCursors, sortNames, REAL_FETCHER, REAL_SLEEPER,
                new java.util.concurrent.Callable<Boolean>() {
                    @Override public Boolean call() {
                        // The document is null until the first tap's lazy
                        // configure() succeeds, so this conjunct is live: an
                        // unconfigured resolver reports NOT_READY.
                        return GraphQlTransport.isReady() && document != null;
                    }
                }, callback);
    }

    /**
     * Package-visible seam for tests: identical to {@link #resolve}, with the
     * readiness check, the fetcher and the sleeper all injected, so a test can
     * exercise the thread-level exception guard without a real transport.
     */
    static Start resolveWith(final String photoPath, final List<String> seedCursors,
                             final List<String> sorts, final Fetcher fetcher,
                             final Sleeper sleeper,
                             final java.util.concurrent.Callable<Boolean> isReady,
                             final Callback callback) {
        if (photoPath == null) {
            return Start.FAILED;
        }
        if (!beginIfIdle()) {
            return Start.BUSY;          // one at a time
        }
        try {
            new Thread(new Runnable() {
                @Override public void run() {
                    Outcome outcome;
                    try {
                        boolean readyNow;
                        try {
                            readyNow = isReady.call();
                        } catch (Throwable t) {
                            readyNow = false;
                        }
                        if (!readyNow) {
                            outcome = Outcome.NOT_READY;
                        } else {
                            outcome = walk(photoPath, seedCursors, sorts, fetcher, sleeper);
                        }
                    } catch (Throwable t) {
                        // An uncaught throwable on a worker thread takes the app down.
                        safeLog("LikesIdentityResolver: " + t.getClass().getName());
                        outcome = Outcome.ERROR;
                    }
                    try {
                        if (callback != null) {
                            callback.onOutcome(outcome);
                        }
                    } catch (Throwable t) {
                        safeLog("LikesIdentityResolver: callback " + t.getClass().getName());
                    } finally {
                        finish();
                    }
                }
            }, "likes-identity").start();
        } catch (Throwable t) {
            finish();                   // never leave the guard stuck
            safeLog("LikesIdentityResolver: thread start " + t.getClass().getName());
            return Start.FAILED;
        }
        return Start.STARTED;
    }

    private static void safeLog(String message) {
        try {
            Log.w(message);
        } catch (Throwable ignored) {
        }
    }

    /**
     * One targeted walk against the real world, for {@link LikesSweep}.
     *
     * <p>Deliberately {@link #walk} and not {@link #resolve}: resolve takes
     * the {@code RUNNING} guard and spawns a thread, and the sweep already
     * holds that guard on a thread of its own -- going through resolve would
     * only ever report BUSY against itself.
     *
     * @param cache carries what previous probes placed, so a second target
     *     benefits from the first one's windows
     */
    static Outcome seek(String photoPath, List<String> sorts, ObservationCache cache) {
        return walk(IdentityStore.get(), cache, photoPath, null, sorts,
                REAL_FETCHER, REAL_SLEEPER);
    }

    /** Package-visible for tests: the whole traversal policy, with the world injected. */
    static Outcome walk(String photoPath, List<String> seedCursors, List<String> sorts,
                        Fetcher fetcher, Sleeper sleeper) {
        return walk(IdentityStore.get(), ObservationCache.get(), photoPath, seedCursors, sorts,
                fetcher, sleeper);
    }

    /** Package-visible for tests: the same walk against an injected store, with a fresh cache. */
    static Outcome walk(IdentityStore store, String photoPath, List<String> seedCursors,
                        List<String> sorts, Fetcher fetcher, Sleeper sleeper) {
        return walk(store, new ObservationCache(), photoPath, seedCursors, sorts, fetcher, sleeper);
    }

    /** Package-visible for tests: the same walk against an injected store and cache. */
    static Outcome walk(IdentityStore store, ObservationCache cache, String photoPath,
                        List<String> seedCursors, List<String> sorts, Fetcher fetcher,
                        Sleeper sleeper) {
        if (store.realIdFor(photoPath) != null) {
            return Outcome.RESOLVED;
        }

        Frontier frontier = new Frontier(sorts, MAX_REQUESTS);
        if (seedCursors != null) {
            for (String cursor : seedCursors) {
                frontier.addCursor(cursor);
            }
        }

        int consecutiveFailures = 0;
        int plannedProbes = 0;
        Log.w("LikesIdentityResolver: walk start, cache holds " + cache.size()
                + " placed entr(ies), target " + (cache.hasTarget(photoPath) ? "already seen" : "unseen"));
        while (true) {
            // The planner's own candidate is tried first: BoundaryBinder is
            // still the only place a cursor gets BOUND to an identity, this
            // merely picks WHICH (sort, cursor) to ask for next. A spent
            // candidate (already issued, or the cap is gone) falls straight
            // through to the blind frontier for this same turn -- it costs
            // no extra request either way.
            SeekPlanner.Plan plan = SeekPlanner.plan(photoPath, sorts, cache);
            Frontier.Probe probe = plan == null ? null : frontier.tryProbe(plan.sort, plan.cursor);
            boolean planned = probe != null;
            if (planned) {
                plannedProbes++;
                Log.w("LikesIdentityResolver: planned probe #" + plannedProbes + " in " + probe.sort);
            } else {
                probe = frontier.next();
            }
            if (probe == null) {
                break;
            }

            String body = fetcher.fetch(probe);
            PageParser.Page page = body == null ? null : PageParser.parse(body);
            if (page == null) {
                // A single bad response is a blip; three in a row means the
                // session, the network or the document is wrong, and hammering
                // on is both useless and rude.
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    Log.w("LikesIdentityResolver: aborting after "
                            + consecutiveFailures + " consecutive failures");
                    return Outcome.ABORTED_FAILURES;
                }
            } else {
                consecutiveFailures = 0;
                absorb(probe, page, store, cache);
                frontier.addCursor(page.after);
                if (planned) {
                    // Page 1 of a sort starts at offset 0; a minted cursor the server
                    // ignored comes back as exactly that page.
                    PageParser.Entry first = page.entries.isEmpty() ? null : page.entries.get(0);
                    Integer firstOffset = first == null ? null : cache.offsetOf(probe.sort, first.photoPath);
                    Log.w("LikesIdentityResolver: planned probe response "
                            + (firstOffset != null && firstOffset.intValue() == 0 && first.index == 0
                                ? "FELL BACK TO PAGE 1 (minted cursor ignored)" : "was a real page"));
                }
                if (DIAGNOSTIC_LOGGING && cache.offsetOf(probe.sort, photoPath) != null) {
                    Log.w("LikesIdentityResolver: target spotted in " + probe.sort
                            + " at offset " + cache.offsetOf(probe.sort, photoPath));
                }
                if (store.realIdFor(photoPath) != null) {
                    Log.w("LikesIdentityResolver: resolved after "
                            + frontier.issued() + " request(s), " + plannedProbes + " planned");
                    return Outcome.RESOLVED;
                }
            }
            try {
                sleeper.sleep(DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Outcome.INTERRUPTED;
            }
        }
        Log.w("LikesIdentityResolver: gave up after " + frontier.issued()
                + " request(s), " + plannedProbes + " planned; "
                + store.identityCount() + " identit(ies) known");
        return Outcome.GAVE_UP;
    }

    /**
     * Package-visible for tests. The boundary decision is BoundaryBinder's,
     * never re-derived here: it sees every raw node, skipped ones included.
     * The same decision is also folded into the cache, so the planner learns
     * from the boundary exactly as the identity store does.
     */
    static void absorb(PageParser.Page page, IdentityStore store) {
        absorb(null, page, store, null);
    }

    static void absorb(Frontier.Probe probe, PageParser.Page page, IdentityStore store,
                       ObservationCache cache) {
        for (PageParser.Entry entry : page.entries) {
            if (entry.realId != null) {
                store.rememberIdentity(entry.photoPath, entry.realId);
            }
        }
        BoundaryBinder.Decision d = BoundaryBinder.decide(page.after, page.rawEntries);
        if (d.bound) {
            store.rememberIdentity(d.path, d.id);
        } else if (d.reason == BoundaryBinder.SkipReason.ID_MISMATCH
                || d.reason == BoundaryBinder.SkipReason.FINAL_ENTRY_NOT_A_CARD
                || d.reason == BoundaryBinder.SkipReason.FINAL_ENTRY_NO_PATH) {
            Log.w("LikesIdentityResolver: boundary bind skipped (" + d.reason + ")");
        }
        if (cache != null && probe != null) {
            cache.absorb(probe.sort, probe.cursor, page, d);
        }
    }

    private static String variables(Frontier.Probe probe) {
        return variables(probe, true);          // what the client itself always sends
    }

    private static String variables(Frontier.Probe probe, boolean includeViews) {
        try {
            org.json.JSONObject v = new org.json.JSONObject();
            v.put("sortOption", probe.sort);
            v.put("nextPageKey", probe.cursor == null ? org.json.JSONObject.NULL : probe.cursor);
            v.put("includeViews", includeViews);
            return v.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }
}
