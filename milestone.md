# VPS Agent — implementation milestones

## Product goal

An Android manager for coding agents running in Docker on the user's Hostinger VPS, reached through Tailscale. Manage the VPS once, attach multiple Hermes/OpenClaw agents, retain the appropriate credentials, and use either the agent's browser interface or a real interactive terminal. Keep server credentials, container selection and provider authentication distinct.

The coding manager comes first. A chef-assistant variant is deferred until the shared connection/session foundation is accepted; it must not complicate the initial implementation.

**Status:** proposed roadmap, not approval of every design choice or a claim that future features exist.
**Published baseline:** `fa7284380f2c48f3eb391f70e2887f4cca1a6354` on `fix/android-agent-manager`.
**Current milestone:** M4 implementation, automated verification and independent review are complete. Remaining coding-manager milestones are authorized; M5 terminal feasibility and M6–M9 design discovery are underway. Real-phone/VPS acceptance remains pending. M8 native adapters remain conditional on verified deployment interfaces.

## Delivery rules

- Deliver one runnable vertical slice at a time. Write a failing regression/behavior test, implement the slice, run the complete relevant suite, and provide an installable APK plus manual checks.
- Track implementation, automated verification, real deployment verification and user acceptance independently.
- Each handoff records the exact commit, APK, build commands, test results and remaining limitations. Publish feature branches when authorized; do not merge into `main` implicitly.
- Preserve existing encrypted profiles and application identity through upgrades. Test migration before changing storage schemas; never reset unreadable secrets silently.
- Use synthetic credentials in automated tests. Enter real secrets only through an appropriate secure mechanism, not repository files, chat, screenshots or diagnostic logs.
- No automatic VPS configuration, Docker permission changes, container installation/restart, public port exposure or credential rotation. Those require separate explicit approval.
- Confirm current official provider documentation and installed versions before selecting libraries, commands, APIs, authentication flows or Android background-service behavior. Candidate approaches below are design proposals, not verified provider contracts.

## Milestone summary

| ID | Runnable deliverable | Implementation | Automated checks | User/deployment acceptance |
|---|---|---|---|---|
| M1 | Portable Gradle build | Complete | Passed at baseline | Pending on user's development machine |
| M2 | Initial saved agent connections | Complete, bounded | Passed at baseline | Pending on phone/VPS |
| M3 | Hosts + Agents manager UI and safe migration | Complete; independent review passed | 11 JVM + 14 API29 device tests passed | Pending |
| M4 | Connection diagnostics and credential lifecycle | Complete; independent review passed | 24 JVM + 46 API29 device tests, zero skips; lint 0 errors / 38 warnings | Pending real phone/VPS |
| M5 | Interactive SSH/Docker agent terminal | Component feasibility in progress | Not yet verified | Pending |
| M6 | Reattachable remote sessions | Planned | Not run | Pending |
| M7 | Browser connection completion and login UX | Planned | Not run | Pending |
| M8A | Native Hermes tasks, if supported by deployment | Optional post-MVP, discovery-gated | Not run | Pending |
| M8B | Native OpenClaw tasks, if supported by deployment | Optional post-MVP, discovery-gated | Not run | Pending |
| M9 | Release hardening and signed test distribution | Planned | Not run | Pending |

**Core MVP:** accepted M1–M7 plus the M9 release gate. Native chat/task APIs are an extension, not a prerequisite for useful browser/terminal access.

## Proposed architecture

Keep the existing Kotlin Android application and refactor incrementally, rather than combining a UI-framework rewrite with connection changes.

Proposed responsibilities within the Android source tree:

- `ui/`: Hosts, Agents, Sessions and Settings screens; explicit loading/error/connection states.
- `model/`: versioned host, agent and session metadata; no secrets in model logging.
- `storage/`: profile repository, transactional migrations and credential vault interface.
- `transport/ssh/`: verified SSH connection, PTY channels and optional local forwarding.
- `terminal/`: terminal rendering, keyboard/control input, sizing and scrollback.
- `providers/`: separate Hermes/OpenClaw capability and launch adapters, not a shared invented login protocol.
- `sessions/`: connection ownership and remote-session identity/reconnection rules.

Start with packages in the existing app module. Introduce Gradle modules only when an independently testable boundary warrants them. Extract networking from Activities before adding full terminal/session lifecycle behavior.

