package com.smali_generator.likes;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * MEASUREMENT HARNESS -- prints a cost report rather than asserting one; the
 * assertions live in {@link SweepBudgetTest}. Run it to see where a pass
 * spends its requests:
 *
 * <pre>./gradlew testDebugUnitTest --tests '*SweepCostMeasurementTest'</pre>
 *
 * then read {@code app/build/test-results/testDebugUnitTest/*SweepCost*.xml}.
 */
public class SweepCostMeasurementTest {

    private static final int PEOPLE = 123;

    @Before public void mintingIsVerified() {
        Cursors.resetMintVerification();
        Cursors.observeServerCursor(Cursors.encode("abcdefghijklmnopqrstuvw"));
        Log.setSink(null);
    }

    @Test public void coldStartCost() {
        FakeLikesServer views = new FakeLikesServer(FakeLikesServer.roster(PEOPLE), true, 11L);
        FakeLikesServer noViews = new FakeLikesServer(views.people, false, 11L);
        SweepStore store = new SweepStore();
        views.watched = store;
        noViews.watched = store;

        LikesSweep.Result walk = LikesSweep.sweep(FakeLikesServer.PRIMARY, views,
                FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, store);
        int afterWalk = views.requests;
        int idsAfterWalk = store.countWithId();

        LikesSweep.expand(FakeLikesServer.PRIMARY, FakeLikesServer.SORTS, views, noViews,
                FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, store);

        report("COLD START", walk.reason, afterWalk, idsAfterWalk, views, noViews, store);
    }

    @Test public void oneNewLikeCost() {
        FakeLikesServer views = new FakeLikesServer(FakeLikesServer.roster(PEOPLE), true, 11L);
        FakeLikesServer noViews = new FakeLikesServer(views.people, false, 11L);

        SweepStore store = new SweepStore();
        List<Integer> order = views.orders.get(FakeLikesServer.PRIMARY);
        for (int pos = 0; pos < order.size(); pos++) {
            FakeLikesServer.Person p = views.people.get(order.get(pos).intValue());
            store.upsert(p.path, pos == 0 ? null : p.id, null, pos, 1000L);
            if (pos != 0) {
                store.rememberName(p.path, "name" + pos, 1000L);
            }
        }
        views.watched = store;
        noViews.watched = store;

        LikesSweep.Result walk = LikesSweep.sweep(FakeLikesServer.PRIMARY, views,
                FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, store);
        int afterWalk = views.requests;
        int idsAfterWalk = store.countWithId();

        LikesSweep.expand(FakeLikesServer.PRIMARY, FakeLikesServer.SORTS, views, noViews,
                FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, store);

        report("ONE NEW LIKE", walk.reason, afterWalk, idsAfterWalk, views, noViews, store);
    }

    /** Several new likes at once, the other everyday case. */
    @Test public void fiveNewLikesCost() {
        FakeLikesServer views = new FakeLikesServer(FakeLikesServer.roster(PEOPLE), true, 11L);
        FakeLikesServer noViews = new FakeLikesServer(views.people, false, 11L);

        SweepStore store = new SweepStore();
        List<Integer> order = views.orders.get(FakeLikesServer.PRIMARY);
        for (int pos = 0; pos < order.size(); pos++) {
            FakeLikesServer.Person p = views.people.get(order.get(pos).intValue());
            store.upsert(p.path, pos < 5 ? null : p.id, null, pos, 1000L);
            if (pos >= 5) {
                store.rememberName(p.path, "name" + pos, 1000L);
            }
        }
        views.watched = store;
        noViews.watched = store;

        LikesSweep.Result walk = LikesSweep.sweep(FakeLikesServer.PRIMARY, views,
                FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, store);
        int afterWalk = views.requests;
        int idsAfterWalk = store.countWithId();

        LikesSweep.expand(FakeLikesServer.PRIMARY, FakeLikesServer.SORTS, views, noViews,
                FakeLikesServer.NO_SLEEP, FakeLikesServer.CLOCK, store);

        report("FIVE NEW LIKES", walk.reason, afterWalk, idsAfterWalk, views, noViews, store);
    }

    private static void report(String title, LikesSweep.StopReason reason, int walkRequests,
                               int idsAfterWalk, FakeLikesServer views, FakeLikesServer noViews,
                               SweepStore store) {
        int total = views.requests + noViews.requests;
        StringBuilder out = new StringBuilder();
        out.append("\n=== ").append(title).append(": ").append(PEOPLE).append(" cards, ")
                .append(FakeLikesServer.SORTS.size()).append(" sorts ===\n");
        out.append(String.format("%-26s %9d requests, %3d ids (%s)%n", "primary walk",
                Integer.valueOf(walkRequests), Integer.valueOf(idsAfterWalk), reason));
        out.append(String.format("%-26s %9d requests, %3d ids%n", "expansion",
                Integer.valueOf(total - walkRequests),
                Integer.valueOf(store.countWithId() - idsAfterWalk)));
        out.append(String.format("%-26s %9d%n", "TOTAL requests", Integer.valueOf(total)));
        out.append(String.format("%-26s %9d%n", "after full coverage",
                Integer.valueOf(views.requestsAfterFullCoverage
                        + noViews.requestsAfterFullCoverage)));
        out.append(String.format("%-26s %9d / %d%n", "identified", Integer.valueOf(store.countWithId()),
                Integer.valueOf(store.size())));
        out.append(String.format("%-26s %9.1f s at 400 ms%n", "wall clock",
                Double.valueOf(total * 0.4)));
        System.out.println(out);
    }
}
