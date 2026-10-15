#!/usr/bin/env python3
"""
LiberPet recorder: saves the feeder camera's clips into a folder, for Home Assistant's media browser and LiberPet's
gallery. Python 3.10+ and ffmpeg, nothing else.

It keeps the camera stream open and a rolling buffer of the last few minutes as short segments, so a clip can start
before whatever triggered it. Triggers:
  - motion: low-resolution frame differences (about twice a second);
  - feedings: the PETLIBRO integration's "Recent Activity" sensor in Home Assistant (optional; its records carry the
    exact dispense time, so the buffer covers Home Assistant's polling delay);
  - continuous mode: everything, in 5-minute clips;
  - manual: LiberPet's record button (it presses the Home Assistant button "input_button.liberpet_record"), or
    `kill -USR1 <pid>`: saves the last pre-roll seconds plus the next 30.
Each clip is an MP4 with a JPG thumbnail and a JSON note. The folder is kept under a size cap, oldest clips first.

    python3 liberpet_recorder.py --config recorder.json
See README.md next to this file.
"""
import argparse
import json
import os
import shutil
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

W, H, FPS = 160, 90, 2  # motion frames: tiny greyscale, twice a second
SEGMENT_S = 2

DEFAULTS = {
    "stream": "rtsp://127.0.0.1:8554/petlibro?video=h264&audio=aac",
    "folder": "recordings",
    "buffer_folder": None,       # rolling buffer; default: a hidden folder inside "folder". Keep it out of shares.
    "cap_gb": 128,
    "mode": "events",            # "events" or "continuous"
    "pre_roll_s": 10,
    "buffer_s": 180,             # must cover Home Assistant's delay for feed records
    "motion": True,
    "motion_threshold": 0.015,   # share of pixels that changed
    "motion_pixel_delta": 25,    # 0-255 change that counts as "changed"
    "motion_post_s": 15,         # keep recording this long after motion stops
    "feed_post_s": 60,
    "max_clip_s": 300,
    "ha_url": None,              # e.g. http://homeassistant.local:8123 (optional, for feed clips)
    "ha_token_file": None,       # file holding a long-lived access token
    "activity_entities": [],     # e.g. ["sensor.granary_camera_feeder_recent_activity"]
    "record_button": "input_button.liberpet_record",  # LiberPet's record button; its state is the press time
}


def log(*a):
    print(datetime.now().strftime("%H:%M:%S"), *a, flush=True)


def place(src, dst):
    """Moves a finished file into the clips folder, which may be another filesystem than the buffer (a size-capped
    image, a network share): copied under a temporary name there, then renamed, so viewers never see half a file."""
    tmp = dst.with_name(f".{dst.name}.part")
    shutil.copyfile(src, tmp)
    os.replace(tmp, dst)
    src.unlink()


