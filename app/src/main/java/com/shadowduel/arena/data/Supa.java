package com.shadowduel.arena.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.shadowduel.arena.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Supabase Auth + PostgREST client. Only the publishable (anon) key is in the APK;
 * Row Level Security on the server decides what the user may read or change.
 * Match results, stats and ratings are written only by the game server.
 */
public final class Supa {
    private Supa() {
    }

    public static final String URL = BuildConfig.SUPABASE_URL.replaceAll("/+$", "");
    public static final String KEY = BuildConfig.SUPABASE_PUBLISHABLE_KEY;
    public static final String NEED_CONFIRM = "NEED_CONFIRM";

    public interface Done {
        void done(String error);
    }

    public interface RowsDone {
        void done(JSONArray rows, String error);
    }

    private interface Job {
        String run() throws Exception;
    }

    static final class ApiException extends Exception {
        ApiException(String m) {
            super(m);
        }
    }

    public static boolean configured() {
        return URL.startsWith("https://") && !URL.contains("YOUR_PROJECT") && KEY.length() > 10;
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("auth", Context.MODE_PRIVATE);
    }

    public static boolean signedIn(Context c) {
        return sp(c).getString("refresh_token", null) != null;
    }

    public static String userId(Context c) {
        return sp(c).getString("user_id", "");
    }

    public static String email(Context c) {
        return sp(c).getString("email", "");
    }

    public static String username(Context c) {
        return sp(c).getString("username", "");
    }

    private static void saveSession(Context c, JSONObject s) throws Exception {
        JSONObject u = s.getJSONObject("user");
        JSONObject meta = u.optJSONObject("user_metadata");
        String name = meta == null ? "" : meta.optString("username", "");
        if (name.isEmpty()) name = u.optString("email", "player").split("@")[0];
        // tokens are stored encrypted with an Android Keystore key and never logged
        SharedPreferences.Editor e = sp(c).edit()
                .putString("access_token", SecureStore.encrypt(s.getString("access_token")))
                .putString("refresh_token", SecureStore.encrypt(s.getString("refresh_token")))
                .putLong("expires_at", System.currentTimeMillis() / 1000 + s.optLong("expires_in", 3600))
                .putString("user_id", u.getString("id"))
                .putString("email", u.optString("email"));
        if (username(c).isEmpty()) e.putString("username", name);
        e.apply();
    }

    public static void signIn(Context c, String email, String password, Done done) {
        async(done, () -> {
            JSONObject body = new JSONObject().put("email", email).put("password", password);
            saveSession(c, call("POST", "/auth/v1/token?grant_type=password", null, body));
            loadUsername(c);
            return null;
        });
    }

    public static void signUp(Context c, String username, String email, String password, Done done) {
        async(done, () -> {
            JSONObject body = new JSONObject().put("email", email).put("password", password)
                    .put("data", new JSONObject().put("username", username));
            JSONObject res = call("POST", "/auth/v1/signup", null, body);
            if (!res.has("access_token")) return NEED_CONFIRM;
            saveSession(c, res);
            sp(c).edit().putString("username", username).apply();
            loadUsername(c);
            return null;
        });
    }

    private static void loadUsername(Context c) {
        try {
            JSONArray rows = new JSONArray(request("GET", "/rest/v1/profiles?select=username&id=eq." + userId(c), token(c), null, null));
            if (rows.length() > 0) sp(c).edit().putString("username", rows.getJSONObject(0).getString("username")).apply();
        } catch (Exception ignored) {
        }
    }

    // ----- Google sign-in: Supabase OAuth with PKCE (browser → shadowduel://auth-callback) -----

    public static final String OAUTH_REDIRECT = "shadowduel://auth-callback";

