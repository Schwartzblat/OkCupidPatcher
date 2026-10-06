package com.smali_generator.patches;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import com.smali_generator.Hook;
import com.smali_generator.picks.ButtonPlacement;
import com.smali_generator.picks.LikedOnce;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Adds a plain Like to every Cupid's Picks card.
 *
 * <h2>What the Picks screen offers without this</h2>
 *
 * Two taps per card, and only two -- read straight out of
 * {@code HighlightModel.bind}'s smali:
 *
 * <pre>
 * findViewById(id/card)         -> listener.onProfileSelected(props)    ; opens /profile/&lt;id&gt;
 * findViewById(id/superlikeFab) -> listener.onProfileSuperLiked(props)  ; SuperLike
 * </pre>
 *
 * There is no plain Like anywhere on the screen. And the SuperLike is not a
 * substitute: {@code StandoutsFragment.onProfileSuperLiked} branches on
 * {@code ViewState.hasSuperlikeTokens}, and with no tokens -- the normal state
 * for an account this patcher unlocked but that is not really paying -- it
 * calls {@code showSuperlikeRateCard("superlike.standouts", ...,
 * MonetizationAreaId.CupidsPicks_SuperLike)}. So the only action the card
 * offers today is a purchase wall, and liking a Pick means opening the profile
 * and coming back.
 *
 * <p>This puts a Like button on the card, immediately inward of the SuperLike
 * FAB, and sends the app's own vote through the app's own service. Nothing
 * about the existing SuperLike or card-tap wiring is changed.
 *
 * <h2>Why the hook is on Kotlin's bind bridge, and takes no backup</h2>
 *
 * The button has to be added <em>after</em> the original binding has run, and
 * NOTES.md's "ArtHooks backup-entry-instruction rule" rules out a 3-argument
 * call-through on anything whose entry instruction is not known safe (two
 * confirmed-unsafe entries, one weakly-consistent row, everything else
 * untested; {@code HighlightModel.bind}'s entry is a plain {@code const}, for
 * which there is no evidence either way -- so the rule says reimplement).
 *
 * Kotlin's covariant-override bridge makes that reimplementation trivial and
 * exact. Each concrete card model carries:
 *
 * <pre>
 * .method public bridge synthetic bind(Ljava/lang/Object;)V
 *     check-cast p1, Landroidx/constraintlayout/widget/ConstraintLayout;
 *     invoke-virtual {p0, p1}, L&lt;Self&gt;;-&gt;bind(Landroidx/constraintlayout/widget/ConstraintLayout;)V
 *     return-void
 * .end method
 * </pre>
 *
 * ...and the bridge is what Epoxy calls, through
 * {@code EpoxyModel.bind(Object)}. So the replacement below reproduces the
 * whole body -- a cast (reflection's own argument check) and a virtual call to
 * the specialised {@code bind} -- and that method is <b>not</b> hooked, so the
 * original binding, including the subclass's super-call into the base, runs
 * unmodified. No backup is installed and nothing can recurse.
 *
 * <p>Hooking the <i>base</i> class's bridge would never fire: both concrete
 * models declare their own, and theirs override it.
 *
 * <h2>How the vote is sent</h2>
 *
 * Through {@code BatchVoteService.submitVote(userId, voteSource, user, true)},
 * the same primitive {@code LikeManager.submitLike} (the profile FAB) and
 * {@code LikesPageViewModel.submitVote} (the Likes grid) use. See
 * {@code picks_vote.py} for how it and the service are found, and for why
 * {@code DOUBLETAKE} is the honest source tag.
 *
 * <p>The service is reached without a DI graph of this patch's own: the host
 * {@code Application} is the graph root ({@code DiExtensionsKt.getOkGraph} is
 * {@code ((OkGraphProvider) context.applicationContext).getOkGraph()}), so
 * {@link #voteService(Context)} walks zero-argument getters outward from the
 * Application object until one's declared <b>return type</b> is the vote
 * service interface. The search is over types only; the single getter chain it
 * ends up invoking is the one that leads to the service, so no unrelated lazy
 * singleton is constructed as a side effect of looking.
 *
 * <h2>What it refuses to do</h2>
 *
 * <ul>
 *   <li>Never run on the main thread. The vote is dispatched on a private
 *       single-thread executor, because the Flowable the service returns
 *       carries no scheduler of its own -- the app's own call sites wrap it in
 *       {@code KotlinExtensionsKt.setupOnMain} before subscribing, and
 *       subscribing to it from the bind path would put a network mutation on
 *       the UI thread.</li>
 *   <li>Never send twice for the same person. {@link LikedOnce} is claimed
 *       before anything is sent and released only if the send failed; a
 *       recycled card comes back already spent. Each duplicate would cost a
 *       real like against the daily cap.</li>
 *   <li>Never let its own failure break the card. Everything after the
 *       faithful bridge call is wrapped: a card that renders without the extra
 *       button is a missing feature, a card that throws out of {@code bind} is
 *       a crash.</li>
 *   <li>Never guess a layout, and never share the FAB's layout state. The
 *       button is laid out exactly on top of the FAB -- a fresh params object
 *       of the FAB's own class, carrying the FAB's constraints and margins by
 *       value -- and then translated clear of it. It must <b>not</b> go
 *       through {@code ConstraintLayout$LayoutParams}' copy constructor, which
 *       aliases the source's solver {@code ConstraintWidget} and laid both
 *       views out at one position on the first device run; the measurement and
 *       the mechanism are in {@link #build_button}. If the FAB is not found,
 *       or no fresh params object can be built, no button is added rather than
 *       one pinned to coordinates this patch invented.</li>
 *   <li>Never leave an unreachable tap target. The offset is not asserted but
 *       checked: one frame after the first button is laid out its on-screen
 *       rectangle is compared with the FAB's, and on any intersection the
 *       button is withdrawn for the rest of the process. A hidden Like sitting
 *       on the SuperLike FAB would make a tap there ambiguous between sending
 *       a like and opening a rate card, which is worse than no button. Both
 *       rectangles are logged under PATCH either way.</li>
 * </ul>
 */
public final class PicksLikeButton implements Hook {

    /** Identifies the added button inside a recycled card. */
    private static final String VIEW_TAG = "okc_patch_picks_like";

    /**
     * The host's own white Like hearts, in preference order: the Compose
     * card's, then the profile FAB's. Looked up by name through
     * {@code Resources.getIdentifier} rather than by id, because resource ids
     * shift between releases while these names do not -- the app does not
     * obfuscate its resource table. Both are white-filled vectors meant to sit
     * on a coloured FAB, which is what {@link #circle(int)} provides. If
     * neither resolves, {@link HeartDrawable} draws one.
     */
    private static final String[] LIKE_DRAWABLES = {"vote_like_heart", "fab_like"};

    /** The host's Like pink, by name, with its 116.0.0 value as the fallback. */
    private static final String LIKE_COLOR_NAME = "darkPink";
    private static final int LIKE_COLOR_FALLBACK = 0xFF96256A;

    /** Share of the button's side length left as padding around the heart. */
    private static final float ICON_INSET = 0.30f;

    private static final String TARGETS = "{{PICKS_CARD_TARGETS}}";
    private static final String BIND_VIEW_CLASS = "{{PICKS_CARD_BIND_VIEW_CLASS_NAME}}";
    private static final String FAB_CLASS = "{{PICKS_FAB_CLASS_NAME}}";
    private static final String PROPS_FIELD = "{{PICKS_PROPS_FIELD_NAME}}";
    private static final String PROPS_USER_METHOD = "{{PICKS_PROPS_USER_METHOD_NAME}}";
    private static final String PICKS_USER_CLASS = "{{PICKS_USER_CLASS_NAME}}";
    /** Diagnostics only: the abstract card the hooked binders inherit bind from. */
    private static final String PICKS_CARD_BASE_CLASS = "{{PICKS_CARD_BASE_CLASS_NAME}}";

    private static final String VOTE_SERVICE_CLASS = "{{VOTE_SERVICE_CLASS_NAME}}";
    private static final String VOTE_SUBMIT_METHOD = "{{VOTE_SUBMIT_METHOD_NAME}}";
    private static final String VOTE_SOURCE_CLASS = "{{VOTE_SOURCE_CLASS_NAME}}";
    private static final String VOTE_SOURCE_ENUM_CLASS = "{{VOTE_SOURCE_ENUM_CLASS_NAME}}";
    private static final String VOTE_SOURCE_CONSTANT = "{{VOTE_SOURCE_CONSTANT}}";
    private static final String VOTE_USER_CLASS = "{{VOTE_USER_CLASS_NAME}}";
    /** Diagnostics only: what the graph walk is expected to hand back. */
    private static final String VOTE_SERVICE_IMPL_CLASS = "{{VOTE_SERVICE_IMPL_CLASS_NAME}}";

    /** From [UserAccessors]: a zero-argument getter on User that reads `userid`. */
    private static final String USER_ID_METHOD = "{{USER_ID_METHOD_NAME}}";

    /**
     * The specialised {@code bind} each hooked bridge forwards to, resolved at
     * load time so a bridge is never hooked unless its own forward target is
     * already in hand. Written during load() and read from whichever thread
     * Epoxy binds on -- the provider's onCreate is the main thread today, but
     * a concurrent map makes that not worth depending on.
     */
    private static final Map<String, Method> REAL_BIND =
            new java.util.concurrent.ConcurrentHashMap<String, Method>();

    /**
     * The {@code props} field, per concrete card class. Keyed per class and
     * not shared: each subclass declares its own, narrowed to its own props
     * type, so one class's Field raises IllegalArgumentException on the
     * other's instances. Reached only from the bind path, i.e. the main
     * thread, and synchronized anyway because that is cheaper than reasoning
     * about it.
     */
    private static final Map<String, Field> PROPS_FIELDS = new HashMap<String, Field>();

    /** Cached reflection. Each entry is idempotent to recompute. */
    private static volatile Method submit_method;
    private static volatile Object vote_source;
    private static volatile Object vote_service;
    private static volatile Executor executor;

    private static volatile boolean armed = false;

    /**
     * One-shot latch for the "cannot place the button" complaint. bind runs
     * once per card per scroll, so without it a single unrecognised layout
     * would fill logcat.
     */
    private static volatile boolean placement_reported = false;

    /**
     * Set once the first laid-out button has been compared against the FAB.
     * The comparison is worth one log line, not one per card per scroll.
     */
    private static volatile boolean placement_verified = false;

    /**
     * Latched when that comparison finds the two rectangles intersecting. From
     * then on no card offers the button at all.
     */
    private static volatile boolean overlapping = false;

    /** Cached per layout-params class. See {@link #value_fields}. */
    private static final Map<String, List<Field>> VALUE_FIELDS =
            new HashMap<String, List<Field>>();

    // ---------------------------------------------------------------- hook

    /**
     * Faithful reimplementation of {@code bind(Object)}: the cast, then the
     * virtual call -- and then the only thing this patch adds.
     *
     * <p>The real {@code bind} runs first and its exceptions propagate
     * unwrapped, so Epoxy sees exactly what it would have seen unpatched. The
     * added work runs after, and cannot escape.
     */
    static void bind_bridge_hook(Object thiz, Object view) {
        if (thiz == null) {
            return;
        }
        Method real = REAL_BIND.get(thiz.getClass().getName());
        if (real == null) {
            // Should be unreachable: load() installs only where it resolved
            // one. If it ever happens, a card that renders unbound is still
            // better than an exception out of Epoxy's bind pass.
            Log.e(HookUtil.TAG, "PicksLikeButton: no bind target for "
                    + thiz.getClass().getName() + " -- card not bound");
            return;
        }
        try {
            real.invoke(thiz, view);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause() != null ? e.getCause() : e);
        } catch (Throwable t) {
            throw rethrow(t);
        }
        try {
            attach(thiz, view);
        } catch (Throwable t) {
            Log.e(HookUtil.TAG, "PicksLikeButton: attach failed: " + t);
        }
    }

    /**
     * Rethrows a Throwable unchanged, so the bridge reproduces the original's
     * exception behaviour instead of wrapping it in something Epoxy's callers
     * have never had to handle.
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException rethrow(Throwable t) throws T {
        throw (T) t;
    }

    // ------------------------------------------------------------ the view

    /** Adds (or re-points) the Like button on one freshly bound card. */
    private static void attach(Object model, Object view) {
        if (!armed || !(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup root = (ViewGroup) view;
        View existing = root.findViewWithTag(VIEW_TAG);
        ImageView button = existing instanceof ImageView ? (ImageView) existing : null;
        if (overlapping) {
            // The placement check found this card's button sitting on the
            // SuperLike FAB. Withdrawn for the rest of the process rather than
            // re-offered on the next bind.
            if (button != null) {
                button.setVisibility(View.GONE);
                button.setEnabled(false);
            }
            return;
        }
        View fab = find_fab(root);
        if (fab == null) {
            // No SuperLike FAB on this card: nothing to position against, and
            // this patch does not invent a position. See the class comment.
            return;
        }
        if (button == null) {
            button = build_button(root, fab);
            if (button == null) {
                return;
            }
            root.addView(button);
        }
        offset_and_verify(root, fab, button,
                ButtonPlacement.size(button.getLayoutParams() == null
                        ? 0 : button.getLayoutParams().width,
                        root.getResources().getDisplayMetrics().density));

        // One reflective walk per bind: the id comes off the same User the
        // vote will be sent with, never from a second lookup that could
        // disagree with it.
        final Object user = user_of(model);
        final String user_id = user == null ? null : id_of(user);
        if (user_id == null) {
            // A card with nobody identifiable behind it: leave the button out
            // of the way rather than offering a tap that cannot resolve.
            button.setVisibility(View.GONE);
            return;
        }
        button.setVisibility(View.VISIBLE);
        // Re-applied on every bind, never assumed from the last one: Epoxy
        // hands the same view to a different person after a scroll.
        set_spent(button, LikedOnce.get().isClaimed(user_id));
        final ImageView target = button;
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View clicked) {
                on_like_tapped(target, user, user_id);
            }
        });
    }

    /** The SuperLike FAB among the card root's direct children. */
    private static View find_fab(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child != null && FAB_CLASS.equals(child.getClass().getName())) {
                return child;
            }
        }
        return null;
    }

    /**
     * A circular Like button laid out exactly on top of the FAB -- same size,
     * same constraints, same margins -- and then translated clear of it.
     * Returns null when a fresh layout params object of the FAB's own type
     * cannot be built, which is the only honest way to place it.
     *
     * <h3>Why not the FAB's layout params copy constructor</h3>
     *
     * Because on a ConstraintLayout it does not copy.
     * {@code ConstraintLayout$LayoutParams.<init>(ViewGroup$LayoutParams)}
     * allocates a fresh solver widget and then, as its last act, overwrites it
     * with the source's:
     *
     * <pre>
     * iget-object v0, p1, ...$LayoutParams;-&gt;mWidget:...ConstraintWidget;
     * iput-object v0, p0, ...$LayoutParams;-&gt;mWidget:...ConstraintWidget;
     * </pre>
     *
     * {@code ConstraintLayout.onLayout} positions each child from
     * {@code ((LayoutParams) child.getLayoutParams()).mWidget}, so two
     * children sharing one widget are laid out at one position. That is
     * exactly what the first device run showed: the Like button and the
     * SuperLike FAB reported byte-identical bounds, both at the end margin
     * this hook had computed, with the FAB displaced 190px (one button plus
     * one gap) from where its XML puts it.
     *
     * <p>So the params object is built with the two-int constructor, which
     * keeps the widget it allocated, and everything else is carried over by
     * value: the type's own declared primitive and String fields (which is
     * where every constraint, bias and gone-margin lives) plus the margins
     * through public API. Any field whose type is a reference to something
     * else -- {@code mWidget} above all -- is skipped by construction, and so
     * is anything the platform declares, because reflecting on
     * {@code MarginLayoutParams}' private {@code endMargin} would be a
     * non-SDK access.
     *
     * <p>This is also what makes the placement parent-agnostic: a
     * {@code FrameLayout.LayoutParams} or a {@code RelativeLayout.LayoutParams}
     * goes through the same two steps, and the offset is a view property
     * either way.
     */
    private static ImageView build_button(ViewGroup root, View fab) {
        Context context = root.getContext();
        if (context == null) {
            return null;
        }
        ViewGroup.LayoutParams source = fab.getLayoutParams();
        if (source == null) {
            return null;
        }
        float density = context.getResources().getDisplayMetrics().density;
        int size = ButtonPlacement.size(source.width, density);

        ViewGroup.LayoutParams params = fresh_params(source, size);
        if (params == null) {
            return null;                // reported once inside fresh_params
        }

        ImageView button = new ImageView(context);
        button.setTag(VIEW_TAG);
        button.setLayoutParams(params);
        // Same elevation as the FAB, or the card's own shadow paints over it.
        button.setElevation(fab.getElevation());
        button.setBackground(circle(like_color(context)));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int inset = Math.round(size * ICON_INSET / 2f);
        button.setPadding(inset, inset, inset, inset);
        button.setImageDrawable(like_icon(context));
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription("Like");
        return button;
    }

    /**
     * A layout params object of the FAB's own class, with its own solver
     * state, carrying the FAB's geometry.
     */
    private static ViewGroup.LayoutParams fresh_params(ViewGroup.LayoutParams source, int size) {
        ViewGroup.LayoutParams params;
        try {
            // Deliberately the two-int constructor and never the one taking a
            // ViewGroup.LayoutParams -- see this method's caller for why.
            params = (ViewGroup.LayoutParams) source.getClass()
                    .getConstructor(int.class, int.class)
                    .newInstance(size, size);
        } catch (Throwable t) {
            if (!placement_reported) {
                placement_reported = true;
                Log.e(HookUtil.TAG, "PicksLikeButton: no (int,int) constructor on "
                        + source.getClass().getName() + " -- no button added, because "
                        + "the copy constructor shares solver state and would stack "
                        + "the button on the FAB: " + t);
            }
            return null;
        }
        copy_value_fields(source, params);
        // Set rather than copied: width and height are declared by
        // ViewGroup.LayoutParams, which the field copy stops short of, and the
        // two-int constructor has already been given the size anyway. Written
        // again here so the computed size still wins if a future layout gives
        // the FAB a MATCH_PARENT or WRAP_CONTENT the fallback had to replace.
        params.width = size;
        params.height = size;
        if (params instanceof ViewGroup.MarginLayoutParams
                && source instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams to = (ViewGroup.MarginLayoutParams) params;
            ViewGroup.MarginLayoutParams from = (ViewGroup.MarginLayoutParams) source;
            // Public API only. setMargins clears the start/end resolution
            // flags, so the relative pair is set after it, and the FAB's
            // margins are reproduced rather than adjusted -- the button is
            // meant to land on the FAB and then translate off it.
            to.setMargins(from.leftMargin, from.topMargin, from.rightMargin, from.bottomMargin);
            to.setMarginStart(from.getMarginStart());
            to.setMarginEnd(from.getMarginEnd());
        }
        return params;
    }

    /**
     * Copies the value fields the layout params type declares for itself --
     * every constraint id, bias, weight, chain style, gone margin and
     * dimension ratio.
     *
     * <p>Two exclusions, both load-bearing. A field whose type is not
     * primitive or String is skipped, which is what keeps the solver
     * {@code ConstraintWidget} out. And a field declared by a platform class
     * is skipped, because {@code MarginLayoutParams}' {@code startMargin} /
     * {@code endMargin} / {@code mMarginFlags} are non-SDK members and
     * reflecting on them would be a hidden-API access; those are copied
     * through public API by the caller instead.
     */
    private static void copy_value_fields(ViewGroup.LayoutParams source,
                                          ViewGroup.LayoutParams target) {
        for (Field field : value_fields(source.getClass())) {
            try {
                field.set(target, field.get(source));
            } catch (Throwable ignored) {
                // One uncopyable field is not worth losing the button over;
                // the overlap check is what guarantees the result is usable.
            }
        }
    }

    /** Cached per layout-params class: the fields {@link #copy_value_fields} may copy. */
    private static List<Field> value_fields(Class<?> type) {
        String key = type.getName();
        synchronized (VALUE_FIELDS) {
            List<Field> cached = VALUE_FIELDS.get(key);
            if (cached != null) {
                return cached;
            }
            List<Field> fields = new java.util.ArrayList<Field>();
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                if (c.getName().startsWith("android.")) {
                    break;              // platform fields: public API only
                }
                for (Field field : c.getDeclaredFields()) {
                    int modifiers = field.getModifiers();
                    if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                        continue;
                    }
                    Class<?> kind = field.getType();
                    if (!kind.isPrimitive() && kind != String.class) {
                        continue;       // solver state and other references
                    }
                    try {
                        field.setAccessible(true);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    fields.add(field);
                }
            }
            VALUE_FIELDS.put(key, fields);
            return fields;
        }
    }

    /**
     * Moves the button clear of the FAB, and proves it worked.
     *
     * <p>The offset is a translation rather than a margin, so it cannot touch
     * the FAB's layout state whatever the parent turns out to be. Because that
     * claim was wrong once already, it is then checked rather than asserted:
     * one frame later the two views' on-screen rectangles are compared, and if
     * they intersect at all the button is taken out of service permanently --
     * an invisible-but-clickable Like sitting on the SuperLike FAB is worse
     * than no Like at all. Both rectangles are logged either way, so the
     * placement can be read out of logcat without a screen dump.
     */
    private static void offset_and_verify(final ViewGroup root, final View fab,
                                          final ImageView button, int size) {
        float density = root.getResources().getDisplayMetrics().density;
        boolean rtl = root.getResources().getConfiguration().getLayoutDirection()
                == View.LAYOUT_DIRECTION_RTL;
        int offset = ButtonPlacement.offset(size, ButtonPlacement.dp(ButtonPlacement.GAP_DP, density));
        // Re-applied on every bind: idempotent, and a recycled view that
        // somehow lost its translation comes back offset.
        button.setTranslationX(ButtonPlacement.translationX(offset, rtl));
        if (placement_verified) {
            return;
        }
        button.post(new Runnable() {
            @Override
            public void run() {
                try {
                    verify_placement(fab, button);
                } catch (Throwable t) {
                    Log.e(HookUtil.TAG, "PicksLikeButton: placement check: " + t);
                }
            }
        });
    }

    private static void verify_placement(View fab, ImageView button) {
        if (placement_verified) {
            return;
        }
        if (button.getWidth() == 0 || fab.getWidth() == 0) {
            return;                     // not laid out yet; a later bind retries
        }
        Rect like = on_screen(button);
        Rect superlike = on_screen(fab);
        placement_verified = true;
        if (Rect.intersects(like, superlike)) {
            overlapping = true;
            button.setVisibility(View.GONE);
            button.setEnabled(false);
            Log.e(HookUtil.TAG, "PicksLikeButton: the Like button overlaps the "
                    + "SuperLike FAB (like=" + like.toShortString()
                    + " superlike=" + superlike.toShortString()
                    + ") -- withdrawn, because a hidden tap target over the FAB is "
                    + "worse than no button");
        } else {
            Log.i(HookUtil.TAG, "PicksLikeButton: placed like=" + like.toShortString()
                    + " superlike=" + superlike.toShortString());
        }
    }

    /** A view's rectangle in screen coordinates, translation included. */
    private static Rect on_screen(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return new Rect(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
    }

    /** Dims and disables a button whose person this process has already liked. */
    private static void set_spent(ImageView button, boolean spent) {
        button.setEnabled(!spent);
        button.setAlpha(spent ? 0.35f : 1f);
        button.setContentDescription(spent ? "Liked" : "Like");
    }

    private static Drawable circle(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        return shape;
    }

    private static int like_color(Context context) {
        try {
            Resources resources = context.getResources();
            int id = resources.getIdentifier(LIKE_COLOR_NAME, "color", context.getPackageName());
            if (id != 0) {
                return context.getColor(id);
            }
        } catch (Throwable ignored) {
            // A renamed or missing colour is not worth a log line per card.
        }
        return LIKE_COLOR_FALLBACK;
    }

    private static Drawable like_icon(Context context) {
        try {
            Resources resources = context.getResources();
            for (String name : LIKE_DRAWABLES) {
                int id = resources.getIdentifier(name, "drawable", context.getPackageName());
                if (id != 0) {
                    Drawable drawable = context.getDrawable(id);
                    if (drawable != null) {
                        return drawable;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the drawn heart.
        }
        return new HeartDrawable();
    }

    /**
     * The fallback Like glyph: a heart drawn from two cubics, filled white to
     * match the host's own vectors. Exists so a release that renames both
     * drawables degrades to a plain-looking button rather than an empty one.
     */
    private static final class HeartDrawable extends Drawable {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        HeartDrawable() {
            paint.setColor(0xFFFFFFFF);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float w = bounds.width();
            float h = bounds.height();
            if (w <= 0f || h <= 0f) {
                return;
            }
            float cx = bounds.left + w / 2f;
            float bottom = bounds.top + h * 0.94f;
            Path path = new Path();
            path.moveTo(cx, bottom);
            path.cubicTo(bounds.left - w * 0.16f, bounds.top + h * 0.42f,
                    bounds.left + w * 0.14f, bounds.top - h * 0.06f,
                    cx, bounds.top + h * 0.26f);
            path.cubicTo(bounds.left + w * 0.86f, bounds.top - h * 0.06f,
                    bounds.left + w * 1.16f, bounds.top + h * 0.42f,
                    cx, bottom);
            path.close();
            canvas.drawPath(path, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // --------------------------------------------------------- the model

    private static Field props_field_of(Class<?> model_class) {
        String key = model_class.getName();
        synchronized (PROPS_FIELDS) {
            if (PROPS_FIELDS.containsKey(key)) {
                return PROPS_FIELDS.get(key);
            }
            Field found = null;
            // Walk up: the concrete card declares its own narrowed `props`,
            // and the base declares one too. Either answers the user getter,
            // and the nearest one is the right one to read.
            for (Class<?> c = model_class; c != null; c = c.getSuperclass()) {
                try {
                    found = c.getDeclaredField(PROPS_FIELD);
                    found.setAccessible(true);
                    break;
                } catch (NoSuchFieldException ignored) {
                    found = null;
                }
            }
            // null is cached too: a class without the field will not grow one.
            PROPS_FIELDS.put(key, found);
            return found;
        }
    }

    private static Object props_of(Object model) {
        Field field = props_field_of(model.getClass());
        if (field == null) {
            return null;
        }
        try {
            return field.get(model);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object user_of(Object model) {
        Object props = props_of(model);
        if (props == null) {
            return null;
        }
        try {
            Method getter = props.getClass().getMethod(PROPS_USER_METHOD);
            getter.setAccessible(true);
            return getter.invoke(props);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String id_of(Object user) {
        try {
            Method getter = user.getClass().getMethod(USER_ID_METHOD);
            getter.setAccessible(true);
            Object id = getter.invoke(user);
            return id instanceof String ? (String) id : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------- the vote

    private static void on_like_tapped(final ImageView button, final Object user,
                                       final String user_id) {
        if (!LikedOnce.get().claim(user_id)) {
            set_spent(button, true);
            return;
        }
        // Spent immediately, before the network answers: the tap has to stop
        // being tappable now, not when the mutation comes back.
        set_spent(button, true);
        final Context context = button.getContext();
        runner().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    send_like(context, user, user_id, button);
                } catch (Throwable t) {
                    Log.e(HookUtil.TAG, "PicksLikeButton: like failed for "
                            + user_id + ": " + t);
                    give_back(context, button, user_id);
                }
            }
        });
    }

    /**
     * Undoes a tap that sent nothing: the claim goes back so the card can be
     * tried again, and the button becomes tappable.
     */
    private static void give_back(Context context, final ImageView button, String user_id) {
        LikedOnce.get().release(user_id);
        on_main(new Runnable() {
            @Override
            public void run() {
                set_spent(button, false);
            }
        });
        toast(context, "Could not send that like");
    }

    /**
     * Hands one like to the app's own vote service. Throws if the vote could
     * not be dispatched at all; once it has been, the outcome arrives through
     * the stream and is reported from there.
     */
    private static void send_like(Context context, Object user, String user_id,
                                  ImageView button) throws Exception {
        Object service = vote_service(context);
        if (service == null) {
            Log.e(HookUtil.TAG, "PicksLikeButton: no " + VOTE_SERVICE_CLASS
                    + " (expected a " + VOTE_SERVICE_IMPL_CLASS
                    + ") reachable from the Application graph");
            give_back(context, button, user_id);
            return;
        }
        Method submit = submit_method(service);
        Object source = vote_source();
        if (submit == null || source == null) {
            give_back(context, button, user_id);
            return;
        }
        Object stream = submit.invoke(service, user_id, source, user, Boolean.TRUE);
        if (stream == null) {
            give_back(context, button, user_id);
            return;
        }
        subscribe(context, stream, user_id, button);
    }

    /**
     * Subscribes to the returned reactive stream, which is what actually sends
     * the mutation, and reports the outcome the stream gives.
     *
     * <p>The stream type is RxJava repackaged under a prefix this patch does
     * not name, so the two-consumer {@code subscribe} overload is found by
     * shape -- two parameters of the same interface type -- and the consumers
     * are {@link Proxy} instances of whatever that interface turns out to be.
     *
     * <p>Not the blocking overload, deliberately. Blocking here would be legal
     * (this already runs off the main thread) and would make the feedback
     * synchronous, but one stream that never terminates would wedge the single
     * executor thread and silently kill every later like. The consumers carry
     * the outcome instead.
     *
     * <p><b>Known limit.</b> The success consumer reports that the mutation
     * completed, not that the server counted the vote. A refusal the backend
     * expresses inside a successful response -- {@code TOO_MANY_DAILY_LIKES}
     * is the one NOTES.md records -- arrives as an ordinary emission, and this
     * does not take the response apart to find it. So the message says the
     * like was sent, which is what is actually known; the full response is
     * logged under PATCH either way.
     */
    private static void subscribe(final Context context, Object stream,
                                  final String user_id, final ImageView button)
            throws Exception {
        Method subscribe = null;
        for (Method candidate : stream.getClass().getMethods()) {
            if (!"subscribe".equals(candidate.getName())) {
                continue;
            }
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length == 2 && parameters[0] == parameters[1]
                    && parameters[0].isInterface()) {
                subscribe = candidate;
                break;
            }
        }
        if (subscribe == null) {
            Log.e(HookUtil.TAG, "PicksLikeButton: no two-consumer subscribe on "
                    + stream.getClass().getName());
            give_back(context, button, user_id);
            return;
        }
        Class<?> consumer = subscribe.getParameterTypes()[0];
        Object on_next = consumer(consumer, new Outcome() {
            @Override
            public void accept(Object value) {
                Log.i(HookUtil.TAG, "PicksLikeButton: vote sent for " + user_id
                        + ": " + value);
                toast(context, "Like sent");
            }
        });
        Object on_error = consumer(consumer, new Outcome() {
            @Override
            public void accept(Object value) {
                Log.e(HookUtil.TAG, "PicksLikeButton: vote rejected for " + user_id
                        + ": " + value);
                give_back(context, button, user_id);
            }
        });
        subscribe.invoke(stream, on_next, on_error);
    }

    /** What one side of the stream does with whatever it is handed. */
    private interface Outcome {
        void accept(Object value);
    }

    /**
     * A {@link Proxy} standing in for the stream's single-method callback
     * interface, whose name and package this patch never writes down.
     */
    private static Object consumer(Class<?> type, final Outcome outcome) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        // Object's own methods reach the handler too, and a
                        // null answer to hashCode() would NPE on unboxing, so
                        // they are answered here rather than treated as the
                        // callback.
                        if (method.getDeclaringClass() == Object.class) {
                            String name = method.getName();
                            if ("hashCode".equals(name)) {
                                return System.identityHashCode(proxy);
                            }
                            if ("equals".equals(name)) {
                                return args != null && args.length == 1 && proxy == args[0];
                            }
                            if ("toString".equals(name)) {
                                return "PicksLikeButton$consumer";
                            }
                            return null;
                        }
                        if (args != null && args.length == 1) {
                            try {
                                outcome.accept(args[0]);
                            } catch (Throwable t) {
                                Log.e(HookUtil.TAG, "PicksLikeButton: outcome: " + t);
                            }
                        }
                        return null;
                    }
                });
    }

    /**
     * The app's vote service, found by walking declared return types outward
     * from the Application object. Types only: the single chain that leads to
     * the service is the only one invoked.
     */
    private static Object vote_service(Context context) throws Exception {
        Object cached = vote_service;
        if (cached != null) {
            return cached;
        }
        Context application = context.getApplicationContext();
        if (application == null) {
            return null;
        }
        // Breadth-first over zero-argument getters, four hops deep: the real
        // chain on 116.0.0 is Application -> OkGraph -> RemoteDataGraph ->
        // the service, and the extra hop is slack for a graph split.
        java.util.List<java.util.List<Method>> frontier = new java.util.ArrayList<java.util.List<Method>>();
        java.util.List<Class<?>> types = new java.util.ArrayList<Class<?>>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        frontier.add(new java.util.ArrayList<Method>());
        types.add(application.getClass());
        for (int depth = 0; depth < 4 && !frontier.isEmpty(); depth++) {
            java.util.List<java.util.List<Method>> next =
                    new java.util.ArrayList<java.util.List<Method>>();
            java.util.List<Class<?>> next_types = new java.util.ArrayList<Class<?>>();
            for (int i = 0; i < frontier.size(); i++) {
                for (Method method : types.get(i).getMethods()) {
                    if (method.getParameterTypes().length != 0) {
                        continue;
                    }
                    Class<?> returned = method.getReturnType();
                    if (VOTE_SERVICE_CLASS.equals(returned.getName())) {
                        Object resolved = walk(application, frontier.get(i), method);
                        if (resolved != null) {
                            vote_service = resolved;
                            return resolved;
                        }
                        continue;
                    }
                    // Only the app's own graph interfaces are worth
                    // traversing, and each type only once.
                    if (!returned.isInterface() || !same_vendor(returned.getName())
                            || !seen.add(returned.getName())) {
                        continue;
                    }
                    java.util.List<Method> path = new java.util.ArrayList<Method>(frontier.get(i));
                    path.add(method);
                    next.add(path);
                    next_types.add(returned);
                }
            }
            frontier = next;
            types = next_types;
        }
        return null;
    }

    /** Invokes a resolved getter chain, then the final getter. */
    private static Object walk(Object root, java.util.List<Method> path, Method last)
            throws Exception {
        Object current = root;
        for (Method step : path) {
            step.setAccessible(true);
            current = step.invoke(current);
            if (current == null) {
                return null;
            }
        }
        last.setAccessible(true);
        return last.invoke(current);
    }

    /**
     * Whether a class belongs to the same vendor namespace as the vote
     * service -- its first two dotted segments. Keeps the graph walk inside
     * the app's own code without this file naming a package.
     */
    private static boolean same_vendor(String class_name) {
        int cut = VOTE_SERVICE_CLASS.indexOf('.', VOTE_SERVICE_CLASS.indexOf('.') + 1);
        if (cut <= 0) {
            return false;
        }
        return class_name.startsWith(VOTE_SERVICE_CLASS.substring(0, cut + 1));
    }

    private static Method submit_method(Object service) {
        Method cached = submit_method;
        if (cached != null) {
            return cached;
        }
        try {
            ClassLoader loader = PicksLikeButton.class.getClassLoader();
            Method found = service.getClass().getMethod(VOTE_SUBMIT_METHOD,
                    String.class,
                    Class.forName(VOTE_SOURCE_CLASS, false, loader),
                    Class.forName(VOTE_USER_CLASS, false, loader),
                    boolean.class);
            found.setAccessible(true);
            submit_method = found;
            return found;
        } catch (Throwable t) {
            Log.e(HookUtil.TAG, "PicksLikeButton: " + VOTE_SUBMIT_METHOD
                    + " not found on " + service.getClass().getName() + ": " + t);
            return null;
        }
    }

    private static Object vote_source() {
        Object cached = vote_source;
        if (cached != null) {
            return cached;
        }
        try {
            // Resolved on the first tap, never at load(): reading an enum
            // constant's value runs the enum's <clinit>, and load() runs
            // before the host Application's onCreate.
            Class<?> enum_class = Class.forName(VOTE_SOURCE_CLASS, false,
                    PicksLikeButton.class.getClassLoader());
            Object value = enum_class.getField(VOTE_SOURCE_CONSTANT).get(null);
            vote_source = value;
            return value;
        } catch (Throwable t) {
            Log.e(HookUtil.TAG, "PicksLikeButton: vote source " + VOTE_SOURCE_CLASS
                    + "." + VOTE_SOURCE_CONSTANT + " unavailable: " + t);
            return null;
        }
    }

    private static Executor runner() {
        Executor cached = executor;
        if (cached == null) {
            synchronized (PicksLikeButton.class) {
                if (executor == null) {
                    executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable runnable) {
                            Thread thread = new Thread(runnable, "picks-like");
                            thread.setDaemon(true);
                            return thread;
                        }
                    });
                }
                cached = executor;
            }
        }
        return cached;
    }

    private static void on_main(Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
    }

    private static void toast(final Context context, final String message) {
        on_main(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                    // A missing toast is not worth taking anything down for.
                }
            }
        });
    }

    // ---------------------------------------------------------- install

    private static boolean unsubstituted(String value) {
        // Spelled in halves so validate-artifactory.py's scan of this tree
        // does not read the example as a real unsubstituted key.
        return value.startsWith("{" + "{");
    }

    @Override
    public void load() {
        try {
            String[] required = {TARGETS, BIND_VIEW_CLASS, FAB_CLASS, PROPS_FIELD,
                    PROPS_USER_METHOD, PICKS_USER_CLASS, VOTE_SERVICE_CLASS,
                    VOTE_SUBMIT_METHOD, VOTE_SOURCE_CLASS, VOTE_SOURCE_ENUM_CLASS,
                    VOTE_SOURCE_CONSTANT, VOTE_USER_CLASS, USER_ID_METHOD};
            for (String value : required) {
                if (unsubstituted(value)) {
                    Log.e(HookUtil.TAG, "PicksLikeButton: placeholder unsubstituted -- "
                            + "a finder did not fire; no Like button is added to Picks");
                    return;
                }
            }

            // Two cross-checks between independently anchored finders. Each
            // one is the difference between a correct vote and a wrong one, so
            // a disagreement installs nothing rather than guessing which
            // finder was right.
            if (!PICKS_USER_CLASS.equals(VOTE_USER_CLASS)) {
                Log.e(HookUtil.TAG, "PicksLikeButton: the Picks card carries a "
                        + PICKS_USER_CLASS + " but the vote takes a " + VOTE_USER_CLASS
                        + " -- not installing");
                return;
            }
            if (!VOTE_SOURCE_CLASS.equals(VOTE_SOURCE_ENUM_CLASS)) {
                Log.e(HookUtil.TAG, "PicksLikeButton: vote source enum mismatch ("
                        + VOTE_SOURCE_CLASS + " vs " + VOTE_SOURCE_ENUM_CLASS
                        + ") -- not installing");
                return;
            }

            ClassLoader loader = PicksLikeButton.class.getClassLoader();
            // getDeclaredField, not get(): this proves the constant exists
            // without running the enum's <clinit> during app startup.
            Class.forName(VOTE_SOURCE_CLASS, false, loader)
                    .getDeclaredField(VOTE_SOURCE_CONSTANT);
            Class<?> bind_view = Class.forName(BIND_VIEW_CLASS, false, loader);

            Method replacement = PicksLikeButton.class.getDeclaredMethod(
                    "bind_bridge_hook", Object.class, Object.class);

            String[] targets = TARGETS.split("\\|");
            int hooked = 0;
            for (String target : targets) {
                String[] parts = target.split(":");
                if (parts.length != 3) {
                    Log.e(HookUtil.TAG, "PicksLikeButton: malformed target '" + target + "'");
                    continue;
                }
                Method real;
                try {
                    // The specialised override the bridge forwards to. Looked
                    // up before the bridge is hooked, so the replacement can
                    // never find itself without a forward target.
                    real = Class.forName(parts[0], false, loader)
                            .getMethod(parts[1], bind_view);
                    real.setAccessible(true);
                } catch (Throwable t) {
                    Log.e(HookUtil.TAG, "PicksLikeButton: " + parts[0] + "." + parts[1]
                            + "(" + BIND_VIEW_CLASS + ") not found -- skipping: " + t);
                    continue;
                }
                REAL_BIND.put(parts[0], real);
                if (HookUtil.install("PicksLikeButton", parts[0], parts[1], parts[2],
                        replacement)) {
                    hooked++;
                } else {
                    REAL_BIND.remove(parts[0]);
                }
            }

            armed = hooked > 0;
            if (armed) {
                Log.i(HookUtil.TAG, "PicksLikeButton: " + hooked + "/" + targets.length
                        + " Picks card binder(s) of " + PICKS_CARD_BASE_CLASS
                        + " carry a plain Like button");
            } else {
                Log.e(HookUtil.TAG, "PicksLikeButton: hooked nothing out of "
                        + targets.length + " target(s)");
            }
        } catch (Throwable t) {
            Log.e(HookUtil.TAG, "PicksLikeButton: " + t);
        }
    }

    @Override
    public void unload() {
    }
}
