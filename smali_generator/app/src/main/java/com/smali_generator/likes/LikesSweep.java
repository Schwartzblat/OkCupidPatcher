package com.smali_generator.likes;

import java.io.File;
import java.util.List;

/**
 * Walks {@code DESC_TIMESTAMP} (like-arrival order) from page 1, one 20-entry
 * window per request, mints the next window's cursor from the id {@code
 * pageInfo.after} names -- the previous window's own last entry -- and
 * records every entry it sees into {@link SweepStore}. Replaces per-card
 * search as the primary way identities get learned; {@link
 * LikesIdentityResolver}'s tap-driven walk remains the fallback for whatever
 * a pass has not named yet.
 *
 * <p>Why a full pass only ever names one entry in twenty, and why that still
 * accumulates: naming position {@code p} needs an anchor at {@code p - 20},
 * so a single pass only ever lands a boundary on positions 20, 40, 60... But
 * every new like shifts everyone down by one position, so successive passes
 * (triggered every time the Likes page loads) name a different residue
 * class, and coverage grows over time without this ever costing more than a
 * window per request. See NOTES.md's "Likes sweep store" section.
 *
 * <p><b>Stop rule</b>, checked once per window, in this order: the window
 * contains only entries already in the store ({@link
 * LikesSweep.StopReason#REACHED_KNOWN} -- the incremental part, so a later
 * pass is cheap); the server reports no further cursor ({@link
 * LikesSweep.StopReason#END_OF_LIST}); or the per-sweep request cap is hit
 * ({@link LikesSweep.StopReason#CAP}, a safety backstop, never a tuning
 * knob).
 *
 * <p>Automatic and silent by design (the deliberate posture change this
 * feature makes -- identities move to disk and collection stops being
 * tap-gated): triggered only from an actual Likes-page load ({@code
 * LikesCursorCapture} already sees every one), never at app startup and
 * never from a background timer. Shares {@link LikesIdentityResolver}'s
 * {@code RUNNING} guard, so a sweep and a tap-driven resolve can never both
 * have a request in flight. No identity, photo path or URL is ever logged
 * here -- {@link SweepStore}'s file is the only place that data lives; the
 * start/finish log lines below report counts only.
 */
public final class LikesSweep {

    /** The sort name the sweep walks. The app may spell it slightly differently build to build. */
    static final String SORT_HINT = "DESC_TIMESTAMP";

    /** Safety backstop, not a tuning knob: a full pass over ~120 entries costs about 6 requests. */
    public static final int MAX_REQUESTS_PER_SWEEP = 30;

    /**
     * How many nameless cards get a targeted seek in one pass. Each costs up
     * to {@link LikesIdentityResolver#MAX_REQUESTS}, so this bounds the pass;
     * the ordinary case after a full sweep is a single new like, i.e. one
     * target resolving in a handful of requests.
     */
    public static final int MAX_SEEK_TARGETS_PER_SWEEP = 5;

    /**
     * The whole expansion's request budget, shared by every walk it issues.
     *
     * <p>A backstop, not a tuning knob: aiming at the missing cards costs
     * about one request each (a response names exactly one card, so that is
     * the floor), and a cold start on a 123-card list measured well inside
     * this. It exists so that a list far larger than any measured, or a
     * server whose ordering keeps a card out of reach, cannot turn one page
     * load into an unbounded walk.
     */
    public static final int MAX_REQUESTS_PER_EXPANSION = 400;

    public static final long DELAY_MS = 400L;
    public static final int MAX_CONSECUTIVE_FAILURES = 3;

    static final String STORE_FILE_NAME = "likes_sweep_store.jsonl";

    public enum StopReason {
        REACHED_KNOWN,      // the window held nothing new -- incremental stop
        END_OF_LIST,        // the server reported no further cursor
        CAP,                // MAX_REQUESTS_PER_SWEEP reached
        FAILURES,           // MAX_CONSECUTIVE_FAILURES bad responses in a row
        SATISFIED,          // the caller's reason for walking is answered
        INTERRUPTED,
        NOT_READY,          // no sort to walk, or no transport yet
    }

