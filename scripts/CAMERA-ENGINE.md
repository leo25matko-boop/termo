# Native camera transport

TermoDom uses a modified, minimal build of [go2rtc 1.9.14](https://github.com/AlexxIT/go2rtc/tree/b5948cfb25404cc5cb37b166ecaa2dca20b11d4b), under its MIT license. This is an independent integration and is not endorsed by Xiaomi. Xiaomi account authentication and camera key exchange contact Xiaomi's original servers. Camera video uses the local network; internet is still required for session keys.

The exact primary-source archive is `https://codeload.github.com/AlexxIT/go2rtc/zip/b5948cfb25404cc5cb37b166ecaa2dca20b11d4b`; SHA256 is `8db2cacfa2e8c5d585d129934b3f09ee3bfca655fdeb8f91383850adacaaaf58`. `prepare-camera-engine.py` verifies that digest before extraction and rejects mismatched patch anchors. No release binary from a third-party mirror is used.

Changes authenticate RTSP on loopback, correct the encoded Xiaomi two-factor query, accept strict JSON configuration through stdin, apply runtime changes only in memory, expose private session and restricted camera-control endpoints, and initialize only the camera API, RTSP and Xiaomi transport. The host binds both listeners to random `127.0.0.1` ports with independent random credentials. HTTP local authentication is mandatory. WebRTC, browser screens, WebSockets, remote shells and other go2rtc services are not initialized. Engine output is discarded; normal and verbose logging are disabled.

On Windows the host stores only Xiaomi session tokens protected by DPAPI for the current Windows user. Passwords, endpoint secrets and configuration are never passed in process arguments or written to a runtime plaintext configuration file. On Android the native host uses its app-private Keystore-backed store. Original Xiaomi credentials and provider-requested captcha or verification codes are entered in native TermoDom controls. No Google account, emulator login or API client key is needed.

Build on Windows with Go 1.24 or newer and Python 3:

```powershell
./scripts/build-camera-engine.ps1 -Destination ./windows/camera
```

Android CI invokes the same source-preparation helper, then builds the required executable ABIs using the Android NDK. The engine is a transport process launched by the native UI. Preserve `LICENSE-go2rtc.txt`, `LICENSE-homebridge-xiaomi-go2rtc.txt`, and `TERMODOM-PATCHES.txt` alongside the packaged engine. Its Go module graph and licenses remain in the pinned source archive. LibVLC is packaged separately with its own license notices by the native player dependency.

The restricted MIoT interface independently derives readable/writable properties, actual property IDs, and value options from the camera model's public [MIoT schemas](https://miot-spec.org/miot-spec-v2/instances?status=released). It verifies the device and model against the original Xiaomi account before each control operation. Only known camera settings are exposed; reset, format, firmware updates, arbitrary RPC, and arbitrary MIoT IDs are excluded. A setting is enabled only after a successful typed read. Each explicit write sends one provider request and reads the property back; the native UI distinguishes confirmed values from unknown or rejected changes. SD card space units are shown only if declared by the model schema. Detection times become editable only when the camera actually returns strict `HH:mm` values.

Protocol and motor transport references include [homebridge-xiaomi-go2rtc at commit 66da2a01eb3a31d100c0fea39901b1683aa4c2e8](https://github.com/moguennouni/homebridge-xiaomi-go2rtc/tree/66da2a01eb3a31d100c0fea39901b1683aa4c2e8), MIT, copyright 2026 Amine GUENNOUNI. The narrow motor-step adaptation supports only `chuangmi.camera.026c02` and `chuangmi.camera.026c05`. It requires an active MISS video connection, limits requests to one bounded direction step per 600 ms, and reports transport acknowledgement separately from sending a request. Other models retain the prepared PTZ section. No continuous movement or undocumented motor commands are exposed.

Known model compatibility is maintained by the upstream author in [go2rtc issue 1982](https://github.com/AlexxIT/go2rtc/issues/1982). Mi 360 2K and 2K Pro are listed, but firmware and network behavior still require verification with the actual user's camera. Unsupported controls remain in preparation instead of showing invented states.
