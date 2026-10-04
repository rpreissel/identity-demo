// Builds docs/media/erklaervideo.mp4: narration per beat (Piper) -> timeline -> frames (Playwright) -> one MP4 per
// scene -> the whole video with a subtitle track. Run ./setup.sh once first.
// Usage: node build.mjs [--preview] [--only s03,s07] [--rate 1.05]
//   --preview  one still per scene (out/preview-*.png) instead of the video
//   --only     build just these scenes (out/scenes/), no final video
//   --rate     Piper length_scale, > 1 speaks slower
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, writeFileSync, rmSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { join } from 'node:path'
import { chromium } from 'playwright'
import { SCENES, CHAPTERS } from './scenes.mjs'
import { spoken } from './spoken.mjs'

const DIR = new URL('.', import.meta.url).pathname
const OUT = join(DIR, 'out'); const TTS = join(OUT, 'tts'); const FR = join(OUT, 'frames'); const SC = join(OUT, 'scenes')
const MEDIA = join(DIR, '../../docs/media'), DEMO = join(MEDIA, 'demo.mp4'), VIDEO = join(MEDIA, 'erklaervideo.mp4')
const args = process.argv.slice(2)
const PREVIEW = args.includes('--preview')
const ONLY = args.includes('--only') ? args[args.indexOf('--only') + 1].split(',') : null
const RATE = args.includes('--rate') ? args[args.indexOf('--rate') + 1] : '1.05'
const FPS = 30, LEAD = 0.6, GAP = 0.4, TAIL = 1.0, ANIM = 0.6
for (const d of [OUT, TTS, FR, SC]) mkdirSync(d, { recursive: true })

const sh = (cmd, a) => execFileSync(cmd, a, { stdio: ['ignore', 'pipe', 'pipe'] }).toString()
const dur = (f) => parseFloat(sh('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', f]))

// 1) Narration (Piper, voice thorsten) and timeline: each beat starts after the previous one plus GAP
const PY = join(OUT, 'piper-venv/bin/python'), MODEL = join(OUT, 'voices/de_DE-thorsten-high.onnx')
const scenes = SCENES.filter((s) => !ONLY || ONLY.some((o) => s.id.startsWith(o)))
const jobs = []
for (const s of scenes) s.beats.forEach((b, i) => {
  const text = spoken(b.s ?? b.t)
  const h = createHash('sha1').update('piper' + RATE + text).digest('hex').slice(0, 10)
  b.wav = join(TTS, `${s.id}-${i}-${h}.wav`)
  if (!existsSync(b.wav)) jobs.push({ text, out: b.wav.replace('.wav', '.raw.wav') })
})
if (jobs.length) {
  writeFileSync(join(OUT, 'jobs.json'), JSON.stringify(jobs))
  sh(PY, [join(DIR, 'tts_piper.py'), MODEL, RATE, join(OUT, 'jobs.json')])
  for (const j of jobs) {
    sh('ffmpeg', ['-v', 'error', '-y', '-i', j.out, '-af', 'silenceremove=start_periods=1:start_threshold=-50dB,areverse,silenceremove=start_periods=1:start_threshold=-50dB,areverse', '-ar', '48000', '-ac', '1', j.out.replace('.raw.wav', '.wav')])
    rmSync(j.out)
  }
}
for (const s of scenes) {
  let t = LEAD
  s.timing = s.beats.map((b) => {
    const d = dur(b.wav)
    const r = { start: t, dur: d, wav: b.wav, text: b.t }
    t += d + GAP
    return r
  })
  s.duration = t - GAP + TAIL
}
const total = scenes.reduce((a, s) => a + s.duration, 0)
console.log(`total ${total.toFixed(1)} s`)
for (const s of scenes) console.log(`  ${s.id.padEnd(24)} ${s.duration.toFixed(1)} s`)

