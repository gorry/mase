package net.gorry.mase;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 音声データをファイルに書き出す。チャンネル数とサンプリングレートは、内部の音声データのまま。
 * 仕様は memo/doc/spec.md の「保存・名前を付けて保存・選択範囲を保存」。
 */
final class AudioSaver {
    static final int FORMAT_WAV = 0;
    static final int FORMAT_MP3 = 1;
    static final int FORMAT_M4A = 2;

    /** ファイル出力のパラメータ */
    static final class Params {
        int format = FORMAT_WAV;
        /** wav の量子化ビット数（8 か 16） */
        int bits = 16;
        /** mp3・m4a のビットレート（bps） */
        int bitRate = 128_000;

        String extension() {
            return format == FORMAT_WAV ? ".wav" : format == FORMAT_MP3 ? ".mp3" : ".m4a";
        }

        String mimeType() {
            return format == FORMAT_WAV ? "audio/x-wav" : format == FORMAT_MP3 ? "audio/mpeg" : "audio/mp4";
        }

        /** 書き出すと、内部の音声データと違う内容になるか（圧縮や 8 ビットで音が変わる） */
        boolean lossy() {
            return !(format == FORMAT_WAV && bits == 16);
        }
    }

    private AudioSaver() {
    }

    /** このサンプリングレートとチャンネル数で、m4a に書き出せるか（端末のエンコーダーによる） */
    static boolean canWriteM4a(int sampleRate, int channels) {
        try {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
            return new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** [start, end) を uri に書き出す。失敗したら IOException */
    static void save(Context context, Uri uri, AudioData data, int start, int end, Params params)
            throws IOException {
        if (params.format == FORMAT_WAV) {
            try (OutputStream raw = context.getContentResolver().openOutputStream(uri, "wt")) {
                if (raw == null) {
                    throw new IOException("cannot open " + uri);
                }
                OutputStream out = new BufferedOutputStream(raw, 1 << 16);
                writeWav(out, data, start, end, params.bits);
                out.flush();
            }
            return;
        }
        // mp3・m4a は、いったんアプリの作業用の場所に作ってから、保存先へ写す（書き出しに、途中へ戻って書ける場所が要るため）
        File temp = File.createTempFile("save", params.extension(), context.getCacheDir());
        try {
            if (params.format == FORMAT_MP3) {
                Mp3Encoder.write(temp, data, start, end, params.bitRate);
            } else {
                writeM4a(temp, data, start, end, params.bitRate);
            }
            try (InputStream in = new FileInputStream(temp);
                 OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) {
                    throw new IOException("cannot open " + uri);
                }
                byte[] buffer = new byte[1 << 16];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static void writeWav(OutputStream out, AudioData data, int start, int end, int bits)
            throws IOException {
        int channels = data.channels;
        int bytesPerSample = bits / 8;
        long dataBytes = (long) (end - start) * channels * bytesPerSample;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'R', 'I', 'F', 'F'});
        header.putInt((int) (36 + dataBytes));
        header.put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        header.putInt(16);
        header.putShort((short) 1); // PCM
        header.putShort((short) channels);
        header.putInt(data.sampleRate);
        header.putInt(data.sampleRate * channels * bytesPerSample);
        header.putShort((short) (channels * bytesPerSample));
        header.putShort((short) bits);
        header.put(new byte[]{'d', 'a', 't', 'a'});
        header.putInt((int) dataBytes);
        out.write(header.array());

        byte[] chunk = new byte[4096 * channels * bytesPerSample];
        int pos = start;
        while (pos < end) {
            int frames = Math.min(4096, end - pos);
            int o = 0;
            for (int i = 0; i < frames; i++) {
                for (int c = 0; c < channels; c++) {
                    short v = data.samples[c][pos + i];
                    if (bits == 8) {
                        chunk[o++] = (byte) ((v >> 8) + 128); // 8 ビットの wav は、符号なし
                    } else {
                        chunk[o++] = (byte) v;
                        chunk[o++] = (byte) (v >> 8);
                    }
                }
            }
            out.write(chunk, 0, o);
            pos += frames;
        }
        if ((dataBytes & 1) != 0) {
            out.write(0); // データの長さが奇数バイトのときは、1 バイト足して偶数にする
        }
    }

    private static void writeM4a(File file, AudioData data, int start, int end, int bitRate)
            throws IOException {
        int channels = data.channels;
        int rate = data.sampleRate;
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 16);

        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        boolean muxing = false;
        try {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            muxer = new MediaMuxer(file.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int track = -1;
            int pos = start;
            boolean inputDone = false;
            boolean outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    int in = encoder.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer buf = encoder.getInputBuffer(in);
                        long timeUs = (long) (pos - start) * 1_000_000L / rate;
                        if (buf == null || pos >= end) {
                            encoder.queueInputBuffer(in, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            buf.clear();
                            buf.order(ByteOrder.nativeOrder());
                            int frames = Math.min(end - pos, Math.min(4096, buf.capacity() / (2 * channels)));
                            for (int i = 0; i < frames; i++) {
                                for (int c = 0; c < channels; c++) {
                                    buf.putShort(data.samples[c][pos + i]);
                                }
                            }
                            encoder.queueInputBuffer(in, 0, frames * 2 * channels, timeUs, 0);
                            pos += frames;
                        }
                    }
                }
                int out = encoder.dequeueOutputBuffer(info, 10_000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxing = true;
                } else if (out >= 0) {
                    ByteBuffer buf = encoder.getOutputBuffer(out);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (buf != null && info.size > 0 && !config && muxing) {
                        buf.position(info.offset).limit(info.offset + info.size);
                        muxer.writeSampleData(track, buf, info);
                    }
                    encoder.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("m4a encoding failed", e);
        } finally {
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (RuntimeException ignored) {
                }
                encoder.release();
            }
            if (muxer != null) {
                try {
                    if (muxing) {
                        muxer.stop();
                    }
                } catch (RuntimeException ignored) {
                }
                muxer.release();
            }
        }
    }
}
