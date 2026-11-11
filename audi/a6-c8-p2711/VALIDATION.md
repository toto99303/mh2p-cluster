# A6 C8 candidate4: historical validation and implementation notes

**Withdrawn after the owner reported MMI freezes and reboots.** The short-run
results below demonstrate switching behavior only; they do not establish runtime
stability. See the [current stability investigation](STABILITY.md) and
[recovery instructions](README.md#recovery-and-rollback).

[Installation, SCP backup and rollback guide](README.md).

Test vehicle: Audi A6 C8, MH2p_ER_AUG35_P2711, 1920x722 cockpit.

## First automatic vehicle result

The owner reports that automatic switching works. The submitted Java and daemon
logs contain two successive connect/disconnect sessions in one HMI run:

| Cycle | AA composition confirmed | Audi composition confirmed |
|---|---|---|
| 1 | `[41,62]` at log elapsed `01:18.151` | `[41,33]` at `02:04.998` |
| 2 | `[41,62]` at `02:35.741` | `[41,33]` at `02:52.749` |

Audi composition was confirmed approximately 2.6 seconds and 1.3 seconds after
the respective AA termination notifications. Both decoder sessions report actual
window ID62 and follow prepare/resume/stop. No `SELECT_FAILED`,
`SELECT_UNCONFIRMED`, `RELEASE_FAILED` or `RESTORE_UNCONFIRMED` appears in this run.

One `QUERY_FAILED ... query pipe did not close` occurs while video is inactive,
before the first connection; normal polling recovers before that session starts.
Keep this as an observed transient, rather than treating the run as error-free.
The first resume-to-AA-confirmation interval is approximately 15.5 seconds; the
second is 1.8 seconds. These logs do not establish the cause of that difference.

Both stock map-readiness flags became true before the first AA preparation.
This first run supports automatic connect, disconnect and reconnect on the tested
layout. The later phone-connected MMI restart below adds startup evidence. The
package remains candidate4 with the same published SHA-256; no runtime or rollback
change accompanies these validation records. Raw vehicle logs are private.

## Phone-connected restart and accepted limitations

The owner confirmed an MMI button restart with the phone already connected: AA
connected automatically and eventually replaced Audi's cockpit map. The subsequent
log records AA activation at elapsed24.828s, native initialization/first draw at27.295s,
prepare at27.535s, resume sent at30.120s, and AA composition confirmed at67.770s.
The readiness gate therefore released after the native flags; the long additional
wait occurred after the resume request. A query-pipe closure failure was recorded
at59.514s. The logs locate a query-capture failure during that gap but do not identify
which part accounts for all the elapsed time. A later reconnect took about3.3s from
AA activation to composition confirmation.

The owner accepts this behavior for the candidate4 release. Known limitations:

- Startup takeover can take tens of seconds (about43s from AA activation to cockpit
  confirmation in the recorded phone-connected restart). There is no fixed30s timer.
- A transient `QUERY_FAILED ... query pipe did not close` can occur and recover.
- Switching from the cockpit audio tab back to the map tab can briefly show Audi's
  map before AA returns. The logs confirm release for HMI policy `[41]`, followed
  by re-selection when the supported map layout returns.
- This MMI button restart is not a full vehicle-sleep cold boot. Other firmware,
  extended cold-boot testing and all alternative layouts remain unverified.

Candidate4's runtime, installer, margins and recovery payloads are unchanged.

## What changes

The existing player uses separate source62, leaving Audi's native source33 bound.
The new background controller selects AA only after native-map initialization and
first draw, a consumed resume command, and registration of both expected sources.
It preserves the HMI's current ordered layers, substituting62 for33. The verified
[41,33] family, including optional KDK20, is supported; other layouts release to
Audi. Native-map suspend/hide/wake and paused-decoder restart heuristics are removed.

On pause or stop, the controller calls the **stock** `/eso/bin/apps/dmdt sb 4`,
which releases the override to the latest HMI-buffered layout. A subsequent query
must report that layout before fresh AA routing is allowed. `AA_CONFIRMED` and
`OEM_CONFIRMED` report observed layer lists, not proof of correct visible pixels.
Disconnect, stale intent during a query/select, unsupported geometry/layout, and
failed selection fall back to Audi. Failed restoration is logged and blocks a new
AA selection. A stopped decoder must disappear before a fresh prepare is admitted.

AA transport and BAP startup keep their original release code. Mirror-command
callbacks only queue intent; native tools run on the background guard. Invisible
H.264 preparation begins once source62 is free, before map readiness, so the decoder
can consume the small video ring while Audi initializes. Prepare/resume are sent on
different polls, after the native mailbox is consumed. There is no fixed boot delay.

Queries use a private, mode700 RAM copy of the owner's exact stock `dmdt`, pinned
by SHA-256. Only its query-exit flushing is patched, using the two preflight-tested
sites. That copy runs **only gs/gd**; all sd/sb controls use installed stock `dmdt`.
The installed utility and its permissions are unchanged. No stock binary or HMI
JAR is distributed. The helper is removed on ordinary JVM exit.

Only the AA JAR differs from candidate3. The release integration class is retained
byte for byte, including the existing secondary-video sender wrapper; the original
104 release method bodies remain unchanged. Player62, hook, signed loader,
configuration, installer, uninstaller, failsafe and rollback payloads are unchanged.
No boot or Persist controller is added. Your requested framing remains:

```text
height_margin=358
margin_top=179
margin_bottom=180
inset_bottom=195
inset_left=160
```

## Build and verification

```sh
bash modkit/build_auto_route62.sh /private/stock/lsd.jar \
  builds/ClusterIntegration_q7_p2711_route62_probe_candidate3.zip \
  /path/to/NEW-candidate4.zip /path/to/ecj-3.20.jar
ECJ_JAR=/path/to/ecj-3.20.jar bash modkit/test/readiness_gate.sh
```

The builder pins the private stock HMI and candidate3 hashes, compiles only owned
adapter classes to class version48, rejects StringBuilder, verifies unchanged
release integration and other JAR entries, and requires only the AA JAR payload to
change in the outer ZIP. Fixtures exercise stale queries/selects, pause/stop,
reconnect retirement, source collisions, ordered policy changes, unsupported layouts,
selection/release failures and timeouts, mailbox sequencing, and both readiness flags.
Host process tests execute shell fixtures only; Audi classes and ARM inputs are
never executed. These checks do not replace the parked vehicle test.
