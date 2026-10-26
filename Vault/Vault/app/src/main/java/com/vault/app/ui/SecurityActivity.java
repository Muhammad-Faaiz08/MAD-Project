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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.vault.app.R;
import com.vault.app.crypto.AppLock;
import com.vault.app.data.Entry;
import com.vault.app.data.VaultRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shows which logins are weak, breached or reused, and re-checks them all on request. */
public class SecurityActivity extends AppCompatActivity implements EntrySheet.Listener {
    private EntryAdapter adapter;
    private TextView statWeak, statBreached, statReused, tvProgress, tvAllGood;
    private MaterialButton btnCheck;
    private Set<String> reused = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_security);

        View root = findViewById(R.id.secRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        statWeak = findViewById(R.id.statWeak);
        statBreached = findViewById(R.id.statBreached);
        statReused = findViewById(R.id.statReused);
        tvProgress = findViewById(R.id.tvProgress);
        tvAllGood = findViewById(R.id.tvAllGood);

        adapter = new EntryAdapter(entry -> {
            Intent i = new Intent(this, DetailActivity.class);
            i.putExtra(DetailActivity.EXTRA_ID, entry.id);
            startActivity(i);
        });
        RecyclerView list = findViewById(R.id.secList);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        btnCheck = findViewById(R.id.btnCheck);
        SpringTouch.attach(btnCheck);
        btnCheck.setOnClickListener(v -> runCheck());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (AppLock.isLocked()) {
            startActivity(new Intent(this, UnlockActivity.class));
            finish();
            return;
        }
        load();
    }

    @Override
    protected void onStop() {
        super.onStop();
        AppLock.markLeft();
    }

    @Override
    public void onEntriesChanged() {
        // A login was saved or deleted: work out again which passwords are still shared
        AppLock.requireFresh(this, () ->
                VaultRepository.get(this).findReused((set, err) -> {
                    if (err == null) reused = set;
                    load();
                }), this::load);
    }

    private void load() {
        VaultRepository.get(this).list((all, err) -> {
            if (err != null) return;
            int weak = 0, breached = 0;
            List<Entry> flagged = new ArrayList<>();
            for (Entry e : all) {
                boolean isBreached = e.breachCount > 0;
                boolean isWeak = e.score <= 1;
                if (isBreached) breached++;
                if (isWeak) weak++;
                if (isBreached || isWeak || reused.contains(e.id)) flagged.add(e);
            }
            statWeak.setText(String.valueOf(weak));
            statBreached.setText(String.valueOf(breached));
            statReused.setText(String.valueOf(reused.size()));
            adapter.setReused(reused);
            adapter.submit(flagged);
            tvAllGood.setVisibility(flagged.isEmpty() && !all.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    private void runCheck() {
        btnCheck.setEnabled(false);
        AppLock.requireFresh(this, () -> {
            tvProgress.setText("Checking…");
            VaultRepository.get(this).recheckAll(
                    (done, total) -> tvProgress.setText("Checking " + done + " of " + total + "…"),
                    (set, err) -> {
                        btnCheck.setEnabled(true);
                        if (err != null) {
                            tvProgress.setText("Check failed: " + err.getMessage());
                            return;
                        }
                        reused = set;
                        tvProgress.setText("All passwords checked.");
                        load();
                    });
        }, () -> btnCheck.setEnabled(true));
    }
}