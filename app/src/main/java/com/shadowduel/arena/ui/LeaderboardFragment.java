package com.shadowduel.arena.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.shadowduel.arena.R;
import com.shadowduel.arena.data.Supa;
import com.shadowduel.arena.databinding.FragmentListBinding;

import org.json.JSONObject;

/** Global ranking (Supabase view public.leaderboard, read-only for clients). */
public class LeaderboardFragment extends Fragment {
    private FragmentListBinding v;
    private static final float[] W = {0.5f, 2.2f, 1, 0.8f, 0.8f, 0.8f, 0.8f};

    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent, @Nullable Bundle b) {
        v = FragmentListBinding.inflate(inf, parent, false);
        return v.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle b) {
        v.listTitle.setText("Bảng xếp hạng toàn server");
        v.listAction.setVisibility(View.VISIBLE);
        v.listAction.setText("LÀM MỚI");
        v.listAction.setOnClickListener(x -> load());
        load();
    }

    private void load() {
        v.listProgress.setVisibility(View.VISIBLE);
        v.listBody.removeAllViews();
        Supa.leaderboard(requireContext(), (rows, error) -> {
            if (v == null) return;
            v.listProgress.setVisibility(View.GONE);
            if (rows == null) {
                v.listBody.addView(Rows.message(requireContext(), error + "\nBảng xếp hạng cần Supabase (xem README)."));
                return;
            }
            v.listBody.addView(Rows.row(requireContext(), new String[]{"#", "NGƯỜI CHƠI", "RATING", "TRẬN", "THẮNG", "KO", "MVP"}, W, 0, true));
            String me = Supa.userId(requireContext());
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                int pos = r.optInt("position", i + 1);
                int color = r.optString("player_id").equals(me) ? requireContext().getColor(R.color.accent_cyan)
                        : pos == 1 ? 0xFFFFC93B : pos == 2 ? 0xFFDDE3FF : pos == 3 ? 0xFFFF9A5A : 0xFFF4F6FF;
                LinearLayout row = Rows.row(requireContext(), new String[]{String.valueOf(pos), r.optString("username"),
                        String.valueOf(r.optInt("rating")), String.valueOf(r.optInt("matches")), String.valueOf(r.optInt("wins")),
                        String.valueOf(r.optInt("kills")), String.valueOf(r.optInt("mvp_count"))}, W, color, false);
                v.listBody.addView(row);
                Rows.animateIn(row, i);
            }
            if (rows.length() == 0) v.listBody.addView(Rows.message(requireContext(), "Chưa có ai trên bảng xếp hạng."));
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        v = null;
    }
}
