package com.shadowduel.arena.game;

import android.content.Context;

import com.shadowduel.arena.game.sim.Defs;
import com.shadowduel.arena.game.sim.GameMap;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Loads the shared JSON data (same files the server uses) from APK assets. */
public final class GameAssets {
    private static Defs defs;

    private GameAssets() {
    }

    public static synchronized Defs defs(Context c) {
        if (defs == null) {
            try {
                defs = Defs.parse(read(c, "characters.json"));
            } catch (Exception e) {
                throw new IllegalStateException("characters.json", e);
            }
        }
        return defs;
    }

    /** A fresh map instance (movers carry per-match state). */
    public static GameMap map(Context c, String id) {
        try {
            return new GameMap(read(c, "maps/" + id + ".json"));
        } catch (Exception e) {
            throw new IllegalStateException("map " + id, e);
        }
    }

    static String read(Context c, String path) throws Exception {
        try (InputStream in = c.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static String mapName(String id) {
        switch (id) {
            case "flame_kingdom": return "Flame Kingdom";
            case "cyber_city": return "Cyber City";
            default: return "Shadow Temple";
        }
    }

    public static String modeName(String id) {
        switch (id) {
            case "tdm": return "Team Deathmatch";
            case "survival": return "Survival Arena";
            case "training": return "Training";
            default: return "Free For All";
        }
    }
}
