package com.gathin.tvhome;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * A liquid-glass background in the shape of its own bounds, in four layers: the blurred wallpaper (the shared
 * blurred copy of the screen, drawn at the view's screen position so the smear lines up with the picture
 * behind it), the pills'/dock's white body fading downwards, a soft sheen off the top-left corner, and the
 * moulded edge - a dark inner shadow under the top rim that gives the glass thickness, and a bright rim that
 * fades towards the bottom, so the shape reads as a lens and not as a flat slab. Used as the background of the
 * top-right pills and of the glass popups' panels, so the frost is part of the background and can never cover
 * content.
 *
 * While the view is focused the slab turns solid white, like the pills' focused state always was.
 *
 * This is the same look dock_blur's RenderEffect layer gives the band, without a RenderEffect: a background
 * draws on every API level and stays under the view's children by construction.
 */
public class BackdropGlassDrawable extends Drawable {

    private final Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The glint in the top-left corner: liquid glass catches the room's light there, not only at the rim. */
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The dark inner shadow just under the top rim, which is what makes the shape look moulded. */
    private final Paint depth = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The bright rim, fading towards the bottom where the glass catches the light less. */
    private final Paint lens = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** How much the backdrop is magnified inside the shape: about 3.5% at the edges reads as bent light. */
    private static final float REFRACTION = 1.035f;
    private final RectF rect = new RectF();
    private final Matrix shaderMatrix = new Matrix();
    /** Where the shape sits on screen, so the shader shows the part of the picture behind it. */
    private int offsetX, offsetY;
    private final float density;
    private final float radius;
    private final int scrimTop, scrimBottom;
    /** The shared blurred copy of the screen-sized wallpaper; null before the first picture or when it was dropped. */
    private Bitmap backdrop;
    /** The same picture for the whole process, so QuickSettingsActivity (the remote's Settings button, its own
     *  task) frosts from it without blurring the wallpaper a second time. */
    private static volatile Bitmap shared;

    static void share(Bitmap blurred) {
        shared = blurred;
    }

    static Bitmap sharedBackdrop() {
        return shared;
    }
    private boolean focused;

    /** The constructor DrawableInflater uses for pill_glass.xml: drawables are inflated with no arguments.
     *  The density is the system's, which is this window's too; a 21dp pill corner, the pills' white glass. */
    public BackdropGlassDrawable() {
        this(Resources.getSystem().getDisplayMetrics().density, 21f, BODY_TOP, BODY_BOTTOM);
    }

    /** The glass body's white, 20% at the top fading to 7%: light enough that the picture behind reads
     *  through the pills, the dock band and the popups, and still milky enough to frost what is behind. */
    static final int BODY_TOP = 0x33FFFFFF, BODY_BOTTOM = 0x12FFFFFF;

    /** @param density     px per dp.
     *  @param radiusDp    the shape's corner radius in dp (a 21dp pill at 42dp height is a circle).
     *  @param scrimTop    the glass fill at the top of the shape, an ARGB colour carrying its alpha.
     *  @param scrimBottom the same at the bottom (the pills and panels fade from 28% to 12% white, milky enough
     *                     to read as frosted glass over a dark picture and still show it through). */
    BackdropGlassDrawable(float density, float radiusDp, int scrimTop, int scrimBottom) {
        radius = radiusDp * density;
        this.scrimTop = scrimTop;
        this.scrimBottom = scrimBottom;
        this.density = density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(density);
        rim.setColor(0x38FFFFFF);
        solid.setColor(0xF5FFFFFF);
        lens.setStyle(Paint.Style.STROKE);
        lens.setStrokeWidth(1.6f * density);
        depth.setStyle(Paint.Style.STROKE);
        depth.setStrokeWidth(2f * density);
    }

    /** The blurred screen at the picture's own size; null when there is no picture (the plain glass stays). */
    void setBackdrop(Bitmap blurred) {
        backdrop = blurred;
        applyShader();
        invalidateSelf();
    }

    /** Where this shape sits on screen, so the shader shows the part of the picture that is behind it. */
    void setScreenOffset(int x, int y) {
        offsetX = x;
        offsetY = y;
        applyShader();
        invalidateSelf();
    }

    private void applyShader() {
        if (backdrop == null) {
            blurPaint.setShader(null);
            return;
        }
        // The picture behind the glass, then a slight magnification about the shape's own centre: the light that
        // crosses the rim is bent, so what is inside the glass is a touch larger than what is outside it. That
        // small shift at the edges is what reads as a lens rather than as a pane of tinted glass.
        shaderMatrix.setTranslate(-offsetX, -offsetY);
        shaderMatrix.postScale(REFRACTION, REFRACTION, rect.centerX(), rect.centerY());
        BitmapShader shader = new BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        shader.setLocalMatrix(shaderMatrix);
        blurPaint.setShader(shader);
    }

