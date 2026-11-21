package com.shadowduel.arena.ui;

import android.content.Context;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shadowduel.arena.R;

import org.json.JSONObject;

/** Shared dialogs and Vietnamese messages for server error codes. */
public final class Dialogs {
    private Dialogs() {
    }

    public static void rules(Context c) {
        new MaterialAlertDialogBuilder(c).setTitle(R.string.rules).setMessage(R.string.rules_text)
                .setPositiveButton("OK", null).show();
    }

    public static String errorText(JSONObject m) {
        switch (m.optString("code")) {
            case "ROOM_NOT_FOUND": return "Không tìm thấy phòng với mã này.";
            case "ROOM_FULL": return "Phòng đã đủ người.";
            case "BAD_PASSWORD": return "Sai mật khẩu phòng.";
            case "MATCH_IN_PROGRESS": return "Trận đấu đã bắt đầu, chưa thể vào.";
            case "NOT_HOST": return "Chỉ chủ phòng mới làm được việc này.";
            case "NOT_ENOUGH_PLAYERS": return "Cần ít nhất 2 người (có thể thêm BOT).";
            case "NOT_READY": return "Chưa đủ người sẵn sàng. " + m.optString("message").replace("Waiting for:", "Đang chờ:");
            case "TEAMS_UNBALANCED": return "Hai đội phải có người và chênh lệch tối đa 1.";
            case "TEAM_FULL": return "Đội này đã đủ người.";
            case "PLAYER_DISCONNECTED": return "Có người chơi đang kết nối lại, đợi một chút.";
            case "AUTH_FAILED": return "Server từ chối đăng nhập: " + m.optString("message");
            case "VERSION_MISMATCH": return "Phiên bản app và server không khớp.";
            case "SERVER_FULL": return "Server đã đầy phòng.";
            default: return m.optString("message", m.optString("code"));
        }
    }

    public static void error(Context c, JSONObject m) {
        new MaterialAlertDialogBuilder(c).setTitle("Không thực hiện được").setMessage(errorText(m))
                .setPositiveButton("OK", null).show();
    }
}
