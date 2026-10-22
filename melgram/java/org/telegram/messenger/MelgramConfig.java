package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Melgram options. Almost every option is off by default, so Melgram behaves like
 * the official Telegram until the user turns something on in "Настройки Melgram".
 * The small hooks patched into Telegram's code call the static getters below.
 */
public final class MelgramConfig {

    public static final String PREFS = "melgram_config";

    // chats
    public static final String HIDE_STORIES = "hide_stories";
    public static final String TIME_SECONDS = "time_seconds";
    public static final String NO_JUMP_NEXT_CHANNEL = "no_jump_next_channel";
    public static final String ASK_BEFORE_CALL = "ask_before_call";
    public static final String SILENCE_NON_CONTACTS = "silence_non_contacts";
    public static final String NO_GREETING_STICKER = "no_greeting_sticker";
    public static final String HIDE_KEYBOARD_ON_SCROLL = "hide_keyboard_on_scroll";
    public static final String NO_NUMBER_ROUNDING = "no_number_rounding";
    public static final String SCROLL_TOP_BUTTON = "scroll_top_button"; // on by default
    // appearance
    public static final String NO_APPBAR_SHADOW = "no_appbar_shadow";
    public static final String NO_GOOEY_AVATAR = "no_gooey_avatar";
    public static final String NO_MEDIA_PREVIEW = "no_media_preview";
    public static final String NO_PREDICTIVE_BACK = "no_predictive_back";
    public static final String TABLET_MODE = "tablet_mode"; // int: 0 auto, 1 on, 2 off

    public static final int TABLET_AUTO = 0;
    public static final int TABLET_ON = 1;
    public static final int TABLET_OFF = 2;

    private static volatile SharedPreferences prefs;
    // hooks run on hot paths (every message bind), so values are cached in memory
    private static final ConcurrentHashMap<String, Boolean> cache = new ConcurrentHashMap<>();

    private static long askedCallUser;
    private static long askedCallTime;

    private MelgramConfig() {
    }

    private static SharedPreferences prefs() {
        SharedPreferences p = prefs;
        if (p == null) {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return null;
            }
            p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs = p;
        }
        return p;
    }

    private static boolean defaultValue(String key) {
        return SCROLL_TOP_BUTTON.equals(key);
    }

    public static boolean get(String key) {
        Boolean cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        boolean def = defaultValue(key);
        try {
            SharedPreferences p = prefs();
            if (p == null) {
                return def;
            }
            boolean value = p.getBoolean(key, def);
            cache.put(key, value);
            return value;
        } catch (Throwable e) {
            return def;
        }
    }

    public static void set(String key, boolean value) {
        cache.put(key, value);
        SharedPreferences p = prefs();
        if (p != null) {
            p.edit().putBoolean(key, value).apply();
        }
    }

    private static final ConcurrentHashMap<String, Integer> intCache = new ConcurrentHashMap<>();

    public static int getInt(String key, int def) {
        Integer cached = intCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            SharedPreferences p = prefs();
            if (p == null) {
                return def;
            }
            int value = p.getInt(key, def);
            intCache.put(key, value);
            return value;
        } catch (Throwable e) {
            return def;
        }
    }

    public static void setInt(String key, int value) {
        intCache.put(key, value);
        SharedPreferences p = prefs();
        if (p != null) {
            p.edit().putInt(key, value).apply();
        }
    }

    // ---------------------------------------------------------------- hooks

    public static boolean hideStories() {
        return get(HIDE_STORIES);
    }

    public static boolean timeWithSeconds() {
        return get(TIME_SECONDS);
    }

    public static boolean noJumpToNextChannel() {
        return get(NO_JUMP_NEXT_CHANNEL);
    }

    public static boolean silenceNonContacts() {
        return get(SILENCE_NON_CONTACTS);
    }

    public static boolean noGreetingSticker() {
        return get(NO_GREETING_STICKER);
    }

    public static boolean hideKeyboardOnScroll() {
        return get(HIDE_KEYBOARD_ON_SCROLL);
    }

    public static boolean noNumberRounding() {
        return get(NO_NUMBER_ROUNDING);
    }

    public static boolean scrollTopButton() {
        return get(SCROLL_TOP_BUTTON);
    }

    /** Per-account switch for Telegram's background ("push") connection. */
    public static boolean backgroundDisabled(int account) {
        return get("bg_off_" + account);
    }

    public static void setBackgroundDisabled(int account, boolean disabled) {
        set("bg_off_" + account, disabled);
    }

    private static final ThreadLocal<DecimalFormat> exactFormat = new ThreadLocal<DecimalFormat>() {
        @Override
        protected DecimalFormat initialValue() {
            DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
            symbols.setGroupingSeparator(',');
            DecimalFormat format = new DecimalFormat("#,##0", symbols);
            format.setGroupingUsed(true);
            return format;
        }
    };

    /** 34355 -> "34,355": exact numbers stay readable. */
    public static String formatExact(long number) {
        try {
            return exactFormat.get().format(number);
        } catch (Throwable e) {
            return String.valueOf(number);
        }
    }

    public static boolean noAppBarShadow() {
        return get(NO_APPBAR_SHADOW);
    }

    public static boolean noGooeyAvatar() {
        return get(NO_GOOEY_AVATAR);
    }

    public static boolean noMediaPreview() {
        return get(NO_MEDIA_PREVIEW);
    }

    public static boolean noPredictiveBack() {
        return get(NO_PREDICTIVE_BACK);
    }

    public static int tabletMode() {
        return getInt(TABLET_MODE, TABLET_AUTO);
    }

    /**
     * "Ask before calling": the first tap shows Telegram's own confirmation dialog;
     * its "Call" button calls startCall again within a few seconds and goes through.
     */
    public static synchronized boolean shouldAskBeforeCall(long userId) {
        if (!get(ASK_BEFORE_CALL)) {
            return false;
        }
        if (askedCallUser == userId && System.currentTimeMillis() - askedCallTime < 30_000) {
            askedCallUser = 0;
            return false;
        }
        return true;
    }

    public static synchronized void markCallAsked(long userId) {
        askedCallUser = userId;
        askedCallTime = System.currentTimeMillis();
    }

    /** Features whose hook could not be applied to this Telegram version. */
    public static boolean isAvailable(String key) {
        for (String disabled : MelgramBuild.DISABLED_FEATURES) {
            if (disabled.equals(key)) {
                return false;
            }
        }
        return true;
    }
}