### Data model boundaries

- **HostProfile:** stable ID, display name, hostname/Tailscale address, SSH port/user, authentication method, credential reference, trusted host keys and explicit verification metadata.
- **AgentProfile:** stable ID, provider type, host ID, Docker container identity, optional container user/work directory, browser endpoint, separate agent credential references and validated launch configuration.
- **CredentialRecord:** scoped to a host or agent and a credential purpose; encrypted password/private-key/token material where needed. Metadata references credentials rather than duplicating plaintext secrets.
- **SessionRecord:** stable local ID, host/agent ID, remote session handle where supported, lifecycle state and nonsecret reconnect metadata. A lost socket is not evidence that a remote task stopped or completed.

For migration from the flat current profile model, initially preserve one host record per legacy agent. Offer explicit host merging later; never silently combine credentials merely because two profiles share an address.

## M1 — portable Gradle build (implemented)

**Delivered:** removed the committed AAPT2 path `/home/progboy/asdk/sdk/build-tools/36.0.0/aapt2`; restored official Unix/Windows wrappers and distribution checksum; kept AGP 8.7.3/Kotlin 2.0.21 after reproducing the real failure. Added wrapper line-ending rules and ignored generated Kotlin logs/IDE state.

**Evidence:** original build failed because that AAPT2 executable did not exist. Removing the override produced a successful build. The archived Kotlin daemon startup error was not reproduced; no speculative daemon workaround was added.

- [x] Clean debug build with JDK 17 / SDK 35.
- [x] Standard wrapper and `.idea/` ignore checks.
- [ ] User imports, syncs and builds the published branch on their development machine.

## M2 — initial saved connections (implemented, awaiting field test)

**Delivered:** encrypted Hermes/OpenClaw profiles; separate SSH/web settings; HTTPS external-browser launch; explicit pinned-key VPS/Docker shell actions; bounded UTF-8 output; lifecycle cleanup and stale-write/concurrent-save regression fixes. Legacy shared passwords are not silently reused as VPS passwords.

**Baseline evidence:** 8 JVM tests and 6 Android API 29 instrumentation tests passed; clean build succeeded; lint had zero errors and 12 dependency/localization warnings. Device tests cover encrypted persistence, profile separation/deletion, Activity restart, system-bar insets and concurrency regressions. They do not establish force-stop recovery or real VPS connectivity.

Manual acceptance:
- [ ] Install/update without losing existing settings; account for signing-key compatibility.
- [ ] Save Hermes and OpenClaw profiles, force-stop/reopen, switch and delete a test profile.
- [ ] With Tailscale connected, verify the actual HTTPS dashboards open and login works.
- [ ] Wrong SSH pin is rejected; correct independently verified pin permits login.
- [ ] Open the selected container shell and run a harmless command in a test workspace.

**Limitations:** non-PTY line-oriented terminal; no full TUI, key authentication, tunneling or persistent remote sessions. Browser cookies are browser-owned and shared by origin. No real Hostinger/Tailscale/container integration has been verified yet.

## M3 — shared Hosts + Agents UI (implemented; user acceptance pending)

**Outcome:** configure the VPS once and attach two agent cards without repeatedly copying machine credentials.

**Implementation note:** v3 uses `HostProfile`, `AgentRecord` and `HostAgentState`; the existing flat `AgentProfile` is a resolved transport compatibility object, not the persisted shared-host schema. The proposed dedicated credential-reference vault remains M4 work. Migration keeps encrypted v2 recovery evidence until the first successful v3 mutation, then removes it atomically. Corrupt/future v3 never silently falls back; there is no recovery UI and downgrading is not a supported data rollback. See README for details.

**Verified build:** version 1.1.0 / code 2; clean build, 11 JVM tests and 14 API29 instrumentation tests passed in the parent verification run. Lint: zero errors, 18 warnings (7 dependency-version and 11 localization warnings). Independent review passed with no blocking findings. M3 source/roadmap checkpoint is committed locally as `7bc94df` but is not published.

**Nonblocking follow-up:** add explicit missing-`selectedId` schema rejection, direct concurrent `saveHost`/`saveAgent`/host-delete fixtures, and Activity recreation-during-save coverage. Current editors do not retain unsaved drafts across Activity recreation. These are not claims of failed migration or missing shared-store synchronization.

