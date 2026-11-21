package com.shadowduel.arena.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;
import com.shadowduel.arena.App;
import com.shadowduel.arena.R;
import com.shadowduel.arena.databinding.ActivityCreateRoomBinding;
import com.shadowduel.arena.game.GameAssets;
import com.shadowduel.arena.game.sim.Const;
import com.shadowduel.arena.net.GameClient;
import com.shadowduel.arena.ui.widget.MapPreviewView;

import org.json.JSONObject;

/** Create Room: name, mode, map (cards built in Java), size, privacy and rule switches. */
public class CreateRoomActivity extends AppCompatActivity implements GameClient.Listener {
    private ActivityCreateRoomBinding v;
    private String map = "shadow_temple";
    private final MaterialCardView[] mapCards = new MaterialCardView[Const.MAPS.length];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        v = ActivityCreateRoomBinding.inflate(getLayoutInflater());
        setContentView(v.getRoot());
        String mode = getIntent().getStringExtra("mode");
        if ("tdm".equals(mode)) v.cTdm.setChecked(true);
        else if ("survival".equals(mode)) v.cSurvival.setChecked(true);
        String who = App.get().client().name();
        v.roomNameInput.setText("SHADOW ROOM" + (who != null ? " · " + who : ""));
        v.maxSlider.addOnChangeListener((s, value, fromUser) -> v.maxLabel.setText("Số người tối đa: " + (int) value));
        v.privateSwitch.setOnCheckedChangeListener((s, on) -> v.roomPasswordLayout.setVisibility(on ? View.VISIBLE : View.GONE));
        v.createModeChips.setOnCheckedStateChangeListener((g, ids) -> updateSwitches());
        updateSwitches();
        for (int i = 0; i < Const.MAPS.length; i++) {
            mapCards[i] = mapCard(Const.MAPS[i]);
            v.mapCards.addView(mapCards[i]);
        }
        selectMap(map);
        v.createButton.setOnClickListener(x -> create());
    }

    private String mode() {
        int id = v.createModeChips.getCheckedChipId();
        return id == R.id.cTdm ? "tdm" : id == R.id.cSurvival ? "survival" : "ffa";
    }

    private void updateSwitches() {
        String m = mode();
        v.ffSwitch.setEnabled("tdm".equals(m));
        v.jipSwitch.setEnabled("ffa".equals(m));
        if (!"tdm".equals(m)) v.ffSwitch.setChecked(false);
        if (!"ffa".equals(m)) v.jipSwitch.setChecked(false);
    }

    private MaterialCardView mapCard(String id) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        lp.bottomMargin = Rows.dp(this, 6);
        card.setLayoutParams(lp);
        card.setRadius(Rows.dp(this, 10));
        card.setStrokeWidth(Rows.dp(this, 2));
        card.setTag(id);
        FrameLayout frame = new FrameLayout(this);
        MapPreviewView preview = new MapPreviewView(this);
        preview.setMap(id);
        frame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        TextView name = new TextView(this);
        name.setText(GameAssets.mapName(id).toUpperCase());
        name.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC));
        name.setTextSize(18);
        name.setTextColor(0xFFFFFFFF);
        name.setShadowLayer(8, 0, 0, 0xFF000000);
        name.setPadding(Rows.dp(this, 10), Rows.dp(this, 4), 0, 0);
        frame.addView(name, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.TOP));
        card.addView(frame);
        card.setOnClickListener(x -> selectMap(id));
        return card;
    }

    private void selectMap(String id) {
        map = id;
        for (MaterialCardView c : mapCards) {
            boolean on = id.equals(c.getTag());
            c.setStrokeColor(on ? getColor(R.color.accent_yellow) : 0x00000000);
            c.setAlpha(on ? 1f : 0.6f);
        }
    }

    private void create() {
        GameClient c = App.get().client();
        JSONObject msg = GameClient.json("t", "create_room",
                "name", v.roomNameInput.getText() == null ? "" : v.roomNameInput.getText().toString(),
                "mode", mode(), "map", map, "maxPlayers", (int) v.maxSlider.getValue(),
                "isPrivate", v.privateSwitch.isChecked(),
                "password", v.roomPasswordInput.getText() == null ? "" : v.roomPasswordInput.getText().toString(),
                "friendlyFire", v.ffSwitch.isChecked(), "allowJoinInProgress", v.jipSwitch.isChecked(),
                "charId", App.get().prefs().character());
        if (!c.send(msg)) {
            com.google.android.material.snackbar.Snackbar.make(v.getRoot(), "Chưa kết nối game server", com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();
            return;
        }
        v.createButton.setEnabled(false);
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
        if ("room".equals(type)) {
            if (!LobbyActivity.open) startActivity(new Intent(this, LobbyActivity.class));
            finish();
        } else if ("error".equals(type)) {
            v.createButton.setEnabled(true);
            Dialogs.error(this, msg);
        }
    }

    @Override
    public void onConnection(GameClient.State state, String error) {
    }
}
