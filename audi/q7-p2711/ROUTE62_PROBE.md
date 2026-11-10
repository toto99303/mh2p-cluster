# P2711 candidate 3: manual, timed AA routing probe

**This is a development test, not an automatic map hand-back fix.** After installing
this player, AA cockpit video needs the manual probe below to be selected. Use it
parked, on the navigation view, for this test; restore the previous player afterwards.
The primary MMI AA connection and the existing readiness gate are unchanged.

[Download the probe ZIP](../../builds/ClusterIntegration_q7_p2711_route62_probe_candidate3.zip)
(Download raw file) ·
[SHA-256](../../builds/ClusterIntegration_q7_p2711_route62_probe_candidate3.zip.sha256) ·
[Download the timed probe script](../../modkit/diagnostics/test_mh2p_route62.sh)
(Download raw file)

## Why this test

The working OEM Screen capture has navigation window ID33 attached to displaymanager.
In the AA capture, the player creates another ID33 window; the original map becomes
unparented. After disconnect, the player is gone and the original map remains
unparented. The renderer can acknowledge Active/visible without this binding returning.

Static inspection of the owner's stock P2711 displaymanager confirms that a replacement
source unregisters the previous source, stops its update notifications, leaves its
window group and releases the manager's reference. No original-source restoration was
identified in the traced close path. The stock executable is not modified or packaged.

Live stock `dmdt` queries show a 1920x722 cockpit composition [41,33], a 1920x600
native map source, and no currently registered ID62. An unused ID at that moment is
not a permanent reservation. Check again immediately before connecting the phone.

The probe player uses ID62, preserving native source33. The script temporarily selects
[41,62] on native cockpit display4, then releases the override after 12 seconds.
P2711's stock Java maps HMI CLUSTER terminal1 to native display4; these are not QNX's
`dpy-*` numbers or the row positions in `dmdt gs` output. No MHI2Q routing IDs are used.

Inspection of this unit's `dmdt sd` handler shows that it sets a per-display override.
**`sb 4` is the cleanup:** it clears that override and applies the latest HMI-buffered
composition. Sending another `sd` command with [41,33] alone would leave the override
active and would not hand control back to the HMI.

## Install the probe on a copy of the current candidate 2 card

1. Keep an untouched copy of the current test card, including
   `Mods/ClusterIntegration/Update/Backup/` and its manifest. Keep the separate
   original SCP backup. Do not uninstall the current mod first.
2. Extract the probe ZIP separately. On a **copy** of the candidate 2 test card,
   replace only these files from the probe:

   ```text
   Mods/ClusterIntegration/Update/cluster
   Mods/ClusterIntegration/Update/gal_cluster.so
   ```

   Preserve the AA JAR, configuration, log markers and Backup folder. The hook is
   the already centered candidate 2 hook, included so the requested margins apply
   even if that earlier framing update was not installed. Both files have refreshed
   timestamps; preserve them when extracting/copying. The unchanged installer uses
   timestamps when only weak `wc` comparisons are available.
3. Run the existing ModKit installation flow, parked with the engine running,
   following its media-removal/reboot prompts. Check the installation log for
   `install:` on the cluster player. If it says `skip identical:` for that player,
   the new player has not been established; stop this test and inspect the card.
   `force_install.txt` only bypasses the firmware gate and does not force a copy.
4. Reboot with the phone disconnected. Confirm Audi's cockpit map visibly works.
   In interactive SSH, run `/eso/bin/apps/dmdt gd`. It should include source33 and
   should not include source62. If source62 is already registered before AA connects,
   stop the test and restore the previous player; this slot is not available.

## Run one 12-second selection test

1. After restarting, upload the downloaded script to
   `/tmp/test_mh2p_route62.sh` using SCP. Run it manually, never through Persist or
   ModKit. A reboot can clear `/tmp`.
