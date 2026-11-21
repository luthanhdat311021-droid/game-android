package com.shadowduel.arena;

import android.app.Application;

import com.shadowduel.arena.data.Prefs;
import com.shadowduel.arena.net.GameClient;

/** Application singletons: settings and the game-server connection (shared by all screens). */
public final class App extends Application {
    private static App instance;
    private GameClient client;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // full-screen everywhere: hide the status bar and the navigation bar (swipe to peek)
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(android.app.Activity a) { immersive(a); }
            @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {
                a.getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(v -> immersive(a));
            }
            @Override public void onActivityStarted(android.app.Activity a) { }
            @Override public void onActivityPaused(android.app.Activity a) { }
            @Override public void onActivityStopped(android.app.Activity a) { }
            @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) { }
            @Override public void onActivityDestroyed(android.app.Activity a) { }
        });
    }

    static void immersive(android.app.Activity a) {
        android.view.Window w = a.getWindow();
        androidx.core.view.WindowInsetsControllerCompat c =
                androidx.core.view.WindowCompat.getInsetsController(w, w.getDecorView());
        c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        c.setSystemBarsBehavior(androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    public static App get() {
        return instance;
    }

    public synchronized GameClient client() {
        if (client == null) client = new GameClient(this);
        return client;
    }

    public Prefs prefs() {
        return new Prefs(this);
    }
}
