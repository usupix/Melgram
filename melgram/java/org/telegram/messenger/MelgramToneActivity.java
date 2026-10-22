package org.telegram.messenger;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Melgram: notification sound from any audio or video file (up to 5 minutes).
 * The user picks a fragment on the waveform; Melgram decodes it, makes it mono
 * 48 kHz, encodes it to Ogg/Opus within Telegram's ringtone limits and uploads it.
 */
public class MelgramToneActivity extends Activity {

    private static final int REQ_PICK = 4201;
    private static final long MAX_SOURCE_US = 5L * 60 * 1_000_000 + 999_999;
    private static final long BUCKET_US = 20_000;
    private static final int OUT_RATE = 48_000;
    private static final long MIN_LEN_US = 500_000;
    private static final long STEP_US = 100_000;

    private Uri sourceUri;
    private String sourceName = "Melgram";
    private long durationUs;
    private float[] buckets = new float[0];
    private long selStartUs;
    private long selLenUs;
    private long maxLenUs;
    private int sizeMax;

    private WaveView wave;
    private SeekBar lenBar;
    private TextView fileLabel;
    private TextView rangeLabel;
    private TextView status;
    private Button pickButton;
    private Button playButton;
    private Button uploadButton;
    private Button backStep;
    private Button forwardStep;
    private CheckBox allAccounts;
    private ProgressBar progress;

    private MediaPlayer player;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    private boolean busy;
    private long playStartedAt;

    private int colorBg, colorCard, colorText, colorSub, colorAccent, colorWave;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        colorBg = dark ? 0xFF0E1621 : 0xFFF0F2F5;
        colorCard = dark ? 0xFF17212B : 0xFFFFFFFF;
        colorText = dark ? 0xFFFFFFFF : 0xFF000000;
        colorSub = dark ? 0xFF8A9AA9 : 0xFF707579;
        colorAccent = 0xFF26A69A;
        colorWave = dark ? 0xFF3A4A5A : 0xFFC5CDD5;
        getWindow().setStatusBarColor(colorBg);
        getWindow().setNavigationBarColor(colorBg);
        if (!dark) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        int account = UserConfig.selectedAccount;
        MessagesController mc = MessagesController.getInstance(account);
        maxLenUs = Math.max(1, mc.ringtoneDurationMax) * 1_000_000L;
        sizeMax = mc.ringtoneSizeMax > 0 ? mc.ringtoneSizeMax : 100 * 1024;
        selLenUs = maxLenUs;

