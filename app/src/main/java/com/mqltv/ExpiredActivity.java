package com.mqltv;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Full-screen screen shown when subscription is expired.
 *
 * Optional: if you add drawable named "subscription_expired" (png/webp), it will be displayed.
 */
public class ExpiredActivity extends FragmentActivity {

    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private TextView clockView;

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            if (clockView != null) {
                String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                clockView.setText(time);
            }
            clockHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();

        // Jika admin sudah perpanjang, refresh status dan tutup layar expired.
        if (AuthPrefs.isLoggedIn(this)) {
            AccountStatusRefresher.refresh(this, () -> {
                if (!SubscriptionGuard.isExpired(ExpiredActivity.this)) {
                    // Kembali ke layar sebelumnya (grid), bukan paksa ke MainActivity.
                    finish();
                }
            });
        }

        // Mulai jam saat layar aktif kembali
        clockHandler.removeCallbacks(clockTick);
        clockHandler.post(clockTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Hentikan jam saat layar tidak terlihat (hemat CPU)
        clockHandler.removeCallbacks(clockTick);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_expired);

        // If user is not logged in, go back to login.
        if (!AuthPrefs.isLoggedIn(this)) {
            Intent i = new Intent(this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
            return;
        }

        clockView = findViewById(R.id.expired_clock);

        // Aktifkan marquee (harus selected = true agar berjalan)
        TextView marquee = findViewById(R.id.expired_marquee);
        if (marquee != null) {
            marquee.setSelected(true);
        }

        ImageView img = findViewById(R.id.expired_image);
        View content = findViewById(R.id.expired_content);

        int resId = getResources().getIdentifier("subscription_expired", "drawable", getPackageName());
        if (resId != 0 && img != null) {
            img.setImageResource(resId);
            img.setVisibility(View.VISIBLE);
            if (content != null) content.setVisibility(View.GONE);
        } else {
            if (img != null) img.setVisibility(View.GONE);
            if (content != null) content.setVisibility(View.VISIBLE);
        }

        Button btn = findViewById(R.id.expired_btn_refresh);
        if (btn != null) {
            btn.setOnClickListener(v -> {
                // Kembali ke layar sebelumnya (grid Live TV), bukan reset ke MainActivity.
                finish();
            });
        }

        // TV-friendly focus
        if (btn != null) btn.requestFocus();
    }

    @Override
    public void onBackPressed() {
        // Tombol back di remote TV → kembali ke layar sebelumnya.
        finish();
    }
}
