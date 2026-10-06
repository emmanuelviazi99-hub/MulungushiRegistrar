package zm.ac.mulungushi.registrar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

/**
 * The prototype's bold splash: a blue panel drops in from the top, a red one rises
 * from the bottom, the logo tile pops in, then the title and subtitle settle.
 * Timings and the easing curve are copied from the prototype's CSS.
 */
public class SplashActivity extends AppCompatActivity {

    private static final int NAVY = 0xFF00234D;
    private static final int BLUE = 0xFF004F9F;
    private static final int RED = 0xFFE30613;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean left;

    /** Draws one of the two diagonal panels (same polygons as the prototype's clip-path). */
    private static final class Panel extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final boolean top;

        Panel(Context c, int color, boolean top) {
            super(c);
            this.top = top;
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            path.reset();
            if (top) {
                path.moveTo(0, 0);
                path.lineTo(w, 0);
                path.lineTo(w, h * 0.34f);
                path.lineTo(0, h * 0.62f);
            } else {
                path.moveTo(0, h * 0.62f);
                path.lineTo(w, h * 0.34f);
                path.lineTo(w, h);
                path.lineTo(0, h);
            }
            path.close();
            canvas.drawPath(path, paint);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLog.install(this);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        final float d = getResources().getDisplayMetrics().density;
        final int wPx = getResources().getDisplayMetrics().widthPixels;
        final int hPx = getResources().getDisplayMetrics().heightPixels;
        final float wDp = wPx / d;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(NAVY);

        float tileDp = Math.min(140f, Math.max(96f, wDp * 0.30f));
        float radiusDp = Math.min(24f, Math.max(16f, wDp * 0.05f));
        int tile = (int) (tileDp * d);

        // each diagonal panel carries its crest motif, so the motif slides in with it
        FrameLayout blue = new FrameLayout(this);
        blue.addView(new Panel(this, BLUE, true), new FrameLayout.LayoutParams(-1, -1));
        FrameLayout red = new FrameLayout(this);
        red.addView(new Panel(this, RED, false), new FrameLayout.LayoutParams(-1, -1));

        // gear + crops on the blue, tucked under the logo tile on the left edge
        int cropsH = (int) Math.min(hPx * 0.29f, 300f * d);
        ImageView crops = new ImageView(this);
        crops.setImageResource(R.drawable.splash_crops);
        crops.setScaleType(ImageView.ScaleType.FIT_XY);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams((int) (cropsH * 72f / 135f), cropsH);
        clp.leftMargin = 0;
        clp.topMargin = statusBarHeight() + (int) (hPx * 0.09f) + tile + (int) (14 * d);
        blue.addView(crops, clp);

        // graduates on the red, right side
        int gradsH = (int) Math.min(hPx * 0.21f, 190f * d);
        ImageView grads = new ImageView(this);
        grads.setImageResource(R.drawable.splash_grads);
        grads.setScaleType(ImageView.ScaleType.FIT_XY);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams((int) (gradsH * 67f / 105.2f), gradsH);
        glp.gravity = Gravity.END | Gravity.TOP;
        glp.rightMargin = (int) Math.min(wPx * 0.07f, 34f * d);
        glp.topMargin = (int) (hPx * 0.51f);
        red.addView(grads, glp);

        root.addView(blue, new FrameLayout.LayoutParams(-1, -1));
        root.addView(red, new FrameLayout.LayoutParams(-1, -1));

        // logo tile
        FrameLayout logoTile = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable tileBg = new android.graphics.drawable.GradientDrawable();
        tileBg.setColor(0xFFFFFFFF);
        tileBg.setCornerRadius(radiusDp * d);
        logoTile.setBackground(tileBg);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.mulungushi_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setContentDescription(getString(R.string.app_name));
        int inner = (int) (tile * 0.78f);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(inner, inner, Gravity.CENTER);
        logoTile.addView(logo, ilp);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(tile, tile);
        tlp.leftMargin = (int) (wPx * 0.08f);
        tlp.topMargin = statusBarHeight() + (int) (hPx * 0.09f);
        root.addView(logoTile, tlp);

        // title + subtitle block, anchored near the bottom
        LinearLayout textBox = new LinearLayout(this);
        textBox.setOrientation(LinearLayout.VERTICAL);
        TextView big = new TextView(this);
        big.setText("Mulungushi\nRegistrar");
        big.setTextColor(0xFFFFFFFF);
        big.setTextSize(Math.min(56f, Math.max(34f, wDp * 0.125f)));
        big.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL));
        big.setLineSpacing(0f, 0.95f);
        big.setLetterSpacing(-0.035f);
        big.setIncludeFontPadding(false);
        TextView sub = new TextView(this);
        sub.setText("Campus roster \u00B7 ICT361");
        sub.setTextColor(0xE6FFFFFF);
        sub.setTextSize(Math.min(17f, Math.max(14f, wDp * 0.04f)));
        sub.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = (int) (12 * d);
        textBox.addView(big, new LinearLayout.LayoutParams(-1, -2));
        textBox.addView(sub, slp);
        FrameLayout.LayoutParams xlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START);
        xlp.leftMargin = (int) (wPx * 0.08f);
        xlp.rightMargin = (int) (wPx * 0.08f);
        xlp.bottomMargin = (int) (hPx * 0.11f);
        root.addView(textBox, xlp);

        setContentView(root);

        // start states
        blue.setTranslationY(-hPx);
        red.setTranslationY(hPx);
        logoTile.setAlpha(0f);
        logoTile.setScaleX(0.9f);
        logoTile.setScaleY(0.9f);
        crops.setAlpha(0f);
        grads.setAlpha(0f);
        big.setAlpha(0f);
        big.setTranslationY(24 * d);
        sub.setAlpha(0f);

        PathInterpolator ease = new PathInterpolator(0.22f, 0.61f, 0.36f, 1f);

        blue.animate().translationY(0).setDuration(800).setInterpolator(ease).start();
        red.animate().translationY(0).setStartDelay(120).setDuration(800).setInterpolator(ease).start();
        android.view.animation.DecelerateInterpolator fadeIn = new android.view.animation.DecelerateInterpolator();
        crops.animate().alpha(1f).setStartDelay(1100).setDuration(1000).setInterpolator(fadeIn).start();
        grads.animate().alpha(1f).setStartDelay(1100).setDuration(1000).setInterpolator(fadeIn).start();
        logoTile.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(700).setDuration(700).setInterpolator(ease).start();
        big.animate().alpha(1f).translationY(0).setStartDelay(850).setDuration(800).setInterpolator(ease).start();
        sub.animate().alpha(1f).setStartDelay(1400).setDuration(800).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();

        // hold, then fade out (0.45s) and hand over to sign in at 3.1s like the prototype
        handler.postDelayed(() -> {
            ObjectAnimator out = ObjectAnimator.ofFloat(root, View.ALPHA, 1f, 0f);
            out.setDuration(450);
            out.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    goToLogin();
                }
            });
            out.start();
        }, 2600);

        // tap to skip
        root.setOnClickListener(v -> {
            handler.removeCallbacksAndMessages(null);
            goToLogin();
        });
    }

    private void goToLogin() {
        if (left) return;
        left = true;
        CrashLog.showIfAny(this, () -> {
            startActivity(new Intent(this, LoginActivity.class));
            overridePendingTransition(R.anim.page_fade_in, R.anim.hold);
            finish();
        });
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
