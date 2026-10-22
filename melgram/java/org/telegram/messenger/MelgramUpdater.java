package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LineProgressView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Melgram: checks update.json in the GitHub repository, downloads a newer APK
 * and opens the system installer. When the automatic build could not follow a
 * big Telegram update, tells the user whom to contact.
 */
public final class MelgramUpdater {

    private static final String PREFS = "melgram_updater";
    private static final long AUTO_INTERVAL = 6L * 60 * 60 * 1000;
    private static final long LATER_INTERVAL = 24L * 60 * 60 * 1000;
    private static final String APK_DIR = "cache";
    private static final String APK_NAME = "melgram_update.apk";

    private static volatile boolean checking;
    private static volatile boolean downloading;

    private MelgramUpdater() {
    }

    /** Called from LaunchActivity.onResume: quiet, at most every few hours. */
    public static void checkAuto(Activity activity) {
        AndroidUtilities.runOnUIThread(() -> check(activity, false), 3000);
    }

    /** Called from Settings: always checks and always reports the result. */
    public static void checkManual(Activity activity) {
        check(activity, true);
    }

    private static boolean alive(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    public static String versionText() {
        return MelgramBuild.VERSION + " (сборка " + MelgramBuild.BUILD + ")";
    }

    // ------------------------------------------------------------------ check

    private static void check(Activity activity, boolean manual) {
        if (!alive(activity) || checking || downloading) {
            return;
        }
        if (MelgramBuild.UPDATE_URL.isEmpty()) {
            if (manual) toast(activity, "В этой сборке нет адреса для обновлений");
            return;
        }
        final SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final long now = System.currentTimeMillis();
        if (!manual && now - prefs.getLong("last_check", 0) < AUTO_INTERVAL) {
            return;
        }
        prefs.edit().putLong("last_check", now).apply();
        checking = true;
        if (manual) toast(activity, "Проверяю обновления…");

        new Thread(() -> {
            JSONObject info = null;
            try {
                info = new JSONObject(httpGetString(MelgramBuild.UPDATE_URL + "?t=" + now));
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final JSONObject result = info;
            AndroidUtilities.runOnUIThread(() -> {
                checking = false;
                if (!alive(activity)) return;
                try {
                    onResult(activity, prefs, result, manual);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            });
        }, "melgram-update-check").start();
    }

    private static void onResult(Activity activity, SharedPreferences prefs, JSONObject info, boolean manual) {
        if (info == null) {
            if (manual) toast(activity, "Не получилось проверить обновления, попробуй позже");
            return;
        }
        int build = info.optInt("build", 0);
        String apkUrl = info.optString("apk_url", "");
        if (build > MelgramBuild.BUILD && !apkUrl.isEmpty()) {
            boolean postponed = prefs.getInt("later_build", -1) == build
                    && System.currentTimeMillis() - prefs.getLong("later_time", 0) < LATER_INTERVAL;
            if (manual || !postponed) {
                showUpdate(activity, prefs, info, build, apkUrl);
            }
            return;
        }
        JSONObject blocked = info.optJSONObject("blocked");
        if (blocked != null) {
            String key = blocked.optString("telegram_commit", blocked.optString("telegram_version", "?"));
            if (manual || !key.equals(prefs.getString("blocked_shown", ""))) {
                prefs.edit().putString("blocked_shown", key).apply();
                showBlocked(activity, blocked);
            }
            return;
        }
        if (manual) {
            toast(activity, "У тебя последняя версия Melgram " + versionText());
        }
    }

    // ------------------------------------------------------------------ dialogs

    private static AlertDialog.Builder dialog(Activity activity) {
        return new AlertDialog.Builder(activity);
    }

    private static void showUpdate(Activity activity, SharedPreferences prefs, JSONObject info, int build, String apkUrl) {
        String version = info.optString("version", "?");
        String text = "Вышла новая версия Melgram " + version + " (сборка " + build + ").\n\n"
                + "Сейчас установлена " + versionText() + ".\n\n"
                + "Аккаунты и переписка сохранятся.";
        dialog(activity)
                .setTitle("Доступно обновление")
                .setMessage(text)
                .setPositiveButton("Обновить", (d, w) -> {
                    try {
                        download(activity, apkUrl, build);
                    } catch (Throwable e) {
                        FileLog.e(e);
                        downloading = false;
                    }
                })
                .setNegativeButton("Позже", (d, w) -> prefs.edit()
                        .putInt("later_build", build)
                        .putLong("later_time", System.currentTimeMillis())
                        .apply())
                .show();
    }

    private static void showBlocked(Activity activity, JSONObject blocked) {
        String contact = blocked.optString("contact", MelgramBuild.CONTACT);
        String text = blocked.optString("message", "");
        if (text.isEmpty()) {
            text = "Вышла новая версия Telegram " + blocked.optString("telegram_version", "") + ", но в ней "
                    + "изменилось много функций, и Melgram не может обновиться автоматически.\n\n"
                    + "Для обновления напишите @" + contact + ".";
        }
        dialog(activity)
                .setTitle("Обновление невозможно")
                .setMessage(text)
                .setPositiveButton("Написать @" + contact, (d, w) -> openContact(activity, contact))
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private static void openContact(Activity activity, String contact) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=" + contact));
            intent.setPackage(activity.getPackageName());
            activity.startActivity(intent);
        } catch (Throwable e) {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/" + contact)));
            } catch (Throwable ignore) {
                toast(activity, "Напиши @" + contact + " в Telegram");
            }
        }
    }

    // ------------------------------------------------------------------ download

    private static File apkFile(Context context) {
        File dir = new File(context.getFilesDir(), APK_DIR);
        dir.mkdirs();
        return new File(dir, APK_NAME);
    }

    private static void download(Activity activity, String apkUrl, int build) {
        if (downloading) return;
        final SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final File target = apkFile(activity);
        if (prefs.getInt("downloaded_build", -1) == build && target.exists() && isOurApk(activity, target)) {
            install(activity, target);
            return;
        }

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(24);
        layout.setPadding(pad, AndroidUtilities.dp(8), pad, AndroidUtilities.dp(4));
        final LineProgressView bar = new LineProgressView(activity);
        bar.setProgressColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        bar.setBackColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2) & 0x33FFFFFF);
        layout.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(3)));
        final TextView label = new TextView(activity);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        label.setPadding(0, AndroidUtilities.dp(10), 0, 0);
        label.setText("Подключаюсь…");
        layout.addView(label);

        final boolean[] cancelled = {false};
        final AlertDialog progress = dialog(activity)
                .setTitle("Скачиваю Melgram")
                .setView(layout)
                .setNegativeButton("Отмена", (d, w) -> cancelled[0] = true)
                .show();
        progress.setCanCancel(false);
        progress.setCanceledOnTouchOutside(false);

        downloading = true;
        new Thread(() -> {
            String error = null;
            File tmp = new File(target.getParentFile(), APK_NAME + ".part");
            try {
                HttpURLConnection c = open(apkUrl);
                long total = c.getContentLength();
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0;
                    int lastPercent = -1;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelled[0]) throw new InterruptedException("cancelled");
                        out.write(buf, 0, n);
                        done += n;
                        final int percent = total > 0 ? (int) (done * 100 / total) : -1;
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            final long mb = done / (1024 * 1024);
                            AndroidUtilities.runOnUIThread(() -> {
                                if (percent >= 0) {
                                    bar.setProgress(percent / 100f, true);
                                    label.setText(percent + "% · " + mb + " МБ");
                                } else {
                                    label.setText(mb + " МБ");
                                }
                            });
                        }
                    }
                } finally {
                    c.disconnect();
                }
                if (target.exists()) target.delete();
                if (!tmp.renameTo(target)) throw new Exception("rename failed");
                if (!isOurApk(activity, target)) throw new Exception("это не APK Melgram");
                prefs.edit().putInt("downloaded_build", build).apply();
            } catch (Throwable e) {
                tmp.delete();
                error = cancelled[0] ? null : String.valueOf(e.getMessage());
                if (!cancelled[0]) FileLog.e(e);
            }
            final String finalError = error;
            final boolean wasCancelled = cancelled[0];
            AndroidUtilities.runOnUIThread(() -> {
                downloading = false;
                try {
                    progress.dismiss();
                } catch (Throwable ignore) {
                }
                if (!alive(activity) || wasCancelled) return;
                if (finalError != null) {
                    toast(activity, "Не получилось скачать обновление: " + finalError);
                } else {
                    install(activity, target);
                }
            });
        }, "melgram-update-download").start();
    }

    private static boolean isOurApk(Context context, File file) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
            return pi != null && context.getPackageName().equals(pi.packageName);
        } catch (Throwable e) {
            return false;
        }
    }

    private static void install(Activity activity, File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                toast(activity, "Разреши Melgram устанавливать приложения, вернись и нажми «Обновить» ещё раз");
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(settings);
                return;
            }
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".provider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Throwable e) {
            FileLog.e(e);
            toast(activity, "Не получилось открыть установку: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Melgram/" + MelgramBuild.VERSION);
        c.setRequestProperty("Cache-Control", "no-cache");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            c.disconnect();
            throw new Exception("HTTP " + code);
        }
        return c;
    }

    private static String httpGetString(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 256 * 1024) throw new Exception("too large");
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static void toast(Context context, String text) {
        try {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show();
        } catch (Throwable ignore) {
        }
    }
}
