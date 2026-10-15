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

## Heat Link (rooms over the internet)

The phone's **link** button (top right of the camera view) opens Heat Link.

- **Share this camera.** Switch it on and the phone gets a room code, a QR code and a share link. Anyone with the code can
  watch from the Heat app or from the Heat page in a browser and can change the same settings the watch can (arm, sensitivity,
  zoom, torch, flip). The code is made once and **kept until you choose New code**: it survives restarts, the room reopens by
  itself when the app starts, and people who have it never need it again. (Uninstalling the app forgets it; updating keeps it.)
- **Join a room.** Type or paste a code or link, or open `heat://link/CODE`; recent rooms are remembered. The viewer screen shows
  the picture and the controls, and any alert pictures.
- **How it connects.** Rooms are a fifth carrier (`CarrierKind.WEBRTC`) under the same session protocol as Wi-Fi and
  Bluetooth, so everything above the carrier (handshake, encryption, reliable control, keyframe gating, the bitrate
  governor, which treats the internet as a 2.5 Mbps road) is unchanged and already tested. The carrier is a WebRTC data
  channel (libwebrtc, used for nothing else) found through the PeerJS signaling server, with STUN/TURN from
  `api/ice` or the page's built-in list. Module `:rooms`; the phone's side is `phone/.../rooms` and `ui/Rooms*.kt`.
- **R8.** libwebrtc reaches Java from native code through `org.jni_zero`; `rooms/consumer-rules.pro` keeps it. Without
  that rule a release build crashes the first time a viewer connects, and a debug build never shows it.

## Following motion on the watch

With several cameras, the watch jumps to whichever one detects movement (**Cameras → Follow motion**, on by default). The
decision is `core/.../FollowMotion.kt` (tested): movement alerts only, not the camera already on screen, not within 2.5 s of
the last jump. The new camera is asked for video in the same instant the alert arrives, so its first keyframe is waiting in the
decoder's feed; its picture is built underneath the current one and fades in when its first frame is drawn, and the old camera
keeps streaming until then (`WatchHub.select` / `release`). The view never goes blank. Other cameras send nothing but
control messages until one of them is shown. The watch's **Activity** page lists recent alerts from every camera with their pictures.

## Distribution

`./gradlew :phone:assembleRelease :wear:assembleRelease -PheatDist` signs with a private key kept outside the repository
(`~/.heat/release.properties`: `storeFile`, `storePassword`, `keyAlias`, `keyPassword`; **back it up, it cannot be recreated**).
Phone and watch must use the same key. Without `-PheatDist` the shared debug key is used, so a development device keeps updating
in place; the two keys cannot update each other (uninstall first). Output: `phone/build/outputs/apk/release/phone-release.apk`
(about 23 MB: arm64 and 32-bit ARM, libwebrtc included) and the watch's 2.4 MB one.

## Frame rate, alerts, pictures (added 2026-10-15)

- **Adaptive frame rate.** `FrameRateGovernor` (core, tested) turns the bitrate the loss-driven `BitrateGovernor` allows into a frame
  rate: it picks the highest of 5/10/15/20/30/45/60 fps that still gives about 0.07 bits per pixel per frame, drops at once when the
  road narrows and climbs one tier at a time after 4 s of clear road. The camera is asked for the rate directly (the AE target range,
  no encoder restart); if the phone cannot really deliver it, the measured rate caps it. Order of degradation: frames first, then
  the bitrate floor. Bluetooth profile is 320x240 up to 30 fps / 500 kbps.
- **Alerts, bulletproof.** Alerts travel on the reliable channel, the host keeps the last 10 (5 min) and replays them to any viewer
  that connects later; viewers drop duplicates. With the watch app open an alert is a white flash, a strong buzz and a red ring plus
  tag that a tap clears (it also ends blackout); with the app closed the watch posts one notification; the phone posts its own only
  when no viewer is connected. No cooldowns.
- **Pictures and clips** (`platform/Gallery.kt`, both devices). Capture takes a picture; clipping is off until switched on, then a
  record button appears and a clip ends by itself after the clip length (default 5 s). At most 20 items (oldest uncollected first),
  uncollected items deleted after 2 days; Save copies to the device's own gallery and keeps the item. All four are settings.
- **Quiet start.** The watch app's ping makes the phone start its camera service in the background (`QuietStart`); nothing is shown and
  the screen is not woken. If Android refuses, a silent notification offers a tap-to-start. The app's own screen reads the same hub.
