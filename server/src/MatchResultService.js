'use strict';
/**
 * Persists match results through the service-role-only SQL function
 * public.record_match_result (see supabase/migrations). The function is idempotent on
 * match id, so retries after a timeout can never double-count kills or rating.
 */
const log = require('./log');

class MatchResultService {
  constructor(supabase) {
    this.supabase = supabase;
    this.saved = new Map(); // matchId -> rating changes (in-memory record for tests / no-DB mode)
  }

  async matchStarted(match) {
    if (!this.supabase.enabled) return;
    try {
      await this.supabase.rpc('record_match_start', {
        p_match_id: match.id, p_room_code: match.room.code, p_mode: match.room.mode, p_map_id: match.room.mapId,
        p_started_at: match.startedAt.toISOString()
      });
    } catch (e) {
      log.warn('record_match_start failed', e.message);
    }
  }

  payload(match, result) {
    const byId = new Map(result.standings.map(s => [s.id, s]));
    const pick = id => (id != null && byId.get(id) ? byId.get(id).userId : null);
    return {
      match_id: match.id,
      room_code: match.room.code,
      mode: match.room.mode,
      map_id: match.room.mapId,
      started_at: match.startedAt.toISOString(),
      ended_at: new Date().toISOString(),
      duration_s: Math.round(result.ticks / match.world.tickRate),
      is_draw: result.draw,
      winner_team: result.winnerTeam || null,
      winner_player_id: result.draw ? null : pick(result.winners[0]),
      mvp_player_id: pick(result.mvp),
      top_kills_player_id: pick(result.topKills),
      most_damage_player_id: pick(result.mostDamage),
      summary: { reason: result.reason, teamScore: result.teamScore },
      participants: result.standings.map(s => ({
        slot: s.slot, player_id: s.userId, display_name: s.name, is_bot: !!s.isBot, team_id: s.team,
        character_id: s.charId, kills: s.kills, deaths: s.deaths, assists: s.assists,
        damage_dealt: s.damageDealt, damage_received: s.damageTaken, score: s.score,
        placement: s.placement, is_mvp: s.id === result.mvp, left_early: !!s.left
      }))
    };
  }

  /**
   * Quest progress + match Gold from the authoritative result only (never from the client).
   * event_id = matchId:userId makes every call idempotent, so a retry cannot double-count.
   */
  async trackQuests(match, result, body) {
    const humans = body.participants.filter(p => p.player_id && !p.left_early);
    if (!humans.length) return;
    const ids = humans.map(p => p.player_id).join(',');
    const ranks = await this.supabase.request('GET', `/rest/v1/rankings?select=player_id,rating&player_id=in.(${ids})`).catch(() => []);
    const rating = new Map((ranks || []).map(r => [r.player_id, r.rating]));
    for (const p of humans) {
      const won = result.draw ? false
        : result.winnerTeam ? p.team_id === result.winnerTeam : p.placement === 1;
      await this.supabase.rpc('record_player_match', { p_event: {
        event_id: match.id + ':' + p.player_id, player_id: p.player_id, mode: body.mode, won,
        kills: p.kills, assists: p.assists, damage: p.damage_dealt, rating: rating.get(p.player_id) || 0
      } });
    }
  }

  /** @returns rating changes { userId: delta } or null when no DB is configured. */
  async save(match, result) {
    const body = this.payload(match, result);
    if (!this.supabase.enabled) {
      this.saved.set(match.id, body);
      return null;
    }
    let delay = 500;
    for (let attempt = 1; ; attempt++) {
      try {
        const changes = await this.supabase.rpc('record_match_result', { p_result: body });
        log.info('match saved', match.id);
        this.saved.set(match.id, body);
        await this.trackQuests(match, result, body).catch(e => log.warn('quest tracking failed', match.id, e.message));
        return changes;
      } catch (e) {
        if (attempt >= 5 || (e.status && e.status < 500 && e.status !== 408 && e.status !== 429)) throw e;
        log.warn('save retry', attempt, e.message);
        await new Promise(r => setTimeout(r, delay));
        delay *= 2;
      }
    }
  }
}

module.exports = { MatchResultService };