    public static final class Result {
        public final StopReason reason;
        public final int requests;
        public final int entriesSeen;
        public final int entriesWithId;

        Result(StopReason reason, int requests, int entriesSeen, int entriesWithId) {
            this.reason = reason;
            this.requests = requests;
            this.entriesSeen = entriesSeen;
            this.entriesWithId = entriesWithId;
        }
    }

    interface Fetcher {
        String fetch(String sort, String cursor);
    }

    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    interface Clock {
        long now();
    }

    private static final Fetcher REAL_FETCHER = new Fetcher() {
        @Override public String fetch(String sort, String cursor) {
            return LikesIdentityResolver.fetchPage(sort, cursor);
        }
    };

    /**
     * The same walk with the list's "viewed you" entries excluded. Every
     * remaining person moves to a different offset, so each sort yields a
     * second, different set of window boundaries -- the cheapest source of
     * identities left once every sort has been walked once.
     */
    private static final Fetcher VIEWS_OFF_FETCHER = new Fetcher() {
        @Override public String fetch(String sort, String cursor) {
            return LikesIdentityResolver.fetchPage(sort, cursor, false);
        }
    };

    private static final Sleeper REAL_SLEEPER = new Sleeper() {
        @Override public void sleep(long ms) throws InterruptedException {
            Thread.sleep(ms);
        }
    };

    private static final Clock REAL_CLOCK = new Clock() {
        @Override public long now() {
            return System.currentTimeMillis();
        }
    };

    private LikesSweep() {
    }