- **Safety rule.** A viewer never shows a stale frame: no new frame for 1.5 s and the picture goes black with "No live picture".

## Modules

| Module | What it is |
|---|---|
| `:core` | Pure JVM. Crypto, the session protocol, reliable control channel, connection manager, settings model and catalog (with search), motion engine, bezel controller, zoom smoother, lens planner, camera host/client. Everything here is unit tested without a device. |
| `:platform` | Android glue: Keystore vault, direct Bluetooth (RFCOMM) and Wear Data Layer carriers, Wi-Fi binding, hints, known cameras. |
| `:glass` | Liquid glass: one AGSL shader (`res/raw/liquid_glass.agsl`), Compose host and surfaces, flat fallback, frame-time governor. |
| `:glass-preview` | Desktop tool that renders the real shader through Skia to PNG, to judge refraction without a device. |
| `:rooms` | Heat Link: PeerJS signaling client and a libwebrtc data channel as a session carrier. Phone only (libwebrtc is large). |
| `:phone` | Camera service (Camera2 -> preview, motion analysis, hardware H.264 encoder), live screen, searchable settings. |
| `:wear` | Watch hub, camera screen (bezel control, ring of buttons, cross-fade between cameras), cameras list, activity list, settings. |

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

## Hosts, receivers and the hub (2026-10-16)

One app, three jobs. Every phone is a **host** (its own camera and detection, one 8-symbol code), can be a **receiver** (watch other hosts, get their alerts) and acts as a **hub** for a paired watch.

- **Adding hosts.** On the phone: Heat Link, Join a room, type a host's code, Watch. The code is kept (up to 8) and the phone stays joined to every saved host in the background (`HostRelay`), so alerts from all of them arrive. Browsers can be hosts too (Motion camera on the site, Host a camera).
- **What the watch sees.** The watch only talks to the phone. The phone forwards ONE host's video at a time (`CameraHost.Audience.HUB`; other viewers of the phone keep its own camera). Alerts from every host reach the watch, labelled with the host's name (`Msg.Alert.hostId/hostName`).
- **Switching.** Motion on a host puts it on show, unless the view is locked. The watch's Hosts button lists the hosts (`Msg.Hosts`), a tap shows one (`Msg.PickHost`), Lock view pins it (remembered). Arm/disarm from the watch goes to the shown host; other settings still address the phone.
- **Website.** The Heat tab asks first: Host a camera or Receive. The receiver page has the watch's controls (sensitivity, zoom, full settings, detection area, photo/clip gallery, black screen, reset stream).
- **Downloads.** Signed APKs are on the GitHub release (`Heat-phone.apk`, `Heat-watch.apk`); the site links `releases/latest/download/…`. Rebuild with `-PheatDist`.
- **Anywhere.** Rooms use WebRTC; behind strict NATs they need a TURN relay. Set `CLOUDFLARE_TURN_KEY_ID` + `CLOUDFLARE_TURN_API_TOKEN` on the Vercel project (`api/ice.js`) and both the site and the app use it.

## Rooms with several hosts, and removing people (2026-10-16)

- **A room is shared.** One code, up to 8 hosts: slot 0 is the room id itself (every older code is unchanged), slots 1-7 are `id-1`..`id-7` (`RoomCode.slotId`). A host takes the first free slot (`RoomSlotHost`; on the web, `claim`), and may enter an existing room's code to join it ("Host here" in the app, "Host in a room" on the site). Receivers look for hosts with a signaling-only probe (`probeRoom` / `probeSlots`: a stub offer to every slot id; an empty slot answers EXPIRE at once) every 15 s and join each one found. The phone hub treats every host slot like any other host.
- **Everyone has full access.** Any receiver can change any host's settings, arm it, and remove people. The watch edits whichever host is on screen through the hub (`CameraHost.ConfigProxy`).
- **Removing.** Every host tells its viewers who is connected (`Msg.Roster`). Anyone can remove a viewer (`Msg.Kick(id)`) or a whole host (`Msg.Kick("")`, or `host:<key>` through the hub). The removed device is told (`Msg.Kicked`), its link stops and its app closes (web tab closes, Android task finishes; a removed host stops hosting and the phone app ends). A removed viewer is turned away if it comes back until that host restarts; a new code locks out everyone. Removing is two taps in every UI.
- **Fixed:** the app's room host never released finished connection attempts, so after eight in a room's life it refused every new viewer until restarted.
