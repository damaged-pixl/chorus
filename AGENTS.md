# Chorus — agent notes

Tiny, fully local Android music player (package `com.bee.chorus`). Plays audio already
on the phone, nothing else. Release APK ~1.8 MB with R8. `README.md` is the fuller
overview; this file is the quick reference for agents working here.

## Non-negotiable invariants

- **No new dependencies without asking.** The thin stack is the point: no Compose, no
  Material Components, no DI framework, no image libs. Media3 (exoplayer + session) is
  the only substantial dependency; appcompat + recyclerview the rest. Every dep added
  is size and RAM the app exists not to spend.
- **No INTERNET permission, ever.** The manifest deliberately `tools:node="remove"`s
  Media3's transitive `ACCESS_NETWORK_STATE`. Don't reintroduce any network access.
- **Do not "simplify" album art back to `ContentResolver.loadThumbnail()`** — it throws
  `FileNotFoundException` for `audio/albumart/#` (URI code 120 isn't in
  MediaProvider's dispatch list). `ui/Art.kt` uses `openTypedAssetFileDescriptor` with
  no size hint + `ImageDecoder` + computed sample size. This is load-bearing.
- **Never put full-size bitmaps in lists.** `Art` has one shared 2-thread pool, an
  LruCache (heap/8), size-based subsampling, and a negative cache for artless albums.
  Keep list binds on the bounded path.
- **Check the *release* APK size after changes** (`~1.6 MB` / 1,633,863 bytes is the
  baseline as of 2026-10-18, after the DayNight/token work; 1,624,203 before it, itself
  up from 1,614,627 for the six new features; earlier 1,849,450 via `localeFilters += "en"`
  + META-INF licence excludes). Debug builds are ~8.7 MB and meaningless for size. A
  dependency or asset regression shows up here first — this is the primary guard against
  feature creep into the thin stack.
- **Never reference a themed colour as `@color/…` from a layout or a theme.** Colours are
  design tokens: declared as theme attributes in `res/values/attrs.xml`, with the light,
  dark and cycle palettes all in `res/values/colors.xml` (`chorus_light_*` /
  `chorus_dark_*` / `chorus_cycle_*`). Layouts use `?attr/chorusXxx`; Kotlin uses
  `Theming.color(ctx, R.attr.chorusXxx)`. A `@color` resolves by *configuration*, not by
  theme, so it would return the dark value while `Theme.Chorus.Cycle` is active and paint
  the bright theme dark. This is also why there is deliberately **no
  `res/values-night/colors.xml`** — the day/night switch happens in
  `res/values-night/themes.xml`, which redefines `Theme.Chorus.Base` against the dark
  palette. Don't "tidy" the palettes back into one name under two qualifiers.
- **There is no light/dark setting and there must never be one.** `Theme.Chorus` is
  `Theme.AppCompat.DayNight.NoActionBar`; the phone's UI mode drives everything, and a
  `uiMode` change arrives as a plain Activity recreation. Never add
  `android:configChanges="uiMode"` to either activity — that would block the recreation
  and leave the app on the stale palette. Cycle mode is the one manual override, and it
  is high-contrast + 72dp targets, not a light/dark preference.

## Stack

AGP 9.4.1 with **built-in Kotlin** (Kotlin 2.4.21, no separate `kotlin-android`
plugin), Gradle 9.6.0, compileSdk 37, minSdk 26, targetSdk 36, Java 17. View-based UI
(framework widgets + appcompat + RecyclerView, shapes/gradients drawn in code — zero
image assets). Plain `SQLiteOpenHelper` (no Room) for playlists (`playlists.db`).
Media3 1.11.1 for playback + MediaSession. Is a git repo (since 2026-10-14); build
outputs (`.gradle/`, `app/build/`, `local.properties`) are ignored — never commit them.

## Build / install

```bash
export ANDROID_HOME=/mnt/steamgames/home-cold/android-sdk   # local.properties already points here
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.bee.chorus/.MainActivity
```

Release is signed with the shared debug keystore by design (no local setup needed).
No test suite — verify by building and running on device.

## Layout

- `data/` — `Track.kt`/`Library.kt` models, `LibraryReader.kt` (MediaStore scan),
  `PlaylistStore.kt` (SQLite **v3**: `playlists` + `playlist_tracks`, `favourites`,
  `play_stats`; tracks by MediaStore id), `Favourites.kt` (app-wide liked set over
  that DB), `Settings.kt` (SharedPreferences wrapper, one flag: cycle mode).
- `playback/` — `PlaybackService.kt` (MediaSessionService; counts plays on media-item
  transitions), `PlayerHolder.kt`, `SleepTimer.kt` (in-memory countdown / end-of-track).
- `ui/` — `Art.kt`, adapters, `PlayerPanel.kt`/`MiniPlayer.kt`, Search/Queue/Playlist
  panels, `CycleMode.kt`/`CycleToggle.kt`, `Theming.kt` (the only place a token is read
  out of a theme, plus the system-bar icon contrast).
- `MainActivity.kt` (tabs + mini player; track tab = `track_screen` sort bar + list),
  `PlayerActivity.kt` (full-screen player).

**`PlayerHolder` owns the only `MediaController`** — every screen is a view over it, so
mini and full player can't drift. Never create a second controller. Its `player`
property is a read-only view for observers (SleepTimer); mutations go via `whenReady`.

**Both activities call `CycleMode.applyTheme(this, cycle)` before `super.onCreate()`** —
`setTheme` has to happen before anything inflates, and toggling needs `recreate()`. Both
also run `CycleMode.apply` on `android.R.id.content` in a `post {}` so the 72dp targets
land once the rows exist. `PlayerActivity` registers a `Settings` listener for this and
**removes it in `onDestroy`** — see the strong-listener note below.

## Gotchas (hard-won, don't regress)

- `targetSdk 36` forces edge-to-edge on Android 15+; both activities must keep their
  `OnApplyWindowInsetsListener` or the tab strip lands under the status bar.
- `MediaStore.Audio.Media.YEAR` is frequently 0 — subtitles omit it when `<= 0` and
  never emit dangling `•` separators.
- The seek bar is driven by a ~500 ms tick that runs **only while playing and attached**
  (Media3 has no position callback) and self-cancels. Don't make it unconditional.
- **System-bar icon colour cannot be a theme attribute.** At `targetSdk 35+` the platform
  forces edge-to-edge and ignores `android:statusBarColor` /
  `android:windowLightStatusBar` outright, so dark-on-light only ever works through
  `Theming.applySystemBars(activity)`. `CycleMode.applyTheme` calls it for both
  activities, so the icons follow whichever palette is active. Don't move it back into a
  theme item.
- **`Settings` listeners are held strongly** — remove any listener capturing an Activity in
  `onDestroy`, or `recreate()` leaks it. `PlayerActivity` now has one: it watches for a
  cycle-mode change made on the Setup tab while the player is behind it, and recreates.
- Playlist DB is **v3 with real migrations** (v1→v2 added `favourites`, v2→v3 added
  `play_stats`; a v1 install falls through both branches). Any schema change needs a
  version bump AND a matching `onUpgrade` branch. `Favourites`, `MainActivity` and
  `PlaybackService` each open their own `PlaylistStore` on the app context — safe
  because SQLite connections are pooled per file path.
- **Play counts increment in `PlaybackService.onMediaItemTransition`** — that callback
  fires only on real track changes (never on pause/resume or in-track seeks), which is
  why the counts are right. Don't also count from the UI side.
- **`Favourites` listeners are held strongly in a static list** — every add needs a
  matching remove or it is leaked for the life of the process. The pairing is the *host's*
  lifecycle: Activity `onCreate`/`onDestroy` (MainActivity's track list) or a view's
  `onAttachedToWindow`/`onDetachedFromWindow` (`PlayerPanel`, `PlaylistPanel`,
  `SearchPanel`). `TrackAdapter.observeFavourites()`/`stopObservingFavourites()` is driven
  by whichever host owns it. **`onAttachedToRecyclerView`/`onDetachedFromRecyclerView` is
  NOT a leak-safe pair** — RecyclerView only calls those on an adapter swap, never on
  window detach or Activity destroy, so `recreate()` (cycle-mode toggle) leaked an adapter
  per recreation. Never register from an adapter's own RecyclerView hooks again.
- **QueuePanel's queue signature (count|first|last|current) cannot see interior
  reorders** — `moveItem`/`removeItem` clear `contentSignature` to force the next
  player event to rebuild the rows. Keep that pattern for any further queue editing.
- The Tracks tab is `track_screen` (A–Z / Most played / Recently played sort bar +
  list) shown as one unit — `show()` and `wireTabs` switch on `track_screen`, never on
  the bare `track_list` RecyclerView inside it.
- `SleepTimer` is deliberately in-memory only (no persistence across process death).
  Its end-of-track mode re-arms one exactly-delayed runnable from every player event —
  read its KDoc before changing the firing logic.
- **Kotlin 2.4 removed `kotlinOptions {}`** and AGP 9's built-in Kotlin derives
  `jvmTarget` from `compileOptions.targetCompatibility` (17) automatically. Don't add a
  `kotlin { compilerOptions {} }` block — it's redundant here.
- **The three list adapters (`TrackAdapter`, `AlbumAdapter`, `PlaylistAdapter`) each take
  an `onClick` and an `onLongClick` as their last constructor params.** Always construct
  with a *named* `onClick = { ... }` argument (or set the property), never a bare trailing
  lambda — a trailing lambda silently binds to `onLongClick` instead. This bit the build
  once already.
- `compileSdk 37` (API 37) needs the **`platforms;android-37.0`** SDK package, not
  `android-37` (which doesn't resolve). The installed cmdline-tools was too old to list it
  and needed updating first.
- British English in user-facing strings.

## Known gaps (backlog, not bugs)

- No gapless/crossfade or playback speed. The sleep timer does not survive process
  death (by design).
