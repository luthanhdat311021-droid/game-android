'use strict';
/**
 * One combatant. step() is the movement/action state machine that the Android client
 * also runs (game/sim/Fighter.java) to predict its own character. Damage is NOT applied
 * here — CombatSystem does that on the server only.
 */
const { BTN, ACT, ANIM } = require('./constants');
const { integrate } = require('./PhysicsSystem');

class Fighter {
  constructor(opts, defs) {
    const c = defs.characters[opts.charId];
    if (!c) throw new Error('unknown character ' + opts.charId);
    const P = defs.physics;
    this.P = P;
    this.def = c;
    this.id = opts.id;
    this.slot = opts.slot;
    this.name = opts.name;
    this.charId = opts.charId;
    this.team = opts.team || 0;
    this.isBot = !!opts.isBot;
    this.userId = opts.userId || null;
    this.w = P.bodyW; this.h = P.bodyH;
    this.maxHp = c.hp; this.attack = c.attack; this.defense = c.defense;
    this.speed = c.speed; this.jumpV = c.jump;
    this.x = 0; this.y = 0; this.vx = 0; this.vy = 0; this.facing = 1;
    this.hp = c.hp; this.energy = 0;
    this.grounded = false; this.groundOneWay = false; this.onMover = -1;
    this.jumps = 2; this.airT = 0; this.dropT = 0;
    this.act = ACT.NONE; this.actT = 0; this.combo = 0; this.queued = 0;
    this.stunT = 0; this.dashCd = 0; this.specialCd = 0;
    this.alive = true; this.respawnT = 0; this.protectT = 0; this.eliminated = false;
    this.inst = 0; this.hits = [];
    // statistics
    this.kills = 0; this.deaths = 0; this.assists = 0; this.score = 0;
    this.dmgDealt = 0; this.dmgTaken = 0; this.placement = 0;
    this.dmgLog = new Map(); this.lastHitBy = null; this.lastHitTick = -9999;
    this.hazardT = 0; this.victory = false;
  }

  /** Move definition of the action in progress. */
  move() {
    if (this.act === ACT.BASIC) return this.def.basic[this.combo];
    if (this.act === ACT.HEAVY) return this.def.heavy;
    if (this.act === ACT.SPECIAL) return this.def.special;
    return null;
  }

  isAttacking() { return this.act === ACT.BASIC || this.act === ACT.HEAVY || this.act === ACT.SPECIAL; }

  /** Special dash (Shadow Slash) passes through attacks while travelling. */
  isIntangible() {
    if (this.act !== ACT.SPECIAL || this.def.special.type !== 'dash_slash') return false;
    const m = this.def.special;
    return this.actT > m.startup && this.actT <= m.startup + m.active;
  }

  startAction(act, combo, world) {
    this.act = act; this.actT = 0; this.combo = combo; this.queued = 0;
    this.inst = world ? world.nextInst() : this.inst + 1;
    this.hits = [];
    this.protectT = 0; // attacking ends spawn protection
  }

  friction(accel, dt) {
    const d = accel * dt;
    if (this.vx > d) this.vx -= d; else if (this.vx < -d) this.vx += d; else this.vx = 0;
  }

