package com.shadowduel.arena.game.sim;

import java.util.Random;

import static com.shadowduel.arena.game.sim.Const.*;

/** Port of server/src/game/BotBrain.js (offline matches and Training). */
public final class Bot {
    private final Fighter f;
    private final Random rand;
    private final float level;
    private Fighter target;
    private int retarget, attackCd, blockT, jumpCd;
    /** Training dummy: stands still and never attacks. */
    public boolean passive;

    Bot(Fighter f, long seed, float level) {
        this.f = f;
        this.rand = new Random(seed);
        this.level = level;
    }

    private double r() {
        return rand.nextDouble();
    }

    private Fighter nearestEnemy(World w) {
        Fighter best = null;
        double bd = 1e9;
        for (Fighter o : w.fighters) {
            if (o == f || !o.alive || (w.teams && o.team == f.team)) continue;
            double d = Math.abs(o.x - f.x) + Math.abs(o.y - f.y) * 1.5 + (o.protectT > 0 ? 600 : 0);
            if (d < bd) {
                bd = d;
                best = o;
            }
        }
        return best;
    }

    Input decide(World w) {
        Input in = new Input();
        if (passive || !f.alive || f.act == ACT_HIT) return in;
        GameMap map = w.map;
        if (attackCd > 0) attackCd--;
        if (jumpCd > 0) jumpCd--;
        if (--retarget <= 0 || target == null || !target.alive || !w.byId.containsKey(target.id)) {
            target = nearestEnemy(w);
            retarget = 15 + (int) (r() * 15);
        }
        double goalX = map.pixelW / 2, goalY = f.y;
        if (target != null) {
            goalX = target.x;
            goalY = target.y;
        }
        double[] zone = w.mode.zone;
        if (zone != null && (f.x < zone[0] + 60 || f.x > zone[1] - 60)) goalX = (zone[0] + zone[1]) / 2;
        double dx = goalX - f.x, dy = goalY - f.y, adx = Math.abs(dx);
        double reach = 72;

        if (blockT > 0) {
            blockT--;
            in.b |= BTN_BLOCK;
            return in;
        }
        if (adx > reach - 10) in.mx = (int) Math.signum(dx) * 100;
        else if (target != null && Math.signum(dx) != f.facing && adx > 4) in.mx = (int) Math.signum(dx) * 40;

        int dir = in.mx != 0 ? (int) Math.signum(in.mx) : f.facing;
        int aheadCol = (int) Math.floor((f.x + dir * (f.w / 2 + 14)) / map.T);
        int midRow = (int) Math.floor((f.y - f.h / 2) / map.T);
        int lowRow = (int) Math.floor((f.y - 8) / map.T);
        boolean wall = map.solid(aheadCol, midRow) || map.solid(aheadCol, lowRow);
        boolean gap = f.grounded && in.mx != 0 && !Physics.groundBelow(map, f.x + dir * 44, f.y, 10);
        boolean hazardAhead = map.hazard(aheadCol, lowRow) || map.hazard(aheadCol, lowRow + 1);
        if (jumpCd == 0) {
            if (f.grounded && (wall || gap || hazardAhead || (dy < -80 && adx < 280))) {
                in.b |= BTN_JUMP;
                jumpCd = 8;
            } else if (!f.grounded && f.vy > 150 && f.jumps > 0 && !Physics.groundBelow(map, f.x, f.y, 12)) {
                in.b |= BTN_JUMP;
                jumpCd = 10;
            } else if (f.grounded && f.groundOneWay && dy > 90 && adx < 200 && r() < 0.1) {
                in.my = 1;
                in.b |= BTN_JUMP;
                jumpCd = 10;
            }
        }
        if (target == null) return in;
        Fighter t = target;
        double tdx = t.x - f.x, tdy = t.y - f.y, tadx = Math.abs(tdx);
        Defs.Move sp = f.def.special;
        if (tadx < 120 && Math.abs(tdy) < 60 && t.isAttacking() && r() < 0.18 * level) {
            blockT = 6 + (int) (r() * 6);
            in.b |= BTN_BLOCK;
            return in;
        }
        if (attackCd == 0 && f.act != ACT_DASH) {
            boolean ranged = ("projectile".equals(f.def.heavy.type) || "projectile".equals(sp.type))
                    && Math.abs(tdy) < 50 && tadx > 180 && tadx < 560;
            if (tadx <= reach + 12 && Math.abs(tdy) < 70) {
                int s = (int) Math.signum(tdx);
                in.mx = (s == 0 ? f.facing : s) * 10;
                double roll = r();
                if (f.energy >= sp.cost && f.specialCd == 0 && roll < 0.3) in.b |= BTN_SPECIAL;
                else if (roll < 0.45) in.b |= BTN_HEAVY;
                else in.b |= BTN_ATTACK;
                attackCd = 3 + (int) (r() * 12 * (1.2 - level));
            } else if (ranged && Math.signum(tdx) == f.facing && r() < 0.25) {
                if ("projectile".equals(sp.type) && f.energy >= sp.cost && f.specialCd == 0) in.b |= BTN_SPECIAL;
                else if ("projectile".equals(f.def.heavy.type)) in.b |= BTN_HEAVY;
                attackCd = 12 + (int) (r() * 12);
            } else if ("dash_slash".equals(sp.type) && tadx < 320 && Math.abs(tdy) < 40 && Math.signum(tdx) == f.facing
                    && f.energy >= sp.cost && f.specialCd == 0 && r() < 0.2) {
                in.b |= BTN_SPECIAL;
                attackCd = 15;
            } else if (tadx > 420 && f.dashCd == 0 && f.grounded && r() < 0.03) {
                in.b |= BTN_DASH;
            }
        }
        return in;
    }
}
