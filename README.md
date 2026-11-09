# Super Downloader

An Android app that takes an X/Twitter link, downloads the video, and hands the file
back to the system share sheet so you can send it anywhere.

Two ways to use it: share a link from the X app into Super Downloader, or paste the
link in the app and press Download.

The launcher shows the name **Downloader**, because the launcher truncates longer
names. Everything else, including the application ID, is Super Downloader.

Application ID: `app.skypper.superdownloader`

## What the app does

1. Reads the post behind the link and collects the video files the post actually
   offers.
2. Downloads the file that matches your template.
3. Optionally re-encodes it, when the template asks for a size limit or for audio
   only.
4. Saves it in the folder you picked, and either opens the share sheet (when the
   link came from a share) or waits for you to press Share.

## The four templates

A template is the quality target for one download. Pick one before pressing
Download. The choice sticks until you change it.

**WhatsApp**
Aims at what WhatsApp accepts for video sent as media. It downloads the best
available file and, only if the file is over **62 MB**, re-encodes it to fit. The
re-encode lowers the bitrate only. Resolution is never changed, so a 1080p video
stays 1080p. Audio goes to 96 kbps AAC. Files that already fit are left untouched.

**Audio Only**
Downloads the video, extracts the audio track, and deletes the video from the
device. The result is an `.m4a` file with the original audio stream when the codec
allows a direct copy, and AAC 192 kbps when it does not. Useful for posts you want
to listen to, not watch.

**Highest Quality**
Downloads the largest file the post offers and does not touch it. No re-encode, no
quality loss, whatever the size.

**Custom**
Opens the resolution ladder: 4K, 1440p, 1080p, 720p, 480p, 360p, 240p, 144p. The
ladder is clipped to what the post really has, so a 360p post shows only 360p and
below, and asking for 4K is not possible.

## Two switches that change how a download starts

**Automatic Quality**
Inside the templates area. On: the app downloads as soon as it receives a link,
using the selected template. Off: the app fills the link field and waits, so you can
change the template before pressing Download.

**The gear, next to Share**
Sets a separate template for links that arrive from the share sheet. The idea is
that a video you are sharing usually wants a different quality than a link you are
saving for later. With the gear's template enabled, a share uses it and the main
template is ignored. With it disabled, both paths use the main template.

## Where files go

The `Download Folder:` bar at the top opens the system folder picker (Storage Access
Framework) and remembers your choice. Until you pick one, files go to:

```
Download/Super Downloader
```

File names follow `<user>_<mediaId>_<template>.<ext>`:

```
melt_simp_2076784277404991489_whatsapp.mp4
angieverses_2114529898609930240_1080p.mp4
demori_2114586788048064512_audio.m4a
```

A name that already exists gets a numeric suffix instead of being overwritten.

Files written to the Downloads collection belong to you, not to the app. They
survive an uninstall.

## History

The last 10 downloads, newest at the bottom, each row with the link, a square
thumbnail from the first frame, a copy button for the link and a share button for
the file. History lives in the app's private storage, so uninstalling clears it.

## How a download works

**Link resolution.** Posts are resolved through `vxtwitter` first and `fxtwitter`
as a fallback. Direct `.mp4` and `.webm` links skip both and are downloaded as they
are. Both services are third party: if X changes something, this is the part that
breaks first.

**Download.** The file is written through `MediaStore` into the Downloads
collection, or through `DocumentsContract` into the folder you picked. No storage
permission is involved, which is why `minSdk` is 29.

**Compression.** When a re-encode is needed, the app first tries the device's
hardware encoder through `MediaCodec`, then falls back to `x264` from ffmpeg-kit.
Hardware encoding runs about 5x realtime on the test phone, against 0.9x for the
software path. The original audio track is remuxed back with a stream copy, so the
audio is never re-encoded twice.

**Progress.** The status block has five states: `Waiting For Download`,
`Downloading`, `Compressing`, `Cancelling`, `Download Complete`. The line under the
title shows the details, including an estimate of the time left during compression.

## Requirements

- Android 10 (API 29) or newer.
- No storage permission, no account, no Play Services.

## Build

You need JDK 21 and the Android SDK.

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/Android/Sdk
./build.bat assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. `./gradlew
assembleDebug` works too, `build.bat` only sets the environment and calls it.

If the project sits in a synced folder (Google Drive, OneDrive), point
`SD_INIT_SCRIPT` at a Gradle init script that moves `buildDir` outside the synced
folder. The Android resource merger fails with `AccessDeniedException` on
intermediate files that a sync client is touching.

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

To exercise the share flow without opening the X app:

```bash
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://x.com/<user>/status/<id>" \
  -n app.skypper.superdownloader/.MainActivity
```

## Known limitations

- Only X/Twitter links are resolved properly. Other sites work only if the link
  points straight at a media file.
- The hardware encoder on some devices ignores the requested bitrate and delivers a
  fixed one. The app accepts the first result when it is smaller than the original
  instead of encoding again.
- Very long videos cannot always fit the WhatsApp limit at a watchable bitrate.
  When that happens the app keeps the smallest result it managed to produce and
  reports the final size.
- The released APK is a debug build, signed with the debug key. It installs fine by
  sideload, but it is not a Play Store artifact.

## License and third party code

The APK links `ffmpeg-kit-min-gpl`, which bundles x264. x264 is GPL licensed, so
builds that include it inherit the GPL obligations, including source availability.
If you need a permissive binary, remove the ffmpeg dependency and keep only the
`MediaCodec` path.

The project does not yet ship a LICENSE file. Decide before distributing.

## Version

`0.0.1`, tested on a Samsung A-series phone running Android 13.
