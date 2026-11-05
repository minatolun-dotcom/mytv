package com.gathin.tvhome;

import android.graphics.Bitmap;

/**
 * Text over a light wallpaper (config.xml auto_ink): when the picture behind the header is bright, the white
 * text there washes out, so it turns dark instead. The decision is the mean luminance of the zone the header
 * text sits in (the picture's top left), measured on the bitmap as it is drawn - wallpaper_dim already painted
 * in - so a dimmed bright picture and an undimmed bright one are judged by what the eye actually sees. Sixteen
 * samples across the zone are enough: the question is "light or dark here", not a histogram.
 */
final class Ink {

    /** At or above this mean luminance the zone counts as light, and the text over it turns dark. White text
     *  stays readable down to about here; below it, black text would sink into the picture instead. */
    static final float LIGHT = 0.45f;

    /** The share of the picture the header text covers: the clock block and the app name above the dock. */
    private static final float ZONE_W = 0.42f, ZONE_H = 0.38f;

    private Ink() {
    }

    /** True when the wallpaper's top-left zone, where the clock, the greeting and the date sit, is light. */
    static boolean light(Bitmap picture) {
        if (picture == null || picture.isRecycled()) return false;
        // The wallpaper is decoded as a hardware bitmap, which cannot be read pixel by pixel. One software copy
        // answers the question and is released at once; a picture that cannot be copied answers "dark", which
        // keeps the theme's white text.
        if (picture.getConfig() == Bitmap.Config.HARDWARE) {
            Bitmap copy;
            try {
                copy = picture.copy(Bitmap.Config.ARGB_8888, false);
            } catch (Exception e) {
                return false;
            }
            if (copy == null) return false;
            boolean light = isLight(copy);
            copy.recycle();
            return light;
        }
        return isLight(picture);
    }

    private static boolean isLight(Bitmap picture) {
        int w = picture.getWidth(), h = picture.getHeight();
        if (w <= 0 || h <= 0) return false;
        int zoneW = Math.max(1, Math.round(w * ZONE_W)), zoneH = Math.max(1, Math.round(h * ZONE_H));
        double sum = 0;
        int n = 0;
        for (int i = 0; i < 16; i++) {
            int x = Math.min(w - 1, i * zoneW / 16 + zoneW / 32);
            for (int j = 0; j < 16; j++) {
                int y = Math.min(h - 1, j * zoneH / 16 + zoneH / 32);
                int argb;
                try {
                    argb = picture.getPixel(x, y);
                } catch (Exception e) {
                    return false;
                }
                sum += luminance(argb);
                n++;
            }
        }
        return n > 0 && sum / n >= LIGHT;
    }

    /** Rec. 709 luma, 0 for black and 1 for white. */
    private static double luminance(int argb) {
        double r = ((argb >> 16) & 0xFF) / 255.0, g = ((argb >> 8) & 0xFF) / 255.0, b = (argb & 0xFF) / 255.0;
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /**
     * The same colour with its channels inverted and its alpha kept, so #8CFFFFFF (white at 55%) becomes
     * #8C000000 (black at 55%): every line keeps the emphasis it was designed with, just as dark text.
     */
    static int flip(int argb) {
        return (argb & 0xFF000000) | (~argb & 0x00FFFFFF);
    }
}
