#!/usr/bin/env python3
"""Loopback-only synthetic SSH fixture for Android tests; never executes shell commands.

Requires Paramiko in an isolated test environment. Runtime keys are freshly generated
and saved only under --state-dir (outside the repository). Do not use on a VPS.
"""
import argparse
import base64
import fcntl
import struct
import termios
import tty
import hashlib
import io
import json
import logging
import os
from pathlib import Path
import shlex
import signal
import socket
import threading
import time

import paramiko


class FixtureServer(paramiko.ServerInterface):
    def __init__(self, user_key):
        self.user_key = user_key
        self.username = None
        self.ptys = {}
        self.pty_lock = threading.Lock()

    def get_allowed_auths(self, username):
        return "password,publickey"

    def check_auth_password(self, username, password):
        if username in ("fixture", "docker-denied") and password == "fixture-password":
            self.username = username
            return paramiko.AUTH_SUCCESSFUL
        return paramiko.AUTH_FAILED

    def check_auth_publickey(self, username, key):
        if username == "fixture" and key.asbytes() == self.user_key.asbytes():
            self.username = username
            return paramiko.AUTH_SUCCESSFUL
        return paramiko.AUTH_FAILED

    def check_channel_request(self, kind, chanid):
        return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

    def close_pty(self, channel_id):
        with self.pty_lock:
            pair = self.ptys.pop(channel_id, None)
        if pair:
            for fd in pair:
                try:
                    os.close(fd)
                except OSError:
                    pass

    def close_ptys(self):
        for channel_id in list(self.ptys):
            self.close_pty(channel_id)

    def set_size(self, channel, width, height, pixelwidth, pixelheight):
        with self.pty_lock:
            pair = self.ptys.get(channel.get_id())
            if pair is None:
                raise OSError("PTY already closed")
            fcntl.ioctl(pair[1], termios.TIOCSWINSZ, struct.pack("HHHH", height, width, pixelwidth, pixelheight))
            rows, cols, px, py = struct.unpack("HHHH", fcntl.ioctl(pair[1], termios.TIOCGWINSZ, bytes(8)))
            return cols, rows, px, py

    def check_channel_pty_request(self, channel, term, width, height, pixelwidth, pixelheight, modes):
        if term != b"xterm-256color" or channel.get_id() in self.ptys:
            return False
        pair = os.openpty()
        tty.setraw(pair[1])  # Raw controls/UTF-8 traverse a real kernel PTY, with echo disabled.
        with self.pty_lock:
            self.ptys[channel.get_id()] = pair
        self.set_size(channel, width, height, pixelwidth, pixelheight)
        return True

    def check_channel_window_change_request(self, channel, width, height, pixelwidth, pixelheight):
        try:
            width, height, pixelwidth, pixelheight = self.set_size(channel, width, height, pixelwidth, pixelheight)
            channel.sendall(f"\r\nSIZE:{width}x{height}:{pixelwidth}x{pixelheight}\r\n".encode())
            return True
        except (EOFError, OSError):
            return False

    def check_channel_shell_request(self, channel):
        return self.pty_stream(channel)

    def pty_stream(self, channel, argv=None):
        pair = self.ptys.get(channel.get_id())
        if pair is None:
            return False
        master, slave = pair

        def through_pty(write_fd, read_fd, data):
            result = bytearray()
            # Bound chunks to avoid filling a PTY before its other end is drained.
            for offset in range(0, len(data), 512):
                chunk = data[offset:offset + 512]
                sent = os.write(write_fd, chunk)
                if sent != len(chunk):
                    raise OSError("Short controlled PTY write")
                remaining = sent
                while remaining:
                    got = os.read(read_fd, remaining)
                    if not got:
                        raise EOFError()
                    result.extend(got)
                    remaining -= len(got)
            return bytes(result)

        def emit(data):
            channel.sendall(through_pty(slave, master, data))

        def stream():
            time.sleep(0.05)
            try:
                h, w, pw, ph = struct.unpack("HHHH", fcntl.ioctl(slave, termios.TIOCGWINSZ, bytes(8)))
                emit(f"PTY:{w}x{h}:{pw}x{ph}\r\nKERNEL-PTY:raw\r\n".encode())
                if argv is not None:
                    emit(("ARGV:" + json.dumps(argv) + "\r\n").encode())
                for byte in "é中😀".encode():
                    emit(bytes([byte]))
                emit(b"\r\n\x1b[31mRED\x1b[0m\r\nABC\x1b[2DX\r\nREADY\r\n")
                channel.sendall_stderr(b"EXTENDED-STDERR\r\n")
                while True:
                    data = channel.recv(4096)
                    if not data:
                        break
                    data = through_pty(master, slave, data)
                    if b"\x04" in data:
                        channel.send_exit_status(7)
                        break
                    if b"\x05" in data:
                        break  # Deliberate disconnect without an exit status.
                    emit(b"INPUT:" + data.hex().encode() + b"\r\n")
            except (EOFError, OSError):
                pass
            finally:
                try:
                    channel.close()
                except (EOFError, OSError):
                    pass  # The client may already have closed the transport.
                finally:
                    self.close_pty(channel.get_id())
        threading.Thread(target=stream, daemon=True).start()
        return True

    def check_channel_exec_request(self, channel, command):
        # These are deterministic protocol fixtures, NOT a real Docker installation.
        text = command.decode("utf-8", errors="replace").strip()
        try:
            argv = shlex.split(text)
        except ValueError:
            argv = []
        if argv[:3] == ["docker", "exec", "-it"] and self.username == "fixture":
            rest = argv[3:]
            if rest[:1] == ["--workdir"] and len(rest) >= 2:
                rest = rest[2:]
            if len(rest) >= 3 and rest[:2] == ["--", "test-container"]:
                return self.pty_stream(channel, argv)

        if text in ("true", "/bin/true", "printf ready", "printf 'ready\\n'"):
            result = (b"ready\n", b"", 0)
        elif "docker" in text and self.username == "docker-denied":
            result = (b"", b"permission denied while connecting to Docker daemon\n", 126)
        elif "docker" in text and "missing" in text:
            result = (b"", b"No such container: missing\n", 1)
        elif "docker inspect" in text and "test-container" in text:
            result = (b"true\n", b"", 0)
        elif text == "command -v docker":
            result = (b"/usr/bin/docker\n", b"", 0)
        elif text.startswith(("docker version", "docker info")):
            result = (b"fixture-docker\n", b"", 0)
        else:
            result = (b"", b"Unsupported fixture command (no command was executed)\n", 127)

        def reply():
            time.sleep(0.05)  # Allow the channel request acknowledgement to be sent first.
            try:
                if result[0]:
                    channel.sendall(result[0])
                if result[1]:
                    channel.sendall_stderr(result[1])
                channel.send_exit_status(result[2])
            except (EOFError, OSError):
                pass
            finally:
                channel.close()
        threading.Thread(target=reply, daemon=True).start()
        return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", type=Path, required=True)
    parser.add_argument("--port", type=int, default=22022)
    args = parser.parse_args()
    args.state_dir.mkdir(parents=True, exist_ok=True)
    os.chmod(args.state_dir, 0o700)
    logging.getLogger("paramiko").addHandler(logging.NullHandler())
    logging.getLogger("paramiko").propagate = False
    host_key = paramiko.RSAKey.generate(2048)
    user_key = paramiko.RSAKey.generate(2048)
    wrong_key = paramiko.RSAKey.generate(2048)

    def pem(key, password=None):
        out = io.StringIO()
        key.write_private_key(out, password=password)
        return out.getvalue()

    report = {
        "host": "10.0.2.2", "port": args.port,
        "username": "fixture", "password": "fixture-password",
        "hostKeyType": "ssh-rsa",
        "hostPin": "SHA256:" + base64.b64encode(hashlib.sha256(host_key.asbytes()).digest()).decode().rstrip("="),
        "privateKeyPem": pem(user_key),
        "encryptedPrivateKeyPem": pem(user_key, "fixture-passphrase"),
        "passphrase": "fixture-passphrase", "wrongPrivateKeyPem": pem(wrong_key),
        "stallPort": args.port + 1, "unsupportedPort": args.port + 2,
    }
    path = args.state_dir / "m4-ssh-fixture.json"
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as out:
        json.dump(report, out)
    stopping = threading.Event()
    sockets = []

    def connection(client, mode):
        if mode == "stall":
            try:
                stopping.wait(30)
            finally:
                client.close()
            return
        transport = paramiko.Transport(client)
        server = FixtureServer(user_key)
        try:
            transport.add_server_key(host_key)
            if mode == "unsupported":
                options = transport.get_security_options()
                options.kex = ("mlkem768x25519-sha256",)  # Not supported by the app's pinned JSch 0.2.20.
            transport.start_server(server=server)
            while transport.is_active() and not stopping.wait(0.1):
                pass
        except (paramiko.SSHException, EOFError, OSError):
            pass  # Negative-handshake cases are expected test traffic.
        finally:
            transport.close()
            server.close_ptys()
            client.close()

    def listener(sock, mode):
        sock.settimeout(0.5)
        while not stopping.is_set():
            try:
                client, _ = sock.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            threading.Thread(target=connection, args=(client, mode), daemon=True).start()

    for offset, mode in enumerate(("normal", "stall", "unsupported")):
        sock = socket.socket()
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("127.0.0.1", args.port + offset))
        sock.listen(20)
        sockets.append(sock)
        threading.Thread(target=listener, args=(sock, mode), daemon=True).start()
    def shutdown(*_):
        stopping.set()
    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    print(f"READY: loopback ports {args.port}-{args.port + 2}; fixture JSON: {path}", flush=True)
    try:
        stopping.wait()
    finally:
        for sock in sockets:
            sock.close()


if __name__ == "__main__":
    main()
