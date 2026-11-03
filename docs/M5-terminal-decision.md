# M5 terminal integration decision

## Selected boundary

VPS Agent 1.3.0 / code 4 uses source-vendored Termux **terminal-emulator** and
**terminal-view**, pinned to app tag `v0.118.3`, commit
`5b657c6adf4304e5198951ce815fe0205dcac29c`. The pinned root license explicitly
identifies these libraries as Apache-2.0 exceptions. No Termux app, termux-shared,
JNI, native subprocess code or local TerminalSession is included. The independent
app is not relicensed. Full notices ship in APK assets `terminal-notices/`;
selected original source hashes are in `terminal/upstream-sha256.json`.
WcWidth's upstream MIT/Markus Kuhn notices are also preserved from its cited commit.

The real Android View was compiled and drawn to a bitmap from a controlled,
pinned SSH **PTY** before replacing the line shell screen. Its upstream parser,
renderer, key mappings and selection machinery are retained. A small
`RemoteSession` interface replaces concrete local `TerminalSession` references.
`TerminalView` is non-final for the app's input-safety subclass. All upstream file
modifications are marked. The broader upstream repository is maintained, but this
is a deliberately pinned stable component, not a promise that all newer fixes are
present. Review upstream changes before release hardening.

## Transport, memory and lifetime

- `ConnectionService` still owns SSH pin validation and password/RSA-key auth.
  `SshPty` requests `xterm-256color` for host shell and Docker exec channels.
- `TerminalController` owns I/O, cancellation and generation isolation. No network
  calls occur on the UI thread, including writes, close and window-change.
- Main-thread model updates receive at most 4096 bytes per batch. The reader waits
  for delivery before reading another batch (backpressure; no escape-byte drops).
  Input has a 128 KiB byte budget, at most 4096 chunks and 4096 bytes per chunk.
  This accommodates IME code-point bursts without an unbounded queue. Overflow closes
  the connection and does not retry the input. Closing cancels/wipes queued bytes.
- Scrollback is bounded at 2000 total terminal rows. Geometry is bounded to 512
  columns / 256 visible rows. SSH pixel dimensions are columns/rows multiplied by
  cell dimensions. Repeated sizes are coalesced off the UI thread.
- PTY stderr normally arrives with stdout. Nonstandard extended-channel stderr is
  decoded independently into a separate 2048-character plain-text panel. It never
  interrupts a partially received stdout UTF-8 or ANSI sequence. The separate panel
  has no unguarded platform selection/copy menu; terminal selection is epoch-gated.
- Stop, rotation/recreation and vault lock disconnect and clear terminal buffers,
  selection, pending paste/launch dialogs, drafts (including IME composition) and
  old-session input authority. Epoch checks revoke input/copy even before a queued
  UI lock listener runs.
  The screen starts disconnected after recreation. There is no auto-connect,
  command retry, background service, M6 persistence or remote process-stop claim.
  Java immutable strings/VM memory are not guaranteed cryptographically erased.

## Input and launch safety

- Native IME/hardware input and a scrollable Ctrl/Esc/Tab/arrow/Ctrl-C row use the
  upstream key mappings. Ctrl is a one-key latch. No local input echo is added.
- Explicit selection copy is separate from emulator callbacks: remote OSC52
  clipboard writes **and reads** are denied. No URL detection or automatic launch
  is enabled. Autofill is disabled and terminal logs are no-ops.
- Explicit clipboard paste accepts text, not clipboard URI/Intent coercion.
  Clipboard, IME bulk/composition, mouse middle-button and keyboard bulk input
  routes require confirmation for either LF or CR. Paste is bounded to 4000 UTF-8
  bytes, rejects NUL, and uses upstream bracketed-paste semantics after consent.
  A cancelled/stale dialog cannot authorize a replacement session.
- **Host shell**, **Container shell** and **Launch agent** are explicit actions.
  Container shell uses `/bin/sh`. Agent launch has no provider-command default;
  supply the exact installed executable, a JSON array of literal string arguments,
  and an optional absolute container work directory. Each argv value is quoted;
  `--` separates Docker options from the validated container name.
- **Locate (read only)** is an explicit optional `/bin/sh` builtin `command -v`
  lookup plus `pwd`, not execution of a guessed provider `--version` command.
  It requires container `/bin/sh`; it is not provider/API capability discovery.
  Configuration is transient, not persisted, and must be entered again after
  leaving the dialog/screen. Do not put credentials in command arguments.

## Controlled verification and boundaries

`scripts/ssh_fixture.py` is a loopback-only Paramiko protocol peer. M4 auth,
pin, timeout, negotiation and Docker-diagnostic cases remain available. M5 adds
a real kernel PTY (`os.openpty`) in raw/no-echo mode. Input and output traverse
its master/slave descriptors; window-change is applied/read back with
`TIOCSWINSZ`/`TIOCGWINSZ`. Controlled Python code, not a shell, writes/reads it.
It exercises fragmented UTF-8, ANSI color/cursor output,
extended stderr, raw input reporting and exit status. Its Docker branch uses
`shlex.split` and reports argv; **it never runs a shell, Docker or an agent**.
`check_pty_fixture.py` tests this peer separately from the Android integration.

