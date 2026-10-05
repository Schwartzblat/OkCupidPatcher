package com.smali_generator.likes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

public class SweepProgressTest {

    /** Never leave a listener or a half-finished sweep visible to the next test. */
    @After public void clearGlobalState() {
        SweepProgress.setListener(null);
        SweepProgress.finish();
    }

    private static SweepStore storeWith(int total, int withId, int withName) {
        SweepStore store = new SweepStore();
        for (int i = 0; i < total; i++) {
            String path = "/photos/" + i + ".jpeg";
            String id = i < withId ? String.format("realid%016d", i) : null;
            store.upsert(path, id, null, i, 1000L);
            if (i < withName) {
                store.rememberName(path, "Name" + i, 2000L);
            }
        }
        return store;
    }

    /** Records what the overlay would have been told, in order. */
    private static final class Recorder implements SweepProgress.Listener {
        final List<SweepProgress.Snapshot> progress = new ArrayList<SweepProgress.Snapshot>();
        final List<SweepProgress.Snapshot> finished = new ArrayList<SweepProgress.Snapshot>();

        @Override public void onProgress(SweepProgress.Snapshot s) {
            progress.add(s);
        }

        @Override public void onFinished(SweepProgress.Snapshot s) {
            finished.add(s);
        }
    }

    // ---- fraction ----------------------------------------------------------

    @Test public void anEmptyStoreIsZeroNotADivideByZero() {
        SweepProgress.start(storeWith(0, 0, 0));
        assertEquals(0f, SweepProgress.current().fraction(), 0.0001f);
    }

    @Test public void walkingPhasesMeasureIdentifiedNotNamed() {
        // The correction the design rests on: naming happens in one pass at
        // the end, so a bar tracking names would sit frozen for the whole walk.
        SweepProgress.start(storeWith(100, 40, 5));
        assertEquals(0.40f, SweepProgress.current().fraction(), 0.0001f);
    }

    @Test public void theNamingPhaseMeasuresNamed() {
        SweepProgress.start(storeWith(100, 90, 25));
        SweepProgress.phase(SweepProgress.Phase.NAMING);
        assertEquals(0.25f, SweepProgress.current().fraction(), 0.0001f);
    }

    @Test public void everyWalkingPhaseMeasuresIdentified() {
        for (SweepProgress.Phase p : SweepProgress.Phase.values()) {
            if (p == SweepProgress.Phase.NAMING || p == SweepProgress.Phase.DONE) {
                continue;
            }
            SweepProgress.setListener(null);
            SweepProgress.finish();
            SweepProgress.start(storeWith(100, 40, 5));
            SweepProgress.phase(p);
            assertEquals(p.name(), 0.40f, SweepProgress.current().fraction(), 0.0001f);
        }
    }

    @Test public void fractionNeverExceedsOne() {
        SweepProgress.Snapshot s =
                new SweepProgress.Snapshot(SweepProgress.Phase.WALKING, 150, 0, 100);
        assertEquals(1f, s.fraction(), 0.0001f);
    }

    @Test public void fractionIsNeverNegative() {
        SweepProgress.Snapshot s =
                new SweepProgress.Snapshot(SweepProgress.Phase.WALKING, -5, 0, 100);
        assertEquals(0f, s.fraction(), 0.0001f);
    }

    // ---- caption -----------------------------------------------------------

    @Test public void aWalkingCaptionNamesThePhaseAndBothCounts() {
        SweepProgress.Snapshot s =
                new SweepProgress.Snapshot(SweepProgress.Phase.WALKING, 42, 5, 118);
        String caption = s.caption();
        assertTrue(caption, caption.contains(SweepProgress.Phase.WALKING.label()));
        assertTrue(caption, caption.contains("42"));
        assertTrue(caption, caption.contains("118"));
    }

    @Test public void theNamingCaptionShowsTheNameCountNotTheIdCount() {
        SweepProgress.Snapshot s =
                new SweepProgress.Snapshot(SweepProgress.Phase.NAMING, 118, 25, 118);
        String caption = s.caption();
        assertTrue(caption, caption.contains(SweepProgress.Phase.NAMING.label()));
        assertTrue(caption, caption.contains("25"));
    }

    @Test public void theTerminalCaptionReportsNamedOutOfTotal() {
        SweepProgress.Snapshot s =
                new SweepProgress.Snapshot(SweepProgress.Phase.DONE, 118, 118, 118);
        assertEquals("118 / 118 identified", s.caption());
    }

    @Test public void everyPhaseProducesANonEmptyCaption() {
        // Adding a Phase without deciding how it reads must be a conscious act.
        for (SweepProgress.Phase p : SweepProgress.Phase.values()) {
            String caption = new SweepProgress.Snapshot(p, 1, 1, 2).caption();
            assertNotNull(p.name(), caption);
            assertFalse(p.name(), caption.trim().isEmpty());
        }
    }

