package org.telegram.messenger;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

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
 * Melgram: lets the user pick any picture from the gallery and put it on the
 * home screen as the Melgram icon. Android does not let an app replace its own
 * launcher icon with an arbitrary picture, so the picture is converted to the
 * adaptive-icon format and pinned as a home-screen shortcut that opens Melgram.
 */
public class MelgramIconActivity extends Activity {

    private static final int REQ_PICK = 4101;
    private static final String SHORTCUT_ID = "melgram_custom_icon";
    private static final String SOURCE_FILE = "melgram_icon_source.png";
    private static final int ICON_SIZE = 432;    // 108dp at xxxhdpi, full adaptive-icon canvas
    private static final int SOURCE_SIZE = 1024; // max side of the stored picture
    private static final int SCALE_MIN = 60;     // percent; at ~67% the whole picture fits the mask

    private SharedPreferences prefs;
    private Bitmap source;
    private Bitmap rendered;

    private ImageView preview;
    private SeekBar scaleBar;
    private TextView scaleLabel;
    private ProgressBar progress;
    private boolean busy;

    private int colorBg, colorText, colorSub, colorAccent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("melgram_icon", MODE_PRIVATE);

        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        colorBg = dark ? 0xFF17212B : 0xFFFFFFFF;
        colorText = dark ? 0xFFFFFFFF : 0xFF000000;
        colorSub = dark ? 0xFF8A9AA9 : 0xFF707579;
        colorAccent = 0xFF26A69A;

        getWindow().setStatusBarColor(colorBg);
        getWindow().setNavigationBarColor(colorBg);
        if (!dark) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        setContentView(buildLayout());

