package com.smali_generator.likes;

import java.util.HashSet;
import java.util.Set;

/**
 * At most one pending navigation per key (a placeholder id), however many taps
 * arrive before it runs. Pure: the thread the navigation runs on is a
 * {@link Poster}, so the Android Handler is only the production implementation.
 *
 * <p>The claim is taken before the post and released when the posted action
 * finishes, whether it returns, throws, or was never run because the post
 * itself failed. A card is therefore never left permanently un-navigable.
 */
public final class NavigateOnce {

    /** Runs a task later, on the thread navigation must happen on. */
    public interface Poster {
        /** @return false if the task was not accepted; may also throw */
        boolean post(Runnable task);
    }

    private final Set<String> pending = new HashSet<String>();

    /**
     * @param action    the navigation itself
     * @param onFailure told if the action threw or could not be posted; never
     *                  allowed to propagate
     * @return true if a navigation was queued; false if one is already pending
     *         for this key, or the post failed
     */
    public boolean navigate(final String key, Poster poster, final Runnable action,
                            final Runnable onFailure) {
        if (!claim(key)) {
            return false;
        }
        boolean posted;
        try {
            posted = poster.post(new Runnable() {
                @Override public void run() {
                    try {
                        action.run();
                    } catch (Throwable t) {
                        fail(onFailure);
                    } finally {
                        release(key);
                    }
                }
            });
        } catch (Throwable t) {
            posted = false;
        }
        if (!posted) {
            release(key);               // nothing will ever run to release it
            fail(onFailure);
            return false;
        }
        return true;
    }

    private static void fail(Runnable onFailure) {
        try {
            if (onFailure != null) {
                onFailure.run();
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean claim(String key) {
        synchronized (pending) {
            return pending.add(key);
        }
    }

    private void release(String key) {
        synchronized (pending) {
            pending.remove(key);
        }
    }
}
