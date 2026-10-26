package com.vault.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.vault.app.MainActivity;
import com.vault.app.R;
import com.vault.app.crypto.AppLock;
import com.vault.app.crypto.BiometricHelper;

public class UnlockActivity extends AppCompatActivity {
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_unlock);

        View root = findViewById(R.id.unlockRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        status = findViewById(R.id.tvStatus);
        View btn = findViewById(R.id.btnUnlock);
        SpringTouch.attach(btn);
        btn.setOnClickListener(v -> promptUnlock());

        // Ask right away, but only on a fresh launch (not after rotation)
        if (savedInstanceState == null) root.post(this::promptUnlock);
    }

    private void promptUnlock() {
        if (!BiometricHelper.canAuthenticate(this)) {
            status.setText("Set a screen lock in Settings to use Vault.");
            return;
        }
        BiometricHelper.authenticate(this, "Unlock Vault", new BiometricHelper.Callback() {
            @Override
            public void onSuccess() {
                AppLock.unlock();
                startActivity(new Intent(UnlockActivity.this, MainActivity.class));
                finish();
            }

            @Override
            public void onFailure(String message) {
                status.setText("Still locked. Tap Unlock to try again.");
            }
        });
    }
}
