# VPS Agent — Android coding-agent manager

Hermes/OpenClaw connection manager for Docker agents on a VPS. The M5 build (version 1.3.0, code 4) adds an interactive SSH/Docker PTY terminal, preserving M4 connection diagnostics, RSA PEM private-key authentication, explicit credential retention/removal, and the optional local device-authentication gate. Separate Hosts and Agents screens let multiple agents share VPS SSH settings without sharing web credentials. Package identity remains `com.progboy.hermeslink` so existing encrypted settings can migrate. The terminal uses a real ANSI renderer; real VPS/provider acceptance and native task dashboards remain separate work. The chef variant is out of scope.

## Build

Requirements: JDK 17, Android SDK platform 35 and SDK build tools, Android SDK licenses accepted. Open this repository root in Android Studio and select JDK 17 for Gradle. Set the SDK location through Android Studio (`local.properties`, ignored) or `ANDROID_HOME`.

```sh
./gradlew clean :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows: use `gradlew.bat` with the same tasks. APK: `app/build/outputs/apk/debug/app-debug.apk`. Minimum Android version: 8.0 / API 26. Install on a test phone with `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Debug builds are not production-signed; upgrading an existing installation requires its original signing key. Do not uninstall an existing app without accepting that its saved credentials will be lost.

The original Gradle build committed an AAPT2 override pointing at another machine's `/home/progboy/asdk/...` directory. It was reproduced and removed; AGP now resolves its own AAPT2. Official Gradle 8.11.1 wrappers support Unix and Windows, respect JAVA_HOME, and verify the distribution checksum. AGP 8.7.3 and Kotlin 2.0.21 were retained: the archived Kotlin daemon error was not reproduced here, so no speculative daemon/version workaround was added.

## Configure an agent

1. Connect the **Tailscale Android app** to the tailnet that contains your Hostinger VPS. This application does not install/configure Tailscale, change ACLs or provision the server.
2. Open **Hosts → Add host**. Give your VPS a name and save its SSH settings. Then use **Agents → Add agent**, select **Hermes** or **OpenClaw**, and explicitly choose the saved host. Multiple agents can share it. Save before navigating away; leaving an editor discards unsaved edits.
3. In **Host details**, enter the **VPS host/IP, SSH port and machine username**, then select password or RSA PEM private-key authentication. These are not the agent web credentials. Editing this host updates the settings resolved by every linked agent. Your server must permit the selected authentication method. Tailscale SSH's browser-based authentication flow is not implemented.
4. Enter an independently verified **SHA256 SSH host-key fingerprint**, select its RSA or supported ECDSA key type, and check the independent-verification confirmation. Obtain both from a trusted server console/admin channel, not from an unverified connection. Changing an existing pin also requires explicit replacement confirmation. RSA host keys use SHA-2 signatures. Legacy pins remain usable with their previous matching behavior; select and independently confirm a key type when editing them. Never bypass validation to make a connection succeed.
5. Optionally enter the exact Docker container name. The VPS account needs Docker permission; the app does not invoke sudo or change group membership. Docker access carries substantial host privileges. Use a least-privileged account where practical.
6. Enter the dashboard's **actual HTTPS URL**. No provider-specific host, port, token exchange or login endpoint is invented. Configure a trusted HTTPS reverse proxy or suitable private HTTPS endpoint separately; HTTP-only endpoints are deliberately rejected.
7. Save the agent, then use its card's **Open dashboard** or **Open SSH controls** action. The preferred-access setting is displayed metadata; both actions remain available. On the SSH screen explicitly choose Host shell or Container shell; merely opening the screen executes no remote command. Cards do not claim agents are online: connection status has not been checked.

Deleting an agent leaves its host intact. Deleting a host still referenced by agents is blocked; first reassign or delete those agents.

### M3 upgrade and recovery policy

Existing flat v2 profiles migrate to one host per agent, preserving the agent IDs and separate secrets. Equal hostnames are **not** automatically merged because credentials may differ. To share one host after migration, explicitly reassign agents in their editors and delete only the now-unused duplicate hosts.

The migration writes the complete encrypted v3 state in one preference transaction and initially retains the encrypted v2 source as recovery evidence. The first successful v3 mutation—including selecting an agent to open—removes the stale v2 source in the same transaction. Corrupt or future-version v3 data fails closed: it is never automatically replaced with old credentials. There is no user-facing recovery/import screen yet; do not clear app data to work around a migration error. The ambiguous pre-v2 password archive remains separately forgettable.

Downgrading to the old build is **not a supported data rollback**: it cannot read v3, and the v2 source may already have been retired. Keep the existing signing key for updates; uninstalling loses encrypted data.

### Credentials and browser sessions

- Profiles and SSH/web secrets are encrypted using the existing Android Keystore-backed encrypted preference store. No plaintext fallback is used when that store fails.
- Optional HTTP Basic credentials are stored **only as separate reverse-proxy credentials**. They are not injected into browser URLs or request headers; enter them into the browser's authentication prompt yourself. Provider tokens and OAuth/password login are handled by the actual dashboard/browser, not by a generic Basic-auth guess.
- Your chosen browser owns its persistent cookies/login state. Profiles pointing at the same origin share that browser's session; use distinct origins/browser profiles when isolation is necessary. Deleting an app profile does not sign out the external browser.
- Cloud backup/device transfer of app data are excluded. Settings and shell screens prevent screenshots; secret fields are excluded from saved view state. Losing the device/app data loses the saved credentials.
- Legacy ambiguous passwords are never silently reused as VPS SSH passwords. Migration retains a web password only for a legacy web profile and archives the old shared password encrypted. The separate **Forget archived legacy shared password** button deletes that archive.

