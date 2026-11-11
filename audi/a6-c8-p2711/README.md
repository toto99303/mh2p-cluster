# Audi A6 C8 P2711 candidate4 — withdrawn: recovery and reference notes

For the owner's requested return to candidate1 with centered screen margins,
see [candidate5 package and instructions](CANDIDATE5.md). The notes below describe
the withdrawn candidate4 package.

**Candidate4 is withdrawn from recommended use.** The owner reports that the MMI
freezes after running for some time and then reboots. Short connect/disconnect
checks did not establish long-term stability. No corrected installable build has
been validated.

**Already installed?** Preserve your original SCP backup and the complete SD card,
including `Mods/ClusterIntegration/Update/Backup/` and its manifest. Use
[Recovery and rollback](#recovery-and-rollback) below to remove ClusterIntegration
with the existing saved-card procedure. A button restart or `dmdt sb 4` does not
remove the installed controller.

[Withdrawn release notice](https://github.com/toto99303/mh2p-cluster/releases/tag/a6-c8-p2711-candidate4)
· [Stability investigation and host reproduction](STABILITY.md)
· [Freeze/restart troubleshooting and PC-side capture](TROUBLESHOOTING.md)

The reported unit is an Audi A6 C8 with MH2p_ER_AUG35_P2711 and a 1920x722 cockpit.
The backup/installation instructions below are retained as reference. **They are
not a recommendation to install candidate4.** A new SCP copy of an already modified
unit is a current-state backup, not an original-files backup.

## 1. Back up the original files to your PC via SCP

Do this **before installing ClusterIntegration**. The SCP backup is a separate
copy on your computer; the installer will create another backup on the SD card
later. Keep both.

You need working SSH/SCP access to the MMI and a network connection between the
MMI and your PC. This package does not enable SSH. Use the IP address and login
credentials from your existing SSH setup. Keep the MMI powered and the network
connection active until the copy finishes.

Using WinSCP on Windows:

1. Create an empty folder on your PC, for example
   `A6-C8-P2711-before-ClusterIntegration`. Keep it outside the SD card.
2. Open WinSCP, choose **New Site**, and enter:

   | Field | Value |
   |---|---|
   | File protocol | **SCP** |
   | Host name | Your MMI's IP address |
   | Port | Your SSH port, usually `22` |
   | User name | Your SSH account, usually `root` |
   | Password | The password for that account |

3. Connect. The **left panel is your PC**; the **right panel is the MMI**.
   Open the new backup folder on the left. On the right, open `/mnt`.
4. Select the remote **`app` folder** and download it to the PC folder. This
   copies the complete **`/mnt/app` directory from the MMI to the PC**. Use binary
   transfer mode and keep the original file names and directory structure.
   Do not upload anything to the MMI for this step.
5. Let every transfer finish. Resolve any transfer errors before installing.
   Check these downloaded paths on your PC:

   ```text
   A6-C8-P2711-before-ClusterIntegration/
     app/eso/bin/apps/gal
     app/eso/bin/apps/dio_manager
     app/eso/hmi/lsd/
   ```

   The executable files should have non-zero sizes. The local `app` folder should
   contain the downloaded directory tree, not just a shortcut or an empty folder.
6. If `/mnt/ota/modkit` already exists on the MMI, also download that folder into
   a separate `existing-modkit` folder on your PC. It contains existing ModKit
   state and other installed mods. If it does not exist, note that and continue;
   do not create it on the unit.
7. Keep this backup unchanged. Save a note with the vehicle, firmware version,
   date and whether any mods were already installed. Use a different folder for
   any later backup.

This is a **file-level backup**, not a complete flash image. The original-file
copy is available for manual recovery; it is not automatically imported by the
uninstaller. Normal ClusterIntegration rollback uses the originals retained on
the unit and the installer backup on the SD card, described below.

## 2. Prepare the released SD package — first installation

1. Download **`ClusterIntegration_a6_c8_p2711_auto_route62_candidate4.zip`** using
   the installable-ZIP link in the release description. GitHub's **Source code**
   downloads and **Code → Download ZIP** are not installation packages.
2. Check its SHA-256 against the accompanying `.sha256` file. In PowerShell,
   open the download folder and run:

   ```powershell
   Get-FileHash -Algorithm SHA256 .\ClusterIntegration_a6_c8_p2711_auto_route62_candidate4.zip
   ```

   Expected hash, ignoring letter case:

   ```text
   e340bd30f5a4091974345a22147b4666ad98f2cbe06dc2b01ac9759d0d281d85
   ```

3. Use an empty SD card formatted as **FAT32**. A card of 32 GB or smaller is
   straightforward to format on Windows. Formatting erases the card: do not
   format an existing mod card that holds your only installer backup.
4. Extract the ZIP into a temporary folder on your PC. Copy **everything inside
   that extracted folder** to the **root of the SD card**, keeping file timestamps.
   When you open the card, the layout must be:

   ```text
   SD card root/
     Data/
     Meta/
     Mods/
       ClusterIntegration/
         Update/
         Persist/
         config_overrides.txt
     Logs/
     README.md
     LICENSE.md
   ```

   `Data`, `Meta` and `Mods` must be visible immediately, not inside a folder
   named after the ZIP. Copying the unopened ZIP to the card is not enough.
   Keep the supplied configuration and all package folders together.
5. For a normal first installation, use the package as supplied. Do not add an
   `uninstall.txt` marker or copy `failsafe.sh` to the card root: those select
   recovery actions. If preparing the card on macOS, remove its `._` metadata
   files before using it with ModKit.
6. Safely eject the card and make sure its physical write-protect switch is unlocked.

**The three-file replacement method is for upgrades only.** A first installation
needs the full package, including the `Data`/`Meta` ModKit loader and all of `Mods`.

## 3. Install from the SD card

1. Park the car, disconnect the phone or wireless AA adapter, and start the engine
   for the update. The SCP backup from step 1 should already be complete on your PC.
2. Insert the prepared SD card into the MMI's SD slot. The released package includes
   the ModKit loader; follow its on-screen update prompts and automatic restarts.
   In the tested setup, the update sequence starts after inserting the card.
   Allow it to finish; do not manually reboot or remove the card during installation.
3. Remove the card **when the MMI asks you to remove the update media**, then allow
   the MMI to restart normally.
4. Put the card back in your PC. Open **`Logs/ClusterIntegration.log`** on the card.
   The log is appended on each run: check the **latest installation block** for
   `ERROR`, `Aborting` or failed backup messages. A final `Done` line alone does
   not establish a successful install.
5. The installer should have created:

   ```text
   Mods/ClusterIntegration/Update/Backup/
   Mods/ClusterIntegration/Update/Backup/manifest.txt
   ```

   Copy the **whole SD card** to a new PC folder, for example
   `A6-C8-P2711-SD-after-candidate4`. This saves the installer backup, manifest,
   configuration and installation log together. Keep the physical card too.
   Leave the original SCP backup in its separate, unchanged folder.

If the update does not start, check FAT32 formatting, the root-folder layout and
the card's write-protect switch first. If the installer refuses the unit, read the
log before changing anything; `force_install.txt` is not a general repair step.

## 4. Check the result and save logs

1. With the phone disconnected, confirm Audi's map works in the cockpit.
2. Connect the phone and start AA. After native navigation initializes, AA should
   take the cockpit automatically. Initial takeover can take tens of seconds;
   about 43 seconds from AA activation was recorded on a phone-connected reboot.
3. Disconnect the phone. Audi's map should return automatically. Reconnect once
   to check that AA returns again.

The installation log is always on the SD card at `Logs/ClusterIntegration.log`.
For a runtime problem, use WinSCP to download `/tmp/cluster.log` before another
restart. If daemon logging is enabled, also download `cluster_daemon.log` from
the USB root or `/tmp/cluster_daemon.log`. Its absence is normal when daemon
logging is off. To enable it for a later diagnostic run, create an empty
`Mods/ClusterIntegration/daemon_log.txt` on the card and run the installer again.

## Updating an existing installation

This path applies when ClusterIntegration is already installed and you have
already kept its original-file and installer backups.

1. Copy the **entire current SD card** to a new folder on your PC before editing
   it. Preserve `Mods/ClusterIntegration/Update/Backup/` and its `manifest.txt`.
   Keep your original, pre-ClusterIntegration SCP backup as well. If you take a new
   SCP backup now, label it as the current modified state.
2. Extract the new released ZIP into a separate PC folder. On a **working copy**
   of the current card, replace only these files from that extraction:

   ```text
   Mods/ClusterIntegration/Update/cluster
   Mods/ClusterIntegration/Update/gal_cluster.so
   Mods/ClusterIntegration/Update/aa_only/ClusterIntegration_v0034_beta2_candidate_90d0b76_aa.jar
   ```

   Accept replacement of those files and preserve their timestamps. Leave the
   card's Backup folder, manifest, configuration and logging markers in place.
   Do not replace the entire `Update` folder or extract the whole release over
   the existing card. Remove any old `uninstall.txt` or root-level `failsafe.sh`
   recovery marker from the working card before an ordinary installation.
3. Run the SD installation sequence in step 3 above. **Do not uninstall first**:
   this is an update of the installed mod, keeping its original-files backup.
4. Check the installation log and repeat step 4's map checks. The AA JAR should
   show `install:` when it changes; `skip identical:` is expected for payloads
   that are already current. Keep another complete card copy after the update.

The installer can use timestamps when deciding whether same-size files changed.
`force_install.txt` bypasses the firmware gate; it does not force identical files
to be copied. The A6 C8 package is the same tested candidate4 as the earlier
Q7-named ZIP, so changing only that outer filename needs no reinstallation.

## Recovery and rollback

For a cockpit that remains on AA, first disconnect the phone and try this in SSH:

```sh
/eso/bin/apps/dmdt sb 4
```

If needed, use the normal MMI button restart with the phone disconnected. Do not
use bare `shutdown` over SSH; it can halt the HMI without restarting it.

### Normal uninstall using the SD card

1. Use the saved installation card with its **Backup folder and manifest intact**.
   On your PC, create an empty file named exactly
   **`Mods/ClusterIntegration/uninstall.txt`**, next to the `Update` folder.
2. Run the same SD/ModKit procedure as installation and follow the media-removal
   and restart prompts. This invokes the existing uninstaller: it restores the
   retained original receiver files and removes ClusterIntegration's added files.
   Other installed ModKit mods are not removed by this marker.
3. Check `Logs/ClusterIntegration.log` for restoration errors and verify normal
   Audi navigation and AA operation. Keep all backups after uninstalling.
   Remove the `uninstall.txt` marker before a future installation.

This removes ClusterIntegration; it does not reinstall a previous version or
remove ModKit's infrastructure. To return to a previous mod version, restore its
three payload files from its saved card and use the update procedure instead.

### Provided failsafe when normal startup is not available

If the ModKit early-startup path still runs, its existing failsafe can attempt
rollback. On the saved card, copy **`Mods/ClusterIntegration/failsafe.sh` to the
SD card root**, beside `Data`, `Meta` and `Mods`. Boot with that card and allow the
attempt to complete. Its log is `Logs/ClusterIntegration-failsafe.log`.

After recovery, remove the **root-level** copy of `failsafe.sh`, or it will attempt
rollback on subsequent boots. Preserve the original script inside the mod folder.
These recovery paths depend on valid originals/backups. A PC file backup is not
an automatic whole-unit restore; do not overwrite `/mnt/app` wholesale as a normal
uninstall step.

## Known behavior and validation

Candidate4 was tested on the owner's Audi A6 C8 with the firmware above. The earlier
Q7 label came from the upstream base. Automatic connect/disconnect/reconnect and an
MMI button restart with the phone connected worked. Startup can be slow, returning
from the cockpit audio tab can briefly show Audi's map, and a transient query-pipe
error recovered automatically. Full vehicle-sleep cold boots, other firmware and
all alternative layouts remain unverified.

An ordinary JVM exit attempts to release the display override. A killed or hung
JVM cannot execute cleanup, so the manual release command or MMI restart can still
be needed. The installed runtime, requested margins and existing rollback scripts
are unchanged by these documentation updates.

See [validation, implementation and build details](VALIDATION.md) for the recorded
tests and reproduction commands.
