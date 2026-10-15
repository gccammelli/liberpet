"""Self-check for the recognizer's learning logic, on made-up fingerprints: venv/bin/python test_brain.py"""
import numpy as np

from liberpet_recognizer import NONE, Brain

rng = np.random.default_rng(1)
cat_a, cat_b, empty = (rng.normal(size=512) for _ in range(3))


def pic(center):
    return center + rng.normal(scale=0.9, size=512) * np.linalg.norm(center) / 22


b = Brain(k=5)
truth = {}
for c in range(40):  # clip c: cat A at the left bowl, cat B at the right one on odd clips, empty otherwise
    for t in range(6):
        for side, center, who in (("left", cat_a, "pet-a"), ("right", cat_b if c % 2 else empty, "pet-b" if c % 2 else NONE)):
            pid = f"c{c}_{side[0]}{t}"
            b.add(pid, pic(center), {"clip": f"day/c{c}", "side": side, "t": t, "score": 0.1, "cat": True})
            truth[pid] = who

assert b.to_label(3) and b.accuracy() == (0.0, {})  # nothing labelled: everything is a candidate

# Only one cat labelled so far: it should go looking for something different (the other cat), on both bowls.
b.labels = {p: "pet-a" for p in list(b.ids)[:8] if truth[p] == "pet-a"}
ask = b.to_label(4)
assert {truth[p] for p in ask} - {"pet-a"}, ask  # not only more of the same cat
assert {b.meta[p]["side"] for p in ask} == {"left", "right"}
b.labels = {}

# The user labels whatever it asks about, a few rounds, as LiberPet would.
for _ in range(6):
    for pid in b.to_label(4):
        b.labels[pid] = truth[pid]
acc, counts = b.accuracy()
print("labels", counts, "leave-one-out accuracy", round(acc, 2))
assert acc >= 0.9 and set(counts) == {"pet-a", "pet-b", NONE}

print("clip 7:", b.clip_cats("day/c7"), "clip 8:", b.clip_cats("day/c8"))
assert {s: p for s, (p, _) in b.clip_cats("day/c7").items()} == {"left": "pet-a", "right": "pet-b"}
assert {s: p for s, (p, _) in b.clip_cats("day/c8").items()} == {"left": "pet-a"}  # empty right bowl: no cat

# The recorder deleted clip 3: its unlabelled pictures go, labelled ones stay, predictions still work.
labelled_in_3 = [p for p in b.labels if p.startswith("c3_")]
b.keep(lambda p: not p.startswith("c3_"))
assert all(p in b.meta for p in labelled_in_3) and not any(p.startswith("c3_") and p not in b.labels for p in b.ids)
assert b.clip_cats("day/c7")["left"][0] == "pet-a"
print("ok")
