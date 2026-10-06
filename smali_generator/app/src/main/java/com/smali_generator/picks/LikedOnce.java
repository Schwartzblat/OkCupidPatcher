package com.smali_generator.picks;

import java.util.HashSet;
import java.util.Set;

/**
 * Which people the added Picks Like button has already liked this process.
 *
 * <p>Two things make this necessary rather than defensive. Epoxy rebinds a
 * carousel card every time it scrolls back into view, and the added button is
 * re-wired on each bind -- so the same card hands out many click events over
 * its life. And the vote itself is asynchronous: the tap returns long before
 * the mutation does, which leaves a window where a second tap would send a
 * second {@code UserVote} for the same person. The server would charge both
 * against the daily likes cap.
 *
 * <p>So a tap must claim the id before it sends anything, and only a claim
 * that succeeded may send. A failed vote releases its claim, because the
 * person has not in fact been liked and the user should be able to try again;
 * a successful one keeps it for the life of the process, which is also what
 * lets the button render itself as already-used when the card scrolls back.
 *
 * <p>Deliberately memory-only, and deliberately not a cache of server state.
 * It records what <em>this process</em> sent, nothing more: a like sent from
 * the profile screen, from another device or in an earlier run is invisible
 * here, and the server is the only authority on whether a vote counted. The
 * same reasoning as {@code DownloadRegistry} being memory-only -- a stale
 * "already liked" would be worse than asking twice.
 *
 * <p>Pure, so it is unit-tested on the JVM; every Android and reflection
 * concern lives in {@code PicksLikeButton}.
 */
public final class LikedOnce {

    private static final LikedOnce INSTANCE = new LikedOnce();

    private final Set<String> claimed = new HashSet<String>();

    /** Package-visible for tests; production code goes through {@link #get()}. */
    LikedOnce() {
    }

    public static LikedOnce get() {
        return INSTANCE;
    }

    /**
     * Takes the right to send one like for {@code userId}.
     *
     * @return true if the caller now owns the claim and must send the vote;
     *         false if a tap already owns it, in which case the caller must
     *         send nothing. A null or blank id is never claimable -- there is
     *         no one to vote for, and treating it as claimable would let one
     *         unusable id block every other.
     */
    public synchronized boolean claim(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return false;
        }
        return claimed.add(userId);
    }

    /**
     * Gives the claim back after a vote failed, so the same card can be tried
     * again. A no-op for an id nobody claimed.
     */
    public synchronized void release(String userId) {
        if (userId != null) {
            claimed.remove(userId);
        }
    }

    /**
     * Whether this process has already sent a like for {@code userId} -- what
     * the button reads when a recycled card is re-bound, so it comes back
     * already spent instead of inviting a duplicate.
     */
    public synchronized boolean isClaimed(String userId) {
        return userId != null && claimed.contains(userId);
    }

    /** Test seam. Never called in production: the set is process-scoped. */
    synchronized void reset() {
        claimed.clear();
    }

    /** How many people this process has liked from Picks. Diagnostics only. */
    public synchronized int size() {
        return claimed.size();
    }
}
