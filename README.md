# My Launcher

A minimal Android home screen, in the same spirit as the Tanjiro TV hub.

- Apps sit in a single column low on the right side of the screen, within thumb reach
- Icons are plain white with no labels or borders, and show the app's real colour while you press them
- The last button in the column (the dot grid) opens the app drawer
- Phone, Messages and Browser are added to the column the first time it starts
- An animated companion (drawn from the character sheets) lives on the home screen: he comes out of his Red Magic
  door when you come home, then wanders, games (with wins, losses and popcorn), plays guitar and writes songs, puts his
  headphones on and dances, has a burger meal, works on his laptop, scrolls his phone, or grabs a pillow and sleeps
  under a blanket. Tap him and he reacts, a little less patiently each time; the fifth tap sends him off through
  his door for a while. Tap the floor and he walks over; he checks his phone when a notification arrives
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
- **Edge lighting** (screen icon at the bottom of the drawer): while the screen is off, a notification wakes it into
  black with a soft orange glow that breathes round the edges until you wake the phone, a new song gives a purple one, and an incoming call gets a cyan pulse
  round the call screen. Needs notification access and "display over other apps"; the menu has previews.
  The glow stops when you tap the screen or press power (or after 30 minutes)
- **Add an app to the home column:** long-press it in the drawer, tap *Add to home*
- **Reorder or remove:** long-press an icon in the column
- **Close the drawer:** Back, Home, or tap empty space

## Pro Camera

Each release also has `pro-camera.apk`, a manual camera with Blackmagic-style controls:

- Resolution (8K, 4K, 1080p, 720p) and frame rate (24, 25, 30, 50, 60, 100, 120): only what your phone can
  actually record is offered; "S" speeds use the phone's slow-motion mode
- Shutter angle (video) or speed (photo), ISO, white balance presets, manual focus with distance readout
- Focus peaking, zebras, false colour, grid, stabilisation, live histogram, audio meter, timecode
- Focal-length buttons: 16, 25, 35, 50 and 78 mm (full-frame equivalent). Each uses the widest real lens
  that reaches it and zooms in; longer ones are crops of the main sensor
- Photos as JPG or RAW (DNG) + JPG; videos as H.265 where supported
- **Blackmagic remote:** the REMOTE button (top right) pairs over Bluetooth with a Pocket Cinema Camera 4K (enter the code shown
  on the camera). Record, ISO, shutter, white balance, frame rate, focus/autofocus and iris then control the camera,
  and its timecode and recording light show in the top bar. Bluetooth carries settings only, not the picture
- Works upright too: settings in two rows on top; focal lengths, photo/video, record and last capture in one column
  at the bottom right under your thumb. Upright clips are saved as
  vertical 9:16 video; the screen holds its orientation while recording
- Volume buttons work as the shutter; the (i) button shows exactly what each lens supports
- Files go to Movies/ProCamera and Pictures/ProCamera
