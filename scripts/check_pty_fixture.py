#!/usr/bin/env python3
"""Synthetic PTY protocol self-test. No shell or Docker command is executed."""
import json
import sys
import time
import paramiko

j = json.load(open(sys.argv[1]))
c = paramiko.SSHClient()
# Disposable loopback fixture only; Android tests separately enforce the pin.
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('127.0.0.1', j['port'], username=j['username'], password=j['password'], look_for_keys=False, allow_agent=False)
try:
    ch = c.get_transport().open_session()
    ch.get_pty(term='xterm-256color', width=80, height=24, width_pixels=640, height_pixels=480)
    ch.invoke_shell()
    ch.settimeout(5)
    data = b''
    while b'READY' not in data:
        data += ch.recv(4096)
    assert b'PTY:80x24:640x480' in data, data
    assert b'KERNEL-PTY:raw' in data, 'Fixture did not exercise a kernel PTY'
    assert 'é中😀'.encode() in data, data
    ch.resize_pty(width=90, height=30, width_pixels=720, height_pixels=600)
    ch.sendall(b'\x03\x1b\x09\x1b[A')
    while b'INPUT:031b091b5b41' not in data or b'SIZE:90x30:720x600' not in data:
        data += ch.recv(4096)
    ch.sendall(b'\x04')
    assert ch.recv_exit_status() == 7
    docker = c.get_transport().open_session()
    docker.get_pty(term='xterm-256color', width=80, height=24)
    docker.exec_command("docker exec -it --workdir '/work dir' -- 'test-container' '/opt/agent' 'x'\"'\"'y' '$(touch /tmp/no)' ''")
    docker.settimeout(5)
    data = b''
    while b'READY' not in data:
        chunk = docker.recv(4096)
        assert chunk, 'Docker PTY fixture missing'
        data += chunk
    argv_line = next(line for line in data.decode().splitlines() if line.startswith('ARGV:'))
    assert json.loads(argv_line[5:]) == ['docker', 'exec', '-it', '--workdir', '/work dir', '--', 'test-container', '/opt/agent', "x'y", '$(touch /tmp/no)', '']
    docker.close()
    print('PTY fixture passed: allocation, resize, Unicode, raw controls, exit, literal Docker argv (not execution)')
finally:
    c.close()
