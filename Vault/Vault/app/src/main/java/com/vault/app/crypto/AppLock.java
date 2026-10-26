package com.vault.app.crypto;

import android.os.SystemClock;

import androidx.fragment.app.FragmentActivity;

/** Remembers whether the vault is unlocked, and makes sure the key is still usable. */
public final class AppLock {
    private static final long GRACE_MS = 30_000;
    /** Must stay below CryptoManager.AUTH_SECONDS (300 s) or the key may expire mid-action. */
    private static final long FRESH_MS = 240_000;

    private static boolean unlocked = false;
    private static long leftAt = 0;
    private static long authAt = 0;

    private AppLock() {}

    public static void unlock() {
        unlocked = true;
        leftAt = 0;
        authAt = SystemClock.elapsedRealtime();
    }

    /** Call from onStop of every screen that shows secrets. */
    public static void markLeft() {
        leftAt = SystemClock.elapsedRealtime();
    }

    /** Call from onStart of every screen that shows secrets. */
    public static boolean isLocked() {
        if (unlocked && leftAt != 0 && SystemClock.elapsedRealtime() - leftAt > GRACE_MS) {
            unlocked = false;
        }
        if (unlocked) leftAt = 0;
        return !unlocked;
    }

    /** True if the user unlocked recently enough that the Keystore key still works. */
    public static boolean isFresh() {
        return unlocked && SystemClock.elapsedRealtime() - authAt < FRESH_MS;
    }

    public static void requireFresh(FragmentActivity activity, Runnable ok) {
        requireFresh(activity, ok, null);
    }

    /** Runs `ok` right away if still fresh, otherwise asks for fingerprint/PIN first. */
    public static void requireFresh(FragmentActivity activity, Runnable ok, Runnable cancelled) {
        if (isFresh()) {
            ok.run();
            return;
        }
        BiometricHelper.authenticate(activity, "Confirm it's you", new BiometricHelper.Callback() {
            @Override
            public void onSuccess() {
                unlock();
                ok.run();
            }

            @Override
            public void onFailure(String message) {
                if (cancelled != null) cancelled.run();
            }
        });
    }
}