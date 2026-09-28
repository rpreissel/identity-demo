#!/usr/bin/env sh
# Records docs/media/demo.mp4: resets the compose stack to empty volumes, plays the demo tasks in a
# browser (e2e-video/demo.spec.ts, captions included) and cuts it (cut-demo-video.mjs): page loads out,
# the app tab as picture-in-picture.
# Needs podman compose, the Playwright browsers (npx playwright install chromium) and ffmpeg.
set -eu
cd "$(dirname "$0")"
ROOT=$(cd .. && pwd)
OUT="$ROOT/docs/media/demo.mp4"
RESULT=test-results-video/demo-Aufgaben-der-Demo-im-Browser

echo "Stack neu aufsetzen (leere Volumes) ..."
(cd "$ROOT" && podman compose down -v && podman compose up -d)
for _ in $(seq 1 60); do
  status=$(podman ps --format '{{.Names}} {{.Status}}')
  echo "$status" | grep -q "orchestrator.*(healthy)" && echo "$status" | grep -q "keycloak.*(healthy)" && break
  sleep 5
done
sleep 15

echo "Aufnahme ..."
rm -rf test-results-video
npx playwright test -c playwright.video.config.ts

echo "Schnitt ..."
mkdir -p "$(dirname "$OUT")"
node cut-demo-video.mjs "$RESULT" "$OUT"
