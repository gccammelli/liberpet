# LiberPet privacy

- **LiberPet only talks to your Home Assistant**, at the address you enter. No analytics, no ads, no other servers.
  The developer never receives any of your data.
- **Your Home Assistant access token** is stored on your phone only, encrypted with a key in the phone's secure
  hardware (Android Keystore; on iPhone, the Keychain), and never included in backups.
- **Settings** (the address, device order, appearance, which extras are on) are stored on your phone, and in your
  phone's own backup if you use it. Your Home picture, if you choose one, stays on the phone and isn't backed up.
- **Petlibro's cloud** is reached by Home Assistant, not by LiberPet: your data there is covered by Petlibro's
  privacy policy.
- **The optional recorder and recognizer** run on your own computer and keep clips, fingerprints and labels there;
  they too talk only to your Home Assistant. Nothing is uploaded anywhere.
- **The demo** uses a made-up home bundled in the app. It sends nothing anywhere.

Questions or problems: [open an issue](https://github.com/gccammelli/liberpet/issues).
