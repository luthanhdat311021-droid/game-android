package com.shadowduel.arena.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.shadowduel.arena.App;
import com.shadowduel.arena.BuildConfig;
import com.shadowduel.arena.data.Net;
import com.shadowduel.arena.data.Supa;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Connection to the authoritative Node.js game server.
 *  - hello with the Supabase access token (or guest name when the server allows guests),
 *  - ping every second (RTT shown in the HUD; server drops silent sockets),
 *  - automatic reconnect with the resume token, so a phone that loses signal keeps its fighter.
 * Lobby messages are delivered to listeners on the UI thread; snapshots go straight to the
 * active match sink on the socket thread to avoid UI-thread latency.
 */
public final class GameClient {

    public interface Listener {
        void onMessage(String type, JSONObject msg);

        void onConnection(State state, String error);
    }

    /** Receives in-match messages (snapshots, roster, match_end) on the network thread. */
    public interface MatchSink {
        void onMatchMessage(String type, JSONObject msg);
    }

    public enum State { OFFLINE, CONNECTING, ONLINE, RECONNECTING }

    private static final String TAG = "GameClient";
    private final Context ctx;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .build();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final ArrayDeque<String[]> early = new ArrayDeque<>(); // match messages before a sink exists
    private volatile WebSocket ws;
    private volatile State state = State.OFFLINE;
    private volatile MatchSink sink;
    private String resumeToken, playerId, name, url;
    private boolean guest, wanted;
    private int generation, attempts;
    private volatile int rtt = -1;
    private final Runnable pinger = this::ping;

    /** Last match_start / room / match_end / rating messages (screens read these when they open). */
    public volatile JSONObject room, matchStart, matchEnd, rating;

    public GameClient(Context c) {
        ctx = c.getApplicationContext();
    }

    public State state() { return state; }
    public String playerId() { return playerId; }
    public String name() { return name; }
    public boolean isGuest() { return guest; }
    public int rtt() { return rtt; }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public synchronized void setSink(MatchSink s) {
        sink = s;
        if (s != null) {
            while (!early.isEmpty()) {
                String[] m = early.poll();
                try {
                    s.onMatchMessage(m[0], new JSONObject(m[1]));
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Connect (or keep the current connection). guestName is used only when not signed in. */
    public void connect(String guestName) {
        String target = App.get().prefs().serverUrl();
        if (state != State.OFFLINE && target.equals(url)) return;
        disconnect();
        wanted = true;
        url = target;
        name = guestName;
        attempts = 0;
        open(State.CONNECTING);
    }

    public void disconnect() {
        wanted = false;
        generation++;
        ui.removeCallbacks(pinger);
        if (ws != null) ws.close(1000, "bye");
        ws = null;
        resumeToken = null;
        setState(State.OFFLINE, null);
    }

    private void open(State st) {
        int gen = ++generation;
        setState(st, null);
        Net.run(() -> {
            String token = null;
            if (Supa.configured() && Supa.signedIn(ctx)) {
                try {
                    token = Supa.token(ctx);
                } catch (Exception e) {
                    Log.w(TAG, "token refresh failed", e);
                }
            }
            String jwt = token;
            Request req;
            try {
                req = new Request.Builder().url(url.replaceFirst("^ws", "http")).build();
            } catch (Exception e) {
                ui.post(() -> setState(State.OFFLINE, "Địa chỉ game server không hợp lệ: " + url));
                return;
            }
            ws = http.newWebSocket(req, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket socket, Response response) {
                    if (gen != generation) return;
                    try {
                        JSONObject hello = new JSONObject().put("t", "hello").put("v", BuildConfig.PROTOCOL_VERSION)
                                .put("name", name == null ? "Guest" : name);
                        if (jwt != null) hello.put("token", jwt);
                        if (resumeToken != null) hello.put("resume", resumeToken);
                        socket.send(hello.toString());
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void onMessage(WebSocket socket, String text) {
                    if (gen == generation) receive(text);
                }

                @Override
                public void onClosed(WebSocket socket, int code, String reason) {
                    if (gen == generation) lost(code >= 4000 ? reason : null, code);
                }

                @Override
                public void onFailure(WebSocket socket, Throwable t, Response r) {
                    if (gen == generation) lost(null, 0);
                }
            });
        });
    }

    private void lost(String reason, int code) {
        ui.post(() -> {
            ui.removeCallbacks(pinger);
            boolean fatal = code == 4001 || code == 4002 || code == 4003;
            if (!wanted || fatal) {
                wanted = wanted && !fatal;
                setState(State.OFFLINE, reason != null ? reason : "Mất kết nối tới game server");
                return;
            }
            attempts++;
            if (attempts > 8) {
                setState(State.OFFLINE, "Không kết nối được game server (" + url + ")");
                return;
            }
            setState(State.RECONNECTING, null);
            long delay = Math.min(8000, 500L << Math.min(4, attempts));
            int gen = generation;
            ui.postDelayed(() -> {
                if (gen == generation && wanted) open(State.RECONNECTING);
            }, delay);
        });
    }

    private void receive(String text) {
        JSONObject m;
        try {
            m = new JSONObject(text);
        } catch (Exception e) {
            return;
        }
        String t = m.optString("t");
        switch (t) {
            case "snap":
            case "roster":
                deliverMatch(t, text, m);
                return;
            case "pong": {
                long sent = m.optLong("c");
                int r = (int) (System.currentTimeMillis() - sent);
                rtt = rtt < 0 ? r : (rtt * 3 + r) / 4;
                return;
            }
            case "welcome":
                resumeToken = m.optString("resume");
                playerId = m.optString("playerId");
                name = m.optString("name");
                guest = m.optBoolean("guest");
                attempts = 0;
                ui.post(() -> {
                    setState(State.ONLINE, null);
                    ui.removeCallbacks(pinger);
                    ui.postDelayed(pinger, 1000);
                });
                break;
            case "room":
                room = m.optJSONObject("room");
                break;
            case "left_room":
            case "kicked":
                room = null;
                break;
            case "match_start":
                matchStart = m;
                matchEnd = null;
                rating = null;
                synchronized (this) {
                    early.clear();
                }
                deliverMatch(t, text, m);
                break;
            case "match_end":
                matchEnd = m;
                deliverMatch(t, text, m);
                break;
            case "rating":
                rating = m;
                break;
            case "match_aborted":
                deliverMatch(t, text, m);
                break;
        }
        ui.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onMessage(t, m);
        });
    }

    private synchronized void deliverMatch(String t, String raw, JSONObject m) {
        MatchSink s = sink;
        if (s != null) s.onMatchMessage(t, m);
        else {
            early.add(new String[]{t, raw});
            while (early.size() > 120) early.poll();
        }
    }

    private void ping() {
        send(json("t", "ping", "c", System.currentTimeMillis(), "rtt", Math.max(0, rtt)));
        ui.postDelayed(pinger, 1000);
    }

    private void setState(State s, String error) {
        state = s;
        for (Listener l : new ArrayList<>(listeners)) l.onConnection(s, error);
    }

    public boolean send(JSONObject o) {
        WebSocket w = ws;
        return w != null && state != State.OFFLINE && w.send(o.toString());
    }

    /** Hot path: input packets are built by hand to avoid allocating JSONObjects 30 times a second. */
    public void sendInput(int seq, int mx, int my, int b) {
        WebSocket w = ws;
        if (w != null) w.send("{\"t\":\"in\",\"s\":" + seq + ",\"mx\":" + mx + ",\"my\":" + my + ",\"b\":" + b + "}");
    }

    public static JSONObject json(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (Exception ignored) {
        }
        return o;
    }
}
