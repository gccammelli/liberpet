# LiberPet

An open-source app for PETLIBRO feeders and fountains. Its screens follow the official Petlibro app's layout,
so it feels familiar.

## How it works

LiberPet talks only to **your Home Assistant**. Home Assistant does the rest:

```
LiberPet ──▶ your Home Assistant ──▶ Petlibro's cloud ──▶ feeder / fountains   (status, logs, controls)
                       │
                       └──▶ go2rtc on your network ──▶ feeder camera            (live view, local only)
```

- **Device data and controls need Petlibro's cloud.** Home Assistant reaches it through the community
  [PETLIBRO integration](https://github.com/jjjonesjr33/petlibro) (install it from HACS). The devices stay
  connected to Petlibro's cloud, and every status update, log and command goes through it. If the cloud or your
  internet is down, Home Assistant can't read or control the devices. Scheduled meals still dispense, because
  the schedule is stored on the feeder.
- **The camera is local.** Its video goes straight from the feeder to go2rtc on your network, then to Home
  Assistant, and never through Petlibro's servers. Setup: [docs/camera.md](docs/camera.md).
- **Pet photos** come through Home Assistant too, from the integration's image entities.

Petlibro allows one login per account. Give Home Assistant a second Petlibro account and share your devices
with it, so the official app can stay signed in on your main account.

## What works

Tested with a Granary Smart Camera Feeder (PLAF203) and Dockstream Smart Fountains (PLWF105).

- **Home:** every feeder and fountain, with problems flagged (filter or cleaning overdue, low water, offline).
- **Feeder:**
  - live camera, muted by default, with full screen; snapshots to your photos, and a record button (with the recorder)
  - recordings gallery (optional; clips by motion or feeding, from a capped folder outside Home Assistant)
  - cat recognition (optional): you label a few short clips, and it names who ate from each bowl in every clip, on your own machine ([tools/recognizer](tools/recognizer/README.md))
  - Feed Now
  - today's amount, feeding count and schedule, with today-only skips
  - schedule list, plus adding, editing, deleting and switching plans on or off
  - desiccant status and reset
  - indicator light
  - feeding logs
  - firmware version
- **Fountain:**
  - today's intake, drinking count and time
  - water level, filter and cleaning status, with resets and cycles
  - flow type (continuous or timed)
  - daily hydration goal and calibration
  - indicator light
  - drinking logs
  - water intake history by day, week, month and year
- **Care:** each pet's age, breed and weight, water with 7-day trends, and (with cat recognition) Cat Insights: visits,
  time eating, favourite bowl and eating times for each cat. Without it, the feeder's food totals.

## What doesn't

- **Meal Call, two-way audio, Petlibro's video cloud and SD-card playback:** no open implementation exists
  yet. Use the official app for these.
- **Exact logs** need the integration's **Recent Activity** sensor, a small addition proposed upstream. Without
  it, LiberPet builds logs from Home Assistant's history, so times can lag by up to a few minutes.
- **Per-pet food and water:** Care shows the totals of each pet's linked devices. Petlibro only splits them per
  pet with RFID models.

## Setup

1. **In Home Assistant:** install the PETLIBRO integration, and give each device an area (its room in LiberPet).
2. **Optional, live camera and recordings:** follow [docs/camera.md](docs/camera.md). LiberPet uses the camera in
   the feeder's area, or the one picked in the feeder's Settings → Live Camera.
3. **In LiberPet:** enter your Home Assistant address and a long-lived access token (your Home Assistant
   profile → Security). The token is stored encrypted with the Android Keystore.

## Extras

- **Device order:** long-press a device card on Home and drag it to rearrange.
- **Home picture:** tap the picture button on Home to use your own photo. It stays on the phone.
- **Appearance:** Me → Appearance switches between System, Light and Dark.

## iPhone (beta)

LiberPet builds for iPhone too, from the same code. The repository's **Build** workflow (run it by hand, or push a
`v*` tag) produces an unsigned `liberpet.ipa`. Install it with [SideStore](https://sidestore.io) (or AltStore), which
signs it with your own free Apple ID. It's checked in the iOS simulator; it hasn't been tried on a real iPhone yet.
Live view and clips play with iOS's own video player.

## Building

```
./gradlew :app:assembleDebug
```

LiberPet is not affiliated with PETLIBRO. PETLIBRO is a trademark of its owner. The device pictures are simple
drawings, not Petlibro's photos.