**Test artifact:** `app/build/outputs/apk/debug/app-debug.apk`, SHA256 `f327245d53c917ee2f78cef8e62b030fefa0874965afba6c8305376bd1114e01`. Parent verification logs: `/opt/data/cache/scratch/android-build/m3-parent-build.log` and `m3-parent-device.log` (temporary validation-machine evidence, not durable repository inputs).

Implementation slices:
1. Add versioned HostProfile/AgentProfile storage and transactional migration fixtures. Preserve IDs/secrets; make failed migrations recoverable without erasing the source data.
2. Build Hosts list/detail and Agents list/detail screens. Each agent explicitly selects a host and provider. Keep existing browser and shell actions working throughout.
3. Show host, provider, container and preferred access mode on agent cards. Do not label an agent online without a recent successful check.
4. Add safe edit/delete flows: deleting an agent must not delete a shared host; deleting a referenced host requires an explicit resolution.

Automated acceptance:
- [x] Flat-profile migration preserves each profile and its distinct credentials, including ambiguous legacy data.
- [x] Two agents can reference one host without sharing agent secrets.
- [x] Host updates affect both linked agents; deletion rules cannot orphan an agent accidentally.
- [x] Save/reload/concurrent edits and empty/error UI states pass tests.

Hands-on acceptance:
- [ ] Create one VPS host, add Hermes and OpenClaw, edit the host once, and confirm both use it.
- [ ] Relaunch the app, switch agents, and verify the existing connection actions still work.
- [ ] Review phone layout, keyboard handling and navigation before adding more screens.

**Dependencies:** M2 migration baseline. **Non-goals:** new authentication protocols, terminal emulation or provider API features.

## M4 — connection diagnostics and credentials

**Outcome:** a user can set up and troubleshoot a host without seeing only a generic connection failure.

Implementation slices:
1. Distinguish validation, DNS/reachability, SSH handshake, host-key mismatch, authentication, Docker permission/container availability and dashboard errors. A reachable host is not the same as a ready agent.
2. Add a fingerprint-verification workflow with key-type information and explicit, separately verified key replacement. Never silently accept changed keys.
3. Add SSH private-key import using a system file picker, encrypted retention and optional passphrase retention. Select supported formats/algorithms only after testing the chosen SSH library on supported Android versions; clearly reject unsupported formats.
4. Add explicit per-credential remember/forget controls and a vault-lock policy. Decide device credential/biometric unlock behavior before enabling it; no plaintext fallback.
5. Add redacted diagnostics export with a preview. Treat IPs, usernames, paths, URLs and terminal output as potentially sensitive too.

Automated acceptance:
- [x] Controlled SSH fixture covers valid/wrong password, key auth, wrong key/passphrase, host-key mismatch, timeout and unsupported algorithm.
- [x] Automated secret-handling checks and independent review pass for app-generated diagnostics, storage, intents and backup policy. Remote terminal output can contain secrets printed by remote programs.
- [x] Forgetting an agent credential does not erase a shared host credential; failed unlock cannot reset the vault.

Verification: clean debug/test APK assembly, 24 JVM tests and 46 API29 instrumentation tests passed with zero skips; lint has 0 errors and 38 warnings. Independent final review passed after regression fixes for stale confirmation saves, malformed key import and lock/access/browser continuation races. Controlled fixtures do not establish real VPS/Docker execution, actual dashboard login, other Android versions or real document-provider behavior. RSA PKCS#1 PEM only; local gate is opt-in UI authentication, not per-use cryptographic authorization.

Hands-on acceptance:
- [ ] Test actual Hostinger SSH policy through Tailscale; distinguish ordinary password/key SSH from any Tailscale SSH authentication flow.
- [ ] Repeat with Tailscale disconnected and incorrect credentials; verify actionable messages.
- [ ] Verify a new host key through a trusted console before accepting replacement.

**Dependencies:** M3. **Non-goals:** installing Tailscale, changing server SSH policy or exposing the Docker socket/API.

## M5 — full interactive terminal (highest-value feature)

**Outcome:** run and interact with the real Hermes/OpenClaw coding assistant inside its selected container from the phone.