  /**
   * Advance one tick. input = { mx: -100..100, my: -1|0|1, b: buttons }.
   * world provides map, dt and nextInst(); predict=true on clients.
   */
  step(input, world) {
    const P = this.P, dt = world.dt, map = world.map;
    if (!this.alive) return;
    if (this.protectT > 0) this.protectT--;
    if (this.dashCd > 0) this.dashCd--;
    if (this.specialCd > 0) this.specialCd--;
    if (this.dropT > 0) this.dropT--;
    this.energy = Math.min(P.energyMax, this.energy + P.energyRegen * dt);

    const b = input.b | 0;
    let mx = (input.mx | 0) / 100;
    if (mx > 1) mx = 1; else if (mx < -1) mx = -1;
    const my = input.my | 0;

    // ride moving platforms
    if (this.grounded && this.onMover >= 0) {
      const m = map.movers[this.onMover];
      this.x += m.dx; this.y = m.y;
    }

    let gravity = true;
    if (this.act === ACT.HIT) {
      this.stunT--;
      if (this.stunT <= 0) { this.act = ACT.NONE; this.stunT = 0; }
      if (this.grounded) this.friction(P.stunFriction, dt);
    } else if (this.act === ACT.DASH) {
      this.actT++;
      this.vx = this.facing * P.dashSpeed; this.vy = 0; gravity = false;
      if (this.actT >= P.dashTicks) { this.act = ACT.NONE; this.vx = this.facing * this.speed * 0.6; }
    } else if (this.isAttacking()) {
      const mv = this.move();
      this.actT++;
      const activeEnd = mv.startup + mv.active;
      if (this.act === ACT.SPECIAL && mv.type === 'dash_slash') {
        if (this.actT > mv.startup && this.actT <= activeEnd) {
          this.vx = this.facing * mv.speed; this.vy = 0; gravity = false;
        } else if (this.actT === activeEnd + 1) {
          this.vx = this.facing * this.speed * 0.5;
        } else {
          this.friction(P.groundAccel, dt);
        }
      } else if (this.actT === mv.startup + 1 && mv.lunge && this.grounded) {
        this.vx = this.facing * mv.lunge;
      } else if (this.grounded) {
        this.friction(P.groundAccel * 0.5, dt);
      }
      if (this.act === ACT.BASIC && (b & BTN.ATTACK) && this.actT > 1) this.queued = 1;
      const total = activeEnd + mv.recovery;
      const canChain = this.act === ACT.BASIC && this.queued && this.combo < 2 && this.actT > activeEnd;
      if (canChain) {
        if (mx !== 0) this.facing = mx > 0 ? 1 : -1;
        this.startAction(ACT.BASIC, this.combo + 1, world);
      } else if (this.actT >= total) {
        this.act = ACT.NONE; this.combo = 0; this.queued = 0;
      }
    }

    if (this.act === ACT.NONE || this.act === ACT.BLOCK) {
      if (b & BTN.BLOCK) {
        this.act = ACT.BLOCK;
        this.friction(P.groundAccel, dt);
      } else {
        this.act = ACT.NONE;
        const target = mx * this.speed;
        const acc = (this.grounded ? P.groundAccel : P.airAccel) * dt;
        if (this.vx < target) this.vx = Math.min(target, this.vx + acc);
        else if (this.vx > target) this.vx = Math.max(target, this.vx - acc);
        if (mx !== 0) this.facing = mx > 0 ? 1 : -1;

        if (b & BTN.JUMP) {
          if (my > 0 && this.grounded && this.groundOneWay) {
            this.dropT = P.dropTicks; this.grounded = false; this.onMover = -1; this.vy = 120;
          } else if (this.jumps > 0) {
            this.vy = -(this.jumps === 2 ? this.jumpV : this.jumpV * P.doubleJumpFactor);
            this.jumps--; this.grounded = false; this.onMover = -1;
          }
        }
        const sp = this.def.special;
        if ((b & BTN.DASH) && this.dashCd === 0) {
          if (mx !== 0) this.facing = mx > 0 ? 1 : -1;
          this.act = ACT.DASH; this.actT = 0; this.dashCd = P.dashCooldown;
        } else if ((b & BTN.SPECIAL) && this.energy >= sp.cost && this.specialCd === 0) {
          this.energy -= sp.cost; this.specialCd = sp.cooldown;
          this.startAction(ACT.SPECIAL, 0, world);
        } else if (b & BTN.HEAVY) {
          this.startAction(ACT.HEAVY, 0, world);
        } else if (b & BTN.ATTACK) {
          this.startAction(ACT.BASIC, 0, world);
        }
      }
    }

    if (gravity) this.vy = Math.min(this.vy + P.gravity * dt, P.maxFall);
    integrate(this, map, dt);
    if (this.grounded) { this.jumps = 2; this.airT = 0; }
    else { this.airT++; if (this.airT > 4 && this.jumps === 2) this.jumps = 1; }
  }

  /** Animation code shown by clients. */
  anim() {
    if (!this.alive) return ANIM.DEAD;
    if (this.victory) return ANIM.VICTORY;
    switch (this.act) {
      case ACT.BASIC: return ANIM.ATTACK1 + this.combo;
      case ACT.HEAVY: return ANIM.HEAVY;
      case ACT.SPECIAL: return ANIM.SPECIAL;
      case ACT.DASH: return ANIM.DASH;
      case ACT.BLOCK: return ANIM.BLOCK;
      case ACT.HIT: return ANIM.HIT;
      default:
        if (this.protectT > this.P.protectTicks - 12) return ANIM.SPAWN;
        if (!this.grounded) return this.vy < 0 ? ANIM.JUMP : ANIM.FALL;
        return Math.abs(this.vx) > 20 ? ANIM.RUN : ANIM.IDLE;
    }
  }
}

module.exports = { Fighter };
