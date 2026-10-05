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
    public static final long DELAY_MS = 400L;
    public static final int MAX_CONSECUTIVE_FAILURES = 3;

    static final String STORE_FILE_NAME = "likes_sweep_store.jsonl";

    public enum StopReason {
        REACHED_KNOWN,      // the window held nothing new -- incremental stop
        END_OF_LIST,        // the server reported no further cursor
        CAP,                // MAX_REQUESTS_PER_SWEEP reached
        FAILURES,           // MAX_CONSECUTIVE_FAILURES bad responses in a row
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
            if (other == null || (primarySort != null && other.equalsIgnoreCase(primarySort))) {
                continue;
            }
            sweep(other, fetcher, sleeper, clock, store, false, false);
        }
        return store.countWithId() - before;
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
     * Names the people no walk from the start of the list can reach, by
     * restarting the walk from someone already known.
     *
     * <p>One walk per residue class, from the earliest known anchor in each:
     * that anchor's walk names everyone sharing its residue from its own
     * offset onward, so a second anchor in the same class would only repeat
     * it. People at an offset below every known anchor of their class stay
     * out of reach -- there is nothing twenty places before them to anchor
     * on -- which is why the cross-sort passes run first.
     *
     * @return how many cards gained an id that did not have one.
     */
    static int expandByAnchoredWalks(String sort, Fetcher fetcher, Sleeper sleeper, Clock clock,
                                     SweepStore store) {
        if (!Cursors.canMint()) {
            return 0;
        }
        int before = store.countWithId();
        java.util.Map<Integer, SweepStore.Record> earliest =
                new java.util.TreeMap<Integer, SweepStore.Record>();
        for (SweepStore.Record r : store.all()) {
            if (r.realId == null) {
                continue;
            }
            Integer residue = Integer.valueOf(((r.position % WINDOW) + WINDOW) % WINDOW);
            SweepStore.Record held = earliest.get(residue);
            if (held == null || r.position < held.position) {
                earliest.put(residue, r);
            }
        }
        for (SweepStore.Record anchor : earliest.values()) {
            String cursor = Cursors.encode(anchor.realId);
            if (cursor == null) {
                continue;
            }
            sweep(sort, fetcher, sleeper, clock, store, true, false, cursor, anchor.position + 1);
        }
        return store.countWithId() - before;
    }

    /**
     * The last of the unnamed: people sitting too early in the swept sort for
     * any anchor to reach -- nothing is twenty places before them -- but not
     * necessarily early in some other sort.
     *
     * <p>For each other sort: walk it once to learn where everyone sits in
     * <em>its</em> order (reported, never stored -- only one ordering fits in
     * the position column), then run one anchored walk per residue of that
     * order. Stops as soon as every card has an id, so the common case costs
     * one sort rather than all of them.
     *
     * @return how many cards gained an id that did not have one.
     */
    static int expandByAnchoredWalksInOtherSorts(List<String> sorts, String primarySort,
                                                 Fetcher fetcher, Sleeper sleeper, Clock clock,
                                                 SweepStore store) {
        if (sorts == null || !Cursors.canMint()) {
            return 0;
        }
        int before = store.countWithId();
        for (String other : sorts) {
            if (other == null || (primarySort != null && other.equalsIgnoreCase(primarySort))) {
                continue;
            }
            if (store.countWithId() >= store.size()) {
                break;                          // everyone is named; nothing left to buy
            }
            final java.util.Map<String, Integer> offsets = new java.util.HashMap<String, Integer>();
            sweep(other, fetcher, sleeper, clock, store, false, false, null, 0,
                    new PositionSink() {
                        @Override public void at(String photoPath, int position) {
                            offsets.put(photoPath, Integer.valueOf(position));
                        }
                    });

            java.util.Map<Integer, SweepStore.Record> earliest =
                    new java.util.TreeMap<Integer, SweepStore.Record>();
            java.util.Map<SweepStore.Record, Integer> anchorOffset =
                    new java.util.HashMap<SweepStore.Record, Integer>();
            for (SweepStore.Record r : store.all()) {
                Integer offset = r.realId == null ? null : offsets.get(r.photoPath);
                if (offset == null) {
                    continue;
                }
                Integer residue = Integer.valueOf(offset.intValue() % WINDOW);
                SweepStore.Record held = earliest.get(residue);
                if (held == null || offset.intValue() < anchorOffset.get(held).intValue()) {
                    earliest.put(residue, r);
                    anchorOffset.put(r, offset);
                }
            }
            for (SweepStore.Record anchor : earliest.values()) {
                String cursor = Cursors.encode(anchor.realId);
                if (cursor == null) {
                    continue;
                }
                sweep(other, fetcher, sleeper, clock, store, false, false, cursor,
                        anchorOffset.get(anchor).intValue() + 1);
            }
        }
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
        // Every card this pass gave an id to -- and any left unnamed by an
        // earlier pass -- gets its name looked up before the store is
        // written, so a card is never left half-resolved on disk.
        // Cards the swept sort can never name on its own -- every offset that
        // is not a window boundary. Other sorts put different people on those
        // boundaries, so they are worth one pass per process.
        int gained = 0;
        int named = 0;
        if (hasUntriedUnnamed(store)) {
            List<String> sorts = LikesIdentityResolver.configuredSorts();
            // Aim before sweeping. Seeding the cache from what is already on
            // disk lets the planner compute the one anchor that puts a target
            // on a window boundary, which costs a request or two; the blind
            // expansion below costs hundreds and is now only the fallback.
            SweepProgress.phase(SweepProgress.Phase.SEEKING);
            gained = seekUnnamed(store, sorts, sort);
            if (store.size() > store.countWithId()) {
                SweepProgress.phase(SweepProgress.Phase.OTHER_SORTS);
                Log.w("LikesSweep: expanding across the other sorts");
                gained += expandAcrossSorts(sorts, sort, REAL_FETCHER, REAL_SLEEPER, REAL_CLOCK,
                        store);
            }
            if (store.size() > store.countWithId()) {
                // Second ordering of every sort, this one with the "viewed
                // you" entries dropped. The primary sort is included: with
                // views off its ordering is not the one already walked.
                SweepProgress.phase(SweepProgress.Phase.VIEWS_OFF);
                Log.w("LikesSweep: expanding again with views excluded");
                gained += expandAcrossSorts(sorts, null, VIEWS_OFF_FETCHER, REAL_SLEEPER,
                        REAL_CLOCK, store);
            }
            if (store.size() > store.countWithId()) {
                // Everyone the cross-sort passes named is now an anchor the
                // swept sort can walk forward from.
                SweepProgress.phase(SweepProgress.Phase.ANCHORED);
                Log.w("LikesSweep: expanding from anchors inside the swept sort");
                gained += expandByAnchoredWalks(sort, REAL_FETCHER, REAL_SLEEPER, REAL_CLOCK, store);
            }
            if (store.size() > store.countWithId()) {
                SweepProgress.phase(SweepProgress.Phase.ANCHORED_OTHER);
                Log.w("LikesSweep: expanding from anchors inside the other sorts");
                gained += expandByAnchoredWalksInOtherSorts(sorts, sort, REAL_FETCHER,
                        REAL_SLEEPER, REAL_CLOCK, store);
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
        CardDb.save(ctx, store, legacy);
        for (SweepStore.Record r : store.all()) {
            if (r.displayName != null) {
                IdentityStore.get().rememberName(r.photoPath, r.displayName);
            }
        }
        Log.w("LikesSweep: finished (" + result.reason + "), " + result.requests
                + " request(s), " + result.entriesSeen + " entr(ies) seen, "
                + result.entriesWithId + " with an id, " + store.size()
                + " total in store, " + store.countWithId() + " with an id overall, "
                + gained + " id(s) from other sorts, "
                + named + " newly named, " + store.countWithName() + " named overall");
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
        int requests = 0;
        int entriesSeen = 0;
        int entriesWithId = 0;
        int consecutiveFailures = 0;
        String cursor = startCursor;
        int position = startPosition;

        while (true) {
            if (requests >= MAX_REQUESTS_PER_SWEEP) {
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
            Cursors.observeServerCursor(page.after);
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
