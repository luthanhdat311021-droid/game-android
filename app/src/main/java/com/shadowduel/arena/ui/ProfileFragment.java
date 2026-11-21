package com.shadowduel.arena.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.shadowduel.arena.App;
import com.shadowduel.arena.R;
import com.shadowduel.arena.data.Supa;
import com.shadowduel.arena.databinding.FragmentListBinding;
import com.shadowduel.arena.game.GameAssets;

import org.json.JSONObject;

/** Profile: name, rating, lifetime stats (read-only, written by the game server), match history. */
public class ProfileFragment extends Fragment {
    private FragmentListBinding v;
    private static final float[] W = {2, 1};

    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent, @Nullable Bundle b) {
        v = FragmentListBinding.inflate(inf, parent, false);
        return v.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle b) {
        v.listAction.setVisibility(View.VISIBLE);
        v.listAction.setText(R.string.history);
        v.listAction.setOnClickListener(x -> startActivity(new Intent(requireContext(), MatchHistoryActivity.class)));
        load();
    }

    private void addPair(String k, String val) {
        LinearLayout r = Rows.row(requireContext(), new String[]{k, val}, W, 0xFFF4F6FF, false);
        v.listBody.addView(r);
        Rows.animateIn(r, v.listBody.getChildCount());
    }

    private void load() {
        v.listBody.removeAllViews();
        String fav = App.get().prefs().character();
        if (!Supa.signedIn(requireContext())) {
            v.listTitle.setText("Hồ sơ · " + (App.get().client().name() != null ? App.get().client().name() : App.get().prefs().guestName()));
            v.listProgress.setVisibility(View.GONE);
            v.listBody.addView(Rows.message(requireContext(), "Bạn đang chơi khách/offline: thống kê và rating chỉ được lưu khi đăng nhập Supabase."));
            addPair("Nhân vật đang chọn", GameAssets.defs(requireContext()).chars.get(fav).name);
            return;
        }
        v.listTitle.setText("Hồ sơ · " + Supa.username(requireContext()) + "  (chạm để đổi tên)");
        v.listTitle.setOnClickListener(x -> rename());
        v.listProgress.setVisibility(View.VISIBLE);
        addPair("Email", Supa.email(requireContext()));
        addPair("Nhân vật đang chọn", GameAssets.defs(requireContext()).chars.get(fav).name);
        Supa.myRanking(requireContext(), (rows, err) -> {
            if (v == null) return;
            JSONObject r = rows == null ? null : rows.optJSONObject(0);
            if (r != null) {
                addPair("Rating", r.optInt("rating") + "  (cao nhất " + r.optInt("peak_rating") + ")");
            }
        });
        Supa.myStats(requireContext(), (rows, err) -> {
            if (v == null) return;
            v.listProgress.setVisibility(View.GONE);
            JSONObject s = rows == null ? null : rows.optJSONObject(0);
            if (s == null) {
                v.listBody.addView(Rows.message(requireContext(), err != null ? err : "Chưa có trận nào."));
                return;
            }
            int d = Math.max(1, s.optInt("deaths"));
            addPair("Số trận", String.valueOf(s.optInt("matches_played")));
            addPair("Thắng / Hòa / Thua", s.optInt("wins") + " / " + s.optInt("draws") + " / " + s.optInt("losses"));
            addPair("Hạ gục / Bị hạ / Hỗ trợ", s.optInt("kills") + " / " + s.optInt("deaths") + " / " + s.optInt("assists"));
            addPair("K/D", String.format(java.util.Locale.US, "%.2f", s.optInt("kills") / (double) d));
            addPair("Sát thương gây ra", String.valueOf(s.optLong("damage_dealt")));
            addPair("MVP", String.valueOf(s.optInt("mvp_count")));
        });
    }

    private void rename() {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(Supa.username(requireContext()));
        new MaterialAlertDialogBuilder(requireContext()).setTitle("Đổi tên người chơi").setView(input)
                .setPositiveButton("LƯU", (d, w) -> Supa.updateUsername(requireContext(), input.getText().toString().trim(), err -> {
                    if (v == null) return;
                    Snackbar.make(v.getRoot(), err == null ? "Đã đổi tên" : err, Snackbar.LENGTH_LONG).show();
                    if (err == null) load();
                }))
                .setNegativeButton("Hủy", null).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        v = null;
    }
}
