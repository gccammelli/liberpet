"""Self-check for the recognizer's learning logic, on made-up fingerprints: venv/bin/python test_brain.py"""
import numpy as np

from liberpet_recognizer import NONE, Brain

rng = np.random.default_rng(1)
# Like the real fingerprints: frames of one cat about 0.93 alike, the two (grey) cats' averages about 0.8.
cat_a = rng.normal(size=512); cat_a /= np.linalg.norm(cat_a)
other = rng.normal(size=512); other -= (other @ cat_a) * cat_a; other /= np.linalg.norm(other)
cat_b = 0.8 * cat_a + 0.6 * other


def pic(center):
    return center + rng.normal(size=512) * 0.012


b = Brain(k=5)
truth = {}


def add(clip, side, t, who):
    pid = f"{clip}_{side[0]}{t:03d}"
    b.add(pid, pic(cat_a if who == "pet-a" else cat_b), {"clip": clip, "side": side, "t": t, "score": 0.1, "cat": True})
    truth[pid] = who


for c in range(30):  # cat A at the left bowl for 8 s; on odd clips cat B at the right bowl too
    for t in range(8):
        add(f"day/c{c}", "left", t, "pet-a")
        if c % 2:
            add(f"day/c{c}", "right", t, "pet-b")
# A swap inside one clip: cat A at the left bowl for 8 s, then cat B there right after (no gap).
for t in range(16):
    add("day/swap", "left", t, "pet-a" if t < 8 else "pet-b")

# A long stay (50 s) is cut into short pieces for labelling.
for t in range(50):
    add("day/long", "right", t, "pet-b")

segs = b.segments()
long = sorted(g["t1"] - g["t0"] + 1 for g in segs.values() if g["clip"] == "day/long")
assert long and max(long) <= 20 and sum(long) == 50, long
swap = sorted((g["t0"], g["t1"]) for g in segs.values() if g["clip"] == "day/swap")
print("swap clip segments:", swap)
assert len(swap) == 2 and swap[0][1] < swap[1][0], swap  # split where the cat changed
assert all(len({truth[p] for p in g["pids"]}) == 1 for g in segs.values())  # every segment is one cat

# Labelling: the user answers whatever segment it asks about, a few rounds, as LiberPet would.
asked = 0
for _ in range(4):
    for sid, g in b.segments_to_label(4, b.segments()):
        b.label_segment(sid, g, truth[g["pids"][0]]); asked += 1
acc, counts = b.accuracy()
print(f"{asked} questions, segments per cat {counts}, accuracy {acc:.2f}")
assert acc >= 0.9 and set(counts) == {"pet-a", "pet-b"}

# Only one cat labelled so far: the next questions go looking for the other one.
b2 = Brain(k=5); b2.ids, b2.meta, b2.pos, b2.by_clip, b2._rows = b.ids, b.meta, b.pos, b.by_clip, b._rows
b2.labels = {p: "pet-a" for p in b.ids if truth[p] == "pet-a"}
ask = b2.segments_to_label(2, b2.segments())
assert ask and all(truth[g["pids"][0]] == "pet-b" for _, g in ask), ask

print("clip 7:", b.clip_cats("day/c7"))
assert {s: p for s, (p, _) in b.clip_cats("day/c7").items()} == {"left": "pet-a", "right": "pet-b"}
b.keep(lambda p: not p.startswith("day/c3_"))
assert not any(p.startswith("day/c3_") and p not in b.labels for p in b.ids)
print("ok")
