package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * The screensaver's front layer: the same picture dreamAmbient shows, cropped and darkened the same
 * way, painted over the clock and faded in between two lines ClockScene gives it — so the picture
 * reads as standing in front of the clock, the way an iPhone's lock screen lets the photo cover the
 * time's lower edge. Nothing here can tell a photo's subject from its background (that would need a
 * model the launcher will not carry), so the "subject edge" is a soft line just below the clock's
 * middle: the clock's lower third sinks into the picture, the date and the top of the time stay
 * whole.
 *
 * The fade is one DST_IN pass with a vertical gradient inside an offscreen layer, so it paints the
 * same on every API level this launcher supports. It only redraws when the picture or the clock's
 * spot changes — about once a minute, like the rest of the scene.
 */
final class DepthLayerView extends View {

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint shadePaint = new Paint();
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The picture, the dim over it (0 = none), and the two lines the fade runs between, in view pixels. */
    private Bitmap bitmap;
    private int shade;
    private float maskStart = Float.NaN;
    private float maskEnd = Float.NaN;

    DepthLayerView(Context context) {
        this(context, null);
    }

    DepthLayerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    /** The picture dreamAmbient is showing, with the same darkening; null takes the layer down. */
    void show(Bitmap picture, int dim) {
        bitmap = picture;
        shade = dim;
        setVisibility(picture == null ? GONE : VISIBLE);
        invalidate();
    }

    /**
     * Where the picture starts taking the clock: fully transparent at start, fully in front by end, and
     * painted only between them — so a view below the band (the date) is never covered.
     */
    void setMask(float start, float end) {
        if (start == maskStart && end == maskEnd) return;
        maskStart = start;
        maskEnd = Math.max(end, start + 1f);
        maskPaint.setShader(new LinearGradient(0, maskStart, 0, maskEnd,
                0x00FFFFFF, 0xFFFFFFFF, Shader.TileMode.CLAMP));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap == null || bitmap.isRecycled() || Float.isNaN(maskStart)) return;
        float w = getWidth(), h = getHeight();
        float bw = bitmap.getWidth(), bh = bitmap.getHeight();
        if (w <= 0 || h <= 0 || bw <= 0 || bh <= 0) return;
        int layer = canvas.saveLayer(0, 0, w, h, null);
        // Only the band the fade runs through is painted. Below the last line the picture would cover
        // whatever sits under it — the day and date under the time — while looking identical to the
        // background, so the clip is invisible and keeps them whole.
        canvas.clipRect(0f, Math.max(0f, maskStart), w, Math.min(h, maskEnd));
        // centerCrop, exactly what the ImageView behind this one does with the same picture, so the
        // two line up pixel for pixel and only the clock ever shows a difference.
        float scale = Math.max(w / bw, h / bh);
        canvas.save();
        canvas.translate((w - bw * scale) / 2f, (h - bh * scale) / 2f);
        canvas.scale(scale, scale);
        canvas.drawBitmap(bitmap, 0, 0, bitmapPaint);
        canvas.restore();
        if ((shade >>> 24) != 0) {
            shadePaint.setColor(shade);
            canvas.drawRect(0, 0, w, h, shadePaint);
        }
        canvas.drawRect(0, 0, w, h, maskPaint);   // DST_IN: keep the picture only where the gradient is opaque
        canvas.restoreToCount(layer);
    }
}
