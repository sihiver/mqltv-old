package com.mqltv;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

/**
 * Full-screen screen shown when subscription is expired.
 *
 * Optional: if you add drawable named "subscription_expired" (png/webp), it will be displayed.
 */
public class ExpiredActivity extends FragmentActivity {

    @Override
    protected void onResume() {
        super.onResume();

        // If admin already renewed, refresh status and close expired screen.
        if (AuthPrefs.isLoggedIn(this)) {
            AccountStatusRefresher.refresh(this, () -> {
                if (!SubscriptionGuard.isExpired(ExpiredActivity.this)) {
                    Intent i = new Intent(ExpiredActivity.this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                }
            });
        }
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
                // Launcher behavior: go back to beranda (MainActivity).
                Intent i = new Intent(ExpiredActivity.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                finish();
            });
        }

        // TV-friendly focus
        if (btn != null) btn.requestFocus();
    }
}
