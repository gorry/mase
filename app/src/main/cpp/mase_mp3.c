/*
 * Java の Mp3Encoder から LAME（libmp3lame.so）を呼ぶための橋渡し。
 */
#include <jni.h>
#include <stdint.h>
#include <lame.h>

static void quiet(const char *format, va_list args) {
    (void) format;
    (void) args;
}

/* エンコーダーを作る。作れなければ 0 */
JNIEXPORT jlong JNICALL
Java_net_gorry_mase_Mp3Encoder_nativeOpen(JNIEnv *env, jclass cls,
                                       jint sampleRate, jint channels, jint kbps) {
    (void) env;
    (void) cls;
    lame_t gf = lame_init();
    if (gf == NULL) {
        return 0;
    }
    lame_set_errorf(gf, quiet);
    lame_set_debugf(gf, quiet);
    lame_set_msgf(gf, quiet);
    lame_set_in_samplerate(gf, sampleRate);
    lame_set_out_samplerate(gf, sampleRate); /* サンプリングレートは変換しない */
    lame_set_num_channels(gf, channels);
    lame_set_mode(gf, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_VBR(gf, vbr_off);
    lame_set_brate(gf, kbps);
    lame_set_quality(gf, 2);
    lame_set_bWriteVbrTag(gf, 1);
    if (lame_init_params(gf) < 0) {
        lame_close(gf);
        return 0;
    }
    return (jlong) (intptr_t) gf;
}

/* 実際に書き出されるサンプリングレート。指定と違えば、呼んだ側で失敗にする */
JNIEXPORT jint JNICALL
Java_net_gorry_mase_Mp3Encoder_nativeOutSampleRate(JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    return lame_get_out_samplerate((lame_t) (intptr_t) handle);
}

/* left・right の [offset, offset+count) をエンコードして out に入れる。返す値は out に入れたバイト数。負なら失敗 */
JNIEXPORT jint JNICALL
Java_net_gorry_mase_Mp3Encoder_nativeEncode(JNIEnv *env, jclass cls, jlong handle,
                                         jshortArray left, jshortArray right,
                                         jint offset, jint count, jbyteArray out) {
    (void) cls;
    lame_t gf = (lame_t) (intptr_t) handle;
    jsize outSize = (*env)->GetArrayLength(env, out);
    jshort *l = (*env)->GetPrimitiveArrayCritical(env, left, NULL);
    jshort *r = (*env)->GetPrimitiveArrayCritical(env, right, NULL);
    jbyte *o = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    jint n = -1;
    if (l != NULL && r != NULL && o != NULL) {
        n = lame_encode_buffer(gf, l + offset, r + offset, count, (unsigned char *) o, outSize);
    }
    if (o != NULL) {
        (*env)->ReleasePrimitiveArrayCritical(env, out, o, 0);
    }
    if (r != NULL) {
        (*env)->ReleasePrimitiveArrayCritical(env, right, r, JNI_ABORT);
    }
    if (l != NULL) {
        (*env)->ReleasePrimitiveArrayCritical(env, left, l, JNI_ABORT);
    }
    return n;
}

/* 残りを書き出す。out は 7200 バイト以上 */
JNIEXPORT jint JNICALL
Java_net_gorry_mase_Mp3Encoder_nativeFlush(JNIEnv *env, jclass cls, jlong handle, jbyteArray out) {
    (void) cls;
    lame_t gf = (lame_t) (intptr_t) handle;
    jsize outSize = (*env)->GetArrayLength(env, out);
    jbyte *o = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (o == NULL) {
        return -1;
    }
    jint n = lame_encode_flush(gf, (unsigned char *) o, outSize);
    (*env)->ReleasePrimitiveArrayCritical(env, out, o, 0);
    return n;
}

/* ファイルの先頭に置く情報のフレーム（長さなどが入る）。flush の後に呼ぶ。入りきらなければ 0 */
JNIEXPORT jint JNICALL
Java_net_gorry_mase_Mp3Encoder_nativeTagFrame(JNIEnv *env, jclass cls, jlong handle, jbyteArray out) {
    (void) cls;
    lame_t gf = (lame_t) (intptr_t) handle;
    jsize outSize = (*env)->GetArrayLength(env, out);
    jbyte *o = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (o == NULL) {
        return 0;
    }
    size_t n = lame_get_lametag_frame(gf, (unsigned char *) o, (size_t) outSize);
    (*env)->ReleasePrimitiveArrayCritical(env, out, o, 0);
    return n > (size_t) outSize ? 0 : (jint) n;
}

JNIEXPORT void JNICALL
Java_net_gorry_mase_Mp3Encoder_nativeClose(JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    if (handle != 0) {
        lame_close((lame_t) (intptr_t) handle);
    }
}
