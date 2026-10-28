# Development notes

How Piper-YT is put together, and the non-obvious things learned building it. For building
and installing, see the [README](../README.md).

## Building

```sh
./gradlew run            # build and launch
./gradlew test           # migrations, history purge, scoring, backups, yt-dlp args
./gradlew installDist    # what install.sh installs, in build/install/piper-yt
```

`PIPER_DATA_DIR=/some/dir ./gradlew run` keeps a test library away from your real one.

## The bundled extractor

`PipePipeExtractor/` is [PipePipeExtractor](https://github.com/InfinityLoop1308/PipePipeExtractor)
v5.4.0 (commit `c68e10e2`), GPLv3, included as source and built through a Gradle composite
build (`includeBuild` in `settings.gradle`), so changes to it are picked up automatically. It
differs from upstream in one line: `YoutubeSearchSortFilter` names its charset `"UTF-8"`
instead of `"UTF_8"`, which Android accepts but desktop Java rejects (every filtered search
failed). To update it, replace the directory with a newer upstream checkout and re-apply that
fix if it's still needed.

## Layout

Paths are under `src/main/java/io/github/piper_yt/piperyt/`.

| Path | What it does |
| --- | --- |
| `net/Network`, `net/NetworkSettings` | The one HTTP client and the route all traffic takes (see Privacy) |
| `net/ProxyBridge` | Local HTTP proxy that sends mpv's connections the same route |
| `net/BotWall`, `net/Mullvad` | Bot-check retries with jittered backoff; optional Mullvad exit rotation |
| `net/OkHttpDownloader` | The HTTP backend the extractor requires (`Downloader` subclass) |
| `net/Extractor` | Extractor init, YouTube search queries by type, link detection |
| `player/MpvPlayer` | Picks streams and SponsorBlock segments; external-mpv fallback |
| `player/Mpv` | libmpv bindings (client + OpenGL render API) over FFM |
| `ui/PlayerView` | Embedded player: mpv renders into a `GLArea`, input is forwarded to mpv |
| `ui/MainWindow` | Window, navigation stack, toasts, link handling |
| `ui/SearchView` | Search bar, result-type picker and infinitely scrolling results grid |
| `ui/VideoPage` | Details, quality picker, description, comments with replies, Up next (related videos and mixes, Favorites first, watched last; Home picks when there are none) with autoplay |
| `ui/MiniPlayer` | The corner player a video page hands its video to when you leave it |
| `ui/ChannelPage` | Banner, about, and lazily loaded Videos/Shorts/Live/Podcasts/Playlists tabs |
| `ui/PlaylistPage` | Playlist header with a link to its creator, then its videos |
| `ui/ItemGrid` | Cards for videos, channels and playlists that load more pages on scroll |
| `ui/VideoCard`, `ui/ChannelCard`, `ui/PlaylistCard` | One result each, as a card or row |
| `util/Theme` | The Ember Muted palette: libadwaita colors and mpv's controller/OSD |
| `ui/Preferences` | Playback (autoplay, mini player, SponsorBlock), History, Feed & Home, Downloads, Data, Network |
| `ui/Ui`, `ui/Images` | Background work / main-thread dispatch, thumbnail cache |
| `ui/Shell` | Root page: sidebar (Home, Feed, Favorites, Playlists, Downloads, History) + search |
| `ui/HomeView`, `ui/FeedView`, `ui/FavoritesView`, … | The sidebar views |
| `ui/LocalPlaylistPage` | A local playlist: play/shuffle/queue, drag to reorder, export, re-sync |
| `ui/VideoMenu`, `ui/Dialogs` | A video's ⋮ menu; playlist picker, download, clear-history dialogs |
| `db/Db`, `db/Migrations` | The SQLite library and its versioned schema (`PRAGMA user_version`) |
| `db/Favorites`, `Feed`, `Playlists`, `History`, `Downloads`, `HomeStore`, `Settings` | One store per table group |
| `db/Backup` | PipePipe/NewPipe zip import/export, JSON import/export, wipe |
| `net/Pacer` | Shuffled, jittered, batched background fetches with bot-wall cool-down |
| `service/FeedRefresher` | Background and manual feed refresh from RSS, through `Pacer` |
| `service/HomeRefresher`, `rec/Scorer`, `rec/RecWeights` | Homepage candidates, local scoring, its weights |
| `service/DownloadManager`, `service/YtDlp` | Persistent download queue; yt-dlp command lines |
| `player/PlayQueue` | Play all / shuffle / queue |

## Privacy

YouTube should see only anonymous, unlinked requests, ideally from a shared VPN exit.

- **One route.** Extractor requests, thumbnails, SponsorBlock and mpv's streams all go out
  the way Preferences → Connection says: the system route (the default; with Mullvad that is
  the tunnel, and its kill switch blocks traffic while it's down) or a SOCKS5 proxy (Mullvad's
  is `10.64.0.1:1080`). The SOCKS5 route fails closed and resolves hostnames through the proxy;
  a local DNS lookup while it's on is refused. Its exit IP differs from the rest of the machine's,
  but YouTube bot-checks it more often. mpv only speaks HTTP proxies, so it goes through
  `ProxyBridge`, in both modes. It has to: stream URLs are tied to the IP that extracted them.
- **No identifiers kept.** OkHttp has no cookie jar, so cookies YouTube sets are dropped. The
  only cookies sent are the fixed "reject all" consent value every NewPipe client sends, and a
  mix's own cookie for its next page. The extractor fetches a fresh visitorData for every player
  request and sends none on browse/search requests, so there is nothing to rotate or persist.
  The default User-Agent is NewPipe's; YouTube client headers are the extractor's own.
- **Bot checks.** "Sign in to confirm you're not a bot", CAPTCHAs, HTTP 429 and PO-token
  rejections are retried whole (fresh visitorData, fresh stream URLs) with exponential, jittered
  backoff, optionally after `mullvad reconnect` (off by default: it reconnects the whole system,
  and with locations set it changes Mullvad's saved relay constraint). After the last retry the
  page shows what happened.
- **PO tokens.** None needed by the default client. A bgutil-style provider plugs in through
  `NewPipe.setYoutubePoTokenResolver`.
- **yt-dlp.** Downloads run yt-dlp with `Network.ytDlpArgs()` first:
  `--ignore-config --no-cookies --no-cache-dir --proxy http://127.0.0.1:…`. The proxy is a
  local bridge like mpv's (its own, labelled `yt-dlp` in the connection log) that sends each
  connection the app's way; on the SOCKS5 route hostnames go to the proxy unresolved. Never
  `--cookies-from-browser`. It talks to YouTube
  (`www.youtube.com`, `*.googlevideo.com`, `i.ytimg.com` for the embedded thumbnail) and, with
  SponsorBlock on for downloads, `sponsor.ajay.app` (passed explicitly as `--sponsorblock-api`).
- **Paced background work.** Feed refreshes and the homepage go through `net/Pacer`: one
  request at a time, shuffled, a random gap (2–20 s by default) before each, batches with a
  longer pause between them, optionally `Mullvad.rotate()` between batches (its own switch, off
  by default). A request still bot-walled after `BotWall`'s retries stops all paced work for
  15 minutes. Pages you open and playlists you copy or download are fetched directly, pages a
  few jittered seconds apart.
- **Feed refresh.** One request per favorite: its RSS feed (`getFeedExtractor`), no browse or
  player calls. In the background, only channels not refreshed within the interval, checked at
  jittered times. RSS doesn't say whether an upload is a livestream (shorts it does, by URL),
  so "Hide livestreams" only knows the ones opened once, which teach the cache, unless
  "Detect livestreams" (off by default) also loads each channel's Live tab: a second request
  per channel, a few seconds after its RSS.
- **Unwatchable videos.** There's no sign-in, so members-only videos (the extractor's
  `requiresMembership()`, from YouTube's "Members only" badge) are dropped everywhere results
  come in: search, channel tabs, playlists and Play all, Up next, Home's related videos
  (`Extractor.isWatchable`). Premium-only videos the extractor already drops as ads, and
  paid movies come in a renderer it doesn't parse. RSS can't tell, so the Feed can't filter.
- **Homepage.** Scoring is local (`rec/Scorer`). Fetches per refresh are capped: related
  videos of 4 seeds (the stream page's "next" data, without stream extraction), cached for
  48 h, and with "Discover" on, the RSS feeds of 2 channels that keep showing up in them.
- **History.** Off means nothing is written (no resume positions either) and nothing is read
  for recommendations. Watch counts and affinity are computed from history on the fly, never
  stored; related videos of watched videos, discovered channels and the computed homepage are
  deleted with the history they came from. Deleted rows are overwritten
  (`PRAGMA secure_delete`).
- **Leak check.** Preferences → Log connections (or `PIPER_NET_DEBUG=1`) prints every
  outbound host, and whether it went through the proxy, to stderr. "Open in browser" leaves the
  app and is not covered.

## Library and backups

- **Schema.** `db/Migrations` holds numbered migrations; never edit a released one. Each table
  that lists videos keeps its own copy of the video's metadata, so deleting from one (history)
  can't leave rows elsewhere pointing at it.
- **PipePipe backups.** A backup zip holds `newpipe.db` (Room) and optionally
  `newpipe.settings` (serialized Android preferences). Import reads any NewPipe-family database
  column by column and merges: favorites and bookmarks by URL, playlists by name ("Watch
  Later" maps to the built-in one), history by video and time; history only while saving it is
  on. Export writes PipePipe's schema 901 exactly, with Room's identity hash
  (`d505dd6c…`), so PipePipe imports it without a migration. Upstream NewPipe's numbers are
  lower and it would refuse 901, so "NewPipe backup" writes its schema 9 instead (identity hash
  `7591e803…`; `BackupTest` checks every statement against NewPipe's `9.json`): no `is_paid`,
  and playlists point at their first video for a thumbnail. Non-YouTube favorites and playlist
  items are kept for export but not refreshed or played. The phone's `newpipe.settings` from
  the last import is put back into PipePipe exports unchanged, and left out of NewPipe ones.

## Implementation notes

- **Threading.** GTK must only be touched from the main thread. Don't pass callbacks
  into GLib from background threads (`GLib.idleAddOnce` etc.): java-gi can free
  the callback's memory before the scheduling call returns, and the JVM aborts. Use
  `Ui.async` / `Ui.runOnMain`.
- **`org.json`** is part of Android, so the extractor only declares it `compileOnly`.
  It's added here as a runtime dependency.
- **Zulu localization.** The YouTube service deliberately requests `hl=zu` so titles aren't
  auto-translated, so textual dates come back in Zulu. Format dates from the parsed
  `getUploadDate()` (see `Format.relativeDate`), never `getTextualUploadDate()`.
- **Search filters.** YouTube search needs an explicit content filter;
  `getSearchQHFactory().fromQuery(String)` throws.
- **Embedded mpv.** libmpv refuses to start unless `LC_NUMERIC` is `C`, and GTK sets
  the user's locale, so `Mpv` resets it before `mpv_create`. Mouse and key events are
  forwarded to mpv (`mouse`, `keydown`, `keypress`), so mpv's on-screen controller and
  default bindings work; `q` isn't forwarded because "quit" would destroy the player.
  The render context must be freed (with the GL context current) before the core is
  destroyed. Freeing it also makes mpv drop the video track, so never reparent the
  `GLArea`: fullscreen grows it in place and hides the rest of the page. Theater mode
  (header button or `t`; on by default, remembered via `java.util.prefs`) widens it the same way; its
  16:9 height is set from a tick callback, since GTK won't size it height-for-width.
  The page is a `Grid`, and its layouts (theater, normal, Up next beside or below the
  details at 1000px wide, fullscreen) move children between cells, never between parents.
- **Mini player.** For the same reason the page's player can't move into the corner: leaving a
  playing video's page (when no other video page takes over) hands the source, SponsorBlock
  segments, position and history entry to `MiniPlayer`, which plays them with its own mpv.
  Going back to the page, or "Back to the video", hands it back the same way. Opening a
  recommendation frees the page's mpv instead, so a trail of videos holds one player.
- **External mpv.** Without libmpv, playback runs in a separate mpv with a throwaway Lua script
  that skips SponsorBlock segments and prints `[pppos] <position> <duration>` every few
  seconds and on pause; the app reads that to save resume positions.
- **Play all on YouTube playlists.** Starts with the loaded videos and queues the remaining
  pages as they arrive, a few jittered seconds apart, until the queue is replaced. Shuffle
  loads every page first.
- **Unparented widgets with signal handlers get freed.** With java-gi 0.12.2, a widget that
  has a signal handler connected (`onClicked`, `onChildActivated`, `onNotify`...) is freed on
  the next GC while it has no parent, even though Java still references it, and using it then
  crashes the JVM. Put such widgets in their parent in the same main-loop turn (e.g. before
  starting an async load), as `PlaylistPage` does with its grid.
- **Search type filters.** Pass a non-null (empty) sort-filter list to
  `getSearchQHFactory().fromQuery`; with `null` the content type is dropped from the request
  and results come back mixed. The bundled `PipePipeExtractor/` also has a one-line fix
  in `YoutubeSearchSortFilter` (charset name `"UTF_8"` → `"UTF-8"`), which Android accepts
  but desktop Java rejects, so every filtered search failed.
- **Seeking through the proxy.** FFmpeg 8.1 seeks badly through an HTTP proxy: it read forward
  through megabytes (it sizes "short" seeks by the loopback socket's large buffer, and YouTube
  throttles), and it reports a reused connection the server closed as an I/O error instead of
  end-of-stream, so it never reconnected. Resuming, seeking and SponsorBlock skips hung. mpv gets
  `stream-lavf-o=short_seek_size=1,reconnect_on_network_error=1,multiple_requests=1,rw_timeout=15000000`
  (`MpvPlayer.STREAM_OPTIONS`); keep these when touching the player.
- **Exceptions in GTK callbacks end the JVM** (an FFM upcall can't throw), silently when stderr
  goes nowhere. Signal handlers that can fail must catch, as `PlayerView`'s mpv calls and toast
  buttons do.
- **Themes.** `util/Theme` holds a theme (12 colors, dark/light, player font); Ember Muted is the
  default, Gruvbox Dark and Light are built in, and
  Preferences → Appearance makes, edits, imports and exports custom ones (`util/Themes`, stored in
  the library's settings). The theme's CSS loads just above user priority, so it beats
  `~/.config/gtk-4.0/gtk.css` (which themes every GTK app); it sets everything that file may set.
  The color scheme follows the theme's dark flag. mpv's controller is recolored through `osc-*`
  `script-opts` when a player starts. `ColorDialogButton.getRgba()` comes back from java-gi 0.12
  with a zero-length segment, so colors are read through GDK's `to_string`.
- **Widget identity.** java-gi can return a new wrapper for a widget it already wrapped
  (e.g. from `getFirstChild`), so compare `handle()`s, not references.
- **SponsorBlock.** Setting `SponsorBlockApiSettings` on the service makes
  `StreamInfo.getInfo` fetch segments alongside the video; the categories come from
  Preferences → Playback (`Extractor.applySponsorBlockCategories`), and downloads use the same
  ones.
- **Pages and listeners.** A popped page's widgets may be freed (see above), so pages only
  listen for library or queue changes while shown (`onShowing`/`onHidden`), and dialogs don't
  listen at all.
- **No PoToken / WebView.** The default `visionos` player client needs neither, and
  signature decoding falls back to PipePipe's remote decoder API (`api.pipepipe.dev`, through
  the same route).
- **TLS.** `NewPipe.init` turns off certificate checks for `HttpsURLConnection` JVM-wide;
  `Extractor.init` puts them back. OkHttp was never affected.
