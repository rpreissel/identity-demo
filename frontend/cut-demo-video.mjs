// Cuts the raw recording of e2e-video/demo.record.ts into docs/media/demo.mp4 (called by record-demo-video.sh).
// timing.json holds the spec's clock: when the first card appeared (calibration), when the app tab was shown and
// which stretches were hidden (page loads) and when which sentence was said. The first dark frame of the video is
// that first card, which aligns the spec's clock with the video's. Then: app tab as picture-in-picture, hidden
// stretches cut out, the narration laid under the video where it now falls.
import { execFileSync, spawnSync } from 'node:child_process'
import { readFileSync, renameSync, rmSync } from 'node:fs'

const [dir, out] = process.argv.slice(2)
if (!dir || !out) throw new Error('usage: node cut-demo-video.mjs <result dir> <output.mp4>')
const timing = JSON.parse(readFileSync(`${dir}/timing.json`, 'utf8'))
const main = `${dir}/video.webm`
const app = `${dir}/video-1.webm`

// Where the cards' dark background starts in the video; the one closest to the logged time is the first card.
// blackdetect reports on stderr.
const detect = spawnSync('ffmpeg', ['-v', 'info', '-i', main, '-vf', 'blackdetect=d=0.3:pix_th=0.12:pic_th=0.80', '-an', '-f', 'null', '-'], { encoding: 'utf8' })
const starts = [...detect.stderr.matchAll(/black_start:([0-9.]+)/g)].map((m) => Number(m[1]))
if (starts.length === 0) throw new Error('no dark frame found: the first card is missing from the video')
const first = starts.reduce((a, b) => (Math.abs(b - timing.calibration) < Math.abs(a - timing.calibration) ? b : a))
const delta = first - timing.calibration
console.log(`first card at ${first.toFixed(2)} s in the video, spec clock ${timing.calibration.toFixed(2)} s, offset ${delta.toFixed(2)} s`)

const duration = Number(execFileSync('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', main], { encoding: 'utf8' }))
const at = (t) => Math.max(0, t + delta)
// Generous edges: cutting a little too much loses a still frame, cutting too little shows a half-built page.
const EDGE_AFTER = 0.3
const hidden = timing.hidden.map((h) => [Math.max(0, at(h.from) - 0.15), h.to === null ? duration + 1 : at(h.to) + EDGE_AFTER])
const keep = `not(${hidden.map(([a, b]) => `between(t,${a.toFixed(3)},${b.toFixed(3)})`).join('+')})`

const appStart = at(timing.app.created)
const appFrom = at(timing.app.from)
const appTo = at(timing.app.to)
const filter = [
  '[0:v]fps=25[base]',
  '[1:v]fps=25,crop=500:940:45:105,scale=-1:640,drawbox=x=0:y=0:w=iw:h=ih:color=black@0.6:t=2[pip]',
  `[base][pip]overlay=W-w-30:110:eof_action=pass:enable='between(t,${appFrom.toFixed(3)},${appTo.toFixed(3)})'[both]`,
  `[both]select='${keep}',setpts=N/25/TB[out]`,
].join(';')

const silent = `${out}.video.mp4`
execFileSync('ffmpeg', ['-y', '-v', 'error', '-i', main, '-itsoffset', appStart.toFixed(3), '-i', app,
  '-filter_complex', filter, '-map', '[out]', '-c:v', 'libx264', '-preset', 'medium', '-crf', '20', '-pix_fmt', 'yuv420p',
  '-r', '25', silent], { stdio: 'inherit' })
const kept = Number(execFileSync('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', silent], { encoding: 'utf8' }))
console.log(`${hidden.length} stretches cut, ${kept.toFixed(1)} s of ${duration.toFixed(1)} s kept`)

// Where a moment of the raw video lands in the cut one: minus everything cut before it.
const cuts = hidden.map(([a, b]) => [a, Math.min(b, duration)]).sort((x, y) => x[0] - y[0])
  .reduce((merged, [a, b]) => {
    const last = merged.at(-1)
    if (last && a <= last[1]) last[1] = Math.max(last[1], b)
    else merged.push([a, b])
    return merged
  }, [])
const cutTime = (v) => v - cuts.reduce((sum, [a, b]) => sum + Math.max(0, Math.min(b, v) - a), 0)
const sentences = timing.narration.map((n) => {
  const v = at(n.at)
  // A sentence said right after a reveal starts within the edge: it then starts with the first frame shown.
  if (cuts.some(([a, b]) => v > a && v < b - EDGE_AFTER)) console.warn(`narration starts while hidden at ${v.toFixed(2)} s: ${n.wav}`)
  return { wav: n.wav, ms: Math.round(cutTime(v) * 1000) }
})
const voice = [
  ...sentences.map((n, i) => `[${i + 1}:a]adelay=${n.ms}|${n.ms}[s${i}]`),
  `${sentences.map((_, i) => `[s${i}]`).join('')}amix=inputs=${sentences.length}:normalize=0,apad,atrim=0:${kept.toFixed(3)},loudnorm=I=-16:TP=-1.5:LRA=11[voice]`,
].join(';')
execFileSync('ffmpeg', ['-y', '-v', 'error', '-i', silent, ...sentences.flatMap((n) => ['-i', n.wav]),
  '-filter_complex', voice, '-map', '0:v', '-map', '[voice]', '-c:v', 'copy', '-c:a', 'aac', '-b:a', '160k', '-ar', '48000',
  '-metadata:s:a:0', 'language=ger', '-movflags', '+faststart', `${out}.tmp.mp4`], { stdio: 'inherit' })
renameSync(`${out}.tmp.mp4`, out)
rmSync(silent)
console.log(`${sentences.length} sentences of narration: ${out}`)

