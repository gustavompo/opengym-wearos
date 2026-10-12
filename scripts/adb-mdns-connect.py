"""Resolve a Wear OS watch's wireless-debugging *connect* port via mDNS — no scanning.

The watch advertises a service instance of `_adb-tls-connect._tcp.local` with the
current ephemeral connect port in its SRV record. On this rig the Galaxy Watch 5 Pro
answers *unicast* mDNS queries only (multicast discovery — `adb mdns services` —
never sees it, see `docs/notes/24-wireless-adb.md`), so this sends the query
directly to the watch's IP. That is one UDP round trip instead of scanning ~28 000
TCP ports. RFC 6762 §5.1/§6.7: a query sent to a specific address with the
unicast-response (QU) bit set.

    python scripts/adb-mdns-connect.py                  # default target 192.168.86.21
    python scripts/adb-mdns-connect.py 192.168.86.21    # explicit IP
    python scripts/adb-mdns-connect.py --query-only     # print the port, do not connect
    python scripts/adb-mdns-connect.py --pair           # resolve _adb-tls-pairing instead

The scanner (`scan_adb_port.py` / `scan-adb-port.ps1`) remains as a last resort for
a watch that answers neither multicast nor unicast; use this script first.
"""

from __future__ import annotations

import argparse
import socket
import struct
import subprocess
import sys
import time

PORT = 5353
SERVICE_CONNECT = "_adb-tls-connect._tcp.local"
SERVICE_PAIR = "_adb-tls-pairing._tcp.local"
DEFAULT_IP = "192.168.86.21"


def encode_name(name: str) -> bytes:
    out = b""
    for part in name.split("."):
        b = part.encode()
        out += bytes([len(b)]) + b
    return out + b"\x00"


def build_query(service: str) -> bytes:
    hdr = struct.pack(">HHHHHH", 0, 0, 1, 0, 0, 0)
    return hdr + encode_name(service) + struct.pack(">HH", 12, 0x8001)  # IN, QU


def parse_name(data: bytes, off: int):
    labels: list[str] = []
    jumped = False
    start = off
    while True:
        if off >= len(data):
            return ".".join(labels), start
        l = data[off]
        if l == 0:
            off += 1
            break
        if l & 0xC0 == 0xC0:
            ptr = struct.unpack(">H", data[off:off + 2])[0] & 0x3FFF
            if not jumped:
                start = off + 2
            jumped = True
            off = ptr
            continue
        off += 1
        labels.append(data[off:off + l].decode("utf-8", "replace"))
        off += l
    if not jumped:
        start = off
    return ".".join(labels), start


def parse_srv_records(data: bytes, service: str):
    """Return [(instance_name, port, target), ...] for SRV records of `service`."""
    if len(data) < 12:
        return []
    _, _, qd, an, ns, ar = struct.unpack(">HHHHHH", data[:12])
    off = 12
    for _ in range(qd):
        _, off = parse_name(data, off)
        off += 4  # qtype+qclass
    records = []
    for _ in range(an + ns + ar):
        try:
            name, off = parse_name(data, off)
        except Exception:
            break
        if off + 10 > len(data):
            break
        rtype, _, _, rdlen = struct.unpack(">HHIH", data[off:off + 10])
        off += 10
        rdata = data[off:off + rdlen]
        if rtype == 33 and rdlen >= 6 and name.endswith(service):
            _, _, port = struct.unpack(">HHH", rdata[:6])
            target = ""
            if rdlen > 6 and rdata[6] != 0:
                try:
                    target, _ = parse_name(data, off + 6)
                except IndexError:
                    target = ""
            records.append((name, port, target))
        off += rdlen
    return records


def resolve(ip: str, service: str, attempts: int = 3, wait: float = 2.0) -> int | None:
    """Unicast mDNS query; return the SRV port, or None."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", 0))
    s.settimeout(wait)
    query = build_query(service)
    for _ in range(attempts):
        s.sendto(query, (ip, PORT))
        deadline = time.time() + wait
        while time.time() < deadline:
            try:
                data, _ = s.recvfrom(9000)
            except socket.timeout:
                break
            recs = parse_srv_records(data, service)
            if recs:
                # the newest registration is fine; prefer any with the known instance
                recs.sort(key=lambda r: " (2)" in r[0])  # "(2)" = older duplicate
                return recs[0][1]
        time.sleep(0.3)
    return None


def adb_path() -> str:
    return r"C:\Users\gusta\AppData\Local\Android\Sdk\platform-tools\adb.exe"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("ip", nargs="?", default=DEFAULT_IP)
    ap.add_argument("--query-only", action="store_true", help="print the port, do not connect")
    ap.add_argument("--pair", action="store_true", help="resolve _adb-tls-pairing instead")
    args = ap.parse_args()

    service = SERVICE_PAIR if args.pair else SERVICE_CONNECT
    port = resolve(args.ip, service)
    if port is None:
        print(f"NO ANSWER: {args.ip} did not answer a unicast mDNS query for {service}", file=sys.stderr)
        print("Last resort: python scripts/scan_adb_port.py " + args.ip, file=sys.stderr)
        return 2
    print(f"resolved {service} -> {args.ip}:{port}", flush=True)
    if args.query_only:
        print(port)
        return 0

    adb = adb_path()
    target = f"{args.ip}:{port}"
    r = subprocess.run([adb, "connect", target], capture_output=True, text=True, timeout=30)
    out = (r.stdout or "") + (r.stderr or "")
    print(out.strip(), flush=True)
    if "connected" not in out:
        if "refused" in out.lower():
            print("refused: the watch's wireless-debugging listener is down.", file=sys.stderr)
            print("Open Wireless debugging on the watch (its connect screen); the port re-registers over mDNS,", file=sys.stderr)
            print("so just run this script again.", file=sys.stderr)
        else:
            print("timed out: the watch may be dozing — wake its screen and retry", file=sys.stderr)
        return 1
    chk = subprocess.run([adb, "-s", target, "shell", "getprop", "ro.product.model"],
                         capture_output=True, text=True, timeout=20)
    print("model:", (chk.stdout or chk.stderr).strip())
    return 0


if __name__ == "__main__":
    sys.exit(main())