// 2) One HTML page per scene; setTime(t) puts every [data-b] element where it is at time t
const css = readFileSync(join(DIR, 'style.css'), 'utf8')
const page = (s) => `<!doctype html><html lang="de"><head><meta charset="utf-8"><style>${css}</style></head><body>
<svg width="0" height="0" style="position:absolute"><defs><marker id="arr" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10 z" fill="#8aa4ff"/></marker></defs></svg>
<div class="stage">
${s.plain ? '' : `<header><div class="kicker">${s.kicker}</div><h1>${s.title}</h1></header>`}
${s.html}
${s.plain ? '' : `<footer><div class="brand">identity-demo</div><div class="chapters">${CHAPTERS.map((c, i) => `<span class="${i === s.chapter ? 'on' : i < s.chapter ? 'done' : ''}">${c}</span>`).join('')}</div></footer>`}
</div>
<script>
const BEATS = ${JSON.stringify(s.timing.map((b) => b.start))}, ANIM = ${ANIM};
const els = [...document.querySelectorAll('[data-b]')].map(el => {
  const start = BEATS[+el.dataset.b] + parseFloat(el.dataset.d || 0)
  const len = el.getTotalLength ? el.getTotalLength() : 0
  if (len) { el.style.strokeDasharray = len; }
  return { el, start, len }
})
const hls = [...document.querySelectorAll('[data-hl]')].map(el => ({ el, start: BEATS[+el.dataset.hl] + parseFloat(el.dataset.d || 0) }))
const head = document.querySelector('header')
window.lastChange = Math.max(ANIM, ...els.map(e => e.start + ANIM), ...hls.map(h => h.start + 0.05))
window.setTime = (t) => {
  if (head) { const p = Math.min(1, t / 0.5); head.style.opacity = p; }
  for (const { el, start, len } of els) {
    const p = Math.max(0, Math.min(1, (t - start) / ANIM)), e = 1 - Math.pow(1 - p, 3)
    if (len) { el.style.strokeDashoffset = len * (1 - e); el.style.opacity = p > 0 ? 1 : 0; if (!el.classList.contains('sim')) el.style.markerEnd = p >= 1 ? 'url(#arr)' : 'none'; }
    else { el.style.opacity = e; el.style.transform = 'translateY(' + ((1 - e) * 22).toFixed(2) + 'px)'; }
  }
  for (const { el, start } of hls) el.classList.toggle('on', t >= start)
}
</script></body></html>`

// 3) Frames; once nothing moves any more, ffmpeg holds the last frame
const browser = await chromium.launch()
async function renderScene(s) {
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 })
  const p = await ctx.newPage()
  await p.setContent(page(s), { waitUntil: 'load' })
  if (PREVIEW) {
    await p.evaluate((t) => window.setTime(t), s.duration)
    await p.screenshot({ path: join(OUT, `preview-${s.id}.png`) })
  } else {
    const dir = join(FR, s.id); rmSync(dir, { recursive: true, force: true }); mkdirSync(dir)
    const last = Math.min(s.duration, await p.evaluate(() => window.lastChange) + 0.1)
    const n = Math.ceil(last * FPS)
    for (let i = 0; i <= n; i++) {
      await p.evaluate((t) => window.setTime(t), i / FPS)
      await p.screenshot({ path: join(dir, `${String(i).padStart(5, '0')}.jpg`), type: 'jpeg', quality: 94 })
    }
  }
  await ctx.close()
}
const queue = [...scenes]
await Promise.all(Array.from({ length: 5 }, async () => { while (queue.length) await renderScene(queue.shift()) }))
await browser.close()
if (PREVIEW) process.exit(0)

