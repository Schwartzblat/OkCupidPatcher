package com.smali_generator.likes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

/**
 * Progress reaches the database as it is learned, not only when a pass ends.
 *
 * <p>A pass can run for minutes in the background; the process can be killed
 * at any point in it. Writing once at the end means a pass that is killed at
 * its last request contributes nothing, and the next one starts from the same
 * six ids as the last -- which is what makes the feature look like it never
 * finishes.
 */
public class SweepPersistenceTest {

    @Before public void mintingIsVerified() {
        Cursors.resetMintVerification();
        Cursors.observeServerCursor(Cursors.encode("abcdefghijklmnopqrstuvw"));
        Log.setSink(null);
    }

    /** Records every flush it is handed, so a test can count them and read their contents. */
    private static final class RecordingPersister implements SweepStore.Persister {
        final List<List<String>> flushes = new ArrayList<List<String>>();

        @Override public void persist(Collection<SweepStore.Record> changed) {
            List<String> paths = new ArrayList<String>();
            for (SweepStore.Record r : changed) {
                paths.add(r.photoPath);
            }
            flushes.add(paths);
        }

        int totalRecordsWritten() {
            int n = 0;
            for (List<String> f : flushes) {
                n += f.size();
            }
            return n;
        }

        Set<String> allPathsWritten() {
            Set<String> all = new HashSet<String>();
            for (List<String> f : flushes) {
                all.addAll(f);
            }
            return all;
        }
    }

    // ---- the store's own contract ---------------------------------------

    @Test public void flushPassesOnlyWhatChangedSinceTheLastFlush() {
        SweepStore store = new SweepStore();
        RecordingPersister persister = new RecordingPersister();
        store.setPersister(persister);

        store.upsert("/photos/a.jpeg", "ida00000000000000000000", null, 0, 1L);
        store.upsert("/photos/b.jpeg", null, null, 1, 1L);
        store.flush();

        store.upsert("/photos/c.jpeg", null, null, 2, 2L);
        store.flush();

        assertEquals(2, persister.flushes.size());
        assertEquals(2, persister.flushes.get(0).size());
        assertEquals(1, persister.flushes.get(1).size());
        assertEquals("/photos/c.jpeg", persister.flushes.get(1).get(0));
    }

    @Test public void flushWithNothingChangedDoesNotCallThePersister() {
        SweepStore store = new SweepStore();
        RecordingPersister persister = new RecordingPersister();
        store.setPersister(persister);

        store.flush();
        store.upsert("/photos/a.jpeg", null, null, 0, 1L);
        store.flush();
        store.flush();

        assertEquals(1, persister.flushes.size());
    }

    /** A name learned after the id is a change of its own. */
    @Test public void aLearnedNameIsFlushed() {
        SweepStore store = new SweepStore();
        RecordingPersister persister = new RecordingPersister();
        store.setPersister(persister);

        store.upsert("/photos/a.jpeg", "ida00000000000000000000", null, 0, 1L);
        store.flush();
        store.rememberName("/photos/a.jpeg", "Ada", 2L);
        store.flush();

        assertEquals(2, persister.flushes.size());
        assertEquals("/photos/a.jpeg", persister.flushes.get(1).get(0));
    }

    /**
     * Reading a store in from disk is not a change to write back: a cold start
     * would otherwise re-write every row it just read.
     */
    @Test public void loadingDoesNotMarkAnythingToFlush() {
        SweepStore store = new SweepStore();
        store.adopt(new SweepStore.Record("/photos/a.jpeg", "ida00000000000000000000", null, null,
                null, null, null, 0, 1L, 1L, "Ada", 1L));
        RecordingPersister persister = new RecordingPersister();
        store.setPersister(persister);

        store.flush();

        assertEquals(0, persister.flushes.size());
    }

    /** The database half must never be able to kill a sweep. */
    @Test public void aThrowingPersisterIsSwallowed() {
        SweepStore store = new SweepStore();
        store.setPersister(new SweepStore.Persister() {
            @Override public void persist(Collection<SweepStore.Record> changed) {
                throw new RuntimeException("disk full");
            }
        });

        store.upsert("/photos/a.jpeg", null, null, 0, 1L);
        store.flush();          // must not throw

        assertEquals(1, store.size());
    }

    /** A failed flush must not lose the change: the next flush carries it again. */
    @Test public void aFailedFlushIsRetriedOnTheNextOne() {
        SweepStore store = new SweepStore();
        final boolean[] fail = {true};
        final List<Integer> sizes = new ArrayList<Integer>();
        store.setPersister(new SweepStore.Persister() {
            @Override public void persist(Collection<SweepStore.Record> changed) {
                sizes.add(Integer.valueOf(changed.size()));
                if (fail[0]) {
                    throw new RuntimeException("disk full");
                }
            }
        });

        store.upsert("/photos/a.jpeg", null, null, 0, 1L);
        store.flush();
        fail[0] = false;
        store.flush();

        assertEquals(2, sizes.size());
        assertEquals(Integer.valueOf(1), sizes.get(1));
    }

    // ---- the sweep's use of it -------------------------------------------

    /**
     * Each window's twenty entries are durable before the next request goes
     * out, so a pass killed at any point keeps everything up to its last
     * completed window.
     */
    @Test public void sweepFlushesAfterEveryWindow() {
        FakeLikesServer server = new FakeLikesServer(FakeLikesServer.roster(60), true, 11L);
        SweepStore store = new SweepStore();
        RecordingPersister persister = new RecordingPersister();
        store.setPersister(persister);

        LikesSweep.Result result = LikesSweep.sweep(FakeLikesServer.PRIMARY, server,
                FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, store);

        assertEquals(3, result.requests);               // 60 people, 20 per window
        assertEquals(3, persister.flushes.size());
        assertEquals(60, persister.totalRecordsWritten());
    }

    /** What the completed windows learned is on disk even though the walk died mid-pass. */
    @Test public void anInterruptedSweepHasFlushedItsCompletedWindows() {
        FakeLikesServer server = new FakeLikesServer(FakeLikesServer.roster(123), true, 11L);
        SweepStore store = new SweepStore();
        RecordingPersister persister = new RecordingPersister();
        store.setPersister(persister);

        LikesSweep.Sleeper interruptAfterTwo = new LikesSweep.Sleeper() {
            private int calls;

            @Override public void sleep(long ms) throws InterruptedException {
                if (++calls >= 2) {
                    throw new InterruptedException();
                }
            }
        };

        LikesSweep.Result result = LikesSweep.sweep(FakeLikesServer.PRIMARY, server,
                interruptAfterTwo, FakeLikesServer.CLOCK, store);

        assertEquals(LikesSweep.StopReason.INTERRUPTED, result.reason);
        assertEquals(2, persister.flushes.size());
        assertEquals(40, persister.allPathsWritten().size());
        Thread.interrupted();           // leave the flag clean for the next test
    }

    /** The expansion phases flush too -- they go through the same walk. */
    @Test public void anchoredWalksFlushAsTheyGo() {
        FakeLikesServer server = new FakeLikesServer(FakeLikesServer.roster(123), true, 11L);
        SweepStore store = new SweepStore();
        LikesSweep.sweep(FakeLikesServer.PRIMARY, server, FakeLikesServer.NO_SLEEP,
                FakeLikesServer.CLOCK, store);

        RecordingPersister persister = new RecordingPersister();
        store.setPersister(persister);
        LikesSweep.expandAcrossSorts(FakeLikesServer.SORTS, FakeLikesServer.PRIMARY, server,
                FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, store);

        assertTrue("expansion wrote nothing as it went", persister.flushes.size() > 1);
    }
}
