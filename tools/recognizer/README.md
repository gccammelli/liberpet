# LiberPet recognizer

Tells your cats apart in the recorder's clips, so LiberPet can show who ate from which bowl. You teach it in the app:
**Feeder › Cat Recognition** plays a short clip of one bowl (the other one dimmed), you tap who it is, and it asks
next about the clip it's least sure of, until it can tell your cats apart (usually 3–5 clips per cat).

It runs on the same machine as the [recorder](../recorder/README.md), on the CPU, with no cloud. Like everything in
LiberPet, it only talks to Home Assistant:

- **Its results** go to the entity `sensor.liberpet_cats`: state `waiting`, `learning` or `ready`, plus the photos to
  label and who it saw at each bowl in each clip. LiberPet reads it.
- **Daily statistics per cat** (visits, time eating, bowl, hours) go to `sensor.liberpet_cat_stats`, for LiberPet's Cat
  Insights page.
- **Your answers** come back through a Home Assistant text helper, `input_text.liberpet_label`, which LiberPet sets.
- **The clips to label** are saved in the clips folder under `_learn/`, so Home Assistant's media browser serves
  them like the recordings.

How it works: every clip gives a frame every second, cut into the two bowls (left and right of the feeder's post).
Each bowl picture gets a [CLIP](https://openai.com/research/clip) image fingerprint and a "looks like a cat" score, so
empty bowls are skipped. A bowl's cat pictures are split into **segments**, one cat each: a new segment starts when the
bowl stays empty for 3 seconds, when the fingerprint jumps (cats swap bowls mid-clip), and every 20 seconds. You label
a segment from its video, and the label covers all its pictures. The cat at a bowl is then the one whose labelled
pictures are most similar (nearest neighbours). "Ready" means at least 3 labelled segments per cat, and naming at
least 90% of labelled segments right when each one is left out in turn.

The camera looks down at the bowls, so it rarely sees a cat's face; the videos let you tell the cats apart by how
they move, their size and their coat.

## Set up

1. **Home Assistant:** add the text helper. Go to **Settings › Devices & services › Helpers › Create helper › Text**,
   name it `LiberPet label` and set the maximum length to 255.
2. **Install** (Python 3.10+, ffmpeg, about 600 MB with the model):
   ```
   uv venv -p 3.12 venv && VIRTUAL_ENV=venv uv pip install fastembed
   ```
   The model (about 340 MB) downloads on the first start.
3. **Configure** `recognizer.json`:
   ```json
   {
     "folder": "/path/to/recordings",
     "data": "/path/to/recognizer-data",
     "ha_url": "http://homeassistant.local:8123",
     "ha_token_file": "/path/to/ha-token"
   }
   ```
   `folder` is the recorder's clips folder. `data` holds fingerprints and labels; keep it out of the shared folder.
   The token must be an administrator's, because publishing an entity's state needs one; the recorder's token works.
   Optional settings: `split` (where the feeder's post is, as fractions of the width, default `[0.36, 0.44]`) and
   `threads` (CPU threads, default 4).
4. **Run** it as a service, like the recorder. A systemd user service,
   `~/.config/systemd/user/liberpet-recognizer.service` (paths: wherever you installed it):
   ```ini
   [Unit]
   Description=LiberPet recognizer (which cat is at which bowl)
   After=liberpet-recorder.service

   [Service]
   ExecStart=%h/liberpet-recognizer/venv/bin/python -u %h/liberpet-recognizer/liberpet_recognizer.py --config %h/liberpet-recognizer/recognizer.json
   Nice=10
   Restart=always
   RestartSec=30

   [Install]
   WantedBy=default.target
   ```
   Then `systemctl --user daemon-reload && systemctl --user enable --now liberpet-recognizer`, and
   `loginctl enable-linger` so it keeps running when you're logged out.

**What it needs:** a computer that's always on (the same one as the recorder), about 1 GB of memory, and a few CPU
seconds per clip (4 threads by default; `threads` changes it). No graphics card.

It processes new clips once a minute. Pictures of clips the recorder deletes for space go too, except labelled ones.

`test_brain.py` checks the learning logic on made-up fingerprints: `venv/bin/python test_brain.py`.
