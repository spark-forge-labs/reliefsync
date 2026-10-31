"""Original cinematic score for the 90 s ReliefSync film (synthesised; no samples, no licensing).
Story-synced sections (seconds):
 0-6 storm | 6-15 apartment tension | 15-22 problem | 22-30 SOS build | 30-50 agents pulse
 50-59 Maya accepts (riser) | 59-74 tracking (full groove) | 74-81 arrival (warm) | 81-86.5 anthem | 86.5-90 end chord
"""
import numpy as np
from scipy.io import wavfile
from scipy.signal import lfilter, butter

SR = 44100
DUR = 90.0
N = int(SR * DUR)
t_all = np.arange(N) / SR
L = np.zeros(N); R = np.zeros(N)
BPM = 100; BEAT = 60 / BPM; BAR = BEAT * 4
rng = np.random.default_rng(7)

def midi(m): return 440.0 * 2 ** ((m - 69) / 12)
def env(x, pts):  # piecewise-linear automation over absolute time
    xs, ys = zip(*pts); return np.interp(x, xs, ys)
def add(sig, start, gain=1.0, pan=0.0):
    i = int(start * SR); j = min(N, i + len(sig))
    if j <= i: return
    s = sig[: j - i] * gain
    L[i:j] += s * np.sqrt(0.5 * (1 - pan)) * 1.414
    R[i:j] += s * np.sqrt(0.5 * (1 + pan)) * 1.414
def lowpass(x, fc, order=2):
    b, a = butter(order, fc / (SR / 2)); return lfilter(b, a, x)
def highpass(x, fc, order=2):
    b, a = butter(order, fc / (SR / 2), btype="high"); return lfilter(b, a, x)

# chords (A minor → C major lift). Each bar = BAR seconds.
AM, F, C, G, EM, DM = [57, 60, 64], [53, 57, 60], [48, 55, 60, 64], [55, 59, 62], [52, 55, 59], [50, 53, 57]
MINOR_PROG = [AM, F, C, G]
LIFT_PROG = [F, G, AM, C]   # heroic
MAJOR_PROG = [C, G, AM, F]
def chord_at(tt):
    bar = int(tt // BAR)
    if tt < 50: return MINOR_PROG[bar % 4]
    if tt < 59: return LIFT_PROG[bar % 4]
    return MAJOR_PROG[bar % 4]

# ---------------------------------------------------------------- pad (warm detuned supersaw-ish, additive)
def pad_note(f, dur, bright=1.0):
    n = int(dur * SR); tt = np.arange(n) / SR
    out = np.zeros(n)
    for det in (-0.12, 0.0, 0.11):
        ff = f * 2 ** (det / 12)
        for h in range(1, 9):
            out += np.sin(2 * np.pi * ff * h * tt + rng.random() * 6.28) / h * (0.85 ** (h - 1)) * (bright if h > 2 else 1)
    a = np.minimum(1, tt / 0.9) * np.minimum(1, (dur - tt) / 0.9)
    return out * a / 9
pad_gain = lambda x: env(x, [(0, .0), (2, .35), (6, .45), (15, .5), (22, .55), (30, .45), (50, .55), (59, .7), (74, .75), (81, .85), (86.5, .9), (90, 0)])
tt = 0.0
while tt < DUR:
    ch = chord_at(tt)
    dur = min(BAR + 0.9, DUR - tt + 0.5)
    for k, m in enumerate(ch):
        sig = pad_note(midi(m), dur, bright=0.7 if tt < 59 else 1.0)
        sig = lowpass(sig, 900 if tt < 30 else (1400 if tt < 59 else 2600))
        add(sig, tt, gain=0.16 * pad_gain(tt), pan=(-0.5 + k * 0.4))
    tt += BAR

# ---------------------------------------------------------------- sub bass
def bass_note(f, dur):
    n = int(dur * SR); x = np.arange(n) / SR
    s = np.sin(2 * np.pi * f * x) + 0.25 * np.sin(2 * np.pi * 2 * f * x)
    return s * np.minimum(1, x / 0.02) * np.exp(-x * 1.2) * np.minimum(1, (dur - x) / 0.05)
tt = 6.0
while tt < 88:
    root = chord_at(tt)[0] - 12
    step = BEAT * 2 if tt < 30 else BEAT / 2
    g = env(tt, [(6, .25), (22, .35), (30, .5), (50, .55), (59, .7), (81, .75), (88, .5)])
    add(bass_note(midi(root), step * 0.95), tt, gain=0.33 * g)
    tt += step

# ---------------------------------------------------------------- piano / pluck motif + arpeggios
def pluck(f, dur=1.2, bright=1.0):
    n = int(dur * SR); x = np.arange(n) / SR
    s = sum(np.sin(2 * np.pi * f * h * x) * (0.6 ** (h - 1)) * np.exp(-x * (2.5 + h * 1.4)) for h in range(1, 6))
    s *= np.minimum(1, x / 0.004)
    return s * bright
# sparse piano 6-30 (melancholy)
melody = [(6.0, 76), (7.2, 74), (8.4, 72), (10.8, 69), (13.2, 72), (14.4, 71), (16.8, 69), (19.2, 76), (20.4, 74), (21.6, 72), (24.0, 69), (26.4, 71), (28.8, 72)]
for st, m in melody:
    add(pluck(midi(m), 2.2), st, gain=0.22, pan=0.15)
    add(pluck(midi(m - 12), 2.2) * .5, st, gain=0.15, pan=-0.15)
# 16th-note arps from SOS (22) on
tt = 22.0; i = 0
while tt < 86.5:
    ch = chord_at(tt)
    notes = [m + 12 for m in ch] + [ch[0] + 24]
    m = notes[i % len(notes)]
    g = env(tt, [(22, .02), (30, .12), (50, .16), (59, .2), (74, .17), (81, .22), (86.5, .0)])
    add(pluck(midi(m), 0.5, 1.0), tt, gain=g, pan=(-0.35 if i % 2 else 0.35))
    tt += BEAT / 4; i += 1
# hero melody 59-86 (uplifting)
hero = [(59.0, 72), (60.2, 74), (61.4, 76), (63.8, 79), (66.2, 77), (67.4, 76), (68.6, 74), (71.0, 72),
        (73.4, 76), (74.6, 79), (75.8, 81), (78.2, 79), (80.6, 76), (81.8, 77), (83.0, 79), (84.2, 84)]
for st, m in hero:
    add(pluck(midi(m), 2.0, 1.0), st, gain=0.2, pan=0.0)
    add(pluck(midi(m + 12), 1.4, 0.6), st + 0.01, gain=0.07, pan=0.3)

# ---------------------------------------------------------------- drums
def kick():
    n = int(0.35 * SR); x = np.arange(n) / SR
    f = 45 + 95 * np.exp(-x * 28)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-x * 9)
