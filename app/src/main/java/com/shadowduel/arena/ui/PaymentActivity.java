package com.shadowduel.arena.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.shadowduel.arena.App;
import com.shadowduel.arena.R;
import com.shadowduel.arena.data.Net;
import com.shadowduel.arena.data.Supa;
import com.shadowduel.arena.databinding.ActivityPaymentBinding;

import org.json.JSONObject;

import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Gems top-up by bank QR. The app only sends the product id: the server reads the price,
 * creates the order and returns bank details + QR. The order becomes PAID only after the
 * server verifies SePay's webhook — never because the user pressed a button. The order id is
 * remembered, so the status can be re-checked after a lost connection or app restart.
 */
public class PaymentActivity extends AppCompatActivity {
    private ActivityPaymentBinding v;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String orderId, status = "";
    private long expiresAt;
    private Bitmap qr;
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (orderId != null && "PENDING".equals(status)) check(false);
            handler.postDelayed(this, 4000);
        }
    };
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long left = Math.max(0, expiresAt - System.currentTimeMillis()) / 1000;
            v.payTimer.setText("PENDING".equals(status) ? String.format(Locale.US, "%02d:%02d", left / 60, left % 60) : "");
            handler.postDelayed(this, 1000);
        }
    };

    static String api() {
        return App.get().prefs().serverUrl().replaceFirst("^ws", "http").replaceAll("/+$", "");
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        v = ActivityPaymentBinding.inflate(getLayoutInflater());
        setContentView(v.getRoot());
        v.payTitle.setText(getIntent().getStringExtra("title") != null ? getIntent().getStringExtra("title") : "Đơn nạp Gems");
        v.payStatus.setText("Đang tạo đơn…");
        v.payCopy.setOnClickListener(x -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("noi dung", v.payContent.getText()));
            Toast.makeText(this, "Đã sao chép nội dung chuyển khoản", Toast.LENGTH_SHORT).show();
        });
        v.paySave.setOnClickListener(x -> saveQr());
        v.payCheck.setOnClickListener(x -> check(true));
        v.payMock.setOnClickListener(x -> mockPay());
        orderId = getIntent().getStringExtra("orderId");
        if (orderId != null) check(true);
        else create(getIntent().getStringExtra("productId"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(poll);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poll);
        handler.removeCallbacks(tick);
    }

    private interface Result {
        void done(JSONObject body, String error);
    }

    private void http(String method, String path, JSONObject body, Result r) {
        Net.run(() -> {
            JSONObject out = null;
            String err = null;
            try {
                Map<String, String> h = new HashMap<>();
                h.put("Authorization", "Bearer " + Supa.token(this));
                h.put("Content-Type", "application/json");
                Net.Response res = Net.call(method, api() + path, h, body == null ? null : body.toString());
                out = new JSONObject(res.body.isEmpty() ? "{}" : res.body);
                if (res.code / 100 != 2) err = errorText(out.optString("error", "HTTP " + res.code));
            } catch (java.io.IOException e) {
                err = "Không kết nối được máy chủ thanh toán (" + api() + ")";
            } catch (Exception e) {
                err = e.getMessage() == null ? "Lỗi" : e.getMessage();
            }
            JSONObject o = out;
            String m = err;
            Net.ui(() -> r.done(o, m));
        });
    }

    private static String errorText(String code) {
        switch (code) {
            case "PAYMENTS_DISABLED": return "Nạp Gems đang tắt trên máy chủ này.";
            case "PROVIDER_NOT_ALLOWED_FOR_CHANNEL": return "Bản cài đặt này không hỗ trợ thanh toán chuyển khoản.";
            case "PROVIDER_NOT_CONFIGURED": return "Máy chủ chưa cấu hình cổng thanh toán.";
            case "TOO_MANY_PENDING_ORDERS": return "Bạn có quá nhiều đơn đang chờ. Hãy hoàn tất hoặc đợi đơn cũ hết hạn.";
            case "PRODUCT_NOT_AVAILABLE": return "Gói nạp không còn bán.";
            case "RATE_LIMITED": return "Thao tác quá nhanh, vui lòng đợi.";
            case "UNAUTHORIZED": return "Phiên đăng nhập hết hạn, hãy đăng nhập lại.";
            default: return code;
        }
    }

    private void create(String productId) {
        http("POST", "/api/shop/orders", com.shadowduel.arena.net.GameClient.json("productId", productId), (b, err) -> {
            if (err != null) {
                v.payStatus.setText(err);
                v.payQrHint.setText(err);
                return;
            }
            render(b);
        });
    }

    private void check(boolean manual) {
        http("GET", "/api/shop/orders/" + orderId, null, (b, err) -> {
            if (err != null) {
                if (manual) v.payStatus.setText(err);
                return;
            }
            render(b);
        });
    }

    private void mockPay() {
        http("POST", "/api/payments/mock/" + orderId + "/pay", null, (b, err) -> {
            if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show();
            check(true);
        });
    }

    private void render(JSONObject b) {
        JSONObject o = b.optJSONObject("order"), pay = b.optJSONObject("payment");
        if (o == null) return;
        String old = status;
        orderId = o.optString("id");
        status = o.optString("status");
        expiresAt = com.shadowduel.arena.data.Economy.parseTime(o.optString("expiresAt"));
        v.payAmount.setText(String.format(Locale.US, "%,d đ  →  %d Gems", o.optLong("amountVnd"), o.optInt("gems")).replace(',', '.'));
        v.payContent.setText(o.optString("code"));
        if (pay != null) {
            v.payBank.setText("Ngân hàng: " + pay.optString("bank") + "\nSố tài khoản: " + pay.optString("accountNumber")
                    + (pay.optString("accountName").isEmpty() ? "" : "\nChủ tài khoản: " + pay.optString("accountName")));
            v.payMock.setVisibility("mock".equals(pay.optString("provider")) && "PENDING".equals(status) ? View.VISIBLE : View.GONE);
            String url = pay.optString("qrUrl");
            if (!url.isEmpty() && qr == null) loadQr(url);
            else if (url.isEmpty()) v.payQrHint.setText(pay.optString("note", "Không có mã QR"));
        }
        switch (status) {
            case "PAID":
                v.payStatus.setText("✔ THANH TOÁN THÀNH CÔNG · +" + o.optInt("gems") + " GEMS");
                v.payStatus.setTextColor(getColor(R.color.accent_green));
                v.payMock.setVisibility(View.GONE);
                if (!"PAID".equals(old)) {
                    v.payStatus.animate().scaleX(1.15f).scaleY(1.15f).setDuration(200).withEndAction(() ->
                            v.payStatus.animate().scaleX(1).scaleY(1).setDuration(300).start()).start();
                }
                break;
            case "PENDING":
                v.payStatus.setText("Đang chờ chuyển khoản… (tự cập nhật)");
                break;
            case "NEEDS_REVIEW":
                v.payStatus.setText("Đã nhận tiền nhưng cần đối soát (sai số tiền / hết hạn). Quản trị viên sẽ xử lý.");
                v.payStatus.setTextColor(getColor(R.color.accent_yellow));
                break;
            default:
                v.payStatus.setText("Đơn " + status + (status.equals("EXPIRED") ? " — hãy tạo đơn mới" : ""));
                v.payStatus.setTextColor(getColor(R.color.text_secondary));
        }
    }

    private void loadQr(String url) {
        v.payQrHint.setText("Đang tải mã QR…");
        Net.run(() -> {
            Bitmap bm = null;
            try (okhttp3.Response r = Net.HTTP.newCall(new okhttp3.Request.Builder().url(url).build()).execute()) {
                if (r.isSuccessful() && r.body() != null) bm = BitmapFactory.decodeStream(r.body().byteStream());
            } catch (Exception ignored) {
            }
            Bitmap res = bm;
            Net.ui(() -> {
                qr = res;
                if (res != null) {
                    v.payQr.setImageBitmap(res);
                    v.payQrHint.setText("");
                } else v.payQrHint.setText("Không tải được QR. Hãy chuyển khoản thủ công theo thông tin bên trái.");
            });
        });
    }

    private void saveQr() {
        if (qr == null) return;
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "shadowduel_" + v.payContent.getText() + ".png");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                qr.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            Toast.makeText(this, "Đã lưu QR vào Ảnh", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Không lưu được ảnh", Toast.LENGTH_SHORT).show();
        }
    }
}
