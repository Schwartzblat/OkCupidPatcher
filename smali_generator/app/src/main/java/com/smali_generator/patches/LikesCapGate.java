package com.smali_generator.patches;

import com.smali_generator.Hook;

import java.lang.reflect.Method;

/**
 * The daily-likes cap.
 *
 * UNLIMITED_LIKES through {@link PremiumGate} is the cleaner fix; this also
 * covers the rate-card "breather" logic that reads the cap directly.
 */
public class LikesCapGate implements Hook {

    /**
     * Inverted sense, unlike every other hook here: the target answers "have
     * you run out of likes", so the unlock value is false, not true.
     */
    static boolean has_reached_cap_hook(Object thiz) {
        return false;
    }

    @Override
    public void load() {
        try {
            Method replacement = LikesCapGate.class.getDeclaredMethod(
                    "has_reached_cap_hook", Object.class);
            HookUtil.install("LikesCapGate",
                    "{{LIKES_CAP_GATE_CLASS_NAME}}",
                    "{{LIKES_CAP_GATE_METHOD_NAME}}",
                    "{{LIKES_CAP_GATE_METHOD_SIG}}",
                    replacement);
        } catch (Throwable t) {
            android.util.Log.e(HookUtil.TAG, "LikesCapGate: " + t);
        }
    }

    @Override
    public void unload() {
    }
}
