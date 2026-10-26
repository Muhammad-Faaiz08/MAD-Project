package com.vault.app.tools;

import android.os.Handler;
import android.os.Looper;

import com.vault.app.data.VaultRepository;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Finds a website's password-change page using the standard /.well-known/change-password address. */
public final class ChangePasswordLink {

    public static final class Result {
        public final String url;
        public final boolean specific; // true = we found the site's real change-password page

        Result(String url, boolean specific) {
            this.url = url;
            this.specific = specific;
        }
    }

    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ChangePasswordLink() {}

    public static void resolve(String domain, VaultRepository.Callback<Result> cb) {
        NET.execute(() -> {
            Result r = check(domain);
            MAIN.post(() -> cb.onDone(r, null));
        });
    }

    private static Result check(String domain) {
        String home = "https://" + domain + "/";
        if (!domain.matches("[a-z0-9.-]+")) return new Result(home, false);
        try {
            // Some sites answer "200 OK" for every address, so test a page that must not exist first
            String canary = "https://" + domain
                    + "/.well-known/resource-that-should-not-exist-whose-status-code-should-not-be-200/";
            if (fetch(canary, null) == 200) return new Result(home, false);

            String[] landed = new String[1];
            if (fetch("https://" + domain + "/.well-known/change-password", landed) == 200) {
                return new Result(landed[0], true);
            }
        } catch (Exception ignored) {
            // offline, timeout, bad certificate: fall back to the homepage below
        }
        return new Result(home, false);
    }

    /** Returns the HTTP status after following redirects, and the address we ended up on. */
    private static int fetch(String address, String[] finalUrl) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "VaultApp");
        try {
            int code = c.getResponseCode();
            if (finalUrl != null) finalUrl[0] = c.getURL().toString();
            return code;
        } finally {
            c.disconnect();
        }
    }
}
