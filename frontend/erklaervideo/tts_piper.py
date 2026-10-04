# Synthesizes narration with Piper; the model is loaded once.
#   tts_piper.py <model.onnx> <length_scale> <jobs.json>   jobs.json: [{"text": "...", "out": "file.wav"}, ...]
#   tts_piper.py <model.onnx> <length_scale> --serve       one job as JSON per stdin line, "ok" per line back
import json
import sys
import wave

from piper import PiperVoice, SynthesisConfig

voice = PiperVoice.load(sys.argv[1])
config = SynthesisConfig(length_scale=float(sys.argv[2]), noise_scale=0.6, noise_w_scale=0.8)


def synthesize(job):
    with wave.open(job["out"], "wb") as out:
        voice.synthesize_wav(job["text"], out, syn_config=config)


if sys.argv[3] == "--serve":
    for line in sys.stdin:
        synthesize(json.loads(line))
        print("ok", flush=True)
else:
    for job in json.load(open(sys.argv[3])):
        synthesize(job)
