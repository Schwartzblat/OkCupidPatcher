package com.smali_generator.patches;

import android.util.Log;

import com.arthooks.ArtHooks;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;

/**
 * Shared install path for every hook in this patcher: resolve the target by
 * the descriptor a finder discovered, redirect it, and log the outcome either
 * way. A hook that logs nothing is indistinguishable from one that never ran.
 */
final class HookUtil {

    static final String TAG = "PATCH";

    private HookUtil() {
    }

    /**
     * Replace the target outright -- the two-argument ArtHooks form.
     *
     * @param label       what shows up in logcat
     * @param className   dotted FQN from the finder's {{..._CLASS_NAME}}
     * @param methodName  from {{..._METHOD_NAME}}
     * @param methodSig   JNI descriptor from {{..._METHOD_SIG}}; this is what
     *                    picks one overload out of several
     * @param replacement must be static; for an instance target it takes a
     *                    leading Object thiz
     */
    static boolean install(String label, String className, String methodName,
                           String methodSig, Method replacement) {
        return install(label, className, methodName, methodSig, replacement, null);
    }

    /**
     * Same, but the three-argument ArtHooks form: {@code backup} is pointed at
     * the target's pre-hook body so the replacement can call through -- which
     * is how a hook filters an argument or post-processes a result instead of
     * discarding the original behaviour.
     *
     * <p>{@code backup} must have the <b>identical</b> signature to
     * {@code replacement}. Its own body is never executed; only its entry
     * point is rewritten.
     */
    static boolean install(String label, String className, String methodName,
                           String methodSig, Method replacement, Method backup) {
        try {
            // initialize=false on purpose. This runs before the host
            // Application's onCreate, and forcing <clinit> on an app class
            // that early can crash startup. Hooking only needs the class
            // loaded, not initialised.
            //
            // A/B tested on 116.0.0 / API 36 against initialize=true: both
            // unblur the likes grid and open the gate identically, so this
            // keeps the safer startup posture. (A debugging session once
            // blamed this flag for hooks "not taking". It was wrong -- the
            // real cause was attaching Frida to the running process, which
            // wipes ArtHooks' entry points. See NOTES.md.)
            Class<?> target = Class.forName(className, false,
                    HookUtil.class.getClassLoader());

            Executable original = ArtHooks.find_function(target, methodName, methodSig);
            if (original == null) {
                Log.e(TAG, label + ": target not found -- " + className + "."
                        + methodName + methodSig);
                return false;
            }

            boolean ok = backup == null
                    ? ArtHooks.hook_function(original, replacement)
                    : ArtHooks.hook_function(original, replacement, backup);
            if (ok) {
                Log.i(TAG, label + ": hooked " + className + "." + methodName + methodSig);
            } else {
                Log.e(TAG, label + ": hook_function refused " + className + "."
                        + methodName + methodSig);
            }
            return ok;
        } catch (Throwable t) {
            // Never let this escape: load() runs during app startup.
            Log.e(TAG, label + ": " + t);
            return false;
        }
    }
}
