import { execFileSync, spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, rmSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { createInterface } from 'node:readline'
import { fileURLToPath } from 'node:url'
// @ts-expect-error plain ES module without types, shared with the explainer video
import { spoken } from '../erklaervideo/spoken.mjs'

/**
 * The voice of the demo video: Piper with the explainer video's voice (frontend/erklaervideo,
 * ./setup.sh once). Each text is synthesized once and kept in out/narration; the recorder holds
 * the screen as long as the sentence lasts, and cut-demo-video.mjs lays the files under the video.
 */

const VOICE_DIR = join(dirname(fileURLToPath(import.meta.url)), '../erklaervideo')
const OUT = join(VOICE_DIR, 'out')
const CACHE = join(OUT, 'narration')
const PYTHON = join(OUT, 'piper-venv/bin/python')
const MODEL = join(OUT, 'voices/de_DE-thorsten-high.onnx')
const LENGTH_SCALE = '1.05'

export type Narration = { wav: string; seconds: number }

let piper: ChildProcessWithoutNullStreams | null = null
let answers: AsyncIterator<string> | null = null

function server() {
  if (!piper) {
    if (!existsSync(PYTHON) || !existsSync(MODEL)) throw new Error(`no voice: run ${join(VOICE_DIR, 'setup.sh')} first`)
    mkdirSync(CACHE, { recursive: true })
    piper = spawn(PYTHON, [join(VOICE_DIR, 'tts_piper.py'), MODEL, LENGTH_SCALE, '--serve'])
    answers = createInterface({ input: piper.stdout })[Symbol.asyncIterator]()
  }
  return { piper, answers: answers! }
}

/** The narration of [text] as a file and its length; synthesized on first use. */
export async function narration(text: string): Promise<Narration> {
  const voiced = spoken(text) as string
  const wav = join(CACHE, `${createHash('sha1').update(LENGTH_SCALE + voiced).digest('hex').slice(0, 16)}.wav`)
  if (!existsSync(wav)) {
    const { piper, answers } = server()
    const raw = wav.replace(/\.wav$/, '.raw.wav')
    piper.stdin.write(JSON.stringify({ text: voiced, out: raw }) + '\n')
    await answers.next()
    // Without the silence Piper puts around a sentence, the hold time is the sentence itself.
    const trim = 'silenceremove=start_periods=1:start_threshold=-50dB,areverse,silenceremove=start_periods=1:start_threshold=-50dB,areverse'
    execFileSync('ffmpeg', ['-v', 'error', '-y', '-i', raw, '-af', trim, '-ar', '48000', '-ac', '1', wav])
    rmSync(raw)
  }
  const seconds = Number(execFileSync('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', wav], { encoding: 'utf8' }))
  return { wav, seconds }
}

export function stopNarrator() {
  piper?.stdin.end()
  piper = null
  answers = null
}
