package com.shadowduel.arena.game;

import com.shadowduel.arena.game.sim.Defs;
import com.shadowduel.arena.game.sim.Fighter;
import com.shadowduel.arena.game.sim.GameMap;
import com.shadowduel.arena.game.sim.Input;
import com.shadowduel.arena.game.sim.Mode;
import com.shadowduel.arena.game.sim.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Full local simulation: Training and offline matches against bots. */
public final class OfflineSession implements GameSession {
    private final World world;
    private final String modeId;
    private final List<World.Event> pending = new ArrayList<>();
    private final Map<Integer, Input> inputs = new HashMap<>();
    private FinalResult result;
    public final boolean training;

    /**
     * @param bots number of bots; in training they are passive dummies unless aggressive is set
     */
    public OfflineSession(Defs defs, GameMap map, String modeId, String playerName, String charId, int bots,
                          float botLevel, boolean aggressiveTrainingBots) {
        this.modeId = modeId;
        this.training = "training".equals(modeId);
        world = new World(defs, map, modeId, -1);
        world.seed = System.nanoTime() & 0xffff;
        int total = bots + 1;
        boolean teams = world.teams;
        Fighter me = world.addFighter(0, playerName, charId, teams ? 1 : 0, false, 0, total);
        com.shadowduel.arena.data.Economy.dress(me);
        // equipment stats only apply in Training (PvE); offline vs-bot matches and online PvP use standard stats
        if (training && com.shadowduel.arena.data.Economy.trainingDefensePct > 0) {
            me.damageTakenMul = 1 - com.shadowduel.arena.data.Economy.trainingDefensePct / 100.0;
        }
        String[] names = {"Shiro", "Kuro", "Hana", "Raiden", "Yuki", "Kenta", "Mei"};
        for (int i = 1; i <= bots; i++) {
            String c = com.shadowduel.arena.game.sim.Const.CHARACTERS[(i + 1) % 3];
            int team = teams ? (i % 2 == 0 ? 1 : 2) : 0;
            Fighter b = world.addFighter(i, "BOT " + names[(i - 1) % names.length], c, team, true, botLevel, total);
            if (training && !aggressiveTrainingBots) {
                world.bots.get(b.id).passive = true;
            }
        }
        for (Fighter f : world.fighters) {
            f.prevX = f.rx = f.x;
            f.prevY = f.ry = f.y;
        }
    }

    public World world() {
        return world;
    }

    /** Training: restore HP/energy of everyone and put them back on spawn points. */
    public void resetTraining() {
        for (Fighter f : world.fighters) {
            if (!f.alive) world.spawner.respawn(f);
            f.hp = f.maxHp;
            f.energy = f.id == 0 ? 100 : 25;
            f.dashCd = 0;
            f.specialCd = 0;
        }
    }

    /** Applies a consumed item's training effect (server already removed it from the inventory). */
    public String applyTrainingEffect(org.json.JSONObject effect) {
        org.json.JSONObject t = effect == null ? null : effect.optJSONObject("training");
        Fighter me = world.byId.get(0);
        if (t == null || me == null || !training) return "Không có hiệu ứng";
        if (t.has("heal")) me.hp = Math.min(me.maxHp, me.hp + t.optInt("heal"));
        if (t.has("energy")) me.energy = Math.min(world.P.energyMax, me.energy + t.optInt("energy"));
        if (t.has("shield_ticks")) me.protectT = Math.max(me.protectT, t.optInt("shield_ticks"));
        if (t.has("speed_ticks")) me.boostT = Math.max(me.boostT, t.optInt("speed_ticks"));
        return "Đã dùng vật phẩm";
    }

    public void setBotsPassive(boolean passive) {
        for (com.shadowduel.arena.game.sim.Bot b : world.bots.values()) b.passive = passive;
    }

    @Override public GameMap map() { return world.map; }
    @Override public String modeId() { return modeId; }
    @Override public int tickRate() { return world.tickRate; }
    @Override public int localId() { return 0; }
    @Override public List<Fighter> fighters() { return world.fighters; }
    @Override public Fighter fighter(int id) { return world.byId.get(id); }
    @Override public List<World.Projectile> projectiles() { return world.projectiles; }
    @Override public List<World.Zone> zones() { return world.zones; }

    @Override
    public List<World.Event> drainEvents() {
        List<World.Event> out = new ArrayList<>(pending);
        pending.clear();
        return out;
    }

    @Override
    public void tick(Input local) {
        if (result != null) return;
        for (Fighter f : world.fighters) {
            f.prevX = f.x;
            f.prevY = f.y;
        }
        inputs.put(0, local);
        if (training) {
            Fighter me = world.byId.get(0);
            if (me != null) me.energy = Math.min(world.P.energyMax, me.energy + 0.5);
        }
        boolean ended = world.step(inputs);
        pending.addAll(world.events);
        if (ended) result = toResult(world.mode.result);
    }

    @Override
    public void interpolate(float alpha, long nowMs) {
        for (Fighter f : world.fighters) {
            double dx = f.x - f.prevX, dy = f.y - f.prevY;
            if (Math.abs(dx) > 200 || Math.abs(dy) > 200) { // respawn teleport: no smear
                f.rx = f.x;
                f.ry = f.y;
            } else {
                f.rx = f.prevX + dx * alpha;
                f.ry = f.prevY + dy * alpha;
            }
        }
    }

    @Override public int timeLeftTicks() { return world.mode.timeLeft(); }
    @Override public int[] teamScores() { return new int[]{world.mode.teamScore[1], world.mode.teamScore[2]}; }
    @Override public double[] safeZone() { return world.mode.zone; }

    @Override
    public List<ScoreRow> scoreboard() {
        List<Fighter> list = new ArrayList<>(world.fighters);
        Collections.sort(list, Mode.scoreboardOrder());
        List<ScoreRow> rows = new ArrayList<>();
        for (Fighter f : list) rows.add(row(f));
        return rows;
    }

    static ScoreRow row(Fighter f) {
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
        return r;
    }

    private FinalResult toResult(Mode.Result r) {
        FinalResult out = new FinalResult();
        out.reason = r.reason;
        out.draw = r.draw;
        out.winners = new int[r.winners.size()];
        for (int i = 0; i < out.winners.length; i++) out.winners[i] = r.winners.get(i);
        out.winnerTeam = r.winnerTeam;
        out.redScore = r.redScore;
        out.blueScore = r.blueScore;
        out.mvp = r.mvp;
        out.topKills = r.topKills;
        out.mostDamage = r.mostDamage;
        out.standings = new ArrayList<>();
        out.placements = new int[r.standings.size()];
        for (int i = 0; i < r.standings.size(); i++) {
            Fighter f = r.standings.get(i);
            out.standings.add(row(f));
            out.placements[i] = f.placement;
        }
        return out;
    }

    /** Ends a training session (or quits an offline match) and produces standings. */
    public void finishNow() {
        if (result == null) result = toResult(world.mode.forceFinish());
    }

    @Override public FinalResult result() { return result; }
    @Override public String netStatus() { return null; }
    @Override public int pingMs() { return -1; }
    @Override public void close() { }
}
