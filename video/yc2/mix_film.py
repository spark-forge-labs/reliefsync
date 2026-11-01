"""Place the voiceover lines on the schedule, synthesise the score, duck music under speech, normalise to -14 LUFS."""
import json, subprocess, sys, os
import numpy as np, soundfile as sf
from scipy.signal import butter, lfilter
sched_p, vo_dir, work = sys.argv[1:4]
sched = json.load(open(sched_p)); SR = 44100
tot = int((sched["total"] + 2.5) * SR); vo = np.zeros(tot)
for seg in sched["segs"]:
    for line, start in seg["lines"]:
        a, sr = sf.read(os.path.join(vo_dir, line + ".wav"))
        if a.ndim > 1: a = a.mean(1)
        if sr != SR:
            n = int(len(a) * SR / sr); a = np.interp(np.linspace(0, len(a) - 1, n), np.arange(len(a)), a)
        i = int(start * SR); vo[i:i + len(a)] += a[: max(0, tot - i)]
b, a_ = butter(2, 90 / (SR / 2), "high"); vo = lfilter(b, a_, vo)                      # trim rumble
subprocess.run([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "score_film.py"), sched_p, os.path.join(work, "score.wav")], check=True)
m, sr2 = sf.read(os.path.join(work, "score.wav")); m = m[:tot] if len(m) >= tot else np.pad(m, ((0, tot - len(m)), (0, 0)))
env = np.abs(vo); b, a_ = butter(2, 6 / (SR / 2)); env = lfilter(b, a_, env); env = np.clip(env / (np.percentile(env, 95) + 1e-9), 0, 1)
duck = 1 - 0.62 * env                                                                  # music dips ~8 dB under the voice
music = m * duck[:, None] * 0.55
vo = vo / (np.abs(vo).max() + 1e-9) * 0.9
mix = music + np.stack([vo, vo], 1)
sf.write(os.path.join(work, "pre.wav"), mix, SR)
subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", os.path.join(work, "pre.wav"), "-af", "loudnorm=I=-14:TP=-1.5:LRA=9", "-ar", "44100", os.path.join(work, "mix.wav")], check=True)
