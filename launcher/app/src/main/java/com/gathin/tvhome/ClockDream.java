package com.gathin.tvhome;

import android.service.dreams.DreamService;

/**
 * Screensaver: a lock-screen clock — date over a huge time at the top left — on Home's wallpaper with
 * the picture rising in front of the clock's lower edge, or on black over a slow ambient glow when the
 * theme shows no picture (ClockScene). No network; the picture is Home's own, decoded once at screen
 * size, cheap enough for a 1 GB TV.
 */
public class ClockDream extends DreamService {

    private ClockScene scene;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);
        scene = new ClockScene(getWindow().getDecorView());
        scene.useHomeWallpaper(this);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        scene.start();
    }

    @Override
    public void onDreamingStopped() {
        scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.stop();
        super.onDetachedFromWindow();
    }
}
