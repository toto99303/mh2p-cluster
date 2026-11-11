/* Synthetic protocol tests only. No OEM process, library or native API runs. */
#include "aa_wheel_state.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

int main(void) {
    struct mh2p_wheel_state s;
    uint8_t response[16], report[32];
    const uint8_t rotary[] = {8, 128, 128, 4};
    const uint8_t packed[] = {10, 4, 23, 128, 128, 4};
    const uint8_t unsupported[] = {8, 99};
    const uint8_t malformed[] = {10, 4, 128};
    const uint8_t unknown[] = {16, 1, 8, 128, 128, 4};
    uint32_t epoch;
    size_t n;
    mh2p_wheel_init(&s, 1);
    assert(!mh2p_wheel_ready(&s, 0));
    mh2p_wheel_mapping(&s, 1, 4); /* Primary input must remain outside this core. */
    assert(s.input_channel == MH2P_NO_CHANNEL);
    mh2p_wheel_mapping(&s, 21, 37);
    mh2p_wheel_mapping(&s, 14, 48);
    epoch = s.epoch;
    assert(!mh2p_wheel_bind(&s, 4, rotary, sizeof rotary, response));
    assert(s.epoch == epoch && !s.rotary_bound);
    n = mh2p_wheel_bind(&s, 37, packed, sizeof packed, response);
    assert(n == 4 && !memcmp(response, "\x80\x03\x08\x00", 4));
    assert(s.rotary_bound && s.epoch != epoch && !mh2p_wheel_ready(&s, 100));
    mh2p_wheel_frame(&s, 14, 100); /* Service ID is not the negotiated channel. */
    assert(!mh2p_wheel_ready(&s, 100));
    mh2p_wheel_frame(&s, 48, 100);
    assert(mh2p_wheel_ready(&s, 100) && mh2p_wheel_ready(&s, 2100));
    assert(!mh2p_wheel_ready(&s, 99) && !mh2p_wheel_ready(&s, 2101));
    assert(mh2p_wheel_bind(&s, 37, unsupported, sizeof unsupported, response) == 13);
    assert(!s.rotary_bound && response[3] == 0xee && response[12] == 1);
    assert(mh2p_wheel_bind(&s, 37, malformed, sizeof malformed, response) == 13);
    assert(!s.rotary_bound);
    assert(mh2p_wheel_bind(&s, 37, unknown, sizeof unknown, response) == 4);
    assert(s.rotary_bound);
    mh2p_wheel_close(&s, 37);
    assert(s.input_channel == MH2P_NO_CHANNEL && !s.rotary_bound);
    mh2p_wheel_mapping(&s, 21, 11);
    assert(!mh2p_wheel_bind(&s, 37, rotary, sizeof rotary, response));
    assert(mh2p_wheel_bind(&s, 11, rotary, sizeof rotary, response) == 4);
    assert(mh2p_wheel_ready(&s, 100));
    mh2p_wheel_close(&s, 48);
    assert(!mh2p_wheel_ready(&s, 100));
    mh2p_wheel_mapping(&s, 14, 19);
    assert(!mh2p_wheel_ready(&s, 100));
    mh2p_wheel_frame(&s, 19, 101);
    assert(mh2p_wheel_ready(&s, 101));
    mh2p_wheel_shutdown(&s);
    assert(!mh2p_wheel_ready(&s, 101));
    mh2p_wheel_init(&s, UINT32_MAX);
    mh2p_wheel_mapping(&s, 21, 37);
    assert(s.disabled && !mh2p_wheel_ready(&s, 101));

    assert(!mh2p_wheel_report(0, 0, report));
    assert(!mh2p_wheel_report(0, 2, report));
    n = mh2p_wheel_report(123456789, 1, report);
    assert(n == 17 && report[0] == 128 && report[1] == 1);
    n = mh2p_wheel_report(UINT64_MAX, -1, report);
    assert(n == 32 && report[n-1] == 1);
    /* For independent Python decoding, print the negative one-step report. */
    n = mh2p_wheel_report(123456789, -1, report);
    printf("REPORT ");
    for (size_t i = 0; i < n; ++i) printf("%02x", report[i]);
    puts("");

    /* Bounded malformed-input sweep, useful with ASan/UBSan. */
    uint32_t rng = 0x1542711;
    uint8_t bytes[96];
    for (unsigned test = 0; test < 20000; ++test) {
        for (size_t i = 0; i < sizeof bytes; ++i) {
            rng = rng * 1664525u + 1013904223u;
            bytes[i] = (uint8_t)(rng >> 24);
        }
        mh2p_wheel_init(&s, 1);
        mh2p_wheel_mapping(&s, 21, 37);
        n = mh2p_wheel_bind(&s, 37, bytes, test % sizeof bytes, response);
        assert(n == 4 || n == 13);
    }
    puts("PASS native protocol state: negotiated channels, binding, expiry, reconnect and malformed input");
    return 0;
}
