# Audi A6 C8 ER P2711 — experimental AA left-wheel zoom, candidate6

**Vehicle test build. Candidate5 remains stable.** Only for the owner's A6 C8,
MH2p_ER_AUG35_P2711, 1920x722 Virtual Cockpit. The new library has not yet been
loaded in the car. Host checks do not establish phone support or stability.

The readiness gate, source33 decoder and video hook remain candidate5. Margins
stay358/179/180, bottom inset195, left/right160, density215. Candidate4's routing
controller is absent. Candidate5's disconnect black screen and inherited limits remain.

## Update from candidate5 — do not uninstall first

1. Save the whole last-used candidate5 SD card to a new PC folder, including
   Backup, manifest and Logs. Keep it available for recovery. Preserve your
   pre-mod SCP backup and older cards separately; do not combine Backup folders.
2. Make another working copy of that card for candidate6. Check the downloaded
   ZIP against its `.sha256` using PowerShell's `Get-FileHash -Algorithm SHA256`.
   Extract it into a separate PC folder. GitHub's Source code ZIP is not this package.
3. Copy these **four files** from the extracted package to the same SD paths:

   ```text
   Mods/ClusterIntegration/Update/aa_only/ClusterIntegration_v0034_beta2_candidate_90d0b76_aa.jar
   Mods/ClusterIntegration/Update/gal
   Mods/ClusterIntegration/Update/install.sh
   Mods/ClusterIntegration/Update/mh2p_wheel.so
   ```

   Replace existing copies and preserve extracted timestamps. Keep Backup,
   manifest, configuration and logging markers. The helper is new; the JAR,
   wrapper and installer change. All other payloads are exactly candidate5,
   including its source33 player and centered gal_cluster.so.
4. Remove uninstall.txt from Mods/ClusterIntegration and any root-level
   failsafe.sh on this test card. Park, disconnect the phone and use the normal
   powered ModKit SD update flow. Remove the card when requested and allow the
   MMI restart to finish. Check Logs/ClusterIntegration.log for successful helper,
   AA JAR and GAL wrapper installation; save any backup/copy/permission errors.

For a first installation from verified stock, first follow the repository's SCP
backup guide, then extract the whole installable ZIP to an empty FAT32 card.
This experimental build is intended for the existing candidate5 installation above.

## First parked test and logs

1. Confirm MMI, Audi map and main-screen AA still start normally. The readiness
   line remains `MH2P_VIDEO_GATE READY initialized=1 first_drawn=1` in cluster.log.
2. With AA visible on the cockpit map tab, turn the left wheel one detent each
   way, waiting a second between movements. Record whether it zooms, the direction
   and any main-screen reaction. Try Google Maps and Waze. Stop if the wrong screen
   reacts or other behavior is unexpected.
3. Check audio-tab and Audi-only controls retain native behavior. Reconnect the
   phone and check old steps do not replay. Successful zoom is separate from a
   longer stability test.

Before rebooting, use SCP to copy these individual RAM files to a new PC folder:

```text
/tmp/cluster.log
/tmp/mh2p_wheel_native.log
/tmp/gal_preload.log
/tmp/cluster_daemon.log       (if present)
```

Also save the used SD card and Logs/ClusterIntegration.log. Java messages begin
MH2P_WHEEL. A missing native log is useful startup evidence. Native submitted
counts mean queued reports, not confirmed phone zoom. RAM logs disappear at restart.

## Return to stable candidate5

Use the saved working candidate5 card. If using the candidate6 working card
instead, save it first, then restore these three SD files from the stable ZIP:

```text
Mods/ClusterIntegration/Update/aa_only/ClusterIntegration_v0034_beta2_candidate_90d0b76_aa.jar
Mods/ClusterIntegration/Update/gal
Mods/ClusterIntegration/Update/install.sh
```

Remove Update/mh2p_wheel.so from that SD card. Keep its Backup/manifest and original
candidate5 file timestamps. Run the same parked SD update with phone disconnected,
then allow a full MMI restart. Verify the stable JAR and wrapper were installed.
Do not use uninstall.txt for this downgrade. force_install.txt bypasses firmware
checks, not file comparison; it is not needed.

The stable wrapper does not load the helper and its JAR has no wheel worker.
A leftover /mnt/app/eso/bin/apps/cluster/mh2p_wheel.so is inactive and may be deleted
separately via SCP after recovery is verified. Do not remove other files.

## Existing rollback

MH2p uninstall, rollback and failsafe scripts are byte-identical to candidate5.
The added helper is recorded in the manifest for normal mod removal. These paths
still depend on your saved card/backup history and cannot guarantee recovery from
every startup failure. Returning to candidate5 differs from restoring stock;
your verified pre-mod SCP backup remains the source of stock originals.
