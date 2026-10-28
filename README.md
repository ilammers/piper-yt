# Piper

Piper is the definitive, privacy-first YouTube client for the Linux desktop. 

> Piper-YT is an independent project. It is not affiliated with or endorsed by PipePipe,
> NewPipe, YouTube or Google.

## Features

- **Search** for videos, channels and playlists, and browse channels, playlists, comments and replies.
- **Playback** through libmpv, with theater mode, fullscreen, quality
  selection, audio-only playback and a **mini player** that keeps a video going while you
  browse. [SponsorBlock](https://sponsor.ajay.app) skips the segment categories you choose.
- **Up next** beside the video (or below it on narrow windows), with **autoplay**.
- **Favorites**: local subscriptions with a Feed built from each channel's RSS feed.
- **Home**: recommendations scored locally from your Favorites and (if you keep it) your
  watch history. Nothing about what you watch is sent anywhere.
- **Playlists**: local playlists, Watch Later, a play queue, and copies or bookmarks of
  YouTube playlists.
- **Downloads** through yt-dlp, with SponsorBlock cutting or chapter marks and subtitles.
- **Watch history** with resume and progress bars, which you can turn off entirely.
- **Backups**: imports PipePipe and NewPipe backups, and exports to either, so your library
  moves between your phone and your desktop. Also a plain JSON export.
- **Themes**: Ember Muted, Gruvbox Dark and Gruvbox Light built in, plus your own.

Members-only videos are hidden, since they can't be viewed without signing in. Similarly, age-restricted videos are shown but cannot be viewed. 

## Privacy

- Every connection (the app's requests, thumbnails, SponsorBlock, mpv's video streams and yt-dlp's downloads) takes one route you choose: the system route (for example a VPN), or a SOCKS5 proxy that fails closed and resolves hostnames through the proxy.
- No cookies are kept and no identifiers are stored between requests.
- Background work (feed refreshes, Home) is paced: one request at a time, shuffled, with
  random gaps.
- Watch history never leaves the machine. With it off, nothing is recorded at all.
- Preferences → Network → Log connections prints every outbound connection and the route it took, so you can check for yourself.

The details are in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md#privacy).

## Install

Piper-YT needs Java 25, GTK 4, libadwaita 1.6 or newer, and mpv. It is developed and
tested on Fedora 44 (GTK 4.22, libadwaita 1.9).

On Fedora:

```sh
sudo dnf install java-25-openjdk-devel mpv mpv-libs yt-dlp ffmpeg
git clone https://github.com/piper-yt/Piper-YT.git
cd Piper-YT
./install.sh
```

`install.sh` builds the app and installs it for your user only (no root): the app goes in
`~/.local/share/piper-yt`, the `piper-yt` command in `~/.local/bin`, and a menu entry in
`~/.local/share/applications`. Run it again to update.

On other distributions, install the equivalents of the same packages: a Java 25 JDK, mpv
with libmpv, and optionally yt-dlp and ffmpeg.

- **libmpv** (`mpv-libs` on Fedora) plays videos inside the window. Without it they open in
  a separate mpv window.
- **yt-dlp and ffmpeg** are only needed for downloads.
- The JDK is only needed to build; running needs just the Java 25 runtime.

To try it without installing: `./gradlew run`.

### Uninstall

```sh
rm -rf ~/.local/share/piper-yt ~/.local/bin/piper-yt \
    ~/.local/share/applications/io.github.piper_yt.PiperYT.desktop
```

Your library is kept separately (see below); delete it too if you want everything gone.

## Data Collection

Everything is in one SQLite file, `library.db`, in
`~/.local/share/io.github.piper_yt.PiperYT/`. Set `PIPER_DATA_DIR` to use
another folder. 

Preferences → Data imports and exports backups, and can wipe the library.

## Ethical Considerations

You are about to break YouTube's Terms of Service.

This program directly serves to circumvent the ad-surveilance model that creators and YouTube uses to monetize watch time. Moreover, local subscriptions do not contribute to the creator's subscriber count since Favorites are local and not linked to an account. 

If you use this software, be courtuious enough to support your favorite creators via a means other than contributing to view totals beit: merch, Patreon, channel membership, etc. 

## Implementation

The original concept for Piper was Rust + libmpv, with RustyPipe as the extractor. However, it became apparent that RustyPipe didn't have the maintenance cadence to serve as the backbone for this project.

Piper is instead written in Java, so it can use the more actively maintained PipePipeExtractor, which is also Java and remains the project's core dependency. The UI is GTK4/libadwaita via java-gi, and playback still uses libmpv.

If RustyPipe gains more traction, a Rust version may be reconsidered.


## Contributing

Bug reports and pull requests are welcome. [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)
explains how the code is organized and the pitfalls found so far (threading with java-gi,
embedding libmpv, and more). Run `./gradlew test` before sending changes.

## Credits

- [PipePipe](https://codeberg.org/NullPointerException/PipePipe) and
  [PipePipeExtractor](https://github.com/InfinityLoop1308/PipePipeExtractor), which this
  project bundles (see [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md#the-bundled-extractor)),
  and the [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) it builds on
- [java-gi](https://github.com/jwharm/java-gi) for the GTK and libadwaita bindings
- [mpv](https://mpv.io), [yt-dlp](https://github.com/yt-dlp/yt-dlp) and
  [SponsorBlock](https://sponsor.ajay.app)

## License

Piper-YT is free software under the [GNU General Public License v3.0](LICENSE). The
bundled PipePipeExtractor is also GPLv3 (see `PipePipeExtractor/LICENSE`).

