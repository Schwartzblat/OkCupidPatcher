package com.smali_generator.patches;

import com.smali_generator.Hook;

import java.lang.reflect.Method;

/**
 * Unblurs the LIKED YOU / VIEWED YOU thumbnails.
 *
 * The teaser user arrives with two image URLs -- a clear `primaryImage
 * { square225 }` and a server-blurred `primaryImageBlurred { square800 }` --
 * and `OkUserCardViewModel.getUserImage()` picks between them on this flag:
 *
 * <pre>
 * if (user.getShowBlurred()) return getBlurredPhoto();   // square800, blurred
 * else return photos[0].get_400x400() != null
 *          ? photos[0].get_400x400()
 *          : photos[0].get_225x225();                    // the clear one
 * </pre>
 *
 * Forcing it false makes the card load the clear thumbnail the client already
 * received, so this works without rewriting any URL or issuing a new request.
 * The trade-off is resolution: the clear image is only offered at square225,
 * where the blurred one is square800, so expect a softer thumbnail.
 */
public class BlurredUserFlag implements Hook {

    static boolean show_blurred_hook(Object thiz) {
        return false;
    }

    @Override
    public void load() {
        try {
            Method replacement = BlurredUserFlag.class.getDeclaredMethod(
                    "show_blurred_hook", Object.class);
            HookUtil.install("BlurredUserFlag",
                    "{{BLURRED_USER_FLAG_CLASS_NAME}}",
                    "{{BLURRED_USER_FLAG_METHOD_NAME}}",
                    "{{BLURRED_USER_FLAG_METHOD_SIG}}",
                    replacement);
        } catch (Throwable t) {
            android.util.Log.e(HookUtil.TAG, "BlurredUserFlag: " + t);
        }
    }

    @Override
    public void unload() {
    }
}