    @Test public void everyNonTerminalPhaseHasItsOwnLabel() {
        List<String> seen = new ArrayList<String>();
        for (SweepProgress.Phase p : SweepProgress.Phase.values()) {
            if (p == SweepProgress.Phase.DONE) {
                continue;
            }
            assertNotNull(p.name(), p.label());
            assertFalse(p.name(), p.label().trim().isEmpty());
            assertFalse("duplicate label: " + p.label(), seen.contains(p.label()));
            seen.add(p.label());
        }
    }

    // ---- lifecycle ---------------------------------------------------------

    @Test public void noSweepIsRunningBeforeStart() {
        assertNull(SweepProgress.current());
    }

    @Test public void noSweepIsRunningAfterFinish() {
        SweepProgress.start(storeWith(10, 3, 0));
        SweepProgress.finish();
        assertNull(SweepProgress.current());
    }

    @Test public void startBeginsInTheWalkingPhase() {
        SweepProgress.start(storeWith(10, 3, 0));
        assertEquals(SweepProgress.Phase.WALKING, SweepProgress.current().phase());
    }

    @Test public void currentTracksTheLiveStoreWithoutAnotherPublish() {
        // Why the overlay can tick on the main thread and see smooth counts
        // while sweep() itself stays untouched.
        SweepStore store = storeWith(100, 40, 0);
        SweepProgress.start(store);
        assertEquals(0.40f, SweepProgress.current().fraction(), 0.0001f);

        store.upsert("/photos/40.jpeg", "realid0000000000000040", null, 40, 3000L);

        assertEquals(0.41f, SweepProgress.current().fraction(), 0.0001f);
    }

    @Test public void startNotifiesTheListener() {
        Recorder r = new Recorder();
        SweepProgress.setListener(r);
        SweepProgress.start(storeWith(10, 1, 0));
        assertEquals(1, r.progress.size());
        assertEquals(SweepProgress.Phase.WALKING, r.progress.get(0).phase());
    }

    @Test public void eachPhaseChangeNotifiesTheListener() {
        Recorder r = new Recorder();
        SweepProgress.setListener(r);
        SweepProgress.start(storeWith(10, 1, 0));
        SweepProgress.phase(SweepProgress.Phase.OTHER_SORTS);
        SweepProgress.phase(SweepProgress.Phase.NAMING);
        assertEquals(3, r.progress.size());
        assertEquals(SweepProgress.Phase.OTHER_SORTS, r.progress.get(1).phase());
        assertEquals(SweepProgress.Phase.NAMING, r.progress.get(2).phase());
    }

    @Test public void finishReportsTheTerminalSnapshotOnce() {
        Recorder r = new Recorder();
        SweepProgress.setListener(r);
        SweepProgress.start(storeWith(100, 100, 118 - 18));
        SweepProgress.finish();
        assertEquals(1, r.finished.size());
        assertEquals(SweepProgress.Phase.DONE, r.finished.get(0).phase());
    }

    @Test public void finishCarriesTheFinalCountsBeforeTheStoreIsReleased() {
        Recorder r = new Recorder();
        SweepProgress.setListener(r);
        SweepProgress.start(storeWith(60, 60, 60));
        SweepProgress.finish();
        assertEquals(60, r.finished.get(0).named());
        assertEquals(60, r.finished.get(0).total());
    }

    @Test public void aSecondFinishIsSilent() {
        // runDeviceSweep calls finish() from a finally; a stray extra call
        // must not make the bar reappear or double-report.
        Recorder r = new Recorder();
        SweepProgress.setListener(r);
        SweepProgress.start(storeWith(10, 1, 0));
        SweepProgress.finish();
        SweepProgress.finish();
        assertEquals(1, r.finished.size());
    }

    @Test public void finishWithoutStartIsSilent() {
        Recorder r = new Recorder();
        SweepProgress.setListener(r);
        SweepProgress.finish();
        assertTrue(r.finished.isEmpty());
    }

    @Test public void aPhaseWithoutAStartNeverMakesABarAppear() {
        Recorder r = new Recorder();
        SweepProgress.setListener(r);
        SweepProgress.phase(SweepProgress.Phase.OTHER_SORTS);
        assertTrue(r.progress.isEmpty());
        assertNull(SweepProgress.current());
    }

    @Test public void aStartWithoutAStoreNeverMakesABarAppear() {
        Recorder r = new Recorder();
        SweepProgress.setListener(r);
        SweepProgress.start(null);
        assertTrue(r.progress.isEmpty());
        assertNull(SweepProgress.current());
    }

    // ---- the sweep must never die because the UI threw ---------------------

    @Test public void aThrowingListenerNeverReachesTheSweep() {
        SweepProgress.setListener(new SweepProgress.Listener() {
            @Override public void onProgress(SweepProgress.Snapshot s) {
                throw new RuntimeException("boom");
            }

            @Override public void onFinished(SweepProgress.Snapshot s) {
                throw new RuntimeException("boom");
            }
        });
        SweepProgress.start(storeWith(10, 1, 0));
        SweepProgress.phase(SweepProgress.Phase.NAMING);
        SweepProgress.finish();
        assertNull(SweepProgress.current());
    }
}
