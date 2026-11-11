# Investigating MMI freezes and restarts

For Audi A6 C8 **MH2p_ER_AUG35_P2711**. Candidate4 is withdrawn. There is no
validated runtime fix yet. The reproduced reader-retention and blocking paths
are leads; they do not establish what caused the owner's MMI restart.

The goal is to preserve crash evidence, observe resource usage before a failure,
and compare with ClusterIntegration removed. Do not reinstall candidate4 just
to reproduce a freeze. Perform any observation while parked; stop at the first
sign of slowness rather than repeatedly provoking automatic reboots.

## 1. Preserve evidence already on the MMI

Use your existing WinSCP connection. Create a **new folder on your PC**, separate
from the original SCP backup and saved installation SD card. Download existing
files; do not change permissions or delete anything on the MMI.

- Open **`/mnt/crashlogs/java/`**. Save its directory listing with file names,
  sizes and timestamps, and download the newest relevant text reports if present.
- Open **`/mnt/crashlogs/core/`**. Save its directory listing too. Keep large
  binary core files on the unit for now; their names and timestamps help identify
  which process crashed without transferring gigabytes.
- Before another restart, download any of these files that exist:
  **`/tmp/cluster.log`**, **`/tmp/cluster_daemon.log`**,
  **`/tmp/gal_preload.log`**, **`/tmp/gal_cluster.log`**.
- Save the installation SD card's **`Logs/ClusterIntegration.log`** separately;
  it is the installer log, not the similarly named runtime log.

The crash paths above come from this car's captured `dumper` and JVM command
lines. A directory can be empty or a hang can leave no crash report. Missing
reports do not rule out a fault. Do not enable extra verbose logging yet.

In a short note, record the installed package/updates, approximate elapsed time
to the fault, whether the phone was connected, and which screens were in use.
Record the PC's time when slowness or a restart occurs: the car's clock may differ.
Keep vehicle logs private; they can contain identifying and navigation data.

## 2. Capture a short process trace to the PC

This is a manual diagnostic script, **not an SD update**. It does not install or
patch anything. Uploading it creates just one temporary script file. It uses
QNX `pidin` to observe boot time/free memory, process arguments, JVM threads,
process memory, JVM file descriptors and cumulative CPU times. It also lists
crash folders and takes a system-log snapshot at the beginning and end if
`sloginfo` is available.

It does not invoke `dmdt`, inspect Screen nodes, send signals, modify display
state or write captured logs to the MMI. Queries still have some overhead.
Only one query runs at a time. A blocked query stops progress; there is no
per-command timeout or background retry. Interrupt the SSH capture if a sample
does not finish for over a minute. Unsupported queries and missing files are
reported in the output; an exit code of zero alone does not guarantee data.

