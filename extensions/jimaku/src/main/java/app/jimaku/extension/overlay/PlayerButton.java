package app.jimaku.extension.overlay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import app.jimaku.extension.ui.Icons;

/**
 * Jimaku's button in the player's top bar, beside YouTube's CC button and the size of it: the
 * extension's control glyph in white, filled while subtitles or the original caption show, as YouTube
 * fills its CC button while captions are on. It opens Jimaku's menu.
 */
public final class PlayerButton extends View {
    private static final float GLYPH_DP = 24;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private boolean on;

    public PlayerButton(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        paint.setColor(Color.WHITE);
        // YouTube's own player buttons cast a faint shadow, so they read over a white video.
        paint.setShadowLayer(2 * density, 0, 0, 0x66000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null));
        setClickable(true);
        setFocusable(true);
    }

    /** Whether subtitles or the original caption show. */
    public void setOn(boolean value) {
        if (on == value) return;
        on = value;
        invalidate();
    }

    /** The extension's control name, as "Jimaku subtitles: English available". */
    public void setName(String name) {
        if (name.contentEquals(getContentDescription() == null ? "" : getContentDescription())) return;
        setContentDescription(name);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = GLYPH_DP * density;
        Icons.draw(canvas, on ? Icons.CONTROL_ON : Icons.CONTROL,
                (getWidth() - size) / 2f, (getHeight() - size) / 2f, size, paint);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Button");
    }
}
