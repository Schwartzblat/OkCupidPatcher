package com.smali_generator.likes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class NavigateOnceTest {

    /** A poster that queues, so a test controls exactly when posted work runs. */
    private static final class QueuedPoster implements NavigateOnce.Poster {
        final List<Runnable> queue = new ArrayList<Runnable>();
        @Override public boolean post(Runnable task) {
            queue.add(task);
            return true;
        }
        void drain() {
            List<Runnable> now = new ArrayList<Runnable>(queue);
            queue.clear();
            for (Runnable r : now) {
                r.run();
            }
        }
    }

    private static final class Counter implements Runnable {
        int runs;
        @Override public void run() { runs++; }
    }

    @Test public void aSecondTapBeforeTheQueueDrainsNavigatesOnlyOnce() {
        NavigateOnce guard = new NavigateOnce();
        QueuedPoster poster = new QueuedPoster();
        Counter navigations = new Counter();
        Counter failures = new Counter();

        assertTrue(guard.navigate("card", poster, navigations, failures));    // tap A
        assertFalse(guard.navigate("card", poster, navigations, failures));   // tap B, same window
        assertEquals(1, poster.queue.size());
        poster.drain();

        assertEquals(1, navigations.runs);
        assertEquals(0, failures.runs);
    }

    @Test public void afterTheQueueDrainsAnotherTapNavigatesAgain() {
        NavigateOnce guard = new NavigateOnce();
        QueuedPoster poster = new QueuedPoster();
        Counter navigations = new Counter();

        assertTrue(guard.navigate("card", poster, navigations, null));
        poster.drain();
        assertTrue(guard.navigate("card", poster, navigations, null));        // tap C
        poster.drain();

        assertEquals(2, navigations.runs);       // the card is not stuck forever
    }

    @Test public void differentCardsDoNotBlockEachOther() {
        NavigateOnce guard = new NavigateOnce();
        QueuedPoster poster = new QueuedPoster();
        assertTrue(guard.navigate("a", poster, new Counter(), null));
        assertTrue(guard.navigate("b", poster, new Counter(), null));
    }

    @Test public void aThrowingNavigationStillReleasesTheClaim() {
        NavigateOnce guard = new NavigateOnce();
        QueuedPoster poster = new QueuedPoster();
        Counter failures = new Counter();
        Runnable boom = new Runnable() {
            @Override public void run() { throw new IllegalStateException("nav blew up"); }
        };

        assertTrue(guard.navigate("card", poster, boom, failures));
        poster.drain();
        assertEquals(1, failures.runs);          // the user is told

        Counter navigations = new Counter();
        assertTrue(guard.navigate("card", poster, navigations, failures));
        poster.drain();
        assertEquals(1, navigations.runs);       // and the card works again
    }

    @Test public void aRejectedPostReleasesTheClaimAndReports() {
        NavigateOnce guard = new NavigateOnce();
        Counter failures = new Counter();
        NavigateOnce.Poster rejecting = new NavigateOnce.Poster() {
            @Override public boolean post(Runnable task) { return false; }
        };

        assertFalse(guard.navigate("card", rejecting, new Counter(), failures));
        assertEquals(1, failures.runs);

        QueuedPoster poster = new QueuedPoster();
        Counter navigations = new Counter();
        assertTrue(guard.navigate("card", poster, navigations, failures));    // not stuck
        poster.drain();
        assertEquals(1, navigations.runs);
    }

    @Test public void aThrowingPostReleasesTheClaimAndReports() {
        NavigateOnce guard = new NavigateOnce();
        Counter failures = new Counter();
        NavigateOnce.Poster throwing = new NavigateOnce.Poster() {
            @Override public boolean post(Runnable task) {
                throw new IllegalStateException("handler is dead");
            }
        };

        assertFalse(guard.navigate("card", throwing, new Counter(), failures));
        assertEquals(1, failures.runs);

        QueuedPoster poster = new QueuedPoster();
        assertTrue(guard.navigate("card", poster, new Counter(), failures));
    }

    @Test public void aThrowingFailureCallbackNeverEscapes() {
        NavigateOnce guard = new NavigateOnce();
        QueuedPoster poster = new QueuedPoster();
        Runnable boom = new Runnable() {
            @Override public void run() { throw new IllegalStateException("nav"); }
        };
        Runnable alsoBoom = new Runnable() {
            @Override public void run() { throw new IllegalStateException("toast"); }
        };
        assertTrue(guard.navigate("card", poster, boom, alsoBoom));
        poster.drain();                           // must not throw
        assertTrue(guard.navigate("card", poster, new Counter(), null));
    }
}
