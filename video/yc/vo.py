import json, numpy as np, soundfile as sf
from kokoro_onnx import Kokoro
LINES = [
 ("L1","In twenty seventeen, Hurricane Harvey flooded Houston. Nine-one-one took seventy-five thousand calls in about three days. A normal day? Eight to nine thousand."),
 ("L2","But after Superstorm Sandy, two in three people in hit communities said their neighbors helped them recover. Help is blocks away. Nothing connects it."),
 ("L3","So when the oxygen battery hits ten percent, Rosa doesn't wait on hold. She opens ReliefSync, and just talks."),
 ("L4","Instantly, a team of AI agents goes to work, and shows its reasoning, live. It understands the emergency, rates it life-threatening, pinpoints her address, and finds a power station nearby."),
 ("L5","No black box. Every thought, tool call, and decision streams live to the people involved."),
 ("L6","Now the part that makes it a marketplace. Every volunteer has an agent that knows their rating, on-time record, vehicle, battery, and price. The dispatcher sends a signal, and the agents answer with bids."),
 ("L7","For life-threatening calls, there's no haggling. The best qualified, fastest responder wins, in about a second. For routine needs, agents negotiate price and quality, inside a relief-fund cap."),
 ("L8","Seven factors, scored live. The winner is notified. The runners-up stay on standby."),
 ("L9","Six seconds after the S.O.S., Maya's phone buzzes. No dispatcher. One tap. And everyone sees the same live route, around the flood."),
 ("L10","Nine minutes later, power is restored. And Rosa's personal data? Erased."),
 ("L11","ReliefSync is not a bulletin board. It's an autonomous dispatcher. Transparent. Built for broken networks. Safe by design. Disaster response runs on neighbors. We make them findable, in seconds."),
 ("L12","ReliefSync. Every call for help, answered in seconds. We're applying to Y Combinator."),
]
k = Kokoro("/tmp/kk/kokoro-v1.0.onnx", "/tmp/kk/voices-v1.0.bin")
out = {}
for key, text in LINES:
    a, sr = k.create(text, voice="af_heart", speed=1.1, lang="en-us")
    sf.write(f"vo/{key}.wav", a, sr); out[key] = round(len(a)/sr, 2); print(key, out[key], flush=True)
json.dump({"sr": sr, "dur": out, "lines": dict(LINES)}, open("vo/meta.json","w"), indent=1)
print("total", sum(out.values()))
