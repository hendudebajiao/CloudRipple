package com.bajiao.notificationisland;

import android.text.TextUtils;
import android.widget.TextView;
import android.view.View;

import java.util.Map;
import java.util.WeakHashMap;

import java.lang.ref.WeakReference;
import android.view.ViewTreeObserver;

/** Opt-in native marquee for marked relay text; no notification timers or reposting. */
final class IslandMarqueeHook {
    private static final Map<TextView, Binding> changed = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> updating = ThreadLocal.withInitial(() -> false);
    private static boolean marqueeEnabled;
    private static final java.lang.reflect.Method START_MARQUEE = nativeMarqueeMethod();

    private static java.lang.reflect.Method nativeMarqueeMethod() {
        try {
            java.lang.reflect.Method method = TextView.class.getDeclaredMethod("startMarquee");
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    private static void resumeMarquee(TextView view) {
        // ColorOS may select its title before the final five-cell layout exists.
        // Native startMarquee is idempotent while running: never toggle selection or
        // replace text on each frame, which would reset the scroll position.
        if (START_MARQUEE == null || !view.isAttachedToWindow() || !view.isShown()
                || view.getLayout() == null || view.getLineCount() != 1) return;
        int available = view.getWidth() - view.getCompoundPaddingLeft() - view.getCompoundPaddingRight();
        if (available <= 0 || view.getLayout().getLineWidth(0) <= available) return;
        try { START_MARQUEE.invoke(view); }
        catch (ReflectiveOperationException | RuntimeException ignored) {}
    }
    private static String leftLabel = "", displayedText = "";
    private static android.graphics.drawable.Icon sourceIcon;
    private static final String LABEL_TAG = "notification_island_left_label";

    static synchronized void rememberCompact(String full, boolean enabled, String label, String shortText,
                                             android.graphics.drawable.Icon icon) {
        // One relay slot: don't keep matching text from an older enabled notification.
        marqueeEnabled = enabled;
        leftLabel = label == null ? "" : label;
        displayedText = enabled && full != null ? full : shortText == null ? "" : shortText;
        sourceIcon = icon;
    }

    private static boolean inRightSection(TextView view) {
        View node = view;
        for (int i = 0; node != null && i < 8; i++) {
            try { if (node.getId() != View.NO_ID && "right_section".equals(node.getResources().getResourceEntryName(node.getId()))) return true; }
            catch (RuntimeException ignored) {}
            node = node.getParent() instanceof View ? (View) node.getParent() : null;
        }
        return false;
    }

    private static void bindLeftLabel(TextView right) {
        if (!isCompact(right.getText()) || !inRightSection(right)) return;
        View root = ancestor(right, "capsule");
        if (!(root instanceof android.view.ViewGroup capsule)) return;
        View left = childWithId(capsule, "left_section");
        if (!(left instanceof android.widget.FrameLayout section)) return;
        if (sourceIcon != null) {
            android.widget.ImageView image = findLeftIcon(section, true);
            if (image != null) {
                try {
                    image.setImageTintList(null);
                    image.clearColorFilter();
                    image.setImageDrawable(sourceIcon.loadDrawable(right.getContext()));
                } catch (RuntimeException ignored) {}
            }
        }
        TextView label = section.findViewWithTag(LABEL_TAG);
        if (label == null) {
            label = new TextView(right.getContext());
            label.setTag(LABEL_TAG);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(android.view.Gravity.CENTER);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            section.addView(label, new android.widget.FrameLayout.LayoutParams(-2, -1,
                    android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL));
        }
        label.setVisibility(View.VISIBLE);
        label.setTextColor(right.getCurrentTextColor());
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, right.getTextSize());
        label.setTypeface(right.getTypeface());
        // Center the actual name in the reserved five-cell area beside the icon.
        // Trailing padding characters would shift short names away from the center.
        if (!TextUtils.equals(label.getText(), leftLabel)) label.setText(leftLabel);
    }

    private static android.widget.ImageView findLeftIcon(View node, boolean left) {
        try { left |= node.getId() != View.NO_ID && "left_section".equals(node.getResources().getResourceEntryName(node.getId())); }
        catch (RuntimeException ignored) {}
        if (left && node instanceof android.widget.ImageView image) return image;
        if (node instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.ImageView image = findLeftIcon(group.getChildAt(i), left);
                if (image != null) return image;
            }
        }
        return null;
    }

    private static synchronized boolean isCompact(CharSequence text) {
        return text != null && !displayedText.isEmpty() && displayedText.contentEquals(text);
    }

