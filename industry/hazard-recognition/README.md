# 隐患识别 Demo（九小场所消防）

独立眼镜端 APK，包名 `com.rokid.industry.hazardrecognition`，应用名“隐患识别”。眼镜通过 Wi-Fi 直接调用视觉大模型，无需手机 App。原有眼镜与手机 Demo 不受影响。

## 示例使用声明

本 Demo 仅提供 SDK 调用、代码实现和使用方式的参考。文中的大模型接入、去重、法规映射及界面展示均为示例方案，不代表开发者必须采用相同实现。开发者可根据自身业务需求调整或替换，并自行完成实际应用的适配与验证。

## 界面和操作

按 480×640 眼镜屏幕布局：左上角 128×72dp 实时预览，右侧显示运行/去重状态，下方 12sp 小字号纵向表格，左列依次显示“隐患内容 / 法规依据 / 整改建议”，右列显示最近一次识别的对应内容。移除底部 Wi-Fi 按钮及操作提示，长表格可滚动。

- 单击眼镜触摸板（Enter / DPAD Center）：手动复检，绕过画面缓存；分析中不再发起第二个请求。
- 左右滑动（DPAD Left / Right）：翻阅表格。
- 长按触摸板：暂停或恢复自动识别，已发出的请求完成后仍会更新记录。
- 返回键退出。进入后台释放相机、取消网络请求和待分析帧；最近结果的 10 秒有效期继续计时，隐患摘要保留、画面缓存清空，重新启动进程后摘要也会清空。
- Wi-Fi 连接在眼镜系统设置中配置，应用内不再提供入口。

表格仅显示最近一次成功识别结果，每次返回整表替换，不追加历史列表。一批最多 3 项隐患，在右列按编号对应；新结果为无隐患或无法判断时会清除上一轮隐患内容。每次成功返回后，结果和采样时间最多显示 10 秒；有新结果立即替换并重新计时，没有新结果则清空并显示等待识别。重复画面和请求失败不延长显示时间，回到前台也不会恢复已过期的结果。内部最多保留 30 条隐患摘要，仅用于判断重复，永不渲染为历史列表。

分析状态统一为“识别中”，不显示处理帧数。成功识别过的隐患画面重复时显示“该隐患重复，已收录”；无隐患画面重复时显示“画面重复，已识别”。尚在处理中或失败的画面不会显示“已收录”。

## 采样、批量分析与去重

相机优先选择长边不超过 1280、短边不超过 720 的可用尺寸，保留 SDK 返回的横竖方向，请求 15 FPS 的纯 NV21 回调。没有合适尺寸时使用 SDK 默认尺寸，预览和编码以帧回调中的实际宽高为准。`sampleFps=3` 时每 334ms 从最新 NV21 帧计算一个轻量 Y/V/U 缩略特征，目标约每秒 3 帧本地筛选，不再每隔 10 秒发起检查。

1. **画面去重**：比较当前帧与正在分析/等待分析/近期成功分析画面的颜色和亮度特征，跳过相似画面。
2. **合并请求**：一次携带最多 3 张不同的新画面；同一时间只有一个云端请求。等待队列最多保留最新 3 帧，发送前排除超过 2 秒的旧帧，避免慢网络积压。
3. **隐患去重**：让模型给同一位置、同一对象、同一消防问题返回一次，并参考已有记录 ID。眼镜端再次比较隐患类别、对象/位置、描述和画面特征，仅在条件匹配时更新内部缓存；界面只展示本轮结果。
4. **复核与失败重试**：稳定画面最多缓存 30 秒，`unknown` 仅缓存 3 秒，手动复检绕过缓存。失败不写入成功缓存，网络/API 错误按 2、4、8、16、30 秒退避，不以 3 次/秒反复冲击接口。

每秒 3 帧是本地采样目标，实际云端吞吐取决于网络、编码和推理时间，不保证每秒完成 3 张分析。去重是轻量近似匹配：大幅头动可能再次检查同一问题，小变化可能暂时被跳过；定时复核用于降低遗漏风险，无法保证完全消除重复或漏检。

