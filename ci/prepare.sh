#!/usr/bin/env bash
# Generates binary assets (fonts, launcher icons, TV banner, signing keystore)
# so the repository only needs to hold text files.
set -euo pipefail
cd "$(dirname "$0")/.."
RES=app/src/main/res
FONT_DIR=/usr/share/fonts/opentype/inter

if [ ! -f "$FONT_DIR/Inter-ExtraBold.otf" ] && command -v apt-get >/dev/null; then
  sudo apt-get update -qq && sudo apt-get install -y -qq fonts-inter || true
fi
if [ ! -f "$FONT_DIR/Inter-ExtraBold.otf" ]; then
  curl -fsSL -o /tmp/inter.zip https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip
  rm -rf /tmp/inter && mkdir -p /tmp/inter && unzip -q -o /tmp/inter.zip -d /tmp/inter
  FONT_DIR=$(dirname "$(find /tmp/inter -name 'Inter-ExtraBold.otf' | head -1)")
fi
echo "Using Inter fonts from $FONT_DIR"

mkdir -p "$RES/font"
cp "$FONT_DIR/Inter-Regular.otf"  "$RES/font/inter_regular.otf"
cp "$FONT_DIR/Inter-Medium.otf"   "$RES/font/inter_medium.otf"
cp "$FONT_DIR/Inter-SemiBold.otf" "$RES/font/inter_semibold.otf"
cp "$FONT_DIR/Inter-Bold.otf"     "$RES/font/inter_bold.otf"

python3 -c "import PIL" 2>/dev/null || python3 -m pip install --quiet pillow
FONT_DIR="$FONT_DIR" python3 ci/logo.py "$RES"

if [ ! -f app/dhakaflix.keystore ]; then
  base64 -d ci/keystore.b64 > app/dhakaflix.keystore
fi
echo "Assets ready."
