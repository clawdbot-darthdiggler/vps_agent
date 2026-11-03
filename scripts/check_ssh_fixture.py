#!/usr/bin/env python3
"""Verify the local synthetic fixture before trusting Android integration results."""
import argparse
import base64
import hashlib
import io
import json
import logging
import socket

import paramiko


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture_json")
    args = parser.parse_args()
    with open(args.fixture_json) as source:
        data = json.load(source)
    logging.getLogger("paramiko").addHandler(logging.NullHandler())
    logging.getLogger("paramiko").propagate = False

    def session(username="fixture", password=None, key=None):
        client = socket.create_connection(("127.0.0.1", data["port"]), timeout=3)
        transport = paramiko.Transport(client)
        try:
            transport.start_client(timeout=3)
            pin = "SHA256:" + base64.b64encode(hashlib.sha256(transport.get_remote_server_key().asbytes()).digest()).decode().rstrip("=")
            assert pin == data["hostPin"], "Fixture host key changed"
            if key is not None:
                transport.auth_publickey(username, key)
            else:
                assert password is not None
                transport.auth_password(username, password)
            return transport
        except Exception:
            transport.close()
            raise

    def command(transport, text):
        channel = transport.open_session(timeout=3)
        channel.settimeout(3)
        channel.exec_command(text)
        out = channel.makefile("rb").read()
        error = channel.makefile_stderr("rb").read()
        code = channel.recv_exit_status()
        channel.close()
        return code, out, error

    t = session(password=data["password"])
    try:
        assert command(t, "true")[0] == 0
        assert command(t, "docker inspect --format '{{.State.Running}}' test-container")[:2] == (0, b"true\n")
        assert command(t, "docker inspect missing")[0] == 1
        assert command(t, "arbitrary-command-must-not-run")[0] == 127
    finally:
        t.close()
    print("PASS password, deterministic Docker checks and command rejection")
    try:
        t = session(password="wrong-password")
        t.close()
        raise AssertionError("Wrong password accepted")
    except paramiko.AuthenticationException:
        print("PASS wrong password rejected")
    for field, password in (("privateKeyPem", None), ("encryptedPrivateKeyPem", data["passphrase"])):
        key = paramiko.RSAKey.from_private_key(io.StringIO(data[field]), password=password)
        t = session(key=key)
        t.close()
    print("PASS unencrypted and encrypted RSA public-key authentication")
    try:
        key = paramiko.RSAKey.from_private_key(io.StringIO(data["wrongPrivateKeyPem"]))
        t = session(key=key)
        t.close()
        raise AssertionError("Unauthorized key accepted")
    except paramiko.AuthenticationException:
        print("PASS unauthorized key rejected")
    t = session(username="docker-denied", password=data["password"])
    try:
        assert command(t, "docker info")[0] == 126
    finally:
        t.close()
    print("PASS Docker permission denial fixture")
    t = paramiko.Transport(socket.create_connection(("127.0.0.1", data["stallPort"]), timeout=2))
    t.banner_timeout = 1
    try:
        try:
            t.start_client(timeout=2)
            assert not t.is_active(), "Stalled peer unexpectedly negotiated SSH"
        except (paramiko.SSHException, EOFError, socket.timeout):
            pass
    finally:
        t.close()
    print("PASS stalled handshake fixture")
    t = paramiko.Transport(socket.create_connection(("127.0.0.1", data["unsupportedPort"]), timeout=2),
                           disabled_algorithms={"kex": ["mlkem768x25519-sha256"]})
    try:
        try:
            t.start_client(timeout=3)
            raise AssertionError("Explicitly disabled algorithm negotiated")
        except paramiko.SSHException:
            pass
    finally:
        t.close()
    print("PASS unsupported algorithm fixture")


if __name__ == "__main__":
    main()
