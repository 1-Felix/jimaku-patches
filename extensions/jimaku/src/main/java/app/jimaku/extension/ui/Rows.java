package app.jimaku.extension.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.jimaku.extension.Guard;

/**
 * The rows of Jimaku's sheets, in the measures of YouTube's player sheets: 48 dp high, a 24 dp
 * icon 16 dp in, the label 52 dp in at 16 sp, a value in the secondary colour at the row's end.
 */
public final class Rows {
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);

    private Rows() {
    }

    /** A language: the check-mark column, its name, and the sparkle at the end for a Premium version. */
    public static View language(Context context, Theme theme, String name, boolean checked, boolean premium,
                                String premiumName, Runnable press) {
        LinearLayout row = row(context, theme, press);
        row.addView(new IconView(context, theme, checked ? Icons.CHECK : null, theme.text, 24), iconParams(theme));
        row.addView(label(context, theme, name), labelParams());
        if (premium) {
            IconView spark = new IconView(context, theme, Icons.SPARKLE, theme.secondary, 14);
            spark.setContentDescription(premiumName);
            spark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(theme.dp(14), theme.dp(14));
            params.setMarginStart(theme.dp(8));
            row.addView(spark, params);
        }
        row.setContentDescription(premium ? name + ", " + premiumName : name);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.RadioButton");
                info.setCheckable(true);
                info.setChecked(checked);
            }
        });
        return row;
    }

    /** A row with an icon, a label and YouTube's switch at its end. */
    public static View toggle(Context context, Theme theme, Icons.Icon icon, String label, boolean on, boolean enabled,
                              Runnable press) {
        LinearLayout row = row(context, theme, press);
        row.addView(new IconView(context, theme, icon, theme.text, 24), iconParams(theme));
        row.addView(label(context, theme, label), labelParams());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(theme.dp(40), theme.dp(24));
        params.setMarginStart(theme.dp(16));
        row.addView(new SwitchView(context, theme, on && enabled), params);
        if (!enabled) row.setAlpha(theme.disabledAlpha / 255f);
        row.setContentDescription(label);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Switch");
                info.setCheckable(true);
                info.setChecked(on && enabled);
                info.setEnabled(enabled);
            }
        });
        return row;
    }

    /**
     * A row that opens a second sheet: an icon, a label and YouTube's chevron, 24 dp as on its More
     * row, which has no value before it.
     */
    public static View submenu(Context context, Theme theme, Icons.Icon icon, String label, Runnable press) {
        LinearLayout row = row(context, theme, press);
        row.addView(new IconView(context, theme, icon, theme.text, 24), iconParams(theme));
        row.addView(label(context, theme, label), labelParams());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(theme.dp(24), theme.dp(24));
        params.setMarginStart(theme.dp(16));
        row.addView(new IconView(context, theme, Icons.NEXT, theme.text, 24), params);
        row.setContentDescription(label);
        readAsButton(row);
        return row;
    }

    /** A setting stepped with YouTube's round − and +, its value between them in a fixed width. */
    public static View stepper(Context context, Theme theme, String label, String value, String decrease,
                               String increase, boolean canDecrease, boolean canIncrease,
                               Runnable down, Runnable up) {
        LinearLayout row = row(context, theme, null);
        row.setPaddingRelative(theme.dp(16), 0, theme.dp(12), 0);
        row.addView(label(context, theme, label), labelParams());
        row.addView(new RoundButton(context, theme, false, canDecrease, decrease, down), roundParams(theme));
        TextView shown = new TextView(context);
        shown.setText(value);
        shown.setTextColor(theme.text);
        shown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        shown.setTypeface(REGULAR);
        shown.setGravity(Gravity.CENTER);
        shown.setFontFeatureSettings("tnum");
        row.addView(shown, new LinearLayout.LayoutParams(theme.dp(72), ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(new RoundButton(context, theme, true, canIncrease, increase, up), roundParams(theme));
        return row;
    }

    /** A setting that reads a state and carries one text button, as Position with Reset. */
    public static View withAction(Context context, Theme theme, String label, String value, String action,
                                  boolean actionEnabled, Runnable press) {
        LinearLayout row = row(context, theme, null);
        row.setPaddingRelative(theme.dp(16), 0, theme.dp(8), 0);
        row.addView(label(context, theme, label), labelParams());
        TextView shown = secondary(context, theme, value);
        row.addView(shown, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(textButton(context, theme, action, actionEnabled, press),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return row;
    }

    /** A second sheet's title row, as YouTube's Captions sheet has it, with an optional text button at its end. */
    public static View title(Context context, Theme theme, String title, String action, boolean actionEnabled,
                             Runnable press) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(theme.dp(48));
        row.setPaddingRelative(theme.dp(16), 0, theme.dp(8), 0);
        TextView text = new TextView(context);
        text.setText(title);
        text.setTextColor(theme.text);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        text.setTypeface(REGULAR);
        text.setAccessibilityHeading(true);
        row.addView(text, labelParams());
        if (action != null) {
            row.addView(textButton(context, theme, action, actionEnabled, press),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, theme.dp(48)));
        }
        return row;
    }

    /** YouTube's 1 dp divider across the sheet. */
    public static View divider(Context context, Theme theme) {
        View line = new View(context);
        line.setBackgroundColor(theme.divider);
        line.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, theme.dp(1))));
        line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return line;
    }

    /** A group's caption above its rows, in the secondary colour, as the extension's Settings has "Size". */
    public static View caption(Context context, Theme theme, String text) {
        TextView caption = secondary(context, theme, text);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        caption.setMinHeight(theme.dp(36));
        caption.setPaddingRelative(theme.dp(16), theme.dp(8), theme.dp(16), 0);
        caption.setAccessibilityHeading(true);
        caption.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return caption;
    }

    /** Empty space between groups of rows. */
    public static View space(Context context, Theme theme, float dp) {
        View space = new View(context);
        space.setLayoutParams(new LinearLayout.LayoutParams(1, theme.dp(dp)));
        space.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return space;
    }

    private static LinearLayout row(Context context, Theme theme, Runnable press) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(theme.dp(48));
        row.setPaddingRelative(theme.dp(16), 0, theme.dp(16), 0);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (press != null) {
            row.setBackground(ripple(theme, new ColorDrawable(Color.WHITE)));
            row.setOnClickListener(v -> Guard.run("menu press", press));
        }
        return row;
    }

    private static Drawable ripple(Theme theme, Drawable mask) {
        return new RippleDrawable(ColorStateList.valueOf(theme.pressed), null, mask);
    }

    private static TextView label(Context context, Theme theme, String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextColor(theme.text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setTypeface(MEDIUM);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return label;
    }

    private static TextView secondary(Context context, Theme theme, String text) {
        TextView value = new TextView(context);
        value.setText(text);
        value.setTextColor(theme.secondary);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        value.setTypeface(REGULAR);
        value.setGravity(Gravity.CENTER_VERTICAL);
        value.setSingleLine(true);
        return value;
    }

    private static TextView textButton(Context context, Theme theme, String text, boolean enabled, Runnable press) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextColor(theme.link);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTypeface(MEDIUM);
        button.setGravity(Gravity.CENTER);
        button.setPadding(theme.dp(12), 0, theme.dp(12), 0);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : theme.disabledAlpha / 255f);
        if (enabled && press != null) {
            button.setBackground(ripple(theme, new ColorDrawable(Color.WHITE)));
            button.setOnClickListener(v -> Guard.run("menu press", press));
        }
        readAsButton(button);
        return button;
    }

    /** TalkBack reads {@code view} as a button, as it reads the rows and buttons of YouTube's sheets. */
    private static void readAsButton(View view) {
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });
    }

    private static LinearLayout.LayoutParams iconParams(Theme theme) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(theme.dp(24), theme.dp(24));
        params.setMarginEnd(theme.dp(12));
        return params;
    }

    private static LinearLayout.LayoutParams labelParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static LinearLayout.LayoutParams roundParams(Theme theme) {
        return new LinearLayout.LayoutParams(theme.dp(40), theme.dp(40));
    }

    /** One icon in a square, in one colour. */
    static final class IconView extends View {
        private final Icons.Icon icon;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float sizeDp;
        private final Theme theme;

        IconView(Context context, Theme theme, Icons.Icon icon, int color, float sizeDp) {
            super(context);
            this.icon = icon;
            this.theme = theme;
            this.sizeDp = sizeDp;
            paint.setColor(color);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (icon == null) return;
            float size = theme.dp(sizeDp);
            Icons.draw(canvas, icon, (getWidth() - size) / 2f, (getHeight() - size) / 2f, size, paint);
        }
    }

    /** YouTube's switch: a 40 × 24 dp track with its knob at the start when off and at the end when on. */
    static final class SwitchView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Theme theme;
        private final boolean on;
        private final RectF track = new RectF();

        SwitchView(Context context, Theme theme, boolean on) {
            super(context);
            this.theme = theme;
            this.on = on;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight();
            track.set(0, 0, getWidth(), h);
            paint.setColor(on ? theme.switchOnTrack : theme.switchOffTrack);
            canvas.drawRoundRect(track, h / 2, h / 2, paint);
            float inset = theme.dp(2);
            float radius = h / 2 - inset;
            float cx = on ? getWidth() - inset - radius : inset + radius;
            paint.setColor(theme.switchKnob);
            canvas.drawCircle(cx, h / 2, radius, paint);
        }
    }

    /** One of YouTube's round − and + buttons: a 40 dp circle, greyed out where it can't step further. */
    static final class RoundButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Theme theme;
        private final boolean plus;

        RoundButton(Context context, Theme theme, boolean plus, boolean enabled, String name, Runnable press) {
            super(context);
            this.theme = theme;
            this.plus = plus;
            setEnabled(enabled);
            setContentDescription(name);
            readAsButton(this);
            if (enabled) {
                setOnClickListener(v -> Guard.run("menu press", press));
                setBackground(new RippleDrawable(ColorStateList.valueOf(theme.pressed), null, null));
            } else {
                setAlpha(theme.disabledAlpha / 255f);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(theme.roundButton);
            canvas.drawCircle(cx, cy, Math.min(cx, cy), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(theme.dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(theme.text);
            float half = theme.dp(7);
            canvas.drawLine(cx - half, cy, cx + half, cy, paint);
            if (plus) canvas.drawLine(cx, cy - half, cx, cy + half, paint);
        }
    }
}