        setContentView(buildLayout());
        updateUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPlayback();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelled = true;
        stopPlayback();
    }

    // ------------------------------------------------------------------ layout

    private View buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(colorBg);
        scroll.setFitsSystemWindows(true);
        scroll.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Звук уведомления из файла", 22, colorText);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView info = text(String.format(Locale.US,
                "Выбери аудио или видео длиной до 5 минут, передвинь рамку на нужный момент и загрузи. " +
                        "Telegram принимает звуки до %d сек и %d КБ — Melgram сам вырежет и сожмёт фрагмент.\n\n" +
                        "После загрузки звук появится в «Уведомления и звуки» → выбор звука → раздел Telegram.",
                maxLenUs / 1_000_000, sizeMax / 1024), 14, colorSub);
        info.setPadding(0, dp(6), 0, 0);
        root.addView(info);

        pickButton = button("Выбрать файл", true);
        pickButton.setOnClickListener(v -> pick());
        root.addView(pickButton, lp(dp(16)));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(colorCard);
        cardBg.setCornerRadius(dp(14));
        card.setBackground(cardBg);
        root.addView(card, lp(dp(16)));

        fileLabel = text("Файл не выбран", 15, colorText);
        fileLabel.setSingleLine(true);
        fileLabel.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        card.addView(fileLabel);

        wave = new WaveView(this);
        card.addView(wave, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)));

        rangeLabel = text("", 14, colorSub);
        rangeLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(rangeLabel, lp(dp(6)));

        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.HORIZONTAL);
        backStep = button("◀ 0,1 с", false);
        forwardStep = button("0,1 с ▶", false);
        backStep.setOnClickListener(v -> moveSelection(-STEP_US));
        forwardStep.setOnClickListener(v -> moveSelection(STEP_US));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half.rightMargin = dp(6);
        steps.addView(backStep, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half2.leftMargin = dp(6);
        steps.addView(forwardStep, half2);
        card.addView(steps, lp(dp(8)));

        TextView lenTitle = text("Длина фрагмента", 14, colorText);
        card.addView(lenTitle, lp(dp(12)));
        lenBar = new SeekBar(this);
        lenBar.setMax((int) Math.max(1, (maxLenUs - MIN_LEN_US) / STEP_US));
        lenBar.setProgress(lenBar.getMax());
        lenBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (!fromUser) return;
                selLenUs = Math.min(maxLenUs, MIN_LEN_US + value * STEP_US);
                clampSelection();
                stopPlayback();
                updateUi();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        card.addView(lenBar, lp(dp(2)));

        playButton = button("▶ Прослушать фрагмент", false);
        playButton.setOnClickListener(v -> togglePlayback());
        card.addView(playButton, lp(dp(10)));

        allAccounts = new CheckBox(this);
        allAccounts.setTextColor(colorText);
        int activated = UserConfig.getActivatedAccountsCount();
        allAccounts.setText("Загрузить во все аккаунты (" + activated + ")");
        allAccounts.setVisibility(activated > 1 ? View.VISIBLE : View.GONE);
        root.addView(allAccounts, lp(dp(12)));

        uploadButton = button("Загрузить в Telegram", true);
        uploadButton.setOnClickListener(v -> export());
        root.addView(uploadButton, lp(dp(8)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress, lp(dp(12)));

        status = text("", 14, colorSub);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(status, lp(dp(6)));

        if (Build.VERSION.SDK_INT < 29) {
            status.setText("Для сжатия звука нужен Android 10 или новее");
        }
        return scroll;
    }

    private void updateUi() {
        boolean hasFile = sourceUri != null && durationUs > 0;
        pickButton.setEnabled(!busy);
        playButton.setEnabled(hasFile && !busy);
        backStep.setEnabled(hasFile && !busy);
        forwardStep.setEnabled(hasFile && !busy);
        lenBar.setEnabled(hasFile && !busy);
        uploadButton.setEnabled(hasFile && !busy && Build.VERSION.SDK_INT >= 29);
        allAccounts.setEnabled(!busy);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (hasFile) {
            rangeLabel.setText(time(selStartUs) + " — " + time(selStartUs + selLenUs)
                    + "   (" + String.format(Locale.US, "%.1f", selLenUs / 1_000_000f).replace('.', ',') + " с из " + time(durationUs) + ")");
        } else {
            rangeLabel.setText("Выбери файл, чтобы увидеть звуковую волну");
        }
        wave.invalidate();
    }

    // ------------------------------------------------------------------ pick + analyse

    private void pick() {
        stopPlayback();
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*"});
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "Выбери аудио или видео"), REQ_PICK);
        } catch (Throwable e) {
            toast("Не нашёл приложение для выбора файлов");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        analyze(data.getData());
    }

    private void analyze(Uri uri) {
        busy = true;
        status.setText("Читаю файл…");
        updateUi();
        final String name = displayName(uri);
        new Thread(() -> {
            String error = null;
            final FloatList peaks = new FloatList(16_000);
            final long[] lastUs = {0};
            final boolean[] tooLong = {false};
            MediaExtractor ex = new MediaExtractor();
            try {
                ex.setDataSource(this, uri, null);
                int track = findAudioTrack(ex);
                if (track < 0) throw new IOException("в файле нет звуковой дорожки");
                MediaFormat fmt = ex.getTrackFormat(track);
                if (fmt.containsKey(MediaFormat.KEY_DURATION) && fmt.getLong(MediaFormat.KEY_DURATION) > MAX_SOURCE_US) {
                    tooLong[0] = true;
                } else {
                    decode(ex, track, 0, (pcm, count, rate, ptsUs) -> {
                        for (int i = 0; i < count; i++) {
                            long t = ptsUs + i * 1_000_000L / rate;
                            peaks.max((int) (t / BUCKET_US), Math.abs(pcm[i]));
                        }
                        lastUs[0] = Math.max(lastUs[0], ptsUs + count * 1_000_000L / rate);
                        if (lastUs[0] > MAX_SOURCE_US) {
                            tooLong[0] = true;
                            return false;
                        }
                        return !cancelled;
                    });
                }
                if (!tooLong[0] && lastUs[0] <= 0) throw new IOException("не получилось раскодировать звук");
            } catch (Throwable e) {
                FileLog.e(e);
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            } finally {
                ex.release();
            }
            final String finalError = error;
            runOnUiThread(() -> {
                busy = false;
                if (isFinishing()) return;
                if (tooLong[0]) {
                    status.setText("Файл длиннее 5 минут — выбери файл покороче");
                } else if (finalError != null) {
                    status.setText("Не получилось открыть файл: " + finalError);
                } else {
                    sourceUri = uri;
                    sourceName = name;
                    durationUs = lastUs[0];
                    float[] arr = peaks.toArray();
                    float max = 0.0001f;
                    for (float v : arr) max = Math.max(max, v);
                    for (int i = 0; i < arr.length; i++) arr[i] /= max;
                    buckets = arr;
                    selLenUs = Math.min(selLenUs, durationUs);
                    selStartUs = 0;
                    clampSelection();
                    fileLabel.setText(name);
                    status.setText("Передвинь рамку на нужный момент");
                }
                updateUi();
            });
        }, "melgram-tone-analyze").start();
    }

    private static int findAudioTrack(MediaExtractor ex) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
            }
        } catch (Throwable ignore) {
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "Melgram";
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", " ").trim();
        if (name.length() > 40) name = name.substring(0, 40).trim();
        return name.isEmpty() ? "Melgram" : name;
    }

    // ------------------------------------------------------------------ selection

    private void clampSelection() {
        if (durationUs <= 0) return;
        selLenUs = Math.max(Math.min(MIN_LEN_US, durationUs), Math.min(selLenUs, Math.min(maxLenUs, durationUs)));
        selStartUs = Math.max(0, Math.min(selStartUs, durationUs - selLenUs));
    }

    private void moveSelection(long deltaUs) {
        selStartUs += deltaUs;
        clampSelection();
        stopPlayback();
        updateUi();
    }

    // ------------------------------------------------------------------ playback

    private void togglePlayback() {
        if (player != null) {
            stopPlayback();
            return;
        }
        try {
            player = new MediaPlayer();
            player.setDataSource(this, sourceUri);
            player.setOnPreparedListener(mp -> {
                mp.seekTo((int) (selStartUs / 1000));
                mp.start();
                playStartedAt = System.currentTimeMillis();
                playButton.setText("■ Остановить");
                handler.postDelayed(this::stopPlayback, selLenUs / 1000);
                tickPlayhead();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                stopPlayback();
                toast("Не получилось проиграть файл");
                return true;
            });
            player.prepareAsync();
        } catch (Throwable e) {
            stopPlayback();
            toast("Не получилось проиграть файл");
        }
    }

    private void tickPlayhead() {
        if (player == null) return;
        wave.invalidate();
        handler.postDelayed(this::tickPlayhead, 33);
    }

    private void stopPlayback() {
        handler.removeCallbacksAndMessages(null);
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
        if (playButton != null) playButton.setText("▶ Прослушать фрагмент");
        if (wave != null) wave.invalidate();
    }

    // ------------------------------------------------------------------ export

    private void export() {
        if (Build.VERSION.SDK_INT < 29 || sourceUri == null) return;
        stopPlayback();
        busy = true;
        status.setText("Вырезаю и сжимаю фрагмент…");
        updateUi();
        final Uri uri = sourceUri;
        final long start = selStartUs;
        final long end = selStartUs + selLenUs;
        final String name = sourceName;
        final boolean toAll = allAccounts.getVisibility() == View.VISIBLE && allAccounts.isChecked();
        new Thread(() -> {
            String error = null;
            File out = null;
            try {
                float[] pcm = extractMono48k(uri, start, end);
                if (pcm.length < OUT_RATE / 10) throw new IOException("фрагмент получился пустым");
                shape(pcm);
                out = uniqueFile(name);
                int[] bitrates = {96_000, 64_000, 48_000, 32_000, 24_000};
                boolean ok = false;
                for (int bitrate : bitrates) {
                    Encoder.encodeOggOpus(pcm, out.getAbsolutePath(), bitrate);
                    if (out.length() > 0 && out.length() <= sizeMax) {
                        ok = true;
                        break;
                    }
                }
                if (!ok) throw new IOException("не удалось уложиться в " + sizeMax / 1024 + " КБ");
            } catch (Throwable e) {
                FileLog.e(e);
                error = e.getMessage() != null ? e.getMessage() : e.toString();
                if (out != null) out.delete();
            }
            final String finalError = error;
            final File finalOut = out;
            runOnUiThread(() -> {
                busy = false;
                if (isFinishing()) return;
                if (finalError != null) {
                    status.setText("Не получилось: " + finalError);
                    updateUi();
                    return;
                }
                upload(finalOut, toAll);
                updateUi();
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
            accounts.add(UserConfig.selectedAccount);
        }
        for (int account : accounts) {
            MediaDataController.getInstance(account).uploadRingtone(file.getAbsolutePath());
        }
        status.setText("Загружаю «" + file.getName().replace(".ogg", "") + "» (" + Math.max(1, file.length() / 1024) + " КБ)"
                + (accounts.size() > 1 ? " в " + accounts.size() + " аккаунта" : "")
                + ". Звук появится в выборе звука уведомлений, в разделе Telegram.");
        toast("Звук загружается в Telegram");
    }

    private File uniqueFile(String name) {
        File dir = new File(getFilesDir(), "melgram_tones");
        dir.mkdirs();
        File f = new File(dir, name + ".ogg");
        for (int i = 2; f.exists(); i++) {
            f = new File(dir, name + " " + i + ".ogg");
        }
        return f;
    }

    /** Decodes [start, end) of the audio track to mono float PCM at 48 kHz. */
    private float[] extractMono48k(Uri uri, long startUs, long endUs) throws IOException {
        final FloatList collected = new FloatList(OUT_RATE * 6);
        final int[] sourceRate = {0};
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(this, uri, null);
            int track = findAudioTrack(ex);
            if (track < 0) throw new IOException("в файле нет звуковой дорожки");
            decode(ex, track, startUs, (pcm, count, rate, ptsUs) -> {
                sourceRate[0] = rate;
                for (int i = 0; i < count; i++) {
                    long t = ptsUs + i * 1_000_000L / rate;
                    if (t < startUs) continue;
                    if (t >= endUs) return false;
                    collected.add(pcm[i]);
                }
                return !cancelled;
            });
        } finally {
            ex.release();
        }
        if (sourceRate[0] <= 0) throw new IOException("не получилось раскодировать звук");
        return resample(collected.toArray(), sourceRate[0], OUT_RATE);
    }

    private static float[] resample(float[] in, int fromRate, int toRate) {
        if (fromRate == toRate || in.length == 0) return in;
        int outLen = (int) ((long) in.length * toRate / fromRate);
        float[] out = new float[outLen];
        double ratio = (double) fromRate / toRate;
        for (int i = 0; i < outLen; i++) {
            double pos = i * ratio;
            int i0 = (int) pos;
            int i1 = Math.min(in.length - 1, i0 + 1);
            float frac = (float) (pos - i0);
            out[i] = in[i0] * (1f - frac) + in[i1] * frac;
        }
        return out;
    }

    /** Gentle normalisation plus short fades so the sound does not click. */
    private static void shape(float[] pcm) {
        float peak = 0f;
        for (float v : pcm) peak = Math.max(peak, Math.abs(v));
        float gain = peak > 0.001f ? Math.min(3f, 0.95f / peak) : 1f;
        int fadeIn = Math.min(pcm.length / 4, OUT_RATE * 15 / 1000);
        int fadeOut = Math.min(pcm.length / 4, OUT_RATE * 80 / 1000);
        for (int i = 0; i < pcm.length; i++) {
            float k = gain;
            if (i < fadeIn) k *= (float) i / fadeIn;
            int fromEnd = pcm.length - 1 - i;
            if (fromEnd < fadeOut) k *= (float) fromEnd / fadeOut;
            pcm[i] = Math.max(-1f, Math.min(1f, pcm[i] * k));
        }
    }

    // ------------------------------------------------------------------ decoding

    private interface PcmSink {
        /** @return false to stop decoding */
        boolean onPcm(float[] mono, int count, int sampleRate, long ptsUs);
    }

    private static void decode(MediaExtractor ex, int track, long startUs, PcmSink sink) throws IOException {
        MediaFormat fmt = ex.getTrackFormat(track);
        String mime = fmt.getString(MediaFormat.KEY_MIME);
        ex.selectTrack(track);
        if (startUs > 0) {
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        }
        MediaCodec dec = MediaCodec.createDecoderByType(mime);
        dec.configure(fmt, null, null, 0);
        dec.start();
        int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
        int rate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        boolean isFloat = false;
        float[] mono = new float[4096];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;
        int idle = 0;
        try {
            while (!outputDone) {
                if (!inputDone) {
                    int ii = dec.dequeueInputBuffer(10_000);
                    if (ii >= 0) {
                        ByteBuffer buffer = dec.getInputBuffer(ii);
                        int size = buffer == null ? -1 : ex.readSampleData(buffer, 0);
                        if (size < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, size, Math.max(0, ex.getSampleTime()), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, 10_000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (Build.VERSION.SDK_INT >= 24 && of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        isFloat = of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
                    }
                } else if (oi >= 0) {
                    idle = 0;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                    boolean keepGoing = true;
                    if (info.size > 0 && channels > 0) {
                        ByteBuffer ob = dec.getOutputBuffer(oi);
                        if (ob != null) {
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            ByteBuffer data = ob.slice().order(ByteOrder.nativeOrder());
                            int frames = isFloat ? info.size / 4 / channels : info.size / 2 / channels;
                            if (mono.length < frames) mono = new float[frames];
                            if (isFloat) {
                                FloatBuffer fb = data.asFloatBuffer();
                                for (int f = 0; f < frames; f++) {
                                    float sum = 0;
                                    for (int c = 0; c < channels; c++) sum += fb.get();
                                    mono[f] = sum / channels;
                                }
                            } else {
                                ShortBuffer sb = data.asShortBuffer();
                                for (int f = 0; f < frames; f++) {
                                    float sum = 0;
                                    for (int c = 0; c < channels; c++) sum += sb.get() / 32768f;
                                    mono[f] = sum / channels;
                                }
                            }
                            keepGoing = sink.onPcm(mono, frames, rate, info.presentationTimeUs);
                        }
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if (!keepGoing) break;
                } else if (inputDone && ++idle > 500) {
                    break; // decoder stalled after end of input
                }
            }
        } finally {
            try {
                dec.stop();
            } catch (Throwable ignore) {
            }
            dec.release();
        }
    }

    // ------------------------------------------------------------------ encoding

    @TargetApi(29)
    private static final class Encoder {
        static void encodeOggOpus(float[] pcm, String path, int bitrate) throws IOException {
            new File(path).delete();
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OUT_RATE, 1);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            MediaMuxer muxer = null;
            boolean muxerStarted = false;
            try {
                enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                enc.start();
                muxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int track = -1;
                int pos = 0;
                boolean inputDone = false;
                boolean outputDone = false;
                int idle = 0;
                while (!outputDone) {
                    if (!inputDone) {
                        int ii = enc.dequeueInputBuffer(10_000);
                        if (ii >= 0) {
                            ByteBuffer buffer = enc.getInputBuffer(ii);
                            buffer.clear();
                            buffer.order(ByteOrder.nativeOrder());
                            int n = Math.min(Math.min(buffer.capacity() / 2, 960 * 4), pcm.length - pos);
                            long pts = (long) pos * 1_000_000L / OUT_RATE;
                            if (n <= 0) {
                                enc.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                for (int k = 0; k < n; k++) {
                                    buffer.putShort((short) Math.round(pcm[pos + k] * 32767f));
                                }
                                enc.queueInputBuffer(ii, 0, n * 2, pts, 0);
                                pos += n;
                            }
                        }
                    }
                    int oi = enc.dequeueOutputBuffer(info, 10_000);
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(enc.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                    } else if (oi >= 0) {
                        idle = 0;
                        ByteBuffer ob = enc.getOutputBuffer(oi);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            info.size = 0;
                        }
                        if (info.size > 0 && muxerStarted && ob != null) {
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            muxer.writeSampleData(track, ob, info);
                        }
                        enc.releaseOutputBuffer(oi, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true;
                        }
                    } else if (inputDone && ++idle > 500) {
                        break;
                    }
                }
            } finally {
                try {
                    enc.stop();
                } catch (Throwable ignore) {
                }
                enc.release();
                if (muxer != null) {
                    try {
                        if (muxerStarted) muxer.stop();
                    } catch (Throwable ignore) {
                    }
                    muxer.release();
                }
            }
        }
    }

    // ------------------------------------------------------------------ waveform view

    private final class WaveView extends View {
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private long dragOffsetUs;

        WaveView(Context context) {
            super(context);
            selPaint.setColor((colorAccent & 0x00FFFFFF) | 0x33000000);
            framePaint.setColor(colorAccent);
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setStrokeWidth(dp(2));
            headPaint.setColor(colorText);
            headPaint.setStrokeWidth(dp(1.5f));
        }

        private float xOf(long us) {
            return durationUs <= 0 ? 0 : (float) us / durationUs * getWidth();
        }

        private long usOf(float x) {
            return durationUs <= 0 ? 0 : (long) (x / Math.max(1, getWidth()) * durationUs);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            float mid = h / 2f;
            float barW = dp(2);
            float gap = dp(1.5f);
            int bars = (int) (w / (barW + gap));
            if (buckets.length == 0 || durationUs <= 0) {
                barPaint.setColor(colorWave);
                for (int i = 0; i < bars; i++) {
                    float x = i * (barW + gap);
                    rect.set(x, mid - dp(1), x + barW, mid + dp(1));
                    canvas.drawRoundRect(rect, barW / 2, barW / 2, barPaint);
                }
                return;
            }
            float selL = xOf(selStartUs);
            float selR = xOf(selStartUs + selLenUs);
            rect.set(selL, dp(2), selR, h - dp(2));
            canvas.drawRoundRect(rect, dp(6), dp(6), selPaint);
            for (int i = 0; i < bars; i++) {
                int from = (int) ((long) i * buckets.length / bars);
                int to = (int) ((long) (i + 1) * buckets.length / bars);
                float v = 0;
                for (int k = from; k < Math.max(from + 1, to) && k < buckets.length; k++) v = Math.max(v, buckets[k]);
                float half = Math.max(dp(1), v * (h / 2f - dp(8)));
                float x = i * (barW + gap);
                barPaint.setColor(x + barW >= selL && x <= selR ? colorAccent : colorWave);
                rect.set(x, mid - half, x + barW, mid + half);
                canvas.drawRoundRect(rect, barW / 2, barW / 2, barPaint);
            }
            rect.set(selL, dp(2), selR, h - dp(2));
            canvas.drawRoundRect(rect, dp(6), dp(6), framePaint);
            if (playStartedAt > 0) {
                long played = (System.currentTimeMillis() - playStartedAt) * 1000;
                float x = xOf(selStartUs + Math.min(played, selLenUs));
                canvas.drawLine(x, dp(4), x, h - dp(4), headPaint);
            }
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
                        updateUi();
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    selStartUs = t - dragOffsetUs;
                    clampSelection();
                    updateUi();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
            }
            return super.onTouchEvent(e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static final class FloatList {
        private float[] data;
        private int size;

        FloatList(int capacity) {
            data = new float[Math.max(16, capacity)];
        }

        private void ensure(int capacity) {
            if (capacity > data.length) {
                float[] bigger = new float[Math.max(capacity, data.length * 2)];
                System.arraycopy(data, 0, bigger, 0, size);
                data = bigger;
            }
        }

        void add(float v) {
            ensure(size + 1);
            data[size++] = v;
        }

        /** data[index] = max(data[index], v), growing the list with zeros if needed */
        void max(int index, float v) {
            if (index < 0) return;
            if (index >= size) {
                ensure(index + 1);
                size = index + 1;
            }
            if (v > data[index]) data[index] = v;
        }

        float[] toArray() {
            float[] out = new float[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
    }

    private static String time(long us) {
        long tenths = us / 100_000;
        long sec = tenths / 10;
        return String.format(Locale.US, "%d:%02d,%d", sec / 60, sec % 60, tenths % 10);
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setMinHeight(dp(46));
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
        return b;
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
