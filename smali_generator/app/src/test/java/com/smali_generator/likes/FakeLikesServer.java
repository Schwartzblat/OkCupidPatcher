package com.smali_generator.likes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A stand-in for the real likes connection, shared by every cost and budget
 * test in this package.
 *
 * <p>Reproduces the three behaviours the sweep's arithmetic depends on: a
 * fixed window size, {@code pageInfo.after} = base64 of the window's own last
 * entry's id, and a cursor naming a person who is not in this sort's order
 * being silently answered with page 1 (the failure mode
 * {@link Cursors#observeServerCursor} exists to catch).
 *
 * <p>Every entry is served gated -- no {@code user} object, so no id -- which
 * is the case the whole feature exists for. Highlights are carried so the
 * attribute-keyed planner paths have something to aim with.
 */
final class FakeLikesServer implements LikesSweep.Fetcher {

    static final int WINDOW = 20;

    /** The app's own {@code LikesListSort}, minus the {@code UNKNOWN__} sentinel. */
    static final List<String> SORTS = Collections.unmodifiableList(Arrays.asList(
            "AGE_ASCENDING", "AGE_DESCENDING", "DESC_TIMESTAMP", "DISTANCE_ASCENDING",
            "JOIN_DATE_DESCENDING", "LAST_LOGIN_DESCENDING", "LIKES_ME", "LIKES_VIEWS_GLOBAL",
            "MATCH_SCORE_DESCENDING", "VIEWED_ME"));

    static final String PRIMARY = "DESC_TIMESTAMP";

    static final class Person {
        final String id;
        final String path;
        final int age;
        final double score;
        final boolean online;
        final String location;
        final boolean viewedMe;

        Person(int i, Random rnd) {
            this.id = String.format("id%021d", Integer.valueOf(i));    // 23 chars, base64url-safe
            this.path = "/photos/p" + i + ".jpeg";
            this.age = 22 + rnd.nextInt(20);
            this.score = rnd.nextInt(100);
            this.online = rnd.nextBoolean();
            this.location = "City" + rnd.nextInt(8);
            this.viewedMe = rnd.nextInt(100) < 30;
        }
    }

    final List<Person> people;
    final Map<String, List<Integer>> orders = new LinkedHashMap<String, List<Integer>>();
    int requests;
    /** Requests charged while the watched store already had every card identified. */
    int requestsAfterFullCoverage;
    SweepStore watched;

    /** @param includeViews false drops the "viewed you" people, shifting everyone's offset. */
    FakeLikesServer(List<Person> people, boolean includeViews, long seed) {
        this.people = people;
        Random rnd = new Random(seed);
        for (String sort : SORTS) {
            List<Integer> order = new ArrayList<Integer>();
            for (int i = 0; i < people.size(); i++) {
                if (includeViews || !people.get(i).viewedMe) {
                    order.add(Integer.valueOf(i));
                }
            }
            if (!sort.equals(PRIMARY)) {
                Collections.shuffle(order, new Random(rnd.nextLong()));
            }
            orders.put(sort, order);
        }
    }

    static List<Person> roster(int size) {
        Random rnd = new Random(7);
        List<Person> people = new ArrayList<Person>();
        for (int i = 0; i < size; i++) {
            people.add(new Person(i, rnd));
        }
        return people;
    }

    /** Where {@code path} sits in {@code sort}'s order, or -1. */
    int offsetOf(String sort, String path) {
        List<Integer> order = orders.get(sort);
        for (int i = 0; i < order.size(); i++) {
            if (people.get(order.get(i).intValue()).path.equals(path)) {
                return i;
            }
        }
        return -1;
    }

    /** The person at {@code offset} of {@code sort}. */
    Person at(String sort, int offset) {
        return people.get(orders.get(sort).get(offset).intValue());
    }

    @Override public String fetch(String sort, String cursor) {
        requests++;
        if (watched != null && watched.size() > 0 && watched.countWithId() >= watched.size()) {
            requestsAfterFullCoverage++;
        }
        List<Integer> order = orders.get(sort);
        if (order == null) {
            return null;
        }
        int start = 0;
        if (cursor != null) {
            String id = Cursors.decode(cursor);
            int at = -1;
            for (int i = 0; i < order.size(); i++) {
                if (people.get(order.get(i).intValue()).id.equals(id)) {
                    at = i;
                    break;
                }
            }
            if (at < 0) {
                return page(order, 0, Math.min(WINDOW, order.size()));   // unrecognised: page 1
            }
            start = at + 1;
        }
        if (start >= order.size()) {
            return page(order, 0, 0);
        }
        return page(order, start, Math.min(start + WINDOW, order.size()));
    }

    private String page(List<Integer> order, int from, int to) {
        StringBuilder nodes = new StringBuilder();
        for (int i = from; i < to; i++) {
            Person p = people.get(order.get(i).intValue());
            if (i > from) {
                nodes.append(',');
            }
            nodes.append("{\"primaryImage\":{\"square225\":\"https://c.example.com")
                    .append(p.path).append("?h=225\"},\"matchHighlights\":{\"age\":")
                    .append(p.age).append(",\"matchScore\":").append(p.score)
                    .append(",\"isOnline\":").append(p.online)
                    .append(",\"dynamicHighlight\":{\"summary\":\"").append(p.location)
                    .append("\"}}}");
        }
        String after = null;
        if (to < order.size() && to > from) {
            after = Cursors.encode(people.get(order.get(to - 1).intValue()).id);
        }
        return "{\"data\":{\"me\":{\"likesIncomingWithPreviews\":{\"data\":[" + nodes
                + "],\"pageInfo\":{\"after\":" + (after == null ? "null" : "\"" + after + "\"")
                + ",\"hasMore\":" + (after != null) + ",\"total\":" + order.size() + "}}}}}";
    }

    static final LikesSweep.Sleeper NO_SLEEP = new LikesSweep.Sleeper() {
        @Override public void sleep(long ms) {
        }
    };

    static final LikesSweep.Clock CLOCK = new LikesSweep.Clock() {
        @Override public long now() {
            return 1000L;
        }
    };
}
