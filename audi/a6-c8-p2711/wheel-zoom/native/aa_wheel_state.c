/* CC BY-NC-SA 4.0, matching this repository. */
#include "aa_wheel_state.h"

static void next_epoch(struct mh2p_wheel_state *s) {
    if (s->epoch == UINT32_MAX) { s->disabled = 1; return; }
    ++s->epoch;
}

void mh2p_wheel_init(struct mh2p_wheel_state *s, uint32_t epoch) {
    s->epoch = epoch ? epoch : 1;
    s->input_channel = s->video_channel = MH2P_NO_CHANNEL;
    s->rotary_bound = s->disabled = s->have_frame = 0;
    s->last_frame_ms = 0;
}

void mh2p_wheel_mapping(struct mh2p_wheel_state *s, unsigned service, unsigned channel) {
    if (!channel || channel >= MH2P_NO_CHANNEL || s->disabled) return;
    if (service == 21) {
        next_epoch(s);
        s->input_channel = channel;
        s->rotary_bound = 0;
    } else if (service == 14) {
        next_epoch(s);
        s->video_channel = channel;
        s->have_frame = 0;
    }
}

void mh2p_wheel_close(struct mh2p_wheel_state *s, unsigned channel) {
    if (channel == s->input_channel) {
        next_epoch(s);
        s->input_channel = MH2P_NO_CHANNEL;
        s->rotary_bound = 0;
    }
    if (channel == s->video_channel) {
        next_epoch(s);
        s->video_channel = MH2P_NO_CHANNEL;
        s->have_frame = 0;
    }
}

void mh2p_wheel_shutdown(struct mh2p_wheel_state *s) {
    next_epoch(s);
    s->input_channel = s->video_channel = MH2P_NO_CHANNEL;
    s->rotary_bound = s->have_frame = 0;
}

void mh2p_wheel_frame(struct mh2p_wheel_state *s, unsigned channel, uint64_t now_ms) {
    if (channel == s->video_channel && channel != MH2P_NO_CHANNEL) {
        s->last_frame_ms = now_ms;
        s->have_frame = 1;
    }
}

int mh2p_wheel_ready(const struct mh2p_wheel_state *s, uint64_t now_ms) {
    return !s->disabled && s->rotary_bound && s->input_channel != MH2P_NO_CHANNEL
        && s->video_channel != MH2P_NO_CHANNEL && s->have_frame
        && now_ms >= s->last_frame_ms && now_ms - s->last_frame_ms <= 2000;
}

static int integer(const uint8_t *b, size_t n, size_t *at, uint32_t *value) {
    unsigned shift;
    uint32_t v = 0;
    for (shift = 0; shift < 35 && *at < n; shift += 7) {
        unsigned c = b[(*at)++];
        if (shift == 28 && (c & 0xf0)) return 0;
        v |= (uint32_t)(c & 127) << shift;
        if (!(c & 128)) { *value = v; return 1; }
    }
    return 0;
}

static int key(uint32_t value, unsigned *rotary, unsigned *count) {
    if (++*count > 64 || (value != 23 && value != MH2P_ROTARY_KEY)) return 0;
    if (value == MH2P_ROTARY_KEY) *rotary = 1;
    return 1;
}

static int binding(const uint8_t *b, size_t n, unsigned *rotary) {
    size_t at = 0;
    unsigned count = 0;
    if (n > 1024 || (!b && n)) return 0;
    while (at < n) {
        uint32_t tag, v;
        if (!integer(b, n, &at, &tag) || !(tag >> 3)) return 0;
        if (tag == 8) {
            if (!integer(b, n, &at, &v) || !key(v, rotary, &count)) return 0;
        } else if (tag == 10) {
            size_t end;
            if (!integer(b, n, &at, &v) || v > n - at) return 0;
            end = at + v;
            while (at < end)
                if (!integer(b, end, &at, &v) || !key(v, rotary, &count)) return 0;
        } else {
            /* Protobuf allows unknown fields. A known key field with another
             * wire type is malformed; never infer a rotary grant from it. */
            if (tag >> 3 == 1) return 0;
            switch (tag & 7) {
                case 0: if (!integer(b, n, &at, &v)) return 0; break;
                case 1: if (n - at < 8) return 0; at += 8; break;
                case 2:
                    if (!integer(b, n, &at, &v) || v > n - at) return 0;
                    at += v; break;
                case 5: if (n - at < 4) return 0; at += 4; break;
                default: return 0;
            }
        }
    }
    return 1;
}

static size_t varint(uint64_t value, uint8_t *out) {
    size_t n = 0;
    do {
        unsigned c = (unsigned)(value & 127);
        value >>= 7;
        out[n++] = (uint8_t)(c | (value ? 128 : 0));
    } while (value);
    return n;
}

size_t mh2p_wheel_bind(struct mh2p_wheel_state *s, unsigned channel,
                      const uint8_t *body, size_t length, uint8_t response[16]) {
    unsigned rotary = 0;
    int valid;
    if (channel != s->input_channel || channel == MH2P_NO_CHANNEL || s->disabled) return 0;
    next_epoch(s);
    valid = binding(body, length, &rotary);
    s->rotary_bound = valid && rotary;
    response[0] = 0x80; response[1] = 3; response[2] = 8;
    /* P2711 InputSource rejects unsupported keys with -18. */
    return 3 + varint(valid ? 0 : (uint64_t)(int64_t)-18, response + 3);
}

size_t mh2p_wheel_report(uint64_t timestamp_ns, int step, uint8_t out[32]) {
    uint8_t delta[10];
    size_t at = 0, size;
    if (step != -1 && step != 1) return 0;
    size = varint((uint64_t)(int64_t)step, delta);
    out[at++] = 0x80; out[at++] = 1;
    out[at++] = 8; at += varint(timestamp_ns, out + at);
    out[at++] = 50; out[at++] = (uint8_t)(7 + size);
    out[at++] = 10; out[at++] = (uint8_t)(5 + size);
    out[at++] = 8; at += varint(MH2P_ROTARY_KEY, out + at);
    out[at++] = 16;
    for (size_t i = 0; i < size; ++i) out[at++] = delta[i];
    return at;
}