Implementation slices:
1. Time-box a terminal component integration spike. Evaluate maintenance, licensing, Android compatibility, accessibility and keyboard behavior against a real PTY before selecting it. Record the decision and notices; do not hand-roll an ANSI renderer as the main path.
2. Replace Activity-owned I/O with a tested SSH connection/session controller. Add PTY allocation and terminal-size updates, preserving pin verification.
3. Add ANSI rendering, scrollback, Unicode, selection/copy and a control-key row: Ctrl, Esc, Tab, arrows and Ctrl-C. Define multiline paste confirmation and do not echo secrets locally.
4. Support explicit Host shell, Container shell and Launch agent actions. Discover the installed command/version/work directory before constructing a launch configuration. Validate/quote every configured command argument; never interpolate raw profile data into shell text.

Automated acceptance:
- [ ] PTY integration fixture covers resize, UTF-8, ANSI cursor/color behavior, stdin, stderr, exit and disconnect.
- [ ] Control keys reach the remote process; multiline paste requires the configured confirmation.
- [ ] Rapid connect/disconnect/switch does not mix output or affect another session.
- [ ] Container selection and launch arguments cannot inject unintended shell commands.

Hands-on acceptance:
- [ ] In a disposable project, launch Hermes, submit a coding task, answer a prompt and interrupt it.
- [ ] Repeat with the actual OpenClaw command available in its container.
- [ ] Rotate the phone, show/hide the keyboard, copy output and resize a full-screen terminal application.

**Dependencies:** M3/M4, actual container/version inventory, approved terminal component. **Non-goals:** guaranteed background survival, autonomous retries of submitted commands or native provider chat APIs.

## M6 — remote session persistence and reconnect

**Outcome:** return to the same running remote session after a network interruption rather than accidentally starting another coding task.

Implementation slices:
1. Verify a server-side session mechanism in the actual containers (a terminal multiplexer is a candidate, not assumed installed). Never install it or modify images without approval.
2. Assign explicit remote session identifiers scoped to host/agent/user; list, attach, detach and separately terminate sessions. Distinguish disconnecting the phone from terminating remote work.
3. Persist only necessary reconnect metadata. Show disconnected/connecting/attached/ended/unknown states, with bounded backoff and user-controlled retry.
4. Verify current Android background restrictions before adding any foreground-service notification. Treat OS process death as expected; remote persistence must not depend on the Android process remaining alive.

Automated acceptance:
- [ ] Dropped network, app recreation and cancellation do not submit a command twice.
- [ ] Reattachment targets the correct remote session after app restart; stale local handles are handled safely.
- [ ] Termination is explicit and cannot target another agent's session.
- [ ] Container restart or missing session reports loss honestly; it does not fabricate task completion.

Hands-on acceptance:
- [ ] Start a harmless long-running test, switch apps/lock the screen, interrupt network, then reattach.
- [ ] Force-stop/reopen Android and confirm remote work is either reattached or accurately reported as unavailable.

**Dependencies:** M5 and approved remote persistence mechanism. **Limit:** persistence across container destruction/redeployment is a separate infrastructure problem.

## M7 — browser access and retained login completion

**Outcome:** reliably open each deployed dashboard without confusing machine credentials, provider tokens and browser sessions.

Implementation slices:
1. Verify each deployment's actual HTTPS endpoint and login/pairing flow. Keep the external-browser path as the default; evaluate Custom Tabs only as a UX change, not as isolated credential storage.
2. Make credential/session ownership explicit: SSH credentials belong to the host; agent secrets belong to their supported auth mechanism; browser cookies belong to the browser.
3. For private/unpublished dashboards, assess an SSH forwarding path only if needed. Prototype redirects, WebSockets, origin checks, secure contexts, certificate validation and reconnect before promising browser compatibility. Do not disable TLS verification or globally enable cleartext traffic to force it to work.
4. Explain browser sign-out versus forgetting app credentials. Identify deployments sharing an origin and document the supported isolation approach; never promise per-agent browser-cookie isolation that the chosen browser cannot supply.

Automated acceptance:
- [ ] Unsafe URLs, embedded credentials and unsupported transport configurations are rejected.
- [ ] Launch/reconnect lifecycle tests cover unavailable browsers and closed forwards if forwarding is implemented.
- [ ] No token/password is injected into a URL, log or unrelated origin by the app.

