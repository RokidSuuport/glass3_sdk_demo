# Glass3 Audio/Video Capture and Browser Streaming

[中文](README.md) | English

This project delivers raw NV21 camera frames and raw PCM microphone frames from Glass3 to an Android app. It can also stream them one way to a PC browser over WebRTC. Run the complete solution, or reuse the capture and streaming components in an existing source project.

## Choose an entry point

| Goal | Approach | Start here |
| --- | --- | --- |
| Run the demo | Install the glasses app and start the PC receiver to view live video and hear audio in a browser; no integration code is required | [Getting started](docs/getting-started.md) |
| Access raw audio/video only | Use `GlassMediaCapture` to receive NV21 video and PCM audio for your own algorithms, encoders, or transport | [Raw media capture integration](docs/media-capture-integration.md) |
| Integrate the complete stream into an app | Use `GlassMediaStreamer` to reuse capture, signaling, and WebRTC transport to a browser | [Full streaming integration](docs/webrtc-streaming.md) |

The last two options have separate code entry points. Raw capture does not require a PC service. To integrate into your Android project, first copy and register the components as described in [Source integration](docs/source-integration.md), then copy the appropriate Kotlin or Java activity.

The detailed guides linked from this README are currently in Chinese.

## Data flow

```text
Glass3 CameraShare NV21 ─┐
                         ├─ Glass3 capture ─ WebRTC transport ─ PC browser
Glass3 SDK PCM ──────────┘                         │
                                      Node.js exchanges signaling only
```

- Video comes from Glass3 CameraShare. The default request is 1280 × 720 at 15 FPS in NV21 format; use the frame callback to determine the actual capture dimensions.
- Audio comes from the Glass3 SDK and currently supports 16 kHz, mono, 16-bit PCM. WebRTC's built-in Android recording and glasses-side playback are disabled.
- The Node.js service exchanges session descriptions and ICE candidates only. It does not store or transcode media.
- The out-of-the-box setup supports one pair of glasses and one browser on the same LAN. See [Production deployment](docs/production-deployment.md) for public-network and multiple-device considerations.

## Project structure

```text
android/glass3-media-capture      Glass3 NV21/PCM capture component
android/webrtc-transport          WebRTC sending and signaling component
android/glass3-media-streaming    Facade for starting a complete stream
android/glass-stream-sender       Installable glasses app
web-stream-receiver               PC signaling service and browser receiver
docs/code                         Complete Kotlin/Java activities
verification/source-consumer      Independent build verification for both source integration entry points
scripts                           Startup, build, and acceptance scripts
```

## Requirements

- A Glass3 device with an available Glass3 Open SDK service.
- JDK 17, Android SDK, and ADB; minimum Android API level 29, target API level 34.
- Browser streaming also requires Node.js 18 or later. The glasses and PC must be on a mutually reachable LAN, with TCP port 8080 on the PC accessible. Raw capture alone does not require these conditions.
- The first build requires access to the Android dependency repositories and npm registry configured by the project.

## Documentation index

The following guides are in Chinese:

- [Five-minute quick start](docs/getting-started.md)
- [Full streaming integration](docs/webrtc-streaming.md)
- [Raw media capture integration](docs/media-capture-integration.md)
- [Copy source into an Android project](docs/source-integration.md)
- [Configuration](docs/configuration.md)
- [Errors and troubleshooting](docs/troubleshooting.md)
- [Production deployment and extensions](docs/production-deployment.md)
- [Public API reference](docs/api-reference.md)

## Delivery scope

The project includes complete source code, an installable app, a browser receiver, source components, and buildable Kotlin/Java examples. It does not include a public TURN service, user accounts, an SFU for rooms with multiple participants, or cloud media storage. Those are production deployment capabilities, not prerequisites for raw media capture.
