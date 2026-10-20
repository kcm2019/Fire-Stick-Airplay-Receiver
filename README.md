# AirPlay Receiver for Fire TV

Turns a Fire TV Stick into an AirPlay receiver: stream audio, mirror your
screen, or play video URLs from an iPhone, iPad, or Mac.

Current version: **1.0.19** (versionCode 20).

## Compatibility

- Fire OS 6+ (Android 7.1 / API 25 and up). Tested build target: Fire OS
  6.7.1.1 (Fire TV Stick).
- Senders: iPhone/iPad (Control Center → Screen Mirroring, or the AirPlay
  icon in any app), Mac (Display settings → AirPlay Display).

## Install on the Fire TV Stick

1. Get the APK onto the Stick. Easiest: `adb install -r
   airplay-tv-receiver-1.0.19.apk` from a computer on the same network
   (enable ADB on the Stick: Settings → My Fire TV → Developer Options →
   ADB debugging). The `-r` form upgrades in place — every build is
   signed with the same key (`debug.keystore`, alias `airplaytv`).
2. Open the "AirPlay Receiver" app once. It starts advertising immediately
   and (by default) starts itself on every boot.
3. On your Apple device, join the same Wi-Fi network, open Control Center
   → Screen Mirroring, and pick the device name shown in the app.

## What it does

- **Screen mirroring**: H.264 with hardware decoding; asks the sender for
  HEVC (H.265) when "Prefer HEVC" is on, if the Stick supports it. The
  picture is aspect-fit to the sender's real resolution (parsed from the
  SPS — the Stick's decoder reports the configured size, not the
  stream's). A Display Fit/Stretch toggle lives in the player settings.
  Note: the MediaTek decoder requires SPS/PPS codec-config buffers to
  carry the `00 00 00 01` start code — see `VideoPipeline`'s decoder
  ladder (mode 1 is the configuration that renders on this hardware).
- **Audio**: lossless ALAC (AirPlay audio mode) decoded in software,
  AAC/AAC-ELD (mirroring audio) via hardware, LPCM passthrough.
- **Video URLs**: video links sent by apps (HLS, e.g. YouTube) play in
  the built-in player on a dedicated surface.
- **Audio now-playing screen**: tvOS-style — blurred artwork backdrop,
  artwork + title/artist/album (DMAP metadata), a progress bar fed by
  the sender's RTSP `progress:` parameter, and a live FFT spectrum
  visualizer (toggleable) fed from the decoded PCM.
- **Player overlay**: appears on connect and on any remote keypress,
  auto-fades after a configurable timeout (5–30 s or Never; default
  10 s) — but stays up permanently when no device is connected.
  Controls: Pause (render-side pause; the sender keeps playing),
  Settings, Stop. Settings: Display Fit/Stretch, Stats on/off, Menu
  fade timeout, Brightness −/+ (window brightness override, shared
  with the audio screen). Focused buttons highlight orange.
- **Stats mode**: top-left live readout — codec, resolution, decoder
  mode, FPS, bitrate, frames fed/shown/dropped (mirroring); format,
  bitrate, packets, session time (audio).
- **Receiver modes**: "Mirroring + Audio" (appears as a screen) or
  "Audio Only" (appears as a speaker — set from the TV UI).
- **Settings** (main screen): device name, mirroring resolution
  (720p/1080p), HEVC preference, HLS playback, start on boot, and a
  built-in log viewer (Settings → General → View Logs).

## Limitations

- DRM-protected video (Netflix, the Apple TV app, Prime Video, etc.)
  cannot play on any third-party receiver: those apps hand over
  FairPlay-encrypted HLS, and FairPlay licensing exists only on Apple
  hardware. The Apple TV app fails with MediaPlayer error (1, -1007)
  (unparseable encrypted segments); the app detects this and shows a
  "DRM-protected" explanation instead of error-looping. Unprotected
  sources (YouTube, Safari video, personal media) play normally.
- No Apple Home app / HomeKit pairing; connection uses the legacy AirPlay
  pairing protocol, same as open-source receivers like UxPlay.
- Pairing happens on your LAN; anyone on your Wi-Fi can discover the
  receiver.

## Build

This VM cannot run Gradle, so the build is a manual pipeline (see
`build.sh`): `aapt2 compile/link` → `javac` → `d8` → `zipalign` → `apksigner`.

```sh
export JAVA_HOME=~/toolchains/jdk-17.0.20.1+1   # any JDK 17
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=~/android-sdk               # build-tools 34.0.0
./build.sh                                      # APK lands in out/
```

Version bumps: `versionCode`/`versionName` in
`app/src/main/AndroidManifest.xml` plus `APK_NAME` in `build.sh`.
Everything targets API 25 — do not use post-API-25 framework APIs
(Netty is pinned to 4.1.115.Final for the same reason: 4.2 calls
`MethodHandles.lookup()`, which does not exist on API 25).

## Layout

- `airplay-core/` — the AirPlay protocol server (ported java-airplay):
  RTSP/HTTP control, RAOP audio, mirror stream, FairPlay, mDNS.
  Notable local changes: Netty 4.1/NIO transport, DMAP metadata +
  artwork + progress parsing in `ControlHandler`, timestamp-paired
  codec-config delivery in the mirror `VideoHandler`.
- `app/` — the Android app: `service/ReceiverService` (foreground
  service + lifecycle), `player/` (`VideoPipeline` decoder ladder,
  `AudioPipeline`, `HlsPlayer`, `NowPlaying`, `AudioLevels`,
  `Stats`), `ui/` (`MainActivity` settings, `PlayerActivity`,
  `AudioActivity`, `VisualizerView`, `LogsActivity`).

Dependencies (fetched once into `libs/`, except the listed jars which are
checked in here):

- netty (TCP/RTSP/HTTP server), jmdns (mDNS), dd-plist, eddsa, curve25519,
  m3u8-parser, slf4j-api, lombok (compile time only).

## Attribution

See NOTICE.md. The protocol core is ported from
[serezhka/java-airplay](https://github.com/serezhka/java-airplay) (MIT);
the ALAC decoder is ported from Apple's open-source reference ALAC codec
(Apache 2.0).
