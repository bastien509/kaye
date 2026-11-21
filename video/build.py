"""Edits the Kaye demo video from the real screen recordings (build/raw) and the voice-over (build/voice).
Each scene: raw segments (some fast-forwarded, with an honest "real time" badge) on a branded 1920x1080 frame,
a caption bar on top, narration, then all scenes are joined and English subtitles are burned in."""
import json
import pathlib
import subprocess

HERE = pathlib.Path(__file__).parent
B = HERE / "build"
RAW, CLIPS = B / "raw", B / "clips"
CLIPS.mkdir(parents=True, exist_ok=True)
PHONE = pathlib.Path(r"C:\Users\admin\AppData\Local\Temp\claude\58bb8fca1af19e42\ad54195d-168e-43dd-a7aa-fdf4f5cbff33\scratchpad")
FONT = "C\\:/Windows/Fonts/segoeuib.ttf"
FONT_R = "C\\:/Windows/Fonts/segoeui.ttf"
BG = "0x0d0e11"
YELLOW = "0xF5C518"
W, H, TOP = 1920, 1080, 92          # canvas and caption bar height
VW, VH = 1842, 880                    # recorded page area scaled into the canvas
FPS = 30
timing = {s["id"]: s for s in json.loads((B / "timing.json").read_text(encoding="utf-8"))}


def run(args):
    r = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args], capture_output=True, text=True)
    if r.returncode:
        raise SystemExit(r.stderr[-2000:])


def dur(p):
    return float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(p)],
                                capture_output=True, text=True).stdout)


def esc(t):
    return t.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\u2019").replace("%", "\\%")


def chrome(caption):
    """Caption bar: step title left (yellow), project tag right."""
    return (f"drawtext=fontfile='{FONT}':text='{esc(caption)}':fontsize=34:fontcolor={YELLOW}:x=48:y=26,"
            f"drawtext=fontfile='{FONT_R}':text='Kaye  ·  #BuildHybridAI  ·  real recording':fontsize=24:fontcolor=0xa2a8b6:x=w-tw-48:y=33")


def badge(text):
    return (f"drawbox=x=(iw-1060)/2:y=(ih-110)/2:w=1060:h=110:color=0x000000@0.72:t=fill,"
            f"drawtext=fontfile='{FONT}':text='{esc(text)}':fontsize=34:fontcolor={YELLOW}:x=(w-tw)/2:y=(h-th)/2")


def segment(out, raw, a, b, caption, speed=1.0, note=None):
    vf = f"setpts=(PTS-STARTPTS)/{speed},scale={VW}:{VH},pad={W}:{H}:{(W - VW) // 2}:{TOP}:color={BG},{chrome(caption)}"
    if note:
        vf += "," + badge(note)
    run(["-ss", str(a), "-to", str(b), "-i", str(RAW / f"{raw}.mp4"), "-vf", vf, "-r", str(FPS), "-an",
         "-c:v", "libx264", "-preset", "medium", "-crf", "19", "-pix_fmt", "yuv420p", str(out)])


def still_pair(out, left, right, caption, seconds):
    """Two real phone screenshots side by side."""
    run(["-loop", "1", "-t", str(seconds), "-i", str(PHONE / left), "-loop", "1", "-t", str(seconds), "-i", str(PHONE / right),
         "-filter_complex",
         f"[0]scale=-2:860[a];[1]scale=-2:860[b];color=c={BG}:s={W}x{H}:d={seconds}[bg];"
         f"[bg][a]overlay=x=W/2-w-40:y={TOP}+20[t];[t][b]overlay=x=W/2+40:y={TOP}+20,{chrome(caption)},"
         f"drawtext=fontfile='{FONT_R}':text='Galaxy S24 Ultra · real screenshots of the Kaye Android app':fontsize=24:fontcolor=0xa2a8b6:x=(w-tw)/2:y=h-40",
         "-r", str(FPS), "-c:v", "libx264", "-preset", "medium", "-crf", "19", "-pix_fmt", "yuv420p", str(out)])


