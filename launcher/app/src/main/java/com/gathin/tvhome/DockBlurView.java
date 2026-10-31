package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * A blurred copy of the wallpaper, clipped to a rounded rectangle (config.xml dock_blur): the dock band, the
 * top bar's glass plate or a glass popup's panel. The blur itself is the view's RenderEffect, set by MainActivity
 * on Android 12 and later; this view only draws the same cover-cropped picture the wallpaper ImageView shows,
 * placed at its screen position so the pixels line up with the backdrop whatever window or offset this view
 * sits at. It is static: nothing redraws it while the wallpaper and the clip's place stay the same, so the GPU
 * caches the blurred result and the UI's own frames over it cost nothing extra.
 */
public class DockBlurView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final int[] loc = new int[2];
    private Bitmap wallpaper;
    private float radius;

    public DockBlurView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Clip to the round rect through the outline (an RRect clip), not Canvas.clipPath: on this TV's
        // GPU pipeline clipPath yielded an empty clip and drew nothing at all, while outline clipping works.
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline outline) {
                if (rect.isEmpty()) {
                    outline.setEmpty();
                    return;
                }
                // setBand speaks screen coordinates; the outline lives in view coordinates, so shift by where
                // this view sits (its own bleed margins inside a popup panel, or its window's offset).
                v.getLocationOnScreen(loc);
                outline.setRoundRect((int) (rect.left - loc[0]), (int) (rect.top - loc[1]),
                        (int) (rect.right - loc[0]), (int) (rect.bottom - loc[1]), radius);
            }
        });
    }

    /** The wallpaper as it is on screen now; null when there is none or it was dropped under memory pressure. */
    void setWallpaper(Bitmap bitmap) {
        wallpaper = bitmap;
        Log.d("TvHome.DockBlur", "setWallpaper " + (bitmap == null ? "null" : bitmap.getWidth() + "x" + bitmap.getHeight()
                + " " + bitmap.getConfig()));
        invalidate();
    }

    /** The band's screen rectangle in pixels and its corner radius; an empty one hides the layer. */
    void setBand(float left, float top, float right, float bottom, float cornerPx) {
        if (rect.left == left && rect.top == top && rect.right == right && rect.bottom == bottom
                && radius == cornerPx) return;
        rect.set(left, top, right, bottom);
        radius = cornerPx;
        invalidateOutline();
        Log.d("TvHome.DockBlur", "setBand " + rect + " r=" + radius);
        invalidate();
    }

@Override
    protected void onDraw(Canvas canvas) {
        Log.d("TvHome.DockBlur", "onDraw wallpaper=" + (wallpaper == null ? "null"
                : wallpaper.isRecycled() ? "recycled" : "ok") + " rect=" + rect + " view=" + getWidth() + "x" + getHeight());
        if (wallpaper == null || wallpaper.isRecycled() || rect.isEmpty()) return;
        getLocationOnScreen(loc);
        // The same cover-crop the wallpaper ImageView shows, anchored to the screen rather than to this view:
        // a small instance inside a popup draws the fragment of the picture that sits behind its window.
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float scale = Math.max((float) dm.widthPixels / wallpaper.getWidth(), (float) dm.heightPixels / wallpaper.getHeight());
        float dx = (dm.widthPixels - wallpaper.getWidth() * scale) / 2f - loc[0];
        float dy = (dm.heightPixels - wallpaper.getHeight() * scale) / 2f - loc[1];
        int save = canvas.save();
        canvas.translate(dx, dy);
        canvas.scale(scale, scale);
        canvas.drawBitmap(wallpaper, 0, 0, paint);
        canvas.restoreToCount(save);
    }
}
