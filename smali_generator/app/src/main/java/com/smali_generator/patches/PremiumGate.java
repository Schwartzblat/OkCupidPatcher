package com.smali_generator.patches;

import android.util.Log;

import com.smali_generator.Hook;

import java.lang.reflect.Method;

/**
 * Turns every paid OkCupid feature on, at every class that carries the gate.
 *
 * The app has no isPremium() flag: each paid feature is gated by
 * {@code boolean getUserHasPremium(PremiumFeatures feature)}, which looks the
 * feature up in the session's premium map. Forcing that predicate true unlocks
 * all ten PremiumFeatures (ADFREE, ALIST_BASIC, ALIST_PREMIUM,
 * ALIST_PREMIUM_PLUS, INCOGNITO_BUNDLE, INTROS, READ_RECEIPTS,
 * SEE_PUBLIC_QUESTIONS, UNLIMITED_LIKES, VIEW_VOTES) at every call site at
 * once, and everything derived from it (hasOkCupidSubscription,
 * getUserSubscription, the per-screen isAList() wrappers) follows.
 *
 * Two classes carry the predicate on 116.0.0 -- the UserProvider
 * implementation and the session singleton -- so the finder hands over a
 * {@code |}-separated list of {@code class:method:sig} triples rather than one
 * named target, and this hook installs the same replacement on each. Which
 * classes those are is never written down here; that is the whole point.
 */
public class PremiumGate implements Hook {

    private static final String TARGETS = "{{PREMIUM_GATE_TARGETS}}";

    /**
     * Instance targets only -- the finder's regex cannot match a static method
     * -- so the replacement is static with a leading thiz. The PremiumFeatures
     * argument is a reference, so Object is enough and keeps this class free of
     * any app type at compile time.
     */
    static boolean has_premium_hook(Object thiz, Object feature) {
        return true;
    }

    @Override
    public void load() {
        try {
            if (TARGETS.startsWith("{" + "{")) {
                // The finder never fired and patch_artifacts left the literal
                // placeholder behind. Compiles fine, hooks nothing -- say so.
                Log.e(HookUtil.TAG, "PremiumGate: placeholder unsubstituted -- "
                        + "the finder did not fire; no premium gate is hooked");
                return;
            }

            Method replacement = PremiumGate.class.getDeclaredMethod(
                    "has_premium_hook", Object.class, Object.class);

            String[] targets = TARGETS.split("\\|");
            int hooked = 0;
            for (String target : targets) {
                String[] parts = target.split(":");
                if (parts.length != 3) {
                    Log.e(HookUtil.TAG, "PremiumGate: malformed target '" + target + "'");
                    continue;
                }
                if (HookUtil.install("PremiumGate", parts[0], parts[1], parts[2], replacement)) {
                    hooked++;
                }
            }

            if (hooked == 0) {
                Log.e(HookUtil.TAG, "PremiumGate: hooked nothing out of "
                        + targets.length + " target(s)");
            } else {
                Log.i(HookUtil.TAG, "PremiumGate: " + hooked + "/" + targets.length
                        + " premium gate(s) forced true");
            }
        } catch (Throwable t) {
            Log.e(HookUtil.TAG, "PremiumGate: " + t);
        }
    }

    @Override
    public void unload() {
    }
}
