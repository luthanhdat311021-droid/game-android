package com.shadowduel.arena.ui;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.Gravity;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.shadowduel.arena.App;
import com.shadowduel.arena.R;
import com.shadowduel.arena.databinding.ActivityMatchLoadingBinding;
import com.shadowduel.arena.game.GameAssets;
import com.shadowduel.arena.game.render.Palette;
import com.shadowduel.arena.net.GameClient;
import com.shadowduel.arena.ui.widget.CharacterPreviewView;

import org.json.JSONArray;
import org.json.JSONObject;

/** "Match Loading" VS screen shown during the server countdown. */
public class MatchLoadingActivity extends AppCompatActivity implements GameClient.Listener {
    public static boolean open;
    private ActivityMatchLoadingBinding v;
    private CountDownTimer timer;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        open = true;
        v = ActivityMatchLoadingBinding.inflate(getLayoutInflater());
        setContentView(v.getRoot());
        JSONObject room = App.get().client().room;
        if (room != null) {
            v.loadingMap.setMap(room.optString("map"));
            v.loadingTitle.setText(GameAssets.mapName(room.optString("map")).toUpperCase());
            v.loadingSub.setText(GameAssets.modeName(room.optString("mode")).toUpperCase() + " · " + room.optInt("players") + " FIGHTERS");
            JSONArray ms = room.optJSONArray("members");
            for (int i = 0; ms != null && i < ms.length(); i++) addFighter(ms.optJSONObject(i), i);
        }
        int ms = getIntent().getIntExtra("ms", 3000);
        timer = new CountDownTimer(ms, 100) {
            @Override
            public void onTick(long left) {
                String s = String.valueOf((left + 999) / 1000);
                if (!s.contentEquals(v.loadingCount.getText())) {
                    v.loadingCount.setText(s);
                    v.loadingCount.startAnimation(AnimationUtils.loadAnimation(MatchLoadingActivity.this, R.anim.logo_in));
                }
            }

            @Override
            public void onFinish() {
                v.loadingCount.setText("FIGHT!");
            }
        }.start();
    }

    private void addFighter(JSONObject m, int index) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        CharacterPreviewView p = new CharacterPreviewView(this);
        p.setCharacter(m.optString("charId"));
        p.showcase = false;
        p.setFacing(index % 2 == 0 ? 1 : -1);
        col.addView(p, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView name = new TextView(this);
        name.setText("P" + (m.optInt("slot") + 1) + " " + m.optString("name"));
        name.setTextColor(Palette.forFighter(m.optInt("slot"), m.optInt("team")));
        name.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC));
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        col.addView(name);
        v.loadingFighters.addView(col, new LinearLayout.LayoutParams(0, -1, 1));
        col.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_up));
    }

    @Override
    protected void onStart() {
        super.onStart();
        App.get().client().addListener(this);
        // match_start may already have arrived
        JSONObject start = App.get().client().matchStart;
        if (start != null && !start.optString("matchId").equals(GameActivity.lastMatchId) && !GameActivity.running
                && App.get().client().room != null && "in_match".equals(App.get().client().room.optString("status"))) {
            go();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        App.get().client().removeListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        open = false;
        if (timer != null) timer.cancel();
    }

    private void go() {
        GameActivity.startOnline(this);
        finish();
    }

    @Override
    public void onMessage(String type, JSONObject msg) {
        if ("match_start".equals(type)) go();
        else if ("match_aborted".equals(type) || "kicked".equals(type) || "left_room".equals(type)) finish();
        else if ("room".equals(type) && msg.optJSONObject("room") != null
                && "lobby".equals(msg.optJSONObject("room").optString("status"))) finish();
    }

    @Override
    public void onConnection(GameClient.State state, String error) {
        if (state == GameClient.State.OFFLINE) runOnUiThread(this::finish);
    }
}