def end_card(out, seconds=4.0):
    lines = [("Kaye", 120, "white", 330), ("Your notebook, now it can count.", 46, YELLOW, 480),
             ("petiht.com/kaye   ·   github.com/bastien509/kaye", 34, "0xd7dbe4", 590),
             ("Firebase AI Logic · Gemini Nano · Firestore · Authentication · Remote Config · Analytics · App Check", 26, "0xa2a8b6", 680),
             ("#BuildHybridAI 2026", 28, "0xa2a8b6", 740)]
    vf = ",".join(f"drawtext=fontfile='{FONT}':text='{esc(t)}':fontsize={s}:fontcolor={c}:x=(w-tw)/2:y={y}" for t, s, c, y in lines)
    run(["-f", "lavfi", "-i", f"color=c={BG}:s={W}x{H}:d={seconds}", "-vf", vf + ",drawbox=x=(iw-420)/2:y=455:w=420:h=4:color=0xD21034:t=fill",
         "-r", str(FPS), "-c:v", "libx264", "-preset", "medium", "-crf", "19", "-pix_fmt", "yuv420p", str(out)])


def concat_video(parts, out):
    lst = out.with_suffix(".txt")
    lst.write_text("".join(f"file '{p.resolve().as_posix()}'\n" for p in parts), encoding="utf-8")
    run(["-f", "concat", "-safe", "0", "-i", str(lst), "-c", "copy", str(out)])