    /**
     * Fire-and-forget, called from the Likes-page-load capture point. Never
     * blocks the caller and never throws into it: a lost race against a tap
     * resolve, or against another sweep already running, is a silent no-op
     * -- there will be another page load to try again on, and a pass that
     * never ran is strictly safer than two walks sharing one connection.
     */
    public static void triggerAsync() {
        try {
            if (!LikesIdentityResolver.isConfigured() && !LikesIdentityResolver.ensureConfigured()) {
                return;
            }
            if (!LikesIdentityResolver.beginIfIdle()) {
                return;
            }
        } catch (Throwable t) {
            return;
        }
        try {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        runDeviceSweep();
                    } catch (Throwable t) {
                        safeLog("LikesSweep: " + t.getClass().getName());
                    } finally {
                        // Both in a finally: the bar must never be left on
                        // screen by a sweep that threw, just as the shared
                        // guard must never be left stuck.
                        SweepProgress.finish();
                        LikesIdentityResolver.finish();
                    }
                }
            }, "likes-sweep").start();
        } catch (Throwable t) {
            LikesIdentityResolver.finish();     // never leave the shared guard stuck
        }
    }

    /**
     * The cards that were still unnamed when the last expansion gave up.
     *
     * <p>An expansion costs tens of requests, so it must not re-run on every
     * visit to the Likes tab. But it does have to run again when a *new*
     * nameless card arrives, which is the whole point of sweeping
     * incrementally. Remembering which cards the last expansion could not
     * name separates the two: a card already in this set has been tried and
     * will not be retried, while anything unnamed and absent from it is new
     * and buys another pass. Null until the first expansion has run.
     */
    private static volatile java.util.Set<String> triedAndStillUnnamed = null;

    /** The unnamed cards this sweep has not already spent an expansion on. */
    static synchronized boolean hasUntriedUnnamed(SweepStore store) {
        java.util.Set<String> tried = triedAndStillUnnamed;
        for (SweepStore.Record r : store.all()) {
            if (r.displayName == null && (tried == null || !tried.contains(r.photoPath))) {
                return true;
            }
        }
        return false;
    }

    /** Package-visible for tests: forget what has been tried, as a fresh process would. */
    static synchronized void resetExpansionMemory() {
        triedAndStillUnnamed = null;
    }

    static synchronized void rememberUnnamed(SweepStore store) {
        java.util.Set<String> remaining = new java.util.HashSet<String>();
        for (SweepStore.Record r : store.all()) {
            if (r.displayName == null) {
                remaining.add(r.photoPath);
            }
        }
        triedAndStillUnnamed = remaining;
    }

    /**
     * Walks every other sort the app offers, for the ids their window
     * boundaries give up.
     *
     * <p>A single sort can only ever name one card in twenty: the cursor
     * names a page's last entry, so the identities it yields are exactly the
     * entries at offsets 19, 39, 59 and so on. Re-walking that same sort
     * learns nothing new. A different sort permutes the same people, so its
     * boundaries fall on a different set of them -- which is the whole reason
     * this is worth tens of requests.
     *
     * <p>Positions are not recorded from these walks and the incremental stop
     * is disabled for them; see the {@code sweep} overload for why.
     *
     * @return how many cards gained an id that did not have one.
     */
    static int expandAcrossSorts(List<String> sorts, String primarySort, Fetcher fetcher,
                                 Sleeper sleeper, Clock clock, SweepStore store) {
        if (sorts == null) {
            return 0;
        }
        int before = store.countWithId();
        for (String other : sorts) {
            if (fullyIdentified(store)) {
                break;              // nothing left for a boundary to reveal
            }
            if (other == null || (primarySort != null && other.equalsIgnoreCase(primarySort))) {
                continue;
            }
            // Stops the moment this walk completes coverage, instead of
            // reading the rest of the list to learn nothing.
            sweep(other, fetcher, sleeper, clock, store, false, false, null, 0, null,
                    UNTIL_COMPLETE, MAX_REQUESTS_PER_SWEEP);
        }
        return store.countWithId() - before;
    }

    /** True once every card in the store carries an id. */
    static boolean fullyIdentified(SweepStore store) {
        return store.size() > 0 && store.countWithId() >= store.size();
    }

    private static final Continuation UNTIL_COMPLETE = new Continuation() {
        @Override public boolean more(SweepStore store) {
            return !fullyIdentified(store);
        }
    };

    /** The cards with no id yet, nearest the top of the swept sort first. */
    static List<String> unnamedPaths(SweepStore store) {
        List<SweepStore.Record> unnamed = new java.util.ArrayList<SweepStore.Record>();
        for (SweepStore.Record r : store.all()) {
            if (r != null && r.photoPath != null && r.realId == null) {
                unnamed.add(r);
            }
        }
        java.util.Collections.sort(unnamed, new java.util.Comparator<SweepStore.Record>() {
            @Override public int compare(SweepStore.Record a, SweepStore.Record b) {
                if (a.position != b.position) {
                    return a.position < b.position ? -1 : 1;
                }
                return a.photoPath.compareTo(b.photoPath);
            }
        });
        List<String> paths = new java.util.ArrayList<String>(unnamed.size());
        for (SweepStore.Record r : unnamed) {
            paths.add(r.photoPath);
        }
        return paths;
    }

    /**
     * The whole expansion, in cost order: aim first, walk blindly only if
     * aiming could not finish the job.
     *
     * <p>The order is the fix for what made a pass slow. Aiming at the cards
     * that are actually missing costs about one request each ({@link
     * TargetedResolve}); walking a sort from page 1 costs a request per window
     * whatever is missing, so it only pays while most of the list is unknown.
     * The old ladder ran the blind walks first and unconditionally, so one new
     * like cost 364 requests instead of the half-dozen the aim needs.
     *
     * @param viewsOn the ordinary fetcher
     * @param viewsOff the same query with {@code includeViews: false}, a
     *     second ordering of every sort for the cold-start case
     * @return how many cards gained an id
     */
    static int expand(String primarySort, List<String> sorts, Fetcher viewsOn, Fetcher viewsOff,
                      Sleeper sleeper, Clock clock, SweepStore store) {
        int before = store.countWithId();

        // 1. The swept sort alone, whose offsets are already on disk. This is
        //    the whole job for any card deep in the list.
        SweepProgress.phase(SweepProgress.Phase.AIMING);
        expandByAnchoredWalks(primarySort, viewsOn, sleeper, clock, store);

        // 2. Every other order, for the cards the swept sort cannot reach --
        //    above all a brand-new like, which sits at offset 0 there.
        if (!fullyIdentified(store)) {
            expandByAnchoredWalksInOtherSorts(sorts, primarySort, viewsOn, sleeper, clock, store);
        }

        // 3. Only now the blind walks. On a cold start they are what gives
        //    each residue its first anchor; afterwards they are never reached.
        if (!fullyIdentified(store)) {
            Log.w("LikesSweep: aiming did not finish -- walking the other sorts");
            SweepProgress.phase(SweepProgress.Phase.OTHER_SORTS);
            expandAcrossSorts(sorts, primarySort, viewsOn, sleeper, clock, store);
        }
        if (!fullyIdentified(store) && viewsOff != null) {
            SweepProgress.phase(SweepProgress.Phase.VIEWS_OFF);
            expandAcrossSorts(sorts, null, viewsOff, sleeper, clock, store);
        }

        // 4. With those seeds in hand, aim again at whatever is left.
        if (!fullyIdentified(store)) {
            SweepProgress.phase(SweepProgress.Phase.AIMING);
            expandByAnchoredWalksInOtherSorts(sorts, primarySort, viewsOn, sleeper, clock, store);
        }
        return store.countWithId() - before;
    }

    private static void aim(SweepStore store, String primarySort, List<String> sorts,
                            Fetcher fetcher, Sleeper sleeper, Clock clock) {
        List<String> targets = unnamedPaths(store);
        if (targets.isEmpty()) {
            return;
        }
        TargetedResolve.Result r = TargetedResolve.resolve(targets, store, primarySort, sorts,
                fetcher, sleeper, clock, MAX_REQUESTS_PER_EXPANSION);
        if (r.requests > 0) {
            Log.w("LikesSweep: aimed " + r.requests + " request(s), named " + r.named
                    + " of " + targets.size() + " target(s)");
        }
    }

    /**
     * The server's page size, measured. Only used to spread anchored walks
     * across distinct residues -- a wrong value would make them redundant,
     * never incorrect, since each walk's boundaries are still read from the
     * server's own cursors.
     */
    static final int WINDOW = 20;

    /** Reports each entry's offset in the sort being walked, without storing it. */
    interface PositionSink {
        void at(String photoPath, int position);
    }

    /**
     * Checked once per window, after it has been recorded: false ends the
     * walk with {@link StopReason#SATISFIED}.
     *
     * <p>Every walk used to run to the end of the list, which is why a pass
     * kept spending requests after it had learned everything it set out to --
     * 69 of 364 on the measured one-new-like case. A walk issued for a reason
     * now carries that reason and stops when it is answered.
     */
    interface Continuation {
        boolean more(SweepStore store);
    }

    /**
     * Names the cards no walk from the start of the swept sort can reach, by
     * aiming a walk at each one from the nearest already-known person in its
     * own residue.
     *
     * <p>This used to bucket every *known* id by residue and walk one per
     * bucket to the end of the list, which asked the wrong question: it spent
     * requests wherever an anchor happened to exist rather than wherever a
     * card was actually missing. Measured on a 123-card list with one card
     * missing at offset 0, it cost 121 requests and could not have named it
     * at all -- nothing is twenty places before offset 0. {@link
     * TargetedResolve} starts from the missing cards instead, so a residue
     * with nothing to find costs nothing.
     *
     * @return how many cards gained an id that did not have one.
     */
    static int expandByAnchoredWalks(String sort, Fetcher fetcher, Sleeper sleeper, Clock clock,
                                     SweepStore store) {
        int before = store.countWithId();
        aim(store, sort, sort == null
                ? java.util.Collections.<String>emptyList()
                : java.util.Collections.singletonList(sort), fetcher, sleeper, clock);
        return store.countWithId() - before;
    }

    /**
     * The same aim, allowed to use every other order as well -- which is what
     * a card inside the swept sort's first window needs, since nothing there
     * can be anchored on.
     *
     * <p>Where this used to walk each other sort from page 1 purely to learn
     * offsets (9 sorts x 7 requests, on top of the two passes that had already
     * fetched those same pages) and then walk one anchor per residue of each,
     * {@link TargetedResolve} walks a sort only until the window holding the
     * target appears, and only for as long as targets remain. Measured: 383
     * requests for 16 ids, before.
     *
     * @return how many cards gained an id that did not have one.
     */
    static int expandByAnchoredWalksInOtherSorts(List<String> sorts, String primarySort,
                                                 Fetcher fetcher, Sleeper sleeper, Clock clock,
                                                 SweepStore store) {
        int before = store.countWithId();
        aim(store, primarySort, sorts, fetcher, sleeper, clock);
        return store.countWithId() - before;
    }

    private static volatile boolean namesPrimed = false;

    /**
     * Makes the recovered names available to the render path, once.
     *
     * <p>Called from the Likes payload hook rather than from the sweep,
     * because the grid composes long before a sweep could finish -- and a
     * name that arrives after composition is a name the card never draws.
     * One small indexed read; everything after it is an in-memory lookup.
     */
    public static void primeNamesIfNeeded() {
        if (namesPrimed) {
            return;
        }
        synchronized (LikesSweep.class) {
            if (namesPrimed) {
                return;
            }
            try {
                android.content.Context ctx = appContext();
                if (ctx == null) {
                    return;                     // try again on the next page load
                }
                int loaded = CardDb.primeNames(ctx);
                namesPrimed = true;
                if (loaded > 0) {
                    Log.w("LikesSweep: " + loaded + " recovered name(s) available to the grid");
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** Lets a later page load pick up names a sweep has just learned. */
    static void invalidatePrimedNames() {
        namesPrimed = false;
    }

    private static void runDeviceSweep() {
        String sort = pickSweepSort(LikesIdentityResolver.configuredSorts());
        if (sort == null) {
            Log.w("LikesSweep: no usable sort to walk -- skipped");
            return;
        }
        android.content.Context ctx = appContext();
        if (ctx == null) {
            Log.w("LikesSweep: no files dir yet -- skipped");
            return;
        }
        File legacy = storeFile();
        SweepStore store = CardDb.load(ctx, legacy);
        // From here on every window this pass records is durable before the
        // next request goes out. A pass can run for a minute in the
        // background and the process can die at any point in it; writing
        // only at the end meant a pass that did not reach the end
        // contributed nothing, and the next one re-learned the same ids.
        installPersister(ctx, store, legacy);
        // Prime the in-memory, per-process IdentityStore with every identity
        // already on disk -- not only the ones this pass's own window
        // happens to revisit -- so the tap-driven fallback in OpenRealProfile
        // (which only ever reads IdentityStore) benefits from everything a
        // previous process already learned, without that file needing any
        // SweepStore-specific lookup of its own.
        primeIdentityStore(store);
        SweepProgress.start(store);
        Log.w("LikesSweep: starting");
        Result result = sweep(sort, REAL_FETCHER, REAL_SLEEPER, REAL_CLOCK, store);

        int gained = 0;
        int named = 0;
        if (hasUntriedUnnamed(store)) {
            List<String> sorts = LikesIdentityResolver.configuredSorts();
            // Aim at the cards that are missing, in cost order; see expand().
            gained = expand(sort, sorts, REAL_FETCHER, VIEWS_OFF_FETCHER, REAL_SLEEPER,
                    REAL_CLOCK, store);
            if (!fullyIdentified(store)) {
                // Last resort: the tap resolver's own blind frontier, for a
                // card no ordering managed to put twenty places after a
                // known one. Bounded by MAX_SEEK_TARGETS_PER_SWEEP.
                SweepProgress.phase(SweepProgress.Phase.SEEKING);
                gained += seekUnnamed(store, sorts, sort);
            }
            SweepProgress.phase(SweepProgress.Phase.NAMING);
            named += NameLookup.nameUnnamed(store);
            // Whatever is still nameless has now had a full expansion spent
            // on it; only a card that was not in the list at this point is
            // worth another one.
            rememberUnnamed(store);
        }
        SweepProgress.phase(SweepProgress.Phase.NAMING);
        named += NameLookup.nameUnnamed(store);
        store.flush();                  // anything the naming pass just learned
        for (SweepStore.Record r : store.all()) {
            if (r.displayName != null) {
                IdentityStore.get().rememberName(r.photoPath, r.displayName);
            }
        }
        if (named > 0) {
            invalidatePrimedNames();    // let the next page load pick them up
        }
        Log.w("LikesSweep: finished (" + result.reason + "), " + result.requests
                + " request(s), " + result.entriesSeen + " entr(ies) seen, "
                + result.entriesWithId + " with an id, " + store.size()
                + " total in store, " + store.countWithId() + " with an id overall, "
                + gained + " id(s) from the expansion, "
                + named + " newly named, " + store.countWithName() + " named overall");
    }

    /**
     * Points the store at the database, so {@link SweepStore#flush} writes
     * through to it.
     *
     * <p>Only the records that changed since the last flush are written, so a
     * flush per window is one small transaction rather than a rewrite of the
     * whole table.
     */
    private static void installPersister(final android.content.Context ctx, SweepStore store,
                                         final File legacy) {
        store.setPersister(new SweepStore.Persister() {
            @Override public void persist(java.util.Collection<SweepStore.Record> changed) {
                CardDb.saveChanged(ctx, changed, legacy);
            }
        });
    }

    /**
     * Names what it can by aiming at it, rather than by walking every sort
     * from the start.
     *
     * <p>Seeds {@link ObservationCache} with every offset and highlight
     * {@link SweepStore} already holds, then runs one targeted walk per
     * target. The seeding is the whole trick: {@link SeekPlanner}'s exact
     * seek needs an id placed one window before the target, and after a full
     * sweep the store has one for every card except those inside the first
     * window -- so most targets resolve in a single request instead of a
     * full cross-sort expansion.
     *
     * <p>A brand-new like is the exception: it sits at offset 0 in
     * {@code DESC_TIMESTAMP} and nothing is twenty places before it, so it
     * falls to the planner's attribute-keyed "bring into view" step in
     * another sort. Still a handful of requests, not hundreds.
     *
     * @return how many cards gained an id that did not have one
     */
    static int seekUnnamed(SweepStore store, List<String> sorts, String sweptSort) {
        if (store == null || sorts == null || sorts.isEmpty()) {
            return 0;
        }
        int before = store.countWithId();
        ObservationCache cache = ObservationCache.get();
        if (sweptSort != null) {
            cache.seed(sweptSort, store.all(), WINDOW);
        }
        List<String> targets = SeekTargets.pick(store, MAX_SEEK_TARGETS_PER_SWEEP);
        if (targets.isEmpty()) {
            return 0;
        }
        Log.w("LikesSweep: seeking " + targets.size() + " nameless card(s) by anchor");
        IdentityStore identities = IdentityStore.get();
        for (String photoPath : targets) {
            if (identities.realIdFor(photoPath) != null) {
                continue;                       // an earlier seek already named it
            }
            LikesIdentityResolver.Outcome outcome =
                    LikesIdentityResolver.seek(photoPath, sorts, cache);
            if (outcome == LikesIdentityResolver.Outcome.INTERRUPTED) {
                break;
            }
        }
        // Fold back whatever the walks learned. observeFromOtherSort, not
        // upsert: these ids came from other sorts' windows and must not move
        // the position column, which is the swept sort's own frame.
        long now = REAL_CLOCK.now();
        for (SweepStore.Record r : store.all()) {
            if (r.realId == null) {
                String found = identities.realIdFor(r.photoPath);
                if (found != null) {
                    store.observeFromOtherSort(r.photoPath, found, null, now);
                }
            }
        }
        int gained = store.countWithId() - before;
        Log.w("LikesSweep: anchored seek named " + gained + " card(s)");
        return gained;
    }

    /** Package-visible for tests: feeds every already-known identity on disk into {@link IdentityStore}. */
    static void primeIdentityStore(SweepStore store) {
        for (SweepStore.Record record : store.all()) {
            if (record.realId != null) {
                IdentityStore.get().rememberIdentity(record.photoPath, record.realId);
            }
        }
    }

    /** Picks the app's own name for like-arrival order, case-insensitively -- never hardcoded elsewhere. */
    static String pickSweepSort(List<String> sorts) {
        if (sorts == null) {
            return null;
        }
        for (String s : sorts) {
            if (s != null && s.equalsIgnoreCase(SORT_HINT)) {
                return s;
            }
        }
        return null;
    }

    private static android.content.Context appContext() {
        try {
            return com.smali_generator.utils.Utils.getApplicationContext();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The pre-SQLite JSON-lines store. Still located (never written) so
     * {@link CardDb} can migrate anything an earlier build collected; it
     * disappears after the first successful database write.
     */
    private static File storeFile() {
        try {
            android.content.Context ctx = appContext();
            if (ctx == null) {
                return null;
            }
            File dir = ctx.getFilesDir();
            return dir == null ? null : new File(dir, STORE_FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void safeLog(String message) {
        try {
            Log.w(message);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Package-visible for tests: the whole chaining/stop-rule policy, with
     * the world injected. One iteration is one window: fetch, record every
     * entry at its absolute offset ({@code position}, running from 0 at page
     * 1), decide whether to stop, else mint the next cursor from this
     * window's own {@code pageInfo.after} and continue.
     */
    static Result sweep(String sort, Fetcher fetcher, Sleeper sleeper, Clock clock, SweepStore store) {
        return sweep(sort, fetcher, sleeper, clock, store, true, true);
    }

    /**
     * As above, with the two things a secondary-sort walk needs to do
     * differently.
     *
     * @param recordPositions false for a sort other than the one the stored
     *     {@code position} is measured in -- see
     *     {@link SweepStore#observeFromOtherSort}.
     * @param stopOnKnown false when the walk is hunting ids rather than new
     *     cards. Every card is already known by then, so the incremental stop
     *     would fire on the first window and the walk would learn nothing.
     */
    static Result sweep(String sort, Fetcher fetcher, Sleeper sleeper, Clock clock, SweepStore store,
                        boolean recordPositions, boolean stopOnKnown) {
        return sweep(sort, fetcher, sleeper, clock, store, recordPositions, stopOnKnown, null, 0);
    }

    /**
     * As above, but resuming from a cursor the caller supplies rather than
     * from the start of the list.
     *
     * <p>This is what breaks the one-in-twenty ceiling. A walk from the start
     * always puts its window boundaries on offsets 19, 39, 59 and so on, so
     * it can only ever name that one residue class. Starting from the cursor
     * of a person already known to sit at offset {@code startPosition - 1}
     * moves the whole grid onto <em>their</em> residue instead, and the walk
     * names everyone in it from there to the end of the list.
     *
     * @param startPosition the absolute offset of the first entry the server
     *     will return, so recorded positions stay in the sort's own frame.
     */
    static Result sweep(String sort, Fetcher fetcher, Sleeper sleeper, Clock clock, SweepStore store,
                        boolean recordPositions, boolean stopOnKnown,
                        String startCursor, int startPosition) {
        return sweep(sort, fetcher, sleeper, clock, store, recordPositions, stopOnKnown,
                startCursor, startPosition, null);
    }

    /**
     * As above, additionally reporting every entry's offset to {@code sink}.
     *
     * <p>A secondary sort's offsets must not be stored -- only one ordering
     * fits in the position column -- but they are exactly what choosing
     * anchors in that sort needs, so they are handed to the caller instead
     * of being written down.
     */
    static Result sweep(String sort, Fetcher fetcher, Sleeper sleeper, Clock clock, SweepStore store,
                        boolean recordPositions, boolean stopOnKnown,
                        String startCursor, int startPosition, PositionSink sink) {
        return sweep(sort, fetcher, sleeper, clock, store, recordPositions, stopOnKnown,
                startCursor, startPosition, sink, null, MAX_REQUESTS_PER_SWEEP);
    }

    /**
     * As above, stopping as soon as {@code continuation} says the walk's
     * reason is answered, and within {@code maxRequests} rather than the
     * per-sweep backstop.
     *
     * <p>{@code maxRequests} is how a caller spreads one budget over several
     * walks: {@link TargetedResolve} issues a walk per target and must bound
     * the whole pass, not each walk separately.
     */
    static Result sweep(String sort, Fetcher fetcher, Sleeper sleeper, Clock clock, SweepStore store,
                        boolean recordPositions, boolean stopOnKnown,
                        String startCursor, int startPosition, PositionSink sink,
                        Continuation continuation, int maxRequests) {
        int requests = 0;
        int entriesSeen = 0;
        int entriesWithId = 0;
        int consecutiveFailures = 0;
        String cursor = startCursor;
        int position = startPosition;

        while (true) {
            if (requests >= maxRequests) {
                return new Result(StopReason.CAP, requests, entriesSeen, entriesWithId);
            }

            String body = fetcher.fetch(sort, cursor);
            requests++;
            PageParser.Page page = body == null ? null : PageParser.parse(body);
            if (page == null) {
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    return new Result(StopReason.FAILURES, requests, entriesSeen, entriesWithId);
                }
                if (!sleep(sleeper)) {
                    return new Result(StopReason.INTERRUPTED, requests, entriesSeen, entriesWithId);
                }
                continue;        // same cursor, worth one retry
            }
            consecutiveFailures = 0;
            // Before any stop check: a steady-state pass stops on its first
            // window, and every aimed strategy refuses itself until a real
            // cursor has confirmed our encoder reproduces the server's form.
            // Verifying this only on a window the walk continued past left
            // the whole expansion blind on exactly the common case.
            Cursors.observeServerCursor(page.after);

            // The same pure decision LikesCursorCapture and the tap resolver
            // use: the cursor names the page's actual final entry, skipped
            // nodes included, never re-derived here.
            BoundaryBinder.Decision boundary = BoundaryBinder.decide(page.after, page.rawEntries);

            long now = clock.now();
            boolean sawNewEntry = false;
            for (PageParser.Entry entry : page.entries) {
                entriesSeen++;
                String realId = (entry.realId != null && !IdentityStore.isPlaceholder(entry.realId))
                        ? entry.realId : null;
                if (realId == null && boundary.bound && boundary.path.equals(entry.photoPath)) {
                    realId = boundary.id;
                }
                if (realId != null) {
                    entriesWithId++;
                    IdentityStore.get().rememberIdentity(entry.photoPath, realId);
                }
                if (!store.contains(entry.photoPath)) {
                    sawNewEntry = true;
                }
                if (recordPositions) {
                    store.upsert(entry.photoPath, realId, entry.attributes, position + entry.index, now);
                } else {
                    store.observeFromOtherSort(entry.photoPath, realId, entry.attributes, now);
                }
                if (sink != null) {
                    sink.at(entry.photoPath, position + entry.index);
                }
            }

            // This window is durable before the next request goes out, so a
            // pass the process does not survive still contributes everything
            // up to its last completed window.
            store.flush();

            if (continuation != null && !continuation.more(store)) {
                return new Result(StopReason.SATISFIED, requests, entriesSeen, entriesWithId);
            }
            if (stopOnKnown && !page.entries.isEmpty() && !sawNewEntry) {
                return new Result(StopReason.REACHED_KNOWN, requests, entriesSeen, entriesWithId);
            }
            if (page.after == null) {
                return new Result(StopReason.END_OF_LIST, requests, entriesSeen, entriesWithId);
            }

            // Mint, rather than reuse verbatim: decode the id this window's
            // own boundary names, then re-encode it, so acceptance proves the
            // general mechanism (any known id can become a cursor), not just
            // that replaying the server's own opaque string works.
            // Check our minted form against the server's own cursor before
            // trusting it. An unrecognised cursor is not an error -- the
            // connection silently returns page 1 -- so without this the sweep
            // re-reads the same window and reports progress it did not make.
            String decodedId = Cursors.decode(page.after);
            String mintedCursor = (decodedId == null || !Cursors.canMint())
                    ? null : Cursors.encode(decodedId);
            if (mintedCursor == null) {
                return new Result(StopReason.END_OF_LIST, requests, entriesSeen, entriesWithId);
            }

            position += page.rawEntries.size();
            cursor = mintedCursor;

            if (!sleep(sleeper)) {
                return new Result(StopReason.INTERRUPTED, requests, entriesSeen, entriesWithId);
            }
        }
    }

    private static boolean sleep(Sleeper sleeper) {
        try {
            sleeper.sleep(DELAY_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
