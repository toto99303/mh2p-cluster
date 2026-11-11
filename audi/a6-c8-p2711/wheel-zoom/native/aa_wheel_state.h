/* CC BY-NC-SA 4.0, matching this repository. No OEM implementation included. */
#ifndef MH2P_AA_WHEEL_STATE_H
#define MH2P_AA_WHEEL_STATE_H
#include <stddef.h>
#include <stdint.h>

#define MH2P_NO_CHANNEL 255u
#define MH2P_ROTARY_KEY 65536u

/* Caller serializes state changes. Core has no I/O, threads or native pointers. */
struct mh2p_wheel_state {
    uint32_t epoch;
    unsigned input_channel, video_channel;
    unsigned rotary_bound, disabled, have_frame;
    uint64_t last_frame_ms;
};

void mh2p_wheel_init(struct mh2p_wheel_state *state, uint32_t epoch);
void mh2p_wheel_mapping(struct mh2p_wheel_state *state, unsigned service, unsigned channel);
void mh2p_wheel_close(struct mh2p_wheel_state *state, unsigned channel);
void mh2p_wheel_shutdown(struct mh2p_wheel_state *state);
void mh2p_wheel_frame(struct mh2p_wheel_state *state, unsigned channel, uint64_t now_ms);
int mh2p_wheel_ready(const struct mh2p_wheel_state *state, uint64_t now_ms);

/* Process ONLY the negotiated cluster-input channel's key binding. The request
 * is protobuf body only. Return encoded response length, zero for other channels.
 * Unsupported/malformed requests are rejected and clear previous rotary binding.
 */
size_t mh2p_wheel_bind(struct mh2p_wheel_state *state, unsigned channel,
                      const uint8_t *body, size_t length, uint8_t response[16]);

/* AA InputReport0x8001, matching the P2711 InputSource report schema. Field2
 * disp_channel_id is optional and is not set by stock reportRelative either.
 * Secondary destination is established by the negotiated input channel.
 */
size_t mh2p_wheel_report(uint64_t timestamp_ns, int step, uint8_t output[32]);
#endif
