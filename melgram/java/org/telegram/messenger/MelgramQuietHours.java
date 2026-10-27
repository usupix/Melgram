package org.telegram.messenger;

import android.os.Handler;
import android.os.Looper;

import org.telegram.tgnet.ConnectionsManager;

/**
 * Melgram: quiet hours and the low-battery saver. While quiet hours are on, the chosen accounts drop their
 * background connection and their notifications come without sound. A light
 * once-a-minute ticker re-applies the background connection when the window
 * starts or ends (only while Melgram's process is alive; a restarted process
 * reads the right state on start).
 */
public final class MelgramQuietHours {

    private static final long TICK_MS = 60_000;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Boolean[] lastState = new Boolean[UserConfig.MAX_ACCOUNT_COUNT];
    private static volatile boolean running;

    private static final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                MelgramBattery.refreshLowBattery();
                MelgramBattery.flush();
                applyAll(false);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (needed()) {
                handler.postDelayed(this, TICK_MS);
            } else {
                running = false;
            }
        }
    };

    private MelgramQuietHours() {
    }

    private static boolean needed() {
        return MelgramConfig.quietEnabled() || MelgramConfig.get(MelgramConfig.LOWBAT_ENABLED);
    }

    public static void ensureTicker() {
        if (running || !needed()) {
            return;
        }
        running = true;
        handler.postDelayed(tick, TICK_MS);
    }

    /** Re-applies the background connection of every account whose quiet state changed. */
    public static void applyAll(boolean force) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                if (!UserConfig.getInstance(a).isClientActivated()) {
                    continue;
                }
                boolean quiet = MelgramConfig.backgroundOff(a);
                if (force || lastState[a] == null || lastState[a] != quiet) {
                    lastState[a] = quiet;
                    ConnectionsManager cm = ConnectionsManager.getInstance(a);
                    cm.setPushConnectionEnabled(cm.isPushConnectionEnabled());
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }
}
