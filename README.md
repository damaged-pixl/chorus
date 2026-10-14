# Chorus

A deliberately tiny, fully local music player for Android. It plays audio already on
the phone. Nothing else.

**Release APK: ~1.8 MB** (R8 + resource shrinking). No Compose, no Material, no DI
framework, no analytics, no crash reporting, no ads, **no INTERNET permission** — the
app cannot phone home even if it wanted to.

## Build / install

```bash
export ANDROID_HOME=/mnt/steamgames/home-cold/android-sdk
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.bee.chorus/.MainActivity
```

Debug builds are ~8.7 MB (unminified, full Kotlin stdlib) — always check the *release*
APK when you care about size.

## Why the stack is this thin

- **No Compose** — the Compose runtime costs APK size and RAM we do not need for six
  screens of lists and a player.
- **No Material Components** — the heaviest dependency pulled in. Everything is built
  from framework widgets plus shapes/gradients created in code, so the "designed" look
  (rounded art, styled seek bar, blurred backdrop, elevated artwork) costs **zero
  image assets**.
- **Plain SQLite** (`SQLiteOpenHelper`) for playlists — no Room, so no annotation
  processor and no build-time codegen.
- **Media3** is the only substantial dependency, and only for playback/MediaSession.

## Features

Background playback with a media notification and lock-screen/Bluetooth controls,
tracks + albums browse, live search, playlists (local SQLite), queue with shuffle and
repeat (off / all / one), full-screen player with seek, mini player.

Playlists are fully editable: long-press a track or album row to add it to an existing
or new playlist, and long-press a saved playlist to rename or delete it.

**Cycle mode** (Setup tab) — for use on a bike. Switches to a bright high-contrast
theme for direct sunlight and enlarges every touch target to 72dp for gloved hands.
Persisted in SharedPreferences.

## Layout

```
data/      Track/Album models, LibraryReader (MediaStore), PlaylistStore (SQLite), Settings
playback/  PlaybackService (MediaSessionService), PlayerHolder (single shared controller)
ui/        Art loader, adapters, PlayerPanel, MiniPlayer, Search/Queue/Playlist panels, CycleMode
MainActivity, PlayerActivity
```

`PlayerHolder` owns the only `MediaController`. Every screen is a view over it, so the
mini player and the full player can never drift apart.

## Things worth knowing (hard-won)

- **`ContentResolver.loadThumbnail()` does not work for album art.**
  MediaProvider routes `loadThumbnail` to `ensureThumbnail()`, which only dispatches on
  URI codes 2/101/117/201/701/801 — `audio/albumart/#` is `0x78` (120), so it falls
  through to `throw new FileNotFoundException()`. `Art.kt` instead uses
  `openTypedAssetFileDescriptor` with **no** size hint (so MediaProvider takes the
  `openFileCommon()` path, which does handle `0x78`) and decodes via `ImageDecoder` with
  a computed `setTargetSampleSize`. Do not "simplify" this back to `loadThumbnail`.
- **Album art is bounded on purpose.** `Art` uses one shared 2-thread pool and a
  size-based `LruCache` (heap/8), subsamples to the requested size, and negative-caches
  the few albums with no art so they aren't re-hit on every bind. Never add full-size
  bitmaps to a list.
- **`targetSdk 36` forces edge-to-edge on Android 15+.** Both activities install an
  `OnApplyWindowInsetsListener`; without it the tab strip renders under the status bar.
- **`MediaStore.Audio.Media.YEAR` is frequently 0.** Subtitles omit it when `<= 0` and
  never emit dangling `•` separators.
- The player runs a ~500ms tick **only while playing and attached** (Media3 has no
  position callback), self-cancelling otherwise. That is what moves the seek bar.

## Not done

- Cycle mode's light theme does not reach the full-screen `PlayerActivity` (it stays
  dark by design so the artwork backdrop reads).
- No gapless/crossfade, sleep timer, or playback speed.