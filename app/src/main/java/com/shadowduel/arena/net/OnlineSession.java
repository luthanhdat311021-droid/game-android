package com.shadowduel.arena.net;

import android.content.Context;

import com.shadowduel.arena.game.GameAssets;
import com.shadowduel.arena.game.GameSession;
import com.shadowduel.arena.game.sim.Const;
import com.shadowduel.arena.game.sim.Defs;
import com.shadowduel.arena.game.sim.Fighter;
import com.shadowduel.arena.game.sim.GameMap;
import com.shadowduel.arena.game.sim.Input;
import com.shadowduel.arena.game.sim.World;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Online match driven by the authoritative server.
 *  - Own fighter: client-side prediction with the shared movement code, reconciled against
 *    every snapshot (rewind to the server state, replay unacknowledged inputs). Visual error
 *    is smoothed out over a few frames instead of snapping.
 *  - Other fighters: entity interpolation buffer, rendered INTERP_TICKS behind the server.
 *  - Damage, kills, respawns and scores only ever come from the server.
 */
public final class OnlineSession implements GameSession, GameClient.MatchSink {

    private static final int INTERP_TICKS = 4;      // ≈133 ms at 30 Hz (2 snapshot intervals)
    private static final int BUFFER = 24;

    /** One remote state sample. */
    private static final class Sample {
        int tick;
        double x, y, vx, vy, en;
        int f, hp, st, a, at, cb, al, pr, rs, sc;
    }

    private final GameClient client;
    private final Defs defs;
    private final GameMap map;
    private final String modeId, matchId;
    private final int tickRate, localId, durationTicks;
    private final World predict;                       // prediction world (movement only)
    private final Map<Integer, Fighter> fighters = new HashMap<>();
    private final List<Fighter> ordered = new ArrayList<>();
    private final Map<Integer, Map<String, Double>> net = new HashMap<>();   // merged server state
    private final Map<Integer, ArrayDeque<Sample>> buffers = new HashMap<>();
    private final ConcurrentLinkedQueue<JSONObject> inbox = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<Input> pending = new ArrayDeque<>();
    private final List<World.Event> events = new ArrayList<>();
    private List<World.Projectile> projectiles = new ArrayList<>();
    private List<World.Zone> zones = new ArrayList<>();
    private final Map<String, Integer> zoneLife = new HashMap<>();
    private Fighter me;
    private int seq, ack;
    private int lastSnapTick, timeLeft = -1;
    private long lastSnapMs;
    private double serverTickEst;   // fractional server tick estimate
    private int[] teamScore = {0, 0};
    private double[] zone;
    private FinalResult result;
    private double errX, errY;      // visual smoothing of reconciliation corrections
    private volatile boolean aborted;
    private long lastMsgMs = System.currentTimeMillis();
    public int corrections;

    public OnlineSession(Context c, GameClient client, JSONObject start) throws Exception {
        this.client = client;
        defs = GameAssets.defs(c);
        modeId = start.getString("mode");
        matchId = start.getString("matchId");
        map = GameAssets.map(c, start.getString("map"));
        tickRate = start.optInt("tickRate", 30);
        localId = start.getInt("you");
        durationTicks = start.optInt("duration", 0);
        serverTickEst = start.optInt("tick", 0);
        lastSnapTick = (int) serverTickEst;
        predict = new World(defs, map, "ffa".equals(modeId) || "tdm".equals(modeId) || "survival".equals(modeId) ? modeId : "ffa", -1);
        applyRoster(start.getJSONArray("players"));
        if (start.optBoolean("resumed")) seq = start.optInt("lastSeq", 0);
        client.setSink(this);
    }

    private void applyRoster(JSONArray players) throws Exception {
        for (int i = 0; i < players.length(); i++) {
            JSONObject p = players.getJSONObject(i);
            int id = p.getInt("id");
            if (fighters.containsKey(id)) continue;
            Fighter f;
            if (id == localId) {
                f = predict.addFighter(id, p.getString("name"), p.getString("charId"), p.optInt("team"), false, 0, 1);
                me = f;
            } else {
                f = new Fighter(id, p.getString("name"), p.getString("charId"), p.optInt("team"), p.optBoolean("isBot"), defs);
            }
            f.slot = p.optInt("slot", id);
            JSONObject skin = p.optJSONObject("skin");   // server-verified cosmetic, look only
            f.skin = com.shadowduel.arena.data.Economy.palette(skin == null ? null : skin.optJSONObject("palette"));
            f.known = false;
            fighters.put(id, f);
            ordered.add(f);
        }
        Collections.sort(ordered, (a, b) -> a.slot - b.slot);
    }

