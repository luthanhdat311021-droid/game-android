package com.shadowduel.arena.game.sim;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Replays the trace recorded by server/tools/make-trace.js through the Java port.
 * Positions must match the server within 0.01 units every tick, or client prediction drifts.
 */
public class SimParityTest {

    static String read(String rel) throws Exception {
        File root = new File(System.getProperty("shared.dir", "../shared"));
        return new String(Files.readAllBytes(new File(root, rel).toPath()), StandardCharsets.UTF_8);
    }

    static Defs defs() throws Exception {
        return Defs.parse(read("characters.json"));
    }

    @Test
    public void javaMatchesServerTrace() throws Exception {
        String raw = new String(Files.readAllBytes(new File("src/test/resources/sim-trace.json").toPath()), StandardCharsets.UTF_8);
        JSONArray traces = new JSONArray(raw);
        Defs defs = defs();
        for (int k = 0; k < traces.length(); k++) {
            JSONObject tr = traces.getJSONObject(k);
            GameMap map = new GameMap(read("maps/" + tr.getString("mapId") + ".json"));
            World w = new World(defs, map, "training", -1);
            Fighter f = w.addFighter(0, "T", tr.getString("charId"), 0, false, 0, 1);
            f.x = tr.getDouble("x");
            f.y = tr.getDouble("y");
            f.energy = 100;
            JSONArray inputs = tr.getJSONArray("inputs"), frames = tr.getJSONArray("frames");
            for (int t = 0; t < inputs.length(); t++) {
                JSONArray in = inputs.getJSONArray(t);
                Map<Integer, Input> m = new HashMap<>();
                m.put(0, new Input(in.getInt(0), in.getInt(1), in.getInt(2)));
                w.step(m);
                JSONArray fr = frames.getJSONArray(t);
                String where = tr.getString("mapId") + " tick " + t;
                assertEquals(where + " x", fr.getDouble(0), f.x, 0.01);
                assertEquals(where + " y", fr.getDouble(1), f.y, 0.01);
                assertEquals(where + " vx", fr.getDouble(2), f.vx, 0.01);
                assertEquals(where + " vy", fr.getDouble(3), f.vy, 0.01);
                assertEquals(where + " act", fr.getInt(4), f.act);
                assertEquals(where + " actT", fr.getInt(5), f.actT);
                assertEquals(where + " facing", fr.getInt(6), f.facing);
                assertEquals(where + " grounded", fr.getInt(7) == 1, f.grounded);
            }
        }
    }

    @Test
    public void eightBotsPlayFullMatch() throws Exception {
        Defs defs = defs();
        GameMap map = new GameMap(read("maps/shadow_temple.json"));
        World w = new World(defs, map, "ffa", -1);
        for (int i = 0; i < 8; i++) w.addFighter(i, "B" + i, Const.CHARACTERS[i % 3], 0, true, 0.6f, 8);
        int ticks = 0;
        while (!w.step(null) && ticks < 20000) ticks++;
        assertNotNull(w.mode.result);
        int deaths = 0, kills = 0;
        for (Fighter f : w.fighters) {
            deaths += f.deaths;
            kills += f.kills;
            assertTrue(Double.isFinite(f.x));
        }
        assertTrue("fights happened", deaths > 5);
        assertTrue(kills <= deaths);
    }

    @Test
    public void tdmFriendlyFireOff() throws Exception {
        Defs defs = defs();
        World w = new World(defs, new GameMap(read("maps/shadow_temple.json")), "tdm", -1);
        Fighter a = w.addFighter(0, "A", "akira", 1, false, 0, 3);
        Fighter mate = w.addFighter(1, "M", "kaze", 1, false, 0, 3);
        Fighter enemy = w.addFighter(2, "E", "kaze", 2, false, 0, 3);
        double[] xs = {500, 650, 800};
        Fighter[] all = {a, mate, enemy};
        for (int i = 0; i < 3; i++) {
            all[i].x = xs[i];
            all[i].y = 26 * 32;
            all[i].protectT = 0;
            all[i].facing = 1;
            all[i].energy = 100;
        }
        Map<Integer, Input> in = new HashMap<>();
        in.put(0, new Input(0, 0, Const.BTN_SPECIAL));
        w.step(in);
        for (int i = 0; i < 25; i++) w.step(null);
        assertEquals(mate.maxHp, mate.hp);
        assertTrue(enemy.hp < enemy.maxHp);
    }
}
