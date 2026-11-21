package com.shadowduel.arena.game.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.shadowduel.arena.game.sim.Const.*;

/**
 * Port of server/src/game/Fighter.js. step() is the movement/action state machine;
 * the client runs it for its own fighter to predict movement before the server confirms.
 */
public final class Fighter {
    public final Defs.Phys P;
    public final Defs.Char def;
    public final int id;
    public int slot;
    public String name;
    public final String charId;
    public int team;
    public final boolean isBot;
    public final double w, h;
    public final int maxHp, attack, defense;
    public final double speed, jumpV;

    public double x, y, vx, vy;
    public int facing = 1;
    public int hp;
    public double energy;
    public boolean grounded, groundOneWay;
    public int onMover = -1, jumps = 2, airT, dropT;
    public int act = ACT_NONE, actT, combo, queued, stunT, dashCd, specialCd;
    public boolean alive = true, eliminated, victory;
    public int respawnT, protectT;
    public int inst;
    public final List<Integer> hits = new ArrayList<>();

    // statistics
    public int kills, deaths, assists, placement;
    public int dmgDealt, dmgTaken;
    public final Map<Integer, Integer> dmgLog = new HashMap<>();
    public int lastHitBy = -1, lastHitTick = -9999, hazardT;

    /** Online: animation code received from the server (-1 = compute locally). */
    public int netAnim = -1;
    /** Online: false while the server has not sent this fighter yet. */
    public boolean known = true;
    /** Render-only: interpolated draw position, previous tick position, animation clock. */
    public double rx, ry, prevX, prevY;
    public int lastAnim = -1;
    public float animTime, hitFlash, displayHp = -1;
    public int netActT;
    /** Cosmetic palette [main, dark, accent, hair] or null. Never affects gameplay. */
    public int[] skin;
    /** Training/PvE-only item effects (offline sessions). Always neutral in online PvP. */
    public int boostT;
    public double damageTakenMul = 1;

    public Fighter(int id, String name, String charId, int team, boolean isBot, Defs defs) {
        this.P = defs.phys;
        this.def = defs.chars.get(charId);
        if (def == null) throw new IllegalArgumentException("unknown character " + charId);
        this.id = id;
        this.slot = id;
        this.name = name;
        this.charId = charId;
        this.team = team;
        this.isBot = isBot;
        this.w = P.bodyW;
        this.h = P.bodyH;
        this.maxHp = def.hp;
        this.attack = def.attack;
        this.defense = def.defense;
        this.speed = def.speed;
        this.jumpV = def.jump;
        this.hp = def.hp;
    }

    public Defs.Move move() {
        if (act == ACT_BASIC) return def.basic[combo];
        if (act == ACT_HEAVY) return def.heavy;
        if (act == ACT_SPECIAL) return def.special;
        return null;
    }

    public boolean isAttacking() {
        return act == ACT_BASIC || act == ACT_HEAVY || act == ACT_SPECIAL;
    }

    public boolean isIntangible() {
        if (act != ACT_SPECIAL || !"dash_slash".equals(def.special.type)) return false;
        Defs.Move m = def.special;
        return actT > m.startup && actT <= m.startup + m.active;
    }

    void startAction(int a, int c, World world) {
        act = a;
        actT = 0;
        combo = c;
        queued = 0;
        inst = world.nextInst();
        hits.clear();
        protectT = 0;
    }

    private void friction(double accel, double dt) {
        double d = accel * dt;
        if (vx > d) vx -= d;
        else if (vx < -d) vx += d;
        else vx = 0;
    }

