package com.smali_generator.patches;

import com.smali_generator.Hook;

import java.lang.reflect.Method;

/**
 * The Compose render half of the unblur.
 *
 * `UserCardComposeKt` branches on the card state's own copy of the flag to
 * choose the blurred composition, independently of the `User` flag that
 * {@link BlurredUserFlag} handles. One picks the clear URL, this one stops the
 * card drawing it blurred.
 */
public class BlurredCardFlag implements Hook {

    static boolean show_blurred_hook(Object thiz) {
        return false;
    }

    @Override
    public void load() {
        try {
            Method replacement = BlurredCardFlag.class.getDeclaredMethod(
                    "show_blurred_hook", Object.class);
            HookUtil.install("BlurredCardFlag",
                    "{{BLURRED_CARD_FLAG_CLASS_NAME}}",
                    "{{BLURRED_CARD_FLAG_METHOD_NAME}}",
                    "{{BLURRED_CARD_FLAG_METHOD_SIG}}",
                    replacement);
        } catch (Throwable t) {
            android.util.Log.e(HookUtil.TAG, "BlurredCardFlag: " + t);
        }
    }

    @Override
    public void unload() {
    }
}
