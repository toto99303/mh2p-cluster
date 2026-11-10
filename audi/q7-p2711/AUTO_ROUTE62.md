# P2711 candidate 4: automatic AA selection and Audi-map return

Development candidate for the owner's **MH2p_ER_AUG35_P2711**, 1920x722 cockpit.
The owner confirmed that the candidate3 timed test selected source62 for 12 seconds
and restored Audi's map before disconnecting the phone. The captured `gs`/`gd`
preflight also succeeded as user `java`. **The owner now reports successful
automatic switching with candidate4; the uploaded logs show two complete AA
selection/Audi-return cycles.** Startup with the phone already connected and
longer cold-boot testing remain unverified for this candidate.

[Download candidate4 ZIP](../../builds/ClusterIntegration_q7_p2711_auto_route62_candidate4.zip)
(Download raw file) ·
[SHA-256](../../builds/ClusterIntegration_q7_p2711_auto_route62_candidate4.zip.sha256)

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
This run therefore supports automatic connect, disconnect and reconnect on the
tested layout, but does not exercise AA arriving before native-map readiness,
an MMI restart with the phone attached, or a true vehicle cold boot with AA attached.
The package remains candidate4 with the same published SHA-256; no runtime or
rollback change accompanies this validation record. Raw vehicle logs are private.

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

## Install on a copy of the current test card

1. Preserve the untouched current card, **including**
   `Mods/ClusterIntegration/Update/Backup/` and its manifest. Keep the original
   pre-mod SCP backup separately. Do not uninstall first.
2. Extract candidate4 separately. On a **copy** of the test card replace these
   three files from its ZIP, preserving their timestamps:

   ```text
   Mods/ClusterIntegration/Update/cluster
   Mods/ClusterIntegration/Update/gal_cluster.so
   Mods/ClusterIntegration/Update/aa_only/ClusterIntegration_v0034_beta2_candidate_90d0b76_aa.jar
   ```

   The first two are exactly the source62 player and centered hook from your
   successful candidate3 test. Include them if you restored an older player after
   that test. Preserve the card's Backup, configuration and existing log markers;
   do not replace the entire Update folder.
3. Use the existing ModKit install flow, parked with the engine running, following
   its removal/restart prompts. Save `Logs/ClusterIntegration.log` from the SD card.
   Confirm `install:` for the AA JAR. Player/hook can say `skip identical:` if the
   candidate3 copies are already installed; an older player must be replaced.
   The unchanged weak-comparison installer depends on file timestamps for equal-size
   player/hook patches. `force_install.txt` bypasses firmware gating, not copying.
4. Restart with the phone disconnected. Audi's cockpit map should work normally.
   The Java log should contain `kombi-guard p2711-auto-route62-candidate4`.
   Do **not** run the old timed selection script during the automatic test.

## First parked test and logs

Start on the same navigation view used for the successful timed test:

1. Let Audi's map appear, then connect the phone. AA should select itself without
   SSH display commands. Check centering.
2. Disconnect and wait up to ten seconds. Audi's map should return automatically.
   Reconnect once and repeat the disconnect check.
3. If those pass, use the normal MMI button restart with the phone connected.
   Observe Audi initialize before AA takes the cockpit. This is a warm-restart
   test; a true vehicle cold boot remains a separate later check.

Keep Java/daemon logging enabled as on the existing test card. Before another
restart, download `/tmp/cluster.log` using SCP. Download `cluster_daemon.log` from
the writable USB root if present, otherwise `/tmp/cluster_daemon.log`. Keep the SD
installation log too. Report which of the three tests you ran and the visible
result; the layer acknowledgements alone cannot establish success.

Useful Java markers are `MH2P_VIDEO_GATE READY`, `MH2P_AUTO_ROUTE AA_REQUESTED`,
`AA_CONFIRMED`, `OEM_REQUESTED`, `OEM_CONFIRMED`, and any `QUERY_FAILED`,
`SELECT_FAILED`, `SELECT_UNCONFIRMED`, `RELEASE_FAILED` or `RESTORE_UNCONFIRMED`.
Before native readiness, a warm `prepare` without `resume` is expected.

## Recovery and rollback

If Audi does not return, disconnect the phone and run this directly in SSH:

```sh
/eso/bin/apps/dmdt sb 4
```

If needed, use the normal MMI button restart with the phone disconnected. The
override is memory-only. Do not use bare `shutdown` over SSH.

For the previous candidate's behavior, restore the **three payload files** above
from the untouched previous test card and run the same ModKit install flow.
Keep that card's Backup intact. This restores that candidate, including its known
limitations; uninstall is a separate return-to-stock operation. The existing
uninstaller/failsafe/original-SCP recovery procedure is unchanged. Check restoration
logs and resulting files; an uninstaller's final `Done` alone is insufficient.

An ordinary JVM shutdown attempts release. A killed/hung JVM cannot execute that
cleanup; **there is no independent watchdog in this candidate**, so a remaining
override can require the manual command or MMI restart above. The free-slot check
prevents taking an already registered62; it is not a permanent reservation against
a different feature claiming the same ID later. Multiple integration instances in
one JVM disable this candidate's secondary video until MMI restart. Other firmware,
layouts and repeated cold boots have not been verified on hardware.

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