1. Download [capture_mh2p_health.sh](https://raw.githubusercontent.com/toto99303/mh2p-cluster/audi-q7-p2711/modkit/diagnostics/capture_mh2p_health.sh)
   to your PC. Use **Save link as**, keeping the `.sh` extension and LF line
   endings. Copy it with WinSCP in **binary transfer mode** to
   **`/tmp/capture_mh2p_health.sh`**. No executable permission change is needed.
   Do not create a folder under `/tmp`; this unit exposes a flat namespace there.
2. Open **Command Prompt on your Windows PC**, in a folder where you want to
   keep the report. The following command runs on the **PC**, not at the MMI's
   `root@... #` prompt. Replace `MMI_IP` with the address from your WinSCP session;
   use the same SSH port/account if yours differs.

   ```bat
   ssh -T -o ServerAliveInterval=5 -o ServerAliveCountMax=3 root@MMI_IP "ksh /tmp/capture_mh2p_health.sh 40 30" > mh2p-health-installed.txt
   ```

   Enter your SSH password if prompted. `40 30` means **40 samples with a
   30-second pause after each completed sample**, roughly 20 minutes in total.
   For roughly an hour, use `120 30`. Maximum 120 samples; minimum pause 30 seconds.
   Use a new PC filename for each run: `>` replaces an existing file.
3. The command normally produces no terminal output because the report is going
   into the PC file. Open that file in another editor while capturing to check
   that `SAMPLE ... END` lines continue appearing. It may take several seconds
   for a sample to finish. Use your normal phone/map setup; record any tab change
   or disconnect in your separate note.
4. If slowness starts, stop with **Ctrl+C** and preserve the file. If the MMI
   restarts, SSH may disconnect; the PC file already contains the received data.
   An SSH disconnect by itself does not prove a full-unit reboot. A normal finish
   ends with **`MH2P_HEALTH_CAPTURE_COMPLETE`**. A truncated file is still useful.
5. After any restart, repeat step 1 to save newly created crash reports and take
   one post-restart sample to a **different** PC file:

   ```bat
   ssh -T root@MMI_IP "ksh /tmp/capture_mh2p_health.sh 1 30" > mh2p-health-after-restart.txt
   ```

   If `/tmp/capture_mh2p_health.sh` disappeared, upload it again first. A full
   reboot can clear `/tmp`; it cannot clear the report already saved on the PC.

If candidate4 is already removed, keep it removed and collect the baseline below.
Existing crash evidence is preferable to reinstalling a withdrawn build.

### If you normally connect with PuTTY

You can use PuTTY's PC-side logging instead of the Windows `ssh` command above.
In PuTTY, open **Session → Logging**, select **All session output**, and choose
a new log filename on your **PC**. Connect with your existing working SSH settings.
At the MMI's `root@... #` prompt run:

```sh
ksh /tmp/capture_mh2p_health.sh 40 30
```

The output appears in PuTTY and is saved in the PC log. Check that the PC file
is growing. Save that file if the connection drops. Do not add `> /tmp/...` to
this command: that would save the report on the MMI instead. Run only one
collector at a time, whichever SSH client you choose.

## 3. Establish a baseline with ClusterIntegration removed

Preserve the evidence above first. Follow the existing
[saved-card recovery procedure](README.md#recovery-and-rollback): keep the intact
`Mods/ClusterIntegration/Update/Backup/` and manifest, add the empty
`Mods/ClusterIntegration/uninstall.txt` marker, run the normal ModKit SD flow and
allow its restart to complete. Inspect the latest installer log for each restore
error; **a final `Done` line alone is insufficient**. If restoration fails, save
that log before proceeding. Keep the original SCP backup unchanged.

The current uninstaller has weak error handling. Its completion message does not
prove that every file was restored. Also, an SSH-only uninstall cannot unload
classes already running in the JVM: a full MMI restart is necessary afterwards.

Check ordinary MMI operation, AA on the main display and Audi's cockpit map.
Then repeat the health capture, saving **`mh2p-health-baseline.txt`**, with the
same phone connection and similar activity for at least the usual time to failure.
You will be comparing system stability; AA cockpit video is absent in this baseline.

**These are not substitutes for removing the mod:**

- `diag.txt` disables video rendering but leaves candidate4's automatic query
  controller running. The controller also polls with the phone disconnected.
- `dmdt sb 4` releases the current display override; it does not stop the controller.
- A button reboot starts the installed controller again.

## 4. What the evidence can tell us

| Observation | Next investigation |
|---|---|
| JVM thread/descriptor counts or memory keep rising during repeated samples | Compare with the reproduced query-reader retention path and controller lifecycle. JVM process memory is not a Java heap measurement. |
| `gal`, `cluster` or `displaymanager` restarts or leaves a core report | Investigate that native process and its decoder/display interactions. |
| Resources stay level but the same threads repeatedly block | Inspect synchronization and I/O waits; OS thread states alone cannot prove a Java deadlock. |
| JVM PID changes while boot time stays the same | HMI/process restart, without evidence of an OS reboot. |
| Boot time changes after reconnecting | Evidence of an OS reboot; correlate with crash/system logs. |
| Baseline remains stable past the usual failure time | Supports a regression associated with the mod; does not identify which component caused it. |
| Baseline also fails | Verify rollback completion and investigate faults beyond the new controller. |

Process memory can include shared mappings; do not add all reported values and
call that total RAM usage. These are sequential observations, not an atomic
system snapshot. A single sample or a single thread in a blocked state is not a
diagnosis. Compare trends and match timestamps across the reports.

Send the health trace(s), newest relevant **text** crash reports, crash-directory
listings and the short timing/context note privately for analysis. Keep binary
cores locally unless a specific one is requested. Only after this evidence should
we choose an isolated diagnostic change or redesign the controller; no speculative
replacement SD package is supplied by this guide.

## Collector validation limits

`python3 modkit/test/health_capture_test.py` exercises the script under host
`bash`/`dash` with stub commands: finite sampling, argument rejection, stopping
after a failed pause, and reporting unsupported/missing tools. It does not test
QNX/J9 or establish that every `pidin` query works on the vehicle. The commands
follow the [QNX 6.6 pidin reference](https://www.qnx.com/developers/docs/6.6.0_anm11_wf10/com.qnx.doc.neutrino.utilities/topic/p/pidin.html).
The owner's previous capture already established `pidin ar` on this unit.
