#!/usr/bin/env python3
"""
LiberPet recognizer: learns which cat is at which bowl from the recorder's clips, with the user labelling
photos in LiberPet until it can tell them apart. Runs next to the recorder; talks only to Home Assistant.

  - Every new clip gives a frame every few seconds, cut into the two bowls (left and right of the feeder's post).
  - Each bowl picture gets a CLIP fingerprint and a "looks like a cat" score; empty bowls are skipped.
  - Labels come from LiberPet through a Home Assistant text helper ("<photo id>=<pet id>", or "=none" for no cat).
  - The pictures it's least sure about are offered for labelling (saved in <clips folder>/_learn/).
  - A nearest-neighbour vote over the labelled pictures names the cat at each bowl in each clip.
  - Everything is published as the Home Assistant entity sensor.liberpet_cats (state: learning / ready).

    venv/bin/python liberpet_recognizer.py --config recognizer.json
See README.md next to this file.
"""
import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

import numpy as np
from PIL import Image

DEFAULTS = {
    "folder": "recordings",          # the recorder's clips folder
    "data": "data",                  # fingerprints and labels (not shared)
    "ha_url": None,
    "ha_token_file": None,           # needs to be allowed to set states (an administrator's token)
    "label_entity": "input_text.liberpet_label",
    "sensor": "sensor.liberpet_cats",
    "split": [0.36, 0.44],           # the feeder's post: left bowl ends, right bowl starts (fraction of the width)
    "every_s": 1,                    # one frame every N seconds of clip
    "cat_score": 0.02,               # CLIP "cat" minus "empty bowl" similarity a picture needs to count
    "queue": 6,                      # pictures offered for labelling at a time
    "k": 5,
    "ready_labels": 3,               # labelled segments (stretches at a bowl) per cat before "ready" is possible
    "ready_accuracy": 0.9,           # share of labelled segments named right when each is left out
    "threads": 4,                    # CPU threads for the model, so the recognizer doesn't take the whole machine
}
MODEL, TEXT_MODEL = "Qdrant/clip-ViT-B-32-vision", "Qdrant/clip-ViT-B-32-text"
NONE = "none"


def log(*a):
    print(datetime.now().strftime("%H:%M:%S"), *a, flush=True)


