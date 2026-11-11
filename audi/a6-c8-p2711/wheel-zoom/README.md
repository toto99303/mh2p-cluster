# A6 C8 MH2p left-wheel zoom: input groundwork

**Development only. This does not yet enable AA map zoom and is not an installable
update.** The candidate4 release, its SD package and its runtime remain unchanged.
No wheel sender is installed or invoked by this branch.

Goal: use the left steering-wheel scroll wheel to zoom Android Auto's cockpit map
while preserving Audi's native controls when AA does not own the map presentation.

## Verified MH2p input path

The owner's supplied **MH2p_ER_AUG35_P2711** HMI was inspected as bytecode only:

1. `CombiBAPListener.setMapScale(int)` receives signed **steps**, ignoring zero.
2. It calls `IMapClusterService.changeZoomLevel(int)`.
3. The concrete `MapClusterService` forwards directly to
   `RemoteZoomView.fireOnZoomLevelChanged(int)`.
4. That view calls `IZoomView.IZoomViewListener.onZoomLevelChanged(int)` with the
   same relative value. Stock `ZoomHandler` passes it to `incrementByStep(int)`.

These are relative events, not successive absolute magnification readings. The
first detent is actionable; it must not be discarded as a baseline sample.
An additive observer can receive the event while the native handler remains intact.
The native renderer's scale limits need not suppress further relative callbacks.

`Mh2pWheelBinding` unwraps the same stock service wrapper used by candidate4,
obtains its concrete remote zoom view and adds a weak observer. Registration must
run on the HMI dispatcher, alongside OEM listener changes. Callbacks must do memory
work only. Repeated binding to the same live view is idempotent; replacement and
close retire the old observer. No stock listener is replaced or removed, and no
navigation state is changed. There is no public remove-listener method on this
stock view, so retired weak observers are inert until the OEM view disposes them.

## Why the MHI2Q implementation cannot be copied directly

The reference feature in [MHI2Q-CarPlay-AltScreen](https://github.com/yuedizhibo/MHI2Q-CarPlay-AltScreen)
observes the stock wheel path and paces outstanding signed movement. Its native
sender invokes CarPlay's `changeMapZoomLevel` command with a secondary-display
UUID. That is an Apple CarPlay control path, whereas this release is Android Auto.
Its magnification callback and MHI2Q stock classes also differ from MH2p's API.

The code here is an independent implementation of the MH2p event observer and a
small bounded queue. It does not import MHI2Q classes or reuse its native sender.
The reference checkout examined is commit
`652448ddc1d5c9dd2f4a673b9950cf3c6a9195a4`.

## Verified AA advertisement and proposed message format

The existing candidate4 hook advertises an InputSourceService with service ID21,
display ID1, supported keys23 and65536 (rotary). Its exact14-byte template is
unchanged from the repository's `src/dhu_sdresp_data.h`.

That advertisement does not itself send wheel events. The inspected hook source
does not implement a wheel sender or the full secondary-input binding workflow.
The stock Java AA interface exposes `postRotaryEvent(int)` without a display or
channel selector. Calling it alone has not been verified to target the cockpit;
it must not be used as an unqualified fallback for main-screen input.

`Mh2pAaWheelWire` builds a standard AA InputReport payload:

- Message type0x8001, required timestamp field1.
- RelativeEvent field6, repeated Rel field1.
- Rotary keycode65536 in field1; signed int32 delta in field2.
- Negative int32 uses sign-extended protobuf varint encoding, not zigzag.

Wire fields are checked against the [AA protocol schemas](https://github.com/opencardev/aasdk/tree/9bf6adf933665dee26532201719fac14a047ccf1/protobuf/aap_protobuf/service/inputsource)
at commit `9bf6adf933665dee26532201719fac14a047ccf1`. The codec contains no transport,
does not choose a channel, and has not yet established that the phone interprets
this secondary rotary report as map zoom. That requires a parked vehicle test.

Service ID21 is **not proof that the negotiated channel is21**. The sender must
learn the mapping for the current connection, complete the applicable input-key
binding exchange, and invalidate it on disconnect/reconnect. Main-screen input
must retain its normal channel. Sending arbitrary zoom keys or synthetic pinches
through the main DSI path is not part of this implementation.

## Queue policy and remaining integration

`Mh2pWheelQueue` accepts relative callbacks only for a positive connection identity
and confirmed AA map ownership. Outstanding target movement is bounded to eight
steps; completed motion is discarded from that count, avoiding a session-wide
artificial zoom ceiling. Reverse motion cancels outstanding opposite movement.
Callbacks outside ±16 are rejected; pending input expires after1s. Polls emit one
step at a time with a150ms minimum interval. These are initial host-test settings,
not hardware-tuned rates or a substitute for native frame/response feedback.

Each dequeued event carries connection, presentation-generation and sequence
identities. Leaving and re-entering the tab or changing connections invalidates
old events, including work already dequeued. The sender must recheck ownership
and generation before transport submission and reject duplicate sequence IDs.
Clock rollback cancels pending work. The ownership input must represent observed
AA presentation, not just candidate4's internal `owned` flag during submission.

The native sender and that presentation/dispatcher wiring are still absent.
Before adding them, inspect the owner's stock `gal` receiver and real
`libautoreceiver.so` binary to verify the P2711 router/input API, object layout,
channel lifecycle and binding response behavior. Keep those OEM inputs private,
outside the repository, as with the stock HMI JAR.

Candidate4 remains the installed baseline. No Java startup hook, native binary,
installer, wrapper, rollback script, map readiness check or framing is changed.
No new SD ZIP or wheel-zoom release is published by this groundwork.

## Host checks

```sh
ECJ_JAR=/path/to/ecj-3.20.jar bash modkit/test/wheel_zoom.sh
```

Tests use independently written fake stock APIs, never the owner's actual HMI.
They cover first-detent capture, signed steps, pacing, reversal, bounds, expiry,
tab/connection cancellation, long-running completed movement, clock rollback,
duplicate listener registration, service replacement and callback failures.
A separate Python decoder checks Java-generated positive and negative AA packets;
invalid inputs are rejected. Every produced class is checked for version48 and
absence of StringBuilder. No native or phone command is executed by these tests.
