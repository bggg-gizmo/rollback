#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

decode_checked() {
  local source="$1"
  local destination="$2"
  local expected_sha256="$3"
  local tmp="${destination}.tmp"

  if [[ ! -f "$source" ]]; then
    echo "Missing binary asset payload: $source" >&2
    exit 2
  fi

  mkdir -p "$(dirname "$destination")"
  base64 --decode "$source" > "$tmp"

  local actual_sha256
  actual_sha256="$(sha256sum "$tmp" | awk '{print $1}')"
  if [[ "$actual_sha256" != "$expected_sha256" ]]; then
    rm -f "$tmp"
    echo "Binary asset checksum mismatch for $source" >&2
    echo "Expected: $expected_sha256" >&2
    echo "Actual:   $actual_sha256" >&2
    exit 2
  fi

  mv -f "$tmp" "$destination"
}

decode_checked   "$ROOT/binary-assets/branding/rollbacker-icon-source.jpg.b64"   "$ROOT/branding/rollbacker-icon-source.jpg"   "06d8d5fee7f9cdafe6ac297e41ec69101f76f23f1460284bb93db45a3f0d40c9"

decode_checked   "$ROOT/binary-assets/app/drawable/rollbacker_art.png.b64"   "$ROOT/app/src/main/res/drawable/rollbacker_art.png"   "efe8cae52cfd282c208781ab84779fb009f5ae30878752a50d6c6531dad9c557"

echo "Materialized verified Roll Backer branding assets."
