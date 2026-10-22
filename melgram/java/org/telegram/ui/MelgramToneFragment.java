package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MelgramToneTools;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Melgram: notification sound from any audio or video file up to 5 minutes.
 * The fragment is picked on a waveform, then encoded and uploaded to Telegram.
 */
public class MelgramToneFragment extends UniversalFragment {

    private static final int REQ_PICK = 4201;
    private static final long MIN_LEN_US = 500_000;
    private static final long STEP_US = 100_000;

    private static final int ID_PICK = 1;
    private static final int ID_PLAY = 2;
    private static final int ID_BACK = 3;
    private static final int ID_FORWARD = 4;
    private static final int ID_ALL = 5;
    private static final int ID_UPLOAD = 6;

    private Uri sourceUri;
    private String sourceName;
    private long durationUs;
    private float[] peaks = new float[0];
    private long selStartUs;
    private long selLenUs;
    private long maxLenUs;
    private int sizeMax;
    private boolean toAllAccounts;
    private boolean busy;
    private String status;

    private WaveView wave;
    private MediaPlayer player;
    private long playStartedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancel = new AtomicBoolean();

    @Override
    public boolean onFragmentCreate() {
        MessagesController mc = MessagesController.getInstance(currentAccount);
        maxLenUs = Math.max(1, mc.ringtoneDurationMax) * 1_000_000L;
        sizeMax = mc.ringtoneSizeMax > 0 ? mc.ringtoneSizeMax : 100 * 1024;
        selLenUs = maxLenUs;
        if (!MelgramToneTools.canEncode()) {
            status = "Для сжатия звука нужен Android 10 или новее.";
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        wave = new WaveView(context);
        return super.createView(context);
    }

    @Override
    public void onPause() {
        super.onPause();
        stopPlayback();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        cancel.set(true);
        stopPlayback();
    }

    @Override
    protected CharSequence getTitle() {
        return "Звук уведомления";
    }

    private boolean hasFile() {
        return sourceUri != null && durationUs > 0;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Файл"));
        items.add(UItem.asButton(ID_PICK, R.drawable.msg_tone_add, hasFile() ? sourceName : "Выбрать аудио или видео", hasFile() ? time(durationUs) : ""));
        items.add(UItem.asShadow(String.format(Locale.US,
                "Подойдёт любой файл до 5 минут: музыка, голосовое, видео. Telegram принимает звуки до %d сек и %d КБ — Melgram сам вырежет и сожмёт фрагмент.",
                maxLenUs / 1_000_000, sizeMax / 1024)));

        if (hasFile()) {
            items.add(UItem.asHeader("Фрагмент"));
            items.add(UItem.asCustom(wave, 132));
            items.add(UItem.asButton(ID_PLAY, playStartedAt > 0 ? "■  Остановить" : "▶  Прослушать фрагмент").accent());
            items.add(UItem.asButton(ID_BACK, "Сдвинуть на 0,1 с назад"));
            items.add(UItem.asButton(ID_FORWARD, "Сдвинуть на 0,1 с вперёд"));
            items.add(UItem.asShadow("Перетащи рамку по волне или нажми на нужное место."));

            int maxTenths = (int) (Math.min(maxLenUs, durationUs) / STEP_US);
            int minTenths = (int) Math.min(MIN_LEN_US / STEP_US, maxTenths);
            if (maxTenths > minTenths) {
                items.add(UItem.asHeader("Длина фрагмента"));
                items.add(UItem.asIntSlideView(1, minTenths, (int) (selLenUs / STEP_US), maxTenths,
                        value -> String.format(Locale.US, "%.1f с", value / 10f).replace('.', ','),
                        value -> {
                            selLenUs = value * STEP_US;
                            clampSelection();
                            stopPlayback();
                            wave.invalidate();
                        }));
                items.add(UItem.asShadow(null));
            }

            int activated = UserConfig.getActivatedAccountsCount();
            if (activated > 1) {
                items.add(UItem.asCheck(ID_ALL, "Загрузить во все аккаунты (" + activated + ")").setChecked(toAllAccounts));
            }
            items.add(UItem.asButton(ID_UPLOAD, R.drawable.msg_tone_on, "Загрузить в Telegram").accent().setLocked(busy).setEnabled(MelgramToneTools.canEncode() && !busy));
        }
        items.add(UItem.asShadow(status != null ? status : "После загрузки звук появится в «Уведомления и звуки» → выбор звука → раздел Telegram."));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        if (busy && item.id != ID_PLAY) return;
        switch (item.id) {
            case ID_PICK:
                pick(activity);
                break;
            case ID_PLAY:
                togglePlayback(activity);
                break;
            case ID_BACK:
                moveSelection(-STEP_US);
                break;
            case ID_FORWARD:
                moveSelection(STEP_US);
                break;
            case ID_ALL:
                toAllAccounts = !toAllAccounts;
                listView.adapter.update(true);
                break;
            case ID_UPLOAD:
                export(activity);
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public boolean isSwipeBackEnabled(MotionEvent event) {
        if (wave != null && wave.isShown() && event != null) {
            int[] loc = new int[2];
            wave.getLocationOnScreen(loc);
            float ex = event.getRawX();
            float ey = event.getRawY();
            if (ex >= loc[0] && ex <= loc[0] + wave.getWidth() && ey >= loc[1] && ey <= loc[1] + wave.getHeight()) {
                return false;
            }
        }
        return super.isSwipeBackEnabled(event);
    }

    // ------------------------------------------------------------------ pick + analyse

    private void pick(Activity activity) {
        stopPlayback();
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*"});
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            activity.startActivityForResult(Intent.createChooser(intent, "Выбери аудио или видео"), REQ_PICK);
        } catch (Throwable e) {
            bulletin(R.raw.error, "Не нашёл приложение для выбора файлов");
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        analyze(data.getData());
    }

    private void analyze(Uri uri) {
        final Context context = getParentActivity();
        if (context == null) return;
        busy = true;
        status = "Читаю файл…";
        listView.adapter.update(true);
        final String name = MelgramToneTools.displayName(context, uri);
        new Thread(() -> {
            MelgramToneTools.Analysis analysis = null;
            String error = null;
            try {
                analysis = MelgramToneTools.analyze(context, uri, cancel);
            } catch (Throwable e) {
                FileLog.e(e);
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final MelgramToneTools.Analysis result = analysis;
            final String finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                busy = false;
                if (isFinishing() || getParentActivity() == null) return;
                if (finalError != null || result == null) {
                    status = "Не получилось открыть файл: " + finalError;
                } else if (result.tooLong) {
                    status = "Файл длиннее 5 минут — выбери файл покороче.";
                } else {
                    sourceUri = uri;
                    sourceName = name;
                    durationUs = result.durationUs;
                    peaks = result.peaks;
                    selLenUs = Math.min(maxLenUs, durationUs);
                    selStartUs = 0;
                    clampSelection();
                    status = null;
                }
                listView.adapter.update(true);
                wave.invalidate();
            });
        }, "melgram-tone-analyze").start();
    }

    // ------------------------------------------------------------------ selection

    private void clampSelection() {
        if (durationUs <= 0) return;
        long maxLen = Math.min(maxLenUs, durationUs);
        selLenUs = Math.max(Math.min(MIN_LEN_US, maxLen), Math.min(selLenUs, maxLen));
        selStartUs = Math.max(0, Math.min(selStartUs, durationUs - selLenUs));
    }

    private void moveSelection(long deltaUs) {
        selStartUs += deltaUs;
        clampSelection();
        stopPlayback();
        wave.invalidate();
    }

    // ------------------------------------------------------------------ playback

    private void togglePlayback(Context context) {
        if (player != null) {
            stopPlayback();
            return;
        }
        if (!hasFile()) return;
        try {
            player = new MediaPlayer();
            player.setDataSource(context, sourceUri);
            player.setOnPreparedListener(mp -> {
                mp.seekTo((int) (selStartUs / 1000));
                mp.start();
                playStartedAt = System.currentTimeMillis();
                listView.adapter.update(true);
                handler.postDelayed(this::stopPlayback, selLenUs / 1000);
                tickPlayhead();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                stopPlayback();
                bulletin(R.raw.error, "Не получилось проиграть файл");
                return true;
            });
            player.prepareAsync();
        } catch (Throwable e) {
            stopPlayback();
            bulletin(R.raw.error, "Не получилось проиграть файл");
        }
    }

    private void tickPlayhead() {
        if (player == null) return;
        wave.invalidate();
        handler.postDelayed(this::tickPlayhead, 33);
    }

    private void stopPlayback() {
        handler.removeCallbacksAndMessages(null);
        boolean wasPlaying = playStartedAt > 0;
        if (player != null) {
            try {
                player.stop();
            } catch (Throwable ignore) {
            }
            try {
                player.release();
            } catch (Throwable ignore) {
            }
            player = null;
        }
        playStartedAt = 0;
        if (wave != null) wave.invalidate();
        if (wasPlaying && listView != null && !isFinishing()) {
            listView.adapter.update(true);
        }
    }

    // ------------------------------------------------------------------ export

    private void export(Context context) {
        if (!MelgramToneTools.canEncode() || !hasFile()) return;
        stopPlayback();
        busy = true;
        status = "Вырезаю и сжимаю фрагмент…";
        listView.adapter.update(true);
        final Uri uri = sourceUri;
        final long start = selStartUs;
        final long end = selStartUs + selLenUs;
        final String name = sourceName;
        final boolean toAll = toAllAccounts && UserConfig.getActivatedAccountsCount() > 1;
        new Thread(() -> {
            File out = null;
            String error = null;
            try {
                out = MelgramToneTools.makeTone(context, uri, start, end, name, sizeMax, cancel);
            } catch (Throwable e) {
                FileLog.e(e);
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final File file = out;
            final String finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                busy = false;
                if (isFinishing() || getParentActivity() == null) return;
                if (finalError != null || file == null) {
                    status = "Не получилось: " + finalError;
                    bulletin(R.raw.error, "Не получилось сделать звук");
                } else {
                    upload(file, toAll);
                }
                listView.adapter.update(true);
            });
        }, "melgram-tone-export").start();
    }

    private void upload(File file, boolean toAll) {
        List<Integer> accounts = new ArrayList<>();
        if (toAll) {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) accounts.add(a);
            }
        } else {
            accounts.add(currentAccount);
        }
        for (int account : accounts) {
            MediaDataController.getInstance(account).uploadRingtone(file.getAbsolutePath());
        }
        String title = file.getName().replace(".ogg", "");
        status = "Загружаю «" + title + "» (" + Math.max(1, file.length() / 1024) + " КБ)"
                + (accounts.size() > 1 ? " в " + accounts.size() + " аккаунта" : "")
                + ". Звук появится в «Уведомления и звуки» → выбор звука → раздел Telegram.";
        bulletin(R.raw.done, "Звук «" + title + "» загружается в Telegram");
    }

    private void bulletin(int raw, String text) {
        BulletinFactory.of(this).createSimpleBulletin(raw, text).show();
    }

    private static String time(long us) {
        long tenths = us / 100_000;
        long sec = tenths / 10;
        return String.format(Locale.US, "%d:%02d,%d", sec / 60, sec % 60, tenths % 10);
    }

    // ------------------------------------------------------------------ waveform

    private final class WaveView extends View {
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private long dragOffsetUs;

        WaveView(Context context) {
            super(context);
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setStrokeWidth(AndroidUtilities.dp(2));
            headPaint.setStrokeWidth(AndroidUtilities.dp(1.5f));
            textPaint.setTextSize(AndroidUtilities.dp(13));
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        private int pad() {
            return AndroidUtilities.dp(21);
        }

        private float waveWidth() {
            return Math.max(1, getWidth() - pad() * 2);
        }

        private float xOf(long us) {
            return durationUs <= 0 ? pad() : pad() + (float) us / durationUs * waveWidth();
        }

        private long usOf(float x) {
            return durationUs <= 0 ? 0 : (long) ((x - pad()) / waveWidth() * durationUs);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int accent = getThemedColor(Theme.key_featuredStickers_addButton);
            int idle = getThemedColor(Theme.key_windowBackgroundWhiteGrayText2);
            int text = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
            selPaint.setColor((accent & 0x00FFFFFF) | 0x26000000);
            framePaint.setColor(accent);
            headPaint.setColor(text);
            textPaint.setColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));

            float top = AndroidUtilities.dp(8);
            float bottom = getHeight() - AndroidUtilities.dp(30);
            float mid = (top + bottom) / 2f;
            float barW = AndroidUtilities.dp(2);
            float gap = AndroidUtilities.dp(1.5f);
            int bars = (int) (waveWidth() / (barW + gap));

            float selL = xOf(selStartUs);
            float selR = xOf(selStartUs + selLenUs);
            rect.set(selL, top - AndroidUtilities.dp(4), selR, bottom + AndroidUtilities.dp(4));
            canvas.drawRoundRect(rect, AndroidUtilities.dp(8), AndroidUtilities.dp(8), selPaint);

            for (int i = 0; i < bars; i++) {
                int from = peaks.length == 0 ? 0 : (int) ((long) i * peaks.length / bars);
                int to = peaks.length == 0 ? 0 : (int) ((long) (i + 1) * peaks.length / bars);
                float v = 0;
                for (int k = from; k < Math.max(from + 1, to) && k < peaks.length; k++) v = Math.max(v, peaks[k]);
                float half = Math.max(AndroidUtilities.dp(1), v * ((bottom - top) / 2f));
                float x = pad() + i * (barW + gap);
                barPaint.setColor(x + barW >= selL && x <= selR ? accent : idle);
                barPaint.setAlpha(x + barW >= selL && x <= selR ? 255 : 110);
                rect.set(x, mid - half, x + barW, mid + half);
                canvas.drawRoundRect(rect, barW / 2, barW / 2, barPaint);
            }

            rect.set(selL, top - AndroidUtilities.dp(4), selR, bottom + AndroidUtilities.dp(4));
            canvas.drawRoundRect(rect, AndroidUtilities.dp(8), AndroidUtilities.dp(8), framePaint);

            if (playStartedAt > 0) {
                long played = (System.currentTimeMillis() - playStartedAt) * 1000;
                float x = xOf(selStartUs + Math.min(played, selLenUs));
                canvas.drawLine(x, top, x, bottom, headPaint);
            }

            String label = time(selStartUs) + " — " + time(selStartUs + selLenUs) + "   ·   "
                    + String.format(Locale.US, "%.1f с", selLenUs / 1_000_000f).replace('.', ',');
            canvas.drawText(label, getWidth() / 2f, getHeight() - AndroidUtilities.dp(9), textPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (busy || durationUs <= 0) return false;
            long t = usOf(e.getX());
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    stopPlayback();
                    if (t >= selStartUs && t <= selStartUs + selLenUs) {
                        dragOffsetUs = t - selStartUs;
                    } else {
                        dragOffsetUs = selLenUs / 2;
                        selStartUs = t - dragOffsetUs;
                        clampSelection();
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    selStartUs = t - dragOffsetUs;
                    clampSelection();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
            }
            return super.onTouchEvent(e);
        }
    }
}
