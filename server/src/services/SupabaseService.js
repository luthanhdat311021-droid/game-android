'use strict';
/**
 * Minimal Supabase REST client for the game server (Node 20+ fetch, no SDK).
 *  - verifyToken(): validates a user's Supabase access token by asking Supabase Auth
 *    (works for both legacy HS256 and new asymmetric JWT signing keys).
 *  - Room mirroring for the lobby (rooms / room_players) so clients get Realtime updates.
 *  - rpc(): calls SECURITY DEFINER functions that only the service role may execute.
 */
const log = require('../log');

class SupabaseService {
  constructor(cfg) {
    this.url = cfg.supabaseUrl;
    this.service = cfg.supabaseServiceKey;
    this.anon = cfg.supabaseAnonKey || cfg.supabaseServiceKey;
    this.enabled = cfg.supabaseEnabled;
    this.tokenCache = new Map(); // token -> { user, until }
  }

  headers(extra) {
    return Object.assign({
      apikey: this.service,
      Authorization: 'Bearer ' + this.service,
      'Content-Type': 'application/json'
    }, extra || {});
  }

  async request(method, path, body, extraHeaders) {
    const res = await fetch(this.url + path, {
      method,
      headers: this.headers(extraHeaders),
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(8000)
    });
    const text = await res.text();
    if (!res.ok) {
      const err = new Error(`Supabase ${method} ${path.split('?')[0]} -> ${res.status}: ${text.slice(0, 300)}`);
      err.status = res.status;
      throw err;
    }
    return text ? JSON.parse(text) : null;
  }

  /** @returns {Promise<{id:string,email:string,username:string}|null>} */
  async verifyToken(token) {
    if (!this.enabled || typeof token !== 'string' || token.length < 20 || token.length > 4096) return null;
    const cached = this.tokenCache.get(token);
    if (cached && cached.until > Date.now()) return cached.user;
    const res = await fetch(this.url + '/auth/v1/user', {
      headers: { apikey: this.anon, Authorization: 'Bearer ' + token },
      signal: AbortSignal.timeout(8000)
    });
    if (!res.ok) return null;
    const u = await res.json();
    if (!u || !u.id) return null;
    let username = (u.user_metadata && u.user_metadata.username) || (u.email || 'player').split('@')[0];
    try {
      const rows = await this.request('GET', `/rest/v1/profiles?select=username&id=eq.${u.id}`);
      if (rows && rows[0] && rows[0].username) username = rows[0].username;
    } catch (e) {
      log.warn('profile lookup failed', e.message);
    }
    const user = { id: u.id, email: u.email, username };
    this.tokenCache.set(token, { user, until: Date.now() + 60000 });
    if (this.tokenCache.size > 5000) this.tokenCache.clear();
    return user;
  }

  /**
   * Equipped cosmetics per character, verified against ownership and expiry in the database.
   * @returns {{[charId:string]:{skin?:object, weapon?:object}}}
   */
  async cosmetics(userId) {
    if (!this.enabled || !userId) return {};
    const [eq, inv] = await Promise.all([
      this.request('GET', `/rest/v1/player_equipment?select=slot,character_id,item_id,items(gameplay_effect,character_id)&player_id=eq.${userId}&slot=in.(skin,weapon,effect,equipment)`),
      this.request('GET', `/rest/v1/player_inventory?select=item_id,expires_at&player_id=eq.${userId}`)
    ]);
    const owned = new Set((inv || []).filter(r => !r.expires_at || new Date(r.expires_at) > new Date()).map(r => r.item_id));
    const out = {};
    for (const e of eq || []) {
      if (!owned.has(e.item_id) || !e.items) continue;
      const ch = e.slot === 'effect' || e.slot === 'equipment' ? '*' : (e.items.character_id || e.character_id);
      (out[ch] = out[ch] || {})[e.slot] = Object.assign({ id: e.item_id }, e.items.gameplay_effect || {});
    }
    return out;
  }

  rpc(fn, args) {
    return this.request('POST', '/rest/v1/rpc/' + fn, args);
  }

  // ------------------------------------------------------------ lobby mirror
  async clearServerRooms(serverId) {
    if (!this.enabled) return;
    await this.request('DELETE', `/rest/v1/rooms?server_id=eq.${encodeURIComponent(serverId)}`);
  }

  async upsertRoom(row, players) {
    if (!this.enabled) return;
    await this.request('POST', '/rest/v1/rooms?on_conflict=id', row, { Prefer: 'resolution=merge-duplicates' });
    await this.request('DELETE', `/rest/v1/room_players?room_id=eq.${row.id}`);
    if (players.length) await this.request('POST', '/rest/v1/room_players', players);
  }

  async deleteRoom(id) {
    if (!this.enabled) return;
    await this.request('DELETE', `/rest/v1/rooms?id=eq.${id}`);
  }
}

module.exports = { SupabaseService };
