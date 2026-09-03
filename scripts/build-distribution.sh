#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${LUNACHAT_VERSION:-4.0.4-SNAPSHOT}"
OUTPUT="${ROOT_DIR}/LunaChat-${VERSION}-artifacts.zip"
STAGE="$(mktemp -d "${TMPDIR:-/tmp}/lunachat-distribution.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT

cd "$ROOT_DIR"
mvn package -DskipTests

cp lunachat-paper/target/LunaChat.jar "$STAGE/"
cp lunachat-velocity/target/LunaChat-Velocity.jar "$STAGE/"
cp lunachat-api/target/lunachat-api-*.jar "$STAGE/"
cp lunachat-api/target/lunachat-api-*-javadoc.jar "$STAGE/"
cp lunachat-api-testkit/target/lunachat-api-testkit-*.jar "$STAGE/lunachat-api-testkit.jar"
cp LICENSE NOTICE "$STAGE/"

rm -f "$OUTPUT"
(cd "$STAGE" && zip -q -r "$OUTPUT" .)
printf 'Wrote %s\n' "$OUTPUT"
