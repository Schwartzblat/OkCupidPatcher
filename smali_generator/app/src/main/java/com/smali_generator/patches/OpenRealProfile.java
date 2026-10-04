package com.smali_generator.patches;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Log;
import android.widget.Toast;

import com.smali_generator.Hook;
import com.smali_generator.likes.IdentityStore;
import com.smali_generator.likes.LikesIdentityResolver;
import com.smali_generator.likes.NavigateOnce;
import com.smali_generator.likes.OutcomeMessages;
import com.smali_generator.utils.Utils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns every tap-to-profile navigation on the likes grid -- gated and
 * non-gated cards alike -- substituting a resolved id in for a placeholder
 * one where it can.
 *
 * Target: {@code LikesPageController.triggerNavigateToProfile(User)} -- the
 * tap's actual entry point (found during Task 11's on-device verification;
 * {@code LikesPageFragment.navigateToProfile(String)}, this hook's original
 * target, turned out to be dead code with zero callers anywhere in the dex).
 *
 * <h2>Why this is a full 2-argument reimplementation, not a 3-argument
 * call-through</h2>
 *
 * The first design tried here used the 3-arg backup form -- call the real
 * body through once this hook decides navigation may proceed. That crashed
 * the app on every single tap, 1/1 reproduced with a full tombstone:
 *
 * <pre>
 * #00 NterpGetMethod
 * #02 nterp2_op_invoke_virtual_slow_path
 * #03 LikesPageController.triggerNavigateToProfile+0
 * #05 OpenRealProfile.callBackupGuarded+12   (this hook's own call-through)
 * </pre>
 *
 * This is the same bug family NOTES.md documents for {@code
 * LikesCursorCapture} (see "The ArtHooks backup-entry-instruction rule"
 * there, which also states how narrow the evidence for it is: a hypothesis,
 * with {@code const-string} and {@code invoke-virtual} entries measured
 * unsafe). A call-through is therefore off the table for this target, and the only way to keep
 * behaviour unchanged for every tap -- not just gated ones -- is to
 * reimplement the method's <b>entire</b> effect faithfully instead of
 * approximating it. See NOTES.md's "ArtHooks backup-entry-instruction rule"
 * for the full evidence this rule rests on (confirmed unsafe entries,
 * confirmed safe ones, and what is still just a hypothesis).
 *
 * <h2>The original body, in full -- no elisions</h2>
 *
 * Reproduced directly from the extracted smali so every effect is visible
 * and accounted for, not asserted:
 *
 * <pre>
 * .method private final triggerNavigateToProfile(Lcom/okcupid/okcupid/data/model/User;)V
 *     invoke-virtual {p1}, User;->getUserid()Ljava/lang/String;
 *     move-result-object v0
 *     new-instance v1, Ljava/lang/StringBuilder;
 *     invoke-direct {v1}, StringBuilder;-&gt;&lt;init&gt;()V
 *     const-string v2, "/profile/"
 *     invoke-virtual {v1, v2}, StringBuilder;-&gt;append(String;)StringBuilder;
 *     invoke-virtual {v1, v0}, StringBuilder;-&gt;append(String;)StringBuilder;
 *     invoke-virtual {v1}, StringBuilder;-&gt;toString()Ljava/lang/String;
 *     move-result-object v0                                    ; v0 = "/profile/" + id
 *
 *     invoke-virtual {p0}, TypedEpoxyController;-&gt;getCurrentData()Ljava/lang/Object;
 *     move-result-object v1
 *     check-cast v1, LikesPageState;
 *     if-eqz v1, :cond_0
 *     invoke-virtual {v1}, LikesPageState;-&gt;getLikesPageConfiguration()LikesPageConfiguration;
 *     move-result-object v1
 *     goto :goto_0
 *   :cond_0
 *     const/4 v1, 0x0                                          ; v1 = null if getCurrentData() isn't a LikesPageState
 *   :goto_0
 *     if-nez v1, :cond_1
 *     const/4 v1, -0x1                                         ; v1 = -1 (no match) if the configuration is null
 *     goto :goto_1
 *   :cond_1
 *     sget-object v2, LikesPageController$WhenMappings;-&gt;$EnumSwitchMapping$0:[I
 *     invoke-virtual {v1}, Enum;-&gt;ordinal()I
 *     move-result v1
 *     aget v1, v2, v1                                          ; v1 = 1(INTROS)/2(LIKES_YOU)/3(YOU_LIKE)/0(anything else)
 *   :goto_1
 *     const/4 v2, 0x1
 *     if-eq v1, v2, :cond_4          ; 1 -&gt; CameFrom.INTROS
 *     const/4 v2, 0x2
 *     if-eq v1, v2, :cond_3          ; 2 -&gt; CameFrom.WHO_LIKES_YOU
 *     const/4 v2, 0x3
 *     if-eq v1, v2, :cond_2          ; 3 -&gt; CameFrom.WHO_YOU_LIKE
 *     sget-object v1, CameFrom;-&gt;DEFAULT:CameFrom;     ; everything else -&gt; CameFrom.DEFAULT
 *     goto :goto_2
 *   :cond_2
 *     sget-object v1, CameFrom;-&gt;WHO_YOU_LIKE:CameFrom;
 *     goto :goto_2
 *   :cond_3
 *     sget-object v1, CameFrom;-&gt;WHO_LIKES_YOU:CameFrom;
 *     goto :goto_2
 *   :cond_4
 *     sget-object v1, CameFrom;-&gt;INTROS:CameFrom;
 *   :goto_2
 *     new-instance v2, Landroid/os/Bundle;
 *     invoke-direct {v2}, Bundle;-&gt;&lt;init&gt;()V
 *     const-string v3, "com.okcupid.okcupid.came_from_tag"
 *     invoke-virtual {v2, v3, v1}, Bundle;-&gt;putSerializable(String;Serializable;)V
 *     const-string v1, "app.okcupid.user.extra"
 *     invoke-virtual {v2, v1, p1}, Bundle;-&gt;putParcelable(String;Parcelable;)V   ; the ORIGINAL user object, unmodified
 *     iget-object p1, p0, LikesPageController;-&gt;navigateToListener:Function2;
 *     if-eqz p1, :cond_5
 *     invoke-interface {p1, v0, v2}, Function2;-&gt;invoke(Object;Object;)Object;   ; dispatch
 *   :cond_5
 *     return-void
 * .end method
 * </pre>
 *
 * Every effect enumerated: (1) build the route string from the id: the one
 * place the id matters; (2) read the controller's current
 * {@code LikesPageConfiguration} (nullable -- two separate null guards, for
 * {@code getCurrentData()} not being a {@code LikesPageState} and for the
 * configuration itself) and map it to a {@code CameFrom} tracking/navigation
 * enum, defaulting to {@code CameFrom.DEFAULT} for anything unrecognised;
 * (3) build a {@code Bundle} with that tag plus the <b>original, unmodified</b>
 * {@code User} parcelable; (4) read the {@code navigateToListener} field and,
 * if non-null, invoke it with {@code (route, bundle)}. No other state is
 * written, no other calls are made, and the only "guard" is the null checks
 * already listed above. This hook reproduces all four effects faithfully.
 *
 * <h2>Why {@code came_from_tag} is not optional</h2>
 *
 * An earlier version of this hook dropped {@code came_from_tag} as an
 * "analytics only" simplification. It is not analytics only:
 * {@code OkRoutesFactory.getProfileFragment} reads it to build
 * {@code ProfileFragmentArgs}, and {@code ProfileFragment} uses the resulting
 * {@code cameFrom} field for actual navigation decisions --
 * {@code goBacktoMainScreenUserCameFrom} branches on
 * {@code CameFrom.MATCH_SEARCH}/{@code SEARCH} to pop the back stack
 * differently, and the first-interaction-tray-dismissed handler branches on
 * {@code CameFrom.WHO_LIKES_YOU} specifically to call
 * {@code goBackToMainFragment()} after a match formed from a likes-you card.
 * (Neither branch is reachable from this controller's own four possible
 * values -- {@code INTROS}/{@code WHO_LIKES_YOU}/{@code WHO_YOU_LIKE}/
 * {@code DEFAULT} -- for the {@code MATCH_SEARCH}/{@code SEARCH} case, but the
 * {@code WHO_LIKES_YOU} case is directly reachable from this exact
 * controller, so the previous omission was a real, reachable behaviour gap,
 * not merely a theoretical one.) This hook reproduces it, reading the same
 * {@code getCurrentData()} → {@code getLikesPageConfiguration()} chain by
 * reflection and mapping it with the identical four-way logic above, keyed
 * by {@link #NAV_TRIGGER_CAME_FROM_CLASS_NAME} ([LikesNavTrigger] extracts
 * this from the very same method body that proves the mapping, rather than
 * guessing the enum's class name).
 *
 * <h2>Scope: this hook now owns all likes-grid navigation</h2>
 *
 * Because a call-through is impossible, this hook's {@code navigate_hook}
 * runs for <b>every</b> tap on this controller, not just gated ones. For a
 * non-gated (already-real) id it still goes through {@link #dispatchNavigation}
 * -- same route-building, same {@code CameFrom} lookup, same {@code Bundle},
 * same listener invocation -- simply without any id substitution, so the
 * effect is the same as the original body's. This is different from how
 * every other hook in this patcher works (each of those sits beside the
 * original method and only ever changes one gated value); this one replaces
 * the method outright and is the sole source of its behaviour from here on.
 * A reflection failure here (e.g. a future app update renaming
 * {@code navigateToListener}) therefore surfaces as "Couldn't open this
 * profile" for <b>any</b> card on this controller, gated or not -- that is
 * the honest cost of the crash above leaving no call-through option, not a
 * regression hidden behind a comment.
 *
 * <h2>The nav/vote split still holds</h2>
 *
 * The resolved id is substituted only in the local {@code route} string this
 * hook builds; the {@code Bundle}'s {@code User} extra is the original,
 * unmodified object, placeholder id and all. {@code User.getUserid()}/
 * {@code getId()} are never hooked on either method. That is what keeps this
 * feature structurally unable to affect
 * {@code LikesPageViewModel.submitVote} (which reads {@code getId()}, a
 * distinct accessor over the same field) -- there is no shared hook surface
 * between navigation and anything else to keep in sync, by construction.
 */
public class OpenRealProfile implements Hook {

    private static final String CAME_FROM_TAG_KEY = "com.okcupid.okcupid.came_from_tag";
    private static final String USER_EXTRA_KEY = "app.okcupid.user.extra";

    private static volatile Field useridField;             // User.userid
    private static volatile Field navigateToListenerField; // LikesPageController.navigateToListener
    private static volatile Method function2Invoke;        // kotlin.jvm.functions.Function2.invoke(Object,Object)
    private static volatile Method getCurrentDataMethod;   // TypedEpoxyController.getCurrentData()
    private static volatile Method getLikesPageConfigMethod; // LikesPageState.getLikesPageConfiguration(), resolved by name off the runtime instance
    private static volatile Class<?> cameFromClass;        // SharedEventKeys$CameFrom

    /**
     * One pending navigation per placeholder id, however many taps arrive
     * before it runs. The guard logic itself (claim/release/the poster
     * abstraction) lives in the core as {@link NavigateOnce} so it is
     * JVM-testable; only the Android {@link Handler} posting is here.
     */
    private static final NavigateOnce NAVIGATION = new NavigateOnce();

    private static final NavigateOnce.Poster MAIN_THREAD = new NavigateOnce.Poster() {
        @Override public boolean post(Runnable task) {
            return new Handler(Looper.getMainLooper()).post(task);
        }
    };

    private static final AtomicBoolean LOGGED_CURRENT_DATA = new AtomicBoolean();
    private static final AtomicBoolean LOGGED_CAME_FROM = new AtomicBoolean();

    /**
     * The {@code CameFrom} this controller's own {@code WhenMappings} switch
     * would have produced, by name rather than by enum ordinal -- ordinals
     * are compiler-assigned declaration order, which is a fact about this
     * build, not a stable contract. {@code LikesPageConfiguration}'s own
     * constant names ({@code INTROS}/{@code LIKES_YOU}/{@code YOU_LIKE}) are
     * read directly off whatever {@code getCurrentData()} returns, so this
     * never needs that class's name either.
     *
     * Degradation: an unreadable configuration maps to {@code DEFAULT}, as
     * the original's null branch does. Returns null (so the caller omits
     * {@code came_from_tag}) only if even {@code DEFAULT} cannot be found on
     * the enum, i.e. the enum is not the one this hook was built against.
     */
    private static Object cameFromFor(Object likesPageState) {
        String configName = null;
        if (likesPageState != null) {
            try {
                if (getLikesPageConfigMethod == null) {
                    getLikesPageConfigMethod = likesPageState.getClass()
                            .getMethod("getLikesPageConfiguration");
                }
                Object configuration = getLikesPageConfigMethod.invoke(likesPageState);
                if (configuration instanceof Enum) {
                    configName = ((Enum<?>) configuration).name();
                }
            } catch (Throwable t) {
                // Falls through to DEFAULT, exactly as the original body's
                // null-configuration branch does.
                logOnce(LOGGED_CAME_FROM, "OpenRealProfile: configuration read failed -- " + t);
            }
        }
        String cameFromName;
        if ("INTROS".equals(configName)) {
            cameFromName = "INTROS";
        } else if ("LIKES_YOU".equals(configName)) {
            cameFromName = "WHO_LIKES_YOU";
        } else if ("YOU_LIKE".equals(configName)) {
            cameFromName = "WHO_YOU_LIKE";
        } else {
            cameFromName = "DEFAULT";
        }
        Object found = cameFromConstant(cameFromName);
        if (found == null && !"DEFAULT".equals(cameFromName)) {
            found = cameFromConstant("DEFAULT");        // degrade, as documented
        }
        if (found == null) {
            logOnce(LOGGED_CAME_FROM, "OpenRealProfile: no CameFrom constant found -- came_from_tag omitted");
        }
        return found;
    }

    private static Object cameFromConstant(String name) {
        try {
            for (Object constant : cameFromClass.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(name)) {
                    return constant;
                }
            }
        } catch (Throwable t) {
            logOnce(LOGGED_CAME_FROM, "OpenRealProfile: CameFrom lookup failed -- " + t);
        }
        return null;
    }

    private static void logOnce(AtomicBoolean flag, String message) {
        if (flag.compareAndSet(false, true)) {
            Log.w(HookUtil.TAG, message);
        }
    }

    /**
     * Faithfully replicates {@code triggerNavigateToProfile}'s own
     * route-building, {@code CameFrom} lookup, {@code Bundle} construction
     * and listener dispatch -- see the class doc for the full original body
     * this mirrors. {@code effectiveId} is whatever the route should
     * actually contain: the field's own value for a pass-through, or the
     * resolved real id for a substitution.
     */
    private static void dispatchNavigation(Object thiz, Object user, String effectiveId) {
        if (effectiveId == null) {
            // A failed id read must not become the literal route "/profile/null".
            Log.e(HookUtil.TAG, "OpenRealProfile: no id to navigate with");
            toast("Couldn't open this profile");
            return;
        }
        try {
            String route = "/profile/" + effectiveId;

            Object likesPageState = null;
            try {
                likesPageState = getCurrentDataMethod.invoke(thiz);
            } catch (Throwable t) {
                // Matches the original's own null-safety: a getCurrentData()
                // that isn't a LikesPageState falls through to null here too.
                logOnce(LOGGED_CURRENT_DATA, "OpenRealProfile: getCurrentData failed -- " + t);
            }
            Object cameFrom = cameFromFor(likesPageState);

            Bundle bundle = new Bundle();
            if (cameFrom instanceof java.io.Serializable) {
                bundle.putSerializable(CAME_FROM_TAG_KEY, (java.io.Serializable) cameFrom);
            }
            bundle.putParcelable(USER_EXTRA_KEY, (Parcelable) user);

            Object listener = navigateToListenerField.get(thiz);
            if (listener != null) {
                function2Invoke.invoke(listener, route, bundle);
            } else {
                // The original body also does nothing here (if-eqz p1,
                // :cond_5 -- a null listener is a silent no-op even
                // unpatched). Logged rather than left silent so a future
                // unbound-controller case is diagnosable instead of looking
                // like a dead tap.
                Log.w(HookUtil.TAG, "OpenRealProfile: navigateToListener is null -- no-op, as unpatched");
            }
        } catch (Throwable t) {
            Log.e(HookUtil.TAG, "OpenRealProfile: dispatch failed -- " + t);
            toast("Couldn't open this profile");
        }
    }

    /** Opens the profile once; a second tap while one is pending does nothing. */
    private static void navigateOnce(final Object thiz, String placeholderId, final Object user,
                                     final String effectiveId) {
        NAVIGATION.navigate(placeholderId, MAIN_THREAD, new Runnable() {
            @Override public void run() {
                dispatchNavigation(thiz, user, effectiveId);
            }
        }, new Runnable() {
            @Override public void run() {
                Log.e(HookUtil.TAG, "OpenRealProfile: navigation failed");
                toast("Couldn't open this profile");
            }
        });
    }

    private static String currentId(Object user) {
        try {
            return (String) useridField.get(user);
        } catch (Throwable t) {
            Log.e(HookUtil.TAG, "OpenRealProfile: id read failed -- " + t);
            return null;
        }
    }

    static void navigate_hook(Object thiz, final Object user) {
        String id = currentId(user);
        if (!IdentityStore.isPlaceholder(id)) {
            // Not gated: dispatch exactly as the original body would have,
            // with the id the field already holds. This hook now runs for
            // every tap on this controller (a call-through was never an
            // option here -- see the class doc), so this branch is what
            // keeps a normal profile's navigation identical to unpatched.
            dispatchNavigation(thiz, user, id);
            return;
        }
        try {
            IdentityStore store = IdentityStore.get();
            String realId = store.realIdForPlaceholder(id);
            if (realId != null) {
                Log.i(HookUtil.TAG, "OpenRealProfile: substituting a resolved id");
                navigateOnce(thiz, id, user, realId);
                return;
            }
            String photoPath = store.photoPathFor(id);
            if (photoPath == null) {
                Log.e(HookUtil.TAG, "OpenRealProfile: no photo path for this card");
                toast("Can't identify this card");
                return;
            }
            final Object target = thiz;
            final Object targetUser = user;
            final String placeholderId = id;
            ensureResolverConfigured();
            // Toasted BEFORE the call, not after: resolve() can report an
            // outcome (e.g. NOT_READY) from its own thread before returning
            // here, and "Identifying..." must never land after it.
            toast("Identifying…");
            LikesIdentityResolver.Start start = LikesIdentityResolver.resolve(photoPath,
                    LikesCursorCapture.seenCursors(), new LikesIdentityResolver.Callback() {
                @Override public void onOutcome(LikesIdentityResolver.Outcome outcome) {
                    if (outcome == LikesIdentityResolver.Outcome.RESOLVED) {
                        String resolved = IdentityStore.get().realIdForPlaceholder(placeholderId);
                        if (resolved != null) {
                            navigateOnce(target, placeholderId, targetUser, resolved);
                        } else {
                            toast("Couldn't identify this person");
                        }
                        return;
                    }
                    String message = OutcomeMessages.messageFor(outcome);
                    if (message != null) {
                        toast(message);
                    }
                }
            });
            if (start == LikesIdentityResolver.Start.BUSY) {
                toast("Still identifying…");       // lost the race to another tap
            } else if (start == LikesIdentityResolver.Start.FAILED) {
                toast("Couldn't start identifying");    // distinct from a lost race
            }
        } catch (Throwable t) {
            Log.e(HookUtil.TAG, "OpenRealProfile: " + t.getClass().getName());
            toast("Something went wrong opening this profile");
        }
    }

    private static void toast(final String message) {
        try {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    Toast.makeText(Utils.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void load() {
        try {
            // Resolver configuration is deliberately NOT done here: it would
            // run app code (<clinit> of the sort enum and the query document
            // holder) from the provider's onCreate, before Application.onCreate,
            // and a failed <clinit> poisons the class for the app's own later
            // use. It runs lazily on the first tap instead (see
            // ensureResolverConfigured). The hook installs regardless: the
            // boundary-card path needs no resolver, and an unconfigured
            // resolver reports NOT_READY on its own.
            Class<?> userClass = Class.forName("{{DEANON_NAV_ID_CLASS_NAME}}", false,
                    OpenRealProfile.class.getClassLoader());
            Field idField = userClass.getDeclaredField("userid");
            idField.setAccessible(true);
            // DEANON_NAV_ID_METHOD_NAME/_SIG are logged here, not verified --
            // this hook reads the field directly rather than calling the
            // accessor, so a rename of the accessor itself would not be
            // caught by this log; it is a breadcrumb for diagnosing a future
            // drift, not a runtime check.
            Log.i(HookUtil.TAG, "OpenRealProfile: navigation reads the id via "
                    + "{{DEANON_NAV_ID_METHOD_NAME}}{{DEANON_NAV_ID_METHOD_SIG}}");

            Class<?> controllerClass = Class.forName("{{NAV_TRIGGER_CLASS_NAME}}", false,
                    OpenRealProfile.class.getClassLoader());
            Field listenerField = controllerClass.getDeclaredField("navigateToListener");
            listenerField.setAccessible(true);

            // com.airbnb.epoxy.TypedEpoxyController -- a third-party library
            // class, not an app type, so (like okhttp3's classes elsewhere in
            // this project) it is named directly rather than through a
            // finder: no app build obfuscates a dependency's own classes.
            Class<?> epoxyControllerClass = Class.forName("com.airbnb.epoxy.TypedEpoxyController",
                    false, OpenRealProfile.class.getClassLoader());
            Method getCurrentData = epoxyControllerClass.getMethod("getCurrentData");

            // kotlin.jvm.functions.Function2 -- Kotlin stdlib, same posture.
            Class<?> function2Class = Class.forName("kotlin.jvm.functions.Function2", false,
                    OpenRealProfile.class.getClassLoader());
            Method invoke = function2Class.getMethod("invoke", Object.class, Object.class);

            // The CameFrom enum, found by [LikesNavTrigger] from inside
            // triggerNavigateToProfile's own body (its DEFAULT constant),
            // not guessed here.
            Class<?> cameFrom = Class.forName("{{NAV_TRIGGER_CAME_FROM_CLASS_NAME}}", false,
                    OpenRealProfile.class.getClassLoader());

            useridField = idField;
            navigateToListenerField = listenerField;
            function2Invoke = invoke;
            getCurrentDataMethod = getCurrentData;
            cameFromClass = cameFrom;

            Method replacement = OpenRealProfile.class.getDeclaredMethod(
                    "navigate_hook", Object.class, Object.class);
            HookUtil.install("OpenRealProfile",
                    "{{NAV_TRIGGER_CLASS_NAME}}",
                    "{{NAV_TRIGGER_METHOD_NAME}}",
                    "{{NAV_TRIGGER_METHOD_SIG}}",
                    replacement);
        } catch (Throwable t) {
            Log.e(HookUtil.TAG, "OpenRealProfile: " + t);
        }
    }

    private static volatile boolean resolverConfigured;
    private static final AtomicBoolean LOGGED_UNCONFIGURED = new AtomicBoolean();

    /**
     * First-tap configuration. Taps are long after startup, so initializing
     * app classes here is safe. A failure is retried on the next tap (logged
     * once); meanwhile resolve() reports NOT_READY because no document is set.
     */
    private static synchronized void ensureResolverConfigured() {
        if (resolverConfigured) {
            return;
        }
        resolverConfigured = configureResolver();
    }

    /** Reads the sort orders and the query document out of the app itself. */
    private static boolean configureResolver() {
        try {
            ClassLoader cl = OpenRealProfile.class.getClassLoader();

            Class<?> sortEnum = Class.forName("{{LIKES_SORT_ENUM_CLASS_NAME}}", true, cl);
            List<String> sorts = new ArrayList<String>();
            Object[] constants = sortEnum.getEnumConstants();
            for (Object c : constants != null ? constants : new Object[0]) {
                sorts.add(((Enum<?>) c).name());
            }

            Class<?> docHolder = Class.forName("{{LIKES_QUERY_DOC_CLASS_NAME}}", true, cl);
            Method getDoc = docHolder.getMethod("{{LIKES_QUERY_DOC_METHOD_NAME}}");
            // A Kotlin companion getter needs its instance; a static one does
            // not. instanceOf returns null when there is no companion field,
            // which is exactly what invoke() wants for a static method.
            Object receiver = java.lang.reflect.Modifier.isStatic(getDoc.getModifiers())
                    ? null : instanceOf(docHolder);
            String document = (String) getDoc.invoke(receiver);

            // The document opens with "query <OperationName>(", which is the
            // operation name the endpoint also expects as a path segment.
            String opName = document.substring(document.indexOf(' ') + 1,
                    document.indexOf('(')).trim();

            LikesIdentityResolver.configure(sorts, opName, document);
            Log.i(HookUtil.TAG, "OpenRealProfile: resolver configured with "
                    + LikesIdentityResolver.usableSortNames(sorts).size() + " sort order(s)");
            return true;
        } catch (Throwable t) {
            logOnce(LOGGED_UNCONFIGURED, "OpenRealProfile: resolver not configured -- " + t);
            return false;
        }
    }

    /** The getter lives on a Kotlin companion; its instance is a static field of the outer class. */
    private static Object instanceOf(Class<?> type) throws Exception {
        Class<?> outer = type.getDeclaringClass();
        if (outer != null) {
            for (java.lang.reflect.Field f : outer.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && type.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f.get(null);
                }
            }
        }
        throw new IllegalStateException("no companion instance for " + type.getName());
    }

    @Override
    public void unload() {
    }
}
