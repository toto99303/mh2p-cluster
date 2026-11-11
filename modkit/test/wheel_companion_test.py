#!/usr/bin/env python3
"""Exercise the owned native adapter with synthetic router and loopback client."""
import ctypes as c
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import tempfile
import time


ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "audi/a6-c8-p2711/wheel-zoom/native"


def exercise():
    assert "MH2P_WHEEL_CHILD_PRELOAD" not in os.environ
    assert os.environ["LD_PRELOAD"] == os.environ["WHEEL_EXPECT_CHILD_PRELOAD"]
    print("PASS companion restores baseline preload for GAL child processes")
    lib = c.CDLL(None)
    router = c.create_string_buffer(2048)
    init = getattr(lib, "_ZN13MessageRouter4initEP14ChannelManager")
    init.argtypes = [c.c_void_p, c.c_void_p]
    mapping = getattr(lib, "_ZN13MessageRouter12setupMappingEhh")
    mapping.argtypes = [c.c_void_p, c.c_uint8, c.c_uint8]
    route = getattr(lib, "_ZN13MessageRouter12routeMessageEhRK10shared_ptrI8IoBufferE")
    route.argtypes = [c.c_void_p, c.c_uint8, c.c_void_p]
    shutdown = getattr(lib, "_ZN13MessageRouter8shutdownEv")
    shutdown.argtypes = [c.c_void_p]
    lib.fixture_packet.restype = c.POINTER(c.c_uint8)

    class Buffer(c.Structure):
        _fields_ = [("data", c.c_void_p), ("capacity", c.c_uint32),
                    ("begin", c.c_uint32), ("end", c.c_uint32)]

    def incoming(channel, packet):
        data = c.create_string_buffer(packet)
        b = Buffer(c.addressof(data), len(packet), 0, len(packet))
        ref = (c.c_void_p * 2)(None, c.addressof(b))
        return route(router, channel, ref)

    def outgoing():
        return lib.fixture_channel(), bytes(lib.fixture_packet()[:lib.fixture_length()])

    assert init(router, router) == 1
    mapping(router, 21, 37)
    mapping(router, 14, 48)
    count = lib.fixture_count()
    assert incoming(4, bytes.fromhex("800208808004")) == 7
    assert lib.fixture_count() == count
    assert incoming(37, bytes.fromhex("800208808004")) == 0
    assert outgoing() == (37, bytes.fromhex("80030800"))
    assert incoming(48, bytes.fromhex("0000010203")) == 7
    print("PASS primary input forwards; secondary binding replies on negotiated channel37")

    peer = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    peer.bind(("127.0.0.1", 0))
    peer.connect(("127.0.0.1", 46335))
    peer.settimeout(1)
    request = 0
    client, cookie, epoch = 0x34127, 0, 0

    def exchange(op, generation=1, sequence=0, value=0, age=0, stale_epoch=None):
        nonlocal request, cookie, epoch
        request += 1
        packet = struct.pack("!4sBBH8I", b"MZ01", op, 0, 40, request, client,
                             cookie, epoch if stale_epoch is None else stale_epoch,
                             generation, sequence, value & 0xffffffff, age)
        peer.send(packet)
        status = peer.recv(80)
        assert len(status) == 48 and status[:8] == b"MZ01\x80\x00\x00\x30"
        values = struct.unpack("!9I", status[8:44])
        assert values[0] == request and values[1] == client
        cookie, epoch = values[2:4]
        return values, struct.unpack("!I", status[44:])[0]

    values, _ = exchange(1)
    assert values[6] == 31 and values[7] <= 2000
    exchange(3, value=1)
    count = lib.fixture_count()
    exchange(4, sequence=1, value=1)
    assert lib.fixture_count() == count  # Worker must never call foreign router.
    incoming(48, bytes.fromhex("0000010203"))
    assert lib.fixture_count() == count+1 and outgoing()[0] == 37
    packet = outgoing()[1]
    timestamp = shift = 0
    for byte in packet[3:]:
        timestamp |= (byte & 127) << shift
        if not byte & 128:
            break
        shift += 7
    assert 0 <= time.monotonic_ns()-timestamp < 1000000000
    assert packet[:2] == b"\x80\x01" and packet.endswith(bytes.fromhex("32080a06088080041001"))
    print("PASS UDP worker queues only; native callback submits rotary report on secondary input")

    exchange(4, sequence=1, value=1)
    incoming(48, bytes.fromhex("0000010203"))
    assert lib.fixture_count() == count+1
    exchange(4, sequence=2, value=-1)
    incoming(48, bytes.fromhex("0000010203"))
    assert lib.fixture_count() == count+1  # Native pacing remains enforced.
    time.sleep(.17)
    exchange(3, value=1)
    incoming(48, bytes.fromhex("0000010203"))
    assert lib.fixture_count() == count+2
    assert outgoing()[1].endswith(bytes.fromhex("32110a0f0880800410ffffffffffffffffff01"))
    print("PASS duplicate sequence rejected; native150ms pacing; negative int32 wire encoding")

    exchange(4, sequence=3, value=1)
    exchange(3, generation=2, value=0)
    time.sleep(.17)
    incoming(48, bytes.fromhex("0000010203"))
    assert lib.fixture_count() == count+2
    exchange(3, generation=3, value=1)
    exchange(4, generation=3, sequence=4, value=1, age=1000)
    incoming(48, bytes.fromhex("0000010203"))
    assert lib.fixture_count() == count+2
    print("PASS leaving presentation and expired intents cannot submit queued zoom")

    old_epoch = epoch
    shutdown(router)
    assert init(router, router) == 1
    mapping(router, 21, 9)
    mapping(router, 14, 19)
    incoming(9, bytes.fromhex("800208808004"))
    incoming(19, bytes.fromhex("0000010203"))
    count = lib.fixture_count()
    exchange(4, generation=3, sequence=5, value=1, stale_epoch=old_epoch)
    incoming(19, bytes.fromhex("0000010203"))
    assert lib.fixture_count() == count
    assert epoch != old_epoch
    print("PASS reconnect/remapping invalidates old connection epoch")
    if os.environ.get("WHEEL_CLIENT_CP"):
        subprocess.run(["java", "-Xverify:all", "-cp", os.environ["WHEEL_CLIENT_CP"],
                        "WheelClientProbe"], check=True, timeout=5,
                       env={k:v for k,v in os.environ.items() if k!="LD_PRELOAD"})
        assert lib.fixture_count() == count
        incoming(19, bytes.fromhex("0000010203"))
        assert lib.fixture_count() == count+1 and outgoing()[0] == 9
        print("PASS Java intent submits only after the native router callback")
    peer.close()


