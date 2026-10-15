# LiberPet recognizer

Tells your cats apart in the recorder's clips, so LiberPet can show who ate from which bowl. You teach it in the app:
**Feeder › Cat Recognition** shows a bowl photo, you tap who it is, and it asks next about the photo it's least sure
of, until it can tell your cats apart (about 10 photos per cat).

It runs on the same machine as the [recorder](../recorder/README.md), on the CPU, with no cloud. Like everything in
LiberPet, it only talks to Home Assistant:

- **Its results** go to the entity `sensor.liberpet_cats`: state `waiting`, `learning` or `ready`, plus the photos to
  label and who it saw at each bowl in each clip. LiberPet reads it.
- **Your answers** come back through a Home Assistant text helper, `input_text.liberpet_label`, which LiberPet sets.
- **The photos to label** are saved in the clips folder under `_learn/`, so Home Assistant's media browser serves
  them like the clips.

How it works: every clip gives a frame every 2 seconds, cut into the two bowls (left and right of the feeder's post).
Each bowl picture gets a [CLIP](https://openai.com/research/clip) image fingerprint and a "looks like a cat" score, so
empty bowls are skipped. The cat at a bowl is the one whose labelled photos are most similar (nearest neighbours).
"Ready" means at least 8 photos per cat, and naming at least 90% of the labelled photos right when each one is left
out in turn.

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
4. **Run** it as a service, like the recorder:
   ```
   venv/bin/python -u liberpet_recognizer.py --config recognizer.json
   ```

It processes new clips once a minute. Pictures of clips the recorder deletes for space go too, except labelled ones.

`test_brain.py` checks the learning logic on made-up fingerprints: `venv/bin/python test_brain.py`.
