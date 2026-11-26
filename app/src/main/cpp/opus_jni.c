// Thin JNI bridge over libopus. All buffer sizes are validated here; the Kotlin
// side (OpusCodec.kt) owns the native handles and must call release() exactly once.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <opus.h>

#define MAX_PACKET 1500

JNIEXPORT jlong JNICALL
Java_ai_paai_link_audio_OpusNative_encoderCreate(JNIEnv *env, jclass clazz,
                                                 jint sampleRate, jint channels, jint bitrate) {
    int err = 0;
    OpusEncoder *enc = opus_encoder_create(sampleRate, channels, OPUS_APPLICATION_VOIP, &err);
    if (err != OPUS_OK || enc == NULL) return 0;
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(10));
    opus_encoder_ctl(enc, OPUS_SET_DTX(0));
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(5));
    return (jlong) (intptr_t) enc;
}

JNIEXPORT jint JNICALL
Java_ai_paai_link_audio_OpusNative_encoderSetBitrate(JNIEnv *env, jclass clazz, jlong handle, jint bitrate) {
    if (handle == 0) return OPUS_BAD_ARG;
    return opus_encoder_ctl((OpusEncoder *) (intptr_t) handle, OPUS_SET_BITRATE(bitrate));
}

JNIEXPORT jint JNICALL
Java_ai_paai_link_audio_OpusNative_encode(JNIEnv *env, jclass clazz, jlong handle,
                                          jshortArray pcm, jint frameSize, jbyteArray out) {
    if (handle == 0 || pcm == NULL || out == NULL) return OPUS_BAD_ARG;
    if ((*env)->GetArrayLength(env, pcm) < frameSize) return OPUS_BAD_ARG;
    jint outLen = (*env)->GetArrayLength(env, out);
    if (outLen > MAX_PACKET) outLen = MAX_PACKET;
    jshort *in = (*env)->GetShortArrayElements(env, pcm, NULL);
    unsigned char buf[MAX_PACKET];
    int n = opus_encode((OpusEncoder *) (intptr_t) handle, in, frameSize, buf, outLen);
    (*env)->ReleaseShortArrayElements(env, pcm, in, JNI_ABORT);
    if (n > 0) (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte *) buf);
    return n;
}

JNIEXPORT void JNICALL
Java_ai_paai_link_audio_OpusNative_encoderDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle != 0) opus_encoder_destroy((OpusEncoder *) (intptr_t) handle);
}

JNIEXPORT jlong JNICALL
Java_ai_paai_link_audio_OpusNative_decoderCreate(JNIEnv *env, jclass clazz, jint sampleRate, jint channels) {
    int err = 0;
    OpusDecoder *dec = opus_decoder_create(sampleRate, channels, &err);
    if (err != OPUS_OK || dec == NULL) return 0;
    return (jlong) (intptr_t) dec;
}

// packet == null means "packet lost": libopus runs packet-loss concealment.
// fec != 0 decodes the in-band FEC copy of the previous frame from this packet.
JNIEXPORT jint JNICALL
Java_ai_paai_link_audio_OpusNative_decode(JNIEnv *env, jclass clazz, jlong handle,
                                          jbyteArray packet, jint packetLen,
                                          jshortArray pcm, jint frameSize, jint fec) {
    if (handle == 0 || pcm == NULL) return OPUS_BAD_ARG;
    if ((*env)->GetArrayLength(env, pcm) < frameSize) return OPUS_BAD_ARG;
    jbyte *data = NULL;
    if (packet != NULL) {
        if (packetLen < 0 || packetLen > (*env)->GetArrayLength(env, packet)) return OPUS_BAD_ARG;
        data = (*env)->GetByteArrayElements(env, packet, NULL);
    }
    jshort *out = (*env)->GetShortArrayElements(env, pcm, NULL);
    int n = opus_decode((OpusDecoder *) (intptr_t) handle, (const unsigned char *) data,
                        packet != NULL ? packetLen : 0, out, frameSize, fec);
    (*env)->ReleaseShortArrayElements(env, pcm, out, 0);
    if (data != NULL) (*env)->ReleaseByteArrayElements(env, packet, data, JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL
Java_ai_paai_link_audio_OpusNative_decoderDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    if (handle != 0) opus_decoder_destroy((OpusDecoder *) (intptr_t) handle);
}

JNIEXPORT jstring JNICALL
Java_ai_paai_link_audio_OpusNative_version(JNIEnv *env, jclass clazz) {
    return (*env)->NewStringUTF(env, opus_get_version_string());
}
