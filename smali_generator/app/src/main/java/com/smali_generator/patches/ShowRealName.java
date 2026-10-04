package com.smali_generator.patches;

import com.smali_generator.Hook;
import com.smali_generator.likes.IdentityStore;
import com.smali_generator.likes.NameResolver;
import com.smali_generator.likes.PhotoPaths;

import java.lang.reflect.Method;

/**
 * Draws the recovered name where the grid would draw {@code "------"}.
 *
 * <p>The gated Likes grid renders a stand-in for every hidden person:
 * {@code toBlurredUser} fabricates the {@code User} with that literal and the
 * card's state object carries it through to the composition. By the time a
 * card is drawn, though, the sweep has usually already recovered who it is --
 * so this substitutes the stored name at the last possible moment, in the one
 * accessor the card reads.
 *
 * <p>Which card is which comes from the state object's own image URL,
 * normalized to the photo path that is this package's join key. So the name
 * drawn on a card is the name that was verified against that same card's
 * photo path when it was looked up ({@code NameResolver.belongsTo}) -- the
 * identity never travels by position or by index, which is what keeps a
 * stranger's name off someone else's card.
 *
 * <p>A full reimplementation, not a call-through. The getter is a plain
 * field read, so this reads the same field; that is not an optimisation but
 * a requirement. A three-argument hook that called the backup segfaulted in
 * {@code ExecuteNterpImpl} the first time the grid composed, on
 * {@code UserCardComposeKt.IntroNameAndLocation} -- an `iget-object` entry is
 * no safer to call back into than the `const-string` and `invoke-virtual`
 * entries already known to be unsafe. See NOTES.md's cold-start rule.
 *
 * <p>Anything that already has a real name (every non-gated entry) is
 * returned untouched, and so is a card this patcher has never named.
 *
 * <p>Read-only by construction: it returns a string for the UI and touches no
 * identity the vote path reads.
 */
public class ShowRealName implements Hook {

    private static volatile Method imageGetter;
    private static volatile boolean imageGetterMissing;
    private static volatile java.lang.reflect.Field nameField;
    private static volatile boolean nameFieldMissing;

    static String card_name_hook(Object thiz) {
        String original = originalNameOf(thiz);
        try {
            if (NameResolver.isRealName(original)) {
                return original;            // a non-gated card already says who it is
            }
            // Cheap, and only on the gated cards: everything the sweep has
            // learned is already in memory by now.
            com.smali_generator.likes.LikesSweep.primeNamesIfNeeded();
            String path = PhotoPaths.normalize(imageUrlOf(thiz));
            String known = path == null ? null : IdentityStore.get().nameFor(path);
            return known != null ? known : original;
        } catch (Throwable t) {
            return original;                // never let the grid fail to draw
        }
    }

    /** What the getter would have returned: the field it reads, read directly. */
    private static String originalNameOf(Object thiz) {
        if (nameFieldMissing || thiz == null) {
            return null;
        }
        try {
            java.lang.reflect.Field field = nameField;
            if (field == null) {
                field = thiz.getClass().getDeclaredField("{{CARD_NAME_FIELD_NAME}}");
                field.setAccessible(true);
                nameField = field;
            }
            Object value = field.get(thiz);
            return value instanceof String ? (String) value : null;
        } catch (Throwable t) {
            nameFieldMissing = true;        // stop paying for the lookup every draw
            return null;
        }
    }

    private static String imageUrlOf(Object thiz) throws Exception {
        if (imageGetterMissing) {
            return null;
        }
        Method getter = imageGetter;
        if (getter == null) {
            try {
                getter = thiz.getClass().getMethod("{{CARD_IMAGE_METHOD_NAME}}");
                getter.setAccessible(true);
                imageGetter = getter;
            } catch (Throwable t) {
                imageGetterMissing = true;  // stop paying for the lookup every draw
                return null;
            }
        }
        Object url = getter.invoke(thiz);
        return url instanceof String ? (String) url : null;
    }

    @Override
    public void load() {
        try {
            Method replacement = ShowRealName.class.getDeclaredMethod(
                    "card_name_hook", Object.class);
            HookUtil.install("ShowRealName",
                    "{{CARD_NAME_CLASS_NAME}}",
                    "{{CARD_NAME_METHOD_NAME}}",
                    "{{CARD_NAME_METHOD_SIG}}",
                    replacement);
        } catch (Throwable t) {
            android.util.Log.e(HookUtil.TAG, "ShowRealName: " + t);
        }
    }

    @Override
    public void unload() {
    }
}
