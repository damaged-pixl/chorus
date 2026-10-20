# Chorus

A deliberately tiny, fully local music player for Android. It plays audio already on
the phone. Nothing else.

**Release APK: ~1.6 MB** (1,649,879 bytes — R8 full mode, English-only locales,
resource shrinking). No Compose, no Material, no DI
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
repeat (off / all / one), full-screen player with seek, mini player with a progress
hairline.

Playlists are fully editable: long-press a track or album row to add it to an existing
or new playlist, and long-press a saved playlist to rename or delete it.

**Favourites** — star any track row or the current track in the full player. The set
lives in SQLite (`playlists.db`), shows up everywhere at once, and gets a "Favourites"
browse entry above "+ New playlist"; long-press inside it to un-star.

**Play counts** — every track change increments a `play_stats` row (counted once per
real transition, never on resume or seek), and the track list can be sorted by
**Most played** or **Recently played** alongside A–Z.

**Sleep timer** — in the full player, in the mode row beside shuffle, repeat and the
favourite star: 15/30/45/60 minutes or "end of track". Pauses playback when it fires, shows
the time remaining while armed, cancels cleanly. In-memory only: it does not survive the
process being killed.

**Queue editing** — long-press a queue row to move it up/down or remove it; the edit
goes through the shared MediaController so the lock screen sees the same queue.

**Lyrics** — the ≡ control in the full player's time row swaps the artwork square for a
read-only lyric sheet. Sources, in order: an ID3v2 USLT/SYLT tag inside the file, then a
`.lrc` sidecar beside it (same basename), then a plain `.txt` sidecar. Timestamped lyrics
highlight and auto-scroll with playback on the player's existing 500 ms tick; those
without timestamps are one scrollable block. Parsed by hand — no dependency — and no
lyrics simply says so in one line.

**Home-screen widget** — a resizable mini player (title, artist, prev / play-pause /
next) with the album art thumbnail, driven through the same single MediaController as
the app and updated from the service's player listener. Zero image assets, no polling.

Empty screens say so in one friendly line (search, queue, playlists).

**Cycle mode** (Setup tab, *or* the sun control in the full-screen player) — for use on
a bike. Switches to a bright high-contrast theme for direct sunlight and enlarges every
touch target to 72dp for gloved hands. Persisted in SharedPreferences.

## Theming

**Light/dark is the system UI mode.** There is no in-app toggle and no setting — the app
follows the phone. `Theme.Chorus` is `Theme.AppCompat.DayNight.NoActionBar`, so switching
the phone between light and dark recreates the activities and every screen re-themes.
Cycle mode is the one manual override, and it is deliberate: it is high-contrast plus
big touch targets for sunlight and gloves, not a light/dark preference.

Colours are **design tokens**, not literals:

- `res/values/attrs.xml` declares the token names as theme attributes.
- `res/values/colors.xml` holds all three palettes (`chorus_light_*`, `chorus_dark_*`,
  `chorus_cycle_*`).
- `res/values/themes.xml` maps `Theme.Chorus.Base` → the light palette and
  `Theme.Chorus.Cycle` → the light palette plus the cycle overrides.
  `res/values-night/themes.xml` redefines `Theme.Chorus.Base` → the dark palette.

Layouts reference `?attr/chorusXxx`; Kotlin resolves them through `ui/Theming.kt`. **Do
not reference a themed colour as `@color/…` from a layout**: a `@color` resolves by
resource *configuration*, not by theme, so it would hand back the night value while
`Theme.Chorus.Cycle` is active and paint the bright theme dark. That is why the two
palettes have distinct names and there is no `values-night/colors.xml`.

Two things that look wrong but are not:

- **System-bar icon colour is set in code**, not in the theme. At `targetSdk 35+` the
  platform forces edge-to-edge and ignores `android:statusBarColor` /
  `android:windowLightStatusBar` outright. `Theming.applySystemBars` measures the themed
  background's luminance and sets the icons through the insets controller;
  `CycleMode.applyTheme` calls it for both activities.
- **The full-screen player is no longer pinned dark.** It follows the same DayNight
  theme as the rest of the app. In the light (and cycle) themes the artwork square sits
  on its own near-black card (`chorusPlayerArtCard`) and the legibility scrim ramps to
  near-white instead of near-black, so a pale sleeve still reads on a pale page. In the
  dark theme the card token is transparent and the player looks exactly as it did before.

## Layout

```
data/      Track/Album models, LibraryReader (MediaStore), PlaylistStore (SQLite:
           playlists, favourites, play_stats), Favourites (app-wide liked set), Settings
lyrics/    Lyrics model, LRC/plain-text parser, ID3v2 USLT/SYLT reader, LyricsLoader
playback/  PlaybackService (MediaSessionService + play counting), PlayerHolder (single
           shared controller), SleepTimer
ui/        Art loader, adapters, PlayerPanel, MiniPlayer, LyricsPanel, Search/Queue/
           Playlist panels, CycleMode, Theming (design-token + system-bar resolution)
widget/    ChorusWidget (home-screen mini player over the same PlayerHolder)
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
  Neither activity declares `android:configChanges`, so a `uiMode` change recreates them
  and the DayNight theme re-resolves — keep it that way.
- **`MediaStore.Audio.Media.YEAR` is frequently 0.** Subtitles omit it when `<= 0` and
  never emit dangling `•` separators.
- The player runs a ~500ms tick **only while playing and attached** (Media3 has no
  position callback), self-cancelling otherwise. That is what moves the seek bar, and the
  lyrics sheet rides the same tick — it has no timer of its own.
- **Lyric sidecars are best-effort by platform.** The `.lrc`/`.txt` path comes from
  MediaStore's DATA column, and plain-file reads of non-media files are only granted below
  API 29; on newer releases the read is refused and the embedded tag (if any) wins. An
  embedded USLT/SYLT tag always works.
- **The widget holds no controller of its own.** Its buttons are broadcasts that go
  through `PlayerHolder`, so there is still exactly one MediaController; updates are
  pushed from `PlaybackService`'s `onEvents`, never polled.

## Not done

- No gapless/crossfade or playback speed.