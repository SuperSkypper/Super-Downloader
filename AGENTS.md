# Super Downloader

Android app that receives a link from X/Twitter (shared or pasted), downloads the
video at the chosen quality and offers the file in the share sheet.

Visible name on Android: **Downloader** (`app_name`). The `applicationId` stays
`app.skypper.superdownloader` — changing it installs a new app, without prefs
or SAF grants. The destination folder also stays `Download/Super Downloader`.

## Screens

A single page, following the Super Capture design (the project's UI reference):

- A single page. Top to bottom: `Download Folder:` bar (opens the SAF folder
  picker), status block, Share button (only with the download finished),
  History (takes the rest of the screen), Quality + gear, link bar, Download/Cancel
  button at the bottom.
- There is no Settings tab and no template system: there is only one folder and the
  gear stores the **share quality** (see below).

Everything in English in the UI, including hints.

## Files

- `MainActivity.kt` — the two tabs, the tab bar (overlay with hairline, `accentDim`
  outlined indicator), the download, the share and the History.
- `Palette.kt` — the same class as Super Capture: resolves `colors.xml` and offers
  `alpha()`/`roundRect()`; no `Int` constant in the companion.
- `Models.kt` — `Quality`/`QualityPreset` (WhatsApp, Highest Quality, Custom +
  the 4K…144p ladder) and `MediaItem` with the post variants.
- `Resolver.kt` — link -> list of MP4s with the variants (the tweet's `master.m3u8`).
- `Downloader.kt` — writes to the chosen SAF location or to `Download/Super Downloader`.
- `Transcode.kt` — recompresses when the WhatsApp preset goes over 62 MB.
- `Thumbs.kt` — first frame of the video, square, for the History row.
- `Prefs.kt` — destination, preset, Custom height, automatic toggle and the History
  (10 items: link + file uri + thumbnail path).

## Status bar (states)

Exactly five, and nothing else writes to it:

- `Waiting for Download`
- `Downloading`
- `Compressing`
- `Cancelling`
- `Download Complete`

The detail line, inside the block and below the title, shows whatever changes:
`78% · 19,9 MB of 25,4 MB · 5,5 MB left`, the limit during compression, the summary
at the end, or the error message in red. Things that are NOT state (pasted link,
chosen quality, copied link) do not touch the status.

## Share quality (the gear)

The preset in QUALITY applies to the link pasted into the field; the gear
(`ShareQualityDialog`) defines the quality used when the app is opened from the
system share sheet. `prefs.qualityFor(fromShare)` decides, and
`fromShare` comes from the intent's `ACTION_SEND`. Off = uses the screen preset.
Turning the toggle on with nothing chosen inherits the screen preset, so the state
is not ambiguous (no chip selected).

Verified on the device, same video, two paths: `ACTION_SEND` with share
`Highest Quality` produced `angieverses_..._2114529898609930240.mp4` (19,9 MB,
720x1280, no recompression) while the main screen was on WhatsApp; the
same link typed into the field produced `..._whatsapp.mp4`.
The panel uses the app's language (theme background, toggle outline, 34dp chips)
and **two borders**, not three: panel + toggle row.

## Design (the Super Capture one)

- Palette in `res/values/colors.xml`, faithful copy: `bg #0B0D10`, `surface #14181F`,
  `surface_variant #1C222B`, `border #2A323D`, `text #E6EDF3`,
  `text_muted #8B98A8`, `accent #58A6FF`, `accent_dim #1F3A5F`,
  `on_accent #08121F`, `danger #F85149`, `warn #D29922`, `ok #3FB950`.
- Theme `Theme.SuperDownloader` = `Theme.Material3.Dark.NoActionBar`.
- Page with a `10dp` margin; loose section title (12sp, ALL CAPS, bold,
  `letterSpacing 0.09`, muted, `padding(10,2,10,6)`, 6dp below) above the card;
  card `bg_card` (surface, 12dp, 1dp border, 6dp padding); row in
  `rowSlot` (10dp horizontal, 4dp vertical, 42dp child); 10dp between blocks.
- Status block: 54dp tall, 15sp centered title, with the detail line
  below it when there is something to say.
- History: 72dp row when it has a thumbnail (64dp image, left
  corner) and 42dp when it does not; the card grows from the bottom up.
- Chip: 34dp, 12dp radius; selected = full `accent` with `on_accent` text;
  the others = `bg_action` with muted text.
- Link bar = Super Capture's `timeLogBar`: 42dp field with `bg_field`,
  14sp, 12dp internal padding; 88dp accent button beside it with glyph +
  word (fixed width: `WRAP_CONTENT` does not add up the text padding).

## Copy: Title Case in labels, sentence case in descriptions

User rule: **everything that is a label has the first letter of every word in
uppercase**; description, hint and error message stay in sentence case.

- Labels: `Paste`, `Paste Link Here` (placeholder), `Download`, `Share`,
  `Cancel`, `Try Again`, `Automatic Quality`, `Download Folder`, `No History`,
  and the states `Waiting For Download` / `Download Complete`.
- Descriptions: card hints, `Reading the link…`, `connecting…`, error
  messages, and the status detail line.
- No visible string in Portuguese: the messages from `Downloader`,
  `Resolver` and `Transcode` appear in the status detail line.

## Build

```bash
./build.bat assembleDebug
```

- JDK 21 Temurin, AGP 8.7.3, Kotlin 2.1.0, compileSdk 35, minSdk 29.
- `build.bat` requires `JAVA_HOME` and `ANDROID_HOME` in the environment and builds from
  its own folder. For machine-specific fixed paths, use `build.local.bat` (outside
  version control) — that is what runs in day-to-day development.
- If the project lives in a synced folder (Google Drive, OneDrive), point
  `SD_INIT_SCRIPT` to an init script that moves `buildDir` outside of it: the
  resource merger fails with `AccessDeniedException` on the intermediate files.
- Default APK: `app/build/outputs/apk/debug/app-debug.apk` (or the path defined
  by the init script).
- `ffmpeg-kit` comes from `dev.ffmpegkit-maintained:ffmpeg-kit-min-gpl` (the
  `com.arthenica` one left Maven Central in 2025).

## Testing on the device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://x.com/<user>/status/<id>" \
  -n app.skypper.superdownloader/.MainActivity
```

`Automatic Quality` on downloads right away; off, the app loads the link and
waits for the `Download` tap (the status stays at `Waiting for Download`).
File at `/sdcard/Download/Super Downloader/<usuario>_<mediaId>_<preset>.mp4`.

## WhatsApp limit (it is not 16 MB)

The official FAQ says **64 MB** (slow connection) / 100 MB (fast) for video sent
as media, capped at 720p; a document goes up to 2 GB without recompression. The app uses
a **62 MB** budget. Third-party blogs repeating "16 MB" refer to photo and
status, not video — with the wrong number the app was recompressing video that would have fit.

## Audit (code state)

Done with a static sweep; what was left out:

- No unused private function, no unused public member, no orphan drawable,
  no unused import, no TODO/debug log.
- Removed: `cardParams`, `Dest.display`, `bestFor`/`bestHeight`/`maxHeight`,
  `clearDest`, six `Palette` accessors and five colors from `colors.xml`,
  seven drawables (`bg_chip_selected`, `bg_pill*`, `ic_download`, `ic_link`,
  `ic_retry`, `progress_horizontal`).

Real bugs the audit found (and that the green build did not show):

- **The `Download Folder:` bar opened Share Quality**, not the folder picker:
  a mass replace swapped the listener of both. `folderLauncher` ended up with
  no callers — the symptom that gave it away.
- **`fromShare` read `Activity.intent`**, which `onNewIntent` does NOT update: the
  second share was classified with the action from the first launch. Now the
  action comes as a parameter of `handle(url, isShare)`.
- **The copy into the cache during transcode was never deleted**: every compression
  left the original (dozens of MB) in `cacheDir/src`.

## Known pitfalls

- The file name uses the **media id**, not the tweet id — they are different on X
  (`melt_simp_2076784277404991489.mp4` for the tweet `2114510211444527226`).
- `minSdk 29` is intentional: `MediaStore.Downloads` + `IS_PENDING` do away with the
  storage permission and prevent a half-written file in the gallery.
- `launchMode=singleTask`: a second link arrives in `onNewIntent`, which cancels the
  previous download and queues on the same single-threaded executor.
- **Iterating `childCount` of a mixed row breaks the app.** `paintChips` did
  `getChildAt(i) as TextView` on every child of the quality row; when
  the gear (FrameLayout) was added at its end, the launch died with
  `ClassCastException: FrameLayout cannot be cast to TextView`. Iterate the model
  indices (`Quality.ALL.indices`) and use `as?`.
- **A `setDetail` that calls itself kills the app at launch** (`StackOverflowError`,
  with no warning from the build). A regex that replaced `detailView.text = x` with
  `setDetail(x)` also rewrote the body of the helper itself. After any sweep like
  that, read the helper body before trusting it.
- **`uiautomator dump` reports `bounds="[0,0][0,0]"`** for the tab bar label and
  for the action button. That is a limitation of the dump, not a broken layout: confirm with
  `screencap` before "fixing" it.
- **`WRAP_CONTENT` on a horizontal `LinearLayout` does not add up the text padding**:
  a button with `WRAP_CONTENT` closed 30dp short of the page margin. A fixed width
  in dp solves it.
- The share sheet takes over the screen. When `mCurrentFocus` answers with another package
  (WhatsApp `ExternalShareAlias`), the user is really sharing:
  check by artifact instead of continuing to tap the screen.
- **X only publishes a few heights.** In a 720x1280 post only 320/480/720 exist in the
  `master.m3u8`; 4K/1440p/1080p do not exist. The Custom ladder cuts at the post's
  maximum (`Quality.ladderFor`) and `Downloader.pick` falls back to the largest available.
- **x264 does not deliver the requested bitrate.** A single pass asking for 2,500k on an 85 s
  video produced 26,5 MB against a 15 MB budget. `Transcode` measures the file and does
  a second pass corrected by the real size.
