# Audi A6 C8 ER P2711 — stable baseline: candidate5

The owner selected **candidate5 as the stable baseline on 11 November 2026**.
The installation ZIP is frozen. Wheel-zoom development proceeds separately;
it is not included in this baseline and does not change its files.

Target: Audi A6 C8, **MH2p_ER_AUG35_P2711**, 1920x722 Virtual Cockpit.

- [Download the unchanged installation ZIP](https://raw.githubusercontent.com/toto99303/mh2p-cluster/91331eb6a32c3c885e514115aed59def5039d4ad/builds/ClusterIntegration_a6_c8_p2711_readiness_centered_candidate5.zip)
- [Release](https://github.com/toto99303/mh2p-cluster/releases/tag/a6-c8-p2711-candidate5)
- [Original installation/update instructions](https://github.com/toto99303/mh2p-cluster/blob/91331eb6a32c3c885e514115aed59def5039d4ad/audi/a6-c8-p2711/CANDIDATE5.md)

Filename: `ClusterIntegration_a6_c8_p2711_readiness_centered_candidate5.zip`

SHA-256:

```text
ee5e824e8401366ad138e6b3436003fc2f3fa8aee993fdef6964263487a61da3
```

The existing `a6-c8-p2711-candidate5` tag remains at
`91331eb6a32c3c885e514115aed59def5039d4ad`. Promotion changes the release status
and documentation, not the tag, ZIP, executable bytes or rollback files. An
already installed candidate5 needs no update for this designation.

## What is preserved

Candidate1's AA JAR/readiness gate and source33 decoder are retained. The screen
hook is byte-identical to candidate4's, with height_margin358, top179, bottom180,
bottom inset195, left/right160 and density215. Candidate4's automatic routing and
debug-query controller are absent. Candidate4 remains withdrawn.

The original MH2p installer, uninstaller, failsafe and SSH rollback are unchanged.
Keep the original pre-mod SCP backup, separate historical SD backups and the
current installation card. Their backup folders are not cumulative. Removal of
ClusterIntegration is separate from removing old NavCompassIgnore and ModKit.

## Recorded vehicle feedback

- The owner reports that candidate1 fixed the startup-readiness issue.
- Following the candidate4-to-candidate5 downgrade instructions, the owner
  reported 20 minutes without an MMI reboot and selected this version as the
  stable baseline. No later duration or failure-run resource trace was supplied.
- Photos show Google Maps moving the marker left during guidance, while Waze
  keeps it centered. The Waze photo uses compact gauges; the Google photos use
  circular gauges. No additional margin change was requested.

The stable label records the owner's accepted baseline for this vehicle. It does
not add evidence of a longer soak test, support for other firmware, or a confirmed
root cause for candidate4's freezes. Candidate1's known disconnect black screen
and inherited locking/lifecycle behavior remain. The existing
[host/package verification](CANDIDATE5_VALIDATION.md) is separate from vehicle
feedback. The packaged README is frozen at its original build-time status;
this page records the later stable designation.

## Wheel-zoom development

The [wheel-zoom draft](https://github.com/toto99303/mh2p-cluster/pull/8) is the
separate development track. Experimental changes must start from the verified
candidate5 package and preserve its framing, readiness and rollback payloads
except for specifically reviewed wheel-zoom changes. Do not rebuild the baseline
from the legacy source62 runtime tree or merge that controller back into it.
