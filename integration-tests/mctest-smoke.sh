#!/usr/bin/env bash
set -euo pipefail

MCTEST_BIN="${MCTEST_BIN:-mctest}"
cleanup() { "$MCTEST_BIN" down --purge >/dev/null 2>&1 || true; }
trap cleanup EXIT

mvn clean package
"$MCTEST_BIN" up 26.2 --plugin lunachat-paper/target/LunaChat.jar --fresh --timeout 180s
"$MCTEST_BIN" status --json
"$MCTEST_BIN" exec paper-1 plugins
"$MCTEST_BIN" exec paper-1 version
if "$MCTEST_BIN" logs paper-1 --tail 300 | grep -E "LunaChat.*(failed|SEVERE)|UnsupportedClassVersionError"; then
  echo "LunaChat startup failure found" >&2
  exit 1
fi
