#!/usr/bin/env bash
# Downloads what the app bundles and puts it where Gradle picks it up:
#   - Lightpanda's official aarch64 Linux build (checked against a pinned SHA-256)
#   - the glibc loader and libraries from Termux's glibc packages (they are built to work on Android)
set -euo pipefail

VERSION="${LIGHTPANDA_VERSION:-1.0.3}"
PINNED_VERSION="1.0.3"
PINNED_SHA256="20a5348bf3b6f71e41088223b188552ab550c1b9c9a9cbd8f099e6f7f3d78549"
GPKG="https://service.termux-pacman.dev/gpkg/aarch64"

root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$root/tmp"
libs="$root/app/src/main/jniLibs/arm64-v8a"
assets="$root/app/src/main/assets/glibc"
rm -rf "$tmp" "$libs" "$assets"
mkdir -p "$tmp" "$libs" "$assets"

echo "==> Lightpanda $VERSION"
curl -fL --retry 3 -o "$tmp/lightpanda" \
  "https://github.com/lightpanda-io/browser/releases/download/$VERSION/lightpanda-aarch64-linux"
if [ "$VERSION" = "$PINNED_VERSION" ]; then
  echo "$PINNED_SHA256  $tmp/lightpanda" | sha256sum -c -
else
  sha256sum "$tmp/lightpanda"
fi

echo "==> glibc from Termux's repository"
curl -fL --retry 3 -o "$tmp/gpkg.db" "$GPKG/gpkg.db"
mkdir -p "$tmp/db"
tar -xf "$tmp/gpkg.db" -C "$tmp/db"
entry="$(find "$tmp/db" -maxdepth 1 -type d -name 'glibc-[0-9]*' -printf '%f\n' | sort -V | tail -1)"
[ -n "$entry" ] || { echo "::error::no glibc package in the Termux glibc repository index"; find "$tmp/db" -maxdepth 1 | head -20; exit 1; }
file="$(awk '/^%FILENAME%$/{getline; print; exit}' "$tmp/db/$entry/desc")"
echo "    package: $entry  file: $file"
curl -fL --retry 3 -o "$tmp/glibc.pkg" "$GPKG/$file"
mkdir -p "$tmp/pkg"
tar -xf "$tmp/glibc.pkg" -C "$tmp/pkg"
lib="$(find "$tmp/pkg" -type d -path '*/glibc/lib' | head -1)"
[ -n "$lib" ] || { echo "::error::glibc/lib not found inside $file"; find "$tmp/pkg" -maxdepth 6 -type d | head -30; exit 1; }

for f in ld-linux-aarch64.so.1 libc.so.6 libm.so.6; do
  [ -e "$lib/$f" ] || { echo "::error::$f missing from the glibc package"; find "$lib" -maxdepth 1 | head -40; exit 1; }
done

glibc_version="$(strings "$lib/libc.so.6" | grep -o 'release version [0-9]*\.[0-9]*' | head -1 | awk '{print $3}')"
echo "    glibc version: ${glibc_version:-unknown}"
if [ -z "$glibc_version" ] || [ "$(printf '%s\n2.38\n' "$glibc_version" | sort -V | head -1)" != "2.38" ]; then
  echo "::error::glibc ${glibc_version:-unknown} is older than the 2.38 Lightpanda needs"
  exit 1
fi

cp -L "$lib/ld-linux-aarch64.so.1" "$libs/libglibcld.so"
cp "$tmp/lightpanda" "$libs/liblightpanda.so"
chmod 0755 "$libs/libglibcld.so" "$libs/liblightpanda.so"
for f in ld-linux-aarch64.so.1 libc.so.6 libm.so.6; do cp -L "$lib/$f" "$assets/$f"; done
ls -l "$libs" "$assets"
