"""Checks the recorder reads LiberPet's motion settings: python3 test_settings.py"""
from liberpet_recorder import motion_settings, W, H

# All squares watched, default sensitivity: the old fixed threshold over the whole picture.
thr, idx = motion_settings("s=50;m=" + "f" * 36)
assert abs(thr - 0.015) < 1e-9 and len(idx) == W * H
# Only the top-left square (first bit) watched: its 10x10 pixels.
thr, idx = motion_settings("s=100;m=8" + "0" * 35)
assert thr < 0.015 and len(idx) == 100 and idx[0] == 0 and idx[-1] == 9 * W + 9
# Bottom-right square only (last bit).
_, idx = motion_settings("s=1;m=" + "0" * 35 + "1")
assert idx[0] == 80 * W + 150 and idx[-1] == W * H - 1
# Junk keeps the old settings.
assert motion_settings("") is None and motion_settings("s=x;m=ff") is None and motion_settings("s=50;m=ff") is None
print("ok")
