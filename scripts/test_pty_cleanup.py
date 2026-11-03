#!/usr/bin/env python3
"""Kernel PTY cleanup regression; no network listener or shell process."""
import os
import threading
import unittest
from typing import Any, cast
from ssh_fixture import FixtureServer


class PtyCleanupTest(unittest.TestCase):
    def test_resize_after_close_is_rejected_without_a_transport_exception(self):
        class Channel:
            def get_id(self): return 1
        server = FixtureServer(None)
        channel = Channel()
        self.assertTrue(server.check_channel_pty_request(cast(Any, channel), b'xterm-256color', 80, 24, 640, 480, b''))
        server.close_pty(1)
        self.assertFalse(server.check_channel_window_change_request(cast(Any, channel), 90, 30, 720, 600))

    def test_peer_close_error_still_releases_kernel_descriptors(self):
        ended = threading.Event()
        class Channel:
            def get_id(self): return 1
            def sendall(self, data): pass
            def sendall_stderr(self, data): pass
            def recv(self, count): return b'\x04'
            def send_exit_status(self, status): pass
            def close(self):
                self.worker = threading.current_thread()
                ended.set()
                raise EOFError('Deliberately closed test transport')
        server = FixtureServer(None)
        channel = Channel()
        server.check_channel_pty_request(cast(Any, channel), b'xterm-256color', 80, 24, 640, 480, b'')
        pair = server.ptys[1]
        try:
            server.pty_stream(channel)
            self.assertTrue(ended.wait(2))
            channel.worker.join(2)
            self.assertFalse(channel.worker.is_alive())
            self.assertEqual({}, server.ptys)
            for fd in pair:
                with self.assertRaises(OSError):
                    os.fstat(fd)
        finally:
            server.close_ptys()


if __name__ == '__main__':
    unittest.main()
