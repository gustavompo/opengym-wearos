"""TCP port scanner for finding a Wear OS device's wireless-debugging *connect* port.

The pairing dialog shows an ephemeral port that stops listening the moment pairing
succeeds; the connect port is a *different* ephemeral port shown on the Wireless
debugging screen itself. When that screen is not readable, scan.

    python scripts/scan_adb_port.py 192.168.86.21
    python scripts/scan_adb_port.py 192.168.86.21 --first 32768 --last 60999
    python scripts/scan_adb_port.py 127.0.0.1 --first 45000 --last 45010   # self-test

Closed ports on Android usually get an RST, so they resolve instantly; the timeout
only bounds genuinely filtered ports. Concurrency is capped by the semaphore, not by
the event loop.
"""

from __future__ import annotations

import argparse
import asyncio
import sys
import time

CONNECT_TIMEOUT = 0.4


async def probe(host: str, port: int, sem: asyncio.Semaphore, timeout: float) -> int | None:
    async with sem:
        try:
            _, writer = await asyncio.wait_for(
                asyncio.open_connection(host, port), timeout=timeout
            )
        except (OSError, asyncio.TimeoutError):
            return None
        writer.close()
        try:
            await writer.wait_closed()
        except OSError:
            pass
        return port


async def scan(host: str, first: int, last: int, concurrency: int, timeout: float) -> list[int]:
    sem = asyncio.Semaphore(concurrency)
    tasks = [asyncio.create_task(probe(host, p, sem, timeout)) for p in range(first, last + 1)]
    found: list[int] = []
    done = 0
    total = len(tasks)
    for coro in asyncio.as_completed(tasks):
        port = await coro
        done += 1
        if port is not None:
            found.append(port)
            print(f"OPEN {host}:{port}", flush=True)
        if done % 4096 == 0:
            print(f"  ...{done}/{total} probed", file=sys.stderr, flush=True)
    return sorted(found)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("host")
    ap.add_argument("--first", type=int, default=1024)
    ap.add_argument("--last", type=int, default=65535)
    ap.add_argument("--concurrency", type=int, default=1000)
    ap.add_argument("--timeout", type=float, default=CONNECT_TIMEOUT)
    args = ap.parse_args()

    started = time.monotonic()
    found = asyncio.run(
        scan(args.host, args.first, args.last, args.concurrency, args.timeout)
    )
    elapsed = time.monotonic() - started
    print(f"scanned {args.first}-{args.last} on {args.host} in {elapsed:.1f}s")
    if not found:
        print("no open ports found")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
