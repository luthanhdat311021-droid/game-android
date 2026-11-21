package com.shadowduel.arena.data;

import android.content.Context;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-side cache of the economy. Everything here is READ from Supabase (RLS: own rows only);
 * all changes go through server-checked SQL functions or the Node.js server.
 * The item catalog comes from the database — nothing is hardcoded in Java.
 */
public final class Economy {
    private Economy() {
    }

    public static final Map<String, JSONObject> items = new HashMap<>();
    public static long gold = -1, gems = -1;
    /** characterId → equipped skin palette (gameplay_effect.palette), only for owned, unexpired items. */
    public static final Map<String, JSONObject> skinPalette = new HashMap<>();
    /** Equipped training-only armor bonus (never used online). */
    public static int trainingDefensePct;

    public interface Done {
        void done(String error);
    }

    public static int rarityColor(String r) {
        switch (r == null ? "" : r) {
            case "rare": return 0xFF3B8BFF;
            case "epic": return 0xFFB34BFF;
            case "legendary": return 0xFFFFC93B;
            case "mythic": return 0xFFFF3B5C;
            default: return 0xFF9AA3B8;
        }
    }

    public static String rarityName(String r) {
        return r == null ? "" : r.substring(0, 1).toUpperCase() + r.substring(1);
    }

    public static void loadCatalog(Context c, Done done) {
        Supa.select(c, "items?select=*&order=category,rarity,id", (rows, err) -> {
            if (rows != null) {
                items.clear();
                for (int i = 0; i < rows.length(); i++) items.put(rows.optJSONObject(i).optString("id"), rows.optJSONObject(i));
            }
            done.done(err);
        });
    }

    public static void loadWallet(Context c, Done done) {
        Supa.select(c, "wallets?select=gold,gems&player_id=eq." + Supa.userId(c), (rows, err) -> {
            JSONObject w = rows == null ? null : rows.optJSONObject(0);
            if (w != null) {
                gold = w.optLong("gold");
                gems = w.optLong("gems");
            }
            done.done(err);
        });
    }

    /** Equipped skins of the signed-in player, verified against ownership/expiry on read. */
    public static void loadEquipment(Context c, Done done) {
        String me = Supa.userId(c);
        Supa.select(c, "player_inventory?select=item_id,expires_at&player_id=eq." + me, (inv, e1) -> {
            java.util.Set<String> owned = new java.util.HashSet<>();
            long now = System.currentTimeMillis();
            for (int i = 0; inv != null && i < inv.length(); i++) {
                JSONObject r = inv.optJSONObject(i);
                if (r.isNull("expires_at") || parseTime(r.optString("expires_at")) > now) owned.add(r.optString("item_id"));
            }
            Supa.select(c, "player_equipment?select=slot,character_id,item_id,items(gameplay_effect,character_id)&player_id=eq." + me,
                    (eq, e2) -> {
                        skinPalette.clear();
                        trainingDefensePct = 0;
                        for (int i = 0; eq != null && i < eq.length(); i++) {
                            JSONObject r = eq.optJSONObject(i);
                            JSONObject it = r.optJSONObject("items");
                            if ("equipment".equals(r.optString("slot")) && it != null && owned.contains(r.optString("item_id"))) {
                                JSONObject tr = it.optJSONObject("gameplay_effect") == null ? null : it.optJSONObject("gameplay_effect").optJSONObject("training");
                                if (tr != null) trainingDefensePct = tr.optInt("defense_pct");
                            }
                            if (!"skin".equals(r.optString("slot")) || it == null || !owned.contains(r.optString("item_id"))) continue;
                            JSONObject pal = it.optJSONObject("gameplay_effect") == null ? null : it.optJSONObject("gameplay_effect").optJSONObject("palette");
                            if (pal != null) skinPalette.put(it.optString("character_id", r.optString("character_id")), pal);
                        }
                        done.done(e1 != null ? e1 : e2);
                    });
        });
    }

    public static long parseTime(String iso) {
        try {
            return java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Palette JSON → [main, dark, accent, hair] colours, or null. */
    public static int[] palette(JSONObject pal) {
        if (pal == null) return null;
        try {
            return new int[]{Color.parseColor(pal.optString("main", "#000000")), Color.parseColor(pal.optString("dark", "#000000")),
                    Color.parseColor(pal.optString("accent", "#FFFFFF")), Color.parseColor(pal.optString("hair", "#000000"))};
        } catch (Exception e) {
            return null;
        }
    }

    public static String rewardText(JSONObject reward) {
        if (reward == null) return "";
        StringBuilder sb = new StringBuilder();
        if (reward.optLong("gold") > 0) sb.append(reward.optLong("gold")).append(" Gold  ");
        if (reward.optLong("gems") > 0) sb.append(reward.optLong("gems")).append(" Gems  ");
        JSONArray its = reward.optJSONArray("items");
        for (int i = 0; its != null && i < its.length(); i++) {
            JSONObject it = its.optJSONObject(i);
            JSONObject def = items.get(it.optString("id"));
            sb.append(it.optInt("qty", 1)).append("× ").append(def != null ? def.optString("name") : it.optString("id")).append("  ");
        }
        return sb.toString().trim();
    }
}
