package com.smali_generator.patches;

import com.smali_generator.Hook;

import java.lang.reflect.Method;

/**
 * Incognito mode availability.
 *
 * UserProviderConcrete has an instance method of the same name that falls
 * through to this static one, so hooking this single static covers both.
 */
public class IncognitoGate implements Hook {

    /**
     * The target is genuinely `public static final`, so the replacement takes
     * NO leading thiz -- a static target's argument layout has no receiver.
     */
    static boolean is_incognito_hook() {
        return true;
    }

    @Override
    public void load() {
        try {
            Method replacement = IncognitoGate.class.getDeclaredMethod("is_incognito_hook");
            HookUtil.install("IncognitoGate",
                    "{{INCOGNITO_GATE_CLASS_NAME}}",
                    "{{INCOGNITO_GATE_METHOD_NAME}}",
                    "{{INCOGNITO_GATE_METHOD_SIG}}",
                    replacement);
        } catch (Throwable t) {
            android.util.Log.e(HookUtil.TAG, "IncognitoGate: " + t);
        }
    }

    @Override
    public void unload() {
    }
}
