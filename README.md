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
- **Search** (magnifier, first icon at the bottom of the drawer): type to filter apps; Enter opens the first match,
  *Web* searches Google for what you typed
- **Organiser** (slider icon at the bottom of the drawer): sort by Type, Most used or Newest.
  Most used asks once for usage access in Settings.
- **Hide an app:** long-press it in the drawer, tap *Hide*. The eye icon shows hidden apps; tap one to bring it back
- **Drawer background** (photo icon at the bottom of the drawer): choose a photo from your phone, or go back to the wallpaper
- **Companion on/off** (person icon at the bottom of the drawer): highlighted when he's on
- **Edge lighting** (screen icon at the bottom of the drawer): while the screen is off, a notification wakes it into
  black with a soft orange glow that breathes round the edges until you wake the phone, a new song gives a purple one, and an incoming call gets a cyan pulse
  round the call screen. Needs notification access and "display over other apps"; the menu has previews.
  The glow stops when you tap the screen or press power (or after 30 minutes).
  The phone's RGB lights only run while the screen is off, so by default the border breathes twice and then the
  screen switches off and the RGB lights take over (needs "Screen off when glow stops"). The menu sets 1, 2 or 3
  breaths, or keeps the border until you wake the phone
- **Trash** (bin icon at the bottom of the drawer): everything in Android's trash, with thumbnails and days left
  (Android deletes items for good after 30 days). Tap items to select, then *Restore* or *Delete forever*, or *Empty*.
  Shows everything with "all files access", or just photos, videos and music without it. From any app's share menu,
  *Move to trash* sends a photo, video or file there
- **Add an app to the home column:** long-press it in the drawer, tap *Add to home*
- **Reorder or remove:** long-press an icon in the column
- **Close the drawer:** Back, Home, or tap empty space

## Pro Camera

Each release also has `pro-camera.apk`, a manual camera with Blackmagic-style controls:

- Resolution (8K, 4K, 1080p, 720p) and frame rate (24, 25, 30, 50, 60, 100, 120): only what your phone can
  actually record is offered; "S" speeds use the phone's slow-motion mode
- Frame rates up to 240 fps
- **Slow motion** (in the frame-rate menu): shoot at 120 or 240 fps and play back at 24, 30 or 60 fps (e.g. 240→24 is 10x slower, 120→60 is 2x and extra smooth), no sound
- White balance slider from 2500K to 6500K, with 4000K as neutral daylight (lower is cooler, higher warmer), or AUTO
- Shutter angle (video) or speed (photo), ISO, manual focus with distance readout
- Focus peaking, zebras and false colour (grouped behind one button on the left), grid, stabilisation, live histogram, audio meter, timecode
- Flash (above the front-camera button): photos off / on / auto, video a steady light. On the front camera the screen flashes
  white for photos and becomes a white ring light for video
- Focal-length buttons: 16, 25, 35, 50 and 78 mm (full-frame equivalent), plus the front (selfie) camera button under the (i) button on the left. Each uses the widest real lens
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

## Sanka

Each release also has `sanka.apk`, a locked place for hidden photos and videos:

- **Bottom bar:** Order, Lock, Hide and Settings sit at the bottom, within thumb reach. *Settings* is a page of its
  own (folders, lock, privacy), with switches for fingerprint unlock, dot numbers and screenshots
- **Folder layout** (Settings, a sheet over your folders so you see each choice straight away): Grid, Banners (wide strips), Mosaic (big and small tiles), Collage (prints on a
  table), Stacks (each folder a fanned pile of its pictures) or List
- **Folder background** (in the same sheet): any picture in Sanka behind the folders, with Blur and Darken sliders
  that change it live
- **Allow screenshots** (Settings): after your fingerprint (or PIN), screenshots and screen recording work until you
  leave Sanka; a banner shows while they're allowed, with *Block now*
- **Lock screen:** matt black with grain and only the Sanka mark. Ten dots on the right at thumb height stand for
  1, 2, 3 … 9, 0 from top to bottom; tap your PIN on them (no keypad, no count shown; it opens as soon as the PIN is
  right). The point near the bottom sits over the fingerprint sensor: rest your finger there and the circuit lights
  up from the point to every dot. Sanka listens for the finger without a pop-up; only the phone's own sensor icon
  shows. *Settings → Line up the fingerprint point* lets you drag the point exactly onto your sensor, and the numbers
  beside the dots can be switched off
