# Audi A6 C8 ER P2711 — candidate5: candidate1 with centered screen margins

Candidate5 uses the exact candidate1 runtime, with the owner's requested screen
framing. Target: **Audi A6 C8, MH2p_ER_AUG35_P2711, 1920x722 Virtual Cockpit**.
The owner reports that candidate1 fixed the original startup-readiness issue.
Candidate5 is an experimental package; it has not yet been tested in the car.

Download **ClusterIntegration_a6_c8_p2711_readiness_centered_candidate5.zip**:

- [Installable SD ZIP](https://raw.githubusercontent.com/toto99303/mh2p-cluster/audi-q7-p2711/builds/ClusterIntegration_a6_c8_p2711_readiness_centered_candidate5.zip)
- [SHA-256 checksum](https://raw.githubusercontent.com/toto99303/mh2p-cluster/audi-q7-p2711/builds/ClusterIntegration_a6_c8_p2711_readiness_centered_candidate5.zip.sha256)
- [Package verification and host checks](https://github.com/toto99303/mh2p-cluster/blob/audi-q7-p2711/audi/a6-c8-p2711/CANDIDATE5_VALIDATION.md)

GitHub's Source code ZIP is not the installation package. In PowerShell, check
the downloaded file with:

```powershell
Get-FileHash -Algorithm SHA256 .\ClusterIntegration_a6_c8_p2711_readiness_centered_candidate5.zip
```

Compare its result with the `.sha256` file before extracting.

## Behavior and screen settings

Candidate1's verified MH2p map-readiness gate is retained: AA secondary video
waits for Audi's cockpit map to initialize and draw its first frame. Main-screen
AA, audio and navigation events retain candidate1 behavior. The AA JAR and
source33 decoder are byte-for-byte candidate1. There is no candidate2 hand-back
helper, source62 player or candidate4 automatic routing/query controller.

```text
height_margin=358
margin_top=179
margin_bottom=180
inset_bottom=195
inset_left=160
```

The existing right inset of 160 and density/real_density of 215 are preserved.
Only inset_left differs from candidate1 (450 -> 160): exactly two bytes in
`gal_cluster.so`'s fixed display-template data. Native instructions are unchanged.
The ZIP's README and JAR/player/hook timestamps are refreshed; the timestamps
make the original QNX installer detect the switch back from candidate4's
same-sized source62 player. Installer and rollback scripts are unchanged.

**Known limits:** candidate1's black cockpit after phone disconnect remains.
This package does not claim to fix the later reported freeze/reboot. Candidate1's
locking and lifecycle behavior are also retained; long-running stability has
not been established. Candidate4 remains withdrawn. Steering-wheel zoom is absent.

## Updating an existing ClusterIntegration installation

Use this path if the mod is still installed, including candidate4. **Do not
uninstall first.** If you have already restored stock, use the full-package path
below instead.

1. Save your original pre-mod SCP backup and every used SD card backup unchanged.
   Make a working copy of your latest installation card. Separate historical
   cards do not have cumulative backups; do not merge their Backup folders.
2. Extract candidate5 to a separate PC folder. From it, copy **these three files**
   to the same paths on the working SD card, replacing the existing files:

   ```text
   Mods/ClusterIntegration/Update/aa_only/ClusterIntegration_v0034_beta2_candidate_90d0b76_aa.jar
   Mods/ClusterIntegration/Update/cluster
   Mods/ClusterIntegration/Update/gal_cluster.so
   ```

   Preserve the extracted file timestamps (11 November 2026). Replace all three,
   including `cluster`: candidate4's decoder uses source62, while candidate5
   requires candidate1's source33 decoder. Keep Backup, manifest, configuration
   and logging markers already on the working card. Do not replace the entire
   Update folder or copy files from the current repository's generic build tree.
3. Remove any `Mods/ClusterIntegration/uninstall.txt` or root-level `failsafe.sh`
   from this working update card. Retain the untouched recovery card/copies.
4. Disconnect the phone, park the car and run the existing ModKit SD update flow.
   Keep power available, follow the on-screen prompts, and remove the card when
   requested. Allow the MMI restart to complete so the old classes are unloaded.
5. Inspect the latest block in `Logs/ClusterIntegration.log` on the card. For an
   update from candidate4, both the AA JAR and `Update/cluster` must be installed;
   `skip identical` for the old source62 player is not a successful downgrade.
   If either was skipped or a copy/restore failed, save the log before retrying.

## Installing after a verified return to stock

Preserve your pre-mod SCP backup. Use an empty FAT32 SD card and extract the
**whole installable candidate5 ZIP** to its root. Opening the card must show
`Data`, `Meta`, `Mods`, `Logs` and `README.md` immediately, without a containing
ZIP-name folder. Leave recovery markers absent and keep the supplied files
together. Run the same powered, parked ModKit update flow above. Afterwards,
save the whole used card, including its new `Mods/ClusterIntegration/Update/Backup/`
and `Logs/ClusterIntegration.log`, to a separate PC folder.

The package contains ClusterIntegration only. A surviving NaviCompass.jar from
the old v1.1 installation is a separate mod; this update neither installs nor
removes it.

## Rollback and checks

The original MH2p rollback files are preserved. For normal ClusterIntegration
removal, add an empty `Mods/ClusterIntegration/uninstall.txt` to the saved working
card and use the normal ModKit update flow. Keep its Backup/manifest intact.
Inspect individual restoration results; a final Done line alone is insufficient.

For an attempted early-startup rollback, the supplied
`Mods/ClusterIntegration/failsafe.sh` can be copied to the card root. Its log is
`Logs/ClusterIntegration-failsafe.log`; remove that root copy after recovery.
These procedures remove ClusterIntegration, not all ModKit infrastructure or
NavCompassIgnore. The existing backup/restore limitations remain.

The pre-mod SCP backup remains the source for verified stock originals.

Check the phone-connected startup, then confirm the centered AA view. The runtime
log still reports candidate1's `kombi-guard p2711-readiness-gate`, because its JAR is
intentionally unchanged. The expected readiness line is
`MH2P_VIDEO_GATE READY initialized=1 first_drawn=1`. Save `/tmp/cluster.log` before
restarting and observe longer-term operation; a successful startup does not
establish a fix for the previously reported freezes.

## Reproduction

```sh
python3 modkit/build_candidate5_package.py \
  builds/ClusterIntegration_q7_p2711_coldstart_candidate1.zip \
  builds/ClusterIntegration_a6_c8_p2711_readiness_centered_candidate5.zip
```

Use a new output filename if it already exists. The builder pins candidate1's
SHA-256 to `99f236136a3615b2dbd819538eecd63e032bb34cbdac98554d95e63964714f22`.
No stock firmware inputs or Java/native compilation are needed. Historical
candidate1 source is at commit `7b0e527`; current source62 build scripts do not
produce this package. The source directory's Q7 name is historical provenance,
not the identity of the owner's A6 C8.
