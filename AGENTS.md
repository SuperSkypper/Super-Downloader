# Super Downloader

Android app that receives an X/Twitter link (shared or pasted), downloads the video
at the chosen quality and offers the file in the system share sheet.

Visible name on Android: **Downloader** (`app_name`). The `applicationId` stays
`app.skypper.superdownloader`: changing it installs a second app, without prefs or
SAF grants. The destination folder also stays `Download/Super Downloader`.

## Screens

One page, in the visual language of Super Capture (the UI reference for this app).
Top to bottom:

- `Download Folder:` bar, which opens the SAF folder picker.
- Status block, with the state title and a detail line.
- Share button, disabled until a download finishes, with the gear beside it.
- History, which takes the remaining height and scrolls.
- Templates row, plus the Custom ladder when Custom is selected.
- Link bar with the Paste button.
- Download or Cancel button at the bottom.

There is no Settings tab and no folder template system: there is one folder, and the
gear stores a separate quality for share-sheet downloads (see below).

Everything visible is in English, hints included.

## Files

- `MainActivity.kt`, the whole page: layout, download, share, History.
- `Palette.kt`, resolves `colors.xml` and offers `alpha()` and `roundRect()`. No `Int`
  constants in the companion.
- `Models.kt`, `Quality` and `QualityPreset` (WhatsApp, Audio Only, Highest Quality,
  Custom and the 4K to 144p ladder), plus `MediaItem` with the post variants.
- `Resolver.kt`, link to a list of MP4 variants, via vxtwitter then fxtwitter.
- `Downloader.kt`, writes through `MediaStore.Downloads` or into the chosen SAF tree.
- `Transcode.kt`, the re-encode pipeline: bitrate budget, hardware first, x264
  fallback, audio extraction.
- `HardwareTranscode.kt`, video transcode with `MediaCodec` (decoder surface straight
  into the encoder).
- `ProgressStrip.kt`, the progress bar drawn by hand inside the status block.
- `Thumbs.kt`, first frame of the video, square, for the History row.
- `Prefs.kt`, folder, template, Custom height, automatic switch, share quality and
  the History (10 items: link, file uri, thumbnail path).

## Status block

Exactly five states, and nothing else writes to it:

- `Waiting For Download`
- `Downloading`
- `Compressing`
- `Cancelling`
- `Download Complete`

The detail line, inside the block below the title, carries whatever changes:
`78% • 19.9 MB of 25.4 MB • 5.5 MB left`, the size limit during compression plus the
time left, the final summary, or an error in red. Things that are not state (link
pasted, quality picked, link copied) never touch the status.

## Share quality (the gear)

The template selected in TEMPLATES applies to a link pasted into the field. The gear
(`ShareQualityDialog`) defines the quality used when the app is opened from the
system share sheet, because a video you are sharing usually wants a different
quality than a link you are saving. `prefs.qualityFor(fromShare)` decides, and
`fromShare` comes from the intent action. Off means both paths use the screen
template. Turning the switch on with nothing chosen inherits the screen template, so
no ambiguous state with zero chips selected.

The panel uses the app's own language: theme background, toggle outline, 34dp chips
and **two borders**, not three (panel plus toggle row).

## Design tokens

- Palette in `res/values/colors.xml`: `bg #0B0D10`, `surface #14181F`,
  `surface_variant #1C222B`, `border #2A323D`, `text #E6EDF3`,
  `text_muted #8B98A8`, `accent #58A6FF`, `accent_dim #1F3A5F`,
  `on_accent #08121F`, `danger #F85149`, `warn #D29922`, `ok #3FB950`.
- Theme `Theme.SuperDownloader` derives from `Theme.Material3.Dark.NoActionBar`, so
  DayNight never turns the app light.
- Page margin `10dp`. Section title loose above the card: 12sp, all caps, bold,
  `letterSpacing 0.09`, muted, `padding(10,2,10,6)`. Card `bg_card` (surface, 12dp
  radius, 1dp border, 6dp padding). Row in `rowSlot` (10dp horizontal, 4dp vertical,
  42dp child). 10dp between blocks.
- Status block 54dp tall, 15sp centered title, detail line under it.
- History row 72dp with a thumbnail (64dp image, left corner) and 42dp without it.
- Chip 34dp, 12dp radius. Selected is full `accent` with `on_accent` text, the rest
  `bg_action` with muted text.
- Link bar follows Super Capture's `timeLogBar`: 42dp field with `bg_field`, 14sp,
  12dp internal padding, and a fixed width accent button beside it with glyph plus
  word.

## Copy rules

- Labels use Title Case: `Paste`, `Paste Link Here`, `Download`, `Share`, `Cancel`,
  `Try Again`, `Automatic Quality`, `Download Folder`, `No History`,
  `Waiting For Download`, `Download Complete`.
- Descriptions, hints and error messages use sentence case: card hints,
  `Reading the link…`, `connecting…`, status detail lines.
- No Portuguese anywhere in the UI. Messages raised by `Downloader`, `Resolver` and
  `Transcode` are English and surface in the status detail line.

## Build

```bash
./build.bat assembleDebug
```

- JDK 21 Temurin, AGP 8.7.3, Kotlin 2.1.0, compileSdk 35, minSdk 29.
- `build.bat` is the portable script: it needs `JAVA_HOME` and `ANDROID_HOME` and
  builds from its own folder. `build.local.bat` is the machine-specific wrapper with
  fixed paths, kept out of version control, and is what day-to-day development calls.
