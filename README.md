# LiberPet

An open-source app for PETLIBRO feeders and fountains that talks only to **your own Home Assistant**, never to Petlibro's cloud.
Its screens follow the official Petlibro app's layout, so it feels familiar.

Home Assistant holds the Petlibro login through the community
[PETLIBRO integration](https://github.com/jjjonesjr33/petlibro) (install it from HACS).
Petlibro allows one login per account, so the official app gets signed out once Home Assistant is logged in. LiberPet replaces it.

## What works

Tested with a Granary Smart Camera Feeder (PLAF203) and Dockstream Smart Fountains (PLWF105).

- **Home:** every feeder and fountain, with problems flagged (filter or cleaning overdue, low water, offline).
- **Feeder:**
  - Feed Now
  - today's amount, feeding count and schedule
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
- **Care:** each pet's age, breed, weight, linked devices, and today's food and water totals with 7-day trends.

## What doesn't

- **Camera live view, recordings, Meal Call and video cloud:** the camera uses Petlibro's proprietary peer-to-peer service, which Home Assistant can't relay.
- **Logs:** with the integration's **Recent Activity** sensor (a small addition, proposed upstream), logs come from Petlibro's own records, with exact times and the same wording as the official app. Without it, LiberPet builds logs from Home Assistant's recorder: they start when Home Assistant starts recording, times can lag by up to a few minutes, and two drinks within the same minute show as one.
- **Rooms:** these are Home Assistant areas. Assign each device an area there.
- **Per-pet food and water:** Care shows the totals of each pet's linked devices. Petlibro only splits them per pet with RFID models.

## Extras

- **Device order:** long-press a device card on Home and drag it to rearrange.
- **Home picture:** tap the picture button on Home to use your own photo. It stays on the phone.
- **Appearance:** Me → Appearance switches between System, Light and Dark.

## Connecting

Enter your Home Assistant address and a long-lived access token (your profile → Security). The token is stored encrypted with the Android Keystore.

## Building

```
./gradlew :app:assembleDebug
```

LiberPet is not affiliated with PETLIBRO. PETLIBRO is a trademark of its owner. The device pictures are simple drawings, not Petlibro's photos.
