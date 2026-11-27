package net.gorry.mase;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 音声ファイルを読み込み、内部の音声データ（16bit signed、2ch まで）にする。
 * wav・mp3・mp4 を、Android 標準の MediaExtractor と MediaCodec で読む。
 */
final class AudioLoader {
    private static final String TAG = "mase.AudioLoader";

    /** 読み込めなかった理由 */
    enum Failure {
        CANNOT_OPEN, UNSUPPORTED, CORRUPT, NO_AUDIO, MEMORY
    }

    static final class LoadException extends Exception {
        final Failure failure;

        LoadException(Failure failure, Throwable cause) {
            super(failure.name(), cause);
            this.failure = failure;
        }
    }

    /** 読み込みを途中でやめたとき */
    static final class CancelledException extends Exception {
    }

    /** 入力が 3ch 以上のときに、読み込むチャンネルを選んでもらう */
    interface ChannelChooser {
        /**
         * @return 選んだチャンネルの番号（0 始まり）。1 つか 2 つ。やめたときは null
         */
        int[] choose(int channelCount);
    }

    static final class Result {
        AudioData data;
        /** 長さの上限で、末尾を切り捨てた */
        boolean truncated;
    }

    private AudioLoader() {
    }

    static Result load(Context context, Uri uri, ChannelChooser chooser, AtomicBoolean cancel)
            throws LoadException, CancelledException {
        // 開けるかどうかを、形式の判定より先に確かめる（理由を分けるため）
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) {
                throw new LoadException(Failure.CANNOT_OPEN, null);
            }
        } catch (LoadException e) {
            throw e;
        } catch (Exception e) {
            Log.w(TAG, "open failed: " + uri, e);
            throw new LoadException(Failure.CANNOT_OPEN, e);
        }

        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            try {
                extractor.setDataSource(context, uri, null);
            } catch (Exception e) {
                Log.w(TAG, "unsupported container: " + uri, e);
                throw new LoadException(Failure.UNSUPPORTED, e);
            }

            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) {
                throw new LoadException(Failure.NO_AUDIO, null);
            }
            extractor.selectTrack(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0;

            Sink sink = new Sink(chooser, cancel, durationUs);
            try {
                if (MediaFormat.MIMETYPE_AUDIO_RAW.equals(mime)) {
                    readRaw(extractor, format, sink);
                } else {
                    try {
                        codec = MediaCodec.createDecoderByType(mime);
                        codec.configure(format, null, null, 0);
                        codec.start();
                    } catch (Exception e) {
                        Log.w(TAG, "no decoder for " + mime, e);
                        throw new LoadException(Failure.UNSUPPORTED, e);
                    }
                    decode(extractor, codec, sink);
                }
            } catch (OutOfMemoryError e) {
                sink.release();
                Log.w(TAG, "out of memory", e);
                throw new LoadException(Failure.MEMORY, e);
            } catch (LoadException | CancelledException e) {
                throw e;
            } catch (Exception e) {
                Log.w(TAG, "decode failed", e);
                throw new LoadException(Failure.CORRUPT, e);
            }

            if (sink.length <= 0) {
                throw new LoadException(Failure.CORRUPT, null);
            }
            Result result = new Result();
            try {
                result.data = new AudioData(sink.buffers, sink.length, sink.sampleRate);
            } catch (OutOfMemoryError e) {
                sink.release();
                throw new LoadException(Failure.MEMORY, e);
            }
            result.truncated = sink.truncated;
            return result;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                }
                codec.release();
            }
            extractor.release();
        }
    }

    /** wav など、圧縮していない音声。デコーダーを通さずに読む */
    private static void readRaw(MediaExtractor extractor, MediaFormat format, Sink sink)
            throws LoadException, CancelledException {
        sink.setFormat(format);
        ByteBuffer buffer = ByteBuffer.allocateDirect(1 << 18).order(ByteOrder.nativeOrder());
        while (!sink.full()) {
            buffer.clear();
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) {
                break;
            }
            buffer.position(0).limit(size);
            sink.write(buffer, extractor.getSampleTime());
            if (!extractor.advance()) {
                break;
            }
        }
    }

    private static void decode(MediaExtractor extractor, MediaCodec codec, Sink sink)
            throws LoadException, CancelledException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;
        int idle = 0;
        while (!outputDone && !sink.full()) {
            if (!inputDone) {
                int in = codec.dequeueInputBuffer(10_000);
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
            int out = codec.dequeueOutputBuffer(info, 10_000);
            if (out >= 0) {
                idle = 0;
                if (info.size > 0) {
                    if (!sink.hasFormat()) {
                        sink.setFormat(codec.getOutputFormat());
                    }
                    ByteBuffer buf = codec.getOutputBuffer(out);
                    if (buf != null) {
                        buf.position(info.offset).limit(info.offset + info.size);
                        sink.write(buf.order(ByteOrder.nativeOrder()), info.presentationTimeUs);
                    }
                }
                codec.releaseOutputBuffer(out, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                sink.setFormat(codec.getOutputFormat());
            } else if (inputDone && ++idle > 500) {
                // 入力を出し切ったのに、出力が終わりを告げない。ここまでの分で打ち切る
                Log.w(TAG, "decoder did not signal end of stream");
                break;
            }
        }
    }

    /** デコードした PCM を受け取り、チャンネルを選び、16bit にして貯める */
    private static final class Sink {
        private final ChannelChooser chooser;
        private final AtomicBoolean cancel;
        private final long durationUs;

        private boolean hasFormat;
        private int inChannels;
        private int encoding;
        private int bytesPerSample;
        private int[] picked;

        int sampleRate;
        short[][] buffers;
        int length;
        int maxLength;
        boolean truncated;

        Sink(ChannelChooser chooser, AtomicBoolean cancel, long durationUs) {
            this.chooser = chooser;
            this.cancel = cancel;
            this.durationUs = durationUs;
        }

        boolean hasFormat() {
            return hasFormat;
        }

        boolean full() {
            return truncated;
        }

        void release() {
            buffers = null;
        }

        void setFormat(MediaFormat format) throws LoadException, CancelledException {
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int enc = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                    ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
            int bytes;
            switch (enc) {
                case AudioFormat.ENCODING_PCM_8BIT:
                    bytes = 1;
                    break;
                case AudioFormat.ENCODING_PCM_16BIT:
                    bytes = 2;
                    break;
                case AudioFormat.ENCODING_PCM_24BIT_PACKED:
                    bytes = 3;
                    break;
                case AudioFormat.ENCODING_PCM_32BIT:
                case AudioFormat.ENCODING_PCM_FLOAT:
                    bytes = 4;
                    break;
                default:
                    throw new LoadException(Failure.UNSUPPORTED, null);
            }
            if (channels <= 0 || rate <= 0) {
                throw new LoadException(Failure.CORRUPT, null);
            }
            if (hasFormat) {
                if (channels != inChannels || rate != sampleRate) {
                    // 途中でチャンネル数やサンプリングレートが変わるものは、扱えない
                    throw new LoadException(Failure.UNSUPPORTED, null);
                }
                encoding = enc;
                bytesPerSample = bytes;
                return;
            }
            inChannels = channels;
            sampleRate = rate;
            encoding = enc;
            bytesPerSample = bytes;
            if (channels <= 2) {
                picked = channels == 1 ? new int[]{0} : new int[]{0, 1};
            } else {
                picked = chooser.choose(channels);
                if (picked == null) {
                    throw new CancelledException();
                }
            }
            maxLength = AudioData.maxLength(rate);
            // 長さの見当を付けて確保する。足りなければ伸ばす
            long estimate = durationUs > 0 ? durationUs * rate / 1_000_000L + rate : rate * 60L;
            int capacity = (int) Math.max(1024, Math.min(maxLength, estimate));
            buffers = new short[picked.length][capacity];
            hasFormat = true;
        }

        void write(ByteBuffer src, long timeUs) throws CancelledException {
            if (cancel.get()) {
                throw new CancelledException();
            }
            int frameBytes = bytesPerSample * inChannels;
            int frames = src.remaining() / frameBytes;
            if (frames <= 0) {
                return;
            }
            if (frames > maxLength - length) {
                frames = maxLength - length;
                truncated = true;
            }
            ensureCapacity(length + frames);
            int base = src.position();
            for (int k = 0; k < picked.length; k++) {
                short[] dst = buffers[k];
                int offset = base + picked[k] * bytesPerSample;
                int at = length;
                switch (encoding) {
                    case AudioFormat.ENCODING_PCM_8BIT:
                        for (int i = 0; i < frames; i++, offset += frameBytes) {
                            dst[at++] = (short) (((src.get(offset) & 0xff) - 128) << 8);
                        }
                        break;
                    case AudioFormat.ENCODING_PCM_16BIT:
                        for (int i = 0; i < frames; i++, offset += frameBytes) {
                            dst[at++] = src.getShort(offset);
                        }
                        break;
                    case AudioFormat.ENCODING_PCM_24BIT_PACKED:
                        // 下位バイトから並ぶ。上位 2 バイトを使う
                        for (int i = 0; i < frames; i++, offset += frameBytes) {
                            dst[at++] = (short) ((src.get(offset + 1) & 0xff) | (src.get(offset + 2) << 8));
                        }
                        break;
                    case AudioFormat.ENCODING_PCM_32BIT:
                        for (int i = 0; i < frames; i++, offset += frameBytes) {
                            dst[at++] = (short) (src.getInt(offset) >> 16);
                        }
                        break;
                    default: // ENCODING_PCM_FLOAT
                        for (int i = 0; i < frames; i++, offset += frameBytes) {
                            float v = src.getFloat(offset) * 32768f;
                            dst[at++] = (short) Math.max(-32768f, Math.min(32767f, v));
                        }
                        break;
                }
            }
            length += frames;
        }

        private void ensureCapacity(int need) {
            int capacity = buffers[0].length;
            if (need <= capacity) {
                return;
            }
            long grown = Math.max(need, capacity + (long) capacity / 2);
            int next = (int) Math.min(maxLength, grown);
            for (int k = 0; k < buffers.length; k++) {
                short[] bigger = new short[next];
                System.arraycopy(buffers[k], 0, bigger, 0, length);
                buffers[k] = bigger;
            }
        }
    }
}
