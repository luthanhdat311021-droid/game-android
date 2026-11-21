package com.shadowduel.arena.game.sim;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Tile map shared with the server (shared/maps/*.json). Mirrors server/src/game/MapLoader.js. */
public final class GameMap {

    public static final class Mover {
        public double baseX, baseY, ax, ay;
        public int period;
        public double x, y, w, dx, dy;
    }

    public static final class Prop {
        public String type;
        public double x, y;
    }

    public final String id, name, theme;
    public final int T, w, h;
    public final double pixelW, pixelH, killY;
    public final char[][] tiles;
    public final double[][] spawns;
    public final Mover[] movers;
    public final List<Prop> props = new ArrayList<>();

    public GameMap(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        id = o.getString("id");
        name = o.getString("name");
        theme = o.getString("theme");
        T = o.getInt("tileSize");
        w = o.getInt("width");
        h = o.getInt("height");
        pixelW = w * T;
        pixelH = h * T;
        killY = o.getDouble("killY");
        JSONArray rows = o.getJSONArray("rows");
        tiles = new char[h][];
        for (int r = 0; r < h; r++) tiles[r] = rows.getString(r).toCharArray();
        JSONArray sp = o.getJSONArray("spawns");
        spawns = new double[sp.length()][];
        for (int i = 0; i < sp.length(); i++) {
            JSONArray s = sp.getJSONArray(i);
            spawns[i] = new double[]{s.getDouble(0), s.getDouble(1)};
        }
        JSONArray mv = o.optJSONArray("movers");
        movers = new Mover[mv == null ? 0 : mv.length()];
        for (int i = 0; i < movers.length; i++) {
            JSONObject m = mv.getJSONObject(i);
            Mover d = new Mover();
            d.baseX = m.getDouble("x");
            d.baseY = m.getDouble("y");
            d.ax = m.getDouble("ax");
            d.ay = m.getDouble("ay");
            d.period = m.getInt("period");
            d.w = m.getInt("w") * T;
            movers[i] = d;
        }
        JSONArray pr = o.optJSONArray("props");
        if (pr != null) for (int i = 0; i < pr.length(); i++) {
            JSONObject p = pr.getJSONObject(i);
            Prop prop = new Prop();
            prop.type = p.getString("type");
            prop.x = p.getDouble("x");
            prop.y = p.getDouble("y");
            props.add(prop);
        }
        updateMovers(0);
    }

    public char tile(int col, int row) {
        if (col < 0 || col >= w) return '#';
        if (row < 0 || row >= h) return '.';
        return tiles[row][col];
    }

    public static boolean isSolid(char c) {
        return c == '#' || c == 'G' || c == 'N' || c == 'W';
    }

    public static boolean isOneWay(char c) {
        return c == '=' || c == 'R';
    }

    public static boolean isHazard(char c) {
        return c == 'L' || c == '^';
    }

    public boolean solid(int col, int row) {
        return isSolid(tile(col, row));
    }

    public boolean oneWay(int col, int row) {
        return isOneWay(tile(col, row));
    }

    public boolean hazard(int col, int row) {
        return isHazard(tile(col, row));
    }

    /** Moving platforms are a pure function of the tick (same formula as the server). */
    public void updateMovers(int tick) {
        for (Mover m : movers) {
            double s = Math.sin((2 * Math.PI * tick) / m.period);
            double nx = m.baseX + m.ax * s, ny = m.baseY + m.ay * s;
            m.dx = tick == 0 ? 0 : nx - m.x;
            m.dy = tick == 0 ? 0 : ny - m.y;
            m.x = nx;
            m.y = ny;
        }
    }
}
