# SDK 2.2.2 方法与回调接入参考

本文补充 [Demo 接入说明](SDK_2_2_2_APIS.md) 中的方法签名、数据结构、回调和接入边界。功能仍放在原消息发送、相机共享、SDK 拍照录像等页面，本文不是新增应用入口。

审查基准固定为 SDK 提交 **`c2b9677b4e2096e0b121946cc057f5b411d0fb63`**。2026-10-12 实时读取远端 `develop/industry` 得到 `b5711b925bd820f9966651c140cc67117370cee6`；它是在该基准之后的 OTA 修复，本次涉及的媒体、TTS、文件进度及 ExtConfig 接口没有变化。不能再把 c2b9677 称为当前远端 HEAD。

源码在独立临时检出中核对，原 SDK 工作区仍为 `11a8234`，未切换或修改。Demo 当前解析的发布包为 `2.2.2-E-20261012.062012-11`；本参考中的服务实现细节按指定提交说明，不把源码修复自动等同于已发布 AAR 或眼镜上的固件。9 月升级文档的四参数 Options 拍照签名和 Phone 概括不能覆盖本基准。

## 导航与示例范围

- [Surface 契约：查询、解码和按约定启动](#surface-contract)
- [NV21：Options byte[] 与 Config V1 借用 ByteBuffer](#nv21-exports)
- [I420：V1/V2 Helper、帧平面、stride、时间戳与释放](#i420-frames)
- [倍率会话、拍照录像封装、回调及水印模型](#camera-details)
- [TTS Manager、完整状态回调及错误](#tts-details)
- [手机文件字节进度与兼容](#phone-progress)
- [手机 ExtConfig 三态配置](#phone-extconfig)
- [受限 RTC 租约](#rtc-scope)

| 内容 | 当前 Demo 与本文的关系 |
| --- | --- |
| Surface 契约、动态倍率、I420 V1/V2、MediaOptions、TTS 会话监听、手机字节进度 | 已在原页面接入；本文补充可复用方法和回调说明。 |
| NV21 Options byte[]、Config V1 ByteBuffer | 在原 NV21 配置区增加数据接口选择，与默认 Options ByteBuffer 共用预览/统计；本节提供同步算法示例，新增两条路径尚未实机验收。 |
| `setZoomAndAwait`、`isI420Active` | 本文给出精确语义/调用示例；Demo 现有控制与帧统计不依赖这两个便利查询/等待方法。 |
| `cancelAll`、`cancelCurrent` | 本文提供应用级控制示例；Demo 页面继续只取消本页拥有的 ID，避免误停其他页面。 |
| `ExtConfig` | 本文补充接入示例；当前 Demo 没有 AppConfig 消费入口。 |
| I420 实时绘制 | 本文补齐格式和异步消费规则；当前 I420 Demo 仍是帧统计，已有 Surface/NV21 提供实时预览。 |

下面标为“签名”的代码用于说明类型，不是完整源文件；标为“骨架”的片段保留业务依赖并注明需实现的函数。完整函数示例仍要求有效服务连接、调用权限与配套设备固件，发出请求不能视为操作成功。

<a id="surface-contract"></a>

## Surface 启动契约

以下按 `c2b9677` 的 `IMediaServer`、`SurfaceShareContractProbe` 和 `CameraSurfaceContract` 核对。原始 AIDL 签名为：

```aidl
long queryCameraSurfaceContract(in CameraShareConfigV2 config,
    boolean enableMix, boolean hasWatermark) = 36;
void startCameraShareWithContract(in Surface surface,
    ICameraSurfaceCallback callback, boolean enableMix,
    in CameraShareConfigV2 config, in TextWatermarkConfig watermarkConfig,
    ICameraSessionCallback sessionCallback, long contract) = 38;
```

`queryCameraSurfaceContract` 不启动共享，返回一个编码后的 `Long`；不是宽高拼接值，也不是相机 session ID。传入的 `hasWatermark` 应来自本次 `watermarkConfig?.hasWatermark() == true`，而非仅检查配置对象是否非空。查询与启动必须使用同一组配置、混合开关和实际水印状态。

| 解码字段 | 含义 |
| --- | --- |
| `version` | 当前支持 `VERSION_1=1`；未知版本不能自行猜测。 |
| `path` | `PATH_DIRECT_CAMERA=1` 为 Camera2 直接输出；`PATH_COMPOSED=0` 为合成输出。当前实现仅无屏幕混合且无有效水印时选择直通。 |
| `bufferWidth` / `bufferHeight` | 必须在创建、提交 Surface 前设置的精确缓冲尺寸；不保证等于请求输出尺寸。 |
| `callbackUsesCameraSourceSize` | true 表示公共尺寸回调使用相机源方向，不能直接用它覆盖契约 buffer 尺寸。它不是“现在交换宽高”的指令。 |

兼容旧服务时使用公开探针 `SurfaceShareContractProbe.read(service, config, enableMix, hasWatermark): Long`。仅 Binder 不认识该事务时探针返回 `0L`；权限、断连或其他 IPC 异常仍抛出。`CameraSurfaceContract.decode(encoded): Value?` 对 0、未知版本或非法编码返回 null；**非零但解码失败必须中止本次启动**，不能转成“旧固件”继续执行。

以下函数演示支持契约的启动路径，调用方保存返回的 Surface、传入的 callback 和 session。应在与纹理生命周期协调的工作/GL 线程执行，避免销毁纹理时仍启动生产者：

```kotlin
import android.graphics.SurfaceTexture
import android.view.Surface
import com.rokid.security.glass3.open.sdk.camera.CameraZoomSession
import com.rokid.security.glass3.sdk.base.data.media.CameraShareConfigV2
import com.rokid.security.glass3.sdk.base.data.media.CameraSurfaceContract
import com.rokid.security.glass3.sdk.base.data.media.TextWatermarkConfig
import com.rokid.security.system.server.media.IMediaServer
import com.rokid.security.system.server.media.SurfaceShareContractProbe
import com.rokid.security.system.server.media.callback.ICameraSurfaceCallback

fun startNegotiatedSurface(
    media: IMediaServer,
    texture: SurfaceTexture,
    callback: ICameraSurfaceCallback,
    session: CameraZoomSession,
    config: CameraShareConfigV2,
    enableMix: Boolean,
    watermark: TextWatermarkConfig?
): Surface {
    CameraZoomSession.requireCapability(media, CameraZoomSession.CAPABILITY_SHARE)
    val encoded = SurfaceShareContractProbe.read(
        media, config, enableMix, watermark?.hasWatermark() == true
    )
    check(encoded != 0L) { "This example requires Surface contract support" }
    val contract = requireNotNull(CameraSurfaceContract.decode(encoded))
    texture.setDefaultBufferSize(contract.bufferWidth, contract.bufferHeight)
    val surface = Surface(texture)
    try {
        media.startCameraShareWithContract(
            surface, callback, enableMix, config, watermark, session.callback(), encoded
        )
        return surface
    } catch (error: Exception) {
        runCatching { session.close() }
        runCatching { media.stopCameraShare(callback) }
        surface.release()
        throw error
    }
}
```

示例对“不支持契约”明确报错；需要兼容旧服务的完整分支见原 `SurfaceShareFragment` 和 `CameraSurfaceBufferPlan`。不能仅因没拿到契约就直接套用新服务直通缓冲规则。指定提交中 `startCameraShareWithOptions` 保留兼容合成路径，`startCameraShareWithContract` 才允许按协商选择直通；不能认为二者只是多一个无关参数。

服务在启动时重新核对契约；若配置或共享状态变化使约定失效，向媒体 callback 的 `onError` 和 session callback 的 `onSessionError` 报 `-8 / surface_contract_stale`。这是异步失败，方法返回不代表已经启动。业务可以清理本次会话后重新查询并重新创建 Surface，不能重用旧契约无限重试。有效契约启动后，不再根据尺寸回调交换 buffer 宽高；View 的等比显示、纹理变换和 buffer 配置是不同步骤。

退出时先停止对应生产者（本会话 `close()` 或同一个 `callback` 的 `stopCameraShare`），再释放自己的 Surface/SurfaceTexture。异步错误和迟到 `onSessionCreated` 也需要关闭对应句柄；不能只释放本地 Surface。动态水印更新仍需启动时同一个 callback Binder 和已有 item ID。

源码：[IMediaServer](https://gitlab.rokid-inc.com/lingban/android/glass3/glass3_sdk_app/-/blob/c2b9677b4e2096e0b121946cc057f5b411d0fb63/glass3/open-sdk/src/main/aidl/com/rokid/security/system/server/media/IMediaServer.aidl#L250)、[服务端契约核对](https://gitlab.rokid-inc.com/lingban/android/glass3/glass3_sdk_app/-/blob/c2b9677b4e2096e0b121946cc057f5b411d0fb63/glass3/system-server/src/main/java/com/rokid/security/system/server/impl/media/MediaServerImpl.kt#L698)。

<a id="nv21-exports"></a>

## NV21 的三种数据接口

以下签名按 `c2b9677` 的 `CameraShareHelper` 核对，回调类型均为该类的内部公开接口。`enableMix=false` 为纯相机，`true` 请求叠加屏幕；原 NV21 Demo 使用 `false`。

```kotlin
fun initNv21BufferExportWithOptions(
    enableMix: Boolean, config: CameraShareConfigV2, callback: Nv21BufferCallback
)
fun initNv21ExportWithOptions(
    enableMix: Boolean, config: CameraShareConfigV2, callback: Nv21Callback
)
fun initNv21BufferExportWithConfig(
    enableMix: Boolean, config: CameraShareConfig, callback: Nv21BufferCallback
)
fun releaseNv21Export()

interface Nv21Callback {
    fun onCameraOpened(width: Int, height: Int)
    fun onNv21Frame(nv21: ByteArray, width: Int, height: Int, timestamp: Long)
    fun onCameraClosed() {}
    fun onError(code: Int, msg: String) {}
    fun onNv21ExportResolutionChanged(width: Int, height: Int, appliedPreviewFps: Int) {}
    fun onNv21ExportRuntimeParamsChanged(appliedPreviewFps: Int, videoStabilizationEnabled: Boolean) {}
    fun onZoomLevelChanged(zoomLevel: Int) {}
}
// Nv21BufferCallback 的生命周期/参数通知相同，只有帧参数变为：
// fun onNv21Frame(buffer: ByteBuffer, width: Int, height: Int, timestamp: Long)
```

| Demo 选择 | 配置与回调 | 数据所有权、倍率证据 |
| --- | --- | --- |
| `OPTIONS_BUFFER`（默认） | `CameraShareConfigV2` + `ConfiguredNv21BufferCallback` | 同步借用只读 ByteBuffer，回调还提供 `sensorTimestampNs` 和 `configurationGeneration`；校验尺寸及 ready/代次后才能认定当前视场帧。 |
| `OPTIONS_ARRAY` | `CameraShareConfigV2` + `Nv21Callback` | Kotlin `ByteArray` 即 Java `byte[]`，适配既有数组算法。V2 会话可更新小数倍率，但数组回调没有配置代次，不能证明该帧属于最新倍率。 |
| `CONFIG_BUFFER` | `CameraShareConfig` + `Nv21BufferCallback` | 借用只读 ByteBuffer，配置使用整数 `zoomLevel`；不建立 V2 `nv21ZoomSession`，帧回调没有配置代次。 |

`width/height` 是该帧实际尺寸，不能强用请求值；`timestamp` 为兼容毫秒时间戳，不是 `sensorTimestampNs`。紧凑 NV21 包含 Y 平面及交错 VU，偶数宽高下需要 `width × height × 3 / 2` 字节。回调不保证主线程，UI 要切到主线程，算法同步执行必须及时返回。

当前 SDK 数组路径每帧从共享内存复制到**两个复用 ByteArray**；仅首次使用、尺寸变化等情况分配大数组，不能描述成“SDK 每帧都分配数组”。数组会在后续回调被复用覆盖，因此同步处理后丢弃引用。ByteBuffer 路径省去这次整帧数组复制，但 buffer、duplicate 和 slice 都只在当前回调返回前有效；它们不是应用拥有的快照，也不是 I420 的可归还租约。两种 NV21 回调都不需要逐帧 `close()`，不能对共享内存自行 unmap。

下面是可接入的**同步算法**示例。`consume` 是业务算法，由调用方提供，禁止保存传入数组/缓冲或在返回后异步读取。若算法耗时或必须异步执行，先复制到应用自己管理的有界复用缓冲，再快速入队；队列满时丢帧，不逐帧无限创建 ByteArray 或协程。

```kotlin
import android.util.Log
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper
import com.rokid.security.glass3.sdk.base.data.media.CameraShareConfig
import com.rokid.security.glass3.sdk.base.data.media.CameraShareConfigV2
import com.rokid.security.glass3.sdk.base.data.media.CameraZoomConfig
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

private fun nv21ByteCount(width: Int, height: Int): Int? {
    if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) return null
    val pixels = width.toLong() * height
    val bytes = pixels + pixels / 2L
    return bytes.takeIf { it <= Int.MAX_VALUE }?.toInt()
}

// 每次启动拥有独立 Helper；取消外层 Job 时，在工作线程停止并清理。
private suspend fun runNv21UntilCancelled(start: (CameraShareHelper) -> Unit): Nothing =
    withContext(Dispatchers.IO) {
        val helper = CameraShareHelper()
        try {
            checkNotNull(GlassSdk.getGlassMediaService()) { "Media service is not connected" }
            start(helper)
            awaitCancellation()
        } finally {
            helper.releaseNv21Export()
        }
    }

// 已有 byte[] 算法：支持 V2 小数倍率，但数组回调不含帧配置代次。
suspend fun runOptionsArrayAlgorithm(
    consume: (ByteArray, Int, Int, Long) -> Unit
): Nothing = runNv21UntilCancelled { helper ->
    val config = CameraShareConfigV2(
        previewWidth = 1280, previewHeight = 720, previewTargetFps = 24,
        useAsyncCallback = false, zoom = CameraZoomConfig.explicit(1.575f)
    )
    helper.initNv21ExportWithOptions(false, config,
        object : CameraShareHelper.Nv21Callback {
            override fun onCameraOpened(width: Int, height: Int) {
                Log.i("Nv21Example", "array opened=${width}x$height")
            }
            override fun onNv21Frame(nv21: ByteArray, width: Int, height: Int, timestamp: Long) {
                val expected = nv21ByteCount(width, height) ?: return
                if (nv21.size < expected) return
                // 同步调用；数组归 SDK 复用，此函数不能留存引用。
                runCatching { consume(nv21, width, height, timestamp) }
                    .onFailure { Log.e("Nv21Example", "array algorithm failed", it) }
            }
            override fun onError(code: Int, msg: String) {
                Log.e("Nv21Example", "array error=$code $msg")
            }
        }
    )
}

// 保留整数倍率的借用入口：更改 zoomLevel 后应停止旧输出，再启动新配置。
suspend fun runConfigBufferAlgorithm(
    consume: (ByteBuffer, Int, Int, Long) -> Unit
): Nothing = runNv21UntilCancelled { helper ->
    val config = CameraShareConfig(
        previewWidth = 1280, previewHeight = 720, previewTargetFps = 24,
        zoomLevel = 2, useAsyncCallback = false
    )
    helper.initNv21BufferExportWithConfig(false, config,
        object : CameraShareHelper.Nv21BufferCallback {
            override fun onCameraOpened(width: Int, height: Int) {
                Log.i("Nv21Example", "config buffer opened=${width}x$height")
            }
            override fun onNv21Frame(buffer: ByteBuffer, width: Int, height: Int, timestamp: Long) {
                val expected = nv21ByteCount(width, height) ?: return
                if (buffer.remaining() < expected) return
                // 同步只读消费；这里没有 ByteArray 分配或整帧复制。
                runCatching { consume(buffer, width, height, timestamp) }
                    .onFailure { Log.e("Nv21Example", "buffer algorithm failed", it) }
            }
            override fun onError(code: Int, msg: String) {
                Log.e("Nv21Example", "config buffer error=$code $msg")
            }
        }
    )
}
```

在拥有页面生命周期的协程中启动其中一个函数，保存返回的 Job；停止/退出时取消，切换配置时先 `cancelAndJoin()` 等旧函数的 `finally` 清理完成再启动。不要同时启动两个示例来切换模式。上述函数持续等待取消，即使错误回调到达也不会自行当作成功或结束；应用应显示错误并取消任务，必要时在启动后增加首帧超时。`releaseNv21Export()` 同时停止本 Helper 的导出、关闭其 V2 会话、清理共享映射和回调；不要在帧回调中同步释放，而应交给工作线程的生命周期控制。

同一个 Helper 已有 NV21 输出时，再次调用上述任一 `init...` 会被忽略，不能用它更新配置。Demo 的“应用配置”统一停止旧输出并隔离旧回调后重启。Config V1 使用整数 `zoomLevel`，禁用 V2 的 `setZoom` / `followShared`；可以查询全局能力和相机状态，但全局状态不等于本 V1 输出已应用该倍率。Options 两种方式都由 Helper 建立 `nv21ZoomSession`，支持动态倍率；其中只有默认配置帧回调提供逐帧代次证据。V2 精确配置帧始终采用同步借用，不能用 `useAsyncCallback=true` 延长数据生命周期。

Options 不支持时通过 `onError(-3, ...)` 明确失败，不会把小数倍率取整后退回 V1。服务未连接和同步启动异常通常分别为 `-1` / `-2`；但指定提交的 **V1 `startNv21Export` 在这两条失败路径只通知数组回调，未通知 Buffer 回调**。因此 V1 ByteBuffer 接入尤其不能把“没有 onError”当作成功，需先核对服务连接，并用 `onCameraOpened`、持续帧和首帧超时判定。此 Demo 工作没有修改 SDK。

验证新增两条路径时，分别从原 NV21 配置区选择数据接口并应用，检查日志中的启动方法、连续帧实际宽高和预览。切换接口/尺寸及退出页面后，旧输出应停止、旧回调不再更新新页面；V1 只允许整数倍率。数组/V1 的帧日志不会包含可用于证明最新倍率的配置代次，不能沿用默认 V2 Buffer 的“已确认当前配置帧”结论。本次新增两种入口尚未进行实机验收。

源码：[CameraShareHelper 的 Options 入口](https://gitlab.rokid-inc.com/lingban/android/glass3/glass3_sdk_app/-/blob/c2b9677b4e2096e0b121946cc057f5b411d0fb63/glass3/open-sdk/src/main/java/com/rokid/security/glass3/open/sdk/camera/CameraShareHelper.kt#L315)、[Config Buffer 入口与释放](https://gitlab.rokid-inc.com/lingban/android/glass3/glass3_sdk_app/-/blob/c2b9677b4e2096e0b121946cc057f5b411d0fb63/glass3/open-sdk/src/main/java/com/rokid/security/glass3/open/sdk/camera/CameraShareHelper.kt#L460)、[双数组复用与帧派发](https://gitlab.rokid-inc.com/lingban/android/glass3/glass3_sdk_app/-/blob/c2b9677b4e2096e0b121946cc057f5b411d0fb63/glass3/open-sdk/src/main/java/com/rokid/security/glass3/open/sdk/camera/CameraShareHelper.kt#L1095)。

<a id="i420-frames"></a>

## I420 Helper、帧结构与绘制时的所有权

公开类型位于 `com.rokid.security.glass3.open.sdk.camera.CameraShareHelper`：

```kotlin
fun initI420BufferExportWithConfig(config: CameraShareConfig, callback: I420BufferCallback)
fun initI420BufferExportWithOptions(config: CameraShareConfigV2, callback: I420BufferCallback)
fun isI420Active(): Boolean
fun releaseI420Export()

interface I420BufferCallback {
    fun onCameraOpened(width: Int, height: Int, appliedPreviewFps: Int)
    fun onI420Frame(frame: I420FrameLease)
    fun onCameraClosed() {}                        // 可选
    fun onError(code: Int, msg: String) {}           // 可选
}
```

V1 Config 使用整数 `zoomLevel`；V2 Options 使用 `CameraZoomConfig` 并通过 `helper.i420ZoomSession` 控制倍率，需 `CAPABILITY_SHARE`。二者都是纯相机 I420，没有屏幕合成参数。已启动时重复调用会被忽略；切换 V1/V2 或尺寸先 `releaseI420Export()`，并隔离旧回调。倍率修改可使用现有 V2 会话，无需停止重启。

`isI420Active()` 直接读取 Helper 的本地 started 标记。该标记在提交启动时设为 true，启动失败/断连期间也可能仍为 true；它表示 Helper 已请求维持导出，**不能证明相机已打开、已出帧、倍率 ready 或服务端仍在运行**。应同时检查 `onCameraOpened`、持续帧统计、错误/关闭回调与 V2 状态。

回调由 SDK/Binder 派发，不保证主线程；及时移交或消费，UI 切主线程。常见 Helper 启动错误为 `-1` 服务未连接、`-2` 启动异常，V2 能力不支持为 `-3`；共享内存映射失败也可能报 `-3`，不能跨回调阶段只凭数值判断“不支持”。保留其他服务错误分支。

| `I420FrameLease` 字段/方法 | 精确类型与含义 |
| --- | --- |
| `dataY`, `dataU`, `dataV` | 三个只读 `ByteBuffer`，依次为 Y、U、V 平面；I420 不是 NV21 的交错 VU。 |
| `width`, `height` | `Int`，此帧实际图像宽高；按帧值消费，不强用请求尺寸。 |
| `strideY`, `strideU`, `strideV` | `Int`，每行字节跨度。当前实现紧凑布局为 width、width/2、width/2；应用仍应按字段读取。 |
| `timestampNs` | `Long`，该帧的源时间戳，单位纳秒；不是 Unix 毫秒，不直接作为日期。 |
| `generation` | `Int`，共享缓冲池代次；不是 `CameraZoomState.frameGeneration` 的配置代次。 |
| `slotIndex`, `frameId` | `Int` 槽位与 `Long` 帧标识，由 Helper 随租约保存；不能自行改为新会话的标识。 |
| `close()` / `release()` | 等价、返回 Unit；同一租约通过原子保护只归还一次。释放后所有平面及其 duplicate/slice 都不能继续读取。 |

Y 为 width×height；U/V 各为半宽×半高，当前共享布局要求偶数尺寸，总数据量为 width×height×3/2。下例复制到应用所有的紧凑平面并释放租约，适合把复制结果交给异步绘制线程。它为易读而逐帧分配数组；实际预览应使用有界缓冲池，避免高分辨率连续分配。

```kotlin
import java.nio.ByteBuffer
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper

data class CopiedI420(
    val width: Int, val height: Int, val timestampNs: Long,
    val y: ByteArray, val u: ByteArray, val v: ByteArray
)

private fun copyPlane(source: ByteBuffer, stride: Int, columns: Int, rows: Int): ByteArray {
    require(columns > 0 && rows > 0 && stride >= columns)
    val required = (rows - 1L) * stride + columns
    require(required <= source.remaining().toLong())
    require(columns.toLong() * rows <= Int.MAX_VALUE)
    val input = source.duplicate()
    val start = input.position()
    return ByteArray(columns * rows).also { output ->
        repeat(rows) { row ->
            input.position(start + row * stride)
            input.get(output, row * columns, columns)
        }
    }
}

// 此函数取得该租约的最终释放责任；成功或异常都归还。
fun copyAndRelease(frame: CameraShareHelper.I420FrameLease): CopiedI420 {
    try {
        val w = frame.width
        val h = frame.height
        require(w > 0 && h > 0 && w % 2 == 0 && h % 2 == 0)
        return CopiedI420(w, h, frame.timestampNs,
            copyPlane(frame.dataY, frame.strideY, w, h),
            copyPlane(frame.dataU, frame.strideU, w / 2, h / 2),
            copyPlane(frame.dataV, frame.strideV, w / 2, h / 2))
    } finally {
        frame.close()
    }
}
```

在 `onI420Frame` 中调用 `copyAndRelease(frame)`，然后将复制结果送进有界渲染队列；队列满时丢弃复制结果即可。绘制时向三个纹理上传 Y/U/V，按实际宽高和色度半尺寸采样；视图保持原图比例。颜色转换矩阵、色彩范围及纹理方向需要与实际管线匹配，租约本身没有旋转/色彩空间字段，不能仅凭宽高保证与 Surface 观感一致。

V1 启动和本地标记查询示例，使用上面的复制函数。`consumeOwnedFrame` 接收的已是应用拥有的数组，可快速放进自己的有界队列；它不是在 Binder 线程执行耗时绘制的位置。

```kotlin
import android.util.Log
import com.rokid.security.glass3.open.sdk.camera.CameraShareHelper
import com.rokid.security.glass3.sdk.base.data.media.CameraShareConfig

fun startI420V1(
    helper: CameraShareHelper,
    consumeOwnedFrame: (CopiedI420) -> Unit
) {
    val callback = object : CameraShareHelper.I420BufferCallback {
        override fun onCameraOpened(width: Int, height: Int, appliedPreviewFps: Int) {
            Log.i("I420Demo", "opened=${width}x$height fps=$appliedPreviewFps")
        }
        override fun onI420Frame(frame: CameraShareHelper.I420FrameLease) {
            consumeOwnedFrame(copyAndRelease(frame))
        }
        override fun onError(code: Int, msg: String) {
            Log.w("I420Demo", "error=$code")
        }
    }
    helper.initI420BufferExportWithConfig(
        CameraShareConfig(previewWidth = 640, previewHeight = 480, zoomLevel = 1),
        callback
    )
    Log.i("I420Demo", "locallyRequested=${helper.isI420Active()}")
}
// 停止/退出时在工作线程调用同一个 helper.releaseI420Export()。
```

另一种方式是把原始 lease 直接交给 GL/WebRTC 最终消费者：必须等消费者真正完成后再 `close()`；入队失败、被替换的旧帧、页面退出和异常路径也要释放。`duplicate()` 只复制 ByteBuffer 视图，不复制像素，不能用它延长已归还槽位的有效期。禁止在回调里 `queueEvent { upload(frame) }` 后立即在回调的 finally 释放原 lease；这是常见的异步使用已释放帧错误。

服务端只有四个共享槽，积压四个未释放租约会阻止正常供帧；停止导出不替调用方释放已借出的帧。Helper 保存原 outputId/generation/slotIndex/frameId 并负责归还，正常使用 Helper 不要额外手工调用 `releaseCameraI420Frame`。直接实现 AIDL 时才需保存原始标识，并按原输出归还旧帧，不能使用重连后新 ID。

当前 Demo 的 I420 页已展示 V1/V2 启动、真实平面读取和 `finally { frame.close() }`，输出帧统计；没有 I420 实时绘制器，也没有把 `isI420Active` 当成功判定。实时预览已有 Surface/NV21 入口。本节提供绘制所需的数据与所有权说明，不将新增文档当作已完成绘制功能。

源码：[CameraShareHelper](https://gitlab.rokid-inc.com/lingban/android/glass3/glass3_sdk_app/-/blob/c2b9677b4e2096e0b121946cc057f5b411d0fb63/glass3/open-sdk/src/main/java/com/rokid/security/glass3/open/sdk/camera/CameraShareHelper.kt#L146)、[I420 AIDL](https://gitlab.rokid-inc.com/lingban/android/glass3/glass3_sdk_app/-/blob/c2b9677b4e2096e0b121946cc057f5b411d0fb63/glass3/open-sdk/src/main/aidl/com/rokid/security/system/server/media/callback/ICameraI420ShareCallback.aidl)。

<a id="camera-details"></a>

## 相机会话、Options 拍照录像及模型详解

本节按 SDK 提交 `c2b9677b4e2096e0b121946cc057f5b411d0fb63` 核对。源码位置均相对 SDK 仓库根目录；末尾列出可定位的路径和行号。`CameraZoomSession`、`CameraMediaOptions`、本节四个 AIDL 和 zoom/watermark 模型在随后远端 `b5711b9` 中没有变化。

### CameraZoomSession：单个输出的控制会话

类位于 `com.rokid.security.glass3.open.sdk.camera`。一个对象对应一个 Options 输出（Surface、NV21、I420 或录像），不应供多个同时存活的输出共用。它保存控制句柄和状态，不保存图像；多个输出共享同一物理相机的倍率，并非每个输出独立裁切。系统高优先级业务可以覆盖普通业务的倍率请求。

五个控制方法的准确 Kotlin 签名如下：

```kotlin
fun setZoom(ratio: Float): Int
fun followShared(): Int
fun refreshState(): CameraZoomState?
fun observeSurfaceFrame(sensorTimestampNs: Long): Unit
suspend fun setZoomAndAwait(ratio: Float, timeoutMs: Long = 2_000L): Float?
```

| 方法 | 参数、返回值及完成条件 |
| --- | --- |
| `setZoom` | `ratio` 是无量纲倍率，如 `1.575f`，必须有限且 `>= 1f`；超硬件上限由服务限制。返回 `0` 为接受意图，`-1` 为参数非法，`-2` 为句柄尚未创建或已关闭。接受后会同步刷新状态。返回 0 不表示已出新帧，也不保证实际倍率等于请求值。 |
| `followShared` | 无参数。撤销本输出的显式倍率，跟随当前共享仲裁；返回 `0` 接受、`-2` 无有效句柄。不会强制全局回到 1×。接受后同步刷新状态。 |
| `refreshState` | 无参数，执行同步 IPC，返回本地最新 `CameraZoomState?`；无句柄时为 `null`。只接受不落后于已缓存 `desiredRevision` 的快照。它不是首帧等待方法，不要逐帧调用。 |
| `observeSurfaceFrame` | `sensorTimestampNs` 为 Surface 已消费帧的原始相机时间戳，单位**纳秒**。自管 `SurfaceTexture` 时在 `updateTexImage()` 之后取 `timestamp`；不能传系统当前时间、毫秒值或 WebRTC 重映射后的时间戳。无状态、已经 ready、时间戳非正数或无句柄时忽略；待确认期间正常递增时间戳按约 50 ms 节流。返回 `Unit`，不提供“确认成功”返回值。`CameraShareHelper` 的 Surface 路径已自动上报，不要再重复上报。 |
| `setZoomAndAwait` | 先提交 `ratio`，再挂起等待 `ready` 且修订号不早于本次提交后读取到的 `desiredRevision`。`timeoutMs` 单位毫秒，范围 `1..10_000`，默认 `2_000`。返回已确认的有限 `appliedZoom`，它可能因仲裁而不同于 `ratio`；提交拒绝、无状态或等待超时返回 `null`。超时不撤销已接受意图，也不停止输出。超范围 timeout 抛参数异常；Binder 异常和调用者协程取消仍可能抛出。 |

上述方法中的 Binder 调用应放在后台线程；`suspend` 只让等待过程挂起，`setZoomAndAwait` 开头的同步 `setZoom` 不会自动切到 IO 线程。不要把“等待成功”直接当作此前任意缓存帧的有效性证明；NV21 仍需比较帧的 `configurationGeneration` 和状态的 `frameGeneration`。

还需要掌握生命周期方法和属性：

```kotlin
var listener: CameraZoomSession.Listener? // 可写，唯一监听器
val state: CameraZoomState?              // 对调用方只读的本地缓存，不产生 IPC
fun callback(onError: (Int, String) -> Unit = { _, _ -> }): ICameraSessionCallback
override fun close(): Unit
// companion object，亦可从 Java 静态调用：
fun requireCapability(service: IMediaServer, capability: Int): Unit
```

- `listener` 的 `onStateChanged(state)` 和可选 `onError(code, reason)` 在 Binder 回调线程执行，应快速返回，UI 切主线程。给 `listener` 赋值本身不会立即重放状态，需要时另读 `state`。监听的是状态边沿，不是每帧事件。
- `state` 初始、重连或 `close()` 后为 `null`；这不代表全局相机关闭。
- 原始 AIDL 启动时每次调用一次 `callback()`，把返回对象交给 `sessionCallback`。该调用会关闭旧句柄、清空旧状态并隔离迟到回调；新句柄到达后重放本对象此前已接受的 `setZoom`/`followShared` 意图。Helper/`CameraMediaOptions` 已代为调用时，不要再额外调用一次。
- `callback(onError)` 的错误处理器随后还会触发 `listener.onError`，同时使用两处时应去重。`onSessionCreated` 只代表拿到句柄，早于实际媒体启动，不等于首帧或 ready。
- `close()` 幂等，停止**所属输出**并释放倍率意图；不是仅注销监听。Helper 吞掉关闭阶段异常，所以返回也不能保证远端已同步完成停止。不要为了退出本页而关闭别的页面持有的 session。
- 能力位为 `CAPABILITY_SHARE = 1`（Surface/NV21/I420）、`CAPABILITY_RECORD = 2`、`CAPABILITY_PHOTO = 4`，组合使用按位 `or`。`requireCapability` 经安全探测检查所有所需位；旧服务未知事务视为无新能力，缺位抛 `UnsupportedOperationException`，传输异常继续抛出，不自动启动旧接口。

已经启动的 Options session 更新倍率示例：

```kotlin
import android.util.Log
import com.rokid.security.glass3.open.sdk.camera.CameraZoomSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun requestZoom(session: CameraZoomSession) = withContext(Dispatchers.IO) {
    val actual = session.setZoomAndAwait(1.575f, timeoutMs = 2_000L)
    Log.i("CameraDemo", "confirmed=$actual; state=${session.state}")
    // actual == null 时由业务决定继续等待、followShared() 或 close()；不自动重启输出。
}
```

### CameraMediaOptions：两种一次启动接口

对象同样位于 `com.rokid.security.glass3.open.sdk.camera`，两个方法都有 `@JvmStatic`。它们先探测能力，再同步发送 Binder 请求，结果通过异步回调返回；返回 `Unit` 不代表业务完成。不支持时明确抛出异常，不把小数倍率取整，也不因 IPC 失败自动重试拍照或录像。

#### takePhoto

```kotlin
fun takePhoto(
    config: CameraShareConfigV2,
    reuseOpenedResolution: Boolean,
    path: String,
    quality: Int,
    callback: ICameraPhotoCallback,
): Unit
```

| 参数 | 含义与限制 |
| --- | --- |
| `config` | V2 输出尺寸/FPS/防抖及 zoom 意图。宽高填写期望最终图片输出尺寸，单位像素；倍率仍受共享仲裁影响。请求值不等于实际 JPEG 尺寸。 |
| `reuseOpenedResolution` | 必填 Boolean。`true` 且相机已打开时复用当前实际分辨率；`false` 按 config 请求拍照分辨率。当前会话没有可用拍照帧源时，服务仍可能临时重配，结束后恢复持续业务。此参数是相对 9 月升级文档的签名变化，不能省略。 |
| `path` | 非空白、服务端可写的 JPEG 路径。SDK 不自动给第三方创建可读 Uri 或授予文件读取权限。 |
| `quality` | JPEG 压缩质量 `1..100`，不是 ISP 锐化强度；当前实现直接校验此范围。 |
| `callback` | 该请求专属的 `ICameraPhotoCallback`，不需全局 `addPhotoCallback` 注册，也不返回长期 session。 |

前提为媒体服务连接且具备 `CAPABILITY_PHOTO`。缺能力抛 `UnsupportedOperationException`，服务未连接抛 `IllegalStateException`；参数、权限或 Binder 错误也可能同步抛出，应与回调失败分别处理。一次拍照没有本接口提供的客户端取消句柄；页面退出只能丢弃迟到 UI 结果，不能把页面协程取消当作服务端拍照已取消。

**`c2b9677` 的实际等待策略：**有持续相机业务时，服务最多先等约 2 秒 ready；仍未确认时会继续使用当前相机倍率尝试拍照，以兼容没有上报帧证据的旧 Surface。随后等待照片结果的上限为 25 秒，底层另有 watchdog；这不是“2 秒保证拍完”，也不能给它套用录像的长期 session 就绪条件。

```kotlin
import android.util.Log
import com.rokid.security.glass3.open.sdk.camera.CameraMediaOptions
import com.rokid.security.glass3.sdk.base.data.media.CameraShareConfigV2
import com.rokid.security.glass3.sdk.base.data.media.CameraZoomConfig
import com.rokid.security.glass3.sdk.base.data.media.CameraZoomState
import com.rokid.security.system.server.media.ICameraPhotoCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun requestPhoto(serverWritableJpegPath: String) = withContext(Dispatchers.IO) {
    val config = CameraShareConfigV2(
        previewWidth = 1920, previewHeight = 1080,
        previewTargetFps = 24,
        zoom = CameraZoomConfig.explicit(1.575f),
    )
    CameraMediaOptions.takePhoto(config, false, serverWritableJpegPath, 90,
        object : ICameraPhotoCallback.Stub() {
            override fun onPhoto(path: String?, width: Int, height: Int, state: CameraZoomState?) {
                Log.i("CameraDemo", "JPEG=$path actual=${width}x$height state=$state")
            }
            override fun onError(code: Int, reason: String?) {
                Log.w("CameraDemo", "Photo error code=$code reason=$reason")
            }
        })
    // 函数走到这里仅表示请求调用返回；JPEG 完成看 onPhoto。
}
```

#### startRecord

```kotlin
fun startRecord(
    callback: VideoCallback,
    config: RecordConfig,
    zoom: CameraZoomConfig,
    watermark: TextWatermarkConfig?,
    session: CameraZoomSession,
): Unit
```

| 参数 | 含义与限制 |
| --- | --- |
| `callback` | 原 `VideoCallback`，接收 `onStart`、`onNewFile(startTime, endTime, path, isLast)`、`onFinish`、`onError`、`onErrorWithDetail`。控制启动错误由 Helper 映射到 `onError()`；session listener 仍可收到控制错误，界面注意去重。停止后仍需接收末文件回调，不能发出 stop 就丢弃全部后续回调。 |
| `config` | 保持原 `RecordConfig` Parcel 布局：`path: String`、`min: Int`、`enableAudio: Boolean` 为必填；`width=1280`、`height=720`（请求 MP4 输出像素尺寸）、`fps=20`（帧/秒）、`isArRMixEnabled=false` 有默认值。`min` 为自动分段分钟数；当前实现 `<= 0` 关闭按时间分段。AR 请求会与设备持久 AR 开关合并。 |
| `zoom` | `CameraZoomConfig` 倍率意图，`explicit(1.575f)` 表示显式申请；不保证最终倍率相同。 |
| `watermark` | 本次录像的文字水印，`null` 不添加本输出的文字水印；不会因此给 NV21/I420 自动加水印。 |
| `session` | 本次录像独占的控制对象；先设置 listener，再传给 Helper。使用状态确认倍率，退出或停止时关闭本对象。 |

前提为 `CAPABILITY_RECORD`。不支持、未连接和同步调用异常与拍照相同，均不自动回退。录像开始以 `onStart` 确认，文件以 `onNewFile` 为准；`session.close()` 会停止这次录像，而不只是撤销倍率请求。

**尺寸限制按此提交的实现解释：**录像并非只接受 Demo 三个按钮的分辨率。服务读取 Camera2 支持尺寸并结合 AVC 编码器对尺寸/FPS 的支持，先找精确匹配，否则选接近尺寸。录制路径固定旋转 90°，实际 MP4 宽高为所选相机源高宽，回退不会缩放回请求值；无法找到相机与编码器支持的组合时通过录像错误回调失败。因此成功开始后仍需核对实际输出文件的宽高，不能只看请求 config。

下面函数在调用者提供的后台线程执行；返回的是需由页面保存并最终关闭的 session，实际开始仍看 `VideoCallback.onStart`：

```kotlin
import android.util.Log
import com.rokid.security.glass3.open.sdk.camera.CameraMediaOptions
import com.rokid.security.glass3.open.sdk.camera.CameraZoomSession
import com.rokid.security.glass3.sdk.base.data.media.CameraZoomConfig
import com.rokid.security.glass3.sdk.base.data.media.CameraZoomState
import com.rokid.security.glass3.sdk.base.data.media.RecordConfig
import com.rokid.security.glass3.sdk.base.data.media.TextWatermarkConfig
import com.rokid.security.glass3.sdk.base.data.media.TextWatermarkItem
import com.rokid.security.system.server.media.callback.VideoCallback

fun requestRecording(serverWritableDirectory: String, callback: VideoCallback): CameraZoomSession {
    val session = CameraZoomSession()
    session.listener = object : CameraZoomSession.Listener {
        override fun onStateChanged(state: CameraZoomState) {
            Log.i("CameraDemo", "ready=${state.ready}; actual=${state.appliedZoom}")
        }
        override fun onError(code: Int, reason: String) {
            Log.w("CameraDemo", "Record session error=$code reason=$reason")
        }
    }
    val config = RecordConfig(
        path = serverWritableDirectory, min = 1, enableAudio = true,
        width = 1280, height = 720, fps = 20,
    )
    val watermark = TextWatermarkConfig(
        enabled = true,
        items = listOf(TextWatermarkItem(id = "task", text = "任务进行中")),
        timestampEnabled = true,
    )
    try {
        CameraMediaOptions.startRecord(callback, config, CameraZoomConfig.explicit(1.575f), watermark, session)
        return session
    } catch (error: Exception) {
        session.close()
        throw error
    }
}
// 页面保存 session；停止/退出时在后台执行 session.close()，继续接收最终文件与终态回调。
```

### 相机会话与照片的底层回调

以下 AIDL 均位于 `com.rokid.security.system.server.media`。AIDL 签名中的 `in` 仅表示入参序列化方向；`oneway` 回调在 Binder 线程异步分发，不会替你切换 Android 主线程。

| 接口及签名 | 调用时机、参数和注意事项 |
| --- | --- |
| `ICameraSessionCallback.onSessionCreated(ICameraSessionControl control)` | Options 控制句柄创建时触发，早于媒体真正启动；不是 `onStart` 或首帧事件。原始 AIDL 使用者需保存句柄并注册状态监听，过期请求的迟到句柄立即 close。 |
| `ICameraSessionCallback.onSessionError(int code, String reason)` | 控制启动失败，可发生在已创建句柄之后；常规启动失败码为 `-1`，Surface 契约失效还会报告 `-8 / surface_contract_stale`。`reason` 是诊断标识，建议记日志、映射成用户文案。媒体运行时错误继续由对应媒体 callback 报告，同步参数/权限错误不保证进入此回调。 |
| `ICameraZoomStateListener.onZoomStateChanged(in CameraZoomState state)` | 注册后先推当前快照，之后仅状态边沿通知。`ready=false` 时不得拿旧缓存帧冒充新配置结果；`ready=true` 也仍需帧代次匹配。 |
| `ICameraPhotoCallback.onPhoto(String path, int width, int height, in CameraZoomState state)` | 本次 JPEG 已写入。`path` 是请求路径，`width/height` 是实际文件像素尺寸；`state` 为拍照使用的共享快照。独立拍照可能没有持续会话 ready/frameGeneration 证据，不能把它套到后续预览帧。 |
| `ICameraPhotoCallback.onError(int code, String reason)` | 本次拍照失败。AIDL 定义 `-4` 忙碌、`-5` 配置变化、`-6` 超时、`-7` 拍照失败；`reason` 是诊断标识。失败/取消后不再发送迟到成功，客户端死亡则不保证收到终态。 |

`ICameraSessionCallback`、`ICameraZoomStateListener`、`ICameraPhotoCallback` 都是 `oneway interface`。`CameraZoomSession.Listener` 是它们之上的 Kotlin 监听封装，不应把两套重复注册成两个不同业务会话。

照片错误码要区分接口约定与当前实现：`c2b9677` 的 `MediaServerImpl.takePhotoWithOptions` **直接发出的码只有 `-4/-6/-7`**；`-5` 仍列在 AIDL 注释中，但此方法没有直接发送分支。不能写成当前固件一定会单独报告 `-5`，也不能仅等 `onError` 而漏接同步异常。

`ICameraSessionControl` 的准确底层方法如下，全部是同步 Binder 调用，控制句柄绑定发起方 UID；跨 UID 调用抛 `SecurityException`：

```aidl
int setZoom(float zoomRatio);
int followShared();
CameraZoomState getState();
void setStateListener(ICameraZoomStateListener listener);
void close();
void observeSurfaceFrame(long sensorTimestampNs);
```

其中 `setZoom/followShared/observeSurfaceFrame` 的单位和接受码与上文一致。`getState` 是共享相机快照，旧句柄 close 后还能得到快照也不代表仍持有输出；`setStateListener` 替换唯一监听并立即推当前状态，传 `null` 注销。`close` 幂等停止该输出；业务不应在停止后复用旧句柄。

### Zoom 请求与状态模型

模型位于 `com.rokid.security.glass3.sdk.base.data.media`，均为 Parcelable。

```kotlin
data class CameraZoomConfig(val mode: Int = DEFAULT, val zoomRatio: Float = 1f)
// 常量：DEFAULT = 0，FOLLOW_SHARED = 1，EXPLICIT = 2
// @JvmStatic fun explicit(ratio: Float): CameraZoomConfig
```

`DEFAULT` 和 `FOLLOW_SHARED` 都跟随共享结果，忽略 `zoomRatio`，不等于申请 1×。只有 `EXPLICIT` 使用倍率；要求有限且 `>= 1f`，未知 mode 非法。`explicit()` 只是构造请求，参数在启动或更新时才会被拒绝。

`CameraZoomState` 的字段必须分清“请求/计划/实测”：

| 字段（类型；默认值） | 解释 |
| --- | --- |
| `requestedZoom: Float = 1f` | 当前仲裁胜出的倍率意图，不一定是本调用方最后请求的值。 |
| `desiredZoom: Float = 1f` | 胜出倍率经设备能力限制后准备下发的值。 |
| `appliedZoom: Float = Float.NaN` | 已由输出确认的倍率，未确认用 NaN；不能取整或替换成 1×。 |
| `desiredRevision: Long = 0` | 当前控制配置修订号，不是帧编号。 |
| `appliedRevision: Long = -1` | 已确认配置修订号，`-1` 未知；切换期间可落后于 desiredRevision。 |
| `ready: Boolean = false` | 当前配置已有有效帧证据，不保证所有旧缓存帧都属于该配置。 |
| `reason: Int = 0` | 诊断来源保留值，不建议用它控制业务状态机。 |
| `sourceWidth/sourceHeight: Int = 0` | 相机源像素尺寸，`0` 尚未配置；并非订阅输出、Surface buffer 或旋转后 JPEG 尺寸。 |
| `frameGeneration: Long = -1` | 已确认的帧配置代次；与 NV21 `configurationGeneration` 对比，不能与 I420 缓冲池 generation 或 desiredRevision 互换。 |
| `limitedByCapability: Boolean = false` | 胜出请求受设备倍率范围限制；不表示被 AI 优先级覆盖。 |
| `sensorCropLeft/Top/Right/Bottom: Int = -1` | 实际 CaptureResult 裁剪框，传感器坐标；`-1` 未知，右/下边界不包含在框内。 |
| `videoStabilizationMode: Int = -1` | CaptureResult 的防抖模式值，`-1` 未知；不是最终 EIS 变换矩阵，不能仅凭它和 crop 框还原全部 AR/预览映射。 |

例如有 NV21 配置帧时，确认倍率的必要条件可写为 `state.ready && state.appliedZoom.isFinite() && frameGeneration > 0 && frameGeneration == state.frameGeneration`；还需验证该帧宽高和缓冲容量。此处 `frameGeneration` 指该帧回调传来的 **configurationGeneration**，不是 I420 租约字段。

### TextWatermarkConfig / TextWatermarkItem

水印模型与 zoom 模型同包。尺寸、边距、坐标和字号均以输出叠加画布的**像素**为单位，不是 dp/sp 或归一化坐标。

| `TextWatermarkConfig` 字段 | 默认值及作用 |
| --- | --- |
| `enabled: Boolean` | `false`；总开关。 |
| `items: List<TextWatermarkItem>` | 空列表；文字项。 |
| `timestampEnabled: Boolean` | `false`；附加时间戳项。 |
| `timestampPattern: String` | `"yyyy-MM-dd HH:mm:ss"`；Java `SimpleDateFormat` 格式，使用设备 locale/时区，非法格式按默认格式回退。 |
| `timestampPosition: Int` | `WatermarkPosition.BOTTOM_RIGHT`。 |
| `timestampMarginX/Y: Int` | 各 `24` px。 |
| `timestampTextSizePx: Int` | `20` px。 |
| `maxItems: Int` | `8`；本配置普通文本项的渲染上限，可配置，不是不可修改的 SDK 硬上限。当前渲染器按 `maxItems.coerceAtLeast(0)` 截取，时间戳另外绘制，不占此数量。 |

`fun hasWatermark(): Boolean` 的实际表达式为 `enabled && (timestampEnabled || items.isNotEmpty())`；它不会检查每条文字是否空白，因此“返回 true”仍可能没有可见文字。渲染器会过滤空白项，再按 `zOrder` 升序排序、按 `maxItems` 截取，最后绘制时间戳。

| `TextWatermarkItem` 字段 | 默认值及作用 |
| --- | --- |
| `id: String` | `""`；动态改字的匹配键。业务应给需要更新的条目设置稳定、非空且唯一的 ID。 |
| `text: String` | `""`；文字，支持换行；渲染器亦将字面 `\n` 替换成换行。 |
| `position: Int` | `TOP_LEFT`；预设位置或 `CUSTOM`。 |
| `marginX/marginY: Int` | 各 `24` px；预设角落到输出边缘的距离。 |
| `x/y: Int` | 各 `0` px；CUSTOM 时参考点坐标。 |
| `textSizePx: Int` | `20`；实际绘制下限 1 px。 |
| `textColor: Int` | `-0x1`，即 `0xFFFFFFFF` 白色 ARGB。 |
| `backgroundColor: Int` | `0x66000000`，半透明黑色 ARGB。 |
| `paddingX/paddingY: Int` | `8/4` px；文字背景内边距。 |
| `alpha: Float` | `1f`；绘制时限制到 `0..1` 并乘以颜色自身 alpha。 |
| `anchor: Int` | `LEFT_TOP`；CUSTOM 时坐标参考点的锚点；预设 position 使用对应默认锚点。 |
| `zOrder: Int` | `0`；普通文本项排序，值大者后绘制。 |

位置常量为 `TOP_LEFT=1`、`TOP_RIGHT=2`、`BOTTOM_LEFT=3`、`BOTTOM_RIGHT=4`、`CENTER=5`、`CUSTOM=100`。锚点常量为 `LEFT_TOP=1`、`RIGHT_TOP=2`、`LEFT_BOTTOM=3`、`RIGHT_BOTTOM=4`、`CENTER=5`。渲染器会限制文本框起点在画布范围内，但不会自动保证超长文字完整容纳；业务应按实际输出尺寸控制文本长度/字号。

动态更新 Surface 文字须在启动时已经配置对应 `id`，后续调用 `updateCameraShareWatermarkText` 时传回**启动时同一个 Surface callback Binder**。该接口只改已有文字项的 text，不是重设整份配置，不会新增不存在的条目。当前实现要求 `itemId` 非空白、`itemId.length <= 64`、`text.length <= 160`；不符合时直接忽略，`Unit` 返回不代表已渲染。录像接口可设置启动水印，但没有本节所述 Surface 动态改字 API 的等价录像入口。

### 本节源码定位（提交 c2b9677）

| 内容 | SDK 仓库相对路径与行号 |
| --- | --- |
| 五个会话控制方法 | `glass3/open-sdk/src/main/java/com/rokid/security/glass3/open/sdk/camera/CameraZoomSession.kt:46`（setZoom）、`:58`（followShared）、`:69`（refreshState）、`:83`（observeSurfaceFrame）、`:99`（setZoomAndAwait） |
| 监听、callback、关闭、能力位 | 同文件 `:18`、`:123`、`:165`、`:175` |
| 录像/拍照 Helper | `glass3/open-sdk/src/main/java/com/rokid/security/glass3/open/sdk/camera/CameraMediaOptions.kt:30`、`:52` |
| 拍照实际等待与错误分支 | `glass3/system-server/src/main/java/com/rokid/security/system/server/impl/media/MediaServerImpl.kt:531`；录像 Options `:629` |
| 会话创建回调 | `glass3/open-sdk/src/main/aidl/com/rokid/security/system/server/media/ICameraSessionCallback.aidl:7` |
| 会话控制 | `glass3/open-sdk/src/main/aidl/com/rokid/security/system/server/media/ICameraSessionControl.aidl:9` |
| 变焦状态监听 | `glass3/open-sdk/src/main/aidl/com/rokid/security/system/server/media/ICameraZoomStateListener.aidl:7` |
| 照片结果回调 | `glass3/open-sdk/src/main/aidl/com/rokid/security/system/server/media/ICameraPhotoCallback.aidl:7` |
| Zoom 模型 | `glass3/sdk/base-data/src/main/java/com/rokid/security/glass3/sdk/base/data/media/CameraZoomConfig.kt:16`；`CameraZoomState.kt:31` |
| 水印模型、常量 | `glass3/sdk/base-data/src/main/java/com/rokid/security/glass3/sdk/base/data/media/TextWatermarkConfig.kt:8`、`:18`、`:28`、`:44` |
| 水印实际渲染约束 | `glass3/system-server/src/main/java/com/rokid/security/system/server/impl/media/TextWatermarkGlRenderer.kt:137`、`:161`、`:188` |
| 动态改字参数限制和匹配 | `glass3/system-server/src/main/java/com/rokid/security/system/server/impl/media/MediaServerImpl.kt:956`；`CameraShareManager.kt:825` |
| RecordConfig | `glass3/sdk/base-data/src/main/java/com/rokid/security/glass3/sdk/base/data/media/RecordConfig.kt:14` |
| 实际录像尺寸选择 | `glass3/sdk/lib-media-v2/src/main/java/com/rokid/glass/arworkflow/media/GlobalMediaManager.kt:1201`；`glass3/sdk/lib-media-v2/src/main/java/com/rokid/glass/arworkflow/media/record/RecordResolutionSelector.kt:18` |
| 录像分段分钟换算 | `glass3/sdk/lib-media-v2/src/main/java/com/rokid/glass/arworkflow/media/record/MediaRecorder.kt:182`、`:385`；`glass3/sdk/lib-media-v2/src/main/java/com/rokid/glass/media2/engine/compose/CameraGlComposeHost.kt:748` |

<a id="tts-details"></a>

## 在线 / 离线 TTS 会话管理接口详解

> 核对基线：`c2b9677b4e2096e0b121946cc057f5b411d0fb63`（已从临时 Git 仓读取指定提交对象逐项复核；未以当前远端 HEAD 代替）。Manager 公开包名均为 `com.rokid.security.glass3.open.sdk.tts`。本文区别 SDK 客户端封装、该提交中的 system-server 实现，以及旧服务回退；依赖版本相同不代表设备固件实现相同。

### 1. Manager 入口与完整方法签名

```kotlin
val online: GlassTtsManager = GlassSdk.getGlassTtsManager()
val offline: GlassOfflineTtsManager = GlassSdk.getGlassOfflineTtsManager()
```

两个 getter 返回共享的非空 Manager；并不保证底层服务已经绑定。应先完成 `GlassSdk.bindSecurityService(...)`，确认 SDK 连接，再提交播报。以下签名中的 `Unit` 表示没有返回值。

两个 Manager 共同提供：

```kotlin
@JvmOverloads
fun speak(
    text: String,
    queueMode: Int = TtsQueueMode.FLUSH,
    callback: TtsSessionCallback? = null
): Long
fun cancel(sessionId: Long): Boolean
fun cancelAll(): Unit
fun getSessionState(sessionId: Long): Int
fun addListener(listener: TtsSessionCallback): Unit
fun removeListener(listener: TtsSessionCallback): Unit
fun release(): Unit
```

仅在线 `GlassTtsManager` 额外提供：

```kotlin
fun cancelCurrent(): Boolean
fun getActiveSessionId(): Long
```

| 方法 | 实际含义与使用边界 |
|---|---|
| `speak(text, queueMode, callback)` | 提交一个会话。`ADD=1` 追加；`FLUSH=0` 替换已有播放。返回 ID 不等于已播放、已完成或无错误；同时观察回调。显式指定队列模式，避免不经意使用默认 FLUSH。 |
| `cancel(sessionId)` | 新服务按调用 PID 验证会话归属。`true` 表示接受取消，不保证同步 native 合成已经退出；以 CANCELED 回调或可查询状态确认业务终态。页面只取消自己保存的有效 ID。 |
| `cancelAll()` | 返回 `Unit`，不能据返回判断成功。新版在线调用 `cancelAllTts()`，新版离线调用 `cancelAll()`；对应 system-server 按 **Binder 调用 PID** 筛选活动和排队会话。同进程的其他页面也受影响；同包另一个进程不属于同一 PID 范围。 |
| `addListener(listener)` | 将对象加入本 Manager 的全局监听集合，并尝试注册其共享远端监听。接收该 Manager 可见会话的状态，不限于当前页面。集合为 `CopyOnWriteArraySet`，同一个对象重复添加不产生多份全局注册；Manager 强引用该对象。 |
| `removeListener(listener)` | 从本 Manager 的本地全局集合移除同一个监听对象。它**不取消播报、不注销 Manager 的共享远端 Binder 监听、不移除通过 `speak(..., callback)` 保存的单会话 callback**。不要以“服务端没有 unregister 日志”判定该方法失败。已经在途的回调仍需页面代次/退出标志过滤。 |
| `getSessionState(sessionId)` | 按需查询，通常含同步 Binder 调用；服务不存在或调用抛错时尝试本地缓存。远端正常返回 UNKNOWN 时，Manager 不会自动用缓存终态覆盖。不能每帧/高频轮询，也不能把 UNKNOWN 直接当作播放失败。 |
| 在线 `getActiveSessionId()` | 读取当前共享在线 Manager 的本地 `AtomicLong`，不发 Binder。STARTED 时设置，匹配 ID 的 COMPLETED/CANCELED/ERROR 时清为 `-1`。这是该进程 Manager 最后观察到的活动 ID；并非全设备活动查询，也不一定属于当前页面。返回 `-1` 不能证明设备上没有任何 TTS。 |
| 在线 `cancelCurrent()` | 实现就是 `cancel(activeSessionId.get())`。返回语义与 `cancel(id)` 相同；缓存无有效活动 ID 时通常返回 false。它会取消共享 Manager 当前活动会话，可能由同进程其他页面创建，不是“取消本页面最后一次请求”。 |
| `release()` | 释放整个共享 Manager 的远端监听及本地回调/状态缓存；不是普通页面清理接口，也不是保证停止所有声音的接口。SDK 整体解绑会管理释放。多个页面共享 Manager 时不要在一个页面退出时调用。 |

线程：`speak`、`cancel`、`cancelAll`、`getSessionState`、`addListener`、`release` 都可能涉及同步 Binder，放在串行后台线程执行。`removeListener` 和在线 `getActiveSessionId` 当前为本地操作，但和页面其他会话操作同一串行队列处理更容易保持顺序。`cancelAll(): Unit` 只表示 Kotlin 方法无返回值，**不表示该 Binder 操作一定是 oneway 或不会阻塞**。

### 2. FLUSH 与“按调用 PID 取消”不是同一作用范围

在该源码实现中，不能将队列常量注释中的“清空当前应用”当作跨应用隔离保证：

- 在线 `TtsServiceImpl.speakTts()` 对 ADD 以外模式会替换其全局 `activeSessionId`，并调用 `collectAndClearPendingLocked()` 清空共享待播队列；没有按发起 PID 过滤。
- 离线 `OfflineTtsServiceImpl.speak()` 将 `queueMode` 透传给共享本地 TTS 引擎；`TtsService.enqueue()` 的 FLUSH 清空该引擎整个 pending 队列并取消 active，也没有发起应用/PID 的筛选。
- 因而 **FLUSH 可能影响同一引擎中其他调用方的播报**。它不是 `cancelAll()` 的安全等价写法。没有明确打断需求时采用 ADD；页面退出使用自己记录的 ID 调用 `cancel(id)`。
- 新版 `cancel(id)` / `cancelAll()` 的服务入口则明确用 `Binder.getCallingPid()` 限制作用范围。在线与离线是两个 Manager/服务入口，取消某一个不会顺带取消另一个引擎。

旧服务回退另论：在线 Manager 的 `cancelAll()` 在新方法抛错时会回退旧 `doCancelTts()`；不能据新版 PID 限制推断旧全局接口同样隔离。离线 Manager 的 `cancelAll()` 没有可保证成功的旧接口回退，异常会被捕获；旧服务下 `Unit` 返回同样不代表已取消。

### 3. TtsSessionCallback：精确签名、触发顺序与线程

```kotlin
abstract class TtsSessionCallback {
    open fun onStateChanged(
        sessionId: Long,
        state: Int,
        errorCode: Int,
        errorMessage: String?
    )
    open fun onQueued(sessionId: Long)
    open fun onStart(sessionId: Long)
    open fun onComplete(sessionId: Long)
    open fun onCancel(sessionId: Long)
    open fun onError(sessionId: Long, code: Int, message: String?)
    open fun onServiceConnectState(connect: Boolean)
}
```

所有方法默认空实现，按需要 override。常规状态派发先调用 `onStateChanged(...)`，再调用对应的便捷回调；如果两者都记录同一个事件，会产生两份业务通知。单会话 callback 与全局 listener 是两条注册路径，**把同一个对象同时传入 speak 和 addListener 会使它通过两条路径收到回调**，全局集合的去重不能去掉这种重复。

| 状态常量/数值 | 便捷回调 | 表示什么 |
|---|---|---|
| `UNKNOWN=-1` | 没有专属便捷回调 | 会话不存在、状态无法查询或本地无记录；不是 ERROR。 |
| `QUEUED=0` | `onQueued(id)` | 已进入队列，尚未开始生成或播放。 |
| `STARTED=1` | `onStart(id)` | 会话已开始生成或播放，不能据此认为播报完成。 |
| `COMPLETED=2` | `onComplete(id)` | 正常结束的终态。客户端回调仍不能独立证明扬声器实际可闻、音量正确或内容质量合格；实际体验需试听。 |
| `CANCELED=3` | `onCancel(id)` | 业务播放被取消的终态；可能来自显式取消或 FLUSH。底层当前同步 native 推理可能还在收尾。 |
| `ERROR=4` | `onError(id, code, message)` | 失败终态；优先记录错误码，不依据诊断字符串做业务分支。 |

`TtsSessionState.isTerminal(state): Boolean` 对 COMPLETED、CANCELED、ERROR 返回 true。业务应按 sessionId 去重并忽略已经结束会话的迟到状态，不能要求每次都完整看到所有中间状态。在线 Manager 注册单会话回调后会查询/回放当前状态；回调可能直接看到较后状态。离线 Manager 会去掉重复状态和已终态之后的状态；在线 Manager 本身没有相同的去重保护，不要跨实现假定 exactly-once。

回调不保证在主线程：远端通知通常在 Binder 线程，旧接口回退和状态回放也可能在调用 speak 的线程同步发生；甚至可能在 `speak()` 返回 ID 之前发生。回调应迅速返回，UI 更新切主线程，较重工作投递到后台队列。不要在 Binder 回调等待 UI、另一次 Binder、网络或磁盘，也不要让异常逃出业务回调。

`onError` 的特殊入口：服务缺失/提交失败时，Manager 可以直接调用 `onError(-1, ...)`，并不先调用 `onStateChanged(ERROR, ...)`。只覆写 `onStateChanged` 会漏掉这种失败；应额外处理无有效 ID 的 `onError`，并检查 speak 返回值。

连接回调：在线 Manager 会把底层 `onServiceConnectState(connect)` 分发给全局 listener 和仍保存的会话 callback；它没有 sessionId，不是某一条播报的成功/失败或完成信号。离线 Manager 没有主动派发该连接回调的实现，主要通过 ERROR、返回值与 `GlassSdk.isReady()` 判断入口可用性。不要等待离线 onServiceConnectState(true) 才发起播报；在线也不要把一次 connect=false 当作某个播报已经失败。

### 4. 错误码与旧服务回退

客户端公开常量为 `TtsErrorCode.NONE=0`、`SERVICE_UNAVAILABLE=-1`、`REMOTE_ERROR=-2`。它们不是全部可能的服务端错误码。该实现中的服务错误还包括在线空文本 `-1001`、服务不可用 `-1002`；离线引擎还可能返回队列满 `-1003`、音频背压 `-1004`、引擎未就绪 `-1005`、引擎进程死亡 `-1006`、音频错误 `-1007`，以及底层扩展值。业务应保留未知错误码处理分支。

离线 Manager 在接口版本不足或调用异常时尝试 `playTtsMsg(text)`，成功回退仍返回 `INVALID_SESSION_ID=-1L`，最多提供合成的 STARTED 通知，没有完整终态和按 ID 取消能力。在线 Manager 回退 `doSpeechTts(text)` 时会生成**本地正数 ID**，所以“ID > 0”也不能独立证明新版会话 API 可用、ADD 排队行为正常或服务支持该 ID 查询。验证新能力时应结合 `getTtsApiVersion()` / `getOfflineTtsApiVersion()`、服务端日志及实际状态过程；不要将 IPC 异常直接解释成“设备一定不支持”。

离线服务在终态通知后移除 `sessionOwners`，后续 `getSessionState(id)` 会因无 owner 返回 UNKNOWN；Manager 仅在服务缺失或异常时回用缓存。页面应保存已经收到的终态，并分别显示“当前查询 UNKNOWN / 最后已知 COMPLETED”等状态。

### 5. 接入示例：新增控制方法如何使用

下面展示独立操作，不应在 speak 后立刻执行取消。所有可能的 Binder 调用通过同一个 `ExecutorService` 串行提交；更新 UI 时另行切主线程。listener 对象必须保留到移除时，业务同时保留本页面实际返回的有效 ID。

```kotlin
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.tts.*
import com.rokid.security.glass3.open.sdk.uitls.log.L
import java.util.concurrent.Executors

val worker = Executors.newSingleThreadExecutor()
val online = GlassSdk.getGlassTtsManager()
val offline = GlassSdk.getGlassOfflineTtsManager()

// 示例只输出状态摘要；不记录播报正文或远端诊断正文。
val listener = object : TtsSessionCallback() {
    override fun onStateChanged(id: Long, state: Int, code: Int, message: String?) {
        L.i("TtsExample", "session=$id state=$state code=$code")
    }
    override fun onError(id: Long, code: Int, message: String?) {
        // 常规 ERROR 已由 onStateChanged 处理；补上提交失败的直接 onError。
        if (id <= 0L) L.w("TtsExample", "submit_failed code=$code")
    }
    override fun onServiceConnectState(connect: Boolean) {
        L.i("TtsExample", "online_service_connected=$connect")
    }
}
worker.execute { online.addListener(listener) }

// 用户选择“停止应用当前在线播报”时执行；这是共享 Manager 范围。
fun cancelCurrentOnline() = worker.execute {
    val observedId = online.getActiveSessionId()
    val accepted = online.cancelCurrent()
    L.i("TtsExample", "observedActiveId=$observedId cancelAccepted=$accepted")
    // 观察与取消之间活动 ID 可能变化；若必须精确取消，使用自己保存的 ID。
}

// 仅当业务明确要停止该调用进程的全部播报时执行。
fun cancelAllForThisProcess() = worker.execute {
    online.cancelAll()
    offline.cancelAll()
    // 没有成功返回值：继续通过已有 ID 的状态/回调核对。
}

// 页面释放自己的全局监听；不 release 共享 Manager，也不自动 cancelAll。
fun removePageListener() = worker.execute {
    online.removeListener(listener)
}
```

此片段刻意将全进程控制写成具名操作函数，避免把它们放进页面退出逻辑。页面退出还需针对自己保存的 ID 发起 `cancel(id)`、使迟到回调失效，并在清理任务入队后关闭自己的 executor；不要关闭别处共享的线程池。无有效 ID 的错误应通过 `speak(..., perRequestCallback)` 单独处理；不要把上述同一个全局 listener 再作为单会话 callback 传入，以免重复。

### 6. 当前 Demo 的实际覆盖情况

入口为原“消息发送”页，代码为 `glassdemo/app/src/main/java/com/rokid/glass/SendMessageActivity.kt` 与 `speech/MessageTtsController.kt`，不是独立 SDK API 页面。

| 能力 | 当前 Demo 使用情况 |
|---|---|
| 在线/离线 `speak` + ADD/FLUSH | 在线、离线分别使用相邻的播报/停止按钮；模式独占首行，点击时把当前模式与说明打印到下方日志，只影响新 TTS，不控制 ASR。页面固定已确认 v2 的 SDK service 调用新会话方法，避免 Manager 隐式旧接口回退。 |
| 在线/离线 `cancel(id)` | 对应的独立停止按钮取消本页该引擎在点击时已有的播放和排队请求，始终按保存的 ID 定向取消。退出也仅清理本页。 |
| `getSessionState` | 播报/停止后自动核对状态，在下方显示当前结果与最后已知状态，区分 UNKNOWN；无独立查询按钮。 |
| 在线 `getActiveSessionId` | 方法说明和应用级示例保留在本文；页面无需独立查询按钮，也不使用该共享活动 ID 取得会话所有权。 |
| 在线/离线 `addListener/removeListener` | 本文保留 Manager 方法说明及独立示例。Demo 消息页使用进程共享 AIDL 会话桥，按服务实例重建监听，避免 Manager 在重连后保留旧注册标记；同一引擎勿混用另一套 Manager/AIDL 监听。 |
| 在线 `cancelCurrent` | 没有独立按钮，也不调用；避免共享 Manager 活动会话属于另一个页面时误取消。已通过按本页 ID 取消满足页面需求。 |
| 在线/离线 `cancelAll` | 没有按钮，也不调用；它是调用进程范围操作，可能取消同进程其他页面的会话。文档保留用于应用级业务接入。 |
| 状态回调 | Demo 会话桥接收在线 `onTtsSessionState` 与离线 `onSessionState`，按引擎、服务代次和真实会话 ID 归属后自动显示；Manager 的 `TtsSessionCallback` 用法见上文。 |
| `release` | 页面不调用，避免重置同进程其他调用方共用的 Manager。 |

已复核当前 Demo 双语 `message_api_strings.xml`：FLUSH 提示明确说明根据固件实现可能中断其他应用播报，与本节所述共享引擎边界一致。当前 Demo 的配对按钮、日志说明和会话桥与本节范围一致。

### 7. 指定提交的源码证据索引

以下行号均按 `c2b9677b4e2096e0b121946cc057f5b411d0fb63` 核对，13 个相关 SDK/服务/AIDL 文件与前述实现说明逐项一致。

| 文件（仓库相对路径） | 关键证据 |
|---|---|
| `glass3/open-sdk/src/main/java/com/rokid/security/glass3/open/sdk/tts/GlassTtsManager.kt` | 65 speak；95 cancel；114 cancelCurrent；118 cancelAll；133 addListener；138 removeListener；142 getSessionState；151 release；191 legacy 回退；236 状态派发/活动 ID 更新；260 通用+便捷回调顺序；281 连接派发；290 getActiveSessionId。 |
| 同目录 `GlassOfflineTtsManager.kt` | 69 speak；105 cancel；117 cancelAll；127 getSessionState；141 addListener；147 removeListener；157 release；176 legacy 回退；197 状态去重；212 回调顺序。 |
| 同目录 `TtsSessionCallback.kt` | 25 onStateChanged；34 onQueued；38 onStart；42 onComplete；46 onCancel；50 onError；59 onServiceConnectState。共 7 个公开可覆写方法。 |
| 同目录 `TtsSessionState.kt`、`TtsQueueMode.kt`、`TtsErrorCode.kt` | 状态、队列模式及客户端错误码定义。 |
| `glass3/system-server/src/main/java/com/rokid/security/system/server/impl/tts/TtsServiceImpl.kt` | 133 speakTts；193 cancelTts；198 cancelAllTts；305 cancelSession 的 PID 校验；437 全共享待播队列清理。 |
| `glass3/system-server/src/main/java/com/rokid/security/system/server/impl/tts/offline/OfflineTtsServiceImpl.kt` | 35 终态后移除 owner；47 speak；54 cancel；59 cancelAll；68 getSessionState 的调用 PID 检查。 |
| `glass3/sdk/lib-tts/src/main/java/com/rokid/os/sprite/tts/TtsService.java` | 129 enqueue 的共享 FLUSH；339 播放完成事件；458 状态通知。 |
| 同目录 `TtsServerManager.java`、`LocalTtsErrorCode.java` | 离线引擎桥接、队列模式透传及服务端扩展错误码。 |
| `glass3/open-sdk/src/main/aidl/com/rokid/security/system/server/tts/ITtsService.aidl`、其 `offline/IOfflineTtsService.aidl` | 服务方法签名；cancelAll 为普通同步 Binder void 方法，未标记 oneway。 |

<a id="phone-progress"></a>

## 手机 FileReceiveListener 三参进度与兼容

准确类型是 `com.rokid.security.phone.sdk.api.msg.listener.FileReceiveListener`；不是眼镜端 AIDL listener，也不是 `FileReceiveV2Listener`。

```kotlin
interface FileReceiveListener {
    fun onStart() {}
    fun onProgressChanged(progress: Float) {}
    fun onProgressChanged(progress: Float, transferredBytes: Long, totalBytes: Long) {
        onProgressChanged(progress)
    }
    fun onComplete(filePath: String) {}
    fun onFail() {}
    fun onCancel() {}
}
```

兼容方向如下：

| 业务覆写 | SDK 调用单参 | SDK 调用三参 |
| --- | --- | --- |
| 只覆写单参 | 进入旧单参实现 | 默认三参实现转调旧单参 |
| 只覆写三参 | 进入空的默认单参，业务收不到该次进度 | 进入三参实现 |
| 同时覆写两者 | 进入单参实现 | 进入三参实现，不自动再调用单参 |

这是源码层和默认方法的回调兼容方向，不应仅凭这段接口源码对全部已编译二进制实现承诺 ABI 兼容；依赖升级后应重新编译业务。

在当前实现中：

- P2P 本地发送 `FileSender` 回调三参，`progress` 是 0–100 的百分比。`transferredBytes/totalBytes` 为 Long 字节数；断点续传时 transferredBytes 包含已经续传跳过的前缀，不是“本轮网络刚发送的字节”。
- P2P 对端进度消息经 `FileOperater` 同样进入发送方同一个 listener 的三参方法。旧对端消息没有字节字段时，`FileExtra` 的缺省值是 `0L/0L`，不能把它解释为真实传输 0 字节或完成。
- 经典蓝牙发送，以及手机本地接收广播给旧 listener 的路径仍调用单参。接收用的 `FileReceiveV2Listener.onProgressChanged(filePath, progress)` 也不是新三参。
- 因同一发送 listener 可能同时接收本地发送进度和对端接收进度，不保证逐条事件严格递增，也没有新增“来源”参数。进度到 100% 不应替代 `onComplete/onFail/onCancel/onResult` 的完成处理；本地 Socket 写完/flush 完成与对端确认接收完成是不同阶段。
- `FileSender` 和 `BtFileSender` 的指定分发处切主线程，但接口本身没有统一主线程契约，消息分发路径也不是每处都显式切主线程；业务仍须自行把 UI 更新切到主线程，并避免在回调里做文件 I/O。

兼容两类路径的示例：

```kotlin
val main = Handler(Looper.getMainLooper())
val listener = object : FileReceiveListener {
    override fun onProgressChanged(progress: Float) {
        // 旧路径只知道百分比，不能反推成“SDK 实测字节”。
        main.post { showProgress(progress, transferred = null, total = null) }
    }

    override fun onProgressChanged(progress: Float, transferredBytes: Long, totalBytes: Long) {
        val validBytes = totalBytes > 0L && transferredBytes in 0L..totalBytes
        main.post {
            showProgress(progress,
                transferred = transferredBytes.takeIf { validBytes },
                total = totalBytes.takeIf { validBytes })
        }
        // 不再调用默认三参实现，否则会额外触发本对象的单参分支。
    }
}
```

`showProgress` 是业务 UI 方法；零字节文件需结合已知文件信息及完成回调单独判断，不能仅据 0/0 判完成。接收监听通过 `addFileReceiveListener/removeFileReceiveListener` 管理；发送通过 `IFileOperate.sendFile(..., listener, onResult)` 传入对应 listener，并与自己的传输生命周期绑定。

来源：`phone/sdk/sdk-api/src/main/java/com/rokid/security/phone/sdk/api/msg/listener/FileReceiveListener.kt`；`phone/sdk/sdk-server/src/main/java/com/rokid/security/phone/sdk/server/msg/file/{FileSender,FileOperater,BtFileSender,BtFileOperater}.kt`；`base-common/sdk-base-common/src/main/java/com/rokid/security/sdk/base/common/FileExtra.kt`。

<a id="phone-extconfig"></a>

## ExtConfig.configuredReportEnabledOrNull：null 从哪里来

类型：`com.rokid.security.phone.sdk.api.feature.api.appconfig.ExtConfig`。

```kotlin
class ExtConfig {
    @SerializedName("reportEnabled")
    private var configuredReportEnabled: Boolean? = null

    var reportEnabled: Boolean
        get() = configuredReportEnabled == true
        set(value) { configuredReportEnabled = value }

    fun configuredReportEnabledOrNull(): Boolean? = configuredReportEnabled
}
```

新增方法读取内部可空字段，保留“未配置”这一状态；原 `reportEnabled: Boolean` 的 getter 保留旧行为，把未配置与显式 false 都折叠成 false。它不是请求服务查询、不是错误码，也不是防抖或录制状态。

| 实际输入/操作 | configuredReportEnabledOrNull() | reportEnabled |
| --- | --- | --- |
| `ExtConfig()`，尚未赋值 | `null` | `false` |
| Gson 反序列化 `{}`，缺少 reportEnabled | `null` | `false` |
| Gson 反序列化 `{"reportEnabled":null}` | `null` | `false` |
| Gson 反序列化 `{"reportEnabled":false}` | `false` | `false` |
| Gson 反序列化 `{"reportEnabled":true}` | `true` | `true` |
| `config.reportEnabled = false` | `false` | `false` |

若外层 `AppItemBean.extConfig` 本身不存在或为 JSON null，则 `app.extConfig?.configuredReportEnabledOrNull()` 的 null 还可能来自 Kotlin 安全调用；这种情况下方法根本没有执行。二者都可表示当前配置没有提供显式值，但不应把“对象不存在”误称为 getter 请求失败。

真实 null 示例（直接使用 Gson 和真实 SDK 类型）：

```kotlin
val gson = Gson()
val missing = gson.fromJson("{}", ExtConfig::class.java)
check(missing.configuredReportEnabledOrNull() == null)
check(!missing.reportEnabled)

val explicitFalse = gson.fromJson("""{"reportEnabled":false}""", ExtConfig::class.java)
check(explicitFalse.configuredReportEnabledOrNull() == false)

val explicitNull = gson.fromJson("""{"reportEnabled":null}""", ExtConfig::class.java)
check(explicitNull.configuredReportEnabledOrNull() == null)

val noExtConfig = gson.fromJson("""{"id":"example"}""", AppItemBean::class.java)
check(noExtConfig.extConfig == null)
check(noExtConfig.extConfig?.configuredReportEnabledOrNull() == null)

// 业务默认值必须在读取可空配置后应用，显式 false 仍优先。
val configured = app.extConfig?.configuredReportEnabledOrNull()
val effective = configured ?: defaultForThisApp
```

不要先把旧 `reportEnabled` 读出再写回再测试 null；一旦通过 setter 写入 false，就已变成显式 false，无法恢复最初的缺省信息。公网/私有化后端返回 payload 不同，也应以实际字段是否出现为准，不要在 Demo 中用强制赋 null 伪造接口行为。

源码现有业务策略 `LauncherReportConfigPolicy.resolve(appCode, configuredValue)` 是 `configuredValue ?: (appCode == "identification")`：identification 在未配置时保持默认开启，其他 appCode 默认关闭；显式 false 始终优先。这是该 Launcher 业务默认策略，不是 ExtConfig API 对所有业务的统一默认值。

来源：`phone/sdk/sdk-api/src/main/java/com/rokid/security/phone/sdk/api/feature/api/appconfig/LauncherRootBean.kt`；`phone/phone-demo/src/main/java/com/rokid/security/phone/sdk/template/feature/appinstall/LauncherAppManager.kt`；`base-common/project-base-common/src/main/java/com/rokid/security/project/base/common/bean/LauncherAppConfig.kt`。

## 固定提交源码定位

以下均为 c2b9677 仓库内相对路径与起始行号：

| 内容 | 文件与行号 |
| --- | --- |
| 手机三参默认转单参 | `phone/sdk/sdk-api/src/main/java/com/rokid/security/phone/sdk/api/msg/listener/FileReceiveListener.kt:8` |
| P2P 本地续传字节进度 | `phone/sdk/sdk-server/src/main/java/com/rokid/security/phone/sdk/server/msg/file/FileSender.kt:85` |
| P2P 本地三参进度与 100% | `phone/sdk/sdk-server/src/main/java/com/rokid/security/phone/sdk/server/msg/file/FileSender.kt:109` |
| P2P 对端字节进度透传 | `phone/sdk/sdk-server/src/main/java/com/rokid/security/phone/sdk/server/msg/file/FileOperater.kt:93` |
| P2P 本地接收旧单参进度 | `phone/sdk/sdk-server/src/main/java/com/rokid/security/phone/sdk/server/msg/file/FileOperater.kt:133` |
| 蓝牙发送旧单参进度 | `phone/sdk/sdk-server/src/main/java/com/rokid/security/phone/sdk/server/msg/file/BtFileSender.kt:67` |
| 蓝牙对端旧单参进度 | `phone/sdk/sdk-server/src/main/java/com/rokid/security/phone/sdk/server/msg/file/BtFileOperater.kt:130` |
| 缺失字节字段默认 0L | `base-common/sdk-base-common/src/main/java/com/rokid/security/sdk/base/common/FileExtra.kt:27` |
| 外层 extConfig 可空 | `phone/sdk/sdk-api/src/main/java/com/rokid/security/phone/sdk/api/feature/api/appconfig/LauncherRootBean.kt:37` |
| ExtConfig 字段/兼容 getter/新增 getter | `phone/sdk/sdk-api/src/main/java/com/rokid/security/phone/sdk/api/feature/api/appconfig/LauncherRootBean.kt:43` |
| 真实 Launcher 读取 nullable 配置 | `phone/phone-demo/src/main/java/com/rokid/security/phone/sdk/template/feature/appinstall/LauncherAppManager.kt:333` |
| 业务默认值策略 | `base-common/project-base-common/src/main/java/com/rokid/security/project/base/common/bean/LauncherAppConfig.kt:28` |

<a id="rtc-scope"></a>

## RTC 唤醒租约的适用范围

`IOfflineCmdService.acquireRtcWakeLease(IBinder ownerToken, long processGeneration, long sessionId)` 与 `releaseRtcWakeLease(...)` 是配套 RTC 业务用于协调离线唤醒的租约接口。服务端按真实调用 UID 检查 `isRtcVoiceCaller`，并校验 token、进程代次和会话 ID；不满足条件可能直接拒绝并返回，不提供普通应用可据此判断成功的返回值。

它们不是任意第三方应用都能使用的通用唤醒开关，也不是新增防抖能力。本文只记录其存在与边界，不提供绕过调用方限制的通用 Demo 按钮。依据：`glass3/system-server/src/main/java/com/rokid/security/system/server/impl/offlineCmd/OfflineCmdImpl.kt:626`、`:700`。

## 本次文档验证

已按指定提交核对公开签名和对应服务实现，检查后续 b5711b9 的范围差异。直接编译该提交的原始 `LauncherRootBean.kt`、`FileReceiveListener.kt` 与临时语义检查：7 个 ExtConfig 场景和 3 个单/三参数回调兼容场景通过。它们验证配置与回调转发语义，不代表设备传输、播报、拍照或录像的端到端验收。

本机使用 Kotlin 2.2.0、Java 17、Android 34 和已解析的 `2.2.2-E-20261012.062012-11` 及配套依赖，编译通过 6 段完整示例：`startNegotiatedSurface`、`copyAndRelease`、`startI420V1`、`requestZoom`、`requestPhoto`、`requestRecording`。只添加独立包名并整理依赖关系，没有修改示例业务逻辑；签名声明和带业务占位函数的接入骨架不计入这 6 段。

本次补全文档没有重新安装 APK，也没有执行设备媒体任务。Demo 已有构建结果见 [接入说明的验证记录](SDK_2_2_2_APIS.md#范围与验证)。
