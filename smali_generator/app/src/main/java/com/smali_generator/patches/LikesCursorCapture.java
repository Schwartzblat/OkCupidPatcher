package com.smali_generator.patches;

import android.util.Log;

import com.smali_generator.Hook;
import com.smali_generator.likes.BoundaryBinder;
import com.smali_generator.likes.BoundaryBinder.CardEntry;
import com.smali_generator.likes.BoundaryBinder.Decision;
import com.smali_generator.likes.IdentityStore;
import com.smali_generator.likes.LikesSweep;
import com.smali_generator.likes.PhotoPaths;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads every Likes page the app fetches for itself -- for free.
 *
 * Target: {@code LikesPageRepo$LikesPagePayload.getUserList()}, reimplemented
 * outright with the two-argument ArtHooks form. There is NO backup and
 * nothing calls through.
 *
 * <p>The original design for this hook targeted the payload's constructor
 * with a three-argument backup, so the capture could run right after
 * construction. That is unusable: the constructor's first instruction is
 * {@code const-string v0, "userList"} (Kotlin's {@code checkNotNullParameter}),
 * and a call-through hook on a {@code const-string} entry crashed 3/3 cold
 * launches with SIGSEGV (measured). Why is a hypothesis, not an established
 * mechanism: see NOTES.md, "The ArtHooks backup-entry-instruction rule", the
 * single source for the evidence and its limits. A constructor hook also cannot skip the backup: without it the
 * object is returned uninitialised. So that route is closed.
 *
 * {@code getUserList()}'s bytecode (checked in the extracted smali) is just
 * {@code iget-object v0, p0, ...->userList:...; return-object v0} -- a pure
 * getter with no constant-pool resolution in it at all, so it is safe to
 * replace outright. {@code thiz} is the payload instance, and both the card
 * list ({@code userList}) and the pagination cursor ({@code nextPagingKey})
 * are fields on it, so this single hook gives the pair the resolver needs:
 * the getter is reimplemented by reading the {@code userList} field directly,
 * and the same call reads the {@code nextPagingKey} field to do the capture.
 *
 * <p>The field names are hardcoded, not supplied by a finder -- the same
 * posture {@link TransportCapture} takes for okhttp's {@code client} and
 * {@code originalRequest} fields, and the same the project's own finders
 * already take for this app's classes ({@code UserAccessors} hardcodes
 * {@code userid}/{@code photos} in its regex). Both {@code userList} and
 * {@code nextPagingKey} are literal field names in the extracted smali for
 * this class, confirmed before writing this hook.
 *
 * <p>This class is a reflection adapter only: it builds one {@link CardEntry}
 * per raw list element (one reflective read of id and photo path each, never
 * repeated) and hands the whole list, plus the raw cursor, to {@link
 * BoundaryBinder#decide}, a pure function in the core that owns the actual
 * decision of whether and what to bind. Which element is "the page's final
 * entry" and what to do when it is unusable is exactly the decision a past
 * bug got wrong here; it is pinned by tests against the core now, not by an
 * argument about this file's control flow.
 */
public class LikesCursorCapture implements Hook {

    private static final Set<String> CURSORS = new LinkedHashSet<String>();

    private static volatile Field userListField;
    private static volatile Field nextPagingKeyField;
    private static volatile Class<?> userClass;
    private static volatile Method getIdMethod;
    private static volatile Method getPhotosMethod;

    // The accessor that unwraps the ObservableData wrapper's payload (e.g.
    // ObservableData.Data.getValue()). Resolved lazily against the first
    // instance actually carrying a List -- Loading/Empty/Error wrappers have
    // no such accessor, so a sweep against one of those finds nothing and is
    // deliberately allowed to retry on the next call rather than being
    // cached as a permanent failure. Once a real Data-shaped instance is
    // seen, the winner is cached and no further sweep ever runs.
    private static volatile Method valueAccessor;

    // The getter can be invoked many times for the same page -- Compose
    // recomposition reads a property getter on every recompose -- but the
    // payload is an immutable data class, so the same instance never carries
    // new data. Memoizing by instance identity makes capture run once per
    // page actually loaded instead of once per read.
    private static volatile Object lastProcessed;

    // A page whose record() keeps throwing (e.g. an unexpected list element
    // shape) would otherwise retry -- and error-log -- on every single
    // recomposition of that same instance, forever, on the UI thread. Capped
    // per distinct instance: after MAX_RECORD_ATTEMPTS failures, the instance
    // is marked processed (via lastProcessed) and logging for it stops.
    // Keyed by identity, not equals()/hashCode() -- this class is a Kotlin
    // data class and value-equal instances must not share a budget.
    private static final int MAX_RECORD_ATTEMPTS = 3;
    private static final Map<Object, Integer> FAILED_ATTEMPTS =
            new IdentityHashMap<Object, Integer>();

    private static final AtomicBoolean WARNED = new AtomicBoolean();

    public static synchronized List<String> seenCursors() {
        return new ArrayList<String>(CURSORS);
    }

    /** Reimplementation of getUserList(); instance target, so leading thiz. */
    static Object get_user_list_hook(Object thiz) {
        Object userList = null;
        try {
            userList = userListField.get(thiz);
        } catch (Throwable t) {
            // load() proved this lookup works, so this should not happen. The
            // real getter never throws here, so make it diagnosable.
            if (WARNED.compareAndSet(false, true)) {
                Log.e(HookUtil.TAG, "LikesCursorCapture: userList read failed -- " + t);
            }
        }
        if (thiz != null && thiz != lastProcessed) {
            try {
                record(thiz, userList);
                // Only mark this instance processed once record() actually
                // completed -- a transient failure must not permanently drop
                // this page's capture, since the getter will be called again
                // on the next recomposition with the very same instance.
                lastProcessed = thiz;
                forgetAttempts(thiz);
            } catch (Throwable t) {
                onRecordFailed(thiz, t);
            }
        }
        return userList;
    }

    private static void forgetAttempts(Object thiz) {
        synchronized (FAILED_ATTEMPTS) {
            FAILED_ATTEMPTS.remove(thiz);
        }
    }

    /** Never interfere with the app's own rendering, and never spam logcat forever. */
    private static void onRecordFailed(Object thiz, Throwable t) {
        int attempts;
        synchronized (FAILED_ATTEMPTS) {
            Integer previous = FAILED_ATTEMPTS.get(thiz);
            attempts = (previous == null ? 0 : previous) + 1;
            FAILED_ATTEMPTS.put(thiz, attempts);
        }
        if (attempts >= MAX_RECORD_ATTEMPTS) {
            // Give up on this exact instance: it will keep being handed back
            // by the real getter on every recomposition, so without this it
            // would retry -- and log -- forever on the UI thread.
            lastProcessed = thiz;
            forgetAttempts(thiz);
            Log.e(HookUtil.TAG, "LikesCursorCapture: giving up on this page after "
                    + attempts + " failed attempt(s) -- " + t);
        } else {
            Log.e(HookUtil.TAG, "LikesCursorCapture: " + t);
        }
    }

    private static void record(Object thiz, Object userList) throws Exception {
        List<?> users = unwrapUsers(userList);
        if (users == null) {
            return;
        }
        IdentityStore store = IdentityStore.get();

        // One reflective read of id and photo path per raw element, used both
        // for the main loop's bookkeeping below and, unchanged, as the input
        // to the boundary decision -- no re-reading the final entry twice.
        List<CardEntry> entries = new ArrayList<CardEntry>(users.size());
        int bound = 0;
        for (Object user : users) {
            boolean isCard = user != null && userClass.isInstance(user);
            String id = null;
            String path = null;
            if (isCard) {
                id = (String) getIdMethod.invoke(user);
                path = firstRealPhotoPath(getPhotosMethod.invoke(user));
            }
            entries.add(new CardEntry(isCard, id, path));
            if (id != null && path != null) {
                store.rememberCard(id, path);
                bound++;
                if (!IdentityStore.isPlaceholder(id)) {
                    // A non-gated entry is an identity for free.
                    store.rememberIdentity(path, id);
                }
            }
        }

        String nextPagingKey = (String) nextPagingKeyField.get(thiz);
        if (nextPagingKey != null) {
            synchronized (LikesCursorCapture.class) {
                CURSORS.add(nextPagingKey);
            }
        }

        Decision decision = BoundaryBinder.decide(nextPagingKey, entries);
        if (decision.bound) {
            store.rememberIdentity(decision.path, decision.id);
        } else {
            logSkipIfNoteworthy(decision);
        }

        Log.i(HookUtil.TAG, "LikesCursorCapture: page of " + bound + " card(s) bound"
                + (decision.bound ? "; boundary identity bound from its cursor" : ""));

        // The Likes page has now genuinely loaded -- the one trigger point
        // for the sweep (never app startup, never a background timer). A
        // no-op if a sweep or a tap-resolve already owns the network.
        // Before the grid composes, not after: a name that arrives later is
        // a name the card never draws.
        LikesSweep.primeNamesIfNeeded();
        LikesSweep.triggerAsync();
    }

    /**
     * NO_CURSOR (most pages near the end of the list), UNDECODABLE_CURSOR
     * (this particular id didn't resolve) and EMPTY_PAGE are the ordinary
     * "nothing to bind, nothing wrong" outcomes and are not worth a log line
     * on every page. The other three reasons are anomalies worth surfacing.
     */
    private static void logSkipIfNoteworthy(Decision decision) {
        switch (decision.reason) {
            case FINAL_ENTRY_NOT_A_CARD:
            case FINAL_ENTRY_NO_PATH:
                Log.i(HookUtil.TAG, "LikesCursorCapture: cursor present but the page's final "
                        + "entry is unusable (" + decision.reason + ") -- skipping boundary bind");
                break;
            case ID_MISMATCH:
                Log.w(HookUtil.TAG, "LikesCursorCapture: cursor-decoded id disagrees with the "
                        + "final entry's own id -- skipping boundary bind");
                break;
            default:
                break;
        }
    }

    /**
     * The list argument may be the plain list or an {@code ObservableData}
     * wrapper exposing one through a generic accessor whose erased return
     * type is {@code Object} (e.g. {@code ObservableData.Data.getValue()}),
     * so the match has to be on the *invoked result*, not the declared return
     * type. That used to mean invoking every public zero-arg method on the
     * wrapper -- including {@code hashCode}, {@code toString}, {@code wait},
     * {@code notifyAll}, and any app method with side effects, on whatever
     * thread called the getter (the UI thread, most likely). Instead, the
     * winning accessor is resolved once (restricted to {@code get*} methods
     * not declared by {@link Object}) and cached, so the sweep itself runs at
     * most once per process rather than once per payload.
     */
    private static List<?> unwrapUsers(Object userList) {
        if (userList instanceof List) {
            return (List<?>) userList;
        }
        if (userList == null) {
            return null;
        }
        Method accessor = valueAccessor;
        if (accessor == null) {
            accessor = resolveValueAccessor(userList);
            if (accessor == null) {
                return null;
            }
        }
        try {
            Object result = accessor.invoke(userList);
            return (result instanceof List) ? (List<?>) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Restricted, one-time (on success) sweep: zero-arg {@code get*} methods
     * not declared by {@link Object}, invoked only until one returns a
     * non-empty {@link List}. Loading/Empty/Error wrapper instances have no
     * such method, so a sweep against one finds nothing -- deliberately not
     * cached as a permanent failure, since the very next page is likely to be
     * the Data-shaped instance that does have one.
     */
    private static synchronized Method resolveValueAccessor(Object sample) {
        if (valueAccessor != null) {
            return valueAccessor;
        }
        for (Method m : sample.getClass().getMethods()) {
            if (m.getDeclaringClass() == Object.class) {
                continue;
            }
            if (m.getParameterTypes().length != 0 || !m.getName().startsWith("get")) {
                continue;
            }
            try {
                Object result = m.invoke(sample);
                if (result instanceof List && !((List<?>) result).isEmpty()) {
                    valueAccessor = m;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        return valueAccessor;
    }

    /**
     * The photo model's accessor names are a naming convention, not a stable
     * API, so take the first zero-argument {@code get*} String method (not Object's) whose value
     * normalizes to a real photo path instead of naming any of them.
     */
    private static String firstRealPhotoPath(Object photos) {
        if (!(photos instanceof List) || ((List<?>) photos).isEmpty()) {
            return null;
        }
        Object photo = ((List<?>) photos).get(0);
        if (photo == null) {
            return null;
        }
        for (Method m : photo.getClass().getMethods()) {
            // Same policy as resolveValueAccessor: never Object-declared
            // (toString() would run app code per card per page on the UI
            // thread) and only get* names.
            if (m.getDeclaringClass() == Object.class
                    || m.getParameterTypes().length != 0
                    || m.getReturnType() != String.class
                    || !m.getName().startsWith("get")) {
                continue;
            }
            try {
                String path = PhotoPaths.normalize((String) m.invoke(photo));
                if (path != null) {
                    return path;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @Override
    public void load() {
        try {
            Class<?> payloadClass = Class.forName("{{LIKES_PAYLOAD_CLASS_NAME}}", false,
                    LikesCursorCapture.class.getClassLoader());
            Field ul = payloadClass.getDeclaredField("userList");
            Field npk = payloadClass.getDeclaredField("nextPagingKey");
            ul.setAccessible(true);
            npk.setAccessible(true);

            Class<?> uc = Class.forName("{{USER_MODEL_CLASS_NAME}}", false,
                    LikesCursorCapture.class.getClassLoader());
            Method gid = uc.getMethod("{{USER_ID_METHOD_NAME}}");
            Method gph = uc.getMethod("{{USER_PHOTOS_METHOD_NAME}}");

            userListField = ul;
            nextPagingKeyField = npk;
            userClass = uc;
            getIdMethod = gid;
            getPhotosMethod = gph;

            Method replacement = LikesCursorCapture.class.getDeclaredMethod(
                    "get_user_list_hook", Object.class);
            HookUtil.install("LikesCursorCapture",
                    "{{LIKES_PAYLOAD_CLASS_NAME}}",
                    "{{LIKES_PAYLOAD_GETTER_NAME}}",
                    "{{LIKES_PAYLOAD_GETTER_SIG}}",
                    replacement);
        } catch (Throwable t) {
            // Includes a different payload layout: install nothing rather
            // than risk replacing a getter we cannot reimplement.
            Log.e(HookUtil.TAG, "LikesCursorCapture: " + t);
        }
    }

    @Override
    public void unload() {
    }
}
