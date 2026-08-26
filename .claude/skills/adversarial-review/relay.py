#!/usr/bin/env python3
"""The one door in a reviewer's network boundary (NFR-15d).

review-sandbox.sh launches the reviewer in a private network namespace: no route to this host's
interfaces, loopback included, so the database and the application - which hold really-imported
rows - are unreachable whether or not they are running (NFR-16). The reviewer must still reach a
model, and this file is the whole of how it does:

  host   <socket> <hosts> --review <dest>
         Runs OUTSIDE the boundary. An HTTP CONNECT relay listening on a unix socket, which the
         launcher binds into the boundary (a unix socket is a filesystem object and crosses a
         network namespace where no packet can). It admits a tunnel to one of the named hosts on
         port 443 and refuses every other name, every address literal and every other port with
         403. The name is resolved here, on the host, so the namespace needs no DNS - and what it
         resolves to is vetted too: an address that is loopback, private, link-local or otherwise
         not a public unicast address is refused, so a list entry that is a literal, or a name
         that resolves to this host (an /etc/hosts line, a split-horizon answer), cannot reopen
         the door the namespace closed. Only the vetted addresses are connected to. `--review`
         puts the review's checkout path in this process's command line, which is how `close`
         identifies what belongs to a round.
  bridge <socket> <port>
         Runs INSIDE the boundary. Listens on 127.0.0.1:<port> - the namespace's own loopback,
         which nothing else shares - and forwards each connection to the unix socket. The reviewer
         is given HTTPS_PROXY=http://127.0.0.1:<port>.
  check  <socket> <host> <ports>
         Runs INSIDE the boundary, before a reviewer is spent, and proves the boundary rather than
         trusting it: each of the project's configured <ports> (comma-separated) does not answer on
         127.0.0.1; a tunnel to a name off the list, and to a loopback address literal, is refused;
         a tunnel to <host> is established. Exit status 0 only when all three hold.

The first bytes the reviewer sends down an established tunnel must be a TLS ClientHello whose
server_name is the name the tunnel was opened to: the provider's edge is shared, and a tunnel to
an admitted name with another name in the SNI would reach that other host. A tunnel whose first
record is not such a ClientHello is closed. Beyond that first record nothing here inspects or logs
what passes through; the bytes are TLS end to end.

The host relay dies with the process that started it (PR_SET_PDEATHSIG): a launcher ended with
SIGKILL runs no trap, and a relay that outlived it would hold the round's socket open (NFR-15d).
"""
import asyncio
import ctypes
import ipaddress
import signal
import socket
import sys

CHUNK = 65536


async def pump(reader, writer):
    try:
        while True:
            data = await reader.read(CHUNK)
            if not data:
                break
            writer.write(data)
            await writer.drain()
    except (ConnectionError, asyncio.IncompleteReadError, OSError):
        pass
    finally:
        try:
            writer.close()
        except OSError:
            pass


async def splice(r1, w1, r2, w2):
    await asyncio.gather(pump(r1, w2), pump(r2, w1))


async def read_request(reader):
    """The request line and headers of one HTTP request; headers are read and discarded."""
    line = await reader.readline()
    while True:
        header = await reader.readline()
        if header in (b"\r\n", b"\n", b""):
            break
    return line.decode("latin1").split()


async def respond(writer, status):
    writer.write(f"HTTP/1.1 {status}\r\n\r\n".encode("latin1"))
    await writer.drain()


# ------------------------------------------------------------------ host


def is_literal(name):
    try:
        ipaddress.ip_address(name)
        return True
    except ValueError:
        return False


async def public_addresses(host):
    """The addresses `host` resolves to that are public unicast; anything else is dropped."""
    loop = asyncio.get_running_loop()
    try:
        infos = await loop.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
    except OSError:
        return []
    addresses = []
    for _family, _type, _proto, _canon, sockaddr in infos:
        address = ipaddress.ip_address(sockaddr[0])
        if address.is_global and not address.is_multicast:
            addresses.append(sockaddr[0])
    return addresses


async def connect_any(addresses):
    for address in addresses:
        try:
            return await asyncio.open_connection(address, 443)
        except OSError:
            continue
    return None


def sni_of(record):
    """The server_name in a TLS ClientHello record, or None where there is none or it is not one."""
    try:
        if record[0] != 0x16 or record[5] != 0x01:  # handshake record, ClientHello
            return None
        p = 9 + 2 + 32  # record header (5), handshake header (4), version (2), random (32)
        p += 1 + record[p]  # session id
        p += 2 + int.from_bytes(record[p : p + 2], "big")  # cipher suites
        p += 1 + record[p]  # compression methods
        end = p + 2 + int.from_bytes(record[p : p + 2], "big")
        p += 2
        while p + 4 <= end:
            ext_type = int.from_bytes(record[p : p + 2], "big")
            ext_len = int.from_bytes(record[p + 2 : p + 4], "big")
            body = record[p + 4 : p + 4 + ext_len]
            if ext_type == 0:  # server_name
                q = 2
                while q + 3 <= len(body):
                    name_type, name_len = body[q], int.from_bytes(body[q + 1 : q + 3], "big")
                    if name_type == 0:
                        return body[q + 3 : q + 3 + name_len].decode("ascii").lower()
                    q += 3 + name_len
                return None
            p += 4 + ext_len
        return None
    except (IndexError, UnicodeDecodeError):
        return None


async def first_record(reader):
    header = await asyncio.wait_for(reader.readexactly(5), 30)
    length = int.from_bytes(header[3:5], "big")
    return header + await asyncio.wait_for(reader.readexactly(length), 30)


async def host_client(allowed, reader, writer):
    try:
        parts = await read_request(reader)
        if len(parts) != 3 or parts[0] != "CONNECT":
            await respond(writer, "405 Method Not Allowed")
            return
        host, _, port = parts[1].rpartition(":")
        host = host.strip("[]").lower()
        if host not in allowed or port != "443" or is_literal(host):
            print(f"relay: refused CONNECT {parts[1]}", file=sys.stderr, flush=True)
            await respond(writer, "403 Forbidden")
            return
        addresses = await public_addresses(host)
        if not addresses:
            print(f"relay: refused CONNECT {parts[1]}: no public address", file=sys.stderr, flush=True)
            await respond(writer, "403 Forbidden")
            return
        upstream = await connect_any(addresses)
        if upstream is None:
            await respond(writer, "502 Bad Gateway")
            return
        up_r, up_w = upstream
        await respond(writer, "200 Connection Established")
        try:
            hello = await first_record(reader)
        except (asyncio.IncompleteReadError, asyncio.TimeoutError, OSError):
            up_w.close()
            return
        sni = sni_of(hello)
        if sni != host:
            print(f"relay: closed tunnel to {host}: SNI {sni!r} is not the name it was opened to",
                  file=sys.stderr, flush=True)
            up_w.close()
            return
        up_w.write(hello)
        await up_w.drain()
        await splice(reader, writer, up_r, up_w)
    except (ConnectionError, OSError):
        pass
    finally:
        try:
            writer.close()
        except OSError:
            pass


def die_with_parent():
    pr_set_pdeathsig = 1
    ctypes.CDLL(None, use_errno=True).prctl(pr_set_pdeathsig, signal.SIGTERM, 0, 0, 0)


async def serve_host(path, hosts):
    die_with_parent()
    allowed = {h.strip().lower() for h in hosts.split(",") if h.strip()}
    if not allowed:
        sys.exit("relay: no host to admit")
    server = await asyncio.start_unix_server(
        lambda r, w: host_client(allowed, r, w), path=path
    )
    async with server:
        await server.serve_forever()


# ---------------------------------------------------------------- bridge


async def bridge_client(path, reader, writer):
    try:
        up_r, up_w = await asyncio.open_unix_connection(path)
    except OSError:
        writer.close()
        return
    await splice(reader, writer, up_r, up_w)


async def serve_bridge(path, port):
    server = await asyncio.start_server(
        lambda r, w: bridge_client(path, r, w), "127.0.0.1", port
    )
    async with server:
        await server.serve_forever()


# ----------------------------------------------------------------- check


def tcp_answers(port):
    with socket.socket() as s:
        s.settimeout(2)
        return s.connect_ex(("127.0.0.1", port)) == 0


async def connect_status(path, target):
    reader, writer = await asyncio.open_unix_connection(path)
    try:
        writer.write(f"CONNECT {target} HTTP/1.1\r\nHost: {target}\r\n\r\n".encode("latin1"))
        await writer.drain()
        line = await asyncio.wait_for(reader.readline(), 20)
        return line.decode("latin1").split()[1] if line else "none"
    finally:
        writer.close()


async def check(path, host, ports):
    failures = []
    for port in (int(p) for p in ports.split(",") if p.strip()):
        if tcp_answers(port):
            failures.append(f"127.0.0.1:{port} ANSWERS inside the boundary: the namespace is the host's")
    for target in ("example.com:443", "127.0.0.1:5432", "127.0.0.1:443", "localhost:443", f"{host}:80"):
        status = await connect_status(path, target)
        if status != "403":
            failures.append(f"CONNECT {target} was not refused (got {status}): the relay admits more than the model")
    status = await connect_status(path, f"{host}:443")
    if status != "200":
        failures.append(f"CONNECT {host}:443 was not established (got {status}): the reviewer cannot reach a model")
    for f in failures:
        print(f"relay check: {f}", file=sys.stderr)
    return 1 if failures else 0


def main(argv):
    mode = argv[1] if len(argv) > 1 else ""
    if mode == "host" and len(argv) >= 4:
        asyncio.run(serve_host(argv[2], argv[3]))
    elif mode == "bridge" and len(argv) == 4:
        asyncio.run(serve_bridge(argv[2], int(argv[3])))
    elif mode == "check" and len(argv) == 5:
        sys.exit(asyncio.run(check(argv[2], argv[3], argv[4])))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
