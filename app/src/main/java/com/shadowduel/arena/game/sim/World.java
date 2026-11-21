package com.shadowduel.arena.game.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static com.shadowduel.arena.game.sim.Const.*;

/**
 * Port of server/src/game/GameWorld.js. Used for offline matches and Training (full
 * simulation with bots) and, with simulateCombat = false, as the prediction world online.
 */
public final class World {

    public static final class Projectile {
        public int id, owner, team, life;
        public String kind;
        public Defs.Move move;
        public double x, y, vx, w, h;
        public boolean pierce, dead;
        public final List<Integer> hits = new ArrayList<>();
    }

    public static final class Zone {
        public int id, owner, team, life, interval, maxLife;
        public String kind;
        public Defs.Move move;
        public double x, y, w, h;
        public final Map<Integer, Integer> last = new HashMap<>();
    }

    public static final class Event {
        public static final int HIT = 1, KILL = 2, SPAWN = 3, LEFT = 4;
        public int type, a = -1, v = -1, d, tick;
        public double x, y;
        public boolean blocked;
        public String kind = "";
        public int[] assists = new int[0];
    }

    public final Defs defs;
    public final Defs.Phys P;
    public final GameMap map;
    public final Defs.Mode modeDef;
    public final int tickRate;
    public final double dt;
    public final boolean teams, respawns;
    public boolean friendlyFire;
    public int tick;
    private int inst;
    public final List<Fighter> fighters = new ArrayList<>();
    public final Map<Integer, Fighter> byId = new HashMap<>();
    public final Map<Integer, Bot> bots = new HashMap<>();
    public final List<Projectile> projectiles = new ArrayList<>();
    public final List<Zone> zones = new ArrayList<>();
    public final List<Event> events = new ArrayList<>();
    public final Combat combat;
    public final Spawner spawner;
    public final Mode mode;
    public long seed = 1;

    public World(Defs defs, GameMap map, String modeId, int durationSec) {
        this.defs = defs;
        this.P = defs.phys;
        this.map = map;
        this.modeDef = defs.modes.get(modeId);
        if (modeDef == null) throw new IllegalArgumentException("mode " + modeId);
        this.tickRate = P.tickRate;
        this.dt = 1.0 / tickRate;
        this.teams = modeDef.teams;
        this.respawns = modeDef.respawn;
        this.friendlyFire = !teams;
        this.combat = new Combat(this);
        this.spawner = new Spawner(this);
        this.mode = new Mode(this, modeDef, durationSec);
    }

    public Fighter addFighter(int id, String name, String charId, int team, boolean bot, float botLevel, int count) {
        Fighter f = new Fighter(id, name, charId, team, bot, defs);
        int index = fighters.size();
        fighters.add(f);
        byId.put(id, f);
        if (bot) bots.put(id, new Bot(f, seed * 31 + index * 7919L + 1, botLevel));
        spawner.initial(f, index, count);
        f.protectT = P.protectTicks;
        f.energy = 25;
        return f;
    }

    public int nextInst() {
        return ++inst;
    }

    public void emit(Event e) {
        e.tick = tick;
        events.add(e);
    }

    /** One fixed tick. inputs: by fighter id. Returns true when the match just ended. */
    public boolean step(Map<Integer, Input> inputs) {
        events.clear();
        if (mode.result != null) return false;
        tick++;
        map.updateMovers(tick);
        for (Fighter f : fighters) {
            if (!f.alive) {
                if (respawns && f.respawnT > 0 && --f.respawnT == 0) spawner.respawn(f);
                continue;
            }
            Bot bot = bots.get(f.id);
            Input in = bot != null ? bot.decide(this) : (inputs != null && inputs.get(f.id) != null ? inputs.get(f.id) : Input.NEUTRAL);
            f.step(in, this);
        }
        combat.update();
        environment();
        return mode.update() != null;
    }

    private void environment() {
        for (Fighter f : fighters) {
            if (!f.alive) continue;
            if (f.y > map.killY) {
                kill(f, creditFor(f), "fall");
                continue;
            }
            if (Physics.inHazard(f, map)) {
                if (f.hazardT <= 0) {
                    f.hazardT = P.hazardIntervalTicks;
                    environmentDamage(f, (int) Math.round(f.maxHp * P.hazardDamagePct), "hazard");
                    if (f.alive && f.act != ACT_HIT) {
                        f.vy = Math.min(f.vy, -420);
                        f.grounded = false;
                        f.onMover = -1;
                    }
                } else f.hazardT--;
            } else if (f.hazardT > 0) f.hazardT--;
        }
    }

    int creditFor(Fighter f) {
        return f.lastHitBy >= 0 && tick - f.lastHitTick <= P.fallCreditTicks ? f.lastHitBy : -1;
    }

    void environmentDamage(Fighter f, int dmg, String cause) {
        if (f.protectT > 0 && !"zone".equals(cause)) return;
        dmg = Math.min(dmg, f.hp);
        f.hp -= dmg;
        f.dmgTaken += dmg;
        Event e = new Event();
        e.type = Event.HIT;
        e.v = f.id;
        e.d = dmg;
        e.x = f.x;
        e.y = f.y - f.h / 2;
        e.kind = cause;
        emit(e);
        if (f.hp <= 0) kill(f, creditFor(f), cause);
    }

    public void kill(Fighter victim, int killerId, String cause) {
        if (!victim.alive) return;
        victim.alive = false;
        victim.hp = 0;
        victim.act = ACT_DEAD;
        victim.vx = 0;
        victim.vy = 0;
        victim.deaths++;
        victim.respawnT = respawns ? P.respawnTicks : 0;
        Fighter killer = killerId >= 0 && killerId != victim.id ? byId.get(killerId) : null;
        if (killer != null) killer.kills++;
        List<Integer> as = new ArrayList<>();
        for (Map.Entry<Integer, Integer> en : victim.dmgLog.entrySet()) {
            int id = en.getKey();
            if (id == killerId || id == victim.id || tick - en.getValue() > P.assistWindowTicks) continue;
            Fighter a = byId.get(id);
            if (a != null && !(teams && a.team == victim.team)) {
                a.assists++;
                as.add(id);
            }
        }
        victim.dmgLog.clear();
        mode.onKill(victim, killer);
        Event e = new Event();
        e.type = Event.KILL;
        e.a = killer != null ? killer.id : -1;
        e.v = victim.id;
        e.kind = cause;
        e.x = victim.x;
        e.y = Math.min(victim.y, map.pixelH);
        e.assists = new int[as.size()];
        for (int i = 0; i < as.size(); i++) e.assists[i] = as.get(i);
        emit(e);
    }

    void cleanup() {
        for (Iterator<Projectile> it = projectiles.iterator(); it.hasNext(); ) if (it.next().dead) it.remove();
        for (Iterator<Zone> it = zones.iterator(); it.hasNext(); ) if (it.next().life <= 0) it.remove();
    }
}
