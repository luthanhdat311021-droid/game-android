package com.shadowduel.arena.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.shadowduel.arena.BuildConfig;

/** User settings stored on the device. */
public final class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public String serverUrl() {
        String s = sp.getString("server_url", "");
        // 10.0.2.2 only exists inside the Android emulator; on a real phone fall back to the build default
        boolean emulator = android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish");
        if (s.isEmpty() || (!emulator && s.contains("10.0.2.2"))) return BuildConfig.GAME_SERVER_URL;
        return s;
    }

    public void setServerUrl(String url) {
        sp.edit().putString("server_url", url.trim()).apply();
    }

    /** 0 low, 1 medium, 2 high */
    public int quality() {
        return sp.getInt("quality", 2);
    }

    public void setQuality(int q) {
        sp.edit().putInt("quality", q).apply();
    }

    public boolean vibration() {
        return sp.getBoolean("vibration", true);
    }

    public void setVibration(boolean v) {
        sp.edit().putBoolean("vibration", v).apply();
    }

    public boolean showFps() {
        return sp.getBoolean("fps", false);
    }

    public void setShowFps(boolean v) {
        sp.edit().putBoolean("fps", v).apply();
    }

    public int controlsOpacity() {
        return sp.getInt("controls_opacity", 85);
    }

    public void setControlsOpacity(int v) {
        sp.edit().putInt("controls_opacity", v).apply();
    }

    public String character() {
        return sp.getString("character", "kaze");
    }

    public void setCharacter(String id) {
        sp.edit().putString("character", id).apply();
    }

    public String guestName() {
        return sp.getString("guest_name", "Guest");
    }

    public void setGuestName(String n) {
        sp.edit().putString("guest_name", n).apply();
    }

    /** "supabase", "guest" or "" (not chosen yet). */
    public String loginMode() {
        return sp.getString("login_mode", "");
    }

    public void setLoginMode(String m) {
        sp.edit().putString("login_mode", m).apply();
    }

    public int botLevel() {
        return sp.getInt("bot_level", 60);
    }

    public void setBotLevel(int v) {
        sp.edit().putInt("bot_level", v).apply();
    }
}
