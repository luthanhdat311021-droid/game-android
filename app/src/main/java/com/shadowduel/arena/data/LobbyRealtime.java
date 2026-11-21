package com.shadowduel.arena.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Supabase Realtime (Phoenix channels) for the lobby:
 *  - Presence: who is online and what they are doing (in lobby / in a room / in a match),
 *  - postgres_changes on public.rooms / public.room_players: room list updates.
 * Presence is only a status indicator — it is never used for match results or anti-cheat.
 * Callbacks run on the UI thread.
 */
public final class LobbyRealtime {

    public interface Listener {
        void onOnline(Map<String, String> players); // presence key -> status text

        void onRoomsChanged();

        void onConnected(boolean on);
    }

    private static final OkHttpClient WS = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build();
    private static final String TOPIC = "realtime:shadow_lobby";
    private final Context ctx;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, String> online = new LinkedHashMap<>();
    private final Runnable heartbeat = this::heartbeat, retry = this::connect;
    private WebSocket socket;
    private boolean running, joined;
    private int generation, ref;
    private String joinRef, status = "Đang ở sảnh";

    public LobbyRealtime(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
    }

    public void start() {
        if (running || !Supa.configured() || !Supa.signedIn(ctx)) return;
        running = true;
        connect();
    }

    public void stop() {
        running = false;
        drop();
    }

    /** Updates the presence status shown to other players. */
    public void setStatus(String s) {
        status = s;
        if (joined) track();
    }

    private void drop() {
        generation++;
        handler.removeCallbacks(heartbeat);
        handler.removeCallbacks(retry);
        if (socket != null) socket.close(1000, null);
        socket = null;
        online.clear();
        if (joined) {
            joined = false;
            listener.onConnected(false);
        }
    }

    private void connect() {
        drop();
        if (!running) return;
        int mine = generation;
        Net.run(() -> {
            String token;
            try {
                token = Supa.token(ctx);
            } catch (Exception e) {
                token = null;
            }
            String jwt = token;
            Net.ui(() -> {
                if (mine != generation || !running) return;
                if (jwt == null) {
                    handler.postDelayed(retry, 5000);
                    return;
                }
                open(mine, jwt);
            });
        });
    }

    private void open(int mine, String jwt) {
        String url = Supa.URL.replace("https://", "wss://") + "/realtime/v1/websocket?apikey=" + Supa.KEY + "&vsn=1.0.0";
        socket = WS.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response r) {
                Net.ui(() -> {
                    if (mine == generation) join(jwt);
                });
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                Net.ui(() -> {
                    if (mine == generation) receive(text);
                });
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                lost(mine);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response r) {
                lost(mine);
            }
        });
    }

    private void lost(int mine) {
        Net.ui(() -> {
            if (mine != generation || !running) return;
            drop();
            handler.postDelayed(retry, 4000);
        });
    }

    private void send(String event, String topic, JSONObject payload, String jr) {
        try {
            JSONObject m = new JSONObject().put("topic", topic).put("event", event).put("payload", payload)
                    .put("ref", String.valueOf(++ref));
            if (jr != null) m.put("join_ref", jr);
            if (socket != null) socket.send(m.toString());
        } catch (Exception ignored) {
        }
    }

    private void join(String jwt) {
        try {
            JSONArray changes = new JSONArray()
                    .put(new JSONObject().put("event", "*").put("schema", "public").put("table", "rooms"))
                    .put(new JSONObject().put("event", "*").put("schema", "public").put("table", "room_players"));
            JSONObject config = new JSONObject()
                    .put("broadcast", new JSONObject().put("ack", false).put("self", false))
                    .put("presence", new JSONObject().put("key", Supa.userId(ctx)).put("enabled", true))
                    .put("postgres_changes", changes);
            joinRef = String.valueOf(ref + 1);
            send("phx_join", TOPIC, new JSONObject().put("config", config).put("access_token", jwt), joinRef);
            handler.postDelayed(heartbeat, 25000);
        } catch (Exception ignored) {
        }
    }

    private void heartbeat() {
        send("heartbeat", "phoenix", new JSONObject(), null);
        handler.postDelayed(heartbeat, 25000);
    }

    private void track() {
        try {
            send("presence", TOPIC, new JSONObject().put("type", "presence").put("event", "track")
                    .put("payload", new JSONObject().put("name", Supa.username(ctx)).put("status", status)), joinRef);
        } catch (Exception ignored) {
        }
    }

    private void receive(String text) {
        try {
            JSONObject m = new JSONObject(text);
            if (!TOPIC.equals(m.optString("topic"))) return;
            JSONObject payload = m.optJSONObject("payload");
            if (payload == null) payload = new JSONObject();
            switch (m.optString("event")) {
                case "phx_reply":
                    if (!m.optString("ref").equals(joinRef)) break;
                    if (!"ok".equals(payload.optString("status"))) {
                        lost(generation);
                        break;
                    }
                    joined = true;
                    listener.onConnected(true);
                    track();
                    break;
                case "postgres_changes":
                    listener.onRoomsChanged();
                    break;
                case "presence_state":
                    online.clear();
                    apply(payload, true);
                    listener.onOnline(online);
                    break;
                case "presence_diff":
                    apply(payload.optJSONObject("leaves"), false);
                    apply(payload.optJSONObject("joins"), true);
                    listener.onOnline(online);
                    break;
                case "phx_error":
                case "phx_close":
                    lost(generation);
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    private void apply(JSONObject o, boolean add) {
        if (o == null) return;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String key = it.next();
            if (!add) {
                online.remove(key);
                continue;
            }
            JSONArray metas = o.optJSONObject(key) == null ? null : o.optJSONObject(key).optJSONArray("metas");
            JSONObject meta = metas == null || metas.length() == 0 ? null : metas.optJSONObject(metas.length() - 1);
            online.put(key, meta == null ? "online" : meta.optString("name", "?") + " · " + meta.optString("status", ""));
        }
    }
}
