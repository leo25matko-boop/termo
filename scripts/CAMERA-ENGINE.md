# Native camera transport

TermoDom uses a modified, minimal build of [go2rtc 1.9.14](https://github.com/AlexxIT/go2rtc/tree/b5948cfb25404cc5cb37b166ecaa2dca20b11d4b), under its MIT license. This is an independent integration and is not endorsed by Xiaomi. Xiaomi account authentication and camera key exchange contact Xiaomi's original servers. Camera video uses the local network; internet is still required for session keys.

The exact primary-source archive is `https://codeload.github.com/AlexxIT/go2rtc/zip/b5948cfb25404cc5cb37b166ecaa2dca20b11d4b`; SHA256 is `8db2cacfa2e8c5d585d129934b3f09ee3bfca655fdeb8f91383850adacaaaf58`. `prepare-camera-engine.py` verifies that digest before extraction and rejects mismatched patch anchors. No release binary from a third-party mirror is used.

Changes are restricted to authenticating RTSP on loopback, correcting the encoded Xiaomi two-factor query, accepting configuration through stdin, applying runtime config changes only in memory, exposing an authenticated session-map endpoint to the native host, and initializing only the camera API, RTSP and Xiaomi transport. The host binds both listeners to random `127.0.0.1` ports with independent random credentials. HTTP local authentication is mandatory. WebRTC, browser screens, WebSockets, remote shells and other go2rtc services are not initialized. Engine output is discarded; normal and verbose logging are disabled.

On Windows the host stores only Xiaomi session tokens protected by DPAPI for the current Windows user. Passwords, endpoint secrets and configuration are never passed in process arguments or written to a runtime plaintext configuration file. On Android the native host uses its app-private Keystore-backed store. Original Xiaomi credentials and provider-requested captcha or verification codes are entered in native TermoDom controls. No Google account, emulator login or API client key is needed.

Build on Windows with Go 1.24 or newer and Python 3:

```powershell
./scripts/build-camera-engine.ps1 -Destination ./windows/camera
```

Android CI invokes the same source-preparation helper, then builds the required executable ABIs using the Android NDK. The engine is a transport process launched by the native UI, not a web app. Preserve `LICENSE-go2rtc.txt` and `TERMODOM-PATCHES.txt` alongside the packaged engine. Its Go module graph and licenses remain in the pinned source archive. LibVLC is packaged separately with its own license notices by the native player dependency.

Known model compatibility is maintained by the upstream author in [go2rtc issue 1982](https://github.com/AlexxIT/go2rtc/issues/1982). Mi 360 2K and 2K Pro are listed, but firmware and network behavior still require verification with the actual user's camera. Unsupported controls remain in preparation instead of showing invented states.
