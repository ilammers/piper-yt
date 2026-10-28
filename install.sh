#!/bin/sh
# Builds Piper-YT and installs it for the current user (no root needed).
set -e
cd "$(dirname "$0")"

PREFIX="${PREFIX:-$HOME/.local}"
APP_DIR="$PREFIX/share/piper-yt"
APP_ID=io.github.piper_yt.PiperYT

./gradlew installDist

rm -rf "$APP_DIR"
mkdir -p "$APP_DIR" "$PREFIX/bin" "$PREFIX/share/applications"
cp -r build/install/piper-yt/. "$APP_DIR/"
ln -sf "$APP_DIR/bin/piper-yt" "$PREFIX/bin/piper-yt"

sed "s|@BIN@|$APP_DIR/bin/piper-yt|" "data/$APP_ID.desktop" \
    > "$PREFIX/share/applications/$APP_ID.desktop"

echo "Installed. Run 'piper-yt' or launch Piper from your app menu."
command -v mpv >/dev/null || echo "Note: playback needs mpv: sudo dnf install mpv mpv-libs"
[ -e /usr/lib64/libmpv.so.2 ] || echo "Note: for playback inside the window: sudo dnf install mpv-libs"
