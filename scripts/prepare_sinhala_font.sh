#!/usr/bin/env bash
# Fetch an exact, verified copy of Noto Sans Sinhala from Google Fonts.
# The files are included in built APKs, not committed as binaries.
set -Eeuo pipefail
cd "$(dirname "$0")/.."
FONT="app/src/main/res/font/noto_sans_sinhala.ttf"
LICENSE="app/src/main/assets/licenses/noto_sans_sinhala_ofl.txt"
mkdir -p "$(dirname "$FONT")" "$(dirname "$LICENSE")"
download_and_verify() {
    local dest="$1" url="$2" expected="$3"
    if [[ -f "$dest" ]] && [[ "$(git hash-object "$dest")" == "$expected" ]]; then
        echo "Verified existing $dest"
        return
    fi
    local temp
    temp="$(mktemp)"
    trap 'rm -f "$temp"' RETURN
    curl --fail --silent --show-error --location --retry 3 --retry-delay 2 "$url" -o "$temp"
    local actual
    actual="$(git hash-object "$temp")"
    if [[ "$actual" != "$expected" ]]; then
        echo "Font/license checksum mismatch for $dest" >&2
        echo "Expected: $expected, got: $actual" >&2
        exit 1
    fi
    mv "$temp" "$dest"
    echo "Verified $dest"
}
download_and_verify "$FONT" \
 "https://raw.githubusercontent.com/google/fonts/main/ofl/notosanssinhala/NotoSansSinhala%5Bwdth%2Cwght%5D.ttf" \
 "4e65c3d072ed4759f6682b008af4087781c056ba"
download_and_verify "$LICENSE" \
 "https://raw.githubusercontent.com/google/fonts/main/ofl/notosanssinhala/OFL.txt" \
 "4e06fdff46edef774eacff1b55518b8a0c2aa4ba"
