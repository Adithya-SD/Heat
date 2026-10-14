# Heat for Android (phone camera + watch viewer)

A rewrite of the native Heat apps. The phone watches a room and streams to the watch; the watch controls it
(zoom, tracker box, exposure, focus, sensitivity, arming) with the rotating bezel. This is separate from the web app
in `public/` and does not speak its protocol yet (see "Not done").

## Install

Both apps share one application id (`app.heat.camera`) and one signing key. The Wear OS Data Layer only
connects apps that share both, so never add an `applicationIdSuffix`, and never install one app from a different
keystore than the other (Android refuses the update, and the two would not find each other if it did not).

```bash
adb -s <phone>  install -r Heat-phone.apk
adb -s <watch>  install -r Heat-watch.apk
adb -s <phone>  shell cmd package compile -f -m speed app.heat.camera    # optional, but see "Start-up speed"
adb -s <watch>  shell cmd package compile -f -m speed app.heat.camera
```

1. Open **Heat** on the phone first and allow the camera and Bluetooth. It creates the household key and hands it to
   the watch.
2. Open **Heat** on the watch and allow Bluetooth when asked (that is what lets it use the fast direct route).
3. On the phone, slide the orb to arm. On the watch, tap the top chip to choose what the bezel adjusts, and turn it.

After a phone restart open the app once: Android does not allow a camera service to start itself from the background.

`Heat-*-DEBUG-fallback.apk` are unminified builds of the same code, for when a release build misbehaves.

## The watch

The chip at the top is the menu. **Tap** it for the next control, **long-press** for the previous; the **bezel**
changes whatever the chip shows. The gauge round the edge shows the same value. Controls, in order: zoom, tracker,
sensitivity, muddiness, exposure, flashlight, focus, white balance, ISO, shutter, night, lock exposure, camera
(front/back). Controls this camera does not have are skipped.

Every bezel click is applied: there is no cooldown and nothing is dropped (a fast spin that arrives as one event of
three clicks steps three times). The phone and the watch word every value identically.

**Tracker:** choosing it hides every button so nothing is in the way. Touch (or drag) puts the box where your finger
is, the bezel makes it bigger or smaller, and the camera follows live. The **Back button** moves on to the next
control and brings the buttons back.

## Modules

| Module | What it is |
|---|---|
| `:core` | Pure JVM. Crypto, the session protocol, reliable control channel, connection manager, settings model and catalog (with search), motion engine, bezel controller, zoom smoother, lens planner, camera host/client. Everything here is unit tested without a device. |
| `:platform` | Android glue: Keystore vault, direct Bluetooth (RFCOMM) and Wear Data Layer carriers, Wi-Fi binding, hints, known cameras. |
| `:glass` | Liquid glass: one AGSL shader (`res/raw/liquid_glass.agsl`), Compose host and surfaces, flat fallback, frame-time governor. |
| `:glass-preview` | Desktop tool that renders the real shader through Skia to PNG, to judge refraction without a device. |
| `:phone` | Camera service (Camera2 -> preview, motion analysis, hardware H.264 encoder), live screen, searchable settings. |
| `:wear` | Watch hub, decoder, camera screen with bezel control, cameras list, settings. |

## How the connection works

- **One protocol, any carrier.** TCP (Wi-Fi), a direct Bluetooth socket and the Wear Data Layer channel all carry
  the same authenticated, encrypted session. A viewer dials every carrier it can; the best one carries traffic and
  the others stay warm, so a failover is a switch, not a reconnect. The direct Bluetooth socket skips Play services
  (whose first call alone costs 1.5 to 5 s on a watch and a few hundred ms per message); the Data Layer is the
  fallback, started only if the direct route has not made it within a moment, and dropped again once the direct one
  is proven.
- **Pairing is a shared key, never a token on the wire.** The handshake proves possession of the key in both
  directions with fresh nonces; each session derives new AES-GCM keys. The dialer starts sending as soon as its proof
  is out, without waiting for the server's confirmation (one flight less). A wrong key is still reported as such.
