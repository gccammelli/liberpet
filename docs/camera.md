# Live camera (Granary Smart Camera Feeder, PLAF203)

The PETLIBRO integration doesn't provide video. The feeder's camera uses TUTK/Kalay, a peer-to-peer video
service, and the cloud API has no RTSP, HLS or WebRTC stream. This page describes a local setup that works
with stock firmware, so it can be repeated or revisited.

**Status:** working on 2026-10-15. Tested on a PLAF203 with firmware 3.1.54, hardware 1.0.7: 1080p H.264 with
AAC audio, over the home network only. The feeder, the official app and the cloud keep working as before.

## How it works

```
feeder camera ──(local network, UDP)──▶ go2rtc with PETLIBRO support ──▶ Home Assistant camera ──▶ LiberPet
```

- **[go2rtc](https://github.com/AlexxIT/go2rtc)** is the streaming tool Home Assistant uses internally.
- **[mkaand/go2rtc](https://github.com/mkaand/go2rtc)**, branch `petlibro-plaf203-v2`, is an open-source fork. It
  adds a `petlibro://` source that talks to the feeder directly on the local network and outputs a standard
  RTSP/WebRTC stream. It needs no firmware change, no cloud, and no DNS or MQTT changes.
- **Home Assistant** shows that stream as an ordinary camera entity.
- **LiberPet** plays the camera through Home Assistant's own stream (HLS), so it keeps a single connection and
  works wherever Home Assistant works. It uses the camera in the feeder's area, or the one picked in the feeder's
  Settings → Live Camera.

## What you need

- **The feeder's IP address,** fixed with a DHCP reservation in your router so it doesn't change. To find it,
  look up the feeder's MAC address (Petlibro app → feeder → Settings → device card → MAC Address) in your
  router's list of clients.
- **The feeder's UID.** This is the `camera_id` attribute in Home Assistant: Developer tools → States →
  the feeder's *Wi-Fi SSID* sensor. It's 20 characters, starting like `32UY…`. It needs a PETLIBRO integration
  version from 2026-09-22 or later, which includes [#269](https://github.com/jjjonesjr33/petlibro/pull/269).
- **A machine on the same network as the feeder** that is always on. The Home Assistant host is ideal.

## 1. Build go2rtc with PETLIBRO support

There's a Windows build in the
[petlibro-v2.0.0-beta.1 release](https://github.com/mkaand/go2rtc/releases/tag/petlibro-v2.0.0-beta.1). For
Linux, build it with Go 1.24; Go can be unpacked in any folder, so no root is needed:

```sh
git clone --depth 1 --branch petlibro-plaf203-v2 https://github.com/mkaand/go2rtc.git go2rtc-petlibro
cd go2rtc-petlibro
CGO_ENABLED=0 GOFLAGS=-buildvcs=false go build -trimpath -o go2rtc .
```

## 2. Configure it

`go2rtc.yaml`:

```yaml
streams:
  petlibro:
    - "petlibro://FEEDER_IP?uid=FEEDER_UID&audio=true&quality=hd&strict=0&reorder_ms=120&idr_grace_ms=350&ack_grace_ms=1000"
api:
  listen: ":1984"
rtsp:
  listen: ":8554"
webrtc:
  listen: ":8555"
```

- `quality=hd` gives 1920×1080; `quality=sd` gives 640×360.
- `audio=true` adds AAC audio.
- Add `verbose=1` while setting it up, to log the handshake.

## 3. Test it

```sh
./go2rtc -config go2rtc.yaml
ffmpeg -rtsp_transport tcp -i "rtsp://localhost:8554/petlibro?video=h264" -frames:v 1 -update 1 frame.jpg
```

`frame.jpg` should show the feeder's view of its bowls. The web page at `http://GO2RTC_HOST:1984/` also plays
the stream.

## 4. Keep it running

On a Linux host, a systemd service keeps it running. Example (`/etc/systemd/system/go2rtc-petlibro.service`):

```ini
[Unit]
Description=go2rtc with PETLIBRO support
After=network-online.target

[Service]
ExecStart=/opt/go2rtc-petlibro/go2rtc -config /opt/go2rtc-petlibro/go2rtc.yaml
Restart=always

[Install]
WantedBy=multi-user.target
```

## 5. Add the camera to Home Assistant

Settings → Devices & services → Add integration → **Generic Camera**:

- **Stream source:** `rtsp://GO2RTC_HOST:8554/petlibro?video=h264&audio=aac`
- **Still image:** leave it empty. Home Assistant then takes stills from the stream.
- **Advanced → RTSP transport:** TCP.

Then give the new camera **the same area as the feeder**, so LiberPet links them automatically.

If Home Assistant runs in a virtual machine on the same computer as go2rtc, use the address the VM uses to reach
that computer, and let go2rtc's RTSP listen only there. The web page (`api`) can stay on `127.0.0.1`.

## Limitations

- **It's a beta,** validated only on firmware 3.1.54. Other firmware or models may not work.
- **Short glitches:** losing several packets in a row can cause about a second of artefacts.
- **Codec changes:** changing the RTSP codec selection while streaming can stall the session. Choose the codecs
  in the URL up front.
- **No two-way audio** (Meal Call or talk).
- **Slow restarts:** after a viewing session ends, the camera can take up to about a minute before it accepts a
  new one. go2rtc and Home Assistant retry on their own, so the next view just starts more slowly.
- **No SD-card playback:** that uses another channel of the camera's protocol, which no open tool implements yet.
- **Local network only:** go2rtc must reach the feeder directly.

## Not used, and why

- **The cloud route:** the integration exposes the cloud camera credentials (`camera_auth_info`,
  `tutk_user_token`, `tutk_app_url`). No open-source client can use them yet, and using Petlibro's own TUTK
  libraries isn't an option for an open-source app.
- **[tannerln7/ha-addon-petlibro-local](https://github.com/tannerln7/ha-addon-petlibro-local)** also streams
  through go2rtc. But it replaces the feeder's cloud connection with a local MQTT broker, runs only on x86
  machines, and was tested on firmware 3.1.48. It's a fallback if the fork stops working.

## Later

- **No extra setup for anyone:** if the `petlibro://` source is merged into the main go2rtc project, Home
  Assistant's built-in go2rtc would support the feeder directly. The PETLIBRO integration could then create
  the camera entity itself from the UID it already has. Worth suggesting to both projects.
- **Local recordings:** record the stream into a folder outside Home Assistant, shared to it as network media
  storage, with a gallery in LiberPet.
