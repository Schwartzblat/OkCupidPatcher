package com.smali_generator.patches;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.smali_generator.Hook;
import com.smali_generator.likes.SweepProgress;
import com.smali_generator.utils.Utils;

import java.lang.ref.WeakReference;

/**
 * Shows the running sweep as a bar at the top of the app window.
 *
 * <p>Installs no ArtHooks hook. It registers {@link
 * Application.ActivityLifecycleCallbacks} to know which Activity is in front
 * and adds a view to that Activity's {@code android.R.id.content}, so nothing
 * here depends on an obfuscated name and an app update cannot break it.
 *
 * <p>Every decision about what the bar says or how full it is belongs to
 * {@link SweepProgress}, which is pure and tested; this file is the Android
 * half -- views, the main-thread Handler, insets -- and makes no decisions of
 * its own. Nothing in it is reachable from a JVM unit test, the same split
 * {@code NameLookup} takes against {@code NameResolver}.
 *
 * <p>The bar appears on whatever screen is in front, not only on <i>Interested
 * in You</i>: a sweep is only ever triggered by a Likes page load, so that is
 * where it shows up, and the app is single-Activity Compose, which gives no
 * reliable "the user left the tab" signal to hide it on. Staying visible is
 * the honest option -- the sweep really is still running.
 *
 * <p>No identity, photo path or name passes through here. Counts only.
 */
public final class SweepProgressOverlay implements Hook, SweepProgress.Listener {

    /** How often the bar re-reads the live counts. See {@link SweepProgress#current()}. */
    private static final long TICK_MS = 250L;

    /** How long the finished bar stays on screen before fading out. */
    private static final long HOLD_MS = 1400L;
    private static final long FADE_MS = 400L;

    /** Fraction of the remaining distance the fill closes each tick, so it glides. */
    private static final float EASE = 0.3f;

    private static final int SCRIM_COLOR = 0xEE101418;
    private static final int TRACK_COLOR = 0x33FFFFFF;
    private static final int FILL_COLOR = 0xFF2DD4BF;
    private static final int TEXT_COLOR = 0xFFE6EDF3;

    /**
     * How far below the status bar the bar sits. Zero: its top edge touches
     * the bottom of the status bar, so it covers the app's "Interest" title
     * row for the couple of seconds it is up, and never paints over the clock.
     *
     * <p>Kept as a named knob rather than inlined, because this is the one
     * number that decides placement and it took several passes on a real
     * device to settle. Reference points measured from the live view
     * hierarchy on a Pixel 9a (420dpi): status bar 0-152px, the "Interest"
     * utility bar 152-278px (48dp), the "INTERESTED IN YOU / INTROS / YOU
     * LIKE" tab row 278-404px (48dp), filter chips from 446px. So 48 would
     * sit below the title, 96 below the tab row.
     *
     * <p>Only ever cosmetic: a wrong offset misplaces the bar, it never
     * breaks the sweep. Other tabs have their own chrome heights, so this is
     * placed for Interest and merely approximate elsewhere.
     */
    private static final int HEADER_DP = 0;

