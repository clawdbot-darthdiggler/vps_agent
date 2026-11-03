# Local SSH/device-test fixtures

These scripts are test infrastructure, not an agent backend. They do not connect to the user's VPS or run Docker/shell commands. Use a dedicated emulator installation of the debug app.

## Start the controlled SSH peer

Create an isolated virtual environment outside the repository and install:

```sh
python -m pip install -r scripts/requirements-ssh-fixture.txt
python scripts/ssh_fixture.py --state-dir /your/scratch/m4-fixture
```

Keep that process running in a separate terminal. It binds **only 127.0.0.1**:

- `22022`: password and RSA-public-key SSH authentication; deterministic diagnostic command responses.
- `22023`: stalled SSH handshake for timeout tests.
- `22024`: only `mlkem768x25519-sha256` key exchange, which the application's pinned JSch 0.2.20 does not support. This is a compatibility-negative fixture, not a recommendation to enable insecure algorithms.

`--port` changes the first port; the other two follow consecutively. Each start creates fresh synthetic host/client keys and a restricted-permission `m4-ssh-fixture.json` in the specified scratch directory. That file contains **test-only private keys**, must stay outside Git, and should be removed after testing. Restarting the fixture changes its pin and keys; reinject the new JSON before running tests.

The normal server accepts synthetic username `fixture` with password `fixture-password` or its generated authorized RSA key. `docker-denied` with the same synthetic password simulates Docker permission failure. The fixture supports a restricted diagnostic command vocabulary; arbitrary commands return exit 127 and are never executed. Docker responses are fixtures, not proof of real Docker access.

First verify the fixture itself:

```sh
python scripts/check_ssh_fixture.py /your/scratch/m4-fixture/m4-ssh-fixture.json
```

This checks positive/negative authentication, encrypted-key use, command rejection, simulated Docker results, stalled handshake and algorithm mismatch. This host-side check is **not** a substitute for Android/JSch tests.

## Build and run Android tests

With JDK 17 and Android SDK configured:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
python scripts/run_device_tests.py \
  --adb "$ANDROID_HOME/platform-tools/adb" \
  --serial emulator-5554 \
  --fixture-json /your/scratch/m4-fixture/m4-ssh-fixture.json \
  --device-credential-fixture \
  --log /your/scratch/m4-device-results.log
```

The runner checks that the device is an emulator, installs both debug APKs, injects the fixture into private app files, verifies the copy, supplies the `sshFixture` instrumentation argument, and checks the actual `OK (N tests)` output and declared count. It rejects skipped tests in a fixture-enabled run. adb's exit code alone is not reliable evidence of passing tests. The injected file is removed in `finally`. Instrumentation output streams to the requested log so partial evidence survives a timeout.

`--device-credential-fixture` explicitly opts into a temporary screen-lock PIN on an **unsecured, disposable emulator** to test the real Android authentication/cancellation dialogs. The test refuses a pre-existing secure lock and restores its changes in cleanup. Do not interrupt it mid-test; a forcibly killed process cannot guarantee cleanup. Omit the option for ordinary runs, but the system-auth test will then skip, which deliberately fails the fixture runner's full-coverage check.

The Android emulator reaches host loopback through `10.0.2.2`; this is not a real VPS address. The fixture-dependent tests may be skipped in ordinary device runs without the explicit fixture argument; do not describe those runs as full SSH integration coverage.

Stop the server with Ctrl-C when finished and remove its scratch key material. No fixture process should be left listening after validation. Do not run the scripts on a production server or expose the ports with forwarding/firewall changes.
