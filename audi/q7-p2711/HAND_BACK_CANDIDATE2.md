# P2711 native-map hand-back — experimental candidate 2

For the supplied **MH2p_ER_AUG35_P2711** HMI. Includes candidate 1's startup
gate unchanged. Host-tested; **not yet vehicle-validated**. A targeted hypothesis
for the black cockpit after unplug, not a confirmed vehicle fix.

[Download SD package](../../builds/ClusterIntegration_q7_p2711_handover_candidate2.zip)
(open the file and choose Download raw file) ·
[SHA-256](../../builds/ClusterIntegration_q7_p2711_handover_candidate2.zip.sha256)

## Evidence and change

Vehicle logs from candidate 1 show readiness, successful AA playback, and a
processed stop after unplug. The daemon subsequently reports no child and no
replacement splash; both subsequent resume commands report no child as well.
The Java guard requests native-map restoration twice, yet the cockpit remains
black. Switching the cockpit to Audio and back to Navigation does not recover it.
The logs do not establish the precise remaining display/compositor state.

Static inspection of the supplied P2711 HMI identifies a concrete mismatch:
the old guard calls the raw DSI suspend/wake API, bypassing MVRequestControl.
Stock MVRequestControl.suspendMapViewer() sends DSI and sets SUSPENDING;
wakeupMapViewer() sends DSI and sets WAKING_UP. MVResponseControl accepts the
respective successful acknowledgements only while those states are present.
Calling raw DSI directly omits that bookkeeping. This provides a testable reason
for using the stock wrapper; it does not prove it is the only cause of black video.

Candidate 2:

- Resolves the cockpit map through the verified stock service wrapper and gets
  its current MVRequestControlActive. No renderer instance is hardcoded to 3.
- Calls suspend/wake through that stock request wrapper, preserving its state
  transitions and acknowledgement path.
- Restores the native context and size before sending wake/visible/unfreeze,
  so those visibility requests are last in the guard's restore sequence.
- Issues wake on both bounded restore attempts; it no longer assumes the first
  request succeeded and omits wake on the second attempt.
- Logs actual stock response state, visibility, freeze, run level and instance
  about two seconds after each restore request. These are reported state, not
  proof of visible pixels on the physical cockpit.
- Cancels pending restore/observation when AA rendering returns, and does not
  restore over a newly prepared AA session.
- Fails with a diagnostic if the stock map/request is unavailable. It does not
  fall back to raw DSI or guess display-context numbers.

The core request-resolution and hide/restore operations remain on the existing
guard thread. No stock field is written reflectively, no new native hook is
introduced, and no processes are killed beyond the existing installer behavior.

## Install using the existing candidate test card

1. Preserve your v1.5 and candidate 1 card copies, including their Backup folders.
2. Extract this package on the computer. Copy only
   `Mods/ClusterIntegration/Update/aa_only/ClusterIntegration_v0034_beta2_candidate_90d0b76_aa.jar`
   onto the existing test card, replacing the same file. Preserve all other card
   contents, especially `Update/Backup/` and your settings.
3. Keep the empty `Mods/ClusterIntegration/daemon_log.txt` marker for this test.
4. Install through the normal ModKit flow while parked with the engine running.
   Follow its remove-media/reboot prompts. No preliminary uninstall is needed.
5. After AA appears, unplug the phone, wait 15 seconds, and note whether Audi's
   map returns. Reconnect and confirm AA still works. Then test another disconnect
   and an MMI restart with the phone connected to check the startup-gate behavior.
6. Before switching off, collect `/tmp/cluster.log` and `cluster_daemon.log` from
   the first writable USB/SD root or `/tmp`. Include only those logs in the upload.

Expected Java build marker: `kombi-guard p2711-stock-handover-candidate2`.
New diagnostics start with `MH2P_HAND_BACK`: `hide-requested`,
`restore-requested`, and `restore-observed`. A successful state acknowledgement
would typically show `renderer=ACTIVE visible=true frozen=false` after restore;
please report the actual screen result even if the log reports those values.

## Rollback and validation limits

All SD-package entries except the existing AA-only JAR are byte-identical to the
original v1.5 ZIP: installer, uninstaller, SSH rollback, failsafe, gal wrapper,
native decoder/hook, configuration and signed loader. Candidate 1's binary
startup gate and instrumented integration class are also unchanged.

To return to candidate 1 or v1.5, put that preserved package's original AA JAR
back on the test card and run the existing install flow. For removal or emergency
recovery, use the unchanged [MH2p uninstall/failsafe procedure](COLD_START_GATE.md#rollback--existing-mh2p-procedure)
with the preserved Backup folder. An uninstaller Done line does not prove all
restores succeeded; inspect errors. Recovery cannot be guaranteed for every
head-unit failure.

Host validation: 15 startup-gate assertions and 20 hand-back assertions passed
using synthetic fixtures, with JVM bytecode verification. Stock Audi code was
inspected/used for compilation, never executed. Tests cover request-wrapper
selection, pending vs acknowledged state, retry wake, replaced renderers,
unready/missing services, and diagnostic failures. All 104 original integration
method attributes/bodies are preserved, class-file version 48 checks pass,
and ZIP comparisons require exactly one changed v1.5 package entry.
The vehicle logs themselves and the supplied stock HMI are not redistributed.

Rebuild with the same pinned inputs and dedicated builder as candidate 1, but
use this branch's source overlay and a candidate 2 output name:

```
ECJ_JAR=/path/to/ecj.jar bash modkit/test/readiness_gate.sh
bash modkit/build_readiness_package.sh /path/to/stock-lsd.jar \
  builds/ClusterIntegration_v0034_q7_p2711_audi.zip \
  /path/to/candidate2.zip /path/to/ecj.jar /path/to/asm.jar
```
