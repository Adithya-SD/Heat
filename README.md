# Heat — P2P camera surveillance

Heat streams a camera directly to a viewer with WebRTC. There are no accounts and no video server: a small PeerJS-compatible signaling relay only helps the two browsers find each other. Motion detection runs in the host browser.

## Reliable connection flow

1. Open the deployed **HTTPS** URL on the camera device. Camera permissions and WebRTC are blocked on ordinary HTTP (except `localhost`).
2. Choose **Host a camera**, allow camera access, and keep the room code and PIN visible.
3. On the second device open the exact same HTTPS URL, choose **Watch a feed**, and enter both values. Use the QR/share invite to avoid transcription errors.
4. Leave both pages open until the status says **Live**. Heat retries signaling and ICE negotiation automatically if a phone briefly changes networks.

The connection now includes:

- Per-session signaling tokens for both host and viewer (the viewer no longer attempts to connect with an undefined token).
- ICE candidate queuing, so candidates arriving before the SDP description are not silently discarded.
- Public TURN fallback in addition to STUN, which improves connections across carrier NATs, VPNs, and restrictive routers.
- Automatic viewer retry and clearer host/offline status.

## If it still does not connect

- Confirm both devices have internet access and are using the same deployed URL.
- Disable VPN/ad-blocker WebRTC blocking and try a different network or browser.
- On iOS, use Safari and grant camera permission; on Android, use Chrome and grant camera permission.
- Make sure the host room code is exactly the one shown and regenerate it if the room was already used.
- A host must remain on the page; closing or background-killing it ends the camera stream.

For local development, serve `public/` over HTTP with a static server. Camera access will only work on `localhost` locally; use an HTTPS deployment when testing from another device.