DeepSeek [上下文缓存](https://api-docs.deepseek.com/guides/kv_cache/)复用输入计算，不等价于业务层的画面/隐患去重。这里的去重由眼镜端和结构化提示词共同实现。

### 开发者接入建议：在服务端去重

**正式业务建议在开发者服务端完成画面与隐患去重**，便于统一管理记录、支持多设备协作，并按业务需要调整去重策略。具体方案由开发者结合自己的业务系统设计，本 Demo 不提供服务端去重实现。

当前 Demo 为便于独立演示，在眼镜端提供轻量去重，可减少重复上传和模型请求；其缓存仅保存在内存中，进程重启后清空，无法跨设备共享，效果也受头动、光照和相似场景影响。

## 九小场所消防范围与法规依据

大模型提示词位于 [InspectionProtocol.kt](app/src/main/java/com/rokid/industry/hazardrecognition/InspectionProtocol.kt) 的 `prompt` 中，由 `request()` 放入系统消息，约定九小场所消防隐患的识别范围和结果格式。开发者可按自己的业务场景调整。

本 Demo 的“法规依据”由 [RegulationCatalog.kt](app/src/main/java/com/rokid/industry/hazardrecognition/RegulationCatalog.kt) 按识别类别做本地示例映射，不由大模型直接生成法条。

正式使用时，应由开发者接入并维护自己的法规数据库或知识库，结合行业、地区和业务要求提供适用依据；本 Demo 仅演示识别与展示流程。

## SDK 接入用例

本工程使用 `com.rokid.security:glass3.open.sdk` 的服务连接、客户端注册和相机共享功能。全部调用集中在 [InspectionActivity.kt](app/src/main/java/com/rokid/industry/hazardrecognition/InspectionActivity.kt)，开发者可按下列顺序阅读和复用。

### 1. 绑定服务并等待客户端就绪

```text
onResume
  → GlassSdk.isReady()
  → 未就绪：GlassSdk.bindSecurityService(applicationContext, callback)
  → IServiceConnectionCallback.onServiceConnected()
  → GlassSdk.registerClient("HazardRecognition", callback)
  → IClientCallback.onReady()
  → startCamera()
```

`isReady()` 只表示服务已连接；为 `true` 时，直接进入客户端注册。`onServiceConnected()` 表示服务连接完成，`onReady()` 才是本例开始调用相机功能的位置。`HazardRecognition` 是业务客户端名称，不是密钥。SDK 回调通过主线程更新页面，并通过会话编号忽略退出页面后才到达的旧回调。

### 2. 查询尺寸，启动共享 NV21 输出

| SDK 方法 / 参数 | 本例取值与用途 |
| --- | --- |
| `CameraShareHelper()` | 创建相机共享辅助对象，由 Activity 持有，退出时用于释放同一会话。 |
| `getSupportedPreviewSizes()` | 返回宽、高、是否竖屏；本例通过 `PreviewSizeSelector.select()` 选择宽高为偶数、长边 ≤1280、短边 ≤720 的最大尺寸，保留原宽高顺序。 |
| `initNv21ExportWithConfig(enableMix, config, callback)` | 启动带参数的共享帧输出。 |
| `enableMix=false` | 接收纯相机画面，不叠加眼镜屏幕内容。 |
| `CameraShareConfig.previewWidth / previewHeight` | 使用前面选中的尺寸；没有合适选项时均传 0，沿用 SDK 默认尺寸。 |
| `previewTargetFps=15` | 请求相机以 15 FPS 输出；实际识别采样由应用控制，目标约 3 FPS。 |
| `enableVideoStabilization=false`、`zoomLevel=1` | 本例关闭防抖并使用 1 倍变焦。 |

以下片段展示 NV21 回调中的数据交接方式，取自本工程；`session`、`frameLock`、`latest`、`preview` 为 Activity 持有的状态，完整生命周期处理见源码：

```kotlin
// onNv21Frame(nv21, width, height, timestamp) 内：
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

`nv21.copyOf()` 保留应用自己的数据，防止 SDK 复用回调缓冲区时影响异步预览或上传。相机回调只交接最新画面，JPEG 编码和模型请求在后台进行。`onCameraOpened(...)` 是打开通知；本例通过收到帧来判断画面是否正常，`onCameraClosed()`、`onError(...)` 则进入错误提示和重试流程。

### 3. 释放资源，避免离开页面后仍占用相机

- `onPause()` → `stopSession()`：递增会话编号、停止采样、取消模型请求，调用 `CameraShareHelper.releaseNv21Export()` 停止共享帧输出。
- `onDestroy()`：调用 `GlassSdk.release()` 释放已就绪的 SDK，取消协程并关闭网络客户端；如果绑定回调迟到，也在回调中执行释放。
- 恢复前台：重新注册客户端并开启取帧。后台仅保留有限的隐患摘要，画面队列与相似帧缓存会清空。

本例接收眼镜系统服务共享的 NV21，应用不直接调用 Android Camera，也不声明或申请 `CAMERA` 权限。该路径已在 RG_glasses 实机验证预览和识别。没有调用 ASR、TTS 或手机 SDK，也没有向眼镜 SDK 填写 AK/SK。

### 4. SDK 取帧后，业务逻辑在哪里实现

| 能力 | 实现位置 | 开发者可参考的用法 |
| --- | --- | --- |
| OpenGL 预览 | [Nv21PreviewView.kt](app/src/main/java/com/rokid/industry/hazardrecognition/Nv21PreviewView.kt) | `submit(frame)` 显示最新帧，`submit(null)` 清空预览。 |
| 画面去重和批量调度 | [FrameQueue.kt](app/src/main/java/com/rokid/industry/hazardrecognition/FrameQueue.kt) | `offer → begin → complete`；最多三帧一批、一个请求进行中。 |
| 视觉模型请求 | [InspectionClient.kt](app/src/main/java/com/rokid/industry/hazardrecognition/InspectionClient.kt)、[InspectionProtocol.kt](app/src/main/java/com/rokid/industry/hazardrecognition/InspectionProtocol.kt) | NV21 转 JPEG，再通过 OkHttp 调用兼容接口；模型 Key 仅用于该 HTTP 请求。 |
| 隐患去重 | [HazardLedger.kt](app/src/main/java/com/rokid/industry/hazardrecognition/HazardLedger.kt) | `known()` 提供摘要，`merge(...)` 根据场景和对象合并记录。 |
| 法规映射和结果展示 | [RegulationCatalog.kt](app/src/main/java/com/rokid/industry/hazardrecognition/RegulationCatalog.kt)、[HazardTableView.kt](app/src/main/java/com/rokid/industry/hazardrecognition/HazardTableView.kt) | 本地映射法条，`render(answer)` 替换表格，由 Activity 控制显示 10 秒。 |

上表属于 Demo 业务实现，可按开发者场景替换；它们不是眼镜 SDK 内置的识别、去重或大模型服务。

## 大模型接入与开发者配置

本地配置的 DeepSeek 接入仅用于演示“眼镜取帧 → 调用模型 → 展示结果”的流程，不提供面向开发者正式业务的大模型服务或账号密钥。正式项目的大模型接入与业务功能需要由开发者根据自身需求实现和维护，可使用开发者自己的视觉模型服务或网关。

Rokid SDK 负责获取 NV21 画面，大模型调用由 Demo 的 HTTP 业务代码完成，模型 Key 与 Rokid SDK 的 AK/SK 无关。下列配置用例用于帮助开发者理解和验证接入流程。

```text
眼镜 SDK 共享 NV21 → 本地筛帧 → JPEG / Base64
  → Wi-Fi / HTTPS → 开发者视觉模型接口（或开发者网关 → 内部视觉模型）
  → 结构化识别结果 → 本地法规映射 → 眼镜纵向表格
```

### 用例一：配置兼容接口，接入开发者自己的视觉模型

在当前工程目录复制 [通用配置模板](inspection.properties.example) 为 `inspection.properties`。如果本机已有该文件，直接编辑，避免覆盖现有配置。

```sh
cp inspection.properties.example inspection.properties
```

配置示意如下，地址和模型名均需替换为开发者实际值，Key 填在本机文件中：

```properties
# 完整的 HTTPS 请求地址，不是仅填写服务域名
endpoint=https://your-gateway.example.com/v1/chat/completions
# 填写该服务实际部署的视觉模型名称
model=your-vision-model
# 在本机填写开发者自己的 API Key 或网关访问令牌；示例故意留空
apiKey=
# 每秒本地采样目标，范围 1–3，不代表云端每秒完成的推理次数
sampleFps=3
```

| 配置项 | 开发者需要确认的内容 |
| --- | --- |
| `endpoint` | 可从眼镜 Wi-Fi 网络访问的完整 HTTPS Chat Completions 地址，包含实际请求路径；本例不会自动补 `/v1` 或 `/chat/completions`，也不跟随重定向。 |
| `model` | 实际可调用的视觉模型名称；必须能理解图片，普通文本模型无法完成此用例。 |
| `apiKey` | 通过 `Authorization: Bearer …` 发送的模型密钥或网关令牌。当前校验要求非空，即使是自建服务也需要配置；其他鉴权方式需在网关适配，或修改 `ModelConfig` 与 `InspectionClient`。 |
| `sampleFps` | 本地筛帧频率，默认 3。单批 1–3 张图，单个请求进行中不再并发上传。旧配置 `intervalSeconds` 不再生效。 |

配置在构建时写入 `BuildConfig`，修改后需要重新构建并安装 APK；仅编辑文件或重启已安装应用不会生效。未配置模型时可看预览，但不会上传画面。

### 用例二：使用 DeepSeek 示例

复制 [DeepSeek 配置模板](inspection.deepseek.properties.example) 为 `inspection.properties`，在本机填入自己的 Key；已有配置时直接编辑对应字段。

本例的 DeepSeek 配置使用 `https://api.deepseek.com/chat/completions` 和 `deepseek-v4-flash-vision-exp`，接口能力及账号可用性参见[官方视觉文档](https://api-docs.deepseek.com/guides/vision/)。代码仅在地址主机为 `api.deepseek.com` 时添加 `thinking.type=disabled` 和 `response_format.type=json_object`。使用开发者网关转发 DeepSeek 时，网关可自行补充这些参数；普通兼容接口不会收到这些专用字段。

### 大模型使用说明

开发者可自行配置支持图像输入的通用大模型，也可接入自己的大模型服务。本 Demo 中的 DeepSeek 接入仅用于举例，实际使用的模型、账号和接入方式由开发者自行选择和实现。

### 构建与接入验证

填写配置后，在当前目录构建安装：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.rokid.industry.hazardrecognition/.InspectionActivity
```

先用清晰的无隐患画面、已知隐患画面和模糊画面分别验证三种状态，再验证多图编号、重复提示与手动复检。出现 401/403 时核对令牌和权限，404 时核对完整路径及模型名；“模型结果不完整或格式不符”时检查模型返回内容是否符合 `InspectionProtocol.parse()` 的解析要求。不要把接口失败当作“没有隐患”。

## 代码与验证

### 按 Demo 用例阅读源码

源码关键位置使用中文“用例”注释，建议按以下顺序阅读：

| 用例 | 入口 | 用法与预期 |
| --- | --- | --- |
| 1. 启动眼镜相机 | `InspectionActivity.startSession/connectSdk/registerCameraClient` | 直接绑定眼镜服务，注册客户端，在 `onReady` 后接收共享 NV21 帧；应用不声明或申请相机权限；本例未传入 Rokid AK/SK，未调用 ASR/TTS。 |
| 2. 接收并显示 NV21 | `startCamera`、`Nv21Frame`、`Nv21PreviewView.submit` | 复制 SDK 回调缓冲区，保留最新帧；预览走 OpenGL，模型编码走后台线程。 |
| 3. 筛选待分析画面 | `FrameQueue.offer/begin/complete` | 主线程按约 3 FPS 筛帧，最多三帧一批；请求结束必须调用 `complete`，失败不写入已识别缓存。 |
| 4. 调用视觉模型 | `InspectionClient.inspect`、`InspectionProtocol.request/parse` | NV21 转 JPEG，再生成图文请求；严格校验返回状态和图片编号。更换服务通过本地配置完成。 |
| 5. 识别重复隐患 | `HazardLedger.known/merge` | 请求前提供摘要，返回后结合场景和对象合并；仅在内存维护去重记录。 |
| 6. 展示本轮结果 | `HazardTableView.render`、`InspectionActivity.expireResult` | `render(answer)` 替换整表，10 秒后 `render(null)` 恢复等待态；清除展示不会清除去重缓存。 |
| 7. 释放资源 | `stopSession/onDestroy`、`InspectionClient.close` | 退出前台停止相机、取消请求；销毁页面释放 SDK、协程和网络连接。 |

例如，`FrameQueueTest.duplicateIsCollectedOnlyAfterSuccessfulRecognition` 是可直接运行的去重用例：先提交一帧、开始请求，此时重复帧返回 `IN_PROGRESS`；成功完成并标记图片编号 `0` 有隐患后，再提交相似帧才返回 `KNOWN_HAZARD`。测试使用合成 NV21 数据，不依赖真实设备和模型密钥。

```sh
# 在 hazard-recognition 目录执行，单独运行上述 Demo 用例：
./gradlew :app:testDebugUnitTest --tests '*FrameQueueTest.duplicateIsCollectedOnlyAfterSuccessfulRecognition'
```
