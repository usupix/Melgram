package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.EmojiThemes;
import org.telegram.ui.ActionBar.Theme;

/**
 * Melgram: "theme from icon". Picks the most striking colour of the current
 * launcher icon and applies it as an accent of the current Telegram theme
 * (light or dark), re-using one Melgram accent instead of piling up new ones.
 */
public final class MelgramThemeTools {

    private static final int FALLBACK = 0xFF26A69A;

    private MelgramThemeTools() {
    }

    /** The background layer of the launcher icon that is enabled right now. */
    public static Bitmap currentIconBitmap(Context context) {
        try {
            for (LauncherIconController.LauncherIcon icon : LauncherIconController.LauncherIcon.values()) {
                if (LauncherIconController.isEnabled(icon)) {
                    Bitmap b = BitmapFactory.decodeResource(context.getResources(), icon.background);
                    if (b != null) return b;
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return BitmapFactory.decodeResource(context.getResources(), R.drawable.melgram_default_icon);
    }

    /** The most saturated, frequent colour of the picture, tuned to work as an accent. */
    public static int accentFrom(Bitmap source, boolean dark) {
        if (source == null) return FALLBACK;
        Bitmap small;
        try {
            small = Bitmap.createScaledBitmap(source, 64, 64, true);
        } catch (Throwable e) {
            return FALLBACK;
        }
        final int bins = 24;
        float[] weight = new float[bins];
        float[] sumR = new float[bins];
        float[] sumG = new float[bins];
        float[] sumB = new float[bins];
        float[] hsv = new float[3];
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int c = small.getPixel(x, y);
                if (Color.alpha(c) < 128) continue;
                Color.colorToHSV(c, hsv);
                if (hsv[1] < 0.25f || hsv[2] < 0.25f) continue;
                float w = hsv[1] * hsv[2];
                int bin = Math.min(bins - 1, (int) (hsv[0] / 360f * bins));
                weight[bin] += w;
                sumR[bin] += Color.red(c) * w;
                sumG[bin] += Color.green(c) * w;
                sumB[bin] += Color.blue(c) * w;
            }
        }
        int best = -1;
        for (int i = 0; i < bins; i++) {
            if (weight[i] > 0 && (best < 0 || weight[i] > weight[best])) best = i;
        }
        if (best < 0 || weight[best] < 4f) {
            return FALLBACK;
        }
        int color = Color.rgb(
                Math.round(sumR[best] / weight[best]),
                Math.round(sumG[best] / weight[best]),
                Math.round(sumB[best] / weight[best]));
        Color.colorToHSV(color, hsv);
        hsv[1] = clamp(hsv[1], 0.45f, 0.85f);
        hsv[2] = dark ? clamp(hsv[2], 0.65f, 0.9f) : clamp(hsv[2], 0.55f, 0.82f);
        return Color.HSVToColor(hsv);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Applies the colour to the current theme. @return false when the theme has no accents (custom file themes). */
    public static boolean apply(BaseFragment fragment, int color) {
        Theme.ThemeInfo theme = Theme.getActiveTheme();
        if (theme == null || theme.themeAccentsMap == null || theme.themeAccents == null) {
            return false;
        }
        boolean night = Theme.isCurrentThemeNight();
        SharedPreferences prefs = fragment.getParentActivity().getSharedPreferences("melgram_theme", Context.MODE_PRIVATE);
        String key = "accent_" + theme.getKey();
        Theme.ThemeAccent accent = theme.themeAccentsMap.get(prefs.getInt(key, -1));
        if (accent == null) {
            accent = theme.getAccent(true);
            if (accent == null) {
                return false;
            }
            prefs.edit().putInt(key, accent.id).apply();
        }
        accent.accentColor = color;
        accent.accentColor2 = 0;
        accent.myMessagesAccentColor = color;
        accent.myMessagesGradientAccentColor1 = 0;
        accent.myMessagesGradientAccentColor2 = 0;
        accent.myMessagesGradientAccentColor3 = 0;
        accent.myMessagesAnimated = false;
        Theme.saveThemeAccents(theme, true, false, false, false);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needSetDayNightTheme, theme, night, null, accent.id);
        try {
            EmojiThemes.saveCustomTheme(theme, accent.id);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return true;
    }
}
