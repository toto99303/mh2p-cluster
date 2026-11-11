# Candidate4 withdrawn: MMI freeze/reboot investigation

The owner of the Audi A6 C8, MH2p_ER_AUG35_P2711, reports that the MMI freezes
after running for some time and then reboots. Candidate4 is withdrawn from
recommended use. [Existing-installation recovery](README.md#recovery-and-rollback)
uses the original SD backup and provided uninstaller. Zoom work is paused.

The short functional tests established connect/disconnect switching and one
phone-connected MMI restart. They did not establish long-running stability.
There is no validated corrected installable build yet.

For evidence collection and a comparison with the mod removed, follow the
[freeze/restart troubleshooting guide](TROUBLESHOOTING.md). Its manual collector
streams process observations to a file on the PC so a reboot does not erase
the received data. It does not run display queries or modify the controller.

## Confirmed implementation defect

Candidate4 runs `gs` and `gd` through a temporary query copy on each guard tick,
including when secondary video is inactive. Each process creates two Java
`Mh2pRoutePipe` threads to read stdout/stderr.

The admission check tracks whether the previous **process** has exited. If it
has exited but a stream reader is still blocked, `run()` reports
`query pipe did not close` after its short joins. It retains no ownership record
for those readers. The next query is admitted and creates two more readers.
Thus per-reader output limits do not impose a limit on the number of unfinished
readers. A blocked reader can retain its stream and other per-query resources.
Normal queries also create repeated short-lived processes and reader threads.

The owner's earlier startup logs already contain `QUERY_FAILED ... query pipe
did not close`. Those messages show this failure mode occurred; they do not show
whether readers remained blocked permanently or explain the later vehicle reboot.

## Reproduction against the shipped classes

```sh
ASM_JAR=/path/to/asm.jar JAVAC_HOST=/path/to/javac \
  bash modkit/test/dmdt_pipe_retention.sh
```

The harness reads the exact candidate4 archive, pinned by SHA-256
`e340bd30f5a4091974345a22147b4666ad98f2cbe06dc2b01ac9759d0d281d85`, and extracts
only the project's `Mh2pDmdt`/`Pump` classes. It substitutes only the Runtime.exec
call with a host fixture: a completed Process whose two streams stay blocked
until explicitly released. All other shipped runner logic executes unchanged.
No OEM HMI class, ARM executable, real subprocess or vehicle API runs.

Observed output:

```text
REPRODUCED completed_queries=1 retained_reader_threads=2
REPRODUCED completed_queries=2 retained_reader_threads=4
REPRODUCED completed_queries=3 retained_reader_threads=6
REPRODUCED: next query admitted after child exit while prior readers remain blocked
PASS fixture cleanup: all simulated blocked readers released
```

This is a deterministic reproduction of an unbounded retention path, not a
simulation of a complete QNX/J9 failure or a confirmed diagnosis of the vehicle
reboot. The harness releases and joins every simulated reader before exit.

## Remediation requirements

Static inspection of the supplied P2711 HMI confirms that its display service has
asynchronous `showDisplayables(display, layers, sessionID)` and
`showDisplayablesResult(display, layers, result, sessionID)` APIs. The stock HMI
filters replies by session ID. These provide a candidate direction for removing
debug-tool polling; using them correctly still requires verified listener
registration, request ownership, cancellation and native-map restoration.
Their presence alone does not establish a working replacement.

- Retire continuous debug-tool process polling from the normal presentation path.
  Investigate the stock display-management service, its request/session IDs and
  callbacks for an adapter driven by presentation changes.
- Bound total outstanding work and resources, including completed child processes
  with unfinished streams. A failed operation must not allow repeated resource
  accumulation or block the main HMI thread.
- Ensure pause/disconnect and layout changes release the cockpit through an
  acknowledged lifecycle. Preserve native source33 and the original rollback.
- Establish behavior under stalled replies, partial operations and shutdown, then
  verify resource counts over long runs and repeated reconnect/tab changes.
- Obtain evidence from the failing vehicle run to distinguish controller/JVM
  resource problems from native decoder/display failures. Record whether the
  fault also occurs with no phone connected and the time until failure.

Replacing output readers alone has not been shown to solve the complete reported
failure. No patched SD package is offered on the strength of this reproduction.
The withdrawn ZIP is retained for investigation and byte comparison; its presence
in repository history is not an installation recommendation.

## Recovery reminder

`/eso/bin/apps/dmdt sb 4` releases a display override only. It does not stop the
installed query controller. A button restart runs that controller again. Use the
saved card with intact Backup/manifest and `Mods/ClusterIntegration/uninstall.txt`
for the provided ClusterIntegration uninstall; inspect the restoration log and
normal MMI operation afterwards. Preserve the original SCP backup and all SD copies.