    static int slotWidth(TextView view) {
        // Stable five full-width glyph cells; the message itself must never change island width.
        return Math.max(1, (int) Math.ceil(view.getPaint().measureText("国国国国国")));
    }

    private static View ancestor(View view, String resource) {
        for (int i = 0; view != null && i < 10; i++) {
            try { if (view.getId() != View.NO_ID && resource.equals(view.getResources().getResourceEntryName(view.getId()))) return view; }
            catch (RuntimeException ignored) {}
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return null;
    }

    private static View childWithId(View view, String resource) {
        try { if (view.getId() != View.NO_ID && resource.equals(view.getResources().getResourceEntryName(view.getId()))) return view; }
        catch (RuntimeException ignored) {}
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = childWithId(group.getChildAt(i), resource);
                if (match != null) return match;
            }
        }
        return null;
    }

    static void afterAttached(TextView view) {
        // Text is frequently assigned before ColorOS inserts the title into right_section.
        afterSetText(view, view.getText());
        Binding binding = changed.get(view);
        if (binding != null) binding.observe(view);
    }

    private static final class Original {
        final TextUtils.TruncateAt ellipsize;
        final int minLines, maxLines, minWidth, maxWidth, minEms, maxEms, repeat;
        final boolean selected, singleLine, horizontal;
        Original(TextView v) {
            ellipsize = v.getEllipsize(); minLines = v.getMinLines(); maxLines = v.getMaxLines();
            minWidth = v.getMinWidth(); maxWidth = v.getMaxWidth();
            minEms = v.getMinEms(); maxEms = v.getMaxEms();
            repeat = v.getMarqueeRepeatLimit(); selected = v.isSelected();
            singleLine = v.isSingleLine(); horizontal = v.isHorizontallyScrollable();
        }
        void restore(TextView v) {
            v.setSingleLine(singleLine);
            v.setMinLines(minLines);
            v.setMaxLines(maxLines);
            v.setHorizontallyScrolling(horizontal);
            if (minEms >= 0) v.setMinEms(minEms); else v.setMinWidth(minWidth);
            if (maxEms >= 0) v.setMaxEms(maxEms); else v.setMaxWidth(maxWidth);
            v.setEllipsize(ellipsize);
            v.setMarqueeRepeatLimit(repeat);
            v.setSelected(selected);
        }
    }

    /** Only relay titles get a draw observer. Never reset text/selection on every frame. */
    private static final class Binding implements ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener {
        final WeakReference<TextView> target;
        final Original original;
        int diagnosticSamples;
        long lastDiagnostic;
        void diagnose(TextView view) {
            if (!BuildConfig.DEBUG || diagnosticSamples >= 4) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastDiagnostic < 1200 || !view.isAttachedToWindow()) return;
            lastDiagnostic = now;
            diagnosticSamples++;
            String nativeState = "unavailable";
            try {
                java.lang.reflect.Field field = TextView.class.getDeclaredField("mMarquee");
                field.setAccessible(true);
                Object marquee = field.get(view);
                if (marquee != null) {
                    java.lang.reflect.Method scroll = marquee.getClass().getDeclaredMethod("getScroll");
                    scroll.setAccessible(true);
                    java.lang.reflect.Method stopped = marquee.getClass().getDeclaredMethod("isStopped");
                    stopped.setAccessible(true);
                    nativeState = "scroll=" + scroll.invoke(marquee) + ",stopped=" + stopped.invoke(marquee);
                } else nativeState = "none";
            } catch (ReflectiveOperationException | RuntimeException ignored) {}
            android.util.Log.i("IslandMarquee", "width=" + view.getWidth()
                    + ",line=" + (view.getLayout() == null ? -1 : view.getLayout().getLineWidth(0))
                    + ",selected=" + view.isSelected() + ",visible=" + view.isShown()
                    + ",native=" + nativeState);
        }
        private ViewTreeObserver observer;
        final Map<View, Integer> widths = new WeakHashMap<>();
        final Map<android.widget.FrameLayout, Integer> gravities = new WeakHashMap<>();
        WeakReference<TextView> label = new WeakReference<>(null);

        void width(View view, int value) {
            if (view == null || view.getLayoutParams() == null) return;
            android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (!widths.containsKey(view)) widths.put(view, lp.width);
            if (lp.width != value) { lp.width = value; view.setLayoutParams(lp); }
        }
        void layout(TextView right) {
            View section = ancestor(right, "right_section");
            View capsule = ancestor(right, "capsule");
            if (section == null || capsule == null) return;
            View left = childWithId(capsule, "left_section");
            int textWidth = slotWidth(right);
            android.widget.ImageView image = left == null ? null : findLeftIcon(left, true);
            int iconWidth = image == null ? 0 : image.getMeasuredWidth();
            if (iconWidth <= 0 && image != null && image.getLayoutParams() != null)
                iconWidth = Math.max(0, image.getLayoutParams().width);
            int gap = Math.round(6 * right.getResources().getDisplayMetrics().density);
            int side = textWidth + (image == null ? 0 : Math.max(iconWidth,
                    Math.round(20 * right.getResources().getDisplayMetrics().density)) + gap);
            // Keep the OEM central camera spacer and outer padding. Equal side sections
            // reserve icon room on both sides; short names/messages simply leave it blank.
            width(left, side);
            width(section, side);
            // Leave the OEM title wrappers at their natural width. Only the actual
            // text viewport is constrained; forcing every ancestor can break its
            // ContentSwitchView animation/layout and stop native marquee.
            if (left instanceof android.view.ViewGroup group) {
                TextView name = group.findViewWithTag(LABEL_TAG);
                if (name != null) {
                    label = new WeakReference<>(name);
                    width(name, textWidth);
                }
            }
        }

        Binding(TextView view) {
            target = new WeakReference<>(view);
            original = new Original(view);
            view.addOnAttachStateChangeListener(this);
            observe(view);
        }
        void observe(TextView view) {
            ViewTreeObserver next = view.getViewTreeObserver();
            if (observer == next) return;
            unobserve();
            if (next.isAlive()) { observer = next; next.addOnPreDrawListener(this); }
        }
        void unobserve() {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
        }
        @Override public boolean onPreDraw() {
            TextView view = target.get();
            if (view == null) { unobserve(); return true; }
            // System templates may overwrite ellipsize/selection after setText and layout.
            refresh(view);
            return true;
        }
        @Override public void onViewAttachedToWindow(View view) {
            observe((TextView) view);
            afterAttached((TextView) view);
        }
        @Override public void onViewDetachedFromWindow(View view) { unobserve(); }
        void release(TextView view) {
            unobserve();
            view.removeOnAttachStateChangeListener(this);
            original.restore(view);
            for (Map.Entry<View, Integer> entry : widths.entrySet()) {
                View node = entry.getKey();
                android.view.ViewGroup.LayoutParams lp = node.getLayoutParams();
                if (lp != null && lp.width != entry.getValue()) { lp.width = entry.getValue(); node.setLayoutParams(lp); }
            }
            for (Map.Entry<android.widget.FrameLayout, Integer> entry : gravities.entrySet()) {
                android.widget.FrameLayout section = entry.getKey();
                for (int i = 0; i < section.getChildCount(); i++) {
                    View child = section.getChildAt(i);
                    if (widths.containsKey(child) && child.getLayoutParams() instanceof android.widget.FrameLayout.LayoutParams lp) {
                        lp.gravity = entry.getValue(); child.setLayoutParams(lp);
                    }
                }
            }
            TextView name = label.get();
            if (name != null) name.setVisibility(View.GONE);
        }
    }

    static void afterSetText(TextView view, CharSequence value) {
        if (updating.get()) return;
        bindLeftLabel(view);
        refresh(view);
    }

    private static void refresh(TextView view) {
        if (updating.get()) return;
        updating.set(true);
        try {
            String full = view.getText().toString();
            if (!isCompact(full) || !inRightSection(view)) {
                Binding binding = changed.remove(view);
                if (binding != null) binding.release(view);
                return;
            }
            if (!changed.containsKey(view)) changed.put(view, new Binding(view));
            if (!view.isSingleLine() || view.getMaxLines() != 1) view.setSingleLine(true);
            if (!view.isHorizontallyScrollable()) view.setHorizontallyScrolling(true);
            int width = slotWidth(view) + view.getCompoundPaddingLeft() + view.getCompoundPaddingRight();
            if (view.getMinEms() >= 0 || view.getMinWidth() != width) view.setMinWidth(width);
            if (view.getMaxEms() >= 0 || view.getMaxWidth() != width) view.setMaxWidth(width);
            boolean scrolling = marqueeEnabled && view.getPaint().measureText(full) > slotWidth(view);
            TextUtils.TruncateAt ellipsize = scrolling ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END;
            if (view.getEllipsize() != ellipsize) view.setEllipsize(ellipsize);
            if (scrolling && view.getMarqueeRepeatLimit() != -1) view.setMarqueeRepeatLimit(-1);
            if (view.isSelected() != scrolling) view.setSelected(scrolling);
            Binding binding = changed.get(view);
            binding.layout(view);
            if (scrolling) resumeMarquee(view);
            binding.diagnose(view);
        } finally {
            updating.set(false);
        }
    }
}
