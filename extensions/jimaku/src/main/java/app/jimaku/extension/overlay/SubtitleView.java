package app.jimaku.extension.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.LocaleList;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.view.View;
import android.view.accessibility.CaptioningManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Draws one subtitle inside the visible part of the video, centred near its bottom. Each line
 * of the document is laid out on its own and wraps within 90% of the video's width; each row
 * gets a dark ground as wide as its words, and the rows' grounds touch without overlapping.
 * The view takes no touches and no focus, so taps reach the player below it.
 */
public final class SubtitleView extends View {
    /**
     * The text size is a fixed part plus a share of the visible video's height, as YouTube sizes
     * its own captions at their default size: measured on YouTube 21.16.256 as 13.7 dp in the
     * portrait player (video 232 dp tall) and 15.9 dp in fullscreen (411 dp).
     */
    private static final float TEXT_SIZE_BASE_DP = 10.9f;
    private static final float TEXT_SIZE_FRACTION = 0.0122f;
    private static final float WRAP_FRACTION = 0.9f;
    /** Gap between the subtitle and the video's bottom edge, of the video's height. */
    private static final float BOTTOM_GAP_FRACTION = 0.06f;
    /** Gap between the subtitle and the controls while they show, of the text size. */
    private static final float CONTROLS_GAP_EMS = 0.5f;
    /** Ground beyond the words on each side, of the text size. */
    private static final float GROUND_PADDING_EMS = 0.25f;
    /** YouTube's default caption ground: black at 75%. */
    private static final int GROUND_COLOR = 0xBF000000;

    /** Told when the view may have started or stopped being on screen. */
    public interface OnScreenChange {
        void onScreenChange();
    }

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groundPaint = new Paint();
    private final float fontScale;
    private final float density;
    private final Rect video = new Rect();
    private final List<StaticLayout> layouts = new ArrayList<>();

    private OnScreenChange onScreenChange;
    private String text;
    private String lang;
    private int controlsTop = -1;

    private String laidOutText;
    private String laidOutLang;
    private int laidOutWidth;
    private float laidOutSize;
    private int laidOutHeight;

    public SubtitleView(Context context) {
        super(context);
        setClickable(false);
        setLongClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(Typeface.DEFAULT);
        groundPaint.setColor(GROUND_COLOR);
        groundPaint.setStyle(Paint.Style.FILL);

        CaptioningManager captioning = context.getSystemService(CaptioningManager.class);
        fontScale = captioning != null ? captioning.getFontScale() : 1f;
        density = context.getResources().getDisplayMetrics().density;
    }

    public void setOnScreenChange(OnScreenChange listener) {
        onScreenChange = listener;
    }

    /** The subtitles' language, as a BCP 47 tag, so the text is drawn as text of that language. */
    public void setLanguage(String lang) {
        if (Objects.equals(this.lang, lang)) return;
        this.lang = lang;
        invalidate();
    }

    /** The subtitle to show, or null for none. */
    public void setText(String text) {
        if (Objects.equals(this.text, text)) return;
        this.text = text;
        invalidate();
    }

    /** The part of this view the video covers on screen. */
    public void setVideoArea(Rect area) {
        if (video.equals(area)) return;
        video.set(area);
        invalidate();
    }

    /** The top of the controls in this view's coordinates while they show, otherwise -1. */
    public void setControlsTop(int top) {
        if (controlsTop == top) return;
        controlsTop = top;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (text == null || video.isEmpty()) return;
        float size = (TEXT_SIZE_BASE_DP * density + video.height() * TEXT_SIZE_FRACTION) * fontScale;
        int wrapWidth = Math.max(1, Math.round(video.width() * WRAP_FRACTION));
        layOut(size, wrapWidth);

        int bottom = video.bottom - Math.round(video.height() * BOTTOM_GAP_FRACTION);
        if (controlsTop >= 0) bottom = Math.min(bottom, controlsTop - Math.round(size * CONTROLS_GAP_EMS));
        int top = Math.max(video.top, bottom - laidOutHeight);
        float left = video.exactCenterX() - wrapWidth / 2f;
        float padding = size * GROUND_PADDING_EMS;

        canvas.save();
        canvas.clipRect(video);
        int y = top;
        for (StaticLayout layout : layouts) {
            for (int row = 0; row < layout.getLineCount(); row++) {
                if (layout.getLineMax(row) <= 0) continue;
                canvas.drawRect(
                        left + layout.getLineLeft(row) - padding,
                        y + layout.getLineTop(row),
                        left + layout.getLineRight(row) + padding,
                        y + layout.getLineBottom(row),
                        groundPaint);
            }
            canvas.save();
            canvas.translate(left, y);
            layout.draw(canvas);
            canvas.restore();
            y += layout.getHeight();
        }
        canvas.restore();
    }

    private void layOut(float size, int wrapWidth) {
        if (Objects.equals(text, laidOutText) && Objects.equals(lang, laidOutLang)
                && wrapWidth == laidOutWidth && size == laidOutSize) {
            return;
        }
        textPaint.setTextSize(size);
        if (lang != null) {
            textPaint.setTextLocales(new LocaleList(Locale.forLanguageTag(lang)));
        } else {
            textPaint.setTextLocales(LocaleList.getDefault());
        }

        layouts.clear();
        int height = 0;
        for (String line : text.split("\n", -1)) {
            StaticLayout layout = StaticLayout.Builder.obtain(line, 0, line.length(), textPaint, wrapWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                    .setIncludePad(false)
                    .setUseLineSpacingFromFallbacks(true)
                    .build();
            layouts.add(layout);
            height += layout.getHeight();
        }
        laidOutText = text;
        laidOutLang = lang;
        laidOutWidth = wrapWidth;
        laidOutSize = size;
        laidOutHeight = height;
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (onScreenChange != null) onScreenChange.onScreenChange();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (onScreenChange != null) onScreenChange.onScreenChange();
    }
}
