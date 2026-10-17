"""Interoperability tests using the real mobile JVM engine and the unchanged desktop engine."""
import hashlib
import json
import os
from pathlib import Path
import queue
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT.parent / 'EtherDrop'))
import etherdrop as ed


class Mobile:
    def __init__(self, role, address):
        java = Path(os.environ.get('ETHERDROP_JAVA_HOME', r'C:\Program Files\Java\jdk-21.0.10')) / 'bin/java.exe'
        classpath = os.pathsep.join(str(ROOT / p) for p in ('.expo/test-classes', '.expo/test-deps/json.jar'))
        self.process = subprocess.Popen([str(java), '-cp', classpath, 'NativePeerMain'], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding='utf-8', creationflags=subprocess.CREATE_NO_WINDOW)
        self.events = queue.Queue()
        self.state = {'transfers': []}
        threading.Thread(target=self.read, daemon=True).start()
        self.send(op='start', role=role, address=address)
        self.wait(lambda state: state.get('network') is not None)

    def read(self):
        for line in self.process.stdout:
            self.events.put(json.loads(line))

    def send(self, **command):
        self.process.stdin.write(json.dumps(command) + '\n')
        self.process.stdin.flush()

    def wait(self, predicate, timeout=15):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate(self.state):
                return self.state
            try:
                event = self.events.get(timeout=0.1)
            except queue.Empty:
                continue
            if event['event'] == 'error':
                raise AssertionError(event)
            self.state = event.get('state', self.state)
        raise AssertionError(f'Mobile timed out: {self.state}')

    def close(self):
        if self.process.poll() is None:
            self.send(op='quit')
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        self.process.stdin.close()
        self.process.stdout.close()
        self.process.stderr.close()


class InteroperabilityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'Photos عربي'
        self.source.mkdir()
        (self.source / 'nested').mkdir()
        (self.source / 'nested/hello.txt').write_text('Hello from EtherDrop 📱', encoding='utf-8')
        (self.source / 'empty.txt').write_bytes(b'')
        (self.source / 'payload.bin').write_bytes(bytes(range(256)) * 65537)
        self.events = queue.Queue()

    def mobile(self, role, address='127.0.0.2'):
        mobile = Mobile(role, address)
        self.addCleanup(mobile.close)
        return mobile

    def event(self, action, timeout=15):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            event = self.events.get(timeout=max(0.01, deadline - time.monotonic()))
            if event[0] == 'transfer' and event[2] == action:
                return event
            if event[0] == 'transfer' and event[2] == 'failed':
                self.fail(event)
        self.fail(f'No desktop event {action}')

    def assert_output(self, destination):
        actual = {path.relative_to(destination).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                  for path in destination.rglob('*') if path.is_file()}
        expected = {(self.source.name + '/' + path.relative_to(self.source).as_posix()): hashlib.sha256(path.read_bytes()).hexdigest()
                    for path in self.source.rglob('*') if path.is_file()}
        self.assertEqual(actual, expected)

    def desktop_receiver(self, address='127.0.0.1'):
        receiver = ed.ReceiverService(ed.WifiAdapter('test', 1, address, 8), self.events, threading.Lock())
        receiver.start()
        self.addCleanup(receiver.stop)
        deadline = time.monotonic() + 3
        while not receiver.server_socket:
            if time.monotonic() > deadline:
                self.fail('Desktop receiver did not start')
            time.sleep(0.01)
        return receiver

    def test_mobile_to_windows_verified_files_folders_and_unicode(self):
        self.desktop_receiver()
        mobile = self.mobile('sender')
        mobile.send(op='peer', address='127.0.0.1')
        mobile.send(op='send', selected=[str(self.source)], verify=True)
        offer = self.event('incoming_offer')
        offer[3]['decision'].accept(self.root / 'received')
        mobile.wait(lambda state: state['transfers'] and state['transfers'][0]['finished'])
        self.assertEqual(mobile.state['transfers'][0]['phase'], 'Completed', mobile.state)
        done = self.event('done')
        self.assert_output(Path(done[3]['destination']))

    def test_windows_to_mobile_verified_and_reconnect_from_checkpoint(self):
        mobile = self.mobile('receiver')
        interrupted = threading.Event()
        original = ed.TransferChannel.send_bytes
        def interrupt_once(channel, block):
            original(channel, block)
            if not interrupted.is_set():
                interrupted.set()
                channel.close()
        sender = ed.Sender(ed.WifiAdapter('test', 1, '127.0.0.1', 8), self.events, threading.Lock())
        self.addCleanup(sender.cancel)
        with patch.object(ed.TransferChannel, 'send_bytes', interrupt_once):
            sender.send('127.0.0.2', 'Android', [self.source], True)
            mobile.wait(lambda state: bool(state['transfers']))
            mobile.send(op='accept', id=mobile.state['transfers'][0]['id'], destination=str(self.root / 'received'))
            done = self.event('done', timeout=25)
        self.assertTrue(interrupted.is_set())
        mobile.wait(lambda state: state['transfers'][0]['finished'])
        self.assertEqual(mobile.state['transfers'][0]['phase'], 'Completed', mobile.state)
        self.assert_output(Path(mobile.state['transfers'][0]['destination']))

    def test_mobile_to_two_windows_receivers_decline_independently(self):
        self.desktop_receiver('127.0.0.1')
        self.desktop_receiver('127.0.0.3')
        mobile = self.mobile('sender')
        mobile.send(op='peer', address='127.0.0.1', session='first')
        mobile.send(op='peer', address='127.0.0.3', session='second')
        mobile.send(op='send', selected=[str(self.source)], verify=False)
        for _ in range(2):
            offer = self.event('incoming_offer')
            if offer[3]['peer_ip'] == '127.0.0.2' and offer[1]:
                if not hasattr(self, 'accepted'):
                    self.accepted = offer[1]
                    offer[3]['decision'].accept(self.root / 'received')
                else:
                    offer[3]['decision'].reject()
        mobile.wait(lambda state: len(state['transfers']) == 2 and all(t['finished'] for t in state['transfers']))
        self.assertEqual(sorted(t['phase'] for t in mobile.state['transfers']), ['Completed', 'Declined'])
        self.assert_output(Path(self.event('done')[3]['destination']))

    def test_mobile_sender_resumes_after_windows_connection_drops(self):
        self.desktop_receiver()
        mobile = self.mobile('sender')
        original = ed.TransferChannel.receive_bytes
        received = 0
        interrupted = threading.Event()
        small = sum(p.stat().st_size for p in self.source.rglob('*') if p.is_file() and p.name != 'payload.bin')
        def interrupt_once(channel, block):
            nonlocal received
            got = original(channel, block)
            received += got
            if received >= ed.BLOCK_SIZE + small and not interrupted.is_set():
                interrupted.set()
                channel.close()
            return got
        with patch.object(ed.TransferChannel, 'receive_bytes', interrupt_once):
            mobile.send(op='peer', address='127.0.0.1')
            mobile.send(op='send', selected=[str(self.source)], verify=True)
            self.event('incoming_offer')[3]['decision'].accept(self.root / 'received')
            mobile.wait(lambda state: state['transfers'] and state['transfers'][0]['finished'], timeout=25)
        self.assertTrue(interrupted.is_set())
        self.assertEqual(mobile.state['transfers'][0]['phase'], 'Completed', mobile.state)
        self.assertTrue(any('Continuing from saved progress' in entry for entry in mobile.state['transfers'][0]['log']))
        self.assert_output(Path(self.event('done')[3]['destination']))

    def test_receiver_rejects_paths_outside_the_chosen_destination(self):
        mobile = self.mobile('receiver')
        with socket.create_connection(('127.0.0.2', ed.TRANSFER_PORT), timeout=5) as sock:
            ed.send_frame(sock, {'magic': ed.MAGIC, 'version': ed.PROTOCOL_VERSION, 'type': 'offer',
                'transfer_id': 'unsafe-path', 'hostname': 'Windows', 'manifest': 'test', 'total_bytes': 4,
                'file_count': 1, 'verify': False, 'wrap': False, 'roots': ['outside.txt']})
            mobile.wait(lambda state: bool(state['transfers']))
            mobile.send(op='accept', id='unsafe-path', destination=str(self.root / 'received'))
            reply = ed.recv_frame(sock)
            while reply['type'] == 'ping':
                reply = ed.recv_frame(sock)
            self.assertEqual(reply['type'], 'accept')
            ed.send_frame(sock, {'type': 'file', 'path': '../outside.txt', 'size': 4, 'offset': 0})
            mobile.wait(lambda state: state['transfers'][0]['finished'])
            self.assertEqual(mobile.state['transfers'][0]['phase'], 'Failed')
        self.assertFalse((self.root / 'outside.txt').exists())

    def test_phone_to_phone_receiver_approval_and_unwrapped_transfer(self):
        receiver = self.mobile('receiver', '127.0.0.3')
        sender = self.mobile('sender')
        sender.send(op='peer', address='127.0.0.3')
        sender.send(op='send', selected=[str(self.source)], wrap=False)
        receiver.wait(lambda state: bool(state['transfers']))
        time.sleep(0.3)
        self.assertFalse((self.root / 'received').exists(), 'No files may be created before approval')
        receiver.send(op='accept', id=receiver.state['transfers'][0]['id'], destination=str(self.root / 'received'))
        sender.wait(lambda state: state['transfers'] and state['transfers'][0]['finished'])
        self.assertEqual(sender.state['transfers'][0]['phase'], 'Completed', sender.state)
        self.assert_output(self.root / 'received')

    def test_mobile_receiver_cancel_removes_new_files(self):
        mobile = self.mobile('receiver')
        sender = ed.Sender(ed.WifiAdapter('test', 1, '127.0.0.1', 8), self.events, threading.Lock())
        self.addCleanup(sender.cancel)
        slow = ed.TransferChannel.send_bytes
        def send_slowly(channel, block):
            slow(channel, block)
            time.sleep(0.5)
        with patch.object(ed.TransferChannel, 'send_bytes', send_slowly):
            sender.send('127.0.0.2', 'Android', [self.source], False)
            mobile.wait(lambda state: bool(state['transfers']))
            transfer = mobile.state['transfers'][0]['id']
            mobile.send(op='accept', id=transfer, destination=str(self.root / 'received'))
            mobile.wait(lambda state: state['transfers'][0]['done'] > 0)
            mobile.send(op='cancel', id=transfer)
            mobile.wait(lambda state: state['transfers'][0]['finished'])
            self.assertEqual(mobile.state['transfers'][0]['phase'], 'Cancelled')
            self.event('cancelled')
        self.assertFalse(list((self.root / 'received').rglob('*')))

    def test_mobile_discovery_accepts_desktop_broadcast_format(self):
        mobile = self.mobile('sender')
        packet = {'magic': ed.MAGIC, 'version': ed.PROTOCOL_VERSION, 'mode': 'wifi',
                  'session': 'windows-discovery', 'hostname': 'Actual Windows peer', 'role': 'receiver', 'transfer_port': ed.TRANSFER_PORT}
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.bind(('127.0.0.1', 0))
            sock.sendto(json.dumps(packet).encode(), ('127.0.0.2', ed.DISCOVERY_PORT))
        mobile.wait(lambda state: any(p['name'] == 'Actual Windows peer' for p in state['peers']))

    def test_discovery_recovers_when_wifi_binding_becomes_available_again(self):
        mobile = self.mobile('sender')
        mobile.send(op='connection', connected=False)
        mobile.wait(lambda state: bool(state.get('error')))
        mobile.send(op='connection', connected=True)
        mobile.wait(lambda state: state.get('error') == '')
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.bind(('127.0.0.1', 0))
            sock.sendto(json.dumps({'magic': ed.MAGIC, 'version': ed.PROTOCOL_VERSION, 'mode': 'wifi',
                'session': 'reconnected', 'hostname': 'Reconnected PC', 'role': 'receiver'}).encode(), ('127.0.0.2', ed.DISCOVERY_PORT))
        mobile.wait(lambda state: any(p['name'] == 'Reconnected PC' for p in state['peers']))


if __name__ == '__main__':
    unittest.main(verbosity=2)