- **Commands are delivered exactly once, in order,** across dead links and carrier switches (numbered,
  acknowledged, retransmitted). Video is never queued: a slow link drops frames and the receiver waits for a
  keyframe rather than ever showing a smeared picture.
- **Settings have one owner.** The camera owns its configuration; viewers send small JSON patches and render
  what the camera echoes back, with their own un-acknowledged edits laid over it. A viewer is bound to one
  camera, so editing the wrong camera is not possible by construction.
- **Wi-Fi on a watch must be asked for.** A watch's default network is its Bluetooth proxy; the app requests a Wi-Fi
  network while someone is looking at a camera and binds its sockets to it. Phone and watch must be on the **same
  Wi-Fi network** for the fast road; otherwise video goes over Bluetooth, at a smaller size and rate.

## Start-up speed

Layers of assumption, each cheap to be wrong about:

1. **Compiled code.** An APK that is only installed runs interpreted. `cmd package compile -f -m speed` (above) took
   the watch's first picture from about 9 s to about 3 s after launch, before anything else was changed.
2. **The process starts early and stays.** The hub starts with the process (not with the first screen), and a
   foreground link service keeps the process, the connection and the decoder alive for ten minutes after the app
   is left, so the next open is a message and a keyframe away from a picture.
3. **Hints** remembered from last time: the phone's Bluetooth address (dial it first), the camera's picture geometry
   (build the decoder before the camera has said anything), Wi-Fi history.
4. **A still** of the last picture is drawn the moment the screen opens and fades into live video.
5. **Frames are kept** (the current group of pictures) so a decoder that starts late replays them and shows a picture
   at once, instead of asking for a keyframe and waiting a round trip.
6. **Fewer flights:** the optimistic handshake, and a keyframe rate limit that delays rather than drops.

The phone's own start is fast: listeners accept in a few milliseconds, the camera is open in about 150 to 400 ms, the
first encoded frame follows within about a second, and a keyframe goes out about 5 to 15 ms after a viewer asks.

## Zoom

Every zoom request goes through a smoother (two critically damped springs on the logarithm of the zoom, stiffness
following the distance to go), advanced once per captured frame from the frame's own timestamp. This phone exposes
its lenses as separate cameras (0 main, 2 ultrawide) and cannot open two rear cameras at once, so changing lens means
reopening the camera (about 0.8 s); the app keeps the last frame on screen, blurred behind and still moving with the
zoom, and dissolves it into the new lens. Phones that expose one logical camera spanning the lenses use it directly.
The lens plan and what was found are on the phone under Settings, This camera, Camera diagnostics.

## Build and test

```bash
./gradlew :core:test :glass:testDebugUnitTest       # JVM tests, no device needed
./gradlew :phone:assembleRelease :wear:assembleRelease
./gradlew :glass-preview:run                         # writes PNGs to glass-preview/build/out
```

Needs JDK 17 and the Android SDK (compileSdk 37). On machines where Java prefers IPv6 and downloads time out, set
`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`.

## Troubleshooting

On the watch, **menu -> Settings -> Connection** shows the link state, carrier, round trip, last handshake
failure and the pairing code. The pairing code must be identical on phone (Settings -> This camera) and watch.

- *"Waiting for your phone"*: open Heat on the phone once, with Bluetooth connected to the watch.
- *"Not paired"* (red): the two devices have different keys. Reinstall the watch app or clear its data.
- *Bluetooth only, slow:* put the watch and the phone on the same Wi-Fi network.
- *The watch never uses the direct Bluetooth route:* it asks for Bluetooth permission the first time; if that was
  refused it falls back to the Data Layer (slower to connect).

## Not done yet

Photo and clip capture and the events gallery, microphone streaming, tamper detection, relaying a camera
through the phone when the watch is off Wi-Fi, the link to `heat-wheat.vercel.app` (PeerJS/WebRTC interop), a
two-sensor cross-fade between lenses (this phone's hidden logical cameras make it impossible here; the planner and
the blend math are written and tested for phones that expose them), and measured latency on a shared Wi-Fi network.
