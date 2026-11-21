package com.shadowduel.arena.game.sim;

/** Port of server/src/game/SpawnSystem.js. */
public final class Spawner {
    private final World w;

    Spawner(World w) {
        this.w = w;
    }

    void initial(Fighter f, int index, int count) {
        double[][] sp = w.map.spawns;
        double step = sp.length / (double) Math.max(1, count);
        place(f, sp[(int) Math.floor(index * step) % sp.length]);
    }

    double[] respawnPoint(Fighter f) {
        double[][] sp = w.map.spawns;
        double[] best = sp[0];
        double bestScore = -1;
        for (int i = 0; i < sp.length; i++) {
            double[] s = sp[(i + w.tick) % sp.length];
            double nearest = 1e9;
            for (Fighter o : w.fighters) {
                if (o == f || !o.alive) continue;
                if (w.teams && o.team == f.team) continue;
                nearest = Math.min(nearest, Math.hypot(o.x - s[0], o.y - s[1]));
            }
            if (nearest > bestScore) {
                bestScore = nearest;
                best = s;
            }
        }
        return best;
    }

    void place(Fighter f, double[] s) {
        f.x = s[0];
        f.y = s[1];
        f.vx = 0;
        f.vy = 0;
        f.grounded = false;
        f.onMover = -1;
        f.dropT = 0;
        f.facing = s[0] < w.map.pixelW / 2 ? 1 : -1;
    }

    public void respawn(Fighter f) {
        place(f, respawnPoint(f));
        f.alive = true;
        f.hp = f.maxHp;
        f.energy = Math.max(f.energy, 25);
        f.act = Const.ACT_NONE;
        f.actT = 0;
        f.combo = 0;
        f.stunT = 0;
        f.queued = 0;
        f.protectT = w.P.protectTicks;
        f.respawnT = 0;
        f.dmgLog.clear();
        f.lastHitBy = -1;
        f.lastHitTick = -9999;
        World.Event e = new World.Event();
        e.type = World.Event.SPAWN;
        e.v = f.id;
        e.x = f.x;
        e.y = f.y;
        w.emit(e);
    }
}
