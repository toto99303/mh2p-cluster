# Candidate5 package verification

Target: Audi A6 C8, MH2p_ER_AUG35_P2711, 1920x722 cockpit. The owner reports
successful startup-readiness behavior with candidate1. **Candidate5 has not yet
been tested on the vehicle; no freeze/reboot fix or long-term stability is claimed.**

[Package and installation instructions](CANDIDATE5.md).

## Exact inputs and output

Base: `ClusterIntegration_q7_p2711_coldstart_candidate1.zip`

```text
99f236136a3615b2dbd819538eecd63e032bb34cbdac98554d95e63964714f22
```

Output: `ClusterIntegration_a6_c8_p2711_readiness_centered_candidate5.zip`

```text
ee5e824e8401366ad138e6b3436003fc2f3fa8aee993fdef6964263487a61da3
```

No OEM firmware inputs are compiled, executed or newly redistributed. Candidate5
derives from the existing archived candidate1 package, not the current source62
runtime source tree. All 60 ZIP entries retain their names and order.

## Runtime changes

Only two runtime bytes differ from candidate1: offsets `0x73b1` and `0x73b2`
inside `gal_cluster.so`'s fixed 54-byte `dhu_cluster_service` template. These
change `inset_left` from 450 to 160. The codec placeholder remains at byte9.
Every executable ELF section is unchanged. An independent protobuf decoder checks
height_margin358, top179, bottom180, bottom inset195, left/right160 and
density/real_density215 in the built archive.

| Payload | SHA-256 |
|---|---|
| AA-only JAR, identical to candidate1 | `95cb9405de7f241183e28ce81e5ddb8f8614dd9d31d1d27026b270ef76b7310b` |
| source33 player, identical to candidate1 | `067059acbe3917eba5c718ed6d810d1c2cc6eebe61c607d1ad840ec37cb83477` |
| centered `gal_cluster.so` | `68fb179a6521b0a34426a987fb2f9ccdcacee7b2c91ea6022bb0f4b965420c01` |

The player retains ASCII33 at offsets `0x14c10/0x14c11`. Candidate4's routing,
query runner and candidate2's hand-back helper are absent from the AA JAR.
Candidate1's complete JAR is preserved, including its instrumented integration,
gate and native-map guard. Their code and limitations are retained.

The only other changed ZIP payload is README.md, now containing candidate5's
instructions. The AA JAR, player, hook and README member dates are set to
11 November 2026, 18:00:00 for reproducibility. All installer, uninstaller,
failsafe, SSH rollback, Persist, wrapper, configuration, Data and Meta payloads
remain byte-identical to candidate1.

## Same-sized player downgrade

Candidate4's source62 player differs from candidate1's source33 player in two
ASCII bytes. Its size and byte/word/line counts are identical. A host fixture
executes the actual candidate1 installer comparison functions with its weakest
`wc` fallback (Bash stand-ins supply only ksh built-ins):

- With equal timestamps, `files_identical` returns0: it misses the changed player.
- With candidate5's fresh timestamp, it returns1: the installer selects replacement.

The three payload dates are newer than candidate4's corresponding members. This
checks the original comparison logic without changing the installer. Extraction
and copying must preserve those dates; successful vehicle installation still
needs its log and, where needed, post-install file verification.

## Host checks

```sh
ECJ_JAR=/path/to/ecj.jar python3 modkit/test/candidate5_package_test.py
```

All six test groups pass:

- Exact payload/member preservation, CRC, checksum, source33 player and executable-section checks.
- Independent decoding of the built screen settings.
- Actual installer `wc` fallback detects the timestamped player downgrade.
- Builder rejects a wrong base and refuses to overwrite an existing output.
- Rebuilding yields a byte-identical ZIP.
- The exact archived `Mh2pVideoGate.class` passes candidate1's 15 readiness and
  cancellation assertions under host JVM verification, using synthetic OEM API
  fixtures only. No stock HMI class, QNX utility or ARM binary is executed.

These are package and host checks, not vehicle validation. Candidate1's known
disconnect black screen and inherited locking/lifecycle concerns remain.
