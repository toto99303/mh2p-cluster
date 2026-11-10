# Candidate 2 results, corrected framing, and Screen snapshots

**The native-map hand-back remains unresolved.** Candidate 2's new diagnostics
report renderer Active, visible=true, frozen=false, runLevel=4 after restoration,
while the cockpit is physically black. The native daemon processes stop and
subsequently reports no child. These are separate observations: a running map
service and visible flag do not establish that its output is bound/composited to
the cockpit. The logs do not yet show the remaining window/display state.

A page switch from Navigation to Audio and back does not recover it either.
The next diagnostic compares Screen's window and display properties in a
working OEM state, during AA playback, and after AA disconnect. It does not
change display state.
No speculative display-context number or compositor command is applied.

## Capture on the existing candidate 2 installation — no mod install required

Use script V3 (`MH2P_SCREEN_SNAPSHOT_V3_NAMED_NODES`). V1 tried to create
folders under the flat `/tmp` namespace; V2 fixed output creation but incorrectly
required property nodes to be regular files. Vehicle listings show named-special
`n` nodes instead, so V2 collected directory listings and **zero properties**.
That was a collector bug, not evidence that Screen debug support is absent.
V3 reads the existing `win-*`/`dpy-*` named nodes, retaining the single-file output
and bounds. Replace any previously uploaded script with this version.

The received V2 listings establish that the original navigation window remains
present after disconnect and that the AA decoder process and its windows disappear.
They do not establish the native window's display binding, visibility or attached
stream because the property bodies were skipped. Collect a V3 black-state report
first; an OEM baseline can follow if a comparison of the actual properties is
still needed. No new mod installation is required.

Download [capture_mh2p_screen.sh](../../modkit/diagnostics/capture_mh2p_screen.sh)
(use Download raw file) and overwrite `/tmp/capture_mh2p_screen.sh` using SCP.
Run it manually through the SSH terminal, not as a ModKit/Persist script. The
script has LF line endings. A full reboot can clear `/tmp`, so upload after any
restart.

For the current investigation, while parked with the cockpit black after AA
disconnect, run just this capture:

```sh
ksh /tmp/capture_mh2p_screen.sh /tmp/mh2p-screen-black-v3.txt
```

Before rebooting, download that one text file using SCP and upload it in the
private troubleshooting conversation. No restart or new mod installation is
needed for this capture. If the filename already exists, choose another new
filename; the script refuses to overwrite an existing file or directory.

If the property bodies are successfully captured and a fresh baseline is
needed, use this sequence:

1. Restart the MMI with the phone disconnected. Wait until Audi's original
   cockpit map is visibly working, then upload the script again.
2. Capture OEM state:

   ```sh
   ksh /tmp/capture_mh2p_screen.sh /tmp/mh2p-screen-oem-v3.txt
   ```

3. Connect the phone, confirm AA appears, wait 15 seconds, and capture AA state:

   ```sh
   ksh /tmp/capture_mh2p_screen.sh /tmp/mh2p-screen-aa-v3.txt
   ```

4. Disconnect the phone, wait 15 seconds with the cockpit black, and capture:

   ```sh
   ksh /tmp/capture_mh2p_screen.sh /tmp/mh2p-screen-black-v3-2.txt
   ```

5. Download the three text files using SCP before rebooting and upload them
   in the private troubleshooting conversation.

The script reads process names, directory listings and text window/display
properties under `/dev/screen`. It writes one private-by-default report to the
new output filename supplied, with read errors included in the same file. It
sends no Screen property-setting commands and copies no runtime logs. It does not
read pixel buffers, BMP screenshots, context/control channels, Wi-Fi files or
other `/tmp` contents. Each property read is limited to 16 KiB and the script
stops after 128 property files, explicitly marking the report incomplete.

If Screen debug support or permissions are unavailable, the report records
that limitation. Missing properties are not proof that a window does not exist.
No debug module is enabled or installed by this script. The script was tested
against host fixtures using bash, including fixtures where mkdir fails and
all regular-file (`-f`) checks return false. The new test still requires actual
property bodies to be captured, and ordinary FIFOs/devices are excluded. It has
not yet been run on QNX hardware.

QNX documents the older nested `win-N/win-N` layout in the
[6.6 documentation](https://www.qnx.com/developers/docs/6.6.0_anm11_wf10/),
and describes window/display property inspection in its
[Screen runtime debugging guide](https://qnx.com/developers/docs/7.1/com.qnx.doc.screen/topic/manual/cscreen_screen-debugging-app.html).
The script handles both nested and flat text-property layouts when available.

## Centered framing only — optional separate update

[Download candidate 2 centered](../../builds/ClusterIntegration_q7_p2711_candidate2_centered.zip)
(use Download raw file) ·
[SHA-256](../../builds/ClusterIntegration_q7_p2711_candidate2_centered.zip.sha256)

This is a framing-only variant of candidate 2, **not a fix for the remaining
black screen**. It uses the requested values:

```text
height_margin=358
margin_top=179
margin_bottom=180
inset_bottom=195
inset_left=160
```

The first four were already present. Left inset changes from 450 to 160.
Unspecified values are preserved: inset_right=160, density=215,
real_density=215, and all other original template settings. The tracked
`modkit/template/template.txt` now records this requested framing for future
package work.

Compared with the exact published candidate 2 ZIP, only
`Mods/ClusterIntegration/Update/gal_cluster.so` changes. Only its two left-inset
varint bytes at file offsets 0x73b1 and 0x73b2 differ. The 54-byte template size,
codec-resolution byte position, native instructions and every other byte are
preserved. The AA JAR and its startup/hand-back code are identical to candidate 2.

To install framing after collecting the diagnostic snapshots:

1. Preserve the working test-card copy and its Backup folder.
2. Extract the centered ZIP separately. On the existing **candidate 2** card,
   replace only `Mods/ClusterIntegration/Update/gal_cluster.so` with that file.
   Preserve the current candidate 2 AA JAR, configuration, logging markers and
   Backup folder.
3. Run the existing ModKit install flow, parked with the engine running, and
   follow its remove-media/reboot prompts.
4. Reconnect AA and check the map position. Hand-back behavior is expected to
   remain as in candidate 2 until the display-state issue is understood.

Unlike the earlier Java-only candidates, this update changes the installed
hook's template data: a JAR-only copy does not apply these margins. To revert
framing, restore candidate 2's previous gal_cluster.so on the card and reinstall.
The original installer, uninstaller, failsafe and SSH rollback are unchanged.
They still rely on the preserved Backup folder for removal/emergency recovery.

Rebuild the centered ZIP without the private stock HMI:

```sh
python3 modkit/build_centered_package.py \
  builds/ClusterIntegration_q7_p2711_handover_candidate2.zip \
  /path/to/candidate2_centered.zip
```

The builder pins the published candidate 2 archive hash and rejects other inputs.
It verifies exactly two hook-data bytes change and exactly one ZIP entry changes,
then prints the decoded output template for inspection. Run the snapshot host
checks with `python3 modkit/test/screen_snapshot_test.py`.