class Recorder:
    def __init__(self, cfg):
        self.cfg = cfg
        self.out = Path(cfg["folder"])
        self.buf = Path(cfg["buffer_folder"]) if cfg["buffer_folder"] else self.out / ".buffer"
        self.buf.mkdir(parents=True, exist_ok=True)
        self.windows = []  # [start, end, reasons(set), peak_motion]
        self.lock = threading.Lock()
        self.seen_records = set()
        self.motion_on = False

    # ---- triggers ----

    def trigger(self, start, end, reason, score=0.0):
        with self.lock:
            for w in self.windows:
                if start <= w[1] + 5 and end >= w[0] - 5:  # overlapping: extend
                    w[0], w[1] = min(w[0], start), max(w[1], end)
                    w[2].add(reason)
                    w[3] = max(w[3], score)
                    return
            self.windows.append([start, end, {reason}, score])
            log("event", reason, datetime.fromtimestamp(start).strftime("%H:%M:%S"))

    def motion_loop(self, frames):
        prev = None
        hot = 0
        last_motion = 0.0
        n = W * H
        delta, threshold = self.cfg["motion_pixel_delta"], self.cfg["motion_threshold"]
        while True:
            frame = frames.read(n)
            if len(frame) < n:
                return  # ffmpeg ended
            if prev is not None:
                changed = sum(1 for a, b in zip(frame, prev) if abs(a - b) > delta) / n
                hot = hot + 1 if changed > threshold else 0
                now = time.time()
                if hot >= 2:  # two frames in a row, so single glitches don't count
                    last_motion = now
                    self.trigger(now - self.cfg["pre_roll_s"], now + self.cfg["motion_post_s"], "motion", changed)
                self.motion_on = now - last_motion < self.cfg["motion_post_s"]
            prev = frame

    def feed_loop(self):
        url, entities, button = self.cfg["ha_url"], self.cfg["activity_entities"], self.cfg["record_button"]
        if not url or not (entities or button) or not self.cfg["ha_token_file"]:
            return
        token = Path(self.cfg["ha_token_file"]).read_text().strip()

        def state(ent):
            req = urllib.request.Request(f"{url.rstrip('/')}/api/states/{ent}", headers={"Authorization": f"Bearer {token}"})
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.load(r)

        first = True
        last_press = None
        while True:
            if button:
                try:
                    pressed = state(button)["state"]  # ISO time of the last press
                    if not first and pressed != last_press and pressed not in ("unknown", "unavailable"):
                        t = datetime.fromisoformat(pressed).timestamp()
                        if t > time.time() - self.cfg["buffer_s"] + self.cfg["pre_roll_s"]:
                            self.trigger(t - self.cfg["pre_roll_s"], t + 30, "manual")
                    last_press = pressed
                except urllib.error.HTTPError as e:
                    if e.code != 404:  # 404: no button set up, nothing to watch
                        log("record button poll failed:", e)
                except Exception as e:
                    log("record button poll failed:", e)
            for ent in entities:
                try:
                    records = state(ent).get("attributes", {}).get("records", [])
                except Exception as e:  # Home Assistant restarting etc.: try again next round
                    log("feed poll failed:", e)
                    continue
                for rec in records:
                    if rec.get("type") != "GRAIN_OUTPUT_SUCCESS":
                        continue
                    key = (ent, rec["time"])
                    if key in self.seen_records:
                        continue
                    self.seen_records.add(key)
                    if first:
                        continue  # don't record feedings from before we started
                    t = datetime.fromisoformat(rec["time"]).timestamp()
                    if t > time.time() - self.cfg["buffer_s"] + self.cfg["pre_roll_s"]:
                        self.trigger(t - self.cfg["pre_roll_s"], t + self.cfg["feed_post_s"], "feed")
            first = False
            time.sleep(5 if button else 20)  # the record button wants a quicker answer; the buffer covers the wait

    # ---- clips ----

    def segments(self):
        """Buffer segments as (start time, path), oldest first. Names are their start times (epoch seconds)."""
        out = []
        for p in self.buf.glob("*.ts"):
            try:
                out.append((int(p.stem), p))
            except ValueError:
                pass
        return sorted(out)

    def cut(self, start, end, reasons, score):
        segs = [p for t, p in self.segments() if start - SEGMENT_S <= t <= end]
        segs = segs[:-1] if segs and segs[-1].stat().st_mtime > time.time() - 1 else segs  # skip the one being written
        if not segs:
            return
        day = datetime.fromtimestamp(start)
        folder = self.out / day.strftime("%Y-%m-%d")
        folder.mkdir(exist_ok=True)
        name = day.strftime("%H-%M-%S") + "_" + "-".join(sorted(reasons))
        lst = self.buf / f"{name}.txt"
        lst.write_text("".join(f"file '{p.resolve()}'\n" for p in segs))
        # Built in the buffer folder and moved into place when complete, so viewers never see a half-written clip.
        tmp = self.buf / f"{name}.mp4"
        subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(lst),
                        "-c", "copy", "-movflags", "+faststart", str(tmp)], check=False)
        lst.unlink(missing_ok=True)
        if not tmp.exists() or tmp.stat().st_size == 0:
            return
        probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(tmp)], capture_output=True, text=True)
        dur = round(float(probe.stdout.strip() or 0))
        name += f"_{dur}s"  # the length in the name: Home Assistant's media browser doesn't show the JSON notes
        thumb = self.buf / f"{name}.jpg"
        subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-ss", str(min(self.cfg["pre_roll_s"], 5)), "-i", str(tmp),
                        "-frames:v", "1", "-vf", "scale=480:-2", "-update", "1", str(thumb)], check=False)
        if thumb.exists():
            place(thumb, folder / thumb.name)
        mp4 = folder / f"{name}.mp4"
        place(tmp, mp4)
        (folder / f"{name}.json").write_text(json.dumps({
            "start": datetime.fromtimestamp(start, timezone.utc).isoformat(), "duration_s": dur,
            "reasons": sorted(reasons), "motion": round(score, 4)}))
        log("saved", mp4.relative_to(self.out), f"{dur}s")
        self.enforce_cap()

    def clip_loop(self):
        while True:
            now = time.time()
            if self.cfg["mode"] == "continuous":
                self.trigger(now - 1, now + 1, "continuous")
            ready = []
            with self.lock:
                for w in list(self.windows):
                    too_long = w[1] - w[0] >= self.cfg["max_clip_s"]
                    if w[1] < now - SEGMENT_S or too_long:
                        self.windows.remove(w)
                        if too_long and w[1] > now:  # split long events: the rest continues in a new clip
                            self.windows.append([w[0] + self.cfg["max_clip_s"], w[1], set(w[2]), w[3]])
                            w[1] = w[0] + self.cfg["max_clip_s"]
                        ready.append(w)
            for w in ready:
                try:
                    self.cut(*w)
                except Exception as e:  # one bad clip (disk full, a glitch) mustn't stop all the later ones
                    log("couldn't save a clip:", e)
            keep_from = min([w[0] for w in self.windows] + [now - self.cfg["buffer_s"]])
            for t, p in self.segments():
                if t < keep_from - SEGMENT_S:
                    p.unlink(missing_ok=True)
            time.sleep(1)

    def enforce_cap(self):
        cap = self.cfg["cap_gb"] * 1024 ** 3
        clips = sorted(self.out.glob("*/*.mp4"))
        used = sum(p.stat().st_size for p in self.out.rglob("*") if p.is_file() and self.buf not in p.parents)
        while used > cap and clips:
            old = clips.pop(0)
            for p in (old, old.with_suffix(".jpg"), old.with_suffix(".json")):
                if p.exists():
                    used -= p.stat().st_size
                    p.unlink()
            if not any(old.parent.iterdir()):
                old.parent.rmdir()
            log("cap: removed", old.name)
        (self.out / "status.json").write_text(json.dumps({
            "cap_gb": self.cfg["cap_gb"], "used_bytes": used, "clips": len(clips), "mode": self.cfg["mode"],
            "updated": datetime.now(timezone.utc).isoformat()}))

    # ---- camera ----

    def run(self):
        # `kill -USR1 <pid>` saves the last pre-roll seconds plus the next 30 (tests, or a "record now" button).
        signal.signal(signal.SIGUSR1, lambda *_: self.trigger(time.time() - self.cfg["pre_roll_s"], time.time() + 30, "manual"))
        threading.Thread(target=self.clip_loop, daemon=True).start()
        threading.Thread(target=self.feed_loop, daemon=True).start()
        self.enforce_cap()
        backoff = 5
        while True:
            cmd = ["ffmpeg", "-nostdin", "-loglevel", "error", "-rtsp_transport", "tcp", "-i", self.cfg["stream"],
                   # 1) the buffer: short segments named by their start time
                   "-map", "0", "-c", "copy", "-f", "segment", "-segment_time", str(SEGMENT_S), "-segment_format", "mpegts",
                   "-reset_timestamps", "1", "-strftime", "1", str(self.buf / "%s.ts")]
            if self.cfg["motion"]:
                # 2) tiny greyscale frames for motion detection
                cmd += ["-map", "0:v", "-vf", f"fps={FPS},scale={W}:{H},format=gray", "-f", "rawvideo", "pipe:1"]
            log("connecting to", self.cfg["stream"].split("?")[0])
            started = time.time()
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE if self.cfg["motion"] else subprocess.DEVNULL)
            if self.cfg["motion"]:
                self.motion_loop(proc.stdout)
            proc.wait()
            # The camera can refuse new sessions for up to a minute after the last one: back off.
            backoff = 5 if time.time() - started > 120 else min(backoff * 2, 60)
            log(f"stream ended (code {proc.returncode}); retrying in {backoff}s")
            time.sleep(backoff)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--config", required=True)
    cfg = {**DEFAULTS, **json.loads(Path(sys.argv[sys.argv.index("--config") + 1]).read_text())} if "--config" in sys.argv else DEFAULTS
    ap.parse_args()
    if not shutil.which("ffmpeg"):
        sys.exit("ffmpeg is required")
    if cfg["mode"] not in ("events", "continuous"):
        sys.exit("mode must be 'events' or 'continuous'")
    Recorder(cfg).run()


if __name__ == "__main__":
    main()
