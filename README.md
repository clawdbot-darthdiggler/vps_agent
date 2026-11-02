# VPS Agent — Android coding-agent manager

Initial Hermes/OpenClaw connection manager for Docker agents on a VPS. Package identity remains `com.progboy.hermeslink` so the original app's encrypted settings can migrate. This is a connection-management milestone, not yet a full interactive agent terminal or native task dashboard. The chef variant is out of scope.

## Build

Requirements: JDK 17, Android SDK platform 35 and SDK build tools, Android SDK licenses accepted. Open this repository root in Android Studio and select JDK 17 for Gradle. Set the SDK location through Android Studio (`local.properties`, ignored) or `ANDROID_HOME`.

```sh
./gradlew clean :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows: use `gradlew.bat` with the same tasks. APK: `app/build/outputs/apk/debug/app-debug.apk`. Minimum Android version: 8.0 / API 26. Install on a test phone with `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Debug builds are not production-signed; upgrading an existing installation requires its original signing key. Do not uninstall an existing app without accepting that its saved credentials will be lost.

The original Gradle build committed an AAPT2 override pointing at another machine's `/home/progboy/asdk/...` directory. It was reproduced and removed; AGP now resolves its own AAPT2. Official Gradle 8.11.1 wrappers support Unix and Windows, respect JAVA_HOME, and verify the distribution checksum. AGP 8.7.3 and Kotlin 2.0.21 were retained: the archived Kotlin daemon error was not reproduced here, so no speculative daemon/version workaround was added.

## Configure an agent

1. Connect the **Tailscale Android app** to the tailnet that contains your Hostinger VPS. This application does not install/configure Tailscale, change ACLs or provision the server.
2. Create a named **Hermes** or **OpenClaw** profile. Multiple profiles are retained; save before switching. Loading another profile or choosing New deliberately discards unsaved edits.
3. For SSH, enter the **VPS host/IP, SSH port, machine username and machine password**. These are not the agent web credentials. Password authentication must be allowed by your server's SSH policy. SSH keys and Tailscale SSH's browser-based authentication flow are not implemented in this milestone.
4. Enter an independently verified **SHA256 SSH host-key fingerprint**. Obtain it from a trusted server console/admin channel, not from an unverified network connection. The app refuses a different presented key. If the server has multiple key types, the pin must match the key negotiated by this client's SSH implementation; key-type selection is a future improvement. Never bypass validation to make a connection succeed.
5. Optionally enter the exact Docker container name. The VPS account needs Docker permission; the app does not invoke sudo or change group membership. Docker access carries substantial host privileges. Use a least-privileged account where practical.
6. Enter the dashboard's **actual HTTPS URL**. No provider-specific host, port, token exchange or login endpoint is invented. Configure a trusted HTTPS reverse proxy or suitable private HTTPS endpoint separately; HTTP-only endpoints are deliberately rejected.
7. Choose **Save & open dashboard in browser** for the provider's own login/pairing process, or **Save & open SSH controls**. On the SSH screen explicitly choose VPS shell or Docker shell; merely opening the screen executes no remote command.

### Credentials and browser sessions

- Profiles and SSH/web secrets are encrypted using the existing Android Keystore-backed encrypted preference store. No plaintext fallback is used when that store fails.
- Optional HTTP Basic credentials are stored **only as separate reverse-proxy credentials**. They are not injected into browser URLs or request headers; enter them into the browser's authentication prompt yourself. Provider tokens and OAuth/password login are handled by the actual dashboard/browser, not by a generic Basic-auth guess.
- Your chosen browser owns its persistent cookies/login state. Profiles pointing at the same origin share that browser's session; use distinct origins/browser profiles when isolation is necessary. Deleting an app profile does not sign out the external browser.
- Cloud backup/device transfer of app data are excluded. Settings and shell screens prevent screenshots; secret fields are excluded from saved view state. Losing the device/app data loses the saved credentials.
- Legacy ambiguous passwords are never silently reused as VPS SSH passwords. Migration retains a web password only for a legacy web profile and archives the old shared password encrypted. The separate **Forget archived legacy shared password** button deletes that archive.

### Terminal limits

The current terminal is a **line-oriented, non-PTY shell**, not ANSI terminal emulation. It supports shell commands and noninteractive coding-agent commands, streams stdout/stderr, keeps only bounded output, and preserves UTF-8 across network chunks. Docker launch uses a validated container name in `docker exec -i NAME /bin/sh`. Nothing is automatically run on connection except the explicitly selected shell.

Full-screen Hermes/OpenClaw TUIs, interactive prompts, Ctrl-C/terminal control keys, SSH key authentication, SSH tunnels, reconnect/session persistence and native task/session APIs are not implemented yet. Use the provider web UI for interactive work in this milestone. Leaving the SSH screen disconnects; this is not a background execution/session guarantee. Output may contain secrets printed by remote programs even though typed commands are not echoed by the app.

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

Check the runner's `OK (...)` result, not just adb's exit status: adb can exit zero even when tests fail.

See `MILESTONES.md` for verified acceptance and the hands-on checklist. Successful compilation/device-local tests do **not** establish access to your real VPS or provider deployments.
