#!/usr/bin/env bash
# Prints the SHA-256 fingerprint of the release signing certificate — this is the value
# Knox Guard's console asks for when allow-listing org.gadgetgenie.my.native.
#
# Usage:
#   scripts/print-sha256.sh path/to/release.keystore key-alias
#
# If you only have the built, signed APK/AAB and not the keystore, pass that instead:
#   scripts/print-sha256.sh path/to/app-release.apk
set -euo pipefail

if [ "$#" -lt 1 ]; then
    echo "Usage: $0 <keystore-or-apk-path> [key-alias]" >&2
    exit 1
fi

INPUT="$1"

find_apksigner() {
    if command -v apksigner >/dev/null 2>&1; then
        command -v apksigner
        return 0
    fi
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -n "$sdk" ] && [ -d "$sdk/build-tools" ]; then
        find "$sdk/build-tools" -name apksigner -type f 2>/dev/null | sort -V | tail -1
    fi
}

if [[ "$INPUT" == *.apk || "$INPUT" == *.aab ]]; then
    APKSIGNER="$(find_apksigner)"
    if [ -z "$APKSIGNER" ]; then
        echo "apksigner not found. Set \$ANDROID_HOME, or add build-tools to PATH." >&2
        exit 1
    fi
    "$APKSIGNER" verify --print-certs "$INPUT" | grep -i "SHA-256"
else
    ALIAS="${2:-}"
    if [ -z "$ALIAS" ]; then
        echo "A key alias is required when inspecting a keystore." >&2
        exit 1
    fi
    keytool -list -v -keystore "$INPUT" -alias "$ALIAS" | grep -i "SHA256:"
fi
