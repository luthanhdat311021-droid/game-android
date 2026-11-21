package com.shadowduel.arena.game.sim;

import static com.shadowduel.arena.game.sim.Const.*;

/** Port of server/src/game/CombatSystem.js: multi-target hit detection, projectiles, zones. */
public final class Combat {
    private static final double PROJ_STEP = 16;
    private final World w;

    Combat(World w) {
        this.w = w;
    }

    boolean canHit(int attackerId, Fighter t, int ownerTeam) {
        if (!t.alive || t.protectT > 0 || t.isIntangible()) return false;
        if (t.id == attackerId) return false;
        return w.friendlyFire || ownerTeam == 0 || t.team != ownerTeam;
    }

    void update() {
        for (Fighter f : w.fighters) {
            if (!f.alive || !f.isAttacking()) continue;
            Defs.Move mv = f.move();
            boolean first = f.actT == mv.startup + 1;
            boolean active = f.actT > mv.startup && f.actT <= mv.startup + mv.active;
            String kind = (f.act == ACT_SPECIAL ? "special" : f.act == ACT_HEAVY ? "heavy" : "basic") + ":" + f.charId;
            if (("melee".equals(mv.type) || "dash_slash".equals(mv.type)) && active && mv.box != null) {
                double bx = f.facing > 0 ? f.x + mv.box[0] : f.x - mv.box[0] - mv.box[2];
                double by = f.y + mv.box[1];
                for (Fighter t : w.fighters) {
                    if (f.hits.contains(t.id) || !canHit(f.id, t, f.team)) continue;
                    if (Physics.overlap(bx, by, mv.box[2], mv.box[3], t.x - t.w / 2, t.y - t.h, t.w, t.h)) {
                        f.hits.add(t.id);
                        int dir = t.x == f.x ? f.facing : (int) Math.signum(t.x - f.x);
                        applyHit(f.id, t, mv, dir, kind);
                    }
                }
            }
            if (first && "projectile".equals(mv.type)) spawnProjectile(f, mv);
            if (first && "zone".equals(mv.type)) spawnZone(f, mv);
        }
        updateProjectiles();
        updateZones();
        w.cleanup();
    }

    private void spawnProjectile(Fighter f, Defs.Move mv) {
        Defs.Proj p = mv.proj;
        World.Projectile pr = new World.Projectile();
        pr.id = w.nextInst();
        pr.owner = f.id;
        pr.team = f.team;
        pr.kind = p.kind;
        pr.move = mv;
        pr.x = f.x + f.facing * (f.w / 2 + p.w / 2);
        pr.y = f.y + p.offY;
        pr.vx = f.facing * p.speed;
        pr.w = p.w;
        pr.h = p.h;
        pr.life = p.life;
        pr.pierce = p.pierce;
        w.projectiles.add(pr);
    }

    private void spawnZone(Fighter f, Defs.Move mv) {
        Defs.ZoneDef z = mv.zone;
        double cx = f.x + f.facing * z.offX;
        World.Zone zn = new World.Zone();
        zn.id = w.nextInst();
        zn.owner = f.id;
        zn.team = f.team;
        zn.kind = z.kind;
        zn.move = mv;
        zn.x = cx - z.w / 2;
        zn.y = f.y - z.h;
        zn.w = z.w;
        zn.h = z.h;
        zn.life = z.life;
        zn.maxLife = z.life;
        zn.interval = z.interval;
        w.zones.add(zn);
    }

    private void updateProjectiles() {
        for (World.Projectile p : w.projectiles) {
            double dist = Math.abs(p.vx * w.dt);
            int steps = Math.max(1, (int) Math.ceil(dist / PROJ_STEP));
            for (int s = 0; s < steps && !p.dead; s++) {
                p.x += p.vx * w.dt / steps;
                int col = (int) Math.floor(p.x / w.map.T), row = (int) Math.floor(p.y / w.map.T);
                if (w.map.solid(col, row) || p.x < 0 || p.x > w.map.pixelW) {
                    p.dead = true;
                    break;
                }
                for (Fighter t : w.fighters) {
                    if (p.hits.contains(t.id) || !canHit(p.owner, t, p.team)) continue;
                    if (Physics.overlap(p.x - p.w / 2, p.y - p.h / 2, p.w, p.h, t.x - t.w / 2, t.y - t.h, t.w, t.h)) {
                        p.hits.add(t.id);
                        applyHit(p.owner, t, p.move, (int) Math.signum(p.vx), p.kind);
                        if (!p.pierce) {
                            p.dead = true;
                            break;
                        }
                    }
                }
            }
            if (--p.life <= 0) p.dead = true;
        }
    }

    private void updateZones() {
        for (World.Zone z : w.zones) {
            for (Fighter t : w.fighters) {
                if (!canHit(z.owner, t, z.team)) continue;
                Integer last = z.last.get(t.id);
                if (last != null && w.tick - last < z.interval) continue;
                if (Physics.overlap(z.x, z.y, z.w, z.h, t.x - t.w / 2, t.y - t.h, t.w, t.h)) {
                    z.last.put(t.id, w.tick);
                    int dir = (int) Math.signum(t.x - (z.x + z.w / 2));
                    applyHit(z.owner, t, z.move, dir == 0 ? 1 : dir, z.kind);
                }
            }
            z.life--;
        }
    }

    void applyHit(int attackerId, Fighter t, Defs.Move mv, int dir, String kind) {
        Defs.Phys P = w.P;
        Fighter a = w.byId.get(attackerId);
        int atk = a != null ? a.attack : 100;
        int dmg = (int) Math.round((mv.dmg * atk / 100.0) * (100.0 / (100 + t.defense)));
        if (t.damageTakenMul != 1) dmg = (int) Math.round(dmg * t.damageTakenMul); // training-only equipment
        boolean blocked = t.act == ACT_BLOCK;
        if (blocked) dmg = Math.max(1, (int) Math.round(dmg * P.blockDamage));
        dmg = Math.min(dmg, t.hp);
        t.hp -= dmg;
        t.dmgTaken += dmg;
        t.energy = Math.min(P.energyMax, t.energy + dmg * P.energyPerDamageTaken);
        if (a != null) {
            a.dmgDealt += dmg;
            a.energy = Math.min(P.energyMax, a.energy + dmg * P.energyPerDamageDealt);
        }
        t.dmgLog.put(attackerId, w.tick);
        t.lastHitBy = attackerId;
        t.lastHitTick = w.tick;
        World.Event e = new World.Event();
        e.type = World.Event.HIT;
        e.a = attackerId;
        e.v = t.id;
        e.d = dmg;
        e.x = t.x;
        e.y = t.y - t.h / 2;
        e.blocked = blocked;
        e.kind = kind;
        w.emit(e);
        if (t.hp <= 0) {
            w.kill(t, attackerId, kind);
            return;
        }
        if (blocked) {
            t.vx = mv.kbx * dir * P.blockKnockback;
        } else {
            t.act = ACT_HIT;
            t.stunT = mv.stun;
            t.combo = 0;
            t.queued = 0;
            t.vx = mv.kbx * dir;
            t.vy = mv.kby;
            if (mv.kby < 0) {
                t.grounded = false;
                t.onMover = -1;
            }
            t.facing = -dir;
        }
    }
}
