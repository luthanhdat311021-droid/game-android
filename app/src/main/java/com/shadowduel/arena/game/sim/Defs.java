package com.shadowduel.arena.game.sim;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** Physics constants, characters and modes parsed from shared/characters.json. */
public final class Defs {

    public static final class Phys {
        public int tickRate;
        public double gravity, maxFall, bodyW, bodyH, groundAccel, airAccel, stunFriction, doubleJumpFactor;
        public int dashTicks, dashCooldown, dropTicks;
        public double dashSpeed, energyMax, energyRegen, energyPerDamageDealt, energyPerDamageTaken;
        public double blockDamage, blockKnockback, blockStun, hazardDamagePct;
        public int respawnTicks, protectTicks, assistWindowTicks, fallCreditTicks, hazardIntervalTicks;
    }

    public static final class Proj {
        public String kind;
        public double speed, w, h, offY;
        public int life;
        public boolean pierce;
    }

    public static final class ZoneDef {
        public String kind;
        public double offX, w, h;
        public int life, interval;
    }

    public static final class Move {
        public String type = "melee", name = "";
        public int startup, active, recovery, dmg, stun, cost, cooldown;
        public double kbx, kby, lunge, speed;
        public double[] box;
        public Proj proj;
        public ZoneDef zone;

        public int total() {
            return startup + active + recovery;
        }
    }

    public static final class Char {
        public String id, name, title, description;
        public int hp, attack, defense;
        public double speed, jump;
        public Move[] basic = new Move[3];
        public Move heavy, special;
    }

    public static final class Mode {
        public String id, name;
        public int durationSec, scoreLimit, zoneStartSec, zoneEndSec;
        public boolean respawn, teams;
        public double zoneMinWidth, zoneDamagePct;
    }

    public final Phys phys = new Phys();
    public final Map<String, Char> chars = new HashMap<>();
    public final Map<String, Mode> modes = new HashMap<>();

    public static Defs parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        Defs d = new Defs();
        JSONObject p = root.getJSONObject("physics");
        Phys ph = d.phys;
        ph.tickRate = p.getInt("tickRate");
        ph.gravity = p.getDouble("gravity");
        ph.maxFall = p.getDouble("maxFall");
        ph.bodyW = p.getDouble("bodyW");
        ph.bodyH = p.getDouble("bodyH");
        ph.groundAccel = p.getDouble("groundAccel");
        ph.airAccel = p.getDouble("airAccel");
        ph.stunFriction = p.getDouble("stunFriction");
        ph.doubleJumpFactor = p.getDouble("doubleJumpFactor");
        ph.dashTicks = p.getInt("dashTicks");
        ph.dashSpeed = p.getDouble("dashSpeed");
        ph.dashCooldown = p.getInt("dashCooldown");
        ph.dropTicks = p.getInt("dropTicks");
        ph.energyMax = p.getDouble("energyMax");
        ph.energyRegen = p.getDouble("energyRegen");
        ph.energyPerDamageDealt = p.getDouble("energyPerDamageDealt");
        ph.energyPerDamageTaken = p.getDouble("energyPerDamageTaken");
        ph.blockDamage = p.getDouble("blockDamage");
        ph.blockKnockback = p.getDouble("blockKnockback");
        ph.blockStun = p.getDouble("blockStun");
        ph.respawnTicks = p.getInt("respawnTicks");
        ph.protectTicks = p.getInt("protectTicks");
        ph.assistWindowTicks = p.getInt("assistWindowTicks");
        ph.fallCreditTicks = p.getInt("fallCreditTicks");
        ph.hazardDamagePct = p.getDouble("hazardDamagePct");
        ph.hazardIntervalTicks = p.getInt("hazardIntervalTicks");

        JSONObject cs = root.getJSONObject("characters");
        for (String id : Const.CHARACTERS) {
            JSONObject c = cs.getJSONObject(id);
            Char ch = new Char();
            ch.id = id;
            ch.name = c.getString("name");
            ch.title = c.getString("title");
            ch.description = c.optString("description");
            ch.hp = c.getInt("hp");
            ch.attack = c.getInt("attack");
            ch.defense = c.getInt("defense");
            ch.speed = c.getDouble("speed");
            ch.jump = c.getDouble("jump");
            JSONArray b = c.getJSONArray("basic");
            for (int i = 0; i < 3; i++) ch.basic[i] = move(b.getJSONObject(i));
            ch.heavy = move(c.getJSONObject("heavy"));
            ch.special = move(c.getJSONObject("special"));
            d.chars.put(id, ch);
        }
        JSONObject ms = root.getJSONObject("modes");
        for (java.util.Iterator<String> it = ms.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject m = ms.getJSONObject(id);
            Mode mo = new Mode();
            mo.id = id;
            mo.name = m.getString("name");
            mo.durationSec = m.optInt("durationSec", 0);
            mo.respawn = m.optBoolean("respawn", true);
            mo.teams = m.optBoolean("teams", false);
            mo.scoreLimit = m.optInt("scoreLimit", 0);
            mo.zoneStartSec = m.optInt("zoneStartSec", 0);
            mo.zoneEndSec = m.optInt("zoneEndSec", 1);
            mo.zoneMinWidth = m.optDouble("zoneMinWidth", 0);
            mo.zoneDamagePct = m.optDouble("zoneDamagePct", 0);
            d.modes.put(id, mo);
        }
        return d;
    }

    private static Move move(JSONObject o) throws Exception {
        Move m = new Move();
        m.type = o.optString("type", "melee");
        m.name = o.optString("name", "");
        m.startup = o.getInt("startup");
        m.active = o.getInt("active");
        m.recovery = o.getInt("recovery");
        m.dmg = o.getInt("dmg");
        m.stun = o.getInt("stun");
        JSONArray kb = o.getJSONArray("kb");
        m.kbx = kb.getDouble(0);
        m.kby = kb.getDouble(1);
        m.lunge = o.optDouble("lunge", 0);
        m.speed = o.optDouble("speed", 0);
        m.cost = o.optInt("cost", 0);
        m.cooldown = o.optInt("cooldown", 0);
        JSONArray box = o.optJSONArray("box");
        if (box != null) m.box = new double[]{box.getDouble(0), box.getDouble(1), box.getDouble(2), box.getDouble(3)};
        JSONObject p = o.optJSONObject("proj");
        if (p != null) {
            m.proj = new Proj();
            m.proj.kind = p.getString("kind");
            m.proj.speed = p.getDouble("speed");
            m.proj.w = p.getDouble("w");
            m.proj.h = p.getDouble("h");
            m.proj.life = p.getInt("life");
            m.proj.pierce = p.getBoolean("pierce");
            m.proj.offY = p.getDouble("offY");
        }
        JSONObject z = o.optJSONObject("zone");
        if (z != null) {
            m.zone = new ZoneDef();
            m.zone.kind = z.getString("kind");
            m.zone.offX = z.getDouble("offX");
            m.zone.w = z.getDouble("w");
            m.zone.h = z.getDouble("h");
            m.zone.life = z.getInt("life");
            m.zone.interval = z.getInt("interval");
        }
        return m;
    }
}
