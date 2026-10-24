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
    public static final String PROFILE_ID = "profile_id"; // on by default
    // ghost mode
    public static final String GHOST_READ = "ghost_read";
    public static final String GHOST_TYPING = "ghost_typing";
    public static final String GHOST_OFFLINE = "ghost_offline";
    public static final String GHOST_STORIES = "ghost_stories";
    public static final String GHOST_READ_AFTER_SEND = "ghost_read_after_send"; // on by default
    public static final String[] GHOST_KEYS = {GHOST_READ, GHOST_TYPING, GHOST_OFFLINE, GHOST_STORIES};
    // quiet hours
    public static final String QUIET_ENABLED = "quiet_enabled";
    public static final String QUIET_FROM = "quiet_from"; // minutes since midnight
    public static final String QUIET_TO = "quiet_to";
    // double tap
    public static final String TAP_IN = "tap_in";
    public static final String TAP_OUT = "tap_out";
    public static final int TAP_REACTION = 0;
    public static final int TAP_REPLY = 1;
    public static final int TAP_COPY = 2;
    public static final int TAP_EDIT = 3;
    public static final int TAP_NONE = 4;
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
        return SCROLL_TOP_BUTTON.equals(key) || GHOST_READ_AFTER_SEND.equals(key) || PROFILE_ID.equals(key);
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

    public static boolean showProfileId() {
        return get(PROFILE_ID);
    }

    public static boolean scrollTopButton() {
        return get(SCROLL_TOP_BUTTON);
    }

    // ---------------------------------------------------------------- ghost mode

    public static boolean ghost(String key) {
        return get(key);
    }

    public static boolean ghostAny() {
        for (String k : GHOST_KEYS) {
            if (get(k)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- double tap

    public static int doubleTapAction(boolean outgoing) {
        return getInt(outgoing ? TAP_OUT : TAP_IN, TAP_REACTION);
    }

    // ---------------------------------------------------------------- forward without author

    private static volatile boolean forwardNoQuote;
    private static volatile long forwardNoQuoteTime;
    private static volatile boolean panelNoQuote;

    public static void requestForwardNoQuote() {
        forwardNoQuote = true;
        forwardNoQuoteTime = System.currentTimeMillis();
    }

    public static void clearForwardNoQuote() {
        forwardNoQuote = false;
    }

    /** Returns and clears the "forward without author" request (valid for 10 minutes). */
    public static boolean takeForwardNoQuote() {
        boolean value = forwardNoQuote && System.currentTimeMillis() - forwardNoQuoteTime < 10 * 60_000L;
        forwardNoQuote = false;
        return value;
    }

    public static void setPanelNoQuote(boolean value) {
        panelNoQuote = value;
    }

    public static boolean takePanelNoQuote() {
        boolean value = panelNoQuote;
        panelNoQuote = false;
        return value;
    }

    // ---------------------------------------------------------------- quiet hours

    public static boolean quietEnabled() {
        return get(QUIET_ENABLED);
    }

    public static int quietFrom() {
        return getInt(QUIET_FROM, 23 * 60);
    }

    public static int quietTo() {
        return getInt(QUIET_TO, 8 * 60);
    }

    /** Accounts take part in quiet hours unless excluded. */
    public static boolean quietForAccount(int account) {
        return !get("quiet_skip_" + account);
    }

    public static void setQuietForAccount(int account, boolean value) {
        set("quiet_skip_" + account, !value);
    }

    public static boolean inQuietHours(int account) {
        if (!quietEnabled() || !quietForAccount(account)) {
            return false;
        }
        MelgramQuietHours.ensureTicker();
        java.util.Calendar c = java.util.Calendar.getInstance();
        int now = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        int from = quietFrom();
        int to = quietTo();
        if (from == to) {
            return false;
        }
        return from < to ? now >= from && now < to : now >= from || now < to;
    }

    public static String formatMinutes(int minutes) {
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** Background connection is off for this account: by its own switch or by quiet hours. */
    public static boolean backgroundOff(int account) {
        return backgroundDisabled(account) || inQuietHours(account);
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
