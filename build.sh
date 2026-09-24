#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM_ZIP="${1:-}"
BUILD_TOOLS_ZIP="${2:-}"

if [[ -z "$PLATFORM_ZIP" || -z "$BUILD_TOOLS_ZIP" ]]; then
  echo "Usage: $0 PLATFORM_API36_ZIP BUILD_TOOLS_API36_ZIP" >&2
  exit 2
fi
if [[ ! -f "$PLATFORM_ZIP" ]]; then
  echo "Platform ZIP not found: $PLATFORM_ZIP" >&2
  exit 2
fi
if [[ ! -f "$BUILD_TOOLS_ZIP" ]]; then
  echo "Build-tools ZIP not found: $BUILD_TOOLS_ZIP" >&2
  exit 2
fi

for command in unzip zip javac java keytool base64 sha256sum; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Required command is missing: $command" >&2
    exit 2
  fi
done

SDK="$ROOT/.sdk"
PLATFORMS="$SDK/platforms"
BUILD_TOOLS="$SDK/build-tools"
OUT="$ROOT/out"
DIST="$ROOT/dist"
APP="$ROOT/app/src/main"
SIGNING="$ROOT/.signing"

bash "$ROOT/tools/materialize-assets.sh"

rm -rf "$SDK" "$OUT"
mkdir -p "$PLATFORMS" "$BUILD_TOOLS" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$DIST" "$SIGNING"
unzip -q "$PLATFORM_ZIP" -d "$PLATFORMS"
unzip -q "$BUILD_TOOLS_ZIP" -d "$BUILD_TOOLS"

ANDROID_JAR="$(find "$PLATFORMS" -type f -name android.jar -print -quit)"
AAPT2="$(find "$BUILD_TOOLS" -type f -name aapt2 -print -quit)"
D8="$(find "$BUILD_TOOLS" -type f -name d8 -print -quit)"
ZIPALIGN="$(find "$BUILD_TOOLS" -type f -name zipalign -print -quit)"
APKSIGNER="$(find "$BUILD_TOOLS" -type f -name apksigner -print -quit)"

for file in "$ANDROID_JAR" "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER"; do
  if [[ -z "$file" || ! -e "$file" ]]; then
    echo "The Android SDK ZIP archives are missing a required Android build component." >&2
    exit 2
  fi
done
chmod +x "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER"

"$AAPT2" compile --dir "$APP/res" -o "$OUT/compiled-res.zip"
"$AAPT2" link   -o "$OUT/resources.apk"   -I "$ANDROID_JAR"   --manifest "$APP/AndroidManifest.xml"   --java "$OUT/gen"   --min-sdk-version 26   --target-sdk-version 36   "$OUT/compiled-res.zip"

mapfile -t JAVA_FILES < <(find "$APP/java" "$OUT/gen" -type f -name '*.java' -print)
javac   -encoding UTF-8   -Xlint:-options   -source 8   -target 8   -classpath "$ANDROID_JAR"   -d "$OUT/classes"   "${JAVA_FILES[@]}"

mapfile -t CLASS_FILES < <(find "$OUT/classes" -type f -name '*.class' -print)
"$D8"   --min-api 26   --lib "$ANDROID_JAR"   --output "$OUT/dex"   "${CLASS_FILES[@]}"

cp "$OUT/resources.apk" "$OUT/unsigned.apk"
(
  cd "$OUT/dex"
  zip -q -u "$OUT/unsigned.apk" classes.dex
)
"$ZIPALIGN" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KEYSTORE="${ROLLBACKER_KEYSTORE:-$SIGNING/rollbacker-local.jks}"
ALIAS="${ROLLBACKER_ALIAS:-rollbacker}"
STOREPASS="${ROLLBACKER_STOREPASS:-}"
KEYPASS="${ROLLBACKER_KEYPASS:-}"
PASSWORD_FILE="$SIGNING/password.txt"

if [[ -z "$STOREPASS" ]]; then
  if [[ -f "$PASSWORD_FILE" ]]; then
    STOREPASS="$(cat "$PASSWORD_FILE")"
  else
    if command -v openssl >/dev/null 2>&1; then
      STOREPASS="$(openssl rand -hex 24)"
    else
      STOREPASS="$(date +%s%N)-rollbacker-local-key"
    fi
    umask 077
    printf '%s' "$STOREPASS" > "$PASSWORD_FILE"
  fi
fi
if [[ -z "$KEYPASS" ]]; then
  KEYPASS="$STOREPASS"
fi

if [[ ! -f "$KEYSTORE" ]]; then
  keytool -genkeypair     -keystore "$KEYSTORE"     -storepass "$STOREPASS"     -keypass "$KEYPASS"     -alias "$ALIAS"     -keyalg RSA     -keysize 3072     -validity 10000     -dname "CN=Background Gremlin Group, OU=Roll Backer, O=Background Gremlin Group"     >/dev/null
fi

"$APKSIGNER" sign   --ks "$KEYSTORE"   --ks-key-alias "$ALIAS"   --ks-pass "pass:$STOREPASS"   --key-pass "pass:$KEYPASS"   --out "$DIST/RollBacker.apk"   "$OUT/aligned.apk"

"$APKSIGNER" verify --verbose "$DIST/RollBacker.apk"
printf '\nBuilt: %s\n' "$DIST/RollBacker.apk"
printf 'SHA-256: '
sha256sum "$DIST/RollBacker.apk" | awk '{print $1}'
