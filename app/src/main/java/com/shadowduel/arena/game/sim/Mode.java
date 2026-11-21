package com.shadowduel.arena.game.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Port of server/src/game/GameModeSystem.js (same rules and tie-breaks). */
public final class Mode {

    public static final class Result {
        public String reason;
        public boolean draw;
        public final List<Integer> winners = new ArrayList<>();
        public int winnerTeam, redScore, blueScore;
        public int mvp = -1, topKills = -1, mostDamage = -1;
        public final List<Fighter> standings = new ArrayList<>();
    }

    private final World w;
    public final Defs.Mode cfg;
    public final int durationTicks;
    public final int[] teamScore = new int[3];
    public double[] zone;
    public Result result;
    private int lastDeathTick = -1;
    private final List<Integer> lastDeaths = new ArrayList<>();

    Mode(World w, Defs.Mode cfg, int durationSec) {
        this.w = w;
        this.cfg = cfg;
        this.durationTicks = (durationSec >= 0 ? durationSec : cfg.durationSec) * w.tickRate;
    }

    public int timeLeft() {
        if (durationTicks == 0) return -1;
        return Math.max(0, durationTicks - w.tick);
    }

    void onKill(Fighter victim, Fighter killer) {
        if ("tdm".equals(cfg.id) && killer != null && killer.team != victim.team) teamScore[killer.team]++;
        if ("survival".equals(cfg.id)) {
            victim.eliminated = true;
            int alive = 0;
            for (Fighter f : w.fighters) if (!f.eliminated) alive++;
            victim.placement = alive + 1;
            if (w.tick != lastDeathTick) {
                lastDeaths.clear();
                lastDeathTick = w.tick;
            }
            lastDeaths.add(victim.id);
        }
    }

    Result update() {
        if (result != null) return result;
        if ("survival".equals(cfg.id)) updateZone();
        if ("tdm".equals(cfg.id) && (teamScore[1] >= cfg.scoreLimit || teamScore[2] >= cfg.scoreLimit)) return finish("score_limit");
        if ("survival".equals(cfg.id)) {
            int alive = 0;
            for (Fighter f : w.fighters) if (!f.eliminated) alive++;
            if (alive <= 1 && w.fighters.size() > 1) return finish("last_standing");
        }
        if (durationTicks > 0 && w.tick >= durationTicks) return finish("time_up");
        return null;
    }

    private void updateZone() {
        double W = w.map.pixelW;
        double t = w.tick / (double) w.tickRate;
        double k = Math.min(1, Math.max(0, (t - cfg.zoneStartSec) / (double) (cfg.zoneEndSec - cfg.zoneStartSec)));
        double half = (W - (W - cfg.zoneMinWidth) * k) / 2;
        zone = new double[]{Math.round(W / 2 - half), Math.round(W / 2 + half)};
        if (k <= 0 || w.tick % 10 != 0) return;
        for (Fighter f : new ArrayList<>(w.fighters)) {
            if (!f.alive || (f.x >= zone[0] && f.x <= zone[1])) continue;
            int dmg = Math.max(1, (int) Math.round(f.maxHp * cfg.zoneDamagePct / 3));
            w.environmentDamage(f, dmg, "zone");
        }
    }

    static int cmpFfa(Fighter a, Fighter b) {
        if (a.kills != b.kills) return b.kills - a.kills;
        if (a.deaths != b.deaths) return a.deaths - b.deaths;
        return b.dmgDealt - a.dmgDealt;
    }

    static double mvpScore(Fighter f) {
        return f.kills * 100 + f.assists * 50 + f.dmgDealt * 0.2 - f.deaths * 20;
    }

    Result finish(String reason) {
        Result r = new Result();
        r.reason = reason;
        List<Fighter> ordered = new ArrayList<>(w.fighters);
        if ("tdm".equals(cfg.id)) {
            r.draw = teamScore[1] == teamScore[2];
            r.winnerTeam = r.draw ? 0 : teamScore[1] > teamScore[2] ? 1 : 2;
            Collections.sort(ordered, Mode::cmpFfa);
            for (Fighter f : w.fighters) {
                f.placement = r.draw ? 1 : f.team == r.winnerTeam ? 1 : 2;
                if (!r.draw && f.team == r.winnerTeam) r.winners.add(f.id);
            }
        } else if ("survival".equals(cfg.id)) {
            List<Fighter> alive = new ArrayList<>();
            for (Fighter f : w.fighters) if (!f.eliminated) alive.add(f);
            if (alive.isEmpty()) {
                for (int id : lastDeaths) {
                    w.byId.get(id).placement = 1;
                    r.winners.add(id);
                }
            } else {
                Collections.sort(alive, (a, b) -> b.hp - a.hp);
                int place = 1;
                for (int i = 0; i < alive.size(); i++) {
                    if (i > 0 && alive.get(i).hp < alive.get(i - 1).hp) place = i + 1;
                    alive.get(i).placement = place;
                    if (place == 1) r.winners.add(alive.get(i).id);
                }
            }
            r.draw = r.winners.size() > 1;
            Collections.sort(ordered, (a, b) -> a.placement != b.placement ? a.placement - b.placement : cmpFfa(a, b));
        } else {
            Collections.sort(ordered, Mode::cmpFfa);
            int place = 1;
            for (int i = 0; i < ordered.size(); i++) {
                if (i > 0 && cmpFfa(ordered.get(i - 1), ordered.get(i)) != 0) place = i + 1;
                ordered.get(i).placement = place;
                if (place == 1) r.winners.add(ordered.get(i).id);
            }
            r.draw = r.winners.size() > 1;
        }
        r.redScore = teamScore[1];
        r.blueScore = teamScore[2];
        Fighter mvp = null, tk = null, md = null;
        for (Fighter f : w.fighters) {
            if (mvp == null || mvpScore(f) > mvpScore(mvp)) mvp = f;
            if (tk == null || f.kills > tk.kills) tk = f;
            if (md == null || f.dmgDealt > md.dmgDealt) md = f;
            f.victory = r.winners.contains(f.id);
        }
        r.mvp = mvp == null ? -1 : mvp.id;
        r.topKills = tk == null ? -1 : tk.id;
        r.mostDamage = md == null ? -1 : md.id;
        r.standings.addAll(ordered);
        result = r;
        return r;
    }

    /** Ends a training session or an aborted match on demand. */
    public Result forceFinish() {
        return result != null ? result : finish("time_up");
    }

    public static Comparator<Fighter> scoreboardOrder() {
        return Mode::cmpFfa;
    }
}