2. Connect the phone and wait until AA is running on the MMI. Its cockpit map will
   not select itself with this probe. Run `/eso/bin/apps/dmdt gd` directly in the
   terminal. Confirm **both** native source33 and the new AA source62 are registered.
   If only source33 exists, do not issue selection commands: the patched player may
   not have been installed or its secondary decoder may not have started.
3. Stay on the navigation view and run:

   ```sh
   ksh /tmp/test_mh2p_route62.sh
   ```

   Before changing a display, the script checks the two known Screen text-property
   aliases. It requires native33 and AA62 to have the expected owners, parent
   attachment, streams and dimensions, and AA62 to have its visible admission flag.
   A refusal sends no display-changing commands. These metadata checks do not prove
   that the current AA buffers contain correct visible pixels.

   The script then requests the AA composition for 12 seconds and sends `sb 4`.
   It also attempts that cleanup on HUP, INT, TERM and ordinary shell exit. It makes
   no installed-file changes and creates only a temporary, single-file concurrency
   lock. SIGKILL, a shell crash or a power failure cannot run shell cleanup.
4. Observe whether AA appears during those 12 seconds and whether it is centered.
   Afterwards, disconnect the phone and wait 10 seconds. Observe whether Audi's map
   returns without restarting the MMI. Candidate 2's existing guard still hides/
   suspends the native viewer while AA is connected, so the image immediately after
   the timed selection ends may be black until the phone is disconnected.
5. Copy the script's terminal output and report those two visual results. Run
   `dmdt gs` and `dmdt gd` directly after disconnect if further diagnosis is needed.
   Do not redirect these queries to a file: the stock tool uses `quick_exit` without
   an explicit stdio flush; direct terminal output worked where file capture was empty.
   An exit status of zero confirms command submission, not visible restoration.

If the override remains active, the verified manual release is:

```sh
/eso/bin/apps/dmdt sb 4
```

Disconnect the phone and wait for the guard's native-map wake. If necessary, use the
normal MMI button restart, with the phone disconnected; the override is memory-only.
The source33 binding is not recreated by `sb`: this probe is testing whether avoiding
its replacement preserves it in the first place.

After the test, restore the **previous player and hook** from the untouched test-card
copy and run the same ModKit installation flow. This restores the previous candidate's
behavior, including its known disconnect limitation. The existing uninstaller,
failsafe and original-files recovery procedure remain unchanged and still rely on
valid preserved backups; no new recovery guarantee is made.

## Exactly what changes

The builder accepts only centered candidate 2 SHA-256
`baf5a72c2f15eff8be71b9c79988e4bf497fc8293f31e07330ccd0fcdcacfff2`.
Only the player payload changes: ASCII `33` becomes `62` at offsets 0x14c10/0x14c11.
All executable ELF sections and all five literal pointer references remain identical.
This also moves that player's test/logo/splash windows to62. Existing native log
messages still mention displayable33; query the real window ID instead of trusting
those unchanged strings. Display-size detection retains its original code.

The AA JAR (including readiness gate and candidate 2 guard), hook bytes, configuration,
signed loader, wrapper, installer, uninstaller and failsafe are byte-identical to
centered candidate 2. Only the player and hook ZIP entry mtimes are refreshed to make
same-size data patches visible to the unchanged installer on a `wc`-only unit.

The hook preserves the requested framing:

```text
height_margin=358
margin_top=179
margin_bottom=180
inset_bottom=195
inset_left=160
```

Unspecified values stay as before: inset_right160, density215, real_density215.
The five shell-fixture refusal/failure cases, cleanup on interruption, archive
integrity and unchanged executable sections are host-tested. QNX execution, source62
registration, its geometry and visible AA/native hand-back remain unverified.

Rebuild and run host checks:

```sh
python3 modkit/build_route62_probe.py \
  builds/ClusterIntegration_q7_p2711_candidate2_centered.zip /path/to/NEW-probe.zip
python3 modkit/test/route62_probe_test.py
```

Automatic selection, session/acknowledgement tracking, dynamic layout handling and
collision admission must be implemented and verified separately after this routing
test. Do not use this development probe as a daily automatic release.
