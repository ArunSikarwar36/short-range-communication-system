#include "srcs_opus.h"
#include <opus.h>

void *srcs_enc_create(int32_t sample_rate, int32_t channels, int32_t bitrate) {
    int err = 0;
    OpusEncoder *enc = opus_encoder_create(sample_rate, channels, OPUS_APPLICATION_VOIP, &err);
    if (err != OPUS_OK || enc == NULL) return NULL;
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(10));
    opus_encoder_ctl(enc, OPUS_SET_DTX(0));
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(5));
    return enc;
}

void *srcs_enc_create_music(int32_t sample_rate, int32_t channels, int32_t bitrate) {
    int err = 0;
    OpusEncoder *enc = opus_encoder_create(sample_rate, channels, OPUS_APPLICATION_AUDIO, &err);
    if (err != OPUS_OK || enc == NULL) return NULL;
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_MUSIC));
    opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(5));
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(8));
    return enc;
}

int32_t srcs_enc_set_bitrate(void *enc, int32_t bitrate) {
    if (enc == NULL) return OPUS_BAD_ARG;
    return opus_encoder_ctl((OpusEncoder *) enc, OPUS_SET_BITRATE(bitrate));
}

int32_t srcs_enc_encode(void *enc, const int16_t *pcm, int32_t frame_size, uint8_t *out, int32_t max_bytes) {
    if (enc == NULL || pcm == NULL || out == NULL) return OPUS_BAD_ARG;
    return opus_encode((OpusEncoder *) enc, pcm, frame_size, out, max_bytes);
}

void srcs_enc_destroy(void *enc) {
    if (enc != NULL) opus_encoder_destroy((OpusEncoder *) enc);
}

void *srcs_dec_create(int32_t sample_rate, int32_t channels) {
    int err = 0;
    OpusDecoder *dec = opus_decoder_create(sample_rate, channels, &err);
    if (err != OPUS_OK || dec == NULL) return NULL;
    return dec;
}

int32_t srcs_dec_decode(void *dec, const uint8_t *data, int32_t len, int16_t *pcm, int32_t frame_size, int32_t fec) {
    if (dec == NULL || pcm == NULL) return OPUS_BAD_ARG;
    return opus_decode((OpusDecoder *) dec, data, data == NULL ? 0 : len, pcm, frame_size, fec);
}

void srcs_dec_destroy(void *dec) {
    if (dec != NULL) opus_decoder_destroy((OpusDecoder *) dec);
}

const char *srcs_opus_version(void) {
    return opus_get_version_string();
}
