package dev.notune.transcribe;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decodes an audio file to 16 kHz mono float samples and hands them out in
 * fixed-size blocks, so memory stays bounded no matter how long the file is.
 *
 * The sample rate, channel count and PCM encoding are taken from the
 * decoder's output format: it can differ from the container's (HE-AAC
 * doubles the rate, some decoders emit float PCM).
 */
final class AudioFileDecoder {
    private static final String TAG = "OfflineVoiceInput";
    static final int TARGET_RATE = 16000;
    /** Samples per block handed to the sink (10 s). */
    private static final int BLOCK = 10 * TARGET_RATE;
    private static final long TIMEOUT_US = 10_000;
    /** Give up if the decoder produces nothing for this long after EOS input. */
    private static final int MAX_IDLE_POLLS = 300;

    interface Sink {
        /** Receives {@code length} samples; return false to stop decoding. */
        boolean onSamples(float[] samples, int length);
    }

    interface Cancel {
        boolean isCancelled();
    }

    private final Context context;
    private final Uri uri;
    private long durationUs = -1;

    AudioFileDecoder(Context context, Uri uri) {
        this.context = context;
        this.uri = uri;
    }

    /** Duration from the container, or -1 if unknown. Valid after decode() started. */
    long durationUs() {
        return durationUs;
    }

    /** Decodes the whole file into the sink. Returns the number of 16 kHz samples produced. */
    long decode(Sink sink, Cancel cancel) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(context, uri, null);
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    inputFormat = format;
                    break;
                }
            }
            if (inputFormat == null) throw new IOException("no audio track");
            if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                durationUs = inputFormat.getLong(MediaFormat.KEY_DURATION);
            }

            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();

            Output out = new Output(sink);
            out.setFormat(inputFormat);
            Log.i(TAG, "Decoding " + mime + " " + out.rate + " Hz, " + out.channels + " ch");

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            int idlePolls = 0;
            while (!cancel.isCancelled()) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (in >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(in);
                        int size = buf == null ? -1 : extractor.readSampleData(buf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int index = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    out.setFormat(codec.getOutputFormat());
                    continue;
                }
                if (index < 0) {
                    if (inputDone && ++idlePolls > MAX_IDLE_POLLS) {
                        Log.w(TAG, "Decoder never signalled end of stream; stopping");
                        break;
                    }
                    continue;
                }
                idlePolls = 0;
                ByteBuffer buf = codec.getOutputBuffer(index);
                boolean keepGoing = true;
                if (buf != null && info.size > 0) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    keepGoing = out.write(buf.order(ByteOrder.LITTLE_ENDIAN));
                }
                codec.releaseOutputBuffer(index, false);
                if (!keepGoing || (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
            out.flush();
            return out.produced;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (RuntimeException ignored) { }
                codec.release();
            }
            extractor.release();
        }
    }

    /** Mixes to mono, resamples to 16 kHz and batches into blocks. */
    private static final class Output {
        private final Sink sink;
        private final float[] block = new float[BLOCK];
        private int filled = 0;
        long produced = 0;
        private boolean stopped = false;

        int rate = TARGET_RATE;
        int channels = 1;
        private int encoding = AudioFormat.ENCODING_PCM_16BIT;
        private final Resampler resampler = new Resampler();

        Output(Sink sink) {
            this.sink = sink;
        }

        void setFormat(MediaFormat f) {
            if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = Math.max(1, f.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING);
            resampler.setRates(rate, TARGET_RATE);
        }

        boolean write(ByteBuffer pcm) {
            int frames;
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                java.nio.FloatBuffer fb = pcm.asFloatBuffer();
                frames = fb.remaining() / channels;
                for (int i = 0; i < frames && !stopped; i++) {
                    float sum = 0;
                    for (int c = 0; c < channels; c++) sum += fb.get();
                    resampler.push(sum / channels, this);
                }
            } else if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
                frames = pcm.remaining() / channels;
                for (int i = 0; i < frames && !stopped; i++) {
                    float sum = 0;
                    for (int c = 0; c < channels; c++) sum += ((pcm.get() & 0xff) - 128) / 128f;
                    resampler.push(sum / channels, this);
                }
            } else {
                java.nio.ShortBuffer sb = pcm.asShortBuffer();
                frames = sb.remaining() / channels;
                for (int i = 0; i < frames && !stopped; i++) {
                    float sum = 0;
                    for (int c = 0; c < channels; c++) sum += sb.get() / 32768f;
                    resampler.push(sum / channels, this);
                }
            }
            return !stopped;
        }

        void emit(float sample) {
            if (stopped) return;
            block[filled++] = sample;
            produced++;
            if (filled == block.length) flush();
        }

        void flush() {
            if (filled > 0 && !stopped) {
                stopped = !sink.onSamples(block, filled);
            }
            filled = 0;
        }
    }

    /**
     * Streaming resampler: a windowed-sinc low-pass (against aliasing when
     * downsampling, e.g. 44.1/48 kHz recordings) followed by linear
     * interpolation, keeping its state across buffers.
     */
    private static final class Resampler {
        private static final int TAPS = 31;
        private float[] taps = null;
        private final float[] history = new float[TAPS];
        private int pos = 0;
        private double step = 1.0;
        private double t = 0.0;
        private float prev = 0f;
        private boolean havePrev = false;

        void setRates(int from, int to) {
            step = (double) from / to;
            if (from > to) {
                // Cutoff a bit below the target Nyquist, normalized to `from`.
                double fc = 0.45 * to / from;
                taps = new float[TAPS];
                double sum = 0;
                int mid = TAPS / 2;
                for (int i = 0; i < TAPS; i++) {
                    int n = i - mid;
                    double sinc = n == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * n) / (Math.PI * n);
                    double window = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (TAPS - 1));
                    taps[i] = (float) (sinc * window);
                    sum += taps[i];
                }
                for (int i = 0; i < TAPS; i++) taps[i] /= sum;
            } else {
                taps = null;
            }
        }

        void push(float x, Output out) {
            float y = x;
            if (taps != null) {
                history[pos] = x;
                pos = (pos + 1) % TAPS;
                float acc = 0;
                for (int i = 0; i < TAPS; i++) acc += taps[i] * history[(pos + i) % TAPS];
                y = acc;
            }
            if (!havePrev) {
                prev = y;
                havePrev = true;
                return;
            }
            // Emit every output sample that falls between prev (t=0) and y (t=1).
            while (t < 1.0) {
                out.emit((float) (prev + (y - prev) * t));
                t += step;
            }
            t -= 1.0;
            prev = y;
        }
    }
}
