package com.bajiao.notificationisland;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Native-view adaptation of the miuix card and floating-pill visual language. */
final class CloudUi {
    static final int BG = 0xffF3F7FC, INK = 0xff1D3551, MUTED = 0xff7A8BA0;
    static final int BLUE = 0xff387DDA, PALE = 0xffE2EEFD, LINE = 0xffEAF0F7;

    static int dp(Context c, float n) { return Math.round(c.getResources().getDisplayMetrics().density * n); }

    static GradientDrawable shape(Context c, int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radius));
        return d;
    }

    static void pressable(View v, int color, int radius) {
        Context c = v.getContext();
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x18387DDA),
                shape(c, color, radius), shape(c, 0xffffffff, radius)));
    }

    static TextView text(Context c, String value, int size, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setIncludeFontPadding(false);
        return v;
    }

    static LinearLayout column(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    static LinearLayout card(Context c) {
        LinearLayout v = column(c);
        v.setPadding(dp(c, 20), dp(c, 20), dp(c, 20), dp(c, 20));
        v.setBackground(shape(c, 0xffFFFFFF, 26));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(c, 14);
        v.setLayoutParams(lp);
        return v;
    }

    /** Floating capsule with an animated selected pill and accessible, 56dp tab targets. */
    static final class Navigation extends FrameLayout {
        private final View indicator;
        private final LinearLayout tabs;
        private final ImageView[] icons = new ImageView[3];
        private final TextView[] labels = new TextView[3];
        private int selected;
        private final java.util.function.IntConsumer onSelected;
        private final int touchSlop;
        private float downX, downY;
        private boolean dragging, cancelled;
        private int pointerId = -1, previewIndex;

        Navigation(Context c, java.util.function.IntConsumer callback) {
            super(c);
            onSelected = callback;
            touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
            setClickable(true);
            setBackground(shape(c, 0xffFFFFFF, 38));
            setElevation(dp(c, 12));
            setOutlineAmbientShadowColor(0xff8AA6C5);
            setOutlineSpotShadowColor(0xff8AA6C5);
            indicator = new View(c);
            indicator.setBackground(shape(c, PALE, 30));
            LayoutParams marker = new LayoutParams(0, dp(c, 56), Gravity.TOP | Gravity.LEFT);
            marker.topMargin = dp(c, 7);
            marker.leftMargin = dp(c, 7);
            addView(indicator, marker);
            tabs = new LinearLayout(c);
            tabs.setPadding(dp(c, 7), dp(c, 7), dp(c, 7), dp(c, 7));
            addView(tabs, new LayoutParams(-1, -1));
            String[] titles = {"首页", "应用", "设置"};
            int[] resources = {R.drawable.ic_tab_home, R.drawable.ic_tab_apps, R.drawable.ic_tab_settings};
            for (int i = 0; i < 3; i++) {
                int index = i;
                LinearLayout tab = column(c);
                tab.setGravity(Gravity.CENTER);
                tab.setFocusable(true);
                tab.setContentDescription(titles[i]);
                tab.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName("android.widget.Button");
                        info.setSelected(selected == index);
                    }
                });
                pressable(tab, 0x00000000, 30);
                icons[i] = new ImageView(c);
                icons[i].setImageResource(resources[i]);
                tab.addView(icons[i], new LinearLayout.LayoutParams(dp(c, 22), dp(c, 22)));
                labels[i] = text(c, titles[i], 11, MUTED, true);
                LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-2, -2);
                labelLp.topMargin = dp(c, 3);
                tab.addView(labels[i], labelLp);
                tab.setOnClickListener(v -> onSelected.accept(index));
                tabs.addView(tab, new LinearLayout.LayoutParams(0, -1, 1));
            }
            select(0, false);
        }

        void select(int index, boolean animate) {
            selected = index;
            tintTabs(index);
            moveIndicator(animate);
        }

        private void tintTabs(int index) {
            for (int i = 0; i < 3; i++) {
                int color = i == index ? BLUE : MUTED;
                icons[i].setImageTintList(ColorStateList.valueOf(color));
                labels[i].setTextColor(color);
                tabs.getChildAt(i).setSelected(i == index);
            }
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX(); downY = event.getY();
                    pointerId = event.getPointerId(0);
                    previewIndex = selected;
                    dragging = false; cancelled = false;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    cancelled = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    int pointer = event.findPointerIndex(pointerId);
                    if (cancelled || pointer < 0) break;
                    float dx = Math.abs(event.getX(pointer) - downX);
                    float dy = Math.abs(event.getY(pointer) - downY);
                    if (dy > touchSlop && dy >= dx) cancelled = true;
                    if (dx > touchSlop && dx > dy) {
                        dragging = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pointerId = -1;
                    break;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
            if (!dragging) return super.onTouchEvent(event);
            int pointer = event.findPointerIndex(pointerId);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (pointer < 0) { finishDrag(false); return true; }
                    updateDrag(event.getX(pointer));
                    return true;
                case MotionEvent.ACTION_UP:
                    if (pointer >= 0) updateDrag(event.getX(pointer));
                    finishDrag(pointer >= 0);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_POINTER_DOWN:
                    finishDrag(false);
                    return true;
                default:
                    return true;
            }
        }

        private void updateDrag(float fingerX) {
            int width = Math.max(0, getWidth() - dp(getContext(), 14)) / 3;
            if (width == 0) return;
            float position = Math.max(0, Math.min(2, (fingerX - dp(getContext(), 7)) / width - .5f));
            indicator.animate().cancel();
            indicator.setTranslationX(position * width);
            int index = Math.round(position);
            if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) index = 2 - index;
            if (previewIndex != index) {
                previewIndex = index;
                tintTabs(index);
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
        }

        private void finishDrag(boolean commit) {
            dragging = false;
            cancelled = true;
            pointerId = -1;
            getParent().requestDisallowInterceptTouchEvent(false);
            if (commit && previewIndex != selected) onSelected.accept(previewIndex);
            else { tintTabs(selected); moveIndicator(true); }
        }

        @Override protected void onDetachedFromWindow() {
            indicator.animate().cancel();
            dragging = false;
            pointerId = -1;
            super.onDetachedFromWindow();
        }

        private void moveIndicator(boolean animate) {
            int width = Math.max(0, getWidth() - dp(getContext(), 14)) / 3;
            if (width == 0) return;
            indicator.getLayoutParams().width = width;
            indicator.requestLayout();
            float x = (getLayoutDirection() == LAYOUT_DIRECTION_RTL ? 2 - selected : selected) * width;
            indicator.animate().cancel();
            if (animate) indicator.animate().translationX(x).setDuration(280)
                    .setInterpolator(new PathInterpolator(.2f, .8f, .2f, 1)).start();
            else indicator.setTranslationX(x);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            moveIndicator(false);
        }
    }
}
