package com.shadowduel.arena.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.AnimationUtils;

import androidx.appcompat.app.AppCompatActivity;

import com.shadowduel.arena.App;
import com.shadowduel.arena.R;
import com.shadowduel.arena.data.Supa;
import com.shadowduel.arena.game.GameAssets;

/** Animated logo while shared game data loads, then routes to Login or Home. */
public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_splash);
        findViewById(R.id.splashLogo).startAnimation(AnimationUtils.loadAnimation(this, R.anim.logo_in));
        new Thread(() -> GameAssets.defs(this)).start();
        new Handler(Looper.getMainLooper()).postDelayed(this::route, 1600);
    }

    private void route() {
        String mode = App.get().prefs().loginMode();
        boolean loggedIn = ("supabase".equals(mode) && Supa.signedIn(this)) || "guest".equals(mode) || "offline".equals(mode);
        startActivity(new Intent(this, loggedIn ? MainActivity.class : LoginActivity.class));
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        finish();
    }
}
