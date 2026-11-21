package com.shadowduel.arena.ui;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.shadowduel.arena.App;
import com.shadowduel.arena.R;
import com.shadowduel.arena.data.Net;
import com.shadowduel.arena.data.Prefs;
import com.shadowduel.arena.databinding.ActivitySettingsBinding;

import org.json.JSONObject;

import java.util.HashMap;

/** Server address, graphics, controls, bot difficulty, vibration, FPS. */
public class SettingsActivity extends AppCompatActivity {
    private ActivitySettingsBinding v;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        v = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(v.getRoot());
        Prefs p = App.get().prefs();
        v.serverInput.setText(p.serverUrl());
        v.guestInput.setText(p.guestName());
        v.qualityGroup.check(p.quality() == 0 ? R.id.qLow : p.quality() == 1 ? R.id.qMed : R.id.qHigh);
        v.opacitySlider.setValue(p.controlsOpacity());
        v.botSlider.setValue(p.botLevel());
        v.vibrationSwitch.setChecked(p.vibration());
        v.fpsSwitch.setChecked(p.showFps());
        v.opacityLabel.setText("Độ đậm nút điều khiển: " + p.controlsOpacity() + "%");
        v.botLabel.setText("Độ khó BOT offline: " + p.botLevel() + "%");
        v.opacitySlider.addOnChangeListener((s, val, u) -> v.opacityLabel.setText("Độ đậm nút điều khiển: " + (int) val + "%"));
        v.botSlider.addOnChangeListener((s, val, u) -> v.botLabel.setText("Độ khó BOT offline: " + (int) val + "%"));
        v.testServer.setOnClickListener(x -> test());
        v.saveSettings.setOnClickListener(x -> {
            String url = v.serverInput.getText() == null ? "" : v.serverInput.getText().toString().trim();
            if (!url.isEmpty() && !url.matches("^wss?://.+")) {
                Toast.makeText(this, "Địa chỉ phải bắt đầu bằng ws:// hoặc wss://", Toast.LENGTH_LONG).show();
                return;
            }
            boolean changed = !url.equals(p.serverUrl());
            p.setServerUrl(url);
            p.setGuestName(v.guestInput.getText() == null ? "Guest" : v.guestInput.getText().toString().trim());
            int id = v.qualityGroup.getCheckedRadioButtonId();
            p.setQuality(id == R.id.qLow ? 0 : id == R.id.qMed ? 1 : 2);
            p.setControlsOpacity((int) v.opacitySlider.getValue());
            p.setBotLevel((int) v.botSlider.getValue());
            p.setVibration(v.vibrationSwitch.isChecked());
            p.setShowFps(v.fpsSwitch.isChecked());
            if (changed && !"offline".equals(p.loginMode())) {
                App.get().client().disconnect();
                App.get().client().connect(p.guestName());
            }
            Toast.makeText(this, "Đã lưu", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private void test() {
        String ws = v.serverInput.getText() == null ? "" : v.serverInput.getText().toString().trim();
        String http = ws.replaceFirst("^ws", "http").replaceAll("/+$", "") + "/health";
        v.testResult.setText("Đang kiểm tra " + http + " …");
        Net.run(() -> {
            String msg;
            long t0 = System.currentTimeMillis();
            try {
                Net.Response r = Net.call("GET", http, new HashMap<>(), null);
                JSONObject o = new JSONObject(r.body);
                msg = "✔ Kết nối được (" + (System.currentTimeMillis() - t0) + " ms) · " + o.optInt("players") + " người · "
                        + o.optInt("rooms") + " phòng · khách: " + (o.optBoolean("guests") ? "có" : "không")
                        + " · Supabase: " + (o.optBoolean("supabase") ? "có" : "không");
            } catch (Exception e) {
                msg = "✖ Không kết nối được: " + e.getClass().getSimpleName() + " " + (e.getMessage() == null ? "" : e.getMessage());
            }
            String m = msg;
            Net.ui(() -> v.testResult.setText(m));
        });
    }
}
