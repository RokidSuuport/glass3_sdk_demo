# Hazard Recognition Demo: Fire Safety in Small Premises

[中文](README.md) | English

An independent glasses APK with package name `com.rokid.industry.hazardrecognition` and display name “隐患识别” (Hazard Recognition). The glasses call a vision model directly over Wi-Fi without a phone app. The existing glasses and phone demos are unaffected.

## Example usage notice

This demo provides references for SDK calls, code implementation, and usage. Model integration, deduplication, regulation mapping, and UI presentation are example approaches, not required designs. Developers can adapt or replace them for their business needs and must validate their own applications.

## Interface and controls

The layout targets a 480×640 glasses screen: a 128×72 dp live preview at the upper left, running/deduplication status on the right, and a scrollable vertical table below with 12 sp text. The left column contains “Hazard / Regulatory reference / Corrective action”; the right column shows the latest recognition result. There is no bottom Wi-Fi button or operation hint area.

- Tap the touchpad (`Enter` / `DPAD Center`): Manually recheck, bypassing the frame cache. A second request is not started while analysis is in progress.
- Swipe left/right (`DPAD Left` / `DPAD Right`): Navigate the table.
- Long-press the touchpad: Pause or resume automatic recognition. Requests already sent still update records when they finish.
- Press Back to exit. Moving to the background releases the camera and cancels network requests and pending frames. The latest result's 10-second lifetime continues to elapse. Hazard summaries remain in memory, but frame caches are cleared; restarting the process also clears the summaries.
- Configure Wi-Fi in the glasses system settings; the app does not provide a Wi-Fi settings entry point.

The table shows only the latest successful result. Each response replaces the entire table rather than appending history. A batch contains at most three hazards, numbered consistently in the right column. A new result indicating no hazard or an unknown outcome clears the previous hazards. After each successful response, results and sampling time are displayed for up to 10 seconds. New results replace them immediately and restart the timer; without a new result, the table clears and returns to the waiting state. Duplicate frames and failed requests do not extend the display period, and returning to the foreground does not restore expired results. At most 30 hazard summaries are retained internally for deduplication; they are never rendered as a history list.

Analysis status is shown as “识别中” (Recognizing), without a processed-frame counter. A duplicate frame containing a successfully recognized hazard shows “该隐患重复，已收录” (Duplicate hazard, already recorded). A duplicate frame with no hazards shows “画面重复，已识别” (Duplicate scene, already recognized). Frames still being processed or associated with failed requests are not marked as recorded.

## Sampling, batch analysis, and deduplication

The camera prefers an available size whose longer side is no more than 1280 and shorter side no more than 720, preserving the landscape/portrait orientation returned by the SDK. It requests pure NV21 callbacks at 15 FPS. If no suitable size is available, it uses the SDK default. Preview and encoding use the actual dimensions from frame callbacks. With `sampleFps=3`, the app computes a lightweight Y/V/U thumbnail feature from the latest NV21 frame every 334 ms, targeting roughly three locally screened frames per second rather than checking once every 10 seconds.

1. **Frame deduplication:** Compare color and luminance features against frames being analyzed, queued, or recently analyzed successfully, and skip similar scenes.
2. **Request batching:** Include at most three distinct new frames in one request. Only one cloud request is active at a time. Keep at most the latest three frames in the pending queue and discard frames older than two seconds before sending, preventing a backlog on slow networks.
3. **Hazard deduplication:** Ask the model to report the same fire-safety issue at the same location and on the same object only once, using existing record IDs as references. The glasses then compare hazard category, object/location, description, and scene features before updating the internal cache. The UI displays only the current result.
4. **Rechecks and retries:** Cache stable scenes for at most 30 seconds and `unknown` results for only three seconds. Manual rechecks bypass the cache. Failed requests do not enter the success cache. Network/API failures use backoff intervals of 2, 4, 8, 16, and 30 seconds instead of retrying three times per second.

Three frames per second is a local sampling target. Actual cloud throughput depends on network, encoding, and inference time; it does not guarantee three completed analyses per second. Deduplication is approximate: large head movements may cause the same issue to be checked again, while small changes may temporarily be skipped. Periodic rechecks reduce omissions but cannot guarantee elimination of duplicates or missed hazards.