        source = loadSavedSource();
        render();
    }

    private View buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(colorBg);
        scroll.setFitsSystemWindows(true);
        scroll.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Иконка Melgram");
        title.setTextColor(colorText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, matchWrap(0));

        TextView info = new TextView(this);
        info.setText("Встроенные картинки меняют настоящий значок Melgram — и на рабочем столе, и в меню приложений.");
        info.setTextColor(colorSub);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        info.setLineSpacing(dp(2), 1f);
        root.addView(info, matchWrap(dp(8)));

        addBuiltInIcons(root);

        TextView ownTitle = new TextView(this);
        ownTitle.setText("Своя картинка из галереи");
        ownTitle.setTextColor(colorText);
        ownTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        ownTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(ownTitle, matchWrap(dp(28)));

        TextView ownInfo = new TextView(this);
        ownInfo.setText("Любую картинку Melgram обрежет и подгонит под формат иконки. Android не разрешает " +
                "менять значок приложения на произвольную картинку, поэтому она появится на рабочем столе " +
                "как ярлык Melgram. Старый значок с рабочего стола можно убрать — в меню приложений он останется.");
        ownInfo.setTextColor(colorSub);
        ownInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        ownInfo.setLineSpacing(dp(2), 1f);
        root.addView(ownInfo, matchWrap(dp(6)));

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(168), dp(168));
        plp.topMargin = dp(28);
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(preview, plp);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams prlp = new LinearLayout.LayoutParams(dp(32), dp(32));
        prlp.topMargin = dp(8);
        prlp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(progress, prlp);

        scaleLabel = new TextView(this);
        scaleLabel.setTextColor(colorText);
        scaleLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        root.addView(scaleLabel, matchWrap(dp(16)));

        scaleBar = new SeekBar(this);
        scaleBar.setMax(100 - SCALE_MIN);
        scaleBar.setProgress(Math.max(0, Math.min(100 - SCALE_MIN, prefs.getInt("scale", 100) - SCALE_MIN)));
        scaleBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (fromUser) {
                    render();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt("scale", currentScalePercent()).apply();
            }
        });
        root.addView(scaleBar, matchWrap(dp(4)));

        root.addView(makeButton("Выбрать из галереи", true, v -> pickImage()), matchWrap(dp(20)));
        root.addView(makeButton("Поставить на рабочий стол", true, v -> pinShortcut()), matchWrap(dp(10)));
        root.addView(makeButton("Вернуть стандартную картинку", false, v -> resetToDefault()), matchWrap(dp(10)));

        return scroll;
    }

    // ---------------------------------------------------------------- built-in icons

    private final java.util.List<View> iconRings = new java.util.ArrayList<>();

    private void addBuiltInIcons(LinearLayout root) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(row);
        root.addView(hs, matchWrap(dp(16)));

        org.telegram.ui.LauncherIconController.LauncherIcon[] icons = org.telegram.ui.LauncherIconController.LauncherIcon.values();
        for (org.telegram.ui.LauncherIconController.LauncherIcon icon : icons) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(6), dp(4), dp(6), dp(4));

            android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
            View ring = new View(this);
            GradientDrawable ringBg = new GradientDrawable();
            ringBg.setShape(GradientDrawable.OVAL);
            ringBg.setColor(0x00000000);
            ringBg.setStroke(dp(3), colorAccent);
            ring.setBackground(ringBg);
            frame.addView(ring, new android.widget.FrameLayout.LayoutParams(dp(72), dp(72)));
            ImageView image = new ImageView(this);
            try {
                Bitmap layer = BitmapFactory.decodeResource(getResources(), icon.background);
                if (layer != null) {
                    image.setImageBitmap(makeCirclePreview(layer));
                }
            } catch (Throwable ignore) {
            }
            android.widget.FrameLayout.LayoutParams ilp = new android.widget.FrameLayout.LayoutParams(dp(60), dp(60));
            ilp.gravity = Gravity.CENTER;
            frame.addView(image, ilp);
            cell.addView(frame, new LinearLayout.LayoutParams(dp(72), dp(72)));

            TextView name = new TextView(this);
            String title;
            try {
                title = org.telegram.messenger.LocaleController.getString(icon.title);
            } catch (Throwable e) {
                title = "";
            }
            name.setText(title);
            name.setTextColor(colorText);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            name.setGravity(Gravity.CENTER_HORIZONTAL);
            name.setMaxLines(2);
            cell.addView(name, new LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT));

            ring.setTag(icon);
            iconRings.add(ring);
            cell.setOnClickListener(v -> {
                try {
                    org.telegram.ui.LauncherIconController.setIcon(icon);
                    toast("Значок поменяется через пару секунд");
                } catch (Throwable e) {
                    toast("Не получилось поменять значок: " + e.getMessage());
                }
                updateIconRings();
            });
            row.addView(cell);
        }
        updateIconRings();
    }

    private void updateIconRings() {
        for (View ring : iconRings) {
            boolean enabled = false;
            try {
                enabled = org.telegram.ui.LauncherIconController.isEnabled((org.telegram.ui.LauncherIconController.LauncherIcon) ring.getTag());
            } catch (Throwable ignore) {
            }
            ring.setVisibility(enabled ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /** The central 72dp of a 108dp adaptive layer, cut to a circle. */
    private static Bitmap makeCirclePreview(Bitmap layer) {
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

    // ---------------------------------------------------------------- picture

    private Bitmap loadSavedSource() {
        File f = new File(getFilesDir(), SOURCE_FILE);
        Bitmap b = null;
        if (f.exists()) {
            try {
                b = BitmapFactory.decodeFile(f.getAbsolutePath());
            } catch (Throwable ignore) {
            }
        }
        if (b == null) {
            b = BitmapFactory.decodeResource(getResources(), R.drawable.melgram_default_icon);
        }
        return b;
    }

    private void pickImage() {
        if (busy) return;
        Intent pick = new Intent(Intent.ACTION_PICK);
        pick.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        try {
            startActivityForResult(pick, REQ_PICK);
        } catch (ActivityNotFoundException e) {
            Intent get = new Intent(Intent.ACTION_GET_CONTENT);
            get.setType("image/*");
            get.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(Intent.createChooser(get, "Выбери картинку"), REQ_PICK);
            } catch (Exception e2) {
                toast("Не нашёл приложение галереи");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        setBusy(true);
        new Thread(() -> {
            Bitmap result = null;
            try {
                result = decodeSquare(uri);
                saveSource(result);
            } catch (Throwable e) {
                result = null;
            }
            final Bitmap finalResult = result;
            runOnUiThread(() -> {
                setBusy(false);
                if (isFinishing()) return;
                if (finalResult == null) {
                    toast("Не получилось открыть эту картинку");
                    return;
                }
                source = finalResult;
                render();
                toast("Готово! Теперь нажми «Поставить на рабочий стол»");
            });
        }, "melgram-icon").start();
    }

    private Bitmap decodeSquare(Uri uri) throws IOException {
        Bitmap bmp;
        if (Build.VERSION.SDK_INT >= 28) {
            bmp = Api28.decode(this, uri, SOURCE_SIZE);
        } else {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, SOURCE_SIZE);
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp != null && Build.VERSION.SDK_INT >= 24) {
                bmp = applyExifRotation(uri, bmp);
            }
        }
        if (bmp == null) {
            throw new IOException("decode failed");
        }
        return cropSquare(bmp);
    }

    private Bitmap applyExifRotation(Uri uri, Bitmap bmp) {
        int degrees = 0;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
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

    private static Bitmap cropSquare(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        int s = Math.min(w, h);
        Bitmap sq = Bitmap.createBitmap(b, (w - s) / 2, (h - s) / 2, s, s);
        if (s > SOURCE_SIZE) {
            sq = Bitmap.createScaledBitmap(sq, SOURCE_SIZE, SOURCE_SIZE, true);
        }
        if (sq.getConfig() != Bitmap.Config.ARGB_8888) {
            sq = sq.copy(Bitmap.Config.ARGB_8888, false);
        }
        return sq;
    }

    private void saveSource(Bitmap b) throws IOException {
        File tmp = new File(getFilesDir(), SOURCE_FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        File dst = new File(getFilesDir(), SOURCE_FILE);
        if (!tmp.renameTo(dst)) {
            throw new IOException("rename failed");
        }
    }

    // --------------------------------------------------------------- rendering

    private int currentScalePercent() {
        return SCALE_MIN + scaleBar.getProgress();
    }

    /** Builds the full 108dp adaptive-icon canvas: the picture centered on its own average colour. */
    private void render() {
        if (source == null) return;
        int percent = currentScalePercent();
        Bitmap out = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(averageColor(source));
        int size = Math.round(ICON_SIZE * percent / 100f);
        int off = (ICON_SIZE - size) / 2;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(source, null, new Rect(off, off, off + size, off + size), p);
        rendered = out;
        preview.setImageBitmap(makePreview(out));
        scaleLabel.setText("Размер картинки: " + percent + "%");
    }

    private static int averageColor(Bitmap b) {
        try {
            Bitmap one = Bitmap.createScaledBitmap(b, 1, 1, true);
            return one.getPixel(0, 0) | 0xFF000000;
        } catch (Throwable e) {
            return 0xFF26A69A;
        }
    }

    /** Shows roughly what a launcher displays: the central 72dp of the 108dp canvas, masked to a circle. */
    private static Bitmap makePreview(Bitmap full) {
        int vis = Math.round(ICON_SIZE * 72f / 108f);
        int o = (ICON_SIZE - vis) / 2;
        Bitmap pv = Bitmap.createBitmap(vis, vis, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(pv);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.drawCircle(vis / 2f, vis / 2f, vis / 2f, p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        c.drawBitmap(full, new Rect(o, o, o + vis, o + vis), new Rect(0, 0, vis, vis), p);
        return pv;
    }

    // --------------------------------------------------------------- shortcut

    private ShortcutInfoCompat buildShortcut() {
        Intent launch = new Intent(Intent.ACTION_MAIN);
        launch.setClassName(getPackageName(), "org.telegram.ui.LaunchActivity");
        launch.addCategory(Intent.CATEGORY_LAUNCHER);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return new ShortcutInfoCompat.Builder(this, SHORTCUT_ID)
                .setShortLabel("Melgram")
                .setLongLabel("Melgram")
                .setIcon(IconCompat.createWithAdaptiveBitmap(rendered))
                .setIntent(launch)
                .build();
    }

    private boolean isPinned() {
        try {
            List<ShortcutInfoCompat> list = ShortcutManagerCompat.getShortcuts(this, ShortcutManagerCompat.FLAG_MATCH_PINNED);
            for (ShortcutInfoCompat s : list) {
                if (SHORTCUT_ID.equals(s.getId())) {
                    return true;
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    private void pinShortcut() {
        if (busy || rendered == null) return;
        prefs.edit().putInt("scale", currentScalePercent()).apply();
        try {
            ShortcutInfoCompat info = buildShortcut();
            if (isPinned()) {
                ShortcutManagerCompat.updateShortcuts(this, Collections.singletonList(info));
                toast("Иконка на рабочем столе обновлена");
                return;
            }
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
                toast("Твой лаунчер не умеет добавлять ярлыки");
                return;
            }
            ShortcutManagerCompat.requestPinShortcut(this, info, null);
        } catch (Throwable e) {
            toast("Не получилось добавить ярлык: " + e.getMessage());
        }
    }

    private void resetToDefault() {
        if (busy) return;
        new File(getFilesDir(), SOURCE_FILE).delete();
        prefs.edit().putInt("scale", 100).apply();
        scaleBar.setProgress(100 - SCALE_MIN);
        source = BitmapFactory.decodeResource(getResources(), R.drawable.melgram_default_icon);
        render();
        if (isPinned()) {
            try {
                ShortcutManagerCompat.updateShortcuts(this, Collections.singletonList(buildShortcut()));
            } catch (Throwable ignore) {
            }
        }
        toast("Вернул стандартную картинку");
    }

    // --------------------------------------------------------------- helpers

    private void setBusy(boolean value) {
        busy = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
    }

    private Button makeButton(String text, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setMinHeight(dp(48));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        if (primary) {
            bg.setColor(colorAccent);
            b.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(0x00000000);
            bg.setStroke(dp(1), colorAccent);
            b.setTextColor(colorAccent);
        }
        b.setBackground(bg);
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        return lp;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
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