Hands-on acceptance:
- [ ] Login to both real dashboards, close/reopen the app and verify the expected browser sessions.
- [ ] Exercise streaming responses, redirects and provider pairing, not only the dashboard landing page.

**Dependencies:** real deployment endpoints; M4 for diagnostic UX; M6 only if persistent forwarding is required. **Non-goal:** reverse-engineering private login flows or silently exporting browser cookies.

## M8A / M8B — optional native task interfaces

**Outcome:** native task submission/history/output inside Android, only for capabilities the deployed provider exposes safely.

**Discovery gate for each provider separately:** record installed version, official supported transport/API, authentication, task/session IDs, streaming format, cancellation semantics, permissions and retry/idempotency behavior. If no suitable interface exists, retain the working browser/terminal path. A server-side bridge would require a separate design/security review and explicit deployment approval.

- **M8A Hermes first:** one task from a selected agent/workspace, streamed output, visible approval-required states, task history and reconnect by a real remote ID where supported.
- **M8B OpenClaw second:** implement its own adapter against verified contracts. Do not assume the Hermes protocol or authentication applies.

Automated acceptance for each adapter:
- [ ] Contract fixtures plus a live test deployment cover auth failure, streaming, disconnect, cancellation and unsupported features.
- [ ] Unknown task outcome after a lost response is not retried automatically unless idempotency is guaranteed.
- [ ] Provider permissions/approvals remain enforced; no automatic approval of privileged tool actions.
- [ ] Output/history cache policy is explicit and protects potentially sensitive code or secrets.

Hands-on acceptance for each adapter:
- [ ] Run a small coding task in a disposable repository, inspect output, handle an approval and reconnect.
- [ ] Confirm actual repository results independently; a chat response is not proof that files changed.

**Dependencies:** accepted core connection flow, verified provider interfaces and explicit decision that native tasks are wanted. These milestones are not part of the minimum browser/terminal MVP.

## M9 — release gate and distribution

**Outcome:** a signed test build that can be upgraded safely and used on the user's phone.

- [ ] Preserve Android package identity and establish a securely held signing key/update path; never commit signing credentials.
- [ ] Add build/unit/lint CI and a practical emulator smoke suite. Review workflow permissions before publication.
- [ ] Test minimum supported Android version, the user's actual Android version, and a current target-version device/emulator; include small screens, keyboard, rotation and accessibility.
- [ ] Verify install/update/migration, force-stop/relaunch, credential loss/error handling and Tailscale reconnect.
- [ ] Review dependencies/licenses and current security advisories; resolve lint warnings or document justified exceptions without hiding unrelated problems.
- [ ] Complete secret handling, backup exclusion, host verification, permission and exported-component review.
- [ ] Publish a clearly labeled signed APK through an agreed distribution method, with checksum, release notes, known limitations and rollback constraints. Do not silently upgrade a schema that makes rollback destructive.
- [ ] Obtain explicit user acceptance of the real Hermes and OpenClaw workflows before labeling the app ready.

## Inputs to collect before dependent implementation

These are setup/design questions, not requests to paste secrets:

1. Actual phone model/Android version and development machine OS; result of testing baseline `fa72843`.
2. Hermes/OpenClaw container names, versions, launch commands and intended working directories. Redact secrets from any inventory.
3. Are both browser dashboards already reachable over trusted HTTPS through Tailscale, or are they HTTP-only/private container ports?
4. Does the VPS use ordinary SSH password/key authentication, Tailscale SSH, or both? Which method should the app support first after password access?
5. Should agent work continue while the app is closed? Proposed default: yes, through an explicitly managed remote session, not a permanently running phone socket.
6. Is a full terminal plus browser sufficient for the first release? Proposed default: yes; native task/chat screens follow only after those work reliably.

## Per-milestone handoff template

- Milestone / scope:
- Commit / branch / publication status:
- APK path or release asset / checksum:
- Automated commands and actual results:
- Real VPS/provider checks performed (or not performed):
- Manual acceptance checklist and user result:
- Known limitations / migration or rollback notes:
- Approved next slice:

## Deferred chef variant

After the coding manager is accepted, determine which connection/session components can be reused and which chef-specific UX/data must be isolated. Prefer a separate application identity and credential namespace for a separate app. Do not build chef prompts, recipe storage or a second branding layer into the coding-manager milestones now.
