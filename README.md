# Veed TV

A live-TV app for Android TV, Google TV and Fire TV, built to be driven entirely by a TV remote
by people who never set it up. It plays free live channels listed by
[iptv-org/iptv](https://github.com/iptv-org/iptv): about 400 of them in the bundled lineup, in six
categories (Malayalam, Movies, Discovery, Music, English News, Sports).

Kotlin, Jetpack Compose and Media3 ExoPlayer; about 2.5 MB. Runs on Android 7.1 and newer
(`minSdk 25`): Android TV, Google TV, and Fire TV with Fire OS 6, 7 or 8. Amazon's Vega OS
sticks can't run Android apps.

## What it does

- Opens on the last channel watched, like a set-top box; ▲▼ (or CH+/CH−) change channel, and
  holding them flips fast. Channels that failed in the last 15 minutes are skipped.
- A channel list by category, with ★ Favourites as the first category, and number entry from
  remotes that have digits.
- A banner with NOW (and a progress bar) and NEXT when programme listings are available.
- Up to four links per channel: a link that drops is reconnected, a dead one hands over to the
  next, and a channel with none left retries quietly (30 s, 1, 2, then every 5 minutes).
- Prefers the category's audio language when a stream carries several.
- Scales its layout to any TV screen, and stops streaming when it leaves the screen.
- In-app updates from your own server, checked by size and SHA-256 and installed by Android only
  if signed with the same key.

## Where the channels come from

The app works in one of two ways, and switches between them by itself.

**With a server (optional).** A server that tests every link nightly and serves four files over
HTTP: `lineup.json`, `epg.json`, and for updates `veed-tv.json` and `veed-tv.apk`. The app asks
for the lineup hourly and the guide every 30 minutes while it is on screen, with
`If-Modified-Since`, so an unchanged file costs a 304. Failures are retried after 1, 2, 4…
minutes. It tries the configured servers in order, the one that answered last time first, and
keeps the last good copy of each file (written atomically). The server the original build uses is
a small private Python service and isn't part of this repo; anything that serves these files
works.

**Without one, or when it hasn't answered for a day.** The app refreshes links itself from
iptv-org (`data/IptvOrg.kt`): it fetches `streams.json` and `feeds.json` (about 1.2 MB
compressed) at most every six hours, stream-parses them keeping only its own channels, and builds
a list with the same channels, numbers and categories as the last tested lineup (the APK bundles
one). Per channel: the tested links iptv-org still lists, in their tested order, then iptv-org's
other links in the category's language, best picture first and geo-blocked ones last; four at
most. The channel list then says "links not tested". There's no programme guide in this mode.
The server's list wins again the moment it answers.

### File formats

`lineup.json` (also bundled as `app/src/main/assets/lineup.json`):

```json
{
  "version": 1,
  "generated": "2026-10-20T13:02:04+00:00",
  "categories": [{ "id": "malayalam", "name": "Malayalam", "language": "mal" }],
  "channels": [{
    "number": 1, "id": "Asianet.in", "name": "Asianet", "category": "malayalam",
    "logo": "https://…",
    "streams": [{ "url": "https://…/index.m3u8", "type": "hls", "userAgent": "…", "referrer": null, "height": 1080 }]
  }]
}
```

Channel ids are iptv-org's; `language` is ISO 639-2. Channels are sorted by `number`, and
`streams` best first. `epg.json` is `{"channels": {"<channel id>": [[start, stop, "title"], …]}}`
in epoch seconds, sorted by start. `veed-tv.json` announces a release:
`{"versionCode": 3, "versionName": "0.3.0", "sha256": "<hex>", "size": 2545540, "notes": "…"}`.

## Building

JDK 17 and the Android SDK (Android Studio's bundled JDK works).

```sh
./gradlew :app:assembleDebug      # installable at once, signed with your debug key
./gradlew :app:assembleRelease    # R8-optimised, much smoother on weak TVs; needs a release key
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew :app:lintRelease` checks for calls newer than `minSdk`; run it after touching
platform APIs, since Fire OS 6 is Android 7.1.

### Configuration

Machine-local settings go in `local.properties` at the repo root, which is never committed:

| Key | Used by | Meaning |
| --- | --- | --- |
| `veedtv.serverUrls` | the app | Comma-separated base URLs of your server(s), e.g. `http://192.0.2.10:8787`. Leave it out to run on the bundled lineup and iptv-org alone. `-Pveedtv.serverUrls=…` overrides it. |
| `veedtv.publishHost` | `tools/publish-release.sh` | The ssh destination that serves the files, `user@host`. |
| `veedtv.publishDir` | `tools/publish-release.sh` | The directory there that the server serves. |
| `veedtv.releaseCertSha256` | `tools/publish-release.sh` | The SHA-256 of your release certificate; the script refuses any other. |

### Release key

Release builds are signed with the key described by `~/.config/veed-tv/release.properties`,
outside the repo:

```properties
storeFile=/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without it the release build comes out unsigned rather than falling back to a debug key. Create
one with `keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias veed-tv -keyalg RSA
-keysize 4096 -validity 36500`, keep the file and its password private, and back them up: Android
installs an update only over a copy with the same signature. For the same reason a fork signed
with its own key can't update, or be updated by, someone else's builds; give a fork its own
`applicationId` if both should be installable side by side.

### Publishing an update

```sh
# bump versionCode and versionName in app/build.gradle.kts, then:
tools/publish-release.sh "One plain sentence about what changed."
```

The script builds, refuses an APK not signed with your key or not newer than the published one,
and copies `veed-tv.apk` and then `veed-tv.json` into `veedtv.publishDir`. Within six hours, or
the next time it is opened, each TV says once that a new version is ready and adds
**Update Veed TV** at the end of the channel list. The first time, Android asks to allow Veed TV
to install apps.

## Using it

Everything works with ▲▼◀▶, OK and BACK, which every remote has, including HDMI-CEC TV remotes
and the Google TV and Fire TV phone apps.

| Button | Watching full screen | In the channel list |
| --- | --- | --- |
| ▲ / ▼ (or CH+ / CH−) | Next / previous channel (hold to flip fast) | Move up / down |
| OK | Open the channel list | Watch the highlighted channel |
| ◀ / Menu / Guide | Open the channel list | ◀ back to the categories; Menu or Guide closes |
| ▶ / Info | Show what's on (banner) | Add / remove ★ Favourite |
| 0–9 | Type a channel number | Type a channel number |
| BACK | Hide the banner; press twice to close the app | Close the list |
| Play/Pause | Pause; OK or Play returns to live | — |
| Last channel | Previous channel | — |

Held keys repeat only for changing channel or moving through a list, so a long press on BACK or
OK can't close the app or click something by accident.

### Channel numbers in the bundled lineup

| Numbers | Category |
| --- | --- |
| 1–99 | Malayalam (1 Asianet, 2 Surya TV, 3 Mazhavil Manorama, 11 Asianet News, 12 Manorama News…) |
| 100–299 | Movies (Star Movies, Sony Pix, Movies Now, HBO…) |
| 300–449 | Discovery (Discovery, Nat Geo, Animal Planet, History TV18…) |
| 450–649 | Music (Vevo, MTV, XITE…) |
| 650–699 | English News |
| 700–749 | Sports |

Favourites and the last channel are remembered by number, so they survive the server swapping
one feed of a channel for another.

## Code tour

| File | What it holds |
| --- | --- |
| `MainActivity.kt` | Start-up, the fetch schedule, key dispatch, layout scaling |
| `ui/TvController.kt` | Everything the remote does: zapping, numbers, favourites, the list |
| `player/TvPlayer.kt` | ExoPlayer set up for live TV: link fallback, reconnects, quiet retries |
| `ui/Guide.kt`, `ui/Overlays.kt`, `ui/Theme.kt` | The channel list, banner and cards |
| `data/LineupRepository.kt` | Server fetches, conditional GETs, saved copies |
| `data/IptvOrg.kt` | The fallback that builds links from iptv-org |
| `update/Updater.kt` | In-app updates through Android's PackageInstaller |
| `tools/branding/` | The icon and banner sources, and the script that renders them |

## Notes from testing

On a Raspberry Pi 4 (2 GB) with LineageOS 18.1, which has no hardware video decoder: Android's
software H.264 decoder played 1080p25 at 5–10.7 Mbit/s with no dropped frames, so the picture
isn't capped; the app uses about 117 MB while playing. Remote keys arrive as ordinary key events
(HDMI-CEC shows up as a virtual input device); `adb logcat -s VeedTV` logs every key, which is how
to see what a remote really sends. Wikimedia refuses anonymous image requests, so logos are
fetched with a named User-Agent (`VeedTvApp.LOGO_USER_AGENT`).

For Fire OS 6 (Android 7.1) the app avoids `java.time`, guards `Bitmap.Config.HARDWARE`, ships
plain PNG launcher icons beside the adaptive one, and bundles Let's Encrypt's ISRG Root X1 and X2
(`res/xml/network_security_config.xml`) for stream hosts using its certificates.

## Where the links come from

Stream links are user-submitted to iptv-org, a community list of publicly reachable streams. This
repo hosts no video and makes no claim about any stream's licensing; some links may not be
published by their broadcasters. Check a channel's terms before relying on or redistributing it.
