package com.smali_generator.likes;

/**
 * How far along the running sweep is, and nothing else. Pure, so what the
 * user is told is pinned by tests rather than by the overlay's draw code --
 * the same split every other pair in this package takes.
 *
 * <p><b>What the bar measures.</b> Identities and names are learned by
 * different passes: {@link LikesSweep#sweep} records an id for every window
 * boundary it crosses, while a name costs a separate request and is only
 * looked up once the walking is over ({@link NameLookup#nameUnnamed}). A bar
 * tracking names would therefore sit frozen for the whole sweep and snap to
 * full at the very end. So the fill tracks {@code countWithId() / size()}
 * through every walking phase and switches to {@code countWithName() /
 * size()} only for {@link Phase#NAMING}, where that is the count actually
 * moving. The caption always names which one it is showing.
 *
 * <p><b>Why the store is held live.</b> {@link #current()} re-reads the store
 * on every call instead of caching the counts a publish happened to carry.
 * That is what lets the overlay tick on the main thread and see counts climb
 * continuously <em>between</em> phase boundaries, so {@code sweep()} needs no
 * progress callback threaded through any of its five overloads. Every {@link
 * SweepStore} method is {@code synchronized}, so reading it from the main
 * thread while the sweep thread writes is safe; nothing in SweepStore ever
 * calls back into this class, so the lock order here is the only one.
 *
 * <p>No identity, photo path or name ever passes through this class -- the
 * same posture {@link LikesSweep} takes. Counts only.
 */
public final class SweepProgress {

    /** The stages of a pass, in the order {@code runDeviceSweep} performs them. */
    public enum Phase {
        WALKING("Identifying…"),
        /** Aiming a window boundary at each card that has no id yet. */
        AIMING("Finding new cards…"),
        OTHER_SORTS("Checking other orders…"),
        VIEWS_OFF("Checking without views…"),
        /** The last resort, reached only when aiming could not place a card. */
        SEEKING("Searching…"),
        NAMING("Looking up names…"),
        /** The pass is over; the caption reads as a result, not an activity. */
        DONE("Done");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        /** What the user is shown while this phase runs. */
        public String label() {
            return label;
        }
    }

    /** An immutable reading. Safe to hand across threads. */
    public static final class Snapshot {
        private final Phase phase;
        private final int identified;
        private final int named;
        private final int total;

        public Snapshot(Phase phase, int identified, int named, int total) {
            this.phase = phase == null ? Phase.WALKING : phase;
            this.identified = identified;
            this.named = named;
            this.total = total;
        }

        public Phase phase() {
            return phase;
        }

        public int identified() {
            return identified;
        }

        public int named() {
            return named;
        }

        public int total() {
            return total;
        }

        /** The count this phase's bar is measuring -- see the class doc. */
        private int measured() {
            return (phase == Phase.NAMING || phase == Phase.DONE) ? named : identified;
        }

        /**
         * @return the fill, in [0, 1]. Zero for an empty store rather than a
         *     divide by zero: the first-ever sweep starts from nothing.
         */
        public float fraction() {
            if (total <= 0) {
                return 0f;
            }
            int measured = measured();
            if (measured <= 0) {
                return 0f;
            }
            if (measured >= total) {
                return 1f;
            }
            return (float) measured / (float) total;
        }

        public String caption() {
            if (phase == Phase.DONE) {
                return named + " / " + total + " identified";
            }
            return phase.label() + "  " + measured() + " / " + total;
        }
    }

    /**
     * The Android half, installed by the overlay. Same seam shape as {@link
     * Log.Sink}, so this file stays reachable from a JVM unit test.
     */
    public interface Listener {
        void onProgress(Snapshot snapshot);

        void onFinished(Snapshot snapshot);
    }

    private static Listener listener;
    private static SweepStore store;        // non-null exactly while a sweep is running
    private static Phase phase;

    private SweepProgress() {
    }

    public static synchronized void setListener(Listener l) {
        listener = l;
    }

    /**
     * A sweep has begun over {@code s}, which is held (not copied) until
     * {@link #finish}. A null store is ignored rather than showing a bar for
     * a sweep that cannot report anything.
     */
    public static void start(SweepStore s) {
        if (s == null) {
            return;
        }
        Snapshot snapshot;
        Listener l;
        synchronized (SweepProgress.class) {
            store = s;
            phase = Phase.WALKING;
            snapshot = read(Phase.WALKING, s);
            l = listener;
        }
        notifyProgress(l, snapshot);
    }

    /**
     * Moves to the next stage. A phase without a start is dropped: a stray
     * call must never be able to put a bar on screen.
     */
    public static void phase(Phase p) {
        if (p == null) {
            return;
        }
        Snapshot snapshot;
        Listener l;
        synchronized (SweepProgress.class) {
            if (store == null) {
                return;
            }
            phase = p;
            snapshot = read(p, store);
            l = listener;
        }
        notifyProgress(l, snapshot);
    }

    /**
     * The pass is over. Called from a {@code finally}, so it has to be
     * idempotent: a second call, or one without a start, reports nothing.
     * The terminal counts are read before the store reference is dropped.
     */
    public static void finish() {
        Snapshot terminal;
        Listener l;
        synchronized (SweepProgress.class) {
            if (store == null) {
                return;
            }
            terminal = read(Phase.DONE, store);
            store = null;
            phase = null;
            l = listener;
        }
        notifyFinished(l, terminal);
    }

    /** @return the live reading, or null when no sweep is running. */
    public static synchronized Snapshot current() {
        return store == null ? null : read(phase, store);
    }

    private static Snapshot read(Phase p, SweepStore s) {
        return new Snapshot(p, s.countWithId(), s.countWithName(), s.size());
    }

    /** A sweep must never die because the UI threw. */
    private static void notifyProgress(Listener l, Snapshot snapshot) {
        if (l == null) {
            return;
        }
        try {
            l.onProgress(snapshot);
        } catch (Throwable ignored) {
        }
    }

    private static void notifyFinished(Listener l, Snapshot snapshot) {
        if (l == null) {
            return;
        }
        try {
            l.onFinished(snapshot);
        } catch (Throwable ignored) {
        }
    }
}
