#ifndef SRCS_OPUS_H
#define SRCS_OPUS_H

#include <stddef.h>
#include <stdint.h>

/* Thin, non-variadic wrapper around libopus for Swift. Mirrors app/src/main/cpp/opus_jni.c. */

/* Mono VoIP encoder with in-band FEC (10 % expected loss), complexity 5, no DTX. NULL on failure. */
void *srcs_enc_create(int32_t sample_rate, int32_t channels, int32_t bitrate);
int32_t srcs_enc_set_bitrate(void *enc, int32_t bitrate);
/* Returns the packet length in bytes, or a negative Opus error. */
int32_t srcs_enc_encode(void *enc, const int16_t *pcm, int32_t frame_size, uint8_t *out, int32_t max_bytes);
void srcs_enc_destroy(void *enc);

void *srcs_dec_create(int32_t sample_rate, int32_t channels);
/* data == NULL runs packet-loss concealment. Returns decoded samples per channel or a negative error. */
int32_t srcs_dec_decode(void *dec, const uint8_t *data, int32_t len, int16_t *pcm, int32_t frame_size, int32_t fec);
void srcs_dec_destroy(void *dec);

const char *srcs_opus_version(void);

#endif
