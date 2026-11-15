# Live camera (Granary Smart Camera Feeder, PLAF203)

The PETLIBRO integration provides a camera entity starting with version 1.4. Its video travels over your local
network using a separate go2rtc process; feeder controls still use the Petlibro cloud. This page also preserves
the standalone go2rtc setup, which works with stock firmware.

**Status:** the standalone setup was tested on 2026-10-15 on a PLAF203 with firmware 3.1.54, hardware 1.0.7:
1080p H.264 with AAC audio. The integration's camera (v1.4) was tested on 2026-11-15 on the same feeder, with no
extra settings: 1080p H.264 at 25 fps with AAC audio, through the same Home Assistant HLS stream LiberPet plays.

## Recommended: PETLIBRO integration camera

1. Update the [PETLIBRO integration](https://github.com/jjjonesjr33/petlibro) to version 1.4 or later and
   restart Home Assistant. A PLAF203 on the account should get a camera entity automatically.
2. Open that camera in Home Assistant. If it cannot find the feeder, reserve the feeder's IP in your router and
   enter it under Settings → Devices & services → PETLIBRO → Configure → Integration settings →
   **Camera Feeder IP Address**. Home Assistant must be able to reach UDP port 32761 on the feeder.
3. In LiberPet, open the feeder. The app selects a camera in the same Home Assistant area, or you can choose it
   under the feeder's Settings → Live Camera.

The integration downloads a pinned, checksum-verified go2rtc build and runs it on the Home Assistant host.
It can discover the feeder on the local subnet, use a device tracker reporting its MAC, or use the configured IP.
See the [integration's camera guide](https://github.com/jjjonesjr33/petlibro/blob/v1.4/docs/camera-stream.md)
for supported architectures, settings and troubleshooting. The helper listens on the host's loopback interface;
the integration offers a **Custom Camera Stream URL** setting if you prefer your own bridge.

The feeder allows roughly one live viewer session at a time, so opening a stream in one app can interrupt another.
If you also run the standalone setup below (for example for the recorder), opening the integration's camera takes
the feeder away from it until Home Assistant closes the idle stream, about two minutes in testing.
The firmware may still require its normal cloud connection to accept local viewing sessions.

## How it works

```
feeder camera ──(local network, UDP)──▶ go2rtc with PETLIBRO support ──▶ Home Assistant camera ──▶ LiberPet
```

- **[go2rtc](https://github.com/AlexxIT/go2rtc)** is the streaming tool Home Assistant uses internally.
- The integration's automatic camera uses [tannerln7/go2rtc](https://github.com/tannerln7/go2rtc). The standalone
  instructions below use **[mkaand/go2rtc](https://github.com/mkaand/go2rtc)**, branch `petlibro-plaf203-v2`. It
  adds a `petlibro://` source that talks to the feeder directly on the local network and outputs a standard
  RTSP/WebRTC stream. It needs no firmware change, no cloud, and no DNS or MQTT changes.
- **Home Assistant** shows that stream as an ordinary camera entity.
- **LiberPet** plays the camera through Home Assistant's own stream (HLS), so it keeps a single connection and
  works wherever Home Assistant works. It uses the camera in the feeder's area, or the one picked in the feeder's
  Settings → Live Camera.

## Standalone setup: what you need

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

## Recordings (optional)

LiberPet's gallery plays clips that its recorder saves outside Home Assistant:

```
go2rtc ──▶ recorder ──▶ folder (capped, e.g. 128 GB) ──NFS, read-only──▶ Home Assistant media ──▶ LiberPet gallery
```

- **Clips by event (default):** motion, plus feedings from the integration's *Recent Activity* sensor, each starting
  10 seconds before the trigger. Continuous recording is an option.
- **Size cap:** the oldest clips are deleted when the folder passes it.
- **Outside Home Assistant:** the folder lives on the recorder's machine or a NAS. Home Assistant only reads it, as
  network media storage named `liberpet`.
- **In LiberPet:** the feeder's camera card → the playback or gallery icon opens a day-by-day gallery. It shows a
  timeline of the day with feedings marked, then thumbnails with why each clip was recorded, and plays clips with a
  scrubber.

Setup and every option: [tools/recorder/README.md](../tools/recorder/README.md). The recorder needs an accessible
stream URL; the integration's managed go2rtc listens on loopback, so an external recorder cannot directly use its
RTSP endpoint. With the recorder running, live view also starts at once, because the camera session stays open.

## Standalone setup limitations

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
- **[Petlibro Local](https://github.com/tannerln7/Petlibro-Home-Assistant)** provides local feeder control as well
  as camera streaming. Its experimental installation redirects the feeder's MQTT connection to a local broker and
  installs a feeder-side State Agent. LiberPet does not currently support its MQTT feeder entities for controls.

## Later

- If the `petlibro://` source is merged into the main go2rtc project, Home Assistant's built-in go2rtc could
  potentially support the feeder without a separate helper.
