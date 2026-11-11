#!/usr/bin/env python3
"""Decode Java output independently; assert the AA protobuf field semantics."""
from pathlib import Path
import struct
import subprocess
import sys


def fields(data):
    pos = 0

    def varint():
        nonlocal pos
        value = 0
        for shift in range(0, 70, 7):
            b = data[pos]
            pos += 1
            value |= (b & 127) << shift
            if not b & 128:
                return value
        raise AssertionError('invalid varint')

    result = {}
    while pos < len(data):
        key = varint()
        if key & 7 == 0:
            value = varint()
        else:
            assert key & 7 == 2
            size = varint()
            value = data[pos:pos+size]
            pos += size
        assert key >> 3 not in result
        result[key >> 3] = value
    return result


classpath = sys.argv[1]
for timestamp in (0, 123456789, 0x7fffffffffffffff):
    for steps in (-16, -1, 1, 16):
        raw = subprocess.run(['java', '-Xverify:all', '-cp', classpath, 'WheelWireDump', str(timestamp), str(steps)], capture_output=True, text=True, check=True)
        packet = bytes.fromhex(raw.stdout.strip())
        assert packet[:2] == b'\x80\x01'
        report = fields(packet[2:])
        assert set(report) == {1, 6} and report[1] == timestamp
        relative = fields(report[6])
        assert set(relative) == {1}
        event = fields(relative[1])
        assert event == {1: 65536, 2: steps & ((1 << 64)-1)}
print('PASS independent decoding: timestamp, display-neutral payload, rotary key and signed int32 delta')
for timestamp, steps in ((-1, 1), (0, 0), (0, 17), (0, -17), (0, -2147483648)):
    raw = subprocess.run(['java', '-cp', classpath, 'WheelWireDump', str(timestamp), str(steps)], capture_output=True)
    assert raw.returncode != 0
print('PASS invalid input cannot produce a wire message')
for path in Path(classpath).rglob('*.class'):
    data = path.read_bytes()
    assert struct.unpack('>H', data[6:8])[0] == 48, path
    assert b'java/lang/StringBuilder' not in data, path
print('PASS Java1.4 compatibility for implementation and fixture classes')
