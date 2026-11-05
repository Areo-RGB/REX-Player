# REX Player

<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="128" height="128" />
</p>

<p align="center">
  <b>Feature-rich Android video player based on libmpv.</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-brightgreen.svg" />
  <img src="https://img.shields.io/badge/License-Apache--2.0-blue.svg" />
  <img src="https://img.shields.io/badge/Kotlin-2.4.10-purple.svg" />
  <a href="https://github.com/mpvRex/REX-Player/releases"><img src="https://img.shields.io/github/downloads/mpvRex/REX-Player/total?logo=Github"/></a>
  <img src="https://img.shields.io/github/stars/mpvRex/REX-Player?style=flat&logo=github" />
</p>

<p align="center">
  <a href="https://trendshift.io/repositories/65794?utm_source=trendshift-badge&amp;utm_medium=badge&amp;utm_campaign=badge-trendshift-65794" target="_blank" rel="noopener noreferrer"><img src="https://trendshift.io/api/badge/trendshift/repositories/65794/daily?language=Kotlin" alt="mpvRex%2FREX-Player | Trendshift" width="250" height="55"/></a>
</p>


> [!NOTE]
> **Repository moved**: from `sfsakhawat999/mpvRex` to [mpvRex/REX-Player](https://github.com/mpvRex/REX-Player). Old links will redirect automatically.

REX Player is an advanced, customizable video player for Android. It combines the versatility of libmpv with a modern Jetpack Compose interface and unique user-centric features.

---

## Showcase

<div class="image-row" align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/glassplayer.png" width="92%">
</div>

<div class="image-row" align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/videoscreen.png" width="31%">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/pip.png" width="31%">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/about.png" width="31%">
</div>

<div class="image-row" align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/playlistwindow.png" width="48%">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/moresheet.png" width="48%">
</div>

---

## Features

- Advanced video playback powered by libmpv, with customizable gestures, frame navigation, zoom, and pan.
- Browse local files, organize playlists, track watched videos, and enjoy Shorts mode.
- Stream media from SMB, WebDAV, FTP, direct URLs, and supported web platforms using the optional REX Ytdlp companion app.
- Customize the player with glass-themed controls, dynamic tabs, subtitle settings, and HDR-to-SDR tone mapping.
- Connect to Jellyfin and share files with nearby devices over your local network.

---

## Installation

<div align="center">
  <a href="https://github.com/mpvRex/REX-Player/releases">
    <img src="https://img.shields.io/badge/Download-Stable_Release-blue?style=for-the-badge&logo=github" alt="Stable Release">
  </a>
  <a href="https://mpvrex.github.io/REX-Player">
    <img src="https://img.shields.io/badge/Download-Preview_Build-orange?style=for-the-badge&logo=github" alt="Preview Release">
  </a>
</div>

<p align="center"><i>Preview builds may be unstable and are intended for testing only.</i></p>

---

## Translations

Translations can be managed using **[Droidlate](https://github.com/estiaksoyeb/Droidlate)** ([PyPI](https://pypi.org/project/droidlate/)) — a local, web-based UI designed for editing Android `strings.xml` translation files.

If you would like to contribute to translating REX Player into your language, please refer to the [Translation Contribution Guide](CONTRIBUTING.md#translation-contributions) for step-by-step instructions on running Droidlate locally.

---

## Playlists generated outside the app

M3U/M3U8 files dropped on the device are picked up automatically: on app start and whenever the
Playlists screen loads, `M3UAutoSync` scans `/storage/emulated/0/Download/timer-clips` and
`/storage/emulated/0/Documents/Lua-scripts` (two levels deep), imports new `*.m3u8` files as
playlists and **refreshes** the ones whose file changed (mtime is compared with the playlist's
`updatedAt`). Entries may be absolute device paths, `file://` URIs or http(s) URLs. The playlist
title comes from the file name (`timer-clips--FIFA 11+.m3u8` → "FIFA 11+").

mpv's Lua API cannot create playlists itself — they are Room rows — so a Lua script can only write
the M3U8 file; this scan is the bridge. See `~/Desktop/timer-clips-folder-playlists.lua` for the
one-playlist-per-clip-folder script used with the timer clips.

## Credits

REX Player has its roots in **[mpvEx](https://github.com/marlboro-advance/mpvEx)**, which itself builds on **[mpv-android](https://github.com/mpv-android/mpv-android)**. We're grateful for the foundation they laid.

Additional inspiration and reference:
[mpvKt](https://github.com/abdallahmehiz/mpvKt) · [Next Player](https://github.com/anilbeesetti/nextplayer) · [Gramophone](https://github.com/FoedusProgramme/Gramophone)

---

## License

Distributed under the **Apache License 2.0**. See `LICENSE` for details.
