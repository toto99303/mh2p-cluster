# Audi A6 C8 P2711 — experimental AA wheel zoom

Candidate6 adds left steering-wheel rotary input to the frozen **candidate5**
baseline. Candidate5 remains stable. Candidate6 has host validation; QNX loading,
physical wheel direction and the phone's interpretation as map zoom remain
**unverified in the vehicle**. See [installation and recovery](CANDIDATE6.md).

The readiness gate, guard, source33 decoder, native video hook and centered screen
template are preserved. Candidate4's routing controller is absent. This add-on
does not restore Audi maps after disconnection or add CarPlay zoom. The baseline's
existing limitations remain.

## Implementation

An additive weak observer receives signed relative steps from MH2p's
`RemoteZoomView`. It registers on the native **CLUSTER** dispatcher. OEM listeners
remain installed. One proxy is reused per view across reattachment. Wheel
callbacks only update bounded memory and wake one fixed worker; no I/O occurs.

One Java worker checks readiness, render intent and matching requested/confirmed
source33 display-manager caches. Passive notifications around the unchanged
baseline sender cancel on pause/stop/prepare before its I/O. Guarded startup and
notifications tolerate an absent or throwing add-on. The worker alone reads the
display-manager monitor: a blocked probe does not make an HMI callback wait for
it, and the native ownership lease expires.

A small new companion loads ahead of the **unchanged** `gal_cluster.so` only in
`gal.real`. The decoder and GAL's child processes retain the baseline preload.
It learns secondary input/video channel mappings, handles secondary key binding
and sends relative reports on that input channel. Primary input is forwarded;
there is no primary AA rotary fallback. One native UDP worker queues intents;
only incoming native router callbacks submit them to the router.

Connection epochs, presentation generations and sequence IDs reject old input.
Queues have eight-step bounds, a 1s age limit, a 250ms ownership lease and a 150ms
minimum submission interval. Matching HMI state and recent video are presentation
evidence, not actual pixel/decoder acknowledgement. Submission is not proof that
the phone applied the zoom.

Logs use `MH2P_WHEEL` in `/tmp/cluster.log` and a bounded 256KiB
`/tmp/mh2p_wheel_native.log`. Java status messages are deduplicated. No dmdt,
Screen API changes, per-event threads or per-event processes are added.

## Reference and compatibility

The [MHI2Q feature](https://github.com/yuedizhibo/MHI2Q-CarPlay-AltScreen/tree/652448ddc1d5c9dd2f4a673b9950cf3c6a9195a4)
sends CarPlay's `changeMapZoomLevel` using a secondary-display UUID. MH2p Android
Auto needs its own input path. This implementation independently follows the
verified MH2p APIs and AA relative-input protocol.

[VERIFIED_API.md](VERIFIED_API.md) records firmware hashes, APIs, native layouts
and build limits. OEM inputs remain private and are never executed by host tests
or included in the package. No QNX SDK was available: the C helper is freestanding
ARM EABI with explicit QNX declarations, importing public libc/libsocket functions
already in GAL's dependency closure. No Linux libc/compiler runtime is linked.
On-car loading and long-session stability still require testing.

## Host checks and build

```sh
ECJ_JAR=/path/to/ecj-3.20.jar bash modkit/test/wheel_zoom.sh
ECJ_JAR=/path/to/ecj-3.20.jar ASM_JAR=/path/to/asm-9.jar \
  python3 modkit/test/wheel_bootstrap_transform_test.py
bash modkit/test/wheel_native_state.sh
ECJ_JAR=/path/to/ecj-3.20.jar python3 modkit/test/wheel_companion_test.py
python3 modkit/test/wheel_package_test.py
```

Synthetic fixtures cover listener lifetime, CLUSTER scheduling, ownership,
guarded bytecode edits under JVM verification, bounded input, malformed bindings,
pacing, primary forwarding, negotiated channels and Java/native interoperability.
The ARM library is statically checked, never executed on the build host.

```sh
python3 modkit/build_wheel_zoom_package.py \
  builds/ClusterIntegration_a6_c8_p2711_readiness_centered_candidate5.zip \
  /tmp/ClusterIntegration_a6_c8_p2711_aa_wheel_candidate6.zip \
  --gal /private/gal --autoreceiver /private/libautoreceiver.so \
  --ecj /path/to/ecj-3.20.jar --asm /path/to/asm-9.jar --zig /path/to/zig-0.13.0
```

The builder pins the stable archive and OEM profile, preserves every original
integration method body and validates Java1.4 and native ABI constraints. It
changes the AA JAR, GAL wrapper, installer and README, adding only
`Update/mh2p_wheel.so`. Rollback scripts and all other payloads stay byte-identical.
