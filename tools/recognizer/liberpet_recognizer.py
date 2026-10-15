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
    "every_s": 2,                    # one frame every N seconds of clip
    "cat_score": 0.02,               # CLIP "cat" minus "empty bowl" similarity a picture needs to count
    "queue": 6,                      # pictures offered for labelling at a time
    "k": 5,
    "ready_labels": 8,               # labels per cat before "ready" is possible
    "ready_accuracy": 0.9,           # leave-one-out accuracy needed for "ready"
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
        lab = [(self.pos[p], l) for p, l in self.labels.items() if p in self.pos and self.pos[p] != exclude]
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
        """Leave-one-out accuracy over the labelled pictures, and the number of labels per label value."""
        counts = {}
        for l in self.labels.values():
            counts[l] = counts.get(l, 0) + 1
        right = total = 0
        for p, l in self.labels.items():
            if p in self.pos:
                guess, _ = self._vote(self.pos[p], exclude=self.pos[p])
                total += 1; right += guess == l
        return (right / total if total else 0.0), counts

    def to_label(self, n, recent=3000):
        """The unlabelled cat pictures it's least sure about (among the most recent), at most one per clip and bowl."""
        cands = [p for p in self.ids[-recent * 4:] if p not in self.labels and self.meta[p]["cat"]][-recent:]
        if not cands:
            return []
        lab = [(self.pos[p], l) for p, l in self.labels.items() if p in self.pos]
        if lab:  # all candidates at once: margin between the two best labels' summed similarity
            L = self.emb[[j for j, _ in lab]]; S = self.emb[[self.pos[p] for p in cands]] @ L.T
            names = sorted({l for _, l in lab}); col = {l: i for i, l in enumerate(names)}
            top = np.argsort(-S, axis=1)[:, : self.k]
            sure = []
            for r in range(len(cands)):
                v = np.zeros(len(names))
                for t in top[r]:
                    v[col[lab[t][1]]] += max(S[r, t], 0)
                v = np.sort(v)[::-1]
                sure.append((v[0] - (v[1] if len(v) > 1 else 0)) / (v.sum() or 1))
        else:
            sure = [0.0] * len(cands)
        order = sorted(range(len(cands)), key=lambda r: (sure[r], -self.meta[cands[r]]["score"]))
        seen, out = set(), []
        for r in order:
            p = cands[r]; key = (self.meta[p]["clip"], self.meta[p]["side"])
            if key not in seen:
                seen.add(key); out.append(p)
                if len(out) == n:
                    break
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
        t = np.array(list(TextEmbedding(TEXT_MODEL, threads=cfg["threads"]).embed(["a photo of a cat", "a photo of a cat eating from a bowl", "a photo of an empty metal pet bowl on a floor"])))
        self.text = t / np.linalg.norm(t, axis=1, keepdims=True)
        self.last_label = None

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
            for i, (v, m) in enumerate(zip(vecs, metas)):
                pid = f"{rel.replace('/', '_')}_{m['side'][0]}{m['t']:03d}"
                m.update(score=round(float(score[i]), 4), cat=bool(score[i] > self.cfg["cat_score"]))
                self.brain.add(pid, v, m)
        self.done.add(rel)
        log("processed", rel, f"{len(crops)} bowl pictures, {sum(1 for m in metas if m.get('cat'))} with a cat")

    def picture(self, pid):
        """The labelling picture in the shared folder (cut again from the clip if needed)."""
        dst = self.learn / f"{pid}.jpg"
        if dst.exists():
            return
        m = self.brain.meta[pid]
        mp4 = self.out / f"{m['clip']}.mp4"
        tmp = self.data / "pick.jpg"
        subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-ss", str(m["t"]), "-i", str(mp4), "-frames:v", "1",
                        "-vf", "scale=896:-2", "-update", "1", str(tmp)], check=False)
        if not tmp.exists():
            return
        im = Image.open(tmp).convert("RGB"); w, h = im.size
        a, b = self.cfg["split"]
        im = im.crop((0, 0, int(w * a), h) if m["side"] == "left" else (int(w * b), 0, w, h))
        part = self.learn / f".{pid}.jpg.part"
        im.save(part, "JPEG", quality=85); part.replace(dst)

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
        if first and pid in self.brain.labels:
            return False  # the helper still shows the last label from before a restart
        if pid in self.brain.meta and pet:
            self.brain.labels[pid] = pet
            log("label", pid, "=", pet)
            return True
        return False

    def publish(self):
        acc, counts = self.brain.accuracy()
        pets = {p: n for p, n in counts.items() if p != NONE}
        ready = len(pets) >= 2 and min(pets.values()) >= self.cfg["ready_labels"] and acc >= self.cfg["ready_accuracy"]
        queue = self.brain.to_label(self.cfg["queue"])
        for pid in queue:
            self.picture(pid)
        keep = {f"{p}.jpg" for p in queue} | {f"{p}.jpg" for p in self.brain.labels}
        for f in self.learn.glob("*.jpg"):
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
            "to_label": [{"id": p, "path": f"_learn/{p}.jpg", "side": self.brain.meta[p]["side"],
                          "guess": self.brain.predict(p)[0]} for p in queue],
            "clips": clips,
        }
        state = "ready" if ready else ("learning" if self.brain.ids else "waiting")
        try:
            self.ha("POST", f"/api/states/{self.cfg['sensor']}", {"state": state, "attributes": attrs})
        except Exception as e:
            log("publish failed:", e)

    def run(self):
        log("starting:", len(self.brain.ids), "pictures,", len(self.brain.labels), "labels")
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
