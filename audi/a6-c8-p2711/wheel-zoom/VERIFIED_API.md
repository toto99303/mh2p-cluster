# P2711 wheel input evidence

Profile: Audi A6 C8 **MH2p_ER_AUG35_P2711**, ARM32 little-endian, QNX6.6,
1920x722 Virtual Cockpit. The owner's OEM inputs were inspected only:

| File | SHA256 |
| --- | --- |
| lsd.jar | `96a49c355e5704db7713e4d5485495b631a5b54859d136f5d8ae9067ece718d8` |
| gal | `c323411d51359179fc73bf13c069d1541d0e02addf4b63aadf25d77dd048f4a7` |
| libautoreceiver.so | `2916bb7c65ee20c04995a42f22504b61e69eae167aa362fc3f05a7a57740a48d` |

## Java path

`CombiBAPListener.setMapScale(int)` ignores zero and passes signed relative
movement to `MapClusterServiceProxy.changeZoomLevel`. The proxy schedules on
`ENaviDispatcher.CLUSTER`. The service wrapper delegates to MapClusterService,
which fires `RemoteZoomView.fireOnZoomLevelChanged(int)` with the same value.
This is not successive absolute magnification data.

The view's unsynchronized listener set requires registration on CLUSTER, using
OSGi `NaviDispatcherFactory.createDispatcher(ENaviDispatcher,String,LogChannel)`.
It returns a disposable client wrapper over the existing dispatcher. No public
remove-listener API exists: weak delegates retire in memory and a weak-key cache
reuses each view's proxy. No OEM set is edited directly.

The context framework exposes `getBundleCxt`, `getLogChannel(String)` and
`getHMIService().getDisplayManager()`. Display-manager caches are
`confirmedActiveContext`, `requestedContexts` and `lastRequestAnswered`. Index1
is the cockpit terminal. `getCurrentContextID` falls back to requested state and
alone cannot authorize input. The add-on requires an answered request, matching
confirmed/requested lists containing33, the unchanged video gate and render intent.

## Native path

| API | P2711 behavior |
| --- | --- |
| setupMapping(uchar,uchar),0xf0779 | Service-to-channel mapping in router's first256bytes. |
| routeMessage(uchar,shared_ptr<IoBuffer> const&) | Integer result. Shared-pointer IoBuffer address at+4; buffer data/read/end at+0/+8/+12. |
| queueOutgoing(uchar,void*,uint),0xf0945 | Stock sendInputReport calls it then immediately frees its marshalled buffer; companion follows that lifetime contract. |
| Router init/shutdown/close | Invalidate mappings and queued work. Init's boolean result is forwarded unchanged. |
| InputSource::reportRelative(uint64,uint,int),0xed461 | Report0x8001 with key65536 and signed relative delta after key binding. |

Candidate5 advertises service14(video) and21(input), which are **not fixed channel
numbers**. Its shared fake endpoint lacks the input binding handler. The companion
consumes secondary request0x8002 and returns0x8003 status0 or-18 for unsupported or
malformed keys. Only advertised keys23/65536 are accepted; rotary must be granted.
Primary input is forwarded. Secondary feedback0x8004 is consumed without inventing
phone movement acknowledgements.

Report0x8001 has timestamp field1, RelativeEvent field6, repeated Rel field1,
keycode field1=65536 and delta field2 signed int32. Negative values use10-byte
sign-extended varints, not zigzag. GAL's clock helper at0xaa250 returns
seconds*1e9+nanoseconds, forwarded unchanged to reportRelative. The companion
matches this using `ClockTime(CLOCK_MONOTONIC=2)` nanoseconds.

GAL's DSI rotary-job path is gated by primary AA input state. An unqualified
Java postRotaryEvent call does not establish secondary targeting; no such fallback
is used. Wire fields also match the public
[AA inputsource schema](https://github.com/opencardev/aasdk/tree/9bf6adf933665dee26532201719fac14a047ccf1/protobuf/aap_protobuf/service/inputsource).

## Loader and limits

QNX RTLD_NEXT is-3, confirmed in the existing hook's resolver. BSD sockaddr_in
is16bytes with byte length/family; pollfd is8bytes, pthread_t32bits. The helper
uses explicit declarations of public QNX calls, no Linux headers/libc objects.
ClockTime avoids time_t assumptions. Public APIs are documented in the
[QNX6.6 reference](https://www.qnx.com/developers/docs/6.6.0_anm11_wf10/com.qnx.doc.neutrino.lib_ref/topic/about.html).

The new library has six router interposers, no DT_NEEDED entries, TLS or compiler
runtime, and standard ARM dynamic relocations. GAL already depends on libc.so.3
and libsocket.so.3. Before enabling input, fingerprints check reportRelative's
first8bytes and setupMapping's first4bytes. A mismatch disables zoom. Such checks
cannot make an incompatible ELF load.

No QNX SDK or vehicle runtime was available for a loader test. Host fixtures use
real host headers and synthetic objects, never OEM code. Phone support, physical
direction, zoom response and long-session stability need parked hardware testing.
Confirmed HMI state and recent frames do not prove decoder pixels or phone action.
