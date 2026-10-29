package app.jimaku.extension.ui;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import app.jimaku.extension.shared.IconPaths;

/**
 * The extension's icons as Android paths, built from the path data {@code lib/icons.ts} keeps.
 * Material Symbols sit in a 960-unit grid from y = -960; {@link #draw} scales one into a square.
 */
public final class Icons {
    /** An icon: a filled path, or a stroked one, in its own grid. */
    public static final class Icon {
        final Path path;
        final float gridX;
        final float gridY;
        final float gridSize;
        /** The stroke width in grid units, or 0 for a filled icon. */
        final float stroke;

        Icon(Path path, float gridX, float gridY, float gridSize, float stroke) {
            this.path = path;
            this.gridX = gridX;
            this.gridY = gridY;
            this.gridSize = gridSize;
            this.stroke = stroke;
        }
    }

    public static final Icon CONTROL = new Icon(controlGlyph(false), 0, -960, 960, 0);
    public static final Icon CONTROL_ON = new Icon(controlGlyph(true), 0, -960, 960, 0);
    public static final Icon TUNE = new Icon(path(IconPaths.TUNE), 0, -960, 960, 0);
    public static final Icon TRANSLATE = new Icon(path(IconPaths.TRANSLATE), 0, -960, 960, 0);
    /** The sparkle alone, in its own box, as the extension's {@code spark()} draws it. */
    public static final Icon SPARKLE = new Icon(path(IconPaths.SPARKLE), 552, -910, 358, 0);
    public static final Icon CHECK = new Icon(path(IconPaths.STROKE_CHECK), 0, 0, 24, 2);
    public static final Icon NEXT = new Icon(path(IconPaths.STROKE_NEXT), 0, 0, 24, 2);
    public static final Icon SEARCH = new Icon(path(IconPaths.STROKE_SEARCH), 0, 0, 24, 2);
    public static final Icon DUAL = new Icon(dual(), 0, 0, 24, 0);

    private static final Matrix matrix = new Matrix();
    private static final Path scaled = new Path();

    private Icons() {
    }

    /** Draws {@code icon} into the square at {@code left}, {@code top} of {@code size} px. */
    public static void draw(Canvas canvas, Icon icon, float left, float top, float size, Paint paint) {
        float k = size / icon.gridSize;
        matrix.setTranslate(-icon.gridX, -icon.gridY);
        matrix.postScale(k, k);
        matrix.postTranslate(left, top);
        icon.path.transform(matrix, scaled);
        Paint.Style style = paint.getStyle();
        float width = paint.getStrokeWidth();
        if (icon.stroke > 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(icon.stroke * k);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        } else {
            paint.setStyle(Paint.Style.FILL);
        }
        canvas.drawPath(scaled, paint);
        paint.setStyle(style);
        paint.setStrokeWidth(width);
    }

    static Path path(String data) {
        Path path = new Path();
        SvgPath.parse(data, new SvgPath.Sink() {
            @Override
            public void moveTo(float x, float y) {
                path.moveTo(x, y);
            }

            @Override
            public void lineTo(float x, float y) {
                path.lineTo(x, y);
            }

            @Override
            public void quadTo(float x1, float y1, float x, float y) {
                path.quadTo(x1, y1, x, y);
            }

            @Override
            public void cubicTo(float x1, float y1, float x2, float y2, float x, float y) {
                path.cubicTo(x1, y1, x2, y2, x, y);
            }

            @Override
            public void close() {
                path.close();
            }
        });
        return path;
    }

    /**
     * The player control: the subtitles frame with the sparkle at its top right and a gap cut
     * around it, as the extension's {@code controlGlyph()}. Filled, the frame is the logo's: a
     * solid rounded rectangle with its two caption lines cut out.
     */
    private static Path controlGlyph(boolean filled) {
        Path sparkle = path(IconPaths.SPARKLE);
        Matrix place = new Matrix();
        place.setTranslate(IconPaths.SPARKLE_SX, IconPaths.SPARKLE_SY);
        place.postScale(IconPaths.SPARKLE_SCALE, IconPaths.SPARKLE_SCALE);
        place.postTranslate(IconPaths.SPARKLE_TX, IconPaths.SPARKLE_TY);
        sparkle.transform(place);

        Paint gapPaint = new Paint();
        gapPaint.setStyle(Paint.Style.STROKE);
        gapPaint.setStrokeJoin(Paint.Join.ROUND);
        gapPaint.setStrokeWidth(IconPaths.SPARKLE_GAP * IconPaths.SPARKLE_SCALE);
        Path gap = new Path();
        gapPaint.getFillPath(sparkle, gap);
        gap.op(sparkle, Path.Op.UNION);

        Path frame;
        if (filled) {
            frame = new Path();
            frame.addRoundRect(new RectF(IconPaths.FRAME_X, IconPaths.FRAME_Y,
                            IconPaths.FRAME_X + IconPaths.FRAME_WIDTH, IconPaths.FRAME_Y + IconPaths.FRAME_HEIGHT),
                    IconPaths.FRAME_RADIUS, IconPaths.FRAME_RADIUS, Path.Direction.CW);
            Path cuts = new Path();
            float r = IconPaths.LINE_STROKE;
            for (float[] line : IconPaths.LINES) {
                cuts.addRoundRect(new RectF(line[0], line[1], line[0] + line[2], line[1] + 2 * r), r, r, Path.Direction.CW);
            }
            for (float[] dot : IconPaths.DOTS) cuts.addCircle(dot[0], dot[1], r, Path.Direction.CW);
            frame.op(cuts, Path.Op.DIFFERENCE);
        } else {
            frame = path(IconPaths.SUBTITLES);
        }
        frame.op(gap, Path.Op.DIFFERENCE);
        frame.op(sparkle, Path.Op.UNION);
        return frame;
    }

    /** Two caption lines, a short one over a long one: the extension's {@code dualIcon()}. */
    private static Path dual() {
        Path path = new Path();
        path.addRoundRect(new RectF(6.5f, 6f, 17.5f, 9f), 1.5f, 1.5f, Path.Direction.CW);
        path.addRoundRect(new RectF(3f, 12f, 21f, 17.5f), 2f, 2f, Path.Direction.CW);
        return path;
    }
}
