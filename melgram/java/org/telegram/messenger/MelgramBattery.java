package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import java.util.Calendar;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Melgram: battery insight and the low-battery saver.
 *
 * Counts the updates every account receives from Telegram while Melgram is in
 * the background (each one wakes the phone up), and turns background
 * connections off when the battery drops below the chosen level.
 */
public final class MelgramBattery {

    private static final String PREFS = "melgram_battery";
    private static final int FLUSH_EVERY = 50;

    private static final AtomicLongArray background = new AtomicLongArray(UserConfig.MAX_ACCOUNT_COUNT);
    private static final AtomicLongArray foreground = new AtomicLongArray(UserConfig.MAX_ACCOUNT_COUNT);
    private static volatile int day = -1;
    private static volatile long sinceFlush;
    private static volatile boolean loaded;

    private static volatile boolean lowBattery;
    private static volatile long lowBatteryCheckedAt;

    private MelgramBattery() {
    }

    // ---------------------------------------------------------------- update counter

    /** Called by ConnectionsManager for every update pushed by the server. */
    public static void onUpdate(int account) {
        try {
            if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return;
            ensureLoaded();
            int today = today();
            if (today != day) {
                synchronized (MelgramBattery.class) {
                    if (today != day) {
                        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
                            background.set(i, 0);
                            foreground.set(i, 0);
                        }
                        day = today;
                    }
                }
            }
            if (ApplicationLoader.mainInterfacePaused) {
                background.incrementAndGet(account);
            } else {
                foreground.incrementAndGet(account);
            }
            if (++sinceFlush >= FLUSH_EVERY) {
                sinceFlush = 0;
                flush();
            }
        } catch (Throwable ignore) {
        }
    }

    public static long backgroundToday(int account) {
        ensureLoaded();
        return day == today() ? background.get(account) : 0;
    }

    public static long foregroundToday(int account) {
        ensureLoaded();
        return day == today() ? foreground.get(account) : 0;
    }

    private static int today() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
    }

    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (MelgramBattery.class) {
            if (loaded) return;
            loaded = true;
            try {
                Context c = ApplicationLoader.applicationContext;
                if (c == null) return;
                SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                int savedDay = p.getInt("day", -1);
                if (savedDay == today()) {
                    for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
                        background.set(i, p.getLong("bg_" + i, 0));
                        foreground.set(i, p.getLong("fg_" + i, 0));
                    }
                }
                day = today();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    public static void flush() {
        try {
            Context c = ApplicationLoader.applicationContext;
            if (c == null) return;
            SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            e.putInt("day", day);
            for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
                e.putLong("bg_" + i, background.get(i));
                e.putLong("fg_" + i, foreground.get(i));
            }
            e.apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ---------------------------------------------------------------- battery state

    public static int batteryLevel() {
        try {
            BatteryManager bm = (BatteryManager) ApplicationLoader.applicationContext.getSystemService(Context.BATTERY_SERVICE);
            return bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
        } catch (Throwable e) {
            return -1;
        }
    }

    public static boolean isCharging() {
        try {
            BatteryManager bm = (BatteryManager) ApplicationLoader.applicationContext.getSystemService(Context.BATTERY_SERVICE);
            return bm != null && Build.VERSION.SDK_INT >= 23 && bm.isCharging();
        } catch (Throwable e) {
            return false;
        }
    }

    /** Re-reads the battery; called by the once-a-minute ticker. */
    public static void refreshLowBattery() {
        if (!MelgramConfig.get(MelgramConfig.LOWBAT_ENABLED)) {
            lowBattery = false;
            return;
        }
        int level = batteryLevel();
        lowBattery = level >= 0 && level <= MelgramConfig.lowBatteryLevel() && !isCharging();
        lowBatteryCheckedAt = System.currentTimeMillis();
    }

    /** Low-battery saver is on and the battery is low right now (value cached for a minute). */
    public static boolean lowBatteryActive() {
        if (!MelgramConfig.get(MelgramConfig.LOWBAT_ENABLED)) {
            return false;
        }
        MelgramQuietHours.ensureTicker();
        if (System.currentTimeMillis() - lowBatteryCheckedAt > 60_000) {
            refreshLowBattery();
        }
        return lowBattery;
    }

    public static boolean isIgnoringOptimizations() {
        try {
            if (Build.VERSION.SDK_INT < 23) return true;
            PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ApplicationLoader.applicationContext.getPackageName());
        } catch (Throwable e) {
            return false;
        }
    }

    /** Telegram's "Keep-Alive Service" (same rule as ApplicationLoader.startPushService). */
    public static boolean keepAliveService() {
        try {
            SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
            if (preferences.contains("pushService")) {
                return preferences.getBoolean("pushService", true);
            }
            return MessagesController.getMainSettings(UserConfig.selectedAccount).getBoolean("keepAliveService", false);
        } catch (Throwable e) {
            return false;
        }
    }

    /** Telegram's global "Background connection" (same rule as ConnectionsManager). */
    public static boolean backgroundConnection() {
        try {
            SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
            if (preferences.contains("pushConnection")) {
                return preferences.getBoolean("pushConnection", true);
            }
            return MessagesController.getMainSettings(UserConfig.selectedAccount).getBoolean("backgroundConnection", false);
        } catch (Throwable e) {
            return true;
        }
    }
}
