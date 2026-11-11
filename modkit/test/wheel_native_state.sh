#!/usr/bin/env bash
# Host C fixtures, never loads a vehicle binary.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
NATIVE="$HERE/../../audi/a6-c8-p2711/wheel-zoom/native"
TEST_WORK="$(mktemp -d)"
trap 'rm -rf "$TEST_WORK"' EXIT
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror -fsanitize=address,undefined -g \
    -I "$NATIVE" "$NATIVE/aa_wheel_state.c" "$HERE/wheel_native_state_test.c" \
    -o "$TEST_WORK/state-test"
"$TEST_WORK/state-test"
