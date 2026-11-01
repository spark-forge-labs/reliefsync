#!/bin/bash
# Builds ReliefSync-YC-pitch.mp4 (≈2:00): new scenes (demoyc.html) + clips of the original film + voiceover + score.
# Needs: ffmpeg, node+playwright, python (numpy scipy soundfile kokoro-onnx) – see vo.py. Usage: bash build_yc.sh [workdir]
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"; V="$HERE/.."; W="${1:-/tmp/ycbuild}"; mkdir -p "$W"
SRC="$V/ReliefSync-YC-demo-90s.mp4"; FPS=30; XF=0.3
python3 - <<PY > "$W/plan.txt"
import json; s=json.load(open("$HERE/sched.json"))
for g in s["segs"]: print(g["name"], g["kind"], g["dur"], g["start"])
PY
# ---- 1. new scenes → segments
for n in A B N3 N4 N5; do
  if [ ! -f "$W/$n.mp4" ]; then
    rm -rf "$W/f_$n"; node "$HERE/render_scenes.js" "$HERE/sched.json" "$W/f_$n" $n 0 2 & node "$HERE/render_scenes.js" "$HERE/sched.json" "$W/f_$n" $n 1 2 & wait
    ffmpeg -v error -y -framerate $FPS -i "$W/f_$n/%05d.jpg" -vf "scale=1920:1080,format=yuv420p" -c:v libx264 -preset veryfast -crf 17 "$W/$n.mp4"
  fi
done
# ---- 2. clips from the original film (video track only): name src_start src_len speed
clip(){ ffmpeg -v error -y -ss $2 -t $3 -i "$SRC" -an -vf "setpts=PTS/$4,fps=$FPS,scale=1920:1080,format=yuv420p" -t $5 -c:v libx264 -preset veryfast -crf 17 "$W/$1.mp4"; }
python3 - <<PY > "$W/dur.txt"
import json; s={g["name"]:g["dur"] for g in json.load(open("$HERE/sched.json"))["segs"]}; print(*[s[k] for k in ("C1","C2","C3","C4","C5")])
PY
read D1 D2 D3 D4 D5 < "$W/dur.txt"; P=$(python3 -c "print($XF+0.05)")
clip C1 6.4  $(python3 -c "print($D1+$P)") 1.0  $(python3 -c "print($D1+$P)")
clip C2 22.5 $(python3 -c "print($D2+$P)") 1.0  $(python3 -c "print($D2+$P)")
clip C3 35.0 $(python3 -c "print(($D3+$P)*1.5)") 1.5 $(python3 -c "print($D3+$P)")
clip C4 50.3 $(python3 -c "print(($D4+$P)*1.25)") 1.25 $(python3 -c "print($D4+$P)")
clip C5 74.6 $(python3 -c "print($D5+$P)") 1.0  $(python3 -c "print($D5+$P)")
# ---- 3. cross-fade chain
python3 - <<PY > "$W/chain.sh"
import json; s=json.load(open("$HERE/sched.json"))["segs"]; XF=$XF
ins=" ".join(f"-i $W/{g['name']}.mp4" for g in s)
f=[]; off=0; prev="[0:v]"
for i in range(1,len(s)):
    off+=s[i-1]["dur"]; out=f"[v{i}]"
    f.append(f"{prev}[{i}:v]xfade=transition=fade:duration={XF}:offset={off-XF/2+0:.3f}{out}"); prev=out
print(f"ffmpeg -v error -y {ins} -filter_complex \"{';'.join(f)}\" -map \"{prev}\" -c:v libx264 -preset medium -crf 17 -pix_fmt yuv420p $W/video.mp4")
PY
bash "$W/chain.sh"
# ---- 4. audio: voiceover on the schedule, score, sidechain duck, loudness
python3 "$HERE/mix_audio.py" "$HERE/sched.json" "$V/vo_wav" "$W"
ffmpeg -v error -y -i "$W/video.mp4" -i "$W/mix.wav" -map 0:v -map 1:a -c:v copy -c:a aac -b:a 192k -shortest -movflags +faststart "$V/ReliefSync-YC-pitch.mp4"
echo "done → $V/ReliefSync-YC-pitch.mp4"
