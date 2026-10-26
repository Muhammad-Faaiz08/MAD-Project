package com.vault.app.ui;
import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** Makes any view shrink slightly the instant it's pressed and spring back on release. */
public final class SpringTouch implements View.OnTouchListener {
    private final SpringAnimation sx;
    private final SpringAnimation sy;

    private SpringTouch(View v) {
        sx = make(v, DynamicAnimation.SCALE_X);
        sy = make(v, DynamicAnimation.SCALE_Y);
    }

    private static SpringAnimation make(View v, DynamicAnimation.ViewProperty prop) {
        SpringAnimation a = new SpringAnimation(v, prop, 1f);
        a.setSpring(new SpringForce(1f)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                .setStiffness(900f));
        return a;
    }

    @SuppressLint("ClickableViewAccessibility")
    public static void attach(View v) {
        v.setOnTouchListener(new SpringTouch(v));
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                press(0.97f);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                press(1f);
                break;
        }
        return false; // let normal click handling continue
    }

    private void press(float scale) {
        sx.animateToFinalPosition(scale);
        sy.animateToFinalPosition(scale);
    }
}