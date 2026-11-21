package com.shadowduel.arena.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shadowduel.arena.R;
import com.shadowduel.arena.data.Supa;
import com.shadowduel.arena.databinding.FragmentListBinding;
import com.shadowduel.arena.game.GameAssets;

import org.json.JSONObject;

/** Match history from Supabase (match_participants + matches). Tap a row for the full scoreboard. */
public class MatchHistoryActivity extends AppCompatActivity {
    private FragmentListBinding v;
    private static final float[] W = {1.6f, 1.3f, 1.2f, 0.6f, 0.6f, 0.6f, 0.8f, 0.8f};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        v = FragmentListBinding.inflate(getLayoutInflater());
        setContentView(v.getRoot());
        v.listTitle.setText(R.string.history);
        v.listAction.setVisibility(View.VISIBLE);
        v.listAction.setText("ĐÓNG");
        v.listAction.setOnClickListener(x -> finish());
        if (!Supa.signedIn(this)) {
            v.listProgress.setVisibility(View.GONE);
            v.listBody.addView(Rows.message(this, "Đăng nhập Supabase để lưu và xem lịch sử đấu."));
            return;
        }
        Supa.history(this, (rows, err) -> {
            v.listProgress.setVisibility(View.GONE);
            if (rows == null) {
                v.listBody.addView(Rows.message(this, err));
                return;
            }
            v.listBody.addView(Rows.row(this, new String[]{"THỜI GIAN", "CHẾ ĐỘ", "BẢN ĐỒ", "K", "D", "A", "HẠNG", "RATING"}, W, 0, true));
            for (int i = 0; i < rows.length(); i++) {
                JSONObject p = rows.optJSONObject(i);
                JSONObject m = p.optJSONObject("matches");
                String when = m == null ? "" : m.optString("started_at").replace('T', ' ');
                if (when.length() > 16) when = when.substring(5, 16);
                int rc = p.optInt("rating_change");
                int place = p.optInt("placement");
                LinearLayout row = Rows.row(this, new String[]{when,
                        m == null ? "" : GameAssets.modeName(m.optString("mode")),
                        m == null ? "" : GameAssets.mapName(m.optString("map_id")),
                        String.valueOf(p.optInt("kills")), String.valueOf(p.optInt("deaths")), String.valueOf(p.optInt("assists")),
                        "#" + place, (rc >= 0 ? "+" : "") + rc}, W, place == 1 ? 0xFFFFC93B : 0xFFF4F6FF, false);
                String matchId = p.optString("match_id");
                row.setOnClickListener(x -> details(matchId));
                v.listBody.addView(row);
                Rows.animateIn(row, i);
            }
            if (rows.length() == 0) v.listBody.addView(Rows.message(this, "Chưa có trận nào được lưu."));
        });
    }

    private void details(String matchId) {
        Supa.matchPlayers(this, matchId, (rows, err) -> {
            StringBuilder sb = new StringBuilder();
            if (rows == null) sb.append(err);
            else for (int i = 0; i < rows.length(); i++) {
                JSONObject p = rows.optJSONObject(i);
                sb.append('#').append(p.optInt("placement")).append("  ").append(p.optString("display_name"))
                        .append(p.optBoolean("is_mvp") ? "  (MVP)" : "").append("\n     ")
                        .append(p.optInt("kills")).append(" K / ").append(p.optInt("deaths")).append(" D / ")
                        .append(p.optInt("assists")).append(" A · ").append(p.optInt("damage_dealt")).append(" dmg\n");
            }
            new MaterialAlertDialogBuilder(this).setTitle("Chi tiết trận").setMessage(sb.toString())
                    .setPositiveButton("OK", null).show();
        });
    }
}
