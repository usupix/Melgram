package org.telegram.ui;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/** Melgram: extra items in the message menu of a chat. */
public final class MelgramChatTools {

    public static final int OPTION_FORWARD_NOQUOTE = 9101;
    public static final int OPTION_SAVE_STICKER = 9102;
    public static final int OPTION_SAVE_ROUND = 9103;

    private MelgramChatTools() {
    }

    /** Called right after Telegram adds its own "Forward" item. */
    public static void addOptions(MessageObject message, boolean canForward, boolean noforwards,
                                  ArrayList<CharSequence> items, ArrayList<Integer> options, ArrayList<Integer> icons) {
        try {
            if (message == null) {
                return;
            }
            if (canForward && !noforwards) {
                items.add("Переслать без автора");
                options.add(OPTION_FORWARD_NOQUOTE);
                icons.add(R.drawable.msg_forward);
            }
            if (noforwards) {
                return;
            }
            if (isStaticSticker(message)) {
                items.add("Сохранить стикер в галерею");
                options.add(OPTION_SAVE_STICKER);
                icons.add(R.drawable.msg_gallery);
            } else if (message.isRoundVideo() && !message.isRoundOnce() && !options.contains(ChatActivity.OPTION_SAVE_TO_GALLERY)) {
                items.add("Сохранить кружок в галерею");
                options.add(OPTION_SAVE_ROUND);
                icons.add(R.drawable.msg_gallery);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static boolean isStaticSticker(MessageObject message) {
        return message.isSticker() && !message.isAnimatedSticker() && !message.isVideoSticker();
    }

    /** @return true when the option was handled here (the menu must then be closed). */
    public static boolean handleOption(ChatActivity fragment, int option, MessageObject message) {
        if (option != OPTION_SAVE_STICKER && option != OPTION_SAVE_ROUND) {
            return false;
        }
        Activity activity = fragment.getParentActivity();
        if (activity == null || message == null) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28
                && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4);
            return true;
        }
        File file = findFile(fragment.getCurrentAccount(), message);
        if (file == null) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, "Файл ещё не загружен — открой его и попробуй снова").show();
            return true;
        }
        if (option == OPTION_SAVE_ROUND) {
            MediaController.saveFile(file.getAbsolutePath(), activity, 1, null, null,
                    uri -> AndroidUtilities.runOnUIThread(() -> savedBulletin(fragment, "Кружок сохранён в галерею")));
            return true;
        }
        final File source = file;
        new Thread(() -> {
            File png = null;
            try {
                Bitmap bitmap = BitmapFactory.decodeFile(source.getAbsolutePath());
                if (bitmap != null) {
                    File dir = activity.getExternalCacheDir() != null ? activity.getExternalCacheDir() : activity.getCacheDir();
                    png = new File(dir, "melgram_sticker_" + message.getId() + "_" + System.currentTimeMillis() + ".png");
                    try (FileOutputStream out = new FileOutputStream(png)) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
                png = null;
            }
            final File result = png;
            AndroidUtilities.runOnUIThread(() -> {
                if (result == null) {
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, "Не получилось сохранить стикер").show();
                    return;
                }
                MediaController.saveFile(result.getAbsolutePath(), activity, 0, null, "image/png",
                        uri -> AndroidUtilities.runOnUIThread(() -> {
                            savedBulletin(fragment, "Стикер сохранён в галерею");
                            result.delete();
                        }));
            });
        }, "melgram-sticker").start();
        return true;
    }

    private static void savedBulletin(ChatActivity fragment, String text) {
        try {
            if (fragment.getParentActivity() != null) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.done, text).show();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static File findFile(int account, MessageObject message) {
        try {
            String path = message.messageOwner != null ? message.messageOwner.attachPath : null;
            if (!TextUtils.isEmpty(path)) {
                File f = new File(path);
                if (f.exists()) return f;
            }
            File f = FileLoader.getInstance(account).getPathToMessage(message.messageOwner);
            if (f != null && f.exists()) return f;
            if (message.getDocument() != null) {
                f = FileLoader.getInstance(account).getPathToAttach(message.getDocument(), true);
                if (f != null && f.exists()) return f;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }
}
