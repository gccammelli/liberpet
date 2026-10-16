# LiberPet recorder

Saves the feeder camera's clips into a folder that Home Assistant shows as media, for LiberPet's gallery. It needs
Python 3.10+ and ffmpeg, nothing else. It reads the stream from [go2rtc](../../docs/camera.md).

It keeps the camera stream open with a rolling buffer of the last few minutes, so each clip can start before the
thing that triggered it:

- **Motion:** compares tiny greyscale frames about twice a second, over the area and at the sensitivity you choose in
  LiberPet (see [Motion detection](#motion-detection)).
- **Feedings:** optional, from the PETLIBRO integration's *Recent Activity* sensor in Home Assistant. Its records
  carry the exact dispense time, so clips start before the dispense even though Home Assistant polls once a minute.
- **Continuous:** everything, in 5-minute clips.
- **Manual:** LiberPet's record button (under the live view) saves the last pre-roll seconds plus the next 30. It presses
  a Home Assistant button that the recorder watches, so the app still only talks to Home Assistant. Set it up once: in Home
  Assistant, **Settings › Devices & services › Helpers › Create helper › Button**, named `LiberPet record` (entity
  `input_button.liberpet_record`; another id goes in `record_button`). Without LiberPet: `kill -USR1 <pid>`.

Each clip is saved as `YYYY-MM-DD/HH-MM-SS_<reasons>_<seconds>s.mp4`, with a `.jpg` thumbnail and a `.json` note.
A clip is moved into place only when it's complete. When the folder grows past the cap, the oldest clips are
deleted. `status.json` reports usage.

## Configuration

`recorder.json`; every key is optional:

| Key | Default | |
|---|---|---|
| `stream` | `rtsp://127.0.0.1:8554/petlibro?video=h264&audio=aac` | go2rtc's RTSP address for the feeder |
| `folder` | `recordings` | where clips go: the folder you share to Home Assistant |
| `buffer_folder` | `<folder>/.buffer` | rolling buffer; put it outside the shared folder, on the same drive |
| `cap_gb` | `128` | the folder's size limit; oldest clips go first |
| `mode` | `events` | `events` or `continuous` |
| `pre_roll_s` | `10` | seconds kept before each trigger |
| `buffer_s` | `180` | how far back the buffer reaches; must cover Home Assistant's delay for feed records |
| `motion` | `true` | motion clips on or off |
| `motion_threshold` | `0.015` | share of pixels that must change (LiberPet's sensitivity replaces it) |
| `motion_pixel_delta` | `25` | change (0–255) that counts for one pixel |
| `motion_post_s` | `15` | keep recording this long after motion stops |
| `feed_post_s` | `60` | length after a feeding |
| `max_clip_s` | `300` | long events are split into clips of this length |
| `ha_url` | none | Home Assistant address, for feed clips |
| `ha_token_file` | none | a file holding a long-lived access token |
| `activity_entities` | `[]` | e.g. `["sensor.<feeder>_recent_activity"]` |
| `settings_entity` | `input_text.liberpet_recorder` | the motion settings LiberPet saves |

At about 0.7 Mbit/s (1080p), 128 GB holds roughly two weeks of continuous footage, and far longer as event clips.

## Motion detection

If the camera records almost all the time, something in view keeps changing: a window, a TV, shadows, or night-vision
noise. In LiberPet, **Feeder › Settings › Motion Detection** shows the camera picture under a grid: dim the squares to
ignore (keep the bowls and the floor in front of them), and lower the sensitivity. 50 is the default; each step of
about 12 halves or doubles how much has to change. The recorder picks up a change within seconds, no restart needed.

Set it up once: in Home Assistant, **Settings › Devices & services › Helpers › Create helper › Text**, named
`LiberPet recorder` (entity `input_text.liberpet_recorder`), and give the recorder `ha_url` and `ha_token_file`.
Without the helper, the recorder uses `motion_threshold` from `recorder.json` over the whole picture.

## Running it as a service

A systemd user service, `~/.config/systemd/user/liberpet-recorder.service`:

```ini
[Unit]
Description=LiberPet recorder (feeder camera clips)
After=go2rtc-petlibro.service
Wants=go2rtc-petlibro.service

[Service]
ExecStart=/usr/bin/python3 -u %h/.local/opt/liberpet-recorder/liberpet_recorder.py --config %h/.local/opt/liberpet-recorder/recorder.json
Restart=always
RestartSec=10

[Install]
WantedBy=default.target
```

```sh
systemctl --user enable --now liberpet-recorder
sudo loginctl enable-linger "$USER"   # keep user services running when logged out
```

## Optional: a hard size limit

`cap_gb` is enforced by the recorder itself. For a limit the operating system enforces, put the folder in its own
fixed-size filesystem: a file on the big drive, formatted and mounted at the folder's path. Home Assistant then also
shows the real size (an NFS share otherwise reports the whole drive). Keep `cap_gb` a little below its size.

```sh
sudo fallocate -l 128G /srv/liberpet-recordings.img
sudo mkfs.ext4 -q -m 0 -L liberpet /srv/liberpet-recordings.img
sudo mkdir -p RECORDINGS_FOLDER
echo '/srv/liberpet-recordings.img RECORDINGS_FOLDER ext4 loop,nofail 0 2' | sudo tee -a /etc/fstab
sudo mount RECORDINGS_FOLDER && sudo chown "$USER": RECORDINGS_FOLDER
```

`nofail` keeps the machine booting even if the image can't be mounted.

## Sharing the folder with Home Assistant

Keep the clips outside Home Assistant and share them read-only.

1. **On the recorder's machine** (NFS):

   ```sh
   sudo apt install nfs-kernel-server
   echo '/path/to/recordings HA_ADDRESS(ro,sync,no_subtree_check,all_squash,anonuid=1000,anongid=1000)' | sudo tee /etc/exports.d/liberpet.exports
   sudo exportfs -ra
   ```

   `HA_ADDRESS` is the address Home Assistant connects from. `anonuid`/`anongid` are the recorder user's IDs
   (`id -u`, `id -g`).
2. **In Home Assistant:** Settings → System → Storage → **Add network storage**:
   - **Name:** `liberpet`
   - **Usage:** Media
   - **Server:** the recorder machine's address
   - **Protocol:** NFS
   - **Remote share path:** `/path/to/recordings`

   The clips then appear under Media → My media → liberpet. LiberPet's gallery reads that folder through Home
   Assistant.

On Home Assistant OS without a Linux server, a NAS share (NFS or SMB) works the same way: point `folder` at it, or
run the recorder on the NAS.
