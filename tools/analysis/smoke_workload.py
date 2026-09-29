"""Small, explicit RCON workload for an isolated smoke server, never the player's save.

Generates a small chunk square, waits for every chunk to be loaded, and removes only its tickets.
Wall time includes polling; this is a coarse regression check, not a throughput benchmark.
"""
import argparse
import socket
import time

from rcon import AUTH, COMMAND, packet, read_packet


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--origin', type=int, required=True, help='block X and Z; multiple of 16')
    parser.add_argument('--size', type=int, default=8, help='chunk side length, 1..15')
    parser.add_argument('--port', type=int, default=25578)
    parser.add_argument('--password', default='citylines-local-smoke')
    args = parser.parse_args()
    if args.origin % 16:
        parser.error('origin must be chunk-aligned')
    if not 1 <= args.size <= 15:
        parser.error('size must be 1..15')

    def command(text):
        with socket.create_connection(('127.0.0.1', args.port), timeout=60) as sock:
            sock.sendall(packet(1, AUTH, args.password))
            auth = read_packet(sock)
            if auth is None or auth[0] == -1:
                raise RuntimeError('RCON authentication failed')
            sock.sendall(packet(2, COMMAND, text))
            reply = read_packet(sock)
            if reply is None:
                raise RuntimeError('RCON returned no response')
            return reply[2]

    lo, hi = args.origin, args.origin + args.size * 16 - 1
    if 'No force loaded chunks' not in command('forceload query'):
        raise RuntimeError('Use an isolated server with no existing force-load tickets')
    print(command('jfr start').strip(), flush=True)
    start = time.perf_counter()
    try:
        print(command(f'forceload add {lo} {lo} {hi} {hi}').strip(), flush=True)
        pending = set(range(lo, hi + 1, 16))
        while pending:
            for z in sorted(pending):
                checks = ' '.join(f'if loaded {x} 80 {z}' for x in range(lo, hi + 1, 16))
                if 'The time is ' in command(f'execute {checks} run time query gametime'):
                    pending.remove(z)
            if time.perf_counter() - start > 180:
                raise TimeoutError(f'{len(pending)} rows still not loaded')
            if pending:
                time.sleep(0.5)
        print(f'origin={lo} chunks={args.size ** 2} loaded_seconds={time.perf_counter() - start:.3f}', flush=True)
    finally:
        print(command(f'forceload remove {lo} {lo} {hi} {hi}').strip(), flush=True)
        print(command('jfr stop').strip(), flush=True)


if __name__ == '__main__':
    main()