    // ------------------------------------------------------------------ network thread
    @Override
    public void onMatchMessage(String type, JSONObject msg) {
        lastMsgMs = System.currentTimeMillis();
        if ("match_aborted".equals(type)) aborted = true;
        inbox.add(msg);
    }

    // ------------------------------------------------------------------ game thread
    private void drainInbox() {
        JSONObject m;
        while ((m = inbox.poll()) != null) {
            try {
                String t = m.optString("t");
                if ("snap".equals(t)) applySnapshot(m);
                else if ("roster".equals(t)) applyRoster(m.getJSONArray("players"));
                else if ("match_end".equals(t)) applyEnd(m);
                else if ("match_start".equals(t) && m.optBoolean("resumed")) {
                    // reconnected: server will send a full snapshot; drop stale prediction
                    pending.clear();
                    applyRoster(m.getJSONArray("players"));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static final String[] KEYS = {"x", "y", "vx", "vy", "f", "hp", "en", "st", "a", "at", "cb", "q", "sn", "dc", "sc",
            "dr", "g", "go", "mv", "j", "ai", "al", "rs", "pr"};

    private void applySnapshot(JSONObject s) throws Exception {
        int tick = s.getInt("tick");
        lastSnapTick = tick;
        lastSnapMs = System.currentTimeMillis();
        // server tick estimate: snap to the newest tick, then advance with local time
        if (tick > serverTickEst || tick < serverTickEst - 15) serverTickEst = tick;
        timeLeft = s.optInt("tl", timeLeft);
        ack = s.optInt("ack", ack);
        JSONArray ps = s.optJSONArray("p");
        if (ps != null) for (int i = 0; i < ps.length(); i++) {
            JSONObject d = ps.getJSONObject(i);
            int id = d.getInt("i");
            Map<String, Double> st = net.get(id);
            if (st == null) net.put(id, st = new HashMap<>());
            for (String k : KEYS) if (d.has(k)) st.put(k, d.getDouble(k));
        }
        // every fighter we know gets a sample at this tick (unchanged fields are implicit)
        for (Map.Entry<Integer, Map<String, Double>> e : net.entrySet()) {
            int id = e.getKey();
            Fighter f = fighters.get(id);
            if (f == null) continue;
            Map<String, Double> st = e.getValue();
            if (id == localId) {
                reconcile(st);
                continue;
            }
            Sample sm = new Sample();
            sm.tick = tick;
            sm.x = g(st, "x"); sm.y = g(st, "y"); sm.vx = g(st, "vx"); sm.vy = g(st, "vy"); sm.en = g(st, "en");
            sm.f = (int) g(st, "f"); sm.hp = (int) g(st, "hp"); sm.st = (int) g(st, "st"); sm.a = (int) g(st, "a");
            sm.at = (int) g(st, "at"); sm.cb = (int) g(st, "cb"); sm.al = (int) g(st, "al"); sm.pr = (int) g(st, "pr");
            sm.rs = (int) g(st, "rs"); sm.sc = (int) g(st, "sc");
            ArrayDeque<Sample> buf = buffers.get(id);
            if (buf == null) buffers.put(id, buf = new ArrayDeque<>());
            Sample last = buf.peekLast();
            if (last != null && last.tick == tick) buf.pollLast();
            buf.add(sm);
            while (buf.size() > BUFFER) buf.poll();
            f.known = true;
        }
        // projectiles: [id, kind, x, y, vx, owner]
        List<World.Projectile> np = new ArrayList<>();
        JSONArray pr = s.optJSONArray("pr");
        if (pr != null) for (int i = 0; i < pr.length(); i++) {
            JSONArray a = pr.getJSONArray(i);
            World.Projectile p = new World.Projectile();
            p.id = a.getInt(0);
            p.kind = a.getString(1);
            p.x = a.getDouble(2);
            p.y = a.getDouble(3);
            p.vx = a.getDouble(4);
            p.owner = a.getInt(5);
            p.w = p.x; // renderer ignores w for projectiles; used as extrapolation base
            np.add(p);
        }
        projectiles = np;
        // zones: [id, kind, x, y, w, h, life, owner]
        List<World.Zone> nz = new ArrayList<>();
        JSONArray zs = s.optJSONArray("z");
        if (zs != null) for (int i = 0; i < zs.length(); i++) {
            JSONArray a = zs.getJSONArray(i);
            World.Zone z = new World.Zone();
            z.id = a.getInt(0);
            z.kind = a.getString(1);
            z.x = a.getDouble(2);
            z.y = a.getDouble(3);
            z.w = a.getDouble(4);
            z.h = a.getDouble(5);
            z.life = a.getInt(6);
            z.owner = a.getInt(7);
            String key = String.valueOf(z.id);
            Integer max = zoneLife.get(key);
            if (max == null) zoneLife.put(key, max = Math.max(z.life, 48));
            z.maxLife = max;
            nz.add(z);
        }
        zones = nz;
        // events
        JSONArray ev = s.optJSONArray("ev");
        if (ev != null) for (int i = 0; i < ev.length(); i++) events.add(toEvent(ev.getJSONObject(i)));
        // scoreboard rows: [id, k, d, a, dmg, score]
        JSONArray sb = s.optJSONArray("sb");
        if (sb != null) for (int i = 0; i < sb.length(); i++) {
            JSONArray r = sb.getJSONArray(i);
            Fighter f = fighters.get(r.getInt(0));
            if (f == null) continue;
            f.kills = r.getInt(1);
            f.deaths = r.getInt(2);
            f.assists = r.getInt(3);
            f.dmgDealt = r.getInt(4);
        }
        JSONArray ts = s.optJSONArray("ts");
        if (ts != null) teamScore = new int[]{ts.getInt(0), ts.getInt(1)};
        JSONArray zn = s.optJSONArray("zn");
        if (zn != null) zone = new double[]{zn.getDouble(0), zn.getDouble(1)};
        JSONArray gone = s.optJSONArray("gone");
        if (gone != null) for (int i = 0; i < gone.length(); i++) remove(gone.getInt(i));
    }

    private void remove(int id) {
        Fighter f = fighters.remove(id);
        if (f != null) ordered.remove(f);
        net.remove(id);
        buffers.remove(id);
    }

    private static double g(Map<String, Double> m, String k) {
        Double v = m.get(k);
        return v == null ? 0 : v;
    }

    private World.Event toEvent(JSONObject e) {
        World.Event ev = new World.Event();
        String type = e.optString("e");
        ev.type = "hit".equals(type) ? World.Event.HIT : "kill".equals(type) ? World.Event.KILL
                : "spawn".equals(type) ? World.Event.SPAWN : World.Event.LEFT;
        ev.tick = e.optInt("tk");
        if ("kill".equals(type)) {
            ev.a = e.isNull("k") ? -1 : e.optInt("k", -1);
            ev.v = e.optInt("v");
            ev.kind = e.optString("c");
            JSONArray as = e.optJSONArray("as");
            ev.assists = new int[as == null ? 0 : as.length()];
            for (int i = 0; i < ev.assists.length; i++) ev.assists[i] = as.optInt(i);
        } else if ("hit".equals(type)) {
            ev.a = e.isNull("a") ? -1 : e.optInt("a", -1);
            ev.v = e.optInt("v");
            ev.d = e.optInt("d");
            ev.blocked = e.optInt("b") == 1;
            ev.kind = e.optString("k");
        } else {
            ev.v = e.optInt("p");
        }
        ev.x = e.optDouble("x", 0);
        ev.y = e.optDouble("y", 0);
        return ev;
    }

    /** Rewind own fighter to the authoritative state and replay inputs the server has not processed yet. */
    private void reconcile(Map<String, Double> st) {
        Fighter f = me;
        if (f == null) return;
        double oldX = f.rx, oldY = f.ry;
        boolean wasKnown = f.known;
        f.x = g(st, "x"); f.y = g(st, "y"); f.vx = g(st, "vx"); f.vy = g(st, "vy");
        f.facing = (int) g(st, "f"); f.hp = (int) g(st, "hp"); f.energy = g(st, "en");
        f.act = (int) g(st, "a"); f.actT = (int) g(st, "at"); f.combo = (int) g(st, "cb"); f.queued = (int) g(st, "q");
        f.stunT = (int) g(st, "sn"); f.dashCd = (int) g(st, "dc"); f.specialCd = (int) g(st, "sc"); f.dropT = (int) g(st, "dr");
        f.grounded = g(st, "g") == 1; f.groundOneWay = g(st, "go") == 1; f.onMover = (int) g(st, "mv");
        f.jumps = (int) g(st, "j"); f.airT = (int) g(st, "ai");
        f.alive = g(st, "al") == 1; f.respawnT = (int) g(st, "rs"); f.protectT = (int) g(st, "pr");
        f.known = true;
        while (!pending.isEmpty() && pending.peek().seq <= ack) pending.poll();
        if (f.alive) {
            int t = lastSnapTick;
            for (Input in : pending) {
                map.updateMovers(++t);
                predict.tick = t;
                f.step(in, predict);
            }
        }
        f.prevX = f.x;
        f.prevY = f.y;
        if (wasKnown && f.alive) {
            double ex = oldX - f.x, ey = oldY - f.y;
            if (Math.abs(ex) < 120 && Math.abs(ey) < 120) {
                if (Math.abs(ex) > 1 || Math.abs(ey) > 1) corrections++;
                errX = ex;
                errY = ey;
            } else {
                errX = errY = 0;
            }
        }
    }

    private void applyEnd(JSONObject m) throws Exception {
        JSONObject r = m.getJSONObject("result");
        FinalResult out = new FinalResult();
        out.matchId = m.optString("matchId");
        out.reason = r.optString("reason");
        out.draw = r.optBoolean("draw");
        JSONArray w = r.optJSONArray("winners");
        out.winners = new int[w == null ? 0 : w.length()];
        for (int i = 0; i < out.winners.length; i++) out.winners[i] = w.getInt(i);
        out.winnerTeam = r.optInt("winnerTeam");
        JSONObject ts = r.optJSONObject("teamScore");
        if (ts != null) {
            out.redScore = ts.optInt("red");
            out.blueScore = ts.optInt("blue");
        }
        out.mvp = r.isNull("mvp") ? -1 : r.optInt("mvp", -1);
        out.topKills = r.isNull("topKills") ? -1 : r.optInt("topKills", -1);
        out.mostDamage = r.isNull("mostDamage") ? -1 : r.optInt("mostDamage", -1);
        JSONArray st = r.getJSONArray("standings");
        out.standings = new ArrayList<>();
        out.placements = new int[st.length()];
        for (int i = 0; i < st.length(); i++) {
            JSONObject s = st.getJSONObject(i);
            ScoreRow row = new ScoreRow();
            row.id = s.getInt("id");
            row.name = s.getString("name");
            row.charId = s.getString("charId");
            row.team = s.optInt("team");
            row.kills = s.optInt("kills");
            row.deaths = s.optInt("deaths");
            row.assists = s.optInt("assists");
            row.damage = s.optInt("damageDealt");
            row.score = s.optInt("score");
            out.standings.add(row);
            out.placements[i] = s.optInt("placement");
        }
        for (Fighter f : ordered) {
            for (int id : out.winners) if (id == f.id) f.victory = true;
        }
        result = out;
    }

    // ------------------------------------------------------------------ GameSession
    @Override
    public void tick(Input local) {
        drainInbox();
        serverTickEst += 1;
        if (result != null || aborted) return;
        if (me != null && me.known && me.alive) {
            local.seq = ++seq;
            client.sendInput(seq, local.mx, local.my, local.b);
            pending.add(local);
            while (pending.size() > 90) pending.poll();
            me.prevX = me.x;
            me.prevY = me.y;
            int t = (int) Math.round(serverTickEst);
            map.updateMovers(t);
            predict.tick = t;
            me.step(local, predict);
        } else if (me != null) {
            // dead / not yet spawned: keep the socket alive and the server aware we are here
            local.seq = ++seq;
            client.sendInput(seq, 0, 0, 0);
            pending.add(local);
            while (pending.size() > 90) pending.poll();
        }
    }

    @Override
    public void interpolate(float alpha, long nowMs) {
        drainInbox();
        double renderTick = serverTickEst - 1 + alpha - INTERP_TICKS;
        for (Map.Entry<Integer, ArrayDeque<Sample>> e : buffers.entrySet()) {
            Fighter f = fighters.get(e.getKey());
            if (f == null || f == me) continue;
            ArrayDeque<Sample> buf = e.getValue();
            Sample a = null, b = null;
            for (Iterator<Sample> it = buf.iterator(); it.hasNext(); ) {
                Sample s = it.next();
                if (s.tick <= renderTick) a = s;
                else {
                    b = s;
                    break;
                }
            }
            Sample use;
            if (a == null) {
                use = buf.peekFirst();
                if (use == null) continue;
                f.rx = use.x;
                f.ry = use.y;
            } else if (b == null) {
                use = a;
                double ahead = Math.min(3, renderTick - a.tick);   // short extrapolation only
                f.rx = a.x + a.vx * ahead / tickRate;
                f.ry = a.y + (a.al == 1 && a.vy != 0 ? a.vy * ahead / tickRate : 0);
            } else {
                use = a;
                double k = (renderTick - a.tick) / Math.max(1, b.tick - a.tick);
                boolean teleport = Math.abs(b.x - a.x) > 300 || Math.abs(b.y - a.y) > 300 || a.al != b.al;
                if (teleport) {
                    f.rx = k < 0.5 ? a.x : b.x;
                    f.ry = k < 0.5 ? a.y : b.y;
                    if (k >= 0.5) use = b;
                } else {
                    f.rx = a.x + (b.x - a.x) * k;
                    f.ry = a.y + (b.y - a.y) * k;
                }
            }
            f.x = f.rx;
            f.y = f.ry;
            f.vx = use.vx;
            f.vy = use.vy;
            f.facing = use.f == 0 ? 1 : use.f;
            f.hp = use.hp;
            f.energy = use.en;
            f.netAnim = use.st;
            f.act = use.a;
            f.actT = use.at;
            f.combo = Math.max(0, Math.min(2, use.cb));
            f.alive = use.al == 1;
            f.protectT = use.pr;
            f.respawnT = use.rs;
            f.specialCd = use.sc;
            f.grounded = use.vy == 0;
        }
        if (me != null) {
            errX *= 0.82;
            errY *= 0.82;
            me.rx = me.prevX + (me.x - me.prevX) * alpha + errX;
            me.ry = me.prevY + (me.y - me.prevY) * alpha + errY;
        }
        // projectiles fly on between snapshots (p.w holds the x received in the last snapshot)
        double since = Math.min(0.15, (System.currentTimeMillis() - lastSnapMs) / 1000.0);
        for (World.Projectile p : projectiles) p.x = p.w + p.vx * since;
    }

    @Override public GameMap map() { return map; }
    @Override public String modeId() { return modeId; }
    @Override public int tickRate() { return tickRate; }
    @Override public int localId() { return localId; }
    @Override public List<Fighter> fighters() { return ordered; }
    @Override public Fighter fighter(int id) { return fighters.get(id); }
    @Override public List<World.Projectile> projectiles() { return projectiles; }
    @Override public List<World.Zone> zones() { return zones; }

    @Override
    public List<World.Event> drainEvents() {
        List<World.Event> out = new ArrayList<>(events);
        events.clear();
        return out;
    }

    @Override
    public int timeLeftTicks() {
        if (timeLeft < 0) return -1;
        return Math.max(0, timeLeft - (int) Math.max(0, serverTickEst - lastSnapTick));
    }

    @Override public int[] teamScores() { return teamScore; }
    @Override public double[] safeZone() { return zone; }

    @Override
    public List<ScoreRow> scoreboard() {
        List<ScoreRow> rows = new ArrayList<>();
        for (Fighter f : ordered) {
            ScoreRow r = new ScoreRow();
            r.id = f.id;
            r.name = f.name;
            r.charId = f.charId;
            r.team = f.team;
            r.kills = f.kills;
            r.deaths = f.deaths;
            r.assists = f.assists;
            r.damage = f.dmgDealt;
            r.score = f.kills;
            r.alive = f.alive;
            rows.add(r);
        }
        Collections.sort(rows, (a, b) -> a.kills != b.kills ? b.kills - a.kills
                : a.deaths != b.deaths ? a.deaths - b.deaths : b.damage - a.damage);
        return rows;
    }

    @Override public FinalResult result() { return result; }

    public boolean aborted() { return aborted; }

    public String matchId() { return matchId; }

    @Override
    public String netStatus() {
        GameClient.State s = client.state();
        if (s == GameClient.State.RECONNECTING) return "ĐANG KẾT NỐI LẠI…";
        if (s == GameClient.State.OFFLINE) return "MẤT KẾT NỐI";
        if (System.currentTimeMillis() - lastMsgMs > 1500) return "MẠNG CHẬM";
        return "ONLINE";
    }

    @Override public int pingMs() { return client.rtt(); }

    @Override
    public void close() {
        client.setSink(null);
    }

    /** Durations used for the loading screen etc. */
    public int durationTicks() { return durationTicks; }

    static boolean isAttack(int act) {
        return act == Const.ACT_BASIC || act == Const.ACT_HEAVY || act == Const.ACT_SPECIAL;
    }
}
