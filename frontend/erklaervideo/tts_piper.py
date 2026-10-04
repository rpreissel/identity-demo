# Synthesizes the narration with Piper. Usage: tts_piper.py <model.onnx> <length_scale> <jobs.json>
# jobs.json: [{"text": "...", "out": "file.wav"}, ...]; the model is loaded once for all jobs.
import json
import sys
import wave

from piper import PiperVoice, SynthesisConfig

voice = PiperVoice.load(sys.argv[1])
config = SynthesisConfig(length_scale=float(sys.argv[2]), noise_scale=0.6, noise_w_scale=0.8)
for job in json.load(open(sys.argv[3])):
    with wave.open(job["out"], "wb") as out:
        voice.synthesize_wav(job["text"], out, syn_config=config)