- Locks with a PIN (4–8 digits) or fingerprint, and locks again as soon as you leave it.
  Screenshots and the recent-apps preview are blocked
- **Hide photos & videos:** choose them from your gallery; they're copied in, encrypted (AES-256, key kept in the
  phone's secure hardware), and Android asks to remove the originals from the gallery
- View photos (pinch / double-tap to zoom) and play videos inside the app; swipe between them
- **Unhide** puts items back in the gallery in their original folder; **Delete** removes them for good
- **Folders:** + New folder on the main screen; each folder's tile shows a cover picture from inside it (the first
  thing you added, or pick one with *Change cover*). Long-press a folder to rename, change its cover or delete it
  (deleting a folder deletes everything in it, for good)
- **Order** (lines icon at the top): *By date created*, meaning when each photo or video file was made (read from
  inside the file or from a date in its name, never the day it came into Sanka; files that carry no date at all
  are listed last under their own heading), *Newest added to Sanka first*, or *By format*: vertical, then
  horizontal, then square, each newest first, or *By similarity*: sets of look-alike pictures (bursts, retakes,
  edited copies) first, then the rest grouped by what's in them (beach & sea, food & drink, people, nature, pets,
  nights out, screenshots…), each group with *Make folder*. The grouping is worked out on the phone by an image
  recognition model built into Sanka; Sanka has no internet permission, so nothing is ever sent anywhere. Folders
  follow the same order
- Pictures show as a collage that keeps each one's shape: tall ones narrow, wide ones wide, every row the same height
- When you hide new pictures you choose the folder (or none); *Add* inside a folder hides straight into it
- **Share into Sanka:** in any app (another vault, Files, the gallery) select photos or videos, tap Share and choose
  *Hide in Sanka*. After unlocking you pick the folder; the other app keeps its copies until you delete them there
- The hide screen has album chips and *Select all*, and can keep the albums pictures came from as Sanka folders
  (handy for moving a whole collection over from another vault app after exporting it to the gallery)
- **Duplicates:** when something you add is identical to a picture already in Sanka, Sanka asks: *Look at them*
  (each set side by side with name, size, folder and date; tap to choose which to keep, hold to view), *Keep one of
  each*, or *Keep everything*. ⋯ › Find duplicates checks the whole vault any time
- Lock screen: a slow, moving violet/blue glow, or one of your own pictures blurred (⋯ › Lock screen background)
- **Share:** from a picture or a selection, send to any app (WhatsApp, mail, …). Sanka unlocks a temporary copy only
  that app can read and clears it within the hour; what you send is no longer protected by Sanka
- Long-press to select several, then *Share*, *Move*, *Unhide* or *Delete* (or *Cover* for one picture in a folder).
  ⋯ menu: change PIN, fingerprint on/off
- Hidden files live only on this phone: uninstalling Sanka deletes them, so unhide anything you want to keep first

## Letra

Each release also has `letra.apk`, a sandstone notebook with a mode for each kind of writing:

- **Note:** formatted text: bold, italic, headings, bullets; *To list* turns the lines into a checklist
- **List:** checklists; ticked items move to *Completed*, with uncheck all, clear done and A–Z
- **Song:** sections (verse, chorus, bridge… or repeat one), chords placed over the exact syllable (tap *Chords*,
  then a word), key / transpose / capo / tempo with a click, syllable counts per line, coloured dots for lines that
  rhyme, and rhyme suggestions in English and Spanish that work offline
- **Recording in Song mode:** *Record* captures a take and writes out the words as you sing (Android 13+, using the
  phone's speech recognition; choose English or Español). Flag moments, then play takes back, tap a line to jump
  there, and *Add words to the song*
- **Script:** screenplay elements (scene, action, character, parenthetical, dialogue, transition); Enter moves to the
  element that usually comes next, and the names you've used become one-tap chips
- **Share:** songs as a chord-sheet PDF, lyrics with or without chords, a recording, or a Letra file; scripts as a
  screenplay PDF, Fountain, plain text or a scene list; notes and lists as text or PDF