class Brain:
    """Fingerprints of bowl pictures plus labels; pure, so it can be tested without Home Assistant or CLIP."""

    def __init__(self, k=5):
        self.k = k
        self.ids, self.meta, self.pos, self.by_clip = [], {}, {}, {}
        self._rows, self._emb = [], None
        self.labels = {}

    def add(self, pid, vec, meta):
        if pid in self.meta:
            return
        self.pos[pid] = len(self.ids); self.ids.append(pid); self.meta[pid] = meta
        self.by_clip.setdefault(meta["clip"], []).append(pid)
        self._rows.append((vec / np.linalg.norm(vec)).astype(np.float32)); self._emb = None

    @property
    def emb(self):
        if self._emb is None:
            self._emb = np.array(self._rows, dtype=np.float32).reshape(len(self._rows), -1) if self._rows else np.zeros((0, 512), np.float32)
        return self._emb

    def keep(self, wanted):
        """Drops pictures for which wanted(pid) is false (clips the recorder deleted), keeping labelled ones."""
        ids, rows, meta, labels = self.ids, self._rows, self.meta, self.labels
        self.__init__(self.k)
        self.labels = labels
        for pid, row in zip(ids, rows):
            if pid in labels or wanted(pid):
                self.add(pid, row, meta[pid])

    def _vote(self, i, exclude=None):
        """(best label, confidence 0..1) from the k most similar labelled pictures."""
        exclude = exclude or set()
        lab = [(self.pos[p], l) for p, l in self.labels.items() if p in self.pos and self.pos[p] not in exclude]
        if not lab:
            return None, 0.0
        idx = np.array([j for j, _ in lab]); sims = self.emb[idx] @ self.emb[i]
        top = np.argsort(-sims)[: self.k]
        score = {}
        for t in top:
            score[lab[t][1]] = score.get(lab[t][1], 0.0) + max(float(sims[t]), 0.0)
        best = max(score, key=score.get)
        return best, score[best] / (sum(score.values()) or 1.0)

    def predict(self, pid):
        return self._vote(self.pos[pid])

    def accuracy(self):
        """
        How often it names a labelled segment right when that whole segment is left out (so near-identical frames
        can't vouch for each other), and the number of labelled segments per label value.
        """
        segs = {}
        for p, l in self.labels.items():
            if p in self.pos:
                segs.setdefault(self.meta[p].get("seg", p), (l, []))[1].append(p)
        counts = {}
        for l, _ in segs.values():
            counts[l] = counts.get(l, 0) + 1
        right = 0
        for l, ps in segs.values():
            excl = {self.pos[p] for p in ps}
            votes = {}
            for p in ps[:: max(1, len(ps) // 5)]:  # a few of its pictures
                g, c = self._vote(self.pos[p], exclude=excl)
                if g:
                    votes[g] = votes.get(g, 0) + c
            right += bool(votes) and max(votes, key=votes.get) == l
        return (right / len(segs) if segs else 0.0), counts

    def segments(self, gap=3, split=0.86, min_len=3, max_len=20):
        """
        Stretches with one cat at one bowl: {id: {clip, side, t0, t1, pids}}. A bowl's cat pictures split where it
        stays empty for more than [gap] seconds or where the picture's fingerprint jumps (the 3 seconds before vs
        the 3 after less similar than [split]): cats can swap bowls within a clip. Shorter than [min_len] s: dropped;
        longer than [max_len] s: cut into pieces, so each labelling video stays short and watchable.
        """
        runs = {}
        for p in self.ids:
            m = self.meta[p]
            if m["cat"]:
                runs.setdefault((m["clip"], m["side"]), []).append((m["t"], p))
        out = {}
        for (clip, side), v in runs.items():
            v.sort()
            cur = []
            for i, (t, p) in enumerate(v):
                if cur and (t - cur[-1][0] > gap or t - cur[0][0] >= max_len or self._jump(cur[-3:], v[i:i + 3], split)):
                    self._close(out, clip, side, cur, min_len); cur = []
                cur.append((t, p))
            self._close(out, clip, side, cur, min_len)
        return out

    def _jump(self, before, after, split):
        x = self.emb[[self.pos[p] for _, p in before]].mean(0); y = self.emb[[self.pos[p] for _, p in after]].mean(0)
        return float(x @ y / (np.linalg.norm(x) * np.linalg.norm(y))) < split

    @staticmethod
    def _close(out, clip, side, cur, min_len):
        if cur and cur[-1][0] - cur[0][0] + 1 >= min_len:
            sid = f"{clip.replace('/', '_')}_{side[0]}{cur[0][0]:03d}"
            out[sid] = {"clip": clip, "side": side, "t0": cur[0][0], "t1": cur[-1][0], "pids": [p for _, p in cur]}

    def label_segment(self, seg_id, seg, pet):
        for p in seg["pids"]:
            self.labels[p] = pet
            self.meta[p]["seg"] = seg_id

    def segments_to_label(self, n, segs=None):
        """The unlabelled segments to ask about next, as (id, segment): least sure first (see [to_label]), bowls in turn."""
        segs = self.segments() if segs is None else segs
        open_ = {sid: g for sid, g in segs.items() if not any(p in self.labels for p in g["pids"])}
        if not open_:
            return []
        pids = [p for g in open_.values() for p in g["pids"]]
        sure = self._sureness(pids)
        score = {sid: float(np.mean([sure[p] for p in g["pids"]])) for sid, g in open_.items()}
        order = sorted(open_, key=lambda sid: (round(score[sid], 2), -len(open_[sid]["pids"])))
        by_side = {"left": [x for x in order if open_[x]["side"] == "left"], "right": [x for x in order if open_[x]["side"] == "right"]}
        out = []
        while len(out) < n and (by_side["left"] or by_side["right"]):
            for side in ("left", "right"):
                if by_side[side] and len(out) < n:
                    sid = by_side[side].pop(0); out.append((sid, open_[sid]))
        return out

    def _sureness(self, cands):
        """Per picture: margin between the two best labels (two or more cats labelled), else similarity to the nearest labelled picture."""
        lab = [(self.pos[p], l) for p, l in self.labels.items() if p in self.pos]
        if not lab:
            return {p: 0.0 for p in cands}
        L = self.emb[[j for j, _ in lab]]; S = self.emb[[self.pos[p] for p in cands]] @ L.T
        if len({l for _, l in lab if l != NONE}) < 2:
            return dict(zip(cands, S.max(axis=1).tolist()))
        names = sorted({l for _, l in lab}); col = {l: i for i, l in enumerate(names)}
        top = np.argsort(-S, axis=1)[:, : self.k]
        out = {}
        for r, p in enumerate(cands):
            v = np.zeros(len(names))
            for t in top[r]:
                v[col[lab[t][1]]] += max(S[r, t], 0)
            v = np.sort(v)[::-1]
            out[p] = float((v[0] - (v[1] if len(v) > 1 else 0)) / (v.sum() or 1))
        return out

    def clip_cats(self, clip):
        """Who was at each bowl in a clip: {side: (pet, share of the votes)} for sides with a confident cat."""
        out = {}
        for side in ("left", "right"):
            votes = {}
            for p in self.by_clip.get(clip, []):
                m = self.meta[p]
                if m["side"] == side and m["cat"]:
                    pet, conf = self.labels.get(p), 1.0
                    if pet is None:
                        pet, conf = self.predict(p)
                    if pet and pet != NONE and conf >= 0.6:
                        votes[pet] = votes.get(pet, 0.0) + conf
            if votes:
                best = max(votes, key=votes.get)
                out[side] = (best, round(votes[best] / sum(votes.values()), 2))
        return out


class Recognizer:
    def __init__(self, cfg):
        self.cfg = cfg
        self.out = Path(cfg["folder"]); self.data = Path(cfg["data"]); self.data.mkdir(parents=True, exist_ok=True)
        self.learn = self.out / "_learn"; self.learn.mkdir(exist_ok=True)
        self.brain = Brain(cfg["k"])
        self.token = Path(cfg["ha_token_file"]).read_text().strip() if cfg["ha_token_file"] else None
        self.load()
        from fastembed import ImageEmbedding, TextEmbedding  # slow import: only when actually running
        self.images = ImageEmbedding(MODEL, threads=cfg["threads"])
        t = np.array(list(TextEmbedding(TEXT_MODEL, threads=cfg["threads"]).embed([
            "a photo of a cat", "a photo of a cat eating from a bowl", "a photo of an empty metal pet bowl on a floor",
            "a photo of a cat's face with its eyes and nose", "a photo of a whole cat sitting on the floor",
            "the top of a cat's head while it eats from a bowl"])))
        self.text = t / np.linalg.norm(t, axis=1, keepdims=True)
        for p in self.brain.ids:  # pictures from before the "face" score existed
            if "face" not in self.brain.meta[p]:
                self.brain.meta[p]["face"] = self.face(self.brain.emb[self.brain.pos[p]][None])[0]
        self.last_label = None
        self.segs = {}

    # ---- storage ----

    def load(self):
        f = self.data / "brain.json"
        if f.exists():
            d = json.loads(f.read_text())
            emb = np.load(self.data / "emb.npy")
            for i, pid in enumerate(d["ids"]):
                self.brain.add(pid, emb[i], d["meta"][pid])
            self.brain.labels = d["labels"]
        self.done = set(json.loads((self.data / "done.json").read_text())) if (self.data / "done.json").exists() else set()

    def save(self):
        np.save(self.data / "emb.npy", self.brain.emb)
        (self.data / "brain.json").write_text(json.dumps({"ids": self.brain.ids, "meta": self.brain.meta, "labels": self.brain.labels}))
        (self.data / "done.json").write_text(json.dumps(sorted(self.done)))

    def face(self, vecs):
        """How much of the cat shows (face or whole body) rather than the top of its head: for choosing photos to label."""
        s = vecs @ self.text.T
        return [round(float(x), 4) for x in np.maximum(s[:, 3], s[:, 4]) - s[:, 5]]

    # ---- clips ----

    def new_clips(self):
        for mp4 in sorted(self.out.glob("*/*.mp4")):
            rel = f"{mp4.parent.name}/{mp4.stem}"
            if rel not in self.done and not mp4.name.startswith(".") and time.time() - mp4.stat().st_mtime > 10:
                yield rel, mp4

    def process(self, rel, mp4):
        frames = self.data / "frames"; frames.mkdir(exist_ok=True)
        for f in frames.glob("*.jpg"):
            f.unlink()
        subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-i", str(mp4), "-vf", f"fps=1/{self.cfg['every_s']},scale=448:-2",
                        str(frames / "%03d.jpg")], check=False)
        crops, metas = [], []
        a, b = self.cfg["split"]
        for f in sorted(frames.glob("*.jpg")):
            im = Image.open(f).convert("RGB"); w, h = im.size
            n = int(f.stem)
            for side, box in (("left", (0, 0, int(w * a), h)), ("right", (int(w * b), 0, w, h))):
                crops.append(im.crop(box)); metas.append({"clip": rel, "side": side, "t": (n - 1) * self.cfg["every_s"]})
        if crops:
            vecs = np.array(list(self.images.embed(crops)))
            vecs = vecs / np.linalg.norm(vecs, axis=1, keepdims=True)
            sims = vecs @ self.text.T
            score = np.maximum(sims[:, 0], sims[:, 1]) - sims[:, 2]
            face = self.face(vecs)
            for i, (v, m) in enumerate(zip(vecs, metas)):
                pid = f"{rel.replace('/', '_')}_{m['side'][0]}{m['t']:03d}"
                m.update(score=round(float(score[i]), 4), cat=bool(score[i] > self.cfg["cat_score"]), face=face[i])
                self.brain.add(pid, v, m)
        self.done.add(rel)
        log("processed", rel, f"{len(crops)} bowl pictures, {sum(1 for m in metas if m.get('cat'))} with a cat")

    def render(self, sid, seg):
        """
        The labelling video in the shared folder: the segment (at most 10 s to watch, sped up if longer) with the
        other bowl dimmed, plus a poster frame. Built under a temporary name, then renamed.
        """
        dst = self.learn / f"{sid}.mp4"
        if dst.exists():
            return
        mp4 = self.out / f"{seg['clip']}.mp4"
        start, dur = max(seg["t0"] - 1, 0), seg["t1"] - seg["t0"] + 2  # at most 22 s: shown in 10 s or less
        a, b = self.cfg["split"]
        dim = f"drawbox=x=iw*{a}:y=0:w=iw*{1 - a}:h=ih:color=black@0.65:t=fill" if seg["side"] == "left" \
            else f"drawbox=x=0:y=0:w=iw*{b}:h=ih:color=black@0.65:t=fill"
        part = self.learn / f".{sid}.part.mp4"
        subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-ss", str(start), "-t", str(dur), "-i", str(mp4),
                        "-vf", f"scale=640:-2,{dim},setpts={min(1.0, 10 / dur):.3f}*PTS", "-an", "-c:v", "libx264", "-preset", "veryfast",
                        "-crf", "28", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(part)], check=False)
        if part.exists() and part.stat().st_size:
            poster = self.learn / f".{sid}.part.jpg"
            subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-i", str(part), "-frames:v", "1", "-update", "1", str(poster)], check=False)
            if poster.exists():
                poster.replace(self.learn / f"{sid}.jpg")
            part.replace(dst)

    # ---- Home Assistant ----

    def ha(self, method, path, body=None):
        req = urllib.request.Request(self.cfg["ha_url"].rstrip("/") + path, method=method,
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.load(r)

    def read_label(self):
        """A new "<photo id>=<pet id>" from LiberPet, if any."""
        try:
            v = self.ha("GET", f"/api/states/{self.cfg['label_entity']}")["state"]
        except urllib.error.HTTPError as e:
            if e.code != 404:
                log("label poll failed:", e)
            return False
        except Exception as e:
            log("label poll failed:", e)
            return False
        if v == self.last_label or "=" not in v:
            self.last_label = v
            return False
        first = self.last_label is None
        self.last_label = v
        pid, pet = v.split("=", 1)
        seg = self.segs.get(pid)
        if first and (pid in self.brain.labels or (seg and seg["pids"][0] in self.brain.labels)):
            return False  # the helper still shows the last label from before a restart
        if seg and pet:
            self.brain.label_segment(pid, seg, pet)
            log("label", pid, f"({len(seg['pids'])} pictures)", "=", pet)
            return True
        if pid in self.brain.meta and pet:  # a single picture (older labelling)
            self.brain.labels[pid] = pet
            log("label", pid, "=", pet)
            return True
        return False

    def guess(self, seg):
        votes = {}
        for p in seg["pids"][:: max(1, len(seg["pids"]) // 5)]:
            g, c = self.brain.predict(p)
            if g:
                votes[g] = votes.get(g, 0) + c
        return max(votes, key=votes.get) if votes else None

    def publish(self):
        acc, counts = self.brain.accuracy()
        pets = {p: n for p, n in counts.items() if p != NONE}
        ready = len(pets) >= 2 and min(pets.values()) >= self.cfg["ready_labels"] and acc >= self.cfg["ready_accuracy"]
        self.segs = self.brain.segments()
        queue = self.brain.segments_to_label(self.cfg["queue"], self.segs)
        for sid, seg in queue:
            self.render(sid, seg)
        keep = {f"{sid}.{ext}" for sid, _ in queue for ext in ("mp4", "jpg")}
        for f in list(self.learn.glob("*.mp4")) + list(self.learn.glob("*.jpg")):
            if f.name not in keep:
                f.unlink()
        clips = {}
        for rel in sorted(self.done)[-300:]:
            cats = self.brain.clip_cats(rel)
            if cats:
                clips[rel] = {side: pet for side, (pet, _) in cats.items()}
        attrs = {
            "friendly_name": "LiberPet cats",
            "accuracy": round(acc, 2), "labels": counts, "pictures": len(self.brain.ids),
            "cat_pictures": sum(1 for m in self.brain.meta.values() if m["cat"]),
            "to_label": [{"id": sid, "path": f"_learn/{sid}.jpg", "video": f"_learn/{sid}.mp4", "side": seg["side"],
                          "seconds": seg["t1"] - seg["t0"] + 1, "guess": self.guess(seg)}
                         for sid, seg in queue if (self.learn / f"{sid}.mp4").exists()],
            "clips": clips,
        }
        state = "ready" if ready else ("learning" if self.brain.ids else "waiting")
        try:
            self.ha("POST", f"/api/states/{self.cfg['sensor']}", {"state": state, "attributes": attrs})
        except Exception as e:
            log("publish failed:", e)

    def run(self):
        log("starting:", len(self.brain.ids), "pictures,", len(self.brain.labels), "labels")
        self.segs = self.brain.segments()
        # Labels from before segments (single pictures): each now labels its whole segment.
        for sid, seg in self.segs.items():
            single = {self.brain.labels[p] for p in seg["pids"] if p in self.brain.labels and "seg" not in self.brain.meta[p]}
            if len(single) == 1:
                self.brain.label_segment(sid, seg, single.pop())
        last_scan = 0.0
        changed = True
        while True:
            if self.read_label():
                changed = True
            if time.time() - last_scan > 60:
                gone = {c for c in self.brain.by_clip if not (self.out / f"{c}.mp4").exists()}
                if gone:  # the recorder's size cap deleted them
                    meta = dict(self.brain.meta)
                    self.brain.keep(lambda p: meta[p]["clip"] not in gone)
                    self.done -= gone; changed = True
                for rel, mp4 in self.new_clips():
                    try:
                        self.process(rel, mp4)
                    except Exception as e:
                        log("couldn't process", rel, e); self.done.add(rel)
                    changed = True
                last_scan = time.time()
            if changed:
                self.save(); self.publish(); changed = False
            time.sleep(1)  # answers from LiberPet arrive one at a time; it waits for each to be taken


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--config", required=True)
    args = ap.parse_args()
    cfg = {**DEFAULTS, **json.loads(Path(args.config).read_text())}
    if not cfg["ha_url"] or not cfg["ha_token_file"]:
        sys.exit("ha_url and ha_token_file are required")
    Recognizer(cfg).run()


if __name__ == "__main__":
    main()