// 4) One video per scene with its narration; clips from demo.mp4 are overlaid into their box
const srt = []; let offset = 0
const ts = (x) => { const ms = Math.round(x * 1000); const h = Math.floor(ms / 3600000), m = Math.floor(ms / 60000) % 60, s = Math.floor(ms / 1000) % 60; return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')},${String(ms % 1000).padStart(3, '0')}` }
for (const s of scenes) {
  const D = s.duration.toFixed(3)
  const ain = s.timing.flatMap((b) => ['-i', b.wav])
  const amix = s.timing.map((b, i) => `[${i}:a]adelay=${Math.round(b.start * 1000)}|${Math.round(b.start * 1000)}[a${i}]`).join(';') +
    ';' + s.timing.map((_, i) => `[a${i}]`).join('') + `amix=inputs=${s.timing.length}:normalize=0,apad,atrim=0:${D}[aout]`
  const wav = join(SC, `${s.id}.wav`)
  sh('ffmpeg', ['-v', 'error', '-y', ...ain, '-filter_complex', amix, '-map', '[aout]', '-ar', '48000', wav])

  const vin = ['-framerate', String(FPS), '-i', join(FR, s.id, '%05d.jpg')]
  let filter = `[0:v]tpad=stop_mode=clone:stop_duration=${D},trim=duration=${D},setpts=PTS-STARTPTS[bg]`
  const inputs = [...vin]
  if (s.clip) {
    const [x, y, w, h] = s.clip.box, src = s.clip.to - s.clip.from, at = 0.4
    const speed = Math.max(0.6, Math.min(2.0, src / (s.duration - at - 1)))
    inputs.push('-ss', String(s.clip.from), '-t', String(src), '-i', DEMO)
    filter += `;[1:v]crop=${s.clip.crop ?? "1600:1040:0:0"},scale=${w}:${h}:flags=lanczos,fps=${FPS},setpts=(PTS-STARTPTS)/${speed.toFixed(3)}+${at}/TB[c];[bg][c]overlay=${x}:${y}:eof_action=repeat[ov];[ov]trim=duration=${D}[v0]`
  } else filter += ';[bg]null[v0]'
  filter += `;[v0]fade=in:st=0:d=0.35,fade=out:st=${(s.duration - 0.4).toFixed(3)}:d=0.4,format=yuv420p[v]`
  inputs.push('-i', wav)
  const ai = s.clip ? 2 : 1
  sh('ffmpeg', ['-v', 'error', '-y', ...inputs, '-filter_complex', filter, '-map', '[v]', '-map', `${ai}:a`,
    '-c:v', 'libx264', '-preset', 'medium', '-crf', '18', '-r', String(FPS), '-c:a', 'aac', '-b:a', '192k', '-ar', '48000', '-t', D, join(SC, `${s.id}.mp4`)])
  for (const b of s.timing) srt.push(`${srt.length + 1}\n${ts(offset + b.start)} --> ${ts(offset + b.start + b.dur + 0.2)}\n${b.text}\n`)
  offset += s.duration
  console.log(`  done: ${s.id}`)
}
if (ONLY) process.exit(0)

// 5) Join, normalize loudness, add the subtitle track
writeFileSync(join(OUT, 'list.txt'), scenes.map((s) => `file '${join(SC, s.id + '.mp4')}'`).join('\n'))
writeFileSync(join(OUT, 'erklaervideo.srt'), srt.join('\n'))
sh('ffmpeg', ['-v', 'error', '-y', '-f', 'concat', '-safe', '0', '-i', join(OUT, 'list.txt'), '-c', 'copy', join(OUT, 'joined.mp4')])
sh('ffmpeg', ['-v', 'error', '-y', '-i', join(OUT, 'joined.mp4'), '-i', join(OUT, 'erklaervideo.srt'),
  '-map', '0:v', '-map', '0:a', '-map', '1:s', '-c:v', 'copy', '-af', 'loudnorm=I=-16:TP=-1.5:LRA=11', '-c:a', 'aac', '-b:a', '192k', '-ar', '48000',
  '-c:s', 'mov_text', '-metadata:s:s:0', 'language=ger', '-metadata:s:a:0', 'language=ger', '-movflags', '+faststart', VIDEO])
console.log('video:', VIDEO)