    /** Builds the authorize URL and remembers the PKCE verifier on the device. */
    public static String googleAuthorizeUrl(Context c) throws Exception {
        byte[] rnd = new byte[48];
        new java.security.SecureRandom().nextBytes(rnd);
        String verifier = android.util.Base64.encodeToString(rnd, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        String challenge = android.util.Base64.encodeToString(hash, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        sp(c).edit().putString("pkce_verifier", SecureStore.encrypt(verifier))
                .putLong("pkce_started", System.currentTimeMillis()).apply();
        return URL + "/auth/v1/authorize?provider=google&redirect_to=" + java.net.URLEncoder.encode(OAUTH_REDIRECT, "UTF-8")
                + "&code_challenge=" + challenge + "&code_challenge_method=s256";
    }

    /** Exchanges the authorization code returned to the deep link for a Supabase session. */
    public static void finishGoogle(Context c, String code, Done done) {
        async(done, () -> {
            String verifier = SecureStore.decrypt(sp(c).getString("pkce_verifier", null));
            long started = sp(c).getLong("pkce_started", 0);
            sp(c).edit().remove("pkce_verifier").remove("pkce_started").apply();
            if (verifier == null || System.currentTimeMillis() - started > 10 * 60_000L) {
                return "Phiên đăng nhập Google đã hết hạn, hãy thử lại";
            }
            JSONObject body = new JSONObject().put("auth_code", code).put("code_verifier", verifier);
            saveSession(c, call("POST", "/auth/v1/token?grant_type=pkce", null, body));
            loadUsername(c);
            return null;
        });
    }

    public static void resetPassword(Context c, String email, Done done) {
        async(done, () -> {
            call("POST", "/auth/v1/recover", null, new JSONObject().put("email", email));
            return null;
        });
    }

    // ----- economy: reads via RLS, writes only through server-checked SQL functions -----

    public interface JsonDone {
        void done(Object result, String error);
    }

    /** Calls a Postgres function with the user's own token (auth.uid() inside the function). */
    public static void rpc(Context c, String fn, JSONObject args, JsonDone done) {
        if (!configured() || !signedIn(c)) {
            done.done(null, "Cần đăng nhập tài khoản Supabase");
            return;
        }
        Net.run(() -> {
            Object result = null;
            String error = null;
            try {
                String raw = request("POST", "/rest/v1/rpc/" + fn, token(c), args == null ? new JSONObject() : args, null).trim();
                result = raw.isEmpty() ? new JSONObject() : new org.json.JSONTokener(raw).nextValue();
            } catch (ApiException e) {
                error = economyMessage(e.getMessage());
            } catch (IOException e) {
                error = "Không có kết nối mạng";
            } catch (Exception e) {
                error = "Có lỗi xảy ra, vui lòng thử lại";
            }
            Object r = result;
            String m = error;
            Net.ui(() -> done.done(r, m));
        });
    }

    public static void select(Context c, String pathAndQuery, RowsDone done) {
        rows(c, "/rest/v1/" + pathAndQuery, done);
    }

    private static String economyMessage(String m) {
        if (m == null) return "Lỗi";
        if (m.contains("INSUFFICIENT_FUNDS")) return "Không đủ tiền";
        if (m.contains("ALREADY_OWNED")) return "Bạn đã sở hữu vật phẩm này";
        if (m.contains("ALREADY_CLAIMED")) return "Phần thưởng này đã được nhận";
        if (m.contains("QUEST_NOT_COMPLETE")) return "Nhiệm vụ chưa hoàn thành";
        if (m.contains("NOT_OWNED")) return "Bạn không sở hữu vật phẩm này (hoặc đã hết hạn)";
        if (m.contains("WRONG_CHARACTER")) return "Vật phẩm dành cho nhân vật khác";
        if (m.contains("NOT_EQUIPPABLE")) return "Vật phẩm không thể trang bị";
        if (m.contains("NOT_USABLE")) return "Vật phẩm không thể sử dụng";
        if (m.contains("PRODUCT_NOT_AVAILABLE")) return "Sản phẩm không còn bán";
        if (m.contains("PGRST202") || m.contains("Could not find the function")) return "Database chưa có phần mở rộng (chạy migration 20261122)";
        return m;
    }

    public static void signOut(Context c) {
        String token = SecureStore.decrypt(sp(c).getString("access_token", null));
        sp(c).edit().clear().apply();
        if (token == null || !configured()) return;
        Net.run(() -> {
            try {
                call("POST", "/auth/v1/logout", token, null);
            } catch (Exception ignored) {
            }
        });
    }

    /** Valid access token, refreshed when close to expiry. Call off the UI thread. */
    public static synchronized String token(Context c) throws Exception {
        SharedPreferences p = sp(c);
        String access = SecureStore.decrypt(p.getString("access_token", null));
        if (access != null && p.getLong("expires_at", 0) - 60 > System.currentTimeMillis() / 1000) return access;
        String refresh = SecureStore.decrypt(p.getString("refresh_token", null));
        if (refresh == null) {
            p.edit().clear().apply();
            throw new ApiException("Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại");
        }
        try {
            JSONObject body = new JSONObject().put("refresh_token", refresh);
            JSONObject s = call("POST", "/auth/v1/token?grant_type=refresh_token", null, body);
            saveSession(c, s);
            return s.getString("access_token");
        } catch (ApiException e) {
            p.edit().clear().apply();
            throw new ApiException("Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại");
        }
    }

    public static void updateUsername(Context c, String name, Done done) {
        async(done, () -> {
            request("PATCH", "/rest/v1/profiles?id=eq." + userId(c), token(c),
                    new JSONObject().put("username", name).put("updated_at", java.time.Instant.now().toString()), null);
            sp(c).edit().putString("username", name).apply();
            return null;
        });
    }

    // ----- read-only data (RLS: public select) -----

    public static void leaderboard(Context c, RowsDone done) {
        rows(c, "/rest/v1/leaderboard?select=*&order=rating.desc&limit=50", done);
    }

    public static void myStats(Context c, RowsDone done) {
        rows(c, "/rest/v1/player_stats?select=*&player_id=eq." + userId(c), done);
    }

    public static void myRanking(Context c, RowsDone done) {
        rows(c, "/rest/v1/rankings?select=rating,peak_rating,matches,wins&player_id=eq." + userId(c), done);
    }

    public static void history(Context c, RowsDone done) {
        rows(c, "/rest/v1/match_participants?select=*,matches(mode,map_id,started_at,duration_s)"
                + "&player_id=eq." + userId(c) + "&order=id.desc&limit=30", done);
    }

    public static void matchPlayers(Context c, String matchId, RowsDone done) {
        rows(c, "/rest/v1/match_participants?select=*&match_id=eq." + matchId + "&order=placement.asc", done);
    }

    private static void rows(Context c, String path, RowsDone done) {
        if (!configured()) {
            done.done(null, "Chưa cấu hình Supabase");
            return;
        }
        Net.run(() -> {
            JSONArray rows = null;
            String error = null;
            try {
                rows = new JSONArray(request("GET", path, signedIn(c) ? token(c) : null, null, null));
            } catch (ApiException e) {
                error = e.getMessage();
            } catch (IOException e) {
                error = "Không có kết nối mạng";
            } catch (Exception e) {
                error = "Có lỗi xảy ra, vui lòng thử lại";
            }
            JSONArray r = rows;
            String m = error;
            Net.ui(() -> done.done(r, m));
        });
    }

    private static void async(Done done, Job job) {
        if (!configured()) {
            done.done("Chưa cấu hình Supabase (xem local.properties.example)");
            return;
        }
        Net.run(() -> {
            String error;
            try {
                error = job.run();
            } catch (ApiException e) {
                error = e.getMessage();
            } catch (IOException e) {
                error = "Không có kết nối mạng";
            } catch (Exception e) {
                error = "Có lỗi xảy ra, vui lòng thử lại";
            }
            String r = error;
            Net.ui(() -> done.done(r));
        });
    }

    private static JSONObject call(String method, String path, String bearer, JSONObject body) throws Exception {
        String raw = request(method, path, bearer, body, null);
        return raw.trim().isEmpty() ? new JSONObject() : new JSONObject(raw);
    }

    private static String request(String method, String path, String bearer, JSONObject body, String prefer) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("apikey", KEY);
        h.put("Authorization", "Bearer " + (bearer == null ? KEY : bearer));
        h.put("Content-Type", "application/json");
        if (prefer != null) h.put("Prefer", prefer);
        Net.Response r = Net.call(method, URL + path, h, body == null ? null : body.toString());
        if (r.code / 100 != 2) throw new ApiException(message(r.code, r.body));
        return r.body;
    }

    private static String message(int code, String raw) {
        String ec = "", msg = "";
        try {
            JSONObject o = new JSONObject(raw);
            ec = o.optString("error_code", o.optString("error", o.optString("code", "")));
            msg = o.optString("msg", o.optString("error_description", o.optString("message", "")));
        } catch (Exception ignored) {
        }
        switch (ec) {
            case "invalid_credentials":
            case "invalid_grant": return "Email hoặc mật khẩu không đúng";
            case "email_not_confirmed": return "Email chưa được xác nhận, hãy kiểm tra hộp thư";
            case "user_already_exists":
            case "email_exists": return "Email này đã được đăng ký";
            case "weak_password": return "Mật khẩu quá yếu, dùng ít nhất 6 ký tự";
            case "over_email_send_rate_limit":
            case "over_request_rate_limit": return "Thao tác quá nhiều lần, vui lòng đợi";
            case "23505": return "Tên người chơi đã có người dùng";
            case "23514": return "Tên 3–20 ký tự, chỉ gồm chữ, số, _ . -";
            case "PGRST205":
            case "42P01": return "Database chưa có bảng (chạy supabase/migrations)";
        }
        if (code == 401 || code == 403) return msg.isEmpty() ? "Không có quyền / khóa API không hợp lệ" : msg;
        return msg.isEmpty() ? "Lỗi HTTP " + code : msg;
    }
}