The instrumentation suite exercises the actual Android renderer and controller,
real JSch PTY channels, input/clipboard safety, lifecycle and stale-session races.
The M4 blocked-write regression is retained at its new controller ownership
boundary rather than obsolete Activity fields. Full results are recorded in the
handoff; a build or synthetic fixture is not real VPS/provider acceptance.

## Manual acceptance still required

1. Install/update with the same signing key; verify existing host profiles survive.
2. With an independently verified host pin and Tailscale connected, explicitly open
   a disposable host/container shell. Confirm `stty size`, Ctrl-C, arrows, password
   echo-off, scrollback, selection/copy, CJK/emoji and full-screen redraw on IME resize.
3. Locate and verify the actual installed Hermes/OpenClaw command and version using
   the deployment's official instructions. Enter exact arguments/workdir, launch,
   submit a harmless task, answer a prompt and interrupt. No such deployment test
   has been performed by this implementation.
4. Try cancelling/accepting multiline paste; background, lock and rotate. Expect
   disconnection/cleared output, not task completion or reattachment.
5. Test physical keyboards, your phone's IME, TalkBack, font scaling and small-screen
   landscape. The native View exposes terminal text to accessibility, but a bitmap
   drawing test does not establish TalkBack usability or correct shaping of every
   Unicode grapheme. API26 and current target-version/physical-device validation
   remain release-hardening work; the controlled emulator is API29.

## Verification record — 2026-11-03

On branch `fix/android-agent-manager`, based on `f2809a4` (uncommitted implementation):

```sh
export JAVA_HOME=/opt/data/tools/jdk17 ANDROID_HOME=/opt/data/tools/android-sdk
./gradlew clean assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug
python scripts/run_device_tests.py \
  --fixture-json /opt/data/cache/scratch/android-build/m5-fixture/m4-ssh-fixture.json \
  --device-credential-fixture \
  --log /opt/data/cache/scratch/m5-acceptance-device.log
```

- Clean build passed; **32 JVM tests, 62 API29 device tests, zero failures/skips**.
  Instrumentation success records and declared totals were independently counted.
  The device-credential test restored its temporary lock; removal of the injected
  SSH fixture file was read back after the runner completed.
- Lint: **0 errors**, 47 app warnings and 17 terminal-module warnings. Warnings
  include localization, programmatic View construction/accessibility, dependency
  updates, and unchanged upstream compatibility branches; they are not waived as
  release-quality certification.
- Original M4 host fixture checks, kernel PTY/argv self-test and two descriptor
  cleanup/late-resize tests passed. The Android suite retains actual key-auth
  verification with an intentionally wrong password.
- RED/GREEN regression work covered queued-lock copy revocation, IME draft
  disposal, cancelled receive/input loss, bounded IME bursts, absent exit status,
  stale-session writes, rotation, multiline/bracketed paste, unguarded extended
  stderr selection, stale connection status after stop, and PTY cleanup errors.
- Full-suite runs exposed two test-lifetime assumptions: the key-auth UI test
  clicked before asynchronous authorization; the concurrent-save test could be
  revoked by a preceding Activity's delayed background lock. Tests now await
  authorization and retain an explicit foreground lifetime respectively. No
  production authentication/epoch checks were weakened. The final complete run
  above passed after these changes.
- Source audit verified all 21 selected upstream hashes against the pinned
  checkout. Only `TerminalSessionClient`, `TerminalViewClient` and `TerminalView`
  differ, with modification notices. APK inspection verified the four license
  assets byte-for-byte, real renderer presence, and absence of `.so`, JNI,
  local `TerminalSession` and `ByteQueue` classes.

Evidence logs are under `/opt/data/cache/scratch/`:
`m5-acceptance-build.log`, `m5-acceptance-device.log`,
`m5-final-host-original.log`, `m5-final-host-pty.log`, and
`m5-final-host-cleanup.log`. Earlier failed/red logs are retained separately.

Verified app APK: `app/build/outputs/apk/debug/app-debug.apk` (1.3.0 / code 4).
SHA-256: `3b1a2eea8f9c9529fbb2af9fc9c52113049dbd40b7df17fe4c5049806c83d3dc`.
Both app/test APKs are preserved outside `build/` at
`/opt/data/cache/scratch/android-build/m5-artifacts/` for subsequent milestone
upgrade/regression checks. These are debug artifacts, not production-signed releases.
Independent parent review and the real deployment/physical-phone checks above
remain separate acceptance gates. No commit, push, real VPS access or
`milestone.md` edit was performed by this implementation task.