- In a synced folder (Google Drive, OneDrive), point `SD_INIT_SCRIPT` at an init
  script that moves `buildDir` outside it, otherwise the resource merger fails with
  `AccessDeniedException` on intermediate files.
- Default APK `app/build/outputs/apk/debug/app-debug.apk`, or whatever path the init
  script sets.
- `ffmpeg-kit` comes from `dev.ffmpegkit-maintained:ffmpeg-kit-min-gpl`; the
  `com.arthenica` artifact left Maven Central in 2025. This build has
  `h264_mediacodec` only as a **decoder**, so the GPU path cannot come from ffmpeg.

## Testing on the device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://x.com/<user>/status/<id>" \
  -n app.skypper.superdownloader/.MainActivity
```

With `Automatic Quality` on, the download starts immediately. Off, the app fills the
link field and waits for the Download tap, with the status at `Waiting For Download`.

Files land in `/sdcard/Download/Super Downloader/<user>_<mediaId>_<template>.<ext>`,
where the template part is `_whatsapp`, `_audio` or `_<height>p`.

## Compression rules

- Bitrate only, never resolution. File size follows bitrate, so dropping to 480p
  damaged the image without making the file fit.
- Duration and resolution come from the downloaded file (`Downloader.probe`), never
  from the post model: `variant.durationMs` once came back as one second and the
  budget turned into a 63 Mbps request.
- Hardware first (`HardwareTranscode`), x264 as fallback. Measured on the test phone:
  about 5x realtime on `MediaCodec`, 0.9x on x264.
- The original audio track is remuxed back with `-c copy`, so audio is never
  re-encoded twice. 96 kbps when it does need encoding.
- One pass is the result. The hardware encoder of the test device ignores the
  requested bitrate (always near 1 Mbps), so a second pass would only repeat minutes
  of work. The app accepts any result smaller than the original, and keeps the
  original when the result is not smaller.
- WhatsApp limits: the official FAQ says 64 MB on a slow connection and 100 MB on a
  fast one for video sent as media, capped at 720p, while a document goes to 2 GB
  without recompression. The budget here is **62 MB**. Blogs repeating "16 MB" are
  talking about photos and status updates, and that number made the app re-encode
  video that would have fit untouched.

## Known pitfalls

- The file name uses the **media id**, not the tweet id. They differ on X:
  `melt_simp_2076784277404991489.mp4` belongs to the tweet `2114510211444527226`.
- `minSdk 29` is deliberate: `MediaStore.Downloads` plus `IS_PENDING` remove the
  storage permission and keep a half-written file out of the gallery.
- `launchMode=singleTask`, so a second link arrives in `onNewIntent`, cancels the
  previous download and queues on the same single-thread executor.
- **Iterating `childCount` on a mixed row crashes the app.** `paintChips` cast every
  child of the templates row to `TextView`; when the gear (`FrameLayout`) was added
  at the end, launch died with `ClassCastException`. Walk the model indices
  (`Quality.ALL.indices`) and cast with `as?`.
- **A `setDetail` that calls itself kills the app at launch** with `StackOverflowError`
  and no build warning. A mass replace that rewrote `detailView.text = x` into
  `setDetail(x)` also rewrote the helper's own body. Read the helper after any sweep.
- **`ProgressBar` ignores the block size.** It honours `maxHeight` and the drawable's
  intrinsic size, so the fill neither spanned the width nor the height. `ProgressStrip`
  draws it by hand. Give it a FIXED height: inside a `wrap_content` `FrameLayout` an
  unsized View receives an AT_MOST spec and grows to swallow the page.
- **The app is resizeable and declares no `configChanges`**, so split screen, rotation
  and window resizes recreate the Activity. Without `handledUrl` plus
  `onSaveInstanceState`, `onCreate` re-read the link from the intent and started the
  download again on every recreate.
- **`uiautomator dump` reports `bounds="[0,0][0,0]"`** for some buttons. That is the
  dump, not the layout: confirm with `screencap` before "fixing" anything.
- **`WRAP_CONTENT` on a horizontal `LinearLayout` does not add the text padding**, so
  a button closed 30dp short of the page margin. A fixed width in dp solves it.
- The share sheet takes over the screen. When `mCurrentFocus` answers with another
  package (WhatsApp `ExternalShareAlias`), the user is sharing for real: check by
  artifact instead of tapping the screen.
- **X publishes only a few heights.** A 720x1280 post carries 320/480/720 in the
  `master.m3u8`, with no 1080p, no 1440p and no 4K. The Custom ladder cuts at the
  post's maximum (`Quality.ladderFor`) and `Downloader.pick` falls back to the largest
  available.
- The `Download Folder:` bar once opened the Share Quality dialog because a mass
  replace swapped both listeners, leaving `folderLauncher` with no callers. That
  orphan listener is the symptom worth grepping for.
- `fromShare` must come from the intent passed into `handle(url, isShare)`.
  `Activity.intent` is not updated by `onNewIntent`, so reading it classified the
  second share with the action of the first launch.
- The temp copy made for transcoding is deleted in a `finally`. Without that, every
  compression left the original, dozens of MB, in `cacheDir/src`.
- Files in `Download/` survive an uninstall, verified by downloading, running
  `pm uninstall` and listing the folder again. Only app-private data (prefs,
  thumbnails, SAF grants) goes away.
- **Never clean that folder with wildcards while testing.** `rm -f .../*whatsapp*`
  deletes every real download of that template, since they all end in `_whatsapp.mp4`.
  Delete the exact test file.
