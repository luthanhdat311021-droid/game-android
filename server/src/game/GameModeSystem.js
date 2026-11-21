'use strict';
/**
 * Rules for Free For All, Team Deathmatch, Survival Arena and Training.
 *
 * Tie rules (also shown in the app's "Rules" dialog):
 *  - FFA: most kills wins; ties broken by fewer deaths, then more damage dealt.
 *         Still tied → DRAW shared by all tied players.
 *  - TDM: first team to scoreLimit, else higher team score at time-up; equal → DRAW.
 *  - Survival: last fighter alive. At time-up the living fighters are ranked by HP;
 *         equal HP → DRAW. If the last fighters die on the same tick → DRAW between them.
 */
const { TEAM } = require('./constants');

class GameModeSystem {
  constructor(world, modeId, cfg, durationSec) {
    this.world = world;
    this.id = modeId;
    this.cfg = cfg;
    this.durationTicks = Math.round((durationSec != null ? durationSec : cfg.durationSec) * world.tickRate);
    this.teamScore = { [TEAM.RED]: 0, [TEAM.BLUE]: 0 };
    this.zone = null;
    this.deathOrder = [];
    this.result = null;
    this.zoneTick = 0;
  }

  timeLeft() {
    if (!this.durationTicks) return -1;
    return Math.max(0, this.durationTicks - this.world.tick);
  }

  onKill(victim, killer) {
    if (this.id === 'tdm' && killer && killer.team !== victim.team) this.teamScore[killer.team]++;
    if (this.id === 'survival') {
      victim.eliminated = true;
      victim.placement = this.world.fighters.filter(f => !f.eliminated).length + 1;
      this.deathOrder.push({ id: victim.id, tick: this.world.tick });
    }
  }

  scoreOf(f) {
    return this.id === 'survival' ? f.kills : f.kills;
  }

  update() {
    const w = this.world;
    if (this.result) return this.result;
    if (this.id === 'survival') this.updateZone();

    if (this.id === 'tdm') {
      for (const t of [TEAM.RED, TEAM.BLUE]) {
        if (this.teamScore[t] >= this.cfg.scoreLimit) return this.finish('score_limit');
      }
    }
    if (this.id === 'survival') {
      const alive = w.fighters.filter(f => !f.eliminated);
      if (alive.length <= 1) return this.finish('last_standing');
    }
    if (this.durationTicks && w.tick >= this.durationTicks) return this.finish('time_up');
    return null;
  }

  updateZone() {
    const w = this.world, c = this.cfg, tr = w.tickRate;
    const W = w.map.pixelW;
    const t = w.tick / tr;
    const k = Math.min(1, Math.max(0, (t - c.zoneStartSec) / (c.zoneEndSec - c.zoneStartSec)));
    const half = (W - (W - c.zoneMinWidth) * k) / 2;
    this.zone = [Math.round(W / 2 - half), Math.round(W / 2 + half)];
    if (k <= 0 || w.tick % 10 !== 0) return;
    for (const f of w.fighters) {
      if (!f.alive || (f.x >= this.zone[0] && f.x <= this.zone[1])) continue;
      const dmg = Math.max(1, Math.round(f.maxHp * c.zoneDamagePct / 3));
      w.environmentDamage(f, dmg, 'zone');
    }
  }

  /** Builds the final standings. Placements start at 1; ties share a placement. */
  finish(reason) {
    const w = this.world;
    for (const f of w.fighters) f.score = this.scoreOf(f);
    let winners = [], winnerTeam = 0, draw = false;
    let ordered;

    if (this.id === 'tdm') {
      const r = this.teamScore[TEAM.RED], b = this.teamScore[TEAM.BLUE];
      draw = r === b;
      winnerTeam = draw ? 0 : r > b ? TEAM.RED : TEAM.BLUE;
      ordered = [...w.fighters].sort((a, c) => cmpFfa(a, c));
      for (const f of w.fighters) f.placement = draw ? 1 : f.team === winnerTeam ? 1 : 2;
      winners = draw ? [] : w.fighters.filter(f => f.team === winnerTeam).map(f => f.id);
    } else if (this.id === 'survival') {
      const alive = w.fighters.filter(f => !f.eliminated);
      if (alive.length === 0) {
        // everybody died — the ones who died on the very last tick share 1st place
        const lastTick = this.deathOrder.length ? this.deathOrder[this.deathOrder.length - 1].tick : -1;
        const last = this.deathOrder.filter(d => d.tick === lastTick).map(d => d.id);
        for (const id of last) w.byId.get(id).placement = 1;
        winners = last; draw = last.length > 1;
      } else {
        alive.sort((a, c) => c.hp - a.hp);
        let place = 1;
        for (let i = 0; i < alive.length; i++) {
          if (i > 0 && alive[i].hp < alive[i - 1].hp) place = i + 1;
          alive[i].placement = place;
        }
        winners = alive.filter(f => f.placement === 1).map(f => f.id);
        draw = winners.length > 1;
      }
      ordered = [...w.fighters].sort((a, c) => a.placement - c.placement || cmpFfa(a, c));
    } else {
      ordered = [...w.fighters].sort(cmpFfa);
      let place = 1;
      for (let i = 0; i < ordered.length; i++) {
        if (i > 0 && cmpFfa(ordered[i - 1], ordered[i]) !== 0) place = i + 1;
        ordered[i].placement = place;
      }
      winners = ordered.filter(f => f.placement === 1).map(f => f.id);
      draw = winners.length > 1;
    }

    const mvp = [...w.fighters].sort((a, c) => mvpScore(c) - mvpScore(a))[0];
    const topKills = [...w.fighters].sort((a, c) => c.kills - a.kills)[0];
    const mostDmg = [...w.fighters].sort((a, c) => c.dmgDealt - a.dmgDealt)[0];
    for (const f of w.fighters) f.victory = winners.includes(f.id);

    this.result = {
      reason, draw, winners, winnerTeam,
      teamScore: { red: this.teamScore[TEAM.RED], blue: this.teamScore[TEAM.BLUE] },
      mvp: mvp ? mvp.id : null,
      topKills: topKills ? topKills.id : null,
      mostDamage: mostDmg ? mostDmg.id : null,
      standings: ordered.map(f => ({
        id: f.id, userId: f.userId, name: f.name, isBot: f.isBot, slot: f.slot, team: f.team, charId: f.charId,
        kills: f.kills, deaths: f.deaths, assists: f.assists, damageDealt: f.dmgDealt, damageTaken: f.dmgTaken,
        score: f.score, placement: f.placement
      })),
      ticks: w.tick
    };
    return this.result;
  }
}

function cmpFfa(a, b) {
  return (b.kills - a.kills) || (a.deaths - b.deaths) || (b.dmgDealt - a.dmgDealt);
}
function mvpScore(f) {
  return f.kills * 100 + f.assists * 50 + f.dmgDealt * 0.2 - f.deaths * 20;
}

module.exports = { GameModeSystem, cmpFfa, mvpScore };