    /**
     * The screen-sized blurred copy the backdrops share, made once per wallpaper: three sliding box passes,
     * whose combined response is the Gaussian the dock's RenderEffect draws, at the same sigma - so the pills
     * and popups read as smooth frosted glass instead of the blocky artefacts a single downscale/upscale pass
     * leaves. The wallpaper's hardware texture is copied to a software bitmap first, since neither getPixels
     * nor a software canvas can touch one. Returns null when there is no picture or no memory for it.
     */
    static Bitmap blurOf(Bitmap source, float sigma) {
        if (source == null || source.isRecycled() || sigma < 1f) return null;
        try {
            int w = source.getWidth(), h = source.getHeight();
            int[] pix = new int[w * h];
            if (source.getConfig() == Bitmap.Config.HARDWARE) {
                Bitmap copy = source.copy(Bitmap.Config.ARGB_8888, false);
                if (copy == null) return null;
                copy.getPixels(pix, 0, w, 0, 0, w, h);
                copy.recycle();
            } else {
                source.getPixels(pix, 0, w, 0, 0, w, h);
            }
            int r = boxRadius(sigma);
            int n = w * h;
            byte[] plane = new byte[n], tmp = new byte[n];
            for (int shift = 16; shift >= 0; shift -= 8) {
                for (int i = 0; i < n; i++) plane[i] = (byte) ((pix[i] >>> shift) & 0xFF);
                for (int pass = 0; pass < 3; pass++) {
                    boxH(plane, tmp, w, h, r);
                    boxV(tmp, plane, w, h, r);
                }
                int mask = 0xFF << shift;
                for (int i = 0; i < n; i++) pix[i] = (pix[i] & ~mask) | ((plane[i] & 0xFF) << shift);
            }
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            out.setPixels(pix, 0, w, 0, 0, w, h);
            return out;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    /** The one box radius whose three passes sum to a Gaussian of this sigma (boxes-for-gauss). */
    private static int boxRadius(float sigma) {
        int ideal = (int) Math.round(Math.sqrt(4f * sigma * sigma + 1f));
        if (ideal % 2 == 0) ideal--;
        return Math.max(1, (ideal - 1) / 2);
    }

    /** One horizontal box pass with a sliding window, clamped at the row's ends. */
    private static void boxH(byte[] s, byte[] d, int w, int h, int r) {
        int div = 2 * r + 1, half = div / 2;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sum = 0;
            for (int x = -r; x <= r; x++) sum += s[row + clamp(x, w - 1)] & 0xFF;
            for (int x = 0; x < w; x++) {
                d[row + x] = (byte) ((sum + half) / div);
                sum += (s[row + clamp(x + r + 1, w - 1)] & 0xFF) - (s[row + clamp(x - r, w - 1)] & 0xFF);
            }
        }
    }

    /** The vertical half of the same pass. */
    private static void boxV(byte[] s, byte[] d, int w, int h, int r) {
        int div = 2 * r + 1, half = div / 2;
        for (int x = 0; x < w; x++) {
            int sum = 0;
            for (int y = -r; y <= r; y++) sum += s[clamp(y, h - 1) * w + x] & 0xFF;
            for (int y = 0; y < h; y++) {
                d[y * w + x] = (byte) ((sum + half) / div);
                sum += (s[clamp(y + r + 1, h - 1) * w + x] & 0xFF) - (s[clamp(y - r, h - 1) * w + x] & 0xFF);
            }
        }
    }

    private static int clamp(int i, int max) {
        return i < 0 ? 0 : (i > max ? max : i);
    }

    @Override
    public boolean isStateful() {
        return true;   // the focused state turns the pill solid white
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean now = false;
        for (int s : state) if (s == android.R.attr.state_focused) { now = true; break; }
        if (focused != now) {
            focused = now;
            invalidateSelf();
            return true;
        }
        return false;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        rect.set(bounds);
        scrim.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                scrimTop, scrimBottom, Shader.TileMode.CLAMP));
        // The lens: a bright rim that dims towards the bottom, an inner shadow that reads as thickness under
        // it, and a wide glint off the top-left corner. All three in the shape's own coordinates, so a pill
        // and a popup panel look like the same material at their own sizes.
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        lens.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                0x6BFFFFFF, 0x29FFFFFF, Shader.TileMode.CLAMP));
        depth.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                new int[]{0x4D000000, 0x00000000, 0x24FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        sheen.setShader(new RadialGradient(bounds.left + bounds.width() * 0.3f,
                bounds.top + bounds.height() * 0.05f,
                Math.max(bounds.width(), bounds.height()) * 0.65f,
                0x2BFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        // The shader is centred on the shape, so a new size needs it again (refraction).
        applyShader();
    }

    @Override
    public void draw(Canvas canvas) {
        if (focused) {
            // Solid like the pills' focused state always was.
            canvas.drawRoundRect(rect, radius, radius, solid);
            return;
        }
        if (backdrop != null) canvas.drawRoundRect(rect, radius, radius, blurPaint);
        canvas.drawRoundRect(rect, radius, radius, scrim);
        canvas.drawRoundRect(rect, radius, radius, sheen);
        canvas.drawRoundRect(rect, radius, radius, depth);
        canvas.drawRoundRect(rect, radius, radius, lens);
    }

    @Override
    public void setAlpha(int alpha) {
        blurPaint.setAlpha(alpha);
        rim.setAlpha(alpha);
        scrim.setAlpha(alpha);
        sheen.setAlpha(alpha);
        depth.setAlpha(alpha);
        lens.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        blurPaint.setColorFilter(cf);
        scrim.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
