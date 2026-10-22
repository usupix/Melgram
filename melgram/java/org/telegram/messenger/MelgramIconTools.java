package org.telegram.messenger;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

/**
 * Melgram: turns any picture into an adaptive launcher icon and pins it to the
 * home screen as a Melgram shortcut (Android does not let an app replace its own
 * launcher icon with an arbitrary picture).
 */
public final class MelgramIconTools {

    public static final int ICON_SIZE = 432;    // 108dp at xxxhdpi, full adaptive-icon canvas
    public static final int SCALE_MIN = 60;     // percent; at ~67% the whole picture fits the mask
    private static final int SOURCE_SIZE = 1024;
    private static final String SOURCE_FILE = "melgram_icon_source.png";
    private static final String SHORTCUT_ID = "melgram_custom_icon";
    private static final String PREFS = "melgram_icon";

    public static final int PIN_REQUESTED = 0;
    public static final int PIN_UPDATED = 1;
    public static final int PIN_UNSUPPORTED = 2;

    private MelgramIconTools() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int getScale(Context context) {
        return Math.max(SCALE_MIN, Math.min(100, prefs(context).getInt("scale", 100)));
    }

    public static void setScale(Context context, int percent) {
        prefs(context).edit().putInt("scale", percent).apply();
    }

    // ---------------------------------------------------------------- source picture

    public static Bitmap loadSource(Context context) {
        File f = new File(context.getFilesDir(), SOURCE_FILE);
        Bitmap b = null;
        if (f.exists()) {
            try {
                b = BitmapFactory.decodeFile(f.getAbsolutePath());
            } catch (Throwable ignore) {
            }
        }
        if (b == null) {
            b = BitmapFactory.decodeResource(context.getResources(), R.drawable.melgram_default_icon);
        }
        return b;
    }

    public static void resetSource(Context context) {
        new File(context.getFilesDir(), SOURCE_FILE).delete();
        setScale(context, 100);
    }

    public static void saveSource(Context context, Bitmap b) throws IOException {
        File tmp = new File(context.getFilesDir(), SOURCE_FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        File dst = new File(context.getFilesDir(), SOURCE_FILE);
        if (!tmp.renameTo(dst)) {
            throw new IOException("rename failed");
        }
    }

    /** Decodes a picture from the gallery, fixes rotation and crops it to a square. */
    public static Bitmap decodeSquare(Context context, Uri uri) throws IOException {
        Bitmap bmp;
        if (Build.VERSION.SDK_INT >= 28) {
            bmp = Api28.decode(context, uri, SOURCE_SIZE);
        } else {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, SOURCE_SIZE);
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp != null && Build.VERSION.SDK_INT >= 24) {
                bmp = applyExifRotation(context, uri, bmp);
            }
        }
        if (bmp == null) {
            throw new IOException("decode failed");
        }
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int s = Math.min(w, h);
        Bitmap sq = Bitmap.createBitmap(bmp, (w - s) / 2, (h - s) / 2, s, s);
        if (s > SOURCE_SIZE) {
            sq = Bitmap.createScaledBitmap(sq, SOURCE_SIZE, SOURCE_SIZE, true);
        }
        if (sq.getConfig() != Bitmap.Config.ARGB_8888) {
            sq = sq.copy(Bitmap.Config.ARGB_8888, false);
        }
        return sq;
    }

    private static Bitmap applyExifRotation(Context context, Uri uri, Bitmap bmp) {
        int degrees = 0;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return bmp;
            android.media.ExifInterface exif = new android.media.ExifInterface(in);
            int o = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
            if (o == android.media.ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
            else if (o == android.media.ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
            else if (o == android.media.ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
        } catch (Throwable ignore) {
        }
        if (degrees == 0) return bmp;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
    }

    static int sampleSize(int w, int h, int target) {
        int min = Math.min(w, h);
        int sample = 1;
        while (min > 0 && min / (sample * 2) >= target) {
            sample *= 2;
        }
        return sample;
    }

    // ---------------------------------------------------------------- rendering

    /** Full 108dp adaptive-icon canvas: the picture centred on its own average colour. */
    public static Bitmap render(Bitmap source, int percent) {
        Bitmap out = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(averageColor(source));
        int size = Math.round(ICON_SIZE * percent / 100f);
        int off = (ICON_SIZE - size) / 2;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(source, null, new Rect(off, off, off + size, off + size), p);
        return out;
    }

    private static int averageColor(Bitmap b) {
        try {
            Bitmap one = Bitmap.createScaledBitmap(b, 1, 1, true);
            return one.getPixel(0, 0) | 0xFF000000;
        } catch (Throwable e) {
            return 0xFF26A69A;
        }
    }

    /** Roughly what a launcher shows: the central 72dp of the 108dp layer, cut to a circle. */
    public static Bitmap circlePreview(Bitmap layer) {
        int size = layer.getWidth();
        int vis = Math.round(size * 72f / 108f);
        int o = (size - vis) / 2;
        Bitmap pv = Bitmap.createBitmap(vis, vis, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(pv);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.drawCircle(vis / 2f, vis / 2f, vis / 2f, p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        c.drawBitmap(layer, new Rect(o, o, o + vis, o + vis), new Rect(0, 0, vis, vis), p);
        return pv;
    }

    // ---------------------------------------------------------------- shortcut

    private static ShortcutInfoCompat buildShortcut(Context context, Bitmap rendered) {
        Intent launch = new Intent(Intent.ACTION_MAIN);
        launch.setClassName(context.getPackageName(), "org.telegram.ui.LaunchActivity");
        launch.addCategory(Intent.CATEGORY_LAUNCHER);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return new ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
                .setShortLabel("Melgram")
                .setLongLabel("Melgram")
                .setIcon(IconCompat.createWithAdaptiveBitmap(rendered))
                .setIntent(launch)
                .build();
    }

    public static boolean isPinned(Context context) {
        try {
            List<ShortcutInfoCompat> list = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED);
            for (ShortcutInfoCompat s : list) {
                if (SHORTCUT_ID.equals(s.getId())) {
                    return true;
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    /** Pins the shortcut, or refreshes its picture when it is already on the home screen. */
    public static int pin(Context context, Bitmap rendered) {
        ShortcutInfoCompat info = buildShortcut(context, rendered);
        if (isPinned(context)) {
            ShortcutManagerCompat.updateShortcuts(context, Collections.singletonList(info));
            return PIN_UPDATED;
        }
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            return PIN_UNSUPPORTED;
        }
        ShortcutManagerCompat.requestPinShortcut(context, info, null);
        return PIN_REQUESTED;
    }

    public static void updateIfPinned(Context context, Bitmap rendered) {
        if (isPinned(context)) {
            try {
                ShortcutManagerCompat.updateShortcuts(context, Collections.singletonList(buildShortcut(context, rendered)));
            } catch (Throwable ignore) {
            }
        }
    }

    /** Kept in its own class so older Android versions never load ImageDecoder. */
    private static final class Api28 {
        static Bitmap decode(Context ctx, Uri uri, int target) throws IOException {
            ImageDecoder.Source src = ImageDecoder.createSource(ctx.getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setTargetSampleSize(sampleSize(info.getSize().getWidth(), info.getSize().getHeight(), target));
            });
        }
    }
}
