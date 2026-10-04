#!/usr/bin/env sh
# Records docs/media/demo.mp4: resets the compose stack to empty volumes, plays the demo tasks in a
# browser (e2e-video/demo.record.ts, captions and narration included) and cuts it (cut-demo-video.mjs):
# page loads out, the app tab as picture-in-picture, the narration under the video.
# Needs podman compose, the Playwright browsers (npx playwright install chromium), ffmpeg and uv (the voice).
set -eu
cd "$(dirname "$0")"
ROOT=$(cd .. && pwd)
OUT="$ROOT/docs/media/demo.mp4"
RESULT=test-results-video/demo.record.ts-Aufgaben-der-Demo-im-Browser

echo "Stimme bereitstellen ..."
./erklaervideo/setup.sh

echo "Stack neu aufsetzen (leere Volumes) ..."
(cd "$ROOT" && podman compose down -v)
# docker-compose reports the volumes removed even when podman refuses their names as ambiguous
# (another stack's "identity-demo-…-data" next to compose's "identity-demo_…-data"); prune by label.
podman volume prune -f --filter "label=com.docker.compose.project=$(basename "$ROOT")" > /dev/null
(cd "$ROOT" && podman compose up -d)
# Ready when the orchestrator serves pages: it answers "starting_up" until the Keycloak migrations ran.
# Polling the port, not "podman ps", so containers of other stacks cannot pass for this one.
for _ in $(seq 1 120); do
  body=$(curl -sf http://localhost:8080/ || true)
  [ -n "$body" ] && ! echo "$body" | grep -q starting_up && break
  sleep 5
done
sleep 5

echo "Aufnahme ..."
rm -rf test-results-video
npx playwright test -c playwright.video.config.ts

echo "Schnitt ..."
mkdir -p "$(dirname "$OUT")"
node cut-demo-video.mjs "$RESULT" "$OUT"