def hat():
    n = int(0.06 * SR); return highpass(rng.standard_normal(n), 7000) * np.exp(-np.arange(n) / SR * 70)
def snare():
    n = int(0.25 * SR); x = np.arange(n) / SR
    return (highpass(rng.standard_normal(n), 1500) * 0.7 + np.sin(2 * np.pi * 190 * x) * 0.4) * np.exp(-x * 18)
K, H, S = kick(), hat(), snare()
# heartbeat in the apartment (6-22)
tt = 6.0
while tt < 22:
    add(K, tt, gain=0.35); add(K, tt + 0.28, gain=0.22); tt += 1.25
# driving pulse from agents (30) on
tt = 30.0; b = 0
while tt < 86.5:
    if tt < 50 or (59 <= tt):
        add(K, tt, gain=0.5 if tt >= 59 else 0.38)
        if tt >= 59 and b % 2 == 1: add(S, tt, gain=0.28)
        if tt >= 40: add(H, tt + BEAT / 2, gain=0.12 if tt < 59 else 0.16, pan=0.3)
    tt += BEAT; b += 1
# riser + snare roll into the dispatch accept (54-59), impact on 59
n = int(5 * SR); x = np.arange(n) / SR
riser = highpass(rng.standard_normal(n), 800) * (x / 5) ** 2
add(lowpass(riser, 6000), 54.0, gain=0.25)
tt = 56.0
while tt < 59:
    add(S, tt, gain=0.1 + 0.25 * (tt - 56) / 3); tt += max(0.06, 0.3 - (tt - 56) * 0.08)
n = int(3 * SR); x = np.arange(n) / SR
boom = np.sin(2 * np.pi * (30 + 60 * np.exp(-x * 6)) * x) * np.exp(-x * 1.6) + lowpass(rng.standard_normal(n), 400) * np.exp(-x * 4) * 0.5
add(boom, 59.0, gain=0.6)
add(boom * 0.6, 86.5, gain=0.5)

# ---------------------------------------------------------------- rain / thunder bed (0-22)
n = int(24 * SR); x = np.arange(n) / SR
rain = lowpass(highpass(rng.standard_normal(n), 300), 5000) * env(x, [(0, 0), (1, .5), (14, .4), (22, .08), (24, 0)])
add(rain, 0, gain=0.18, pan=-0.2); add(lowpass(rng.standard_normal(n), 3000) * env(x, [(0, 0), (1, .4), (22, 0)]), 0, gain=0.1, pan=0.3)
for st in (1.7, 4.6):
    n = int(2.5 * SR); x = np.arange(n) / SR
    add(lowpass(rng.standard_normal(n), 220) * np.exp(-x * 1.8) * np.minimum(1, x / 0.05), st, gain=0.55)

# ---------------------------------------------------------------- final chord ring (86.5-90)
for k, m in enumerate([48, 55, 60, 64, 67, 72]):
    add(pad_note(midi(m), 3.6, 1.0), 86.4, gain=0.12, pan=-0.6 + k * 0.24)
    add(pluck(midi(m + 12), 3.0), 86.5, gain=0.07, pan=-0.6 + k * 0.24)

# ---------------------------------------------------------------- master: simple reverb-ish tail, glue, fades, limiter
def comb(x, d, fb):
    y = x.copy(); dl = int(d * SR)
    for k in range(dl, len(y), dl):
        y[k:k + dl] += y[k - dl:k][: len(y[k:k + dl])] * fb
    return y
for ch in (L, R):
    wet = lowpass(comb(ch * 0.25, 0.113, 0.45) + comb(ch * 0.25, 0.157, 0.4), 3500)
    ch += wet * 0.35
fade = env(t_all, [(0, 0), (0.4, 1), (88.2, 1), (90, 0)])
mix = np.stack([L * fade, R * fade], axis=1)
mix /= np.max(np.abs(mix)) + 1e-9
mix = np.tanh(mix * 1.6) / np.tanh(1.6) * 0.89
wavfile.write("score.wav", SR, (mix * 32767).astype(np.int16))
print("ok", mix.shape[0] / SR, "s")
