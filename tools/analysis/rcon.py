"""Minimal RCON client for the read-only probe workflow (analysis tool, not mod code).

Gradle does not forward stdin to the dedicated server it launches, so console commands such as
``save-all`` cannot be typed. This speaks just enough of the RCON protocol to send one command and
print the reply, which is what the world-generation probes need (see the delivery record's probe
section: "gradlew runServer 不转发 stdin").

Usage:
    python3 tools/analysis/rcon.py <command> [--port 25575] [--password probe] [--host 127.0.0.1]

The server must run with ``enable-rcon=true`` and a matching ``rcon.password`` in server.properties.
"""
import argparse
import socket
import struct
import sys

AUTH = 3
COMMAND = 2


def packet(request_id, kind, payload):
    body = struct.pack('<ii', request_id, kind) + payload.encode('utf-8') + b'\x00\x00'
    return struct.pack('<i', len(body)) + body


def read_packet(sock):
    raw = sock.recv(4)
    if len(raw) < 4:
        return None
    (length,) = struct.unpack('<i', raw)
    data = b''
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            break
        data += chunk
    request_id, kind = struct.unpack('<ii', data[:8])
    return request_id, kind, data[8:-2].decode('utf-8', 'replace')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command')
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=25575)
    parser.add_argument('--password', default='probe')
    args = parser.parse_args()

    with socket.create_connection((args.host, args.port), timeout=15) as sock:
        sock.sendall(packet(1, AUTH, args.password))
        auth = read_packet(sock)
        if auth is None or auth[0] == -1:
            print('auth failed', file=sys.stderr)
            return 2
        sock.sendall(packet(2, COMMAND, args.command))
        reply = read_packet(sock)
        if reply is None:
            print('no reply', file=sys.stderr)
            return 3
        print(reply[2])
    return 0


if __name__ == '__main__':
    sys.exit(main())