DeepSeek [context caching](https://api-docs.deepseek.com/guides/kv_cache/) reuses input computation; it is not business-level frame/hazard deduplication. This demo implements deduplication through glasses-side logic and structured prompts.

### Integration recommendation: deduplicate on the server

**For production applications, perform frame and hazard deduplication on your own server** to manage records centrally, support multiple devices, and adjust policies for your business. Developers should design this with their own systems; this demo does not include server-side deduplication.

The demo provides lightweight glasses-side deduplication for standalone demonstrations, reducing repeated uploads and model calls. Its cache is in memory only, is cleared on process restart, cannot be shared across devices, and is affected by head movements, lighting, and similar scenes.

## Fire-safety scope and regulatory references

The `prompt` in [InspectionProtocol.kt](app/src/main/java/com/rokid/industry/hazardrecognition/InspectionProtocol.kt) defines the recognition scope and result format for the Chinese fire-safety category known as “九小场所” (nine categories of small premises). `request()` includes it in the system message. Developers can adapt it to their own scenarios.

Regulatory references use a local example mapping by recognition category in [RegulationCatalog.kt](app/src/main/java/com/rokid/industry/hazardrecognition/RegulationCatalog.kt); the model does not directly generate legal provisions.

For production use, developers should integrate and maintain their own regulation database or knowledge base, selecting applicable references for their industry, region, and business requirements. This demo only illustrates recognition and presentation.

## SDK integration examples

The project uses service connection, client registration, and camera sharing from `com.rokid.security:glass3.open.sdk`. These calls are concentrated in [InspectionActivity.kt](app/src/main/java/com/rokid/industry/hazardrecognition/InspectionActivity.kt). Read and reuse them in the following order.

### 1. Bind the service and wait for the client to be ready

```text
onResume
  → GlassSdk.isReady()
  → If not ready: GlassSdk.bindSecurityService(applicationContext, callback)
  → IServiceConnectionCallback.onServiceConnected()
  → GlassSdk.registerClient("HazardRecognition", callback)
  → IClientCallback.onReady()
  → startCamera()
```

`isReady()` indicates only that the service is connected. If it returns `true`, proceed directly to client registration. `onServiceConnected()` signals service connection; this example starts camera calls only in `onReady()`. `HazardRecognition` is a business client name, not a key. SDK callbacks update the UI on the main thread, and session IDs exclude stale callbacks that arrive after leaving the screen.

### 2. Query dimensions and start shared NV21 output

| SDK method / parameter | Value and purpose in this example |
| --- | --- |
| `CameraShareHelper()` | Create the camera-sharing helper, owned by the activity and used to release the same session on exit. |
| `getSupportedPreviewSizes()` | Return width, height, and portrait orientation. `PreviewSizeSelector.select()` chooses the largest size with even dimensions, longer side ≤1280, and shorter side ≤720, preserving the original width/height order. |
| `initNv21ExportWithConfig(enableMix, config, callback)` | Start shared frame output with configuration. |
| `enableMix=false` | Receive only camera content, without the glasses screen overlay. |
| `CameraShareConfig.previewWidth / previewHeight` | Use the selected dimensions, or pass 0 for both to retain SDK defaults when no suitable size is available. |
| `previewTargetFps=15` | Request 15 FPS camera output. The app independently controls inspection sampling at a target of about 3 FPS. |
| `enableVideoStabilization=false`, `zoomLevel=1` | Disable stabilization and use 1× zoom. |

This excerpt shows how the project hands off data in the NV21 callback. `session`, `frameLock`, `latest`, and `preview` are activity-owned state; see the source for the complete lifecycle:

```kotlin
// Inside onNv21Frame(nv21, width, height, timestamp):
if (token != session) return
if (!Nv21Frame.validSize(nv21.size, width, height)) {
    post(token) { fail("相机帧格式不支持，单击重试") }
    return
}
synchronized(frameLock) {
    if (token != session) return
    val frame = Nv21Frame(
        nv21.copyOf(), width, height,
        SystemClock.elapsedRealtime(), System.currentTimeMillis()
    )
    latest = frame
    preview.submit(frame)
}
```

`nv21.copyOf()` preserves app-owned data so SDK callback-buffer reuse cannot affect asynchronous preview or upload. The callback hands off only the latest frame; JPEG encoding and model requests run in the background. `onCameraOpened(...)` is an open notification. This example determines whether frames are flowing by receiving them; `onCameraClosed()` and `onError(...)` enter the error/retry flow.

### 3. Release resources when leaving the screen

- `onPause()` → `stopSession()`: Increment the session ID, stop sampling, cancel model requests, and call `CameraShareHelper.releaseNv21Export()` to stop shared frame output.
- `onDestroy()`: Call `GlassSdk.release()` for a ready SDK, cancel coroutines, and close the network client. If a binding callback arrives late, release the SDK in that callback too.
- Return to the foreground: Register the client again and restart frame capture. Only a limited set of hazard summaries remains while backgrounded; frame queues and similar-frame caches are cleared.

The app receives NV21 frames shared by the glasses system service. It does not call Android Camera directly or declare/request `CAMERA` permission. The Chinese project documentation records preview and recognition validation on an RG_glasses device. This path does not call ASR, TTS, or the phone SDK, and does not supply AK/SK credentials to the glasses SDK.

### 4. Business logic after frame capture

| Capability | Implementation | Usage reference |
| --- | --- | --- |
| OpenGL preview | [Nv21PreviewView.kt](app/src/main/java/com/rokid/industry/hazardrecognition/Nv21PreviewView.kt) | `submit(frame)` displays the latest frame; `submit(null)` clears it. |
| Frame deduplication and batch scheduling | [FrameQueue.kt](app/src/main/java/com/rokid/industry/hazardrecognition/FrameQueue.kt) | `offer → begin → complete`; at most three frames per batch and one active request. |
| Vision model request | [InspectionClient.kt](app/src/main/java/com/rokid/industry/hazardrecognition/InspectionClient.kt), [InspectionProtocol.kt](app/src/main/java/com/rokid/industry/hazardrecognition/InspectionProtocol.kt) | Convert NV21 to JPEG and call a compatible endpoint with OkHttp. The model key is used only for this HTTP request. |
| Hazard deduplication | [HazardLedger.kt](app/src/main/java/com/rokid/industry/hazardrecognition/HazardLedger.kt) | `known()` supplies summaries; `merge(...)` merges records by scene and object. |
| Regulation mapping and result display | [RegulationCatalog.kt](app/src/main/java/com/rokid/industry/hazardrecognition/RegulationCatalog.kt), [HazardTableView.kt](app/src/main/java/com/rokid/industry/hazardrecognition/HazardTableView.kt) | Map regulations locally and replace the table with `render(answer)`; the activity controls the 10-second display period. |

These are demo business implementations that developers can replace. They are not recognition, deduplication, or model services built into the glasses SDK.

## Model integration and developer configuration

The locally configured DeepSeek integration demonstrates the flow from frame capture to model calls and result display. It does not provide a production model service or account credentials. Developers must implement and maintain model integration and business functionality for their own requirements, using their own vision service or gateway.

The Rokid SDK obtains NV21 frames; the demo's HTTP business code calls the model. The model key is unrelated to Rokid SDK AK/SK credentials. The examples below explain and help validate integration.

```text
Glasses SDK shared NV21 → Local frame screening → JPEG / Base64
  → Wi-Fi / HTTPS → Developer vision endpoint (or gateway → internal vision model)
  → Structured recognition result → Local regulation mapping → Vertical table on glasses
```

### Example 1: Connect your own vision model through a compatible endpoint

Copy the [generic configuration template](inspection.properties.example) to `inspection.properties` in this project directory. If the file already exists, edit it instead of overwriting existing configuration.

```sh
cp inspection.properties.example inspection.properties
```

Replace the endpoint and model name with actual values and enter your key in the local file:

```properties
# Complete HTTPS request URL, not just a service domain
endpoint=https://your-gateway.example.com/v1/chat/completions
# Vision model actually deployed by the service
model=your-vision-model
# Your API Key or gateway token; intentionally blank in this example
apiKey=
# Local sampling target, 1–3 FPS; not completed cloud inferences per second
sampleFps=3
```

| Field | What to confirm |
| --- | --- |
| `endpoint` | Full HTTPS Chat Completions URL reachable over the glasses Wi-Fi network, including the request path. This example does not append `/v1` or `/chat/completions` or follow redirects. |
| `model` | An available model that understands images. A text-only model cannot perform this task. |
| `apiKey` | Model key or gateway token sent as `Authorization: Bearer …`. Validation requires a nonempty value, including for self-hosted services. Adapt other authentication methods in the gateway or modify `ModelConfig` and `InspectionClient`. |
| `sampleFps` | Local frame screening frequency, default 3. Each batch contains 1–3 images, with no concurrent uploads while a request is active. The old `intervalSeconds` setting is no longer used. |

Configuration is written to `BuildConfig` during the build. Rebuild and reinstall the APK after changes; editing the file or restarting the installed app alone has no effect. Without model configuration, preview works but frames are not uploaded.

### Example 2: Use the DeepSeek example

Copy the [DeepSeek configuration template](inspection.deepseek.properties.example) to `inspection.properties` and enter your own key locally. If configuration already exists, edit the relevant fields directly.

This example uses `https://api.deepseek.com/chat/completions` and `deepseek-v4-flash-vision-exp`. Consult the [official vision documentation](https://api-docs.deepseek.com/guides/vision/) for endpoint capabilities and account availability. The code adds `thinking.type=disabled` and `response_format.type=json_object` only when the endpoint host is `api.deepseek.com`. A developer gateway forwarding to DeepSeek can add these parameters itself. Other compatible endpoints do not receive these provider-specific fields.

### Model usage

Developers may configure any general-purpose model that supports image input, or integrate their own model service. DeepSeek is only an example; developers choose and implement the actual model, account, and integration method.

### Build and validate integration

After filling in configuration, build and install from this directory:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.rokid.industry.hazardrecognition/.InspectionActivity
```

First validate the three result states with a clear scene without hazards, a known hazard, and a blurry scene. Then check image numbering in batches, duplicate indicators, and manual rechecks. For 401/403, check tokens and permissions; for 404, check the complete endpoint path and model name. For incomplete or invalid model results, check the response against the parsing requirements of `InspectionProtocol.parse()`. Do not interpret an API failure as “no hazard.”

## Code and verification

### Read source by example

Key source locations contain Chinese “用例” (example) comments. Suggested reading order:

| Example | Entry point | Usage and expectations |
| --- | --- | --- |
| 1. Start the glasses camera | `InspectionActivity.startSession/connectSdk/registerCameraClient` | Bind the glasses service, register the client, and receive shared NV21 frames after `onReady`. No camera permission is declared/requested; this example does not pass Rokid AK/SK or call ASR/TTS. |
| 2. Receive and display NV21 | `startCamera`, `Nv21Frame`, `Nv21PreviewView.submit` | Copy the SDK callback buffer and keep the latest frame. Preview uses OpenGL; model encoding runs in the background. |
| 3. Screen frames for analysis | `FrameQueue.offer/begin/complete` | Screen at about 3 FPS on the main thread, at most three frames per batch. Always call `complete` when the request ends; failures do not enter the recognized-frame cache. |
| 4. Call a vision model | `InspectionClient.inspect`, `InspectionProtocol.request/parse` | Convert NV21 to JPEG, construct an image/text request, and strictly validate response status and image indices. Change services through local configuration. |
| 5. Identify duplicate hazards | `HazardLedger.known/merge` | Supply summaries before the request; merge returned records using scene and object information. Deduplication records exist only in memory. |
| 6. Display the current result | `HazardTableView.render`, `InspectionActivity.expireResult` | `render(answer)` replaces the table. After 10 seconds, `render(null)` restores the waiting state. Clearing the display does not clear the deduplication cache. |
| 7. Release resources | `stopSession/onDestroy`, `InspectionClient.close` | Stop the camera and cancel requests when leaving the foreground; release SDK, coroutine, and network resources on destruction. |

For example, `FrameQueueTest.duplicateIsCollectedOnlyAfterSuccessfulRecognition` is a runnable deduplication test. After submitting a frame and starting a request, a duplicate returns `IN_PROGRESS`. Only after successful completion marks image index `0` as containing a hazard does a similar frame return `KNOWN_HAZARD`. The test uses synthetic NV21 data and does not require a real device or model key.

```sh
# Run this example test from the hazard-recognition directory:
./gradlew :app:testDebugUnitTest --tests '*FrameQueueTest.duplicateIsCollectedOnlyAfterSuccessfulRecognition'
```
