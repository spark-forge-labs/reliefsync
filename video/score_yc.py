"""Original score for the ReliefSync YC pitch film (synthesised from scratch – no samples, no licensing).
Sections follow video/yc/sched.json (seconds):
  A hook / B insight  : low drone + heartbeat, sparse piano           (0 – 20.4)
  story + agents      : bass, arps from the SOS, hats                 (20.4 – 50.1)
  marketplace         : full groove, riser into the impact at 50.06   (50.1 – 81.2)
  dispatch + resolve  : hero melody, warm                              (81.2 – 97.2)
  why / anthem        : lifted progression                             (97.2 – 112)
  close               : resolve chord + ring-out                       (112 – end)
Usage: python score_yc.py sched.json out.wav
"""
import json
import sys

import numpy as np
from scipy.io import wavfile
from scipy.signal import butter, lfilter

sched = json.load(open(sys.argv[1])); out = sys.argv[2]
S = {s["name"]: s for s in sched["segs"]}
T_STORY, T_AGENTS, T_MKT, T_DISP, T_WHY, T_CLOSE = S["C1"]["start"], S["C3"]["start"] - 6, S["N3"]["start"], S["C4"]["start"], S["N4"]["start"], S["N5"]["start"]
DUR = sched["total"] + 2.5
SR = 44100; N = int(SR * DUR); L = np.zeros(N); R = np.zeros(N)
BPM = 100; BEAT = 60 / BPM; BAR = BEAT * 4
rng = np.random.default_rng(11)
midi = lambda m: 440.0 * 2 ** ((m - 69) / 12)
env = lambda x, pts: np.interp(x, *zip(*pts))


def add(sig, start, gain=1.0, pan=0.0):
    i = int(start * SR); j = min(N, i + len(sig))
    if j <= i: return
    s = sig[: j - i] * gain
    L[i:j] += s * np.sqrt(0.5 * (1 - pan)) * 1.414; R[i:j] += s * np.sqrt(0.5 * (1 + pan)) * 1.414


def lp(x, fc, o=2): b, a = butter(o, fc / (SR / 2)); return lfilter(b, a, x)
def hp(x, fc, o=2): b, a = butter(o, fc / (SR / 2), btype="high"); return lfilter(b, a, x)


