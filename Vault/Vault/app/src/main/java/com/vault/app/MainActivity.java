package com.vault.app;

import com.vault.app.ui.SecurityActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.vault.app.ui.DetailActivity;

import com.vault.app.crypto.AppLock;
import com.vault.app.data.VaultRepository;
import com.vault.app.ui.EntryAdapter;
import com.vault.app.ui.EntrySheet;
import com.vault.app.ui.SpringTouch;
import com.vault.app.ui.UnlockActivity;

public class MainActivity extends AppCompatActivity implements EntrySheet.Listener {
    private EntryAdapter adapter;
    private TextView tvCount, tvEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        tvCount = findViewById(R.id.tvCount);
        tvEmpty = findViewById(R.id.tvEmpty);

        adapter = new EntryAdapter(entry -> {
            Intent i = new Intent(this, DetailActivity.class);
            i.putExtra(DetailActivity.EXTRA_ID, entry.id);
            startActivity(i);
        });
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        View add = findViewById(R.id.btnAdd);
        SpringTouch.attach(add);
        add.setOnClickListener(v ->
                EntrySheet.newInstance(null).show(getSupportFragmentManager(), "entry"));
        View sec = findViewById(R.id.btnSecurity);
        SpringTouch.attach(sec);
        sec.setOnClickListener(v -> startActivity(new Intent(this, SecurityActivity.class)));
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (AppLock.isLocked()) {
            startActivity(new Intent(this, UnlockActivity.class));
            finish();
            return;
        }
        refresh();
        if (AppLock.isFresh()) VaultRepository.get(this).backfillHints();
        VaultRepository.get(this).enrichDomains(this::refresh);
    }

    @Override
    protected void onStop() {
        super.onStop();
        AppLock.markLeft();
    }

    @Override
    public void onEntriesChanged() {
        refresh();
    }

    private void refresh() {
        VaultRepository.get(this).list((all, err) -> {
            if (err != null) { toast("Couldn't load: " + err); return; }
            adapter.submit(all);
            tvCount.setText(all.size() == 1 ? "1 login" : all.size() + " logins");
            tvEmpty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}