package org.telegram.messenger;

import android.annotation.TargetApi;
import android.content.Context;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Melgram: audio helpers for notification sounds. Decodes any audio or video
 * file Android can read, cuts a fragment, makes it mono 48 kHz and encodes it to
 * Ogg/Opus within Telegram's ringtone limits.
 */
public final class MelgramToneTools {

    public static final long MAX_SOURCE_US = 5L * 60 * 1_000_000 + 999_999;
    public static final long BUCKET_US = 20_000;
    private static final int OUT_RATE = 48_000;

    private MelgramToneTools() {
    }

    public static boolean canEncode() {
        return Build.VERSION.SDK_INT >= 29;
    }

    public static final class Analysis {
        public float[] peaks = new float[0]; // 0..1, one value per BUCKET_US
        public long durationUs;
        public boolean tooLong;
    }

    // ---------------------------------------------------------------- analyse

    public static Analysis analyze(Context context, Uri uri, AtomicBoolean cancel) throws IOException {
        final Analysis result = new Analysis();
        final FloatList peaks = new FloatList(16_000);
        final long[] lastUs = {0};
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(context, uri, null);
            int track = findAudioTrack(ex);
            if (track < 0) throw new IOException("в файле нет звуковой дорожки");
            MediaFormat fmt = ex.getTrackFormat(track);
            if (fmt.containsKey(MediaFormat.KEY_DURATION) && fmt.getLong(MediaFormat.KEY_DURATION) > MAX_SOURCE_US) {
                result.tooLong = true;
                return result;
            }
            decode(ex, track, 0, (pcm, count, rate, ptsUs) -> {
                for (int i = 0; i < count; i++) {
                    long t = ptsUs + i * 1_000_000L / rate;
                    peaks.max((int) (t / BUCKET_US), Math.abs(pcm[i]));
                }
                lastUs[0] = Math.max(lastUs[0], ptsUs + count * 1_000_000L / rate);
                if (lastUs[0] > MAX_SOURCE_US) {
                    result.tooLong = true;
                    return false;
                }
                return !cancel.get();
            });
        } finally {
            ex.release();
        }
        if (result.tooLong) return result;
        if (lastUs[0] <= 0) throw new IOException("не получилось раскодировать звук");
        float[] arr = peaks.toArray();
        float max = 0.0001f;
        for (float v : arr) max = Math.max(max, v);
        for (int i = 0; i < arr.length; i++) arr[i] /= max;
        result.peaks = arr;
        result.durationUs = lastUs[0];
        return result;
    }

    // ---------------------------------------------------------------- make tone

    /** Cuts [startUs, endUs), encodes it and returns the .ogg file ready for upload. */
    public static File makeTone(Context context, Uri uri, long startUs, long endUs, String name, int sizeMax, AtomicBoolean cancel) throws IOException {
        if (!canEncode()) throw new IOException("нужен Android 10 или новее");
        float[] pcm = extractMono48k(context, uri, startUs, endUs, cancel);
        if (pcm.length < OUT_RATE / 10) throw new IOException("фрагмент получился пустым");
        shape(pcm);
        File out = uniqueFile(context, name);
        int[] bitrates = {96_000, 64_000, 48_000, 32_000, 24_000};
        for (int bitrate : bitrates) {
            Encoder.encodeOggOpus(pcm, out.getAbsolutePath(), bitrate);
            if (out.length() > 0 && out.length() <= sizeMax) {
                return out;
            }
        }
        out.delete();
        throw new IOException("не удалось уложиться в " + sizeMax / 1024 + " КБ");
    }

    public static String displayName(Context context, Uri uri) {
        String name = null;
        try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
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

    private static File uniqueFile(Context context, String name) {
        File dir = new File(context.getFilesDir(), "melgram_tones");
        dir.mkdirs();
        File f = new File(dir, name + ".ogg");
        for (int i = 2; f.exists(); i++) {
            f = new File(dir, name + " " + i + ".ogg");
        }
        return f;
    }

    private static float[] extractMono48k(Context context, Uri uri, long startUs, long endUs, AtomicBoolean cancel) throws IOException {
        final FloatList collected = new FloatList(OUT_RATE * 6);
        final int[] sourceRate = {0};
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(context, uri, null);
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
                return !cancel.get();
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

    // ---------------------------------------------------------------- decoding

    private interface PcmSink {
        /** @return false to stop decoding */
        boolean onPcm(float[] mono, int count, int sampleRate, long ptsUs);
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

    // ---------------------------------------------------------------- encoding

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

    // ---------------------------------------------------------------- helpers

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
}
