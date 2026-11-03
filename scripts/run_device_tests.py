#!/usr/bin/env python3
"""Install/run Android instrumentation on a dedicated emulator and verify actual results.

Build the app and androidTest APKs first. Optional --fixture-json copies freshly
generated synthetic SSH credentials into private app files, then removes them.
This runner refuses physical devices. It never prints fixture credentials.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess


PACKAGE = "com.progboy.hermeslink"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools", "adb"))
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--fixture-json", type=Path)
    parser.add_argument("--device-credential-fixture", action="store_true",
                        help="Opt into temporary screen-lock changes on an unsecured disposable emulator")
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--app-apk", type=Path, default=Path("app/build/outputs/apk/debug/app-debug.apk"))
    parser.add_argument("--test-apk", type=Path, default=Path("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
    args = parser.parse_args()
    adb = args.adb if Path(args.adb).is_file() else shutil.which("adb")
    if not adb:
        parser.error("Set ANDROID_HOME or pass --adb")
    command = [adb, "-s", args.serial]
    def run(parts, **kwargs):
        return subprocess.run(command + parts, capture_output=True, check=True, timeout=600, **kwargs)
    if run(["shell", "getprop", "ro.kernel.qemu"]).stdout.strip() != b"1":
        parser.error("Refusing to modify a physical device: use a dedicated emulator")
    for apk in (args.app_apk, args.test_apk):
        if not apk.is_file():
            parser.error(f"Missing APK: {apk}")
        output = run(["install", "-r", str(apk)]).stdout.decode()
        if "Success" not in output:
            raise RuntimeError("adb did not report installation success")
    args.log.parent.mkdir(parents=True, exist_ok=True)
    # Overwrite old success evidence before running; never accept a stale result.
    args.log.write_text("Instrumentation has not completed.\n")
    fixture_name = "m4-ssh-fixture.json"
    try:
        extra = []
        if args.fixture_json:
            payload = args.fixture_json.read_bytes()
            run(["shell", "run-as", PACKAGE, "mkdir", "-p", "files"])
            run(["shell", "run-as", PACKAGE, "sh", "-c", f'"cat > files/{fixture_name}"'], input=payload)
            actual = run(["shell", "run-as", PACKAGE, "cat", f"files/{fixture_name}"]).stdout
            if actual != payload:
                raise RuntimeError("Fixture read-back mismatch")
            extra = ["-e", "sshFixture", fixture_name]
        if args.device_credential_fixture:
            extra += ["-e", "deviceCredentialFixture", "true"]
        # Stream directly to disk so a runner timeout cannot discard partial test evidence.
        with args.log.open("w") as log:
            subprocess.run(command + ["shell", "am", "instrument", "-w", "-r"] + extra +
                           [f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"],
                           stdout=log, stderr=subprocess.STDOUT, check=True, timeout=900)
        text = args.log.read_text(errors="replace")
        match = re.search(r"^OK \((\d+) tests?\)$", text, re.MULTILINE)
        counts = {int(n) for n in re.findall(r"INSTRUMENTATION_STATUS: numtests=(\d+)", text)}
        if not match or counts != {int(match.group(1))}:
            raise RuntimeError(f"Instrumentation did not complete successfully. See {args.log}")
        if args.fixture_json and "INSTRUMENTATION_STATUS_CODE: -3" in text:
            raise RuntimeError(f"Fixture run skipped tests. See {args.log}")
        print(f"Verified {match.group(1)} device tests passed; log: {args.log}")
    finally:
        if args.fixture_json:
            run(["shell", "run-as", PACKAGE, "rm", "-f", f"files/{fixture_name}"])


if __name__ == "__main__":
    main()
