package com.vault.app.ui;

import java.util.Collections;
import java.util.Set;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.vault.app.R;
import com.vault.app.data.Entry;

import java.util.ArrayList;
import java.util.List;

/** Shows one card per saved login. Never decrypts anything. */
public class EntryAdapter extends RecyclerView.Adapter<EntryAdapter.Holder> {

    public interface OnEntryClick {
        void onEntryClick(Entry entry);
    }

    private List<Entry> items = new ArrayList<>();
    private final OnEntryClick listener;
    private Set<String> reused = Collections.emptySet();

    public void setReused(Set<String> ids) {
        reused = ids;
    }

    public EntryAdapter(OnEntryClick listener) {
        this.listener = listener;
    }

    public void submit(List<Entry> list) {
        items = list;
        notifyDataSetChanged();
    }

    public int count() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_entry, parent, false);
        SpringTouch.attach(v);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        Context c = h.itemView.getContext();

        h.title.setText(e.label != null ? e.label : e.title);

        boolean hasSite = e.domain != null && !e.domain.isEmpty();
        boolean isApp = e.packageName != null && !e.packageName.isEmpty();
        String sub;
        if (hasSite) {
            sub = e.domain;
        } else if (isApp) {
            sub = e.label != null ? e.title + " · Android app" : "Android app";
        } else {
            sub = "No website";
        }
        h.sub.setText(sub);

        int colorRes;
        String status;
        if (e.breachCount > 0) {
            colorRes = R.color.danger;
            status = "Breached";
        } else if (e.score <= 1) {
            colorRes = R.color.danger;
            status = "Weak";
        } else if (reused.contains(e.id)) {
            colorRes = R.color.warn;
            status = "Reused";
        } else if (e.score == 2) {
            colorRes = R.color.warn;
            status = "Fair";
        } else {
            colorRes = R.color.ok;
            status = "";
        }
        int color = ContextCompat.getColor(c, colorRes);
        h.dot.getBackground().mutate().setTint(color);
        h.status.setText(status);
        h.status.setTextColor(color);

        h.itemView.setOnClickListener(v -> listener.onEntryClick(e));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final View dot;
        final TextView title, sub, status;

        Holder(View v) {
            super(v);
            dot = v.findViewById(R.id.dot);
            title = v.findViewById(R.id.tvTitle);
            sub = v.findViewById(R.id.tvSub);
            status = v.findViewById(R.id.tvStatus);
        }
    }
}