if __name__ == "__main__":
    if "--run" in sys.argv:
        exercise()
    else:
        with tempfile.TemporaryDirectory(prefix="mh2p-native-wheel-") as tmp:
            tmp = Path(tmp)
            helper, fake = tmp / "wheel.so", tmp / "fake.so"
            subprocess.run(["cc", "-std=c11", "-D_POSIX_C_SOURCE=200809L", "-DMH2P_HOST_TEST",
                            "-fPIC", "-shared", "-O2", "-Wall", "-Wextra", "-Werror", "-pthread",
                            str(NATIVE/"wheel_companion.c"), str(NATIVE/"aa_wheel_state.c"),
                            "-ldl", "-o", str(helper)], check=True)
            subprocess.run(["cc", "-fPIC", "-shared", "-Wall", "-Wextra", "-Werror",
                            str(ROOT/"modkit/test/wheel_fake_router.c"), "-o", str(fake)], check=True)
            env = dict(os.environ, LD_PRELOAD=str(helper)+":"+str(fake),
                       MH2P_WHEEL_CHILD_PRELOAD=str(fake), WHEEL_EXPECT_CHILD_PRELOAD=str(fake))
            if os.environ.get("ECJ_JAR"):
                classes = tmp/"classes"
                classes.mkdir()
                client = ROOT/"audi/a6-c8-p2711/wheel-zoom/src/de/audi/app/terminalmode/smartphone/androidauto2/nav/zoom/Mh2pWheelClient.java"
                subprocess.run(["java", "-jar", os.environ["ECJ_JAR"], "-1.4", "-source", "1.4",
                                "-target", "1.4", "-nowarn", "-d", str(classes), str(client),
                                str(ROOT/"modkit/test/wheel-fixtures/WheelClientProbe.java")], check=True)
                env["WHEEL_CLIENT_CP"] = str(classes)
            subprocess.run([sys.executable, __file__, "--run"], env=env, check=True, timeout=20)
