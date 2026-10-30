package com.bajiao.notificationisland;

import android.text.TextUtils;
import android.widget.TextView;
import android.view.View;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.LinkedHashSet;

/** Opt-in native marquee for marked relay text; no notification timers or reposting. */
final class IslandMarqueeHook {
    private static final Map<TextView, Original> changed = new WeakHashMap<>();
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
        if (!inRightSection(right) || !right.getText().toString().equals(displayedText)) return;
        View node = right;
        while (node != null) {
            String id = "";
            try { id = node.getResources().getResourceEntryName(node.getId()); } catch (RuntimeException ignored) {}
            if ("capsule".equals(id) && node instanceof android.view.ViewGroup capsule) {
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
        bindLeftLabel(view);
        if (!changed.containsKey(view)) {
            afterSetText(view, view.getText());
        } else {
            view.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            view.setMarqueeRepeatLimit(-1);
            view.setSelected(true);

        }
    }

    private static final class Original {
        final TextUtils.TruncateAt ellipsize;
        final int maxLines, minWidth, maxWidth, minEms, maxEms, repeat;
        final boolean selected;
        Original(TextView v) {
            ellipsize = v.getEllipsize(); maxLines = v.getMaxLines();
            minWidth = v.getMinWidth(); maxWidth = v.getMaxWidth();
            minEms = v.getMinEms(); maxEms = v.getMaxEms();
            repeat = v.getMarqueeRepeatLimit(); selected = v.isSelected();
        }
        void restore(TextView v) {
            v.setSingleLine(false);
            v.setMaxLines(maxLines);
            if (minEms >= 0) v.setMinEms(minEms); else v.setMinWidth(minWidth);
            if (maxEms >= 0) v.setMaxEms(maxEms); else v.setMaxWidth(maxWidth);
            v.setEllipsize(ellipsize);
            v.setMarqueeRepeatLimit(repeat);
            v.setSelected(selected);
        }
    }

    static void afterSetText(TextView view, CharSequence value) {
        if (updating.get()) return;
        updating.set(true);
        try {
            bindLeftLabel(view);
            String full = null;
            if (full == null && isCompact(value) && inRightSection(view)) {
                full = value.toString();
            }
            if (full == null || full.codePointCount(0, full.length()) <= 4) {
                Original original = changed.remove(view);
                if (original != null) original.restore(view);
                return;
            }
            if (!changed.containsKey(view)) changed.put(view, new Original(view));
            view.setText(full);
            view.setSingleLine(true);
            view.setMaxEms(4);
            view.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            view.setMarqueeRepeatLimit(-1);
            view.setSelected(true);
        } finally {
            updating.set(false);
        }
    }
}
