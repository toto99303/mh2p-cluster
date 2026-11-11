# Candidate6 host validation — 11 November 2026

Package: `ClusterIntegration_a6_c8_p2711_aa_wheel_candidate6.zip`.
SHA256: `f0d7a2ef1011cd422a293d5c43319bb9bd4b464b882199a361b01bd0604ef7fb`.

Stable candidate5 ZIP remains byte-identical, SHA256
`ee5e824e8401366ad138e6b3436003fc2f3fa8aee993fdef6964263487a61da3`.
All candidate5 members remain unchanged except the AA JAR, GAL wrapper,
installer and package README. Only `Update/mh2p_wheel.so` is added.

Passed locally:

- Java1.4 queue/binding tests, including100 reattachments without listener growth,
  first relative detent, bounded pacing, reversal, expiry and reconnect cancellation.
- Coordinator reflection tests with synthetic APIs: one CLUSTER registration job,
  confirmed/requested ownership, readiness gate, pause cancellation, factory
  re-registration, and shutdown cancellation.
- Synthetic bytecode transformation tests under `java -Xverify:all`: both a
  missing and a throwing add-on preserve baseline startup and sender behavior,
  including the original sender's exception. The add-on catch precedes outer
  constructor handlers.
- All105 original integration methods/attributes preserved byte-for-byte under
  the sender rename. All15 original nonconstructor subsystem methods preserved.
  All other baseline JAR entries unchanged; extra classes are only owned wheel
  classes, version48, with no StringBuilder or synthetic OEM fixture classes.
- Pure native protocol tests under ASan/UBSan, including20,000 malformed binding
  cases, negotiated channels, unsupported keys, expiry and epoch overflow.
- Host native companion with synthetic router: primary input forwarded; secondary
  binding on channel37; reconnect remaps to9; positive/negative reports, nanosecond
  timestamps,150ms pacing, duplicates and stale intents; UDP worker does not call
  the router; Java1.4 client interoperates with the native worker. Router init
  retains its success result. GAL child preload is restored to the baseline hook.
- Five archive/installer checks: exact changes, checksum, original method bodies,
  Java48, ARM ELF ABI/import/export/relocation constraints, ksh syntax, helper
  copy/permission failure aborting before Java and manifest tracking on success.

The new ARM library is below64KiB, EABI5 soft-float, with six router exports and
23 public QNX libc/libsocket imports. No Linux runtime, compiler runtime, TLS,
interpreter, extra DT_NEEDED dependency or unexpected ARM relocation is present.
No original OEM class or ARM binary was executed or added to the repository.

Not validated: QNX loader compatibility on the car, physical wheel direction,
phone interpretation as map zoom, behavior across real HMI states and long-session
stability. Candidate6 stays an experimental draft. These tests do not establish
a fix for earlier freezes or the baseline's disconnect black screen.