### M4 diagnostics, key import and credential lifecycle

- Use **Test saved host** or **Test connection / diagnostics**, then **Run saved connection checks**. Results distinguish DNS, reachability, SSH negotiation, pin mismatch, authentication, Docker/container and dashboard failures, with an action to take. Checks use bounded network timeouts, a 25-second overall deadline and explicit cancellation; they do not repair server settings or establish persistent online status.
- Dashboard diagnostics send an unauthenticated HTTPS `HEAD` request without redirects. They do not log in or validate a provider API. A login response can require opening the actual dashboard in the browser.
- **Import RSA PEM private key** opens Android's document picker. The file must be at most 64 KiB and use RSA PKCS#1 PEM, unencrypted or passphrase-encrypted. OpenSSH, PKCS#8 and Ed25519 private keys are not supported. Parsing/decryption and actual authentication are checked when connecting. Importing only changes the editor; **Save host** applies it. The app does not retain document access.
- Host password, private key, passphrase and agent password have separate remember controls. Remembered values use encrypted storage; unremembered values last only for the current foreground session. Passphrase retention defaults OFF. **Forget SSH credentials** leaves the host, pin and linked agents intact; **Forget agent credential only** does not erase shared SSH secrets. The separate archived-password removal action deletes obsolete migration credentials.
- **Vault lock policy** enables optional device credential/biometric authentication, default OFF. Configure a secure Android screen lock first. This is a local UI access gate, **not** per-use hardware-backed cryptographic authorization. Backgrounding or **Lock now / clear session secrets** clears ephemeral secrets/unsaved edits and closes connections; the app's active document picker/authentication dialog is exempt. Failed or cancelled unlock does not reset storage. With the gate OFF, returning to the app does not require authentication.
- **Preview diagnostic report / export** shows the exact export before opening the system save picker. Reports are constructed only from format version, fixed stage/code names and bounded elapsed times—not endpoints, usernames, keys, credentials, raw exceptions or shell output.
- M4 retains schema v3 with optional fields for compatibility. Existing M3 records load without resetting credentials. Future/corrupt schemas still fail closed.

### M5 interactive terminal

Choose **Host shell**, **Container shell**, or **Launch agent** explicitly. Opening the screen does not connect or send commands. The native Termux terminal components provide ANSI cursor/color rendering, Unicode, scrollback and selection/copy. Tap the terminal for the keyboard; swipe the control row for Ctrl, Esc, Tab, arrows and Ctrl-C. Ctrl is a one-key latch. Clipboard/IME multiline paste requires confirmation for LF **or CR**; remote OSC52 clipboard access and automatic links are disabled. Paste is limited to 4000 UTF-8 bytes.

**Launch agent** requires the exact installed command, literal arguments as a JSON array (for example `["--help"]` only if documented by that installed program), and an optional absolute container working directory. No Hermes/OpenClaw CLI is guessed. **Locate (read only)** explicitly opens a new PTY to run `command -v` and `pwd` via container `/bin/sh`; it does not execute the provider program or determine its version/API. Configuration is transient and is cleared when the dialog/screen closes. Every command/argument/workdir is validated and shell-quoted. Container access uses `docker exec -it`; no sudo, Docker installation or permission changes occur.

**Leaving, locking or rotating disconnects and clears the terminal.** Returning starts disconnected, without retries or reattachment. This may not stop remote work. M6 persistence/background execution and SSH tunnels are not implemented. Scrollback and transport queues are bounded; extended SSH stderr is shown separately from the ANSI stdout stream. Output can contain secrets printed by remote programs; the app never adds a local echo.

The renderer and real SSH PTY path are tested against a controlled synthetic peer, not the user's VPS. Docker argv tests do not execute real Docker or Hermes/OpenClaw. See `docs/M5-terminal-decision.md` for design, lifecycle, safety boundaries, accessibility/phone acceptance and limitations. Third-party licenses/attribution and modification notices ship in `terminal/src/main/assets/terminal-notices/`; the independent app is not relicensed.

## Tests

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest  # connected emulator/test device required
```

Device tests create and remove synthetic profiles, exercise real encrypted storage, verify no plaintext test secrets appear on disk, reopen the main activity, and check system-bar inset handling. Only run them on a dedicated test app installation.

On slow software-only emulators, Gradle's device property discovery can time out. The equivalent runner can be invoked directly after `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r com.progboy.hermeslink.test/androidx.test.runner.AndroidJUnitRunner
```

Check the runner's `OK (...)` result, not just adb's exit status: adb can exit zero even when tests fail. These generic commands omit opt-in SSH and device-credential fixtures. For the complete zero-skip M4/M5 suite, follow `scripts/README.md` and run `scripts/run_device_tests.py` with both `--fixture-json` and `--device-credential-fixture` on an unsecured disposable emulator. The runner restores its temporary device-lock fixture and removes the injected synthetic credentials.

See `milestone.md` for the implementation roadmap, verified acceptance and hands-on checklists. Successful compilation/device-local tests do **not** establish access to your real VPS or provider deployments.
