package com.shadowduel.arena.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.shadowduel.arena.App;
import com.shadowduel.arena.R;
import com.shadowduel.arena.data.Supa;
import com.shadowduel.arena.databinding.ActivityMatchResultBinding;
import com.shadowduel.arena.game.GameAssets;
import com.shadowduel.arena.game.GameSession;
import com.shadowduel.arena.net.GameClient;

import org.json.JSONObject;

/** Winner, MVP, Top Kills, Most Damage, placements and rating change. */
public class MatchResultActivity extends AppCompatActivity implements GameClient.Listener {
    private ActivityMatchResultBinding v;
    private GameSession.FinalResult r;
    private static final float[] W = {0.5f, 2.2f, 0.9f, 0.6f, 0.6f, 0.6f, 0.9f};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        v = ActivityMatchResultBinding.inflate(getLayoutInflater());
        setContentView(v.getRoot());
        r = GameActivity.lastResult;
        if (r == null) {
            finish();
            return;
        }
        boolean online = GameActivity.lastOnline;
        int me = GameActivity.lastLocalId;
        String mode = GameActivity.lastMode;
        boolean iWon = false;
        for (int id : r.winners) if (id == me) iWon = true;
        if (r.draw) v.resultBanner.setText("HÒA!");
        else v.resultBanner.setText(iWon ? "CHIẾN THẮNG!" : "THẤT BẠI");
        v.resultBanner.startAnimation(AnimationUtils.loadAnimation(this, R.anim.logo_in));
        v.resultHero.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_left));

        GameSession.ScoreRow winner = find(r.winners.length > 0 ? r.winners[0] : -1);
        if ("tdm".equals(mode)) {
            v.resultWinner.setText((r.draw ? "Hai đội hòa " : r.winnerTeam == 1 ? "RED TEAM thắng " : "BLUE TEAM thắng ")
                    + r.redScore + " - " + r.blueScore);
        } else if (winner != null) {
            StringBuilder names = new StringBuilder();
            for (int id : r.winners) {
                GameSession.ScoreRow s = find(id);
                if (s != null) names.append(names.length() > 0 ? ", " : "").append(s.name);
            }
            v.resultWinner.setText((r.draw ? "Đồng hạng nhất: " : "Người thắng: ") + names);
        }
        GameSession.ScoreRow mvp = find(r.mvp), tk = find(r.topKills), md = find(r.mostDamage);
        v.resultPreview.setCharacter(mvp != null ? mvp.charId : winner != null ? winner.charId : "kaze");
        v.resultAwards.setText("MVP: " + (mvp == null ? "—" : mvp.name)
                + "\nTop Kills: " + (tk == null ? "—" : tk.name + " (" + tk.kills + ")")
                + "\nMost Damage: " + (md == null ? "—" : md.name + " (" + md.damage + ")"));
        v.resultRating.setText(online ? (Supa.signedIn(this) ? "Đang cập nhật rating…" : "Khách: không tính rating")
                : "Offline / Training: không tính rank");

        boolean surv = "survival".equals(mode);
        v.resultTable.addView(Rows.row(this, new String[]{"#", "FIGHTER", "NHÂN VẬT", surv ? "KO" : "K", "D", "A", "DMG"}, W, 0, true));
        for (int i = 0; i < r.standings.size(); i++) {
            GameSession.ScoreRow s = r.standings.get(i);
            int place = r.placements != null && i < r.placements.length ? r.placements[i] : i + 1;
            int color = s.id == me ? getColor(R.color.accent_cyan) : s.team == 1 ? getColor(R.color.team_red)
                    : s.team == 2 ? getColor(R.color.team_blue) : place == 1 ? 0xFFFFC93B : 0xFFF4F6FF;
            LinearLayout row = Rows.row(this, new String[]{String.valueOf(place), s.name,
                    GameAssets.defs(this).chars.get(s.charId).name, String.valueOf(s.kills), String.valueOf(s.deaths),
                    String.valueOf(s.assists), String.valueOf(s.damage)}, W, color, false);
            v.resultTable.addView(row);
            Rows.animateIn(row, i);
        }

        v.resultHome.setOnClickListener(x -> {
            if (online) {
                App.get().client().send(GameClient.json("t", "leave_room"));
                App.get().client().room = null;
            }
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
        if (online) {
            v.resultAgain.setText(R.string.back_lobby);
            v.resultAgain.setOnClickListener(x -> finish()); // LobbyActivity is underneath
        } else {
            v.resultAgain.setText(R.string.play_again);
            v.resultAgain.setOnClickListener(x -> {
                if (GameActivity.lastOfflineIntent != null) startActivity(new Intent(GameActivity.lastOfflineIntent));
                finish();
            });
        }
        if (online) showRating(App.get().client().rating);
    }

    private GameSession.ScoreRow find(int id) {
        if (id < 0 || r == null) return null;
        for (GameSession.ScoreRow s : r.standings) if (s.id == id) return s;
        return null;
    }

    private void showRating(JSONObject msg) {
        if (msg == null || r == null || !msg.optString("matchId").equals(r.matchId)) return;
        JSONObject ch = msg.optJSONObject("changes");
        String me = Supa.userId(this);
        if (ch != null && ch.has(me)) {
            int d = ch.optInt(me);
            v.resultRating.setText("Rating " + (d >= 0 ? "+" : "") + d);
            v.resultRating.setTextColor(d >= 0 ? getColor(R.color.accent_green) : getColor(R.color.accent_red));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        App.get().client().addListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        App.get().client().removeListener(this);
    }

    @Override
    public void onMessage(String type, JSONObject msg) {
        if ("rating".equals(type)) showRating(msg);
    }

    @Override
    public void onConnection(GameClient.State state, String error) {
    }
}
