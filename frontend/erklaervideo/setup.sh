#!/usr/bin/env sh
# Installs the narration voice for the explainer video into out/: Piper TTS in a Python venv (via uv)
# and the German voice "thorsten (high)". Needs uv and curl; about 120 MB download.
set -eu
cd "$(dirname "$0")"
mkdir -p out/voices
[ -x out/piper-venv/bin/python ] || uv venv -q --python 3.12 out/piper-venv
VIRTUAL_ENV="$PWD/out/piper-venv" uv pip install -q piper-tts
BASE=https://huggingface.co/rhasspy/piper-voices/resolve/main/de/de_DE/thorsten/high
for f in de_DE-thorsten-high.onnx de_DE-thorsten-high.onnx.json; do
  [ -f "out/voices/$f" ] || curl -sSLf -o "out/voices/$f" "$BASE/$f"
done
