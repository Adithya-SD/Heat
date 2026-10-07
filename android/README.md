# Heat for Android (phone camera + watch viewer)

A rewrite of the native Heat apps. The phone watches a room and streams to the watch; the watch controls it
(zoom, tracker box, sensitivity, arming) with the rotating bezel. This is separate from the web app in `public/`
and does not speak its protocol yet (see "Not done").

## Install

Both apps share one application id (`app.heat.camera`) and one signing key. The Wear OS Data Layer only
connects apps that share both, so never add an `applicationIdSuffix`.

```bash
adb -s <phone>  install -r Heat-phone.apk
adb -s <watch>  install -r Heat-watch.apk
```

1. Open **Heat** on the phone first and allow the camera. It creates the household key and hands it to the
   watch over the bonded Bluetooth link.
2. Open **Heat** on the watch. It finds the phone over Bluetooth and over Wi-Fi at the same time.
3. On the phone, tap **Arm**. On the watch, turn the bezel.

`Heat-*-DEBUG-fallback.apk` are unminified builds of the same code, for when a release build misbehaves.
The old apps (`com.heat.sentry`, `com.heat.controller`) are untouched and can stay installed.

## Modules

| Module | What it is |
|---|---|
| `:core` | Pure JVM. Crypto, the session protocol, reliable control channel, connection manager, settings sync, motion engine, bezel controller, camera host/client. Everything here is unit tested without a device. |
| `:platform` | Android glue: Keystore vault, Wear Data Layer carrier, pairing-key sync, network watcher, known cameras. |
| `:glass` | Liquid glass: one AGSL shader (`res/raw/liquid_glass.agsl`), Compose host and surfaces, flat fallback, frame-time governor. |
| `:glass-preview` | Desktop tool that renders the real shader through Skia to PNG, to judge refraction without a device. |
| `:phone` | Camera service (Camera2 -> preview, motion analysis, hardware H.264 encoder), live screen, settings. |
| `:wear` | Watch hub, hardware decoder, camera screen with bezel control, cameras list, settings. |

## How the connection works

- **One protocol, any carrier.** TCP (Wi-Fi) and the Wear Data Layer channel (Bluetooth) both carry the same
  authenticated, encrypted session. A viewer dials every carrier it can; the best one carries traffic and the
  others stay warm, so a failover is a switch, not a reconnect.
- **Pairing is a shared key, never a token on the wire.** The handshake proves possession of the key in both
  directions with fresh nonces; each session derives new AES-GCM keys. Discovery beacons are authenticated.
- **Commands are delivered exactly once, in order,** across dead links and carrier switches (numbered,
  acknowledged, retransmitted). Video is never queued: a slow link drops frames and the receiver waits for a
  keyframe rather than ever showing a smeared picture.
- **Settings have one owner.** The camera owns its configuration; viewers send small JSON patches and render
  what the camera echoes back, with their own un-acknowledged edits laid over it. A viewer is bound to one
  camera, so editing the wrong camera is not possible by construction.

## Build and test

```bash
./gradlew :core:test :glass:testDebugUnitTest       # 151 JVM tests, no device needed
./gradlew :phone:assembleRelease :wear:assembleRelease
./gradlew :glass-preview:run                         # writes PNGs to glass-preview/build/out
```

Needs JDK 17 and the Android SDK (compileSdk 36). On machines where Java prefers IPv6 and downloads time out,
set `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`.

## Troubleshooting

On the watch, **menu -> Settings -> Connection** shows the link state, carrier, round trip, last handshake
failure and the pairing code. The pairing code must be identical on phone (Settings -> This camera) and watch.

- *"Waiting for your phone"*: open Heat on the phone once, with Bluetooth connected to the watch.
- *"Not paired"* (red): the two devices have different keys. Reinstall the watch app or clear its data.
- *Connects over Bluetooth but not Wi-Fi*: both must be on the same network; some routers isolate clients.

## Not done yet

Photo and clip capture and the events gallery, microphone streaming, tamper detection, relaying a camera
through the phone when the watch is off Wi-Fi, the link to `heat-wheat.vercel.app` (PeerJS/WebRTC interop), and
measured latency numbers on real hardware (none have been taken yet).
