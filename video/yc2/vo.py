import json, soundfile as sf
from kokoro_onnx import Kokoro
LINES = [
 ("L1","It's the worst night of the storm. Rosa's mother is on oxygen. The battery says ten percent. Rosa calls nine-one-one... and waits."),
 ("L2","In Hurricane Harvey, Houston's nine-one-one took seventy-five thousand calls in three days. A normal day? Eight thousand. The line is the bottleneck."),
 ("L3","But help was already there. After Superstorm Sandy, two in three people said their neighbors helped them recover. Help is blocks away. Nothing connects it."),
 ("L4","So Rosa opens ReliefSync, and just talks. In seconds, an A.I. agent understands the emergency, rates it life-threatening, finds her street, and sees the flood cutting off the main road."),
 ("L5","Now, the marketplace. The dispatcher broadcasts a request. Every nearby volunteer has an agent that answers, with a price, an arrival time, and a track record."),
 ("L6","Maya's four-by-four is four minutes away. Lena, a nurse, volunteers for free, but she's twenty-two minutes out. Diego's boat is too slow. Sam's battery is low."),
 ("L7","Seven factors, scored live. For a life-threatening call, there's no haggling. Maya wins, in about a second."),
 ("L8","For routine needs, the agents negotiate. Price, quality, track record, inside a relief-fund cap. Offer. Counter. Deal."),
 ("L9","Six seconds after the S.O.S., Maya's phone buzzes. One tap. The route runs around the flood, on real streets."),
 ("L10","Nine minutes later, the power is back on. And Rosa's data is erased."),
 ("L11","Not a bulletin board. An autonomous dispatcher, that turns neighbors into first responders."),
 ("L12","ReliefSync. Every call for help, answered in seconds."),
]
k = Kokoro("/tmp/kk/kokoro-v1.0.onnx", "/tmp/kk/voices-v1.0.bin")
out = {}
for key, text in LINES:
    a, sr = k.create(text, voice="af_heart", speed=1.08, lang="en-us")
    sf.write(f"vo/{key}.wav", a, sr); out[key] = round(len(a)/sr, 2); print(key, out[key], flush=True)
json.dump({"sr": sr, "dur": out, "lines": dict(LINES)}, open("vo/meta.json","w"), indent=1)
print("total", sum(out.values()))