    private static final int BAR_HEIGHT_DP = 5;
    private static final int PAD_DP = 10;
    private static final float CAPTION_SP = 12f;

    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile WeakReference<Activity> front = new WeakReference<Activity>(null);
    private WeakReference<Activity> attachedTo = new WeakReference<Activity>(null);
    private LinearLayout container;              // non-null exactly while attached
    private BarView bar;
    private TextView caption;
    private boolean lifecycleRegistered;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            SweepProgress.Snapshot live = SweepProgress.current();
            if (live == null) {
                return;                          // finish() will do the tearing down
            }
            render(live);
            main.postDelayed(this, TICK_MS);
        }
    };

    private final Runnable detach = new Runnable() {
        @Override public void run() {
            detachNow();
        }
    };

    // ---- Hook --------------------------------------------------------------

    @Override
    public void load() {
        try {
            SweepProgress.setListener(this);
            registerLifecycle();
            Log.i(HookUtil.TAG, "SweepProgressOverlay: installed");
        } catch (Throwable t) {
            Log.e(HookUtil.TAG, "SweepProgressOverlay: " + t);
        }
    }

    @Override
    public void unload() {
        try {
            SweepProgress.setListener(null);
            main.removeCallbacks(ticker);
            main.removeCallbacks(detach);
            main.post(detach);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Runs from the provider's onCreate, before {@code Application.onCreate}
     * and so before any Activity exists -- which is exactly why every
     * {@code onActivityResumed} is caught. If the Application is not reachable
     * that early, the first published reading retries.
     */
    private synchronized void registerLifecycle() {
        if (lifecycleRegistered) {
            return;
        }
        Application app = Utils.getApplication();
        if (app == null) {
            return;
        }
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                front = new WeakReference<Activity>(activity);
                // A sweep that outlived the previous Activity keeps its bar --
                // rendered and ticking, not attached blank until the next
                // phase boundary, which can be tens of requests away.
                SweepProgress.Snapshot live = SweepProgress.current();
                if (live != null) {
                    show(activity, live);
                }
            }

            @Override public void onActivityDestroyed(Activity activity) {
                if (activity == front.get()) {
                    front = new WeakReference<Activity>(null);
                }
                // Only when it is the Activity holding the bar: any other
                // one dying would otherwise take the bar down with it.
                if (activity == attachedTo.get()) {
                    detachNow();                 // the views' own Context is going away
                }
            }

            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
        });
        lifecycleRegistered = true;
    }

    // ---- SweepProgress.Listener (called on the sweep thread) ---------------

    @Override
    public void onProgress(final SweepProgress.Snapshot snapshot) {
        registerLifecycle();                     // no-op once it has taken
        main.removeCallbacks(detach);             // a new pass cancels a pending fade
        main.post(new Runnable() {
            @Override public void run() {
                Activity activity = front.get();
                if (activity != null) {
                    show(activity, snapshot);
                }                                // else: nothing on screen yet
            }
        });
    }

    /** Attach, draw this reading, and keep the counts moving. Main thread only. */
    private void show(Activity activity, SweepProgress.Snapshot snapshot) {
        attachTo(activity);
        if (container != null) {
            // A pass starting inside the previous one's fade window would
            // otherwise show a bar on its way out.
            container.animate().cancel();
            container.setAlpha(1f);
        }
        render(snapshot);
        main.removeCallbacks(ticker);
        main.postDelayed(ticker, TICK_MS);
    }

    @Override
    public void onFinished(final SweepProgress.Snapshot snapshot) {
        main.post(new Runnable() {
            @Override public void run() {
                main.removeCallbacks(ticker);
                if (container == null) {
                    return;                      // never attached; nothing to wind down
                }
                render(snapshot);
                container.animate().alpha(0f).setStartDelay(HOLD_MS).setDuration(FADE_MS).start();
                main.removeCallbacks(detach);
                main.postDelayed(detach, HOLD_MS + FADE_MS + 50L);
            }
        });
    }

    // ---- views -------------------------------------------------------------

    private void attachTo(Activity activity) {
        try {
            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) {
                return;
            }
            if (container != null && container.getParent() == content) {
                return;                          // already where it belongs
            }
            detachNow();
            container = buildBar(activity);
            attachedTo = new WeakReference<Activity>(activity);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP);
            // Below the status bar AND below the app's own header, so neither
            // the clock nor the "Interest" title is covered. content is
            // already attached, so its insets are the real ones.
            lp.topMargin = statusBarInset(content) + dp(activity, HEADER_DP);
            // Added last, so it draws over the app's own content.
            content.addView(container, lp);
        } catch (Throwable t) {
            Log.e(HookUtil.TAG, "SweepProgressOverlay: attach failed -- " + t);
        }
    }

    private void detachNow() {
        try {
            if (container != null && container.getParent() instanceof FrameLayout) {
                ((FrameLayout) container.getParent()).removeView(container);
            }
        } catch (Throwable ignored) {
        }
        container = null;
        bar = null;
        caption = null;
        attachedTo = new WeakReference<Activity>(null);
    }

    private LinearLayout buildBar(Context ctx) {
        final LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SCRIM_COLOR);
        root.setClickable(false);
        root.setFocusable(false);
        int pad = dp(ctx, PAD_DP);
        root.setPadding(pad, pad, pad, pad);
        // Keeps the offset right across a rotation or an inset change. The
        // first placement is done in attachTo() from the content view's own
        // insets, which are already real by then, so the bar never appears at
        // the wrong height and then jumps.
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top = 0;
                try {
                    top = insets.getInsets(WindowInsets.Type.statusBars()).top;
                } catch (Throwable ignored) {
                }
                offsetBelowHeader(v, top + dp(v.getContext(), HEADER_DP));
                return insets;
            }
        });

        bar = new BarView(ctx);
        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, BAR_HEIGHT_DP)));

        caption = new TextView(ctx);
        caption.setTextColor(TEXT_COLOR);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, CAPTION_SP);
        caption.setSingleLine(true);
        LinearLayout.LayoutParams capParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        capParams.topMargin = dp(ctx, 6);
        root.addView(caption, capParams);
        return root;
    }

    private void render(SweepProgress.Snapshot snapshot) {
        if (container == null || snapshot == null) {
            return;
        }
        if (caption != null) {
            caption.setText(snapshot.caption());
        }
        if (bar != null) {
            bar.setTarget(snapshot.fraction());
        }
    }

    private static int statusBarInset(View attached) {
        try {
            WindowInsets insets = attached.getRootWindowInsets();
            if (insets != null) {
                return insets.getInsets(WindowInsets.Type.statusBars()).top;
            }
        } catch (Throwable ignored) {
        }
        return 0;                                // the listener corrects it
    }

    private static void offsetBelowHeader(View v, int top) {
        try {
            android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp instanceof FrameLayout.LayoutParams
                    && ((FrameLayout.LayoutParams) lp).topMargin != top) {
                ((FrameLayout.LayoutParams) lp).topMargin = top;
                v.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {
        }
    }

    private static int dp(Context ctx, int value) {
        try {
            return Math.round(ctx.getResources().getDisplayMetrics().density * value);
        } catch (Throwable t) {
            return value * 3;
        }
    }

    /**
     * Track plus fill, drawn outright. No resources and no theme attributes,
     * so the bar looks the same whatever the host app's theme does and
     * whatever stitch did or did not merge into the resource table.
     */
    private static final class BarView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float shown;
        private float target;

        BarView(Context ctx) {
            super(ctx);
        }

        /** Eased toward, not jumped to, so a 250ms tick still reads as motion. */
        void setTarget(float fraction) {
            target = Math.max(0f, Math.min(1f, fraction));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float height = getHeight();
            float width = getWidth();
            if (height <= 0f || width <= 0f) {
                return;
            }
            float radius = height / 2f;

            paint.setColor(TRACK_COLOR);
            rect.set(0f, 0f, width, height);
            canvas.drawRoundRect(rect, radius, radius, paint);

            shown += (target - shown) * EASE;
            if (Math.abs(target - shown) < 0.001f) {
                shown = target;
            }
            if (shown > 0f) {
                paint.setColor(FILL_COLOR);
                // Never narrower than the cap, or a rounded rect draws nothing.
                rect.set(0f, 0f, Math.max(height, width * shown), height);
                canvas.drawRoundRect(rect, radius, radius, paint);
            }
            if (shown != target) {
                postInvalidateOnAnimation();
            }
        }
    }
}
