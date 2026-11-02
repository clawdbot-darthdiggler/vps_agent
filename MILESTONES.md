# Android agent manager milestones

## M1 — portable Gradle build

Acceptance:
- [x] Reproduce original build failure with JDK 17 and Android SDK 35.
- [x] Remove machine-specific AAPT2 path; assemble debug APK successfully.
- [x] Restore official Unix/Windows Gradle wrapper scripts and distribution checksum.
- [x] Final clean build, unit tests and Android lint after integration.
- [ ] User imports project and builds on their Android development machine.

Evidence: original build failed with `Specified AAPT2 executable does not exist: /home/progboy/asdk/sdk/build-tools/36.0.0/aapt2`. Removing only this override produced `BUILD SUCCESSFUL`. The committed Kotlin daemon logs were not reproduced on this host; no speculative Kotlin version downgrade or daemon workaround applied.

## M2 — saved coding-agent connections

Scope: Hermes/OpenClaw profiles, separate web and VPS SSH settings, encrypted persistence, browser access, host-verified SSH and explicit Docker shell entry. Chef variant excluded.

Acceptance:
- [x] Unit tests for HTTPS URL policy, legacy migration, host pin match/rejection, Docker command safety, bounded output and UTF-8 chunk decoding.
- [x] Debug APK builds and lint completes without errors (dependency-update/localization warnings remain).
- [x] Android API 29 device tests: separate encrypted profiles, no plaintext test secrets on disk, selection/deletion, UI reload after activity restart and system-bar insets.
- [x] Review follow-up: regression-tested stale SSH write failures, serialized persistence transactions and blocked editing during saves.

Automated verification: clean build succeeded; 8 JVM tests and 6 instrumentation tests passed on an Android API 29 software emulator. Lint: zero errors, 12 warnings (dependency versions and localization). Device tests verify activity restart, not process death/force-stop. Final build and direct instrumentation logs are in `/opt/data/cache/scratch/android-build/handoff-build.log` and `handoff-device.log` on the validation machine; scratch logs are not durable project inputs.
- [ ] User saves two profiles, force-stops/reopens, switches profiles and deletes one.
- [ ] User logs into each deployed provider's actual browser UI.
- [ ] User connects through Tailscale to VPS SSH with verified fingerprint.
- [ ] Wrong fingerprint is rejected; correct fingerprint permits connection.
- [ ] User explicitly opens selected Docker container shell and runs a harmless command.

Manual acceptance and actual Hostinger/Tailscale/container integration are pending. No VPS credentials were requested or used during implementation.

## Next bounded milestones (not yet implemented)

- Full interactive terminal emulation suitable for Hermes/OpenClaw TUIs.
- SSH key authentication and optional SSH port forwarding.
- Provider-native task/session APIs, reconnection and background-session UX after confirming deployed versions.
- Dedicated machine profiles shared by multiple agents, with explicit credential lifecycle.
- Chef application flavor only after the coding manager is accepted.