AM, F, C, G = [57, 60, 64], [53, 57, 60], [48, 55, 60, 64], [55, 59, 62]
MINOR, LIFT, MAJOR = [AM, F, C, G], [F, G, AM, C], [C, G, AM, F]
def chord_at(t):
    bar = int(t // BAR)
    if t < T_MKT - 6: return MINOR[bar % 4]
    if t < T_MKT: return LIFT[bar % 4]
    if t < T_WHY: return MAJOR[bar % 4]
    return LIFT[bar % 4]


def pad_note(f, dur, bright=1.0):
    n = int(dur * SR); tt = np.arange(n) / SR; out = np.zeros(n)
    for det in (-0.12, 0.0, 0.11):
        ff = f * 2 ** (det / 12)
        for h in range(1, 9): out += np.sin(2 * np.pi * ff * h * tt + rng.random() * 6.28) / h * (0.85 ** (h - 1)) * (bright if h > 2 else 1)
    return out * np.minimum(1, tt / 0.9) * np.minimum(1, (dur - tt) / 0.9) / 9


pad_g = lambda x: env(x, [(0, 0), (2, .4), (T_STORY, .5), (T_MKT, .7), (T_DISP, .8), (T_WHY, .9), (T_CLOSE, .9), (DUR - 1, 0)])
t = 0.0
while t < DUR:
    ch = chord_at(t); dur = min(BAR + 0.9, DUR - t + 0.5)
    for k, m in enumerate(ch):
        sig = lp(pad_note(midi(m), dur, 0.7 if t < T_MKT else 1.0), 900 if t < T_STORY else (1400 if t < T_MKT else 2600))
        add(sig, t, gain=0.16 * pad_g(t), pan=-0.5 + k * 0.4)
    t += BAR


def bass_note(f, dur):
    n = int(dur * SR); x = np.arange(n) / SR
    return (np.sin(2 * np.pi * f * x) + 0.25 * np.sin(4 * np.pi * f * x)) * np.minimum(1, x / 0.02) * np.exp(-x * 1.2) * np.minimum(1, (dur - x) / 0.05)
t = 6.0
while t < T_CLOSE + 4:
    step = BEAT * 2 if t < T_STORY + 6 else BEAT / 2
    g = env(t, [(6, .25), (T_STORY, .35), (T_AGENTS, .5), (T_MKT, .7), (T_DISP, .75), (T_CLOSE, .6), (T_CLOSE + 4, .2)])
    add(bass_note(midi(chord_at(t)[0] - 12), step * 0.95), t, gain=0.33 * g); t += step


def pluck(f, dur=1.2, bright=1.0):
    n = int(dur * SR); x = np.arange(n) / SR
    return sum(np.sin(2 * np.pi * f * h * x) * (0.6 ** (h - 1)) * np.exp(-x * (2.5 + h * 1.4)) for h in range(1, 6)) * np.minimum(1, x / 0.004) * bright


# sparse melancholy piano over the problem statement
for st, m in [(1.0, 76), (2.4, 74), (3.8, 72), (6.4, 69), (9.0, 72), (10.4, 71), (12.6, 69), (14.8, 76), (16.2, 74), (17.6, 72), (19.0, 69)]:
    add(pluck(midi(m), 2.2), st, gain=0.22, pan=0.15); add(pluck(midi(m - 12), 2.2) * .5, st, gain=0.15, pan=-0.15)
# 16th arps from the SOS on
t = T_STORY + 7.4; i = 0
while t < T_CLOSE:
    ch = chord_at(t); notes = [m + 12 for m in ch] + [ch[0] + 24]
    g = env(t, [(T_STORY + 7, .02), (T_AGENTS, .13), (T_MKT, .2), (T_DISP, .17), (T_WHY, .21), (T_CLOSE, 0)])
    add(pluck(midi(notes[i % len(notes)]), 0.5), t, gain=g, pan=-0.35 if i % 2 else 0.35); t += BEAT / 4; i += 1
# hero melody from the dispatch
hero = [72, 74, 76, 79, 77, 76, 74, 72, 76, 79, 81, 79, 76, 77, 79, 84]
for k, m in enumerate(hero):
    st = T_DISP + 0.2 + k * 1.2
    if st < T_CLOSE + 1:
        add(pluck(midi(m), 2.0), st, gain=0.2); add(pluck(midi(m + 12), 1.4, 0.6), st + 0.01, gain=0.07, pan=0.3)


def kick():
    n = int(0.35 * SR); x = np.arange(n) / SR
    return np.sin(2 * np.pi * np.cumsum(45 + 95 * np.exp(-x * 28)) / SR) * np.exp(-x * 9)
def hat(): n = int(0.06 * SR); return hp(rng.standard_normal(n), 7000) * np.exp(-np.arange(n) / SR * 70)
def snare(): n = int(0.25 * SR); x = np.arange(n) / SR; return (hp(rng.standard_normal(n), 1500) * 0.7 + np.sin(2 * np.pi * 190 * x) * 0.4) * np.exp(-x * 18)
K, H, SN = kick(), hat(), snare()
t = 2.0
while t < T_STORY + 4: add(K, t, gain=0.33); add(K, t + 0.28, gain=0.2); t += 1.25     # heartbeat
t = T_AGENTS - 6; b = 0
while t < T_CLOSE:
    full = T_MKT <= t < T_WHY + 8 or T_DISP <= t
    add(K, t, gain=0.5 if full else 0.36)
    if full and b % 2 == 1: add(SN, t, gain=0.28)
    if t > T_AGENTS: add(H, t + BEAT / 2, gain=0.16 if full else 0.11, pan=0.3)
    t += BEAT; b += 1
# riser into the marketplace impact, boom on every act change
n = int(5 * SR); x = np.arange(n) / SR
add(lp(hp(rng.standard_normal(n), 800) * (x / 5) ** 2, 6000), T_MKT - 5, gain=0.25)
n = int(3 * SR); x = np.arange(n) / SR
boom = np.sin(2 * np.pi * (30 + 60 * np.exp(-x * 6)) * x) * np.exp(-x * 1.6) + lp(rng.standard_normal(n), 400) * np.exp(-x * 4) * 0.5
for tb, gb in ((T_MKT, .6), (T_DISP, .35), (T_WHY, .4), (T_CLOSE, .55)): add(boom * (gb / .6), tb, gain=0.6)
# closing chord (C major add9) with long ring-out
for m in (48, 55, 60, 64, 67, 74): add(lp(pad_note(midi(m), 8.0, 1.0), 3000), T_CLOSE, gain=0.2, pan=(m % 5 - 2) / 5)
add(pluck(midi(84), 4.0), T_CLOSE + 0.1, gain=0.25)

peak = max(np.abs(L).max(), np.abs(R).max()) or 1
st = np.stack([L, R], 1) / peak * 0.9
wavfile.write(out, SR, (st * 32767).astype(np.int16))
print("score", round(DUR, 1), "s")