def scene(sid, parts, lead=0.35, tail=0.5):
    """Joins a scene's video parts, holds the last frame if the narration is longer, adds the voice."""
    silent = CLIPS / f"{sid}_v.mp4"
    concat_video(parts, silent)
    vlen = dur(silent)
    voice = B / "voice" / f"{sid}.mp3" if sid in timing else None
    alen = (timing[sid]["duration"] + lead + tail) if voice else vlen
    total = max(vlen, alen)
    out = CLIPS / f"{sid}.mp4"
    if voice:
        run(["-i", str(silent), "-i", str(voice), "-filter_complex",
             f"[0:v]tpad=stop_mode=clone:stop_duration={total - vlen + 0.05:.2f}[v];"
             f"[1:a]adelay={int(lead * 1000)}|{int(lead * 1000)},apad,atrim=0:{total:.2f},aresample=48000[a]",
             "-map", "[v]", "-map", "[a]", "-t", f"{total:.2f}", "-c:v", "libx264", "-preset", "medium", "-crf", "19",
             "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "160k", "-ac", "2", str(out)])
    else:
        run(["-i", str(silent), "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo", "-t", f"{total:.2f}",
             "-c:v", "copy", "-c:a", "aac", "-b:a", "160k", str(out)])
    return out, total, lead


def p(name):
    return CLIPS / name


plan = []  # (scene id, file, start offset of narration)

# 1–2: the problem + the idea, over the landing page.
segment(p("s1a.mp4"), "r1_landing", 0.3, 16.8, "The problem: a market vendor's notebook")
plan.append(scene("s1_hook", [p("s1a.mp4")]))
segment(p("s2a.mp4"), "r1_landing", 16.8, 32.0, "The idea: hybrid AI with Firebase AI Logic")
plan.append(scene("s2_idea", [p("s2a.mp4")]))
# 3: no-account start (Firebase Auth + Firestore sample notebook).
segment(p("s3a.mp4"), "r3_start", 1.0, 18.0, "Start in seconds: Firebase Auth (anonymous) + Firestore", speed=2.0)
segment(p("s3b.mp4"), "r3_start", 18.0, 22.0, "Start in seconds: Firebase Auth (anonymous) + Firestore")
plan.append(scene("s3_start", [p("s3a.mp4"), p("s3b.mp4")]))
# 4: the key moment — a page read by Gemini Nano in Chrome, ONLY_ON_DEVICE.
cap4 = "Read on the device: Gemini Nano in Chrome · ONLY_ON_DEVICE"
segment(p("s4a.mp4"), "r4_snap", 0.5, 9.0, cap4)
segment(p("s4b.mp4"), "r4_snap", 9.0, 82.8, cap4, speed=24.6, note="» ×25   Gemini Nano reading on this laptop's CPU · 78 s real time")
segment(p("s4c.mp4"), "r4_snap", 82.8, 97.0, cap4)
plan.append(scene("s4_snap", [p("s4a.mp4"), p("s4b.mp4"), p("s4c.mp4")]))
# 5: the math decides; consent before the cloud.
cap5 = "The math decides: a wrong total, and Kaye asks before using the cloud"
segment(p("s5a.mp4"), "r5_mistake", 1.6, 7.6, cap5)
segment(p("s5b.mp4"), "r5_mistake", 7.6, 37.0, cap5, speed=14.7, note="» ×15   read on-device by Gemini Nano · 30 s real time")
segment(p("s5c.mp4"), "r5_mistake", 37.0, 48.0, cap5)
plan.append(scene("s5_mistake", [p("s5a.mp4"), p("s5b.mp4"), p("s5c.mp4")]))
# 6: Kreyòl note → cloud with names hidden.
segment(p("s6a.mp4"), "r6_note", 0.5, 26.0, "Kreyòl note: names replaced on the device before the cloud", speed=1.3)
plan.append(scene("s6_note", [p("s6a.mp4")]))
# 7: who owes me + polite reminder (written on-device).
segment(p("s7a.mp4"), "r7_owed", 2.0, 18.0, "Who owes me: a polite reminder, written on the device", speed=2.0)
plan.append(scene("s7_owed", [p("s7a.mp4")]))
# 8: month record from totals only (on-device).
cap8 = "Month record: written from totals only"
segment(p("s8a.mp4"), "r8_month", 11.6, 21.0, cap8, speed=2.0)
segment(p("s8b.mp4"), "r8_month", 21.0, 57.6, cap8, speed=18.3, note="» ×18   Gemini Nano writing on-device · 37 s real time")
segment(p("s8c.mp4"), "r8_month", 57.6, 63.2, cap8)
plan.append(scene("s8_month", [p("s8a.mp4"), p("s8b.mp4"), p("s8c.mp4")]))
# 9: Android (real phone screenshots).
half = round((timing["s9_android"]["duration"] + 0.85) / 2, 2)
still_pair(p("s9a.mp4"), "k8.png", "k11.png", "Android: same engine, asks before the cloud", half)
still_pair(p("s9b.mp4"), "k10.png", "k14.png", "Android: page read, total verified, Firestore offline", half)
plan.append(scene("s9_android", [p("s9a.mp4"), p("s9b.mp4")]))
# 10: Firebase products + end card.
segment(p("s10a.mp4"), "r10_stack", 0.3, 15.5, "Six Firebase products, no backend of our own")
plan.append(scene("s10_close", [p("s10a.mp4")]))
end_card(p("end_v.mp4"))
run(["-i", str(p("end_v.mp4")), "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo", "-t", "4", "-c:v", "copy", "-c:a", "aac", "-b:a", "160k", str(p("end.mp4"))])

# Join scenes (re-encode audio/video timestamps cleanly) and write subtitles.
files = [f for f, _, _ in plan] + [p("end.mp4")]
concat_video(files, B / "kaye_nosubs.mp4")

srt, t = [], 0.0


def ts(x):
    h, m, s = int(x // 3600), int(x % 3600 // 60), x % 60
    return f"{h:02d}:{m:02d}:{s:06.3f}".replace(".", ",")


n = 1
for (f, total, lead), sid in zip(plan, [s for s in timing]):
    text, d = timing[sid]["text"], timing[sid]["duration"]
    words = text.split()
    chunks, cur = [], []
    for w_ in words:
        cur.append(w_)
        if len(" ".join(cur)) > 58 or w_.endswith((".", "?", "!")) and len(" ".join(cur)) > 25:
            chunks.append(" ".join(cur)); cur = []
    if cur:
        chunks.append(" ".join(cur))
    chars = sum(len(c) for c in chunks)
    start = t + lead
    for c in chunks:
        length = d * len(c) / chars
        srt.append(f"{n}\n{ts(start)} --> {ts(start + length - 0.05)}\n{c}\n")
        n += 1
        start += length
    t += dur(f)
(B / "kaye.srt").write_text("\n".join(srt), encoding="utf-8")

style = "FontName=Segoe UI Semibold,FontSize=13,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,BorderStyle=1,Outline=1.2,Shadow=0,MarginV=6,Alignment=2"
subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", "build/kaye_nosubs.mp4",
                "-vf", f"subtitles=build/kaye.srt:force_style='{style}'", "-c:v", "libx264", "-preset", "slow", "-crf", "18",
                "-pix_fmt", "yuv420p", "-c:a", "copy", "-movflags", "+faststart", "build/Kaye_BuildHybridAI_demo.mp4"],
               cwd=HERE, check=True)
print("done:", round(dur(B / "Kaye_BuildHybridAI_demo.mp4"), 1), "s")
