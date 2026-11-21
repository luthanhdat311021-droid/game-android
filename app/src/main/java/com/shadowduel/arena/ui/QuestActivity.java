package com.shadowduel.arena.ui;

import android.os.Bundle;
import android.view.View;

import com.shadowduel.arena.R;
import com.shadowduel.arena.data.Economy;
import com.shadowduel.arena.data.Supa;
import com.shadowduel.arena.databinding.ItemCardBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Daily / weekly quests and achievements. Progress is written only by the game server from
 * verified match results; Claim calls public.claim_quest(), which re-checks progress and
 * refuses a second claim for the same period.
 */
public class QuestActivity extends EconomyActivity {
    private static final String[] TYPES = {"daily", "weekly", "achievement"};

    @Override protected String title() { return "NHIỆM VỤ"; }
    @Override protected String[] tabs() { return new String[]{"Hằng ngày", "Hằng tuần", "Thành tích"}; }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        v.ecoAction.setVisibility(View.VISIBLE);
        v.ecoAction.setText("ĐIỂM DANH");
        v.ecoAction.setOnClickListener(x -> Supa.rpc(this, "claim_daily_login", null, (r, err) -> {
            if (err != null) toast(err);
            else toast(((JSONObject) r).optBoolean("claimed") ? "Điểm danh hôm nay: +100 Gold" : "Hôm nay bạn đã điểm danh rồi");
            refreshWallet();
        }));
    }

    /** Same period keys as public.quest_period() (Asia/Ho_Chi_Minh). */
    static String period(String type) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        if ("daily".equals(type)) return now.format(DateTimeFormatter.ISO_LOCAL_DATE);
        if ("weekly".equals(type)) return String.format(Locale.US, "%d-W%02d", now.get(IsoFields.WEEK_BASED_YEAR), now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        return "all";
    }

    @Override
    protected void load(int tab) {
        String type = TYPES[tab], period = period(type), me = Supa.userId(this);
        Supa.select(this, "quests?select=*,quest_objectives(metric,target)&type=eq." + type + "&order=sort", (quests, err) -> {
            if (quests == null) {
                show(null, err, "");
                return;
            }
            Supa.select(this, "player_quest_progress?select=quest_id,metric,progress&player_id=eq." + me + "&period=eq." + period, (prog, e2) -> {
                Supa.select(this, "quest_reward_claims?select=quest_id&player_id=eq." + me + "&period=eq." + period, (claims, e3) -> {
                    Map<String, Long> p = new HashMap<>();
                    for (int i = 0; prog != null && i < prog.length(); i++) {
                        JSONObject r = prog.optJSONObject(i);
                        p.put(r.optString("quest_id") + "/" + r.optString("metric"), r.optLong("progress"));
                    }
                    Set<String> claimed = new HashSet<>();
                    for (int i = 0; claims != null && i < claims.length(); i++) claimed.add(claims.optJSONObject(i).optString("quest_id"));
                    List<JSONObject> rows = new ArrayList<>();
                    for (int i = 0; i < quests.length(); i++) {
                        JSONObject q = quests.optJSONObject(i);
                        JSONArray objs = q.optJSONArray("quest_objectives");
                        long cur = 0, target = 1;
                        double frac = 1;
                        for (int k = 0; objs != null && k < objs.length(); k++) {
                            JSONObject o = objs.optJSONObject(k);
                            long have = p.containsKey(q.optString("id") + "/" + o.optString("metric")) ? p.get(q.optString("id") + "/" + o.optString("metric")) : 0;
                            double f = Math.min(1.0, have / (double) Math.max(1, o.optLong("target")));
                            if (f <= frac) {
                                frac = f;
                                cur = have;
                                target = o.optLong("target");
                            }
                        }
                        try {
                            q.put("_cur", Math.min(cur, target)).put("_target", target).put("_done", frac >= 1).put("_claimed", claimed.contains(q.optString("id")));
                        } catch (Exception ignored) {
                        }
                        rows.add(q);
                    }
                    show(rows, err, "Không có nhiệm vụ.");
                });
            });
        });
    }

    @Override
    protected void bind(ItemCardBinding b, JSONObject q, int pos) {
        JSONObject reward = q.optJSONObject("reward");
        boolean done = q.optBoolean("_done"), claimed = q.optBoolean("_claimed");
        String rarity = "achievement".equals(q.optString("type")) ? "legendary" : "weekly".equals(q.optString("type")) ? "epic" : "rare";
        style(b, rarity);
        b.cardRarity.setText(q.optString("type").toUpperCase(Locale.ROOT));
        b.cardTitle.setText(q.optString("title"));
        b.cardSub.setText(q.optString("description") + "\nThưởng: " + Economy.rewardText(reward));
        b.cardSub.setMaxLines(3);
        if (reward != null && reward.optLong("gems") > 0) b.cardIcon.setCurrency("gems");
        else if (reward != null && reward.optJSONArray("items") != null && reward.optJSONArray("items").length() > 0) {
            b.cardIcon.setItem(Economy.items.get(reward.optJSONArray("items").optJSONObject(0).optString("id")));
        } else b.cardIcon.setCurrency("gold");
        b.cardProgress.setVisibility(View.VISIBLE);
        b.cardProgress.setMax((int) Math.min(Integer.MAX_VALUE, q.optLong("_target")));
        b.cardProgress.setProgress((int) Math.min(Integer.MAX_VALUE, q.optLong("_cur")));
        b.cardButton.setText(claimed ? "ĐÃ NHẬN" : done ? "NHẬN" : q.optLong("_cur") + "/" + q.optLong("_target"));
        b.cardButton.setEnabled(done && !claimed);
        b.cardButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(done && !claimed ? R.color.accent_green : R.color.surface_high)));
        b.cardButton.setOnClickListener(x -> {
            b.cardButton.setEnabled(false);
            Supa.rpc(this, "claim_quest", com.shadowduel.arena.net.GameClient.json("p_quest", q.optString("id")), (r, err) -> {
                if (err != null) {
                    toast(err);
                    b.cardButton.setEnabled(true);
                    return;
                }
                celebrate(b.getRoot());
                toast("Đã nhận: " + Economy.rewardText(reward));
                reload();
            });
        });
    }
}
