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
- **Logs start when Home Assistant starts recording:** Home Assistant's recorder keeps 10 days by default, and daily totals beyond that come from its long-term statistics. Two drinks within the same minute show as one, because the integration polls every minute.
- **Rooms:** these are Home Assistant areas. Assign each device an area there.

## Connecting

Enter your Home Assistant address and a long-lived access token (your profile → Security). The token is stored encrypted with the Android Keystore.

## Building

```
./gradlew :app:assembleDebug
```

LiberPet is not affiliated with PETLIBRO. PETLIBRO is a trademark of its owner. The device pictures are simple drawings, not Petlibro's photos.