    public void step(Input input, World world) {
        double dt = world.dt;
        GameMap map = world.map;
        if (!alive) return;
        if (protectT > 0) protectT--;
        if (dashCd > 0) dashCd--;
        if (specialCd > 0) specialCd--;
        if (dropT > 0) dropT--;
        if (boostT > 0) boostT--;
        energy = Math.min(P.energyMax, energy + P.energyRegen * dt);

        int b = input.b;
        double mx = input.mx / 100.0;
        if (mx > 1) mx = 1;
        else if (mx < -1) mx = -1;
        int my = input.my;

        if (grounded && onMover >= 0) {
            GameMap.Mover m = map.movers[onMover];
            x += m.dx;
            y = m.y;
        }

        boolean gravity = true;
        if (act == ACT_HIT) {
            stunT--;
            if (stunT <= 0) {
                act = ACT_NONE;
                stunT = 0;
            }
            if (grounded) friction(P.stunFriction, dt);
        } else if (act == ACT_DASH) {
            actT++;
            vx = facing * P.dashSpeed;
            vy = 0;
            gravity = false;
            if (actT >= P.dashTicks) {
                act = ACT_NONE;
                vx = facing * speed * 0.6;
            }
        } else if (isAttacking()) {
            Defs.Move mv = move();
            actT++;
            int activeEnd = mv.startup + mv.active;
            if (act == ACT_SPECIAL && "dash_slash".equals(mv.type)) {
                if (actT > mv.startup && actT <= activeEnd) {
                    vx = facing * mv.speed;
                    vy = 0;
                    gravity = false;
                } else if (actT == activeEnd + 1) {
                    vx = facing * speed * 0.5;
                } else {
                    friction(P.groundAccel, dt);
                }
            } else if (actT == mv.startup + 1 && mv.lunge != 0 && grounded) {
                vx = facing * mv.lunge;
            } else if (grounded) {
                friction(P.groundAccel * 0.5, dt);
            }
            if (act == ACT_BASIC && (b & BTN_ATTACK) != 0 && actT > 1) queued = 1;
            int total = activeEnd + mv.recovery;
            boolean canChain = act == ACT_BASIC && queued != 0 && combo < 2 && actT > activeEnd;
            if (canChain) {
                if (mx != 0) facing = mx > 0 ? 1 : -1;
                startAction(ACT_BASIC, combo + 1, world);
            } else if (actT >= total) {
                act = ACT_NONE;
                combo = 0;
                queued = 0;
            }
        }

        if (act == ACT_NONE || act == ACT_BLOCK) {
            if ((b & BTN_BLOCK) != 0) {
                act = ACT_BLOCK;
                friction(P.groundAccel, dt);
            } else {
                act = ACT_NONE;
                double target = mx * speed * (boostT > 0 ? 1.35 : 1);
                double acc = (grounded ? P.groundAccel : P.airAccel) * dt;
                if (vx < target) vx = Math.min(target, vx + acc);
                else if (vx > target) vx = Math.max(target, vx - acc);
                if (mx != 0) facing = mx > 0 ? 1 : -1;

                if ((b & BTN_JUMP) != 0) {
                    if (my > 0 && grounded && groundOneWay) {
                        dropT = P.dropTicks;
                        grounded = false;
                        onMover = -1;
                        vy = 120;
                    } else if (jumps > 0) {
                        vy = -(jumps == 2 ? jumpV : jumpV * P.doubleJumpFactor);
                        jumps--;
                        grounded = false;
                        onMover = -1;
                    }
                }
                Defs.Move sp = def.special;
                if ((b & BTN_DASH) != 0 && dashCd == 0) {
                    if (mx != 0) facing = mx > 0 ? 1 : -1;
                    act = ACT_DASH;
                    actT = 0;
                    dashCd = P.dashCooldown;
                } else if ((b & BTN_SPECIAL) != 0 && energy >= sp.cost && specialCd == 0) {
                    energy -= sp.cost;
                    specialCd = sp.cooldown;
                    startAction(ACT_SPECIAL, 0, world);
                } else if ((b & BTN_HEAVY) != 0) {
                    startAction(ACT_HEAVY, 0, world);
                } else if ((b & BTN_ATTACK) != 0) {
                    startAction(ACT_BASIC, 0, world);
                }
            }
        }

        if (gravity) vy = Math.min(vy + P.gravity * dt, P.maxFall);
        Physics.integrate(this, map, dt);
        if (grounded) {
            jumps = 2;
            airT = 0;
        } else {
            airT++;
            if (airT > 4 && jumps == 2) jumps = 1;
        }
    }

    public int anim() {
        if (netAnim >= 0) return netAnim;
        if (!alive) return ANIM_DEAD;
        if (victory) return ANIM_VICTORY;
        switch (act) {
            case ACT_BASIC: return ANIM_ATTACK1 + combo;
            case ACT_HEAVY: return ANIM_HEAVY;
            case ACT_SPECIAL: return ANIM_SPECIAL;
            case ACT_DASH: return ANIM_DASH;
            case ACT_BLOCK: return ANIM_BLOCK;
            case ACT_HIT: return ANIM_HIT;
            default:
                if (protectT > P.protectTicks - 12) return ANIM_SPAWN;
                if (!grounded) return vy < 0 ? ANIM_JUMP : ANIM_FALL;
                return Math.abs(vx) > 20 ? ANIM_RUN : ANIM_IDLE;
        }
    }

    /** Fraction 0..1 of the current action, used by the renderer to pose limbs. */
    public float actionProgress() {
        Defs.Move m = move();
        if (m != null) return Math.min(1f, actT / (float) Math.max(1, m.total()));
        if (act == ACT_DASH) return Math.min(1f, actT / (float) P.dashTicks);
        return 0f;
    }
}
