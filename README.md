# Super Downloader

An Android app that takes an X/Twitter link — shared into it or pasted — downloads
the video at the quality you pick and hands the file back to the system share sheet.

Visible name on the launcher is **Downloader** (the full name is truncated there).
Application ID: `app.skypper.superdownloader`.

## What it does

- **Share target**: pick Super Downloader in the share sheet of X, it downloads and
  opens the share sheet again with the MP4, ready for WhatsApp/Telegram/whatever.
- **Templates**: `WhatsApp` (re-encodes above the platform limit), `Audio Only`
  (extracts the audio track as M4A), `Highest Quality`, `Custom` (resolution ladder
  clipped to what the post actually offers).
- **Download folder**: chosen with the system folder picker (SAF); defaults to
  `Download/Super Downloader`.
- **History**: the last 10 downloads, newest at the bottom, with a first-frame
  thumbnail and copy/share buttons.
- **Compression**: hardware `MediaCodec` encoding when available (~5x realtime on
  the test device), bitrate-only — resolution is never touched — with `x264` as a
  fallback via ffmpeg-kit.

## Requirements

- Android 10 (API 29) or newer. No storage permission is needed: files go through
  `MediaStore` / SAF.
- JDK 21 and the Android SDK to build.

## Build

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/Android/Sdk
./build.bat assembleDebug        # or ./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

If the project sits in a synced folder (Google Drive, OneDrive), point
`SD_INIT_SCRIPT` at a Gradle init script that moves `buildDir` outside it — the
Android resource merger fails with `AccessDeniedException` on synced intermediate
files.

## Install as a share target

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "https://x.com/<user>/status/<id>" \
  -n app.skypper.superdownloader/.MainActivity
```

## How links are resolved

`vxtwitter` first, `fxtwitter` as fallback, and direct `.mp4`/`.webm` URLs are used
as-is. Both are third-party services: if X changes something, resolution is what
breaks first.

## Licensing note

The APK links `ffmpeg-kit-min-gpl`, which bundles x264 — **GPL-licensed**. Builds
that include it inherit the GPL obligations (source availability). If you need a
permissive binary, drop the ffmpeg dependency and keep only the `MediaCodec` path.

## Status

Early build (`0.1.0`), tested on a Samsung A-series phone running Android 13.
