package com.bajiao.notificationisland;

import android.text.TextUtils;
import android.widget.TextView;
import android.view.View;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.LinkedHashSet;
import java.lang.ref.WeakReference;
import android.view.ViewTreeObserver;

/** Opt-in native marquee for marked relay text; no notification timers or reposting. */
final class IslandMarqueeHook {
    private static final Map<TextView, Binding> changed = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> updating = ThreadLocal.withInitial(() -> false);
    private static final LinkedHashSet<String> compactTexts = new LinkedHashSet<>();
    private static String leftLabel = "", displayedText = "";
    private static android.graphics.drawable.Icon sourceIcon;
    private static final String LABEL_TAG = "notification_island_left_label";

    static synchronized void rememberCompact(String full, boolean enabled, String label, String shortText,
                                             android.graphics.drawable.Icon icon) {
        // One relay slot: don't keep matching text from an older enabled notification.
        compactTexts.clear();
        if (enabled && full != null) compactTexts.add(full);
        leftLabel = label == null ? "" : label;
        displayedText = enabled && full != null ? full : shortText;
        sourceIcon = icon;
    }

    private static boolean inRightSection(TextView view) {
        View node = view;
        for (int i = 0; node != null && i < 8; i++) {
            try { if ("right_section".equals(node.getResources().getResourceEntryName(node.getId()))) return true; }
            catch (RuntimeException ignored) {}
            node = node.getParent() instanceof View ? (View) node.getParent() : null;
        }
        return false;
    }

    private static void bindLeftLabel(TextView right) {
        if (!inRightSection(right)) return;
        boolean matches;
        synchronized (IslandMarqueeHook.class) {
            matches = right.getText().toString().equals(displayedText);
        }
        View node = right;
        while (node != null) {
            String id = "";
            try { id = node.getResources().getResourceEntryName(node.getId()); } catch (RuntimeException ignored) {}
            if ("capsule".equals(id) && node instanceof android.view.ViewGroup capsule) {
                TextView previous = capsule.findViewWithTag(LABEL_TAG);
                if (!matches) {
                    if (previous != null) previous.setVisibility(View.GONE);
                    return;
                }
                if (sourceIcon != null) {
                    android.widget.ImageView image = findLeftIcon(capsule, false);
                    if (image != null) {
                        try {
                            image.setImageTintList(null);
                            image.clearColorFilter();
                            image.setImageDrawable(sourceIcon.loadDrawable(right.getContext()));
                        }
                        catch (RuntimeException ignored) {}
                    }
                }
                TextView label = capsule.findViewWithTag(LABEL_TAG);
                if (label == null) {
                    label = new TextView(right.getContext());
                    label.setTag(LABEL_TAG);
                    label.setSingleLine(true);
                    label.setEllipsize(TextUtils.TruncateAt.END);
                    label.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                    capsule.addView(label, new android.view.ViewGroup.LayoutParams(-2, -1));
                    TextView fixed = label;
                    capsule.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> layoutLabel(capsule, fixed));
                }
                label.setVisibility(View.VISIBLE);
                label.setTextColor(right.getCurrentTextColor());
                label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, right.getTextSize());
                label.setTypeface(right.getTypeface());
                label.setText(leftLabel);
                TextView fixed = label;
                capsule.post(() -> layoutLabel(capsule, fixed));
                return;
            }
            node = node.getParent() instanceof View ? (View) node.getParent() : null;
        }
    }

    private static android.widget.ImageView findLeftIcon(View node, boolean left) {
        try { left |= "left_section".equals(node.getResources().getResourceEntryName(node.getId())); }
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

    private static void layoutLabel(android.view.ViewGroup capsule, TextView label) {
        float density = capsule.getResources().getDisplayMetrics().density;
        int start = Math.round(34 * density);
        int end = Math.max(start, capsule.getWidth() / 2 - Math.round(12 * density));
        label.measure(View.MeasureSpec.makeMeasureSpec(end-start, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(capsule.getHeight(), View.MeasureSpec.EXACTLY));
        label.layout(start, 0, end, capsule.getHeight());
    }

    private static synchronized boolean isCompact(CharSequence text) {
        return text != null && compactTexts.contains(text.toString());
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
        private ViewTreeObserver observer;

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
            if (!isCompact(full) || full.codePointCount(0, full.length()) <= 4 || !inRightSection(view)) {
                Binding binding = changed.remove(view);
                if (binding != null) binding.release(view);
                return;
            }
            if (!changed.containsKey(view)) changed.put(view, new Binding(view));
            if (!view.isSingleLine() || view.getMaxLines() != 1) view.setSingleLine(true);
            if (!view.isHorizontallyScrollable()) view.setHorizontallyScrolling(true);
            // Four actual code points, not four ems: narrow Latin text must overflow too.
            int end = full.offsetByCodePoints(0, 4);
            int width = Math.max(1, (int) Math.ceil(view.getPaint().measureText(full, 0, end)))
                    + view.getCompoundPaddingLeft() + view.getCompoundPaddingRight();
            if (view.getMinEms() >= 0 || view.getMinWidth() != 0) view.setMinWidth(0);
            if (view.getMaxEms() >= 0 || view.getMaxWidth() != width) view.setMaxWidth(width);
            if (view.getEllipsize() != TextUtils.TruncateAt.MARQUEE)
                view.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            if (view.getMarqueeRepeatLimit() != -1) view.setMarqueeRepeatLimit(-1);
            if (!view.isSelected()) view.setSelected(true);
        } finally {
            updating.set(false);
        }
    }
}
