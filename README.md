# Heat — video calls and motion cameras, device to device

One static page (`public/index.html`), no accounts and no video server. Media goes straight between the two devices over WebRTC; a PeerJS signaling relay only helps them find each other.

## Two ways to use it

Pick one on the home screen (**Video call** is the default):

| Mode | What it does |
| --- | --- |
| **Video call** | 1:1 two-way video and audio. Mute, camera on/off, front/back camera, tap your own picture to swap big/small, call timer and latency, screen share (desktop browsers), text chat, QR / invite link / native share. |
| **Motion camera** | One device is the camera and watches for movement inside a box you can drag and resize; any number of viewers get live video, motion alerts with a snapshot, sound/vibration/notifications, and can change sensitivity remotely. |

The host chooses the mode; whoever joins adopts it automatically.

## Using it

1. Open the deployed **HTTPS** URL on both devices (camera access is blocked on plain HTTP except `localhost`).
2. On the first device choose a mode and tap **Start…**. You get a room code, a QR code and an invite link.
3. On the second device type the code under **Join a room**, scan the QR, or open the invite link.
4. A video-call room holds two devices; a third gets "Room is full" and keeps waiting for a spot. Motion-camera rooms allow many viewers.

## How the connection works (and why it used to hang on "Connecting…")

- **STUN + TURN.** STUN finds a direct path between the devices. When there isn't one (a phone on mobile data, carrier NAT, strict routers, VPNs) the media has to be relayed through a TURN server. PeerJS ships TURN servers by default, but passing a custom `config` **replaces** that list, so an ICE list with STUN only leaves devices on different networks stuck on "Connecting…". The list in `index.html` (`ICE`) now includes TURN.
- **Don't restart a negotiation that is still working.** A relayed connection can take several seconds. The viewer used to tear the connection down and start over after 6 s, which could repeat forever; it now waits up to 15–20 s before retrying.
- **Hung signaling socket.** If the signaling WebSocket neither opens nor errors, the page now gives up after 10 s and opens a fresh one, on both host and viewer.
- **Honest status.** The viewer shows which stage it is in (reaching the server → looking for the host → connecting video → in call) and, after 20 s, a hint about the likely cause.

The TURN entries are public, shared, best-effort relays. For something you depend on, put your own TURN credentials (Cloudflare Calls, Metered, coturn, …) in the `ICE` constant near the top of the script.

## If it still does not connect

- Both devices must be online and use the same room code; the host page must stay open.
- Turn off VPN / ad-blockers that block WebRTC, or try another network or browser.
- iOS: use Safari; Android: use Chrome; allow camera and microphone when asked.
- If a call connects but you hear nothing on a device that joined from an invite link, tap the screen once (browsers block sound until you interact).

## Deploying

`public/` is a plain static site; Vercel serves it as-is (no build step). For local work, serve `public/` with any static server — camera access works on `localhost`, but use an HTTPS deployment when testing from another device.
