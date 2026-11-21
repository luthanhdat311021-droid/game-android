'use strict';
/**
 * SHADOW DUEL game server.
 *   HTTP  GET /health   -> status JSON
 *         GET /rooms    -> public room list
 *   WS    /            -> game protocol (see docs/PROTOCOL.md)
 */
const http = require('http');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');
const config = require('./config');
const log = require('./log');
const { SupabaseService } = require('./services/SupabaseService');
const { PlayerSessionManager } = require('./PlayerSessionManager');
const { RoomManager, GameError, cleanText } = require('./RoomManager');
const { MatchManager } = require('./MatchManager');
const { MatchResultService } = require('./MatchResultService');
const { PROTOCOL_VERSION } = require('./game/constants');
const { EconomyApi } = require('./api/EconomyApi');

function createServer(cfg = config) {
  const supabase = new SupabaseService(cfg);
  const results = new MatchResultService(supabase);
  let rooms;
  const sessions = new PlayerSessionManager(cfg, s => rooms.leave(s));
  rooms = new RoomManager(cfg, sessions, supabase);
  const matches = new MatchManager(cfg, rooms, sessions, results);
  rooms.matchManager = matches;

  const economy = new EconomyApi(cfg, supabase);
  const httpServer = http.createServer(async (req, res) => {
    if (await economy.handle(req, res)) return;
    const json = (code, body) => {
      res.writeHead(code, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' });
      res.end(JSON.stringify(body));
    };
    if (req.url === '/health') {
      return json(200, {
        ok: true, version: PROTOCOL_VERSION, players: sessions.count(), rooms: rooms.rooms.size,
        matches: matches.matches.size, supabase: supabase.enabled, guests: cfg.allowGuests,
        maxTickMs: Math.round(matches.stats.maxTickMs * 100) / 100
      });
    }
    if (req.url === '/rooms') return json(200, { rooms: rooms.list() });
    json(404, { error: 'not found' });
  });

  const wss = new WebSocketServer({ server: httpServer, maxPayload: 16 * 1024 });

  wss.on('connection', (ws, req) => {
    if (wss.clients.size > cfg.maxConnections) { ws.close(1013, 'server busy'); return; }
    ws.isAlive = true;
    ws.lastMsg = Date.now();
    ws.on('pong', () => { ws.isAlive = true; });
    const helloTimer = setTimeout(() => { if (!ws.session) ws.close(4000, 'hello timeout'); }, 5000);

    ws.on('message', async (raw, isBinary) => {
      ws.lastMsg = Date.now();
      if (isBinary) return;
      let msg;
      try { msg = JSON.parse(raw.toString()); } catch (e) { return send(ws, { t: 'error', code: 'BAD_JSON' }); }
      if (!msg || typeof msg.t !== 'string') return send(ws, { t: 'error', code: 'BAD_MESSAGE' });
      try {
        if (!ws.session) {
          if (msg.t !== 'hello') return send(ws, { t: 'error', code: 'HELLO_REQUIRED' });
          clearTimeout(helloTimer);
          await hello(ws, msg);
          return;
        }
        if (['create_room', 'join_room', 'quick_match', 'select_character'].includes(msg.t) && ws.session.userId) {
          // equipped cosmetics are re-read from the database (never taken from the client)
          ws.session.cosmetics = await supabase.cosmetics(ws.session.userId).catch(() => ws.session.cosmetics || {});
        }
        handle(ws.session, msg);
      } catch (e) {
        if (e instanceof GameError) send(ws, { t: 'error', code: e.code, message: e.message, re: msg.t });
        else { log.error('handler error', msg.t, e.stack); send(ws, { t: 'error', code: 'INTERNAL', re: msg.t }); }
      }
    });

    ws.on('close', () => {
      clearTimeout(helloTimer);
      sessions.detach(ws);
      if (ws.session) {
        const room = ws.session.roomCode && rooms.rooms.get(ws.session.roomCode);
        if (room) rooms.changed(room);
      }
    });
    ws.on('error', e => log.warn('ws error', e.message));
  });

  function send(ws, msg) {
    if (ws.readyState === 1) ws.send(JSON.stringify(msg));
  }

  async function hello(ws, msg) {
    if (msg.v !== PROTOCOL_VERSION) {
      send(ws, { t: 'error', code: 'VERSION_MISMATCH', message: `Server protocol ${PROTOCOL_VERSION}, client ${msg.v}` });
      ws.close(4002, 'version');
      return;
    }
    let session = null;
    if (msg.resume) session = sessions.resume(msg.resume, ws);
    if (!session) {
      let identity = null;
      if (msg.token && supabase.enabled) {
        const user = await supabase.verifyToken(msg.token);
        if (!user) {
          // a token that does not verify is refused — never silently downgraded to a guest
          send(ws, { t: 'error', code: 'AUTH_FAILED', message: 'Invalid or expired Supabase session' });
          ws.close(4003, 'auth');
          return;
        }
        identity = { playerId: user.id, userId: user.id, name: cleanText(user.username, 20, 'Player'), isGuest: false };
      }
      if (!identity && cfg.allowGuests && !msg.token) {
        const tag = crypto.randomInt(1000, 9999);
        identity = { playerId: 'guest-' + crypto.randomUUID(), userId: null, name: cleanText(msg.name, 16, 'Guest') + '#' + tag, isGuest: true };
      }
      if (!identity) {
        send(ws, { t: 'error', code: 'AUTH_FAILED', message: supabase.enabled ? 'Invalid or expired Supabase session' : 'Server requires login but Supabase is not configured' });
        ws.close(4003, 'auth');
        return;
      }
      if (ws.readyState !== 1) return;
      session = sessions.create(identity, ws);
    }
    send(ws, {
      t: 'welcome', v: PROTOCOL_VERSION, playerId: session.playerId, name: session.name, guest: session.isGuest,
      resume: session.resumeToken, tickRate: 30, serverTime: Date.now(), room: session.roomCode
    });
    const room = session.roomCode && rooms.rooms.get(session.roomCode);
    if (room) {
      send(ws, { t: 'room', room: room.state() });
      if (room.match) matches.resync(room, session);
      rooms.changed(room);
    }
  }

  function handle(s, msg) {
    switch (msg.t) {
      case 'in': {
        if (!sessions.allowInput(s)) {
          if (++s.violations > 300) s.ws.close(4008, 'input flood');
          return;
        }
        const room = s.roomCode && rooms.rooms.get(s.roomCode);
        if (room && room.match) room.match.pushInput(s.playerId, msg);
        return;
      }
      case 'ping':
        sessions.send(s, { t: 'pong', c: msg.c, ms: Date.now(), tick: currentTick(s) });
        if (Number.isFinite(msg.rtt)) s.rtt = msg.rtt;
        return;
      case 'list_rooms': sessions.send(s, { t: 'rooms', rooms: rooms.list() }); return;
      case 'create_room': rooms.create(s, msg); return;
      case 'join_room': rooms.join(s, msg.code, msg.password, msg.charId); return;
      case 'quick_match': rooms.quickMatch(s, msg.mode, msg.charId); return;
      case 'leave_room': rooms.leave(s); sessions.send(s, { t: 'left_room' }); return;
      case 'select_character': rooms.selectCharacter(s, msg.charId); return;
      case 'select_team': rooms.selectTeam(s, msg.team); return;
      case 'ready': rooms.setReady(s, msg.ready); return;
      case 'room_settings': rooms.updateSettings(s, msg); return;
      case 'add_bot': rooms.addBot(s, msg.charId); return;
      case 'remove_member': rooms.removeMember(s, msg.playerId); return;
      case 'start_match': rooms.start(s); return;
      default: throw new GameError('UNKNOWN_TYPE', 'Unknown message type ' + msg.t);
    }
  }

  function currentTick(s) {
    const room = s.roomCode && rooms.rooms.get(s.roomCode);
    return room && room.match ? room.match.world.tick : 0;
  }

  // heartbeat: drop dead sockets so their sessions go into reconnect grace
  const heartbeat = setInterval(() => {
    const now = Date.now();
    for (const ws of wss.clients) {
      if (!ws.isAlive || now - ws.lastMsg > cfg.heartbeatTimeoutMs) { ws.terminate(); continue; }
      ws.isAlive = false;
      ws.ping();
    }
  }, 5000);

  async function start() {
    if (supabase.enabled) {
      try { await supabase.clearServerRooms(cfg.serverId); } catch (e) { log.warn('could not clear stale rooms:', e.message); }
    }
    economy.start();
    await new Promise(r => httpServer.listen(cfg.port, cfg.host, r));
    const addr = httpServer.address();
    log.info(`SHADOW DUEL server on ws://${cfg.host}:${addr.port}  supabase=${supabase.enabled} guests=${cfg.allowGuests}`);
    return addr.port;
  }

  async function stop() {
    clearInterval(heartbeat);
    economy.stop();
    for (const ws of wss.clients) ws.terminate();
    await new Promise(r => wss.close(r));
    await new Promise(r => httpServer.close(r));
    if (matches.timer) clearInterval(matches.timer);
  }

  return { start, stop, rooms, matches, sessions, results, httpServer, economy };
}

if (require.main === module) {
  if (!config.supabaseEnabled && !config.allowGuests) {
    log.warn('Supabase is not configured and ALLOW_GUESTS is off: nobody will be able to log in. See server/.env.example');
  }
  const server = createServer();
  server.start();
  const shutdown = () => server.stop().then(() => process.exit(0));
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

module.exports = { createServer };
