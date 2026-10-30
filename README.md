# My Launcher

A minimal Android home screen, in the same spirit as the Tanjiro TV hub.

- Apps sit in a single column low on the right side of the screen, within thumb reach
- Icons are plain white with no labels or borders, and show the app's real colour while you press them
- The last button in the column (the dot grid) opens the app drawer
- Phone, Messages and Browser are added to the column the first time it starts
- An animated companion lives on the home screen: he walks in through a door when you come home,
  wanders, sits, games, snacks and naps. Tap near him and he walks over
- No dock, widgets, search or clock; the phone's own wallpaper shows behind it

## Install

Every push to `main` builds a new APK. On the phone, open **Releases** on this repo, tap the newest
one, download `my-launcher.apk` and open it (allow "install unknown apps" for your browser the first time).
Then press Home and choose **My Launcher**, or pick it under Settings → Apps → Default apps → Home app.

New builds install straight over the old one; your home column is kept.

## Using it

- **Drawer:** pages of 6 × 8 apps; swipe left and right
- **Organiser** (slider icon at the bottom of the drawer): sort by Type, Most used or Newest.
  Most used asks once for usage access in Settings.
- **Hide an app:** long-press it in the drawer, tap *Hide*. The eye icon shows hidden apps; tap one to bring it back
- **Drawer background** (photo icon at the bottom of the drawer): choose a photo from your phone, or go back to the wallpaper
- **Companion on/off** (person icon at the bottom of the drawer): highlighted when he's on
- **Add an app to the home column:** long-press it in the drawer, tap *Add to home*
- **Reorder or remove:** long-press an icon in the column
- **Close the drawer:** Back, Home, or tap empty space

## Pro Camera

Each release also has `pro-camera.apk`, a manual camera with Blackmagic-style controls:

- Resolution (8K, 4K, 1080p, 720p) and frame rate (24, 25, 30, 50, 60, 100, 120): only what your phone can
  actually record is offered; "S" speeds use the phone's slow-motion mode
- Shutter angle (video) or speed (photo), ISO, white balance presets, manual focus with distance readout
- Focus peaking, zebras, false colour, grid, stabilisation, live histogram, audio meter, timecode
- Lens buttons for each back camera, plus a 2× crop of the main sensor
- Photos as JPG or RAW (DNG) + JPG; videos as H.265 where supported
- Volume buttons work as the shutter; the (i) button shows exactly what each lens supports
- Files go to Movies/ProCamera and Pictures/ProCamera
