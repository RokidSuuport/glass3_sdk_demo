# 隐患识别 Demo（九小场所消防）

独立眼镜端 APK，包名 `com.rokid.industry.hazardrecognition`，应用名“隐患识别”。眼镜通过 Wi-Fi 直接调用视觉大模型，无需手机 App。原有眼镜与手机 Demo 不受影响。

## 界面和操作

按 480×640 眼镜屏幕布局：左上角 128×72dp 实时预览，右侧显示运行/去重状态，下方 12sp 小字号纵向表格，左列依次显示“隐患内容 / 法规依据 / 整改建议”，右列显示最近一次识别的对应内容。移除底部 Wi-Fi 按钮及操作提示，长表格可滚动。

- 单击眼镜触摸板（Enter / DPAD Center）：手动复检，绕过画面缓存；分析中不再发起第二个请求。
- 左右滑动（DPAD Left / Right）：翻阅表格。
- 长按触摸板：暂停或恢复自动识别，已发出的请求完成后仍会更新记录。
- 返回键退出。进入后台释放相机、取消网络请求和待分析帧；最近结果的 10 秒有效期继续计时，内部去重缓存保留，重新启动进程后清空。
- Wi-Fi 连接在眼镜系统设置中配置，应用内不再提供入口。

表格仅显示最近一次成功识别结果，每次返回整表替换，不追加历史列表。一批最多 3 项隐患，在右列按编号对应；新结果为无隐患或无法判断时会清除上一轮隐患内容。每次成功返回后，结果和采样时间最多显示 10 秒；有新结果立即替换并重新计时，没有新结果则清空并显示等待识别。重复画面和请求失败不延长显示时间，回到前台也不会恢复已过期的结果。内部最多保留 30 条隐患摘要，仅用于判断重复，永不渲染为历史列表。

分析状态统一为“识别中”，不显示处理帧数。成功识别过的隐患画面重复时显示“该隐患重复，已收录”；无隐患画面重复时显示“画面重复，已识别”。尚在处理中或失败的画面不会显示“已收录”。

## 采样、批量分析与去重

相机优先请求 1280×720、15 FPS 的纯 NV21 回调，保持流畅小窗预览。`sampleFps=3` 时每 334ms 从最新 NV21 帧计算一个轻量 Y/V/U 缩略特征，目标约每秒 3 帧本地筛选，不再每隔 10 秒发起检查。

1. **画面去重**：比较当前帧与正在分析/等待分析/近期成功分析画面的颜色和亮度特征，跳过相似画面。
2. **合并请求**：一次携带最多 3 张不同的新画面；同一时间只有一个云端请求。等待队列最多保留最新 3 帧，发送前排除超过 2 秒的旧帧，避免慢网络积压。
3. **隐患去重**：让模型给同一位置、同一对象、同一消防问题返回一次，并参考已有记录 ID。眼镜端再次比较隐患类别、对象/位置、描述和画面特征，仅在条件匹配时更新内部缓存；界面只展示本轮结果。
4. **复核与失败重试**：稳定画面最多缓存 30 秒，`unknown` 仅缓存 3 秒，手动复检绕过缓存。失败不写入成功缓存，网络/API 错误按 2、4、8、16、30 秒退避，不以 3 次/秒反复冲击接口。

每秒 3 帧是本地采样目标，实际云端吞吐取决于网络、编码和推理时间，不保证每秒完成 3 张分析。去重是轻量近似匹配：大幅头动可能再次检查同一问题，小变化可能暂时被跳过；定时复核用于降低遗漏风险，无法保证完全消除重复或漏检。

DeepSeek [上下文缓存](https://api-docs.deepseek.com/guides/kv_cache/)复用输入计算，不等价于业务层的画面/隐患去重。这里的去重由眼镜端和结构化提示词共同实现。

### 客户接入建议：在服务端去重

**正式业务建议在客户服务端完成画面与隐患去重**，便于统一管理记录、支持多设备协作，并按业务需要调整去重策略。具体方案由客户结合自己的业务系统设计，本 Demo 不提供服务端去重实现。

当前 Demo 为便于独立演示，在眼镜端提供轻量去重，可减少重复上传和模型请求；其缓存仅保存在内存中，进程重启后清空，无法跨设备共享，效果也受头动、光照和相似场景影响。

## 九小场所消防范围与法规依据

重点观察有直接可见证据的疏散通道/安全出口堵塞、消火栓遮挡、灭火器明显异常、用电异常、可燃物靠近热源、危险充电及烟火。不凭桌面凌乱判定通道堵塞，不把完整绝缘电线说成裸露导体，也不因看不到设备就认定缺失。

模型只输出观察、类别、对象、建议及帧编号，**不生成法条**。表格法规列由本地受控映射提供：

已按[北京市政府公开的《中华人民共和国消防法》（2021 修正）](https://www.beijing.gov.cn/zhengce/zhengcefagui/qtwj/202307/t20230726_3207767.html)核对下列映射（2026-09-09）。眼镜端展示条款位置和简短要求，摘要不是法规原文。

| 观察类别 | 参考条款 | 展示要求与适用范围 |
| --- | --- | --- |
| 疏散通道/安全出口堵塞 | 第28条 | 不得堵塞疏散通道、安全出口 |
| 消火栓遮挡 | 第28条 | 不得埋压、圈占、遮挡消火栓 |
| 灭火器明显异常 | 第16条第1款第2项 | 单位应维护消防器材，确保完好有效 |
| 用电异常、危险充电 | 第27条第2款 | 用电及线路须符合消防技术标准；具体是否违规需核对相应标准 |
| 可燃物相关隐患 | 第16条第1款第5项 | 标为“通用职责”：单位应检查并及时消除火灾隐患，不把普通可燃物当作易燃易爆危险品 |
| 烟火 | 第44条第1款 | 标为“处置要求”：发现火灾应立即报警，不凭烟火直接认定违规用火 |
| 其他或未匹配类别 | 待核实 | 暂未匹配具体条款，不生成虚构依据 |

所列依据仅供现场复核，不作违法认定。“九小场所”具体分类及地方要求未在此 Demo 中固定，后续可按地区扩展本地法规映射。

## SDK 接入用例

本工程使用 `com.rokid.security:glass3.open.sdk` 的服务连接、客户端注册和相机共享功能。全部调用集中在 [InspectionActivity.kt](app/src/main/java/com/rokid/industry/hazardrecognition/InspectionActivity.kt)，客户可按下列顺序阅读和复用。

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

`isReady()` 已为 `true` 时，直接进入客户端注册。`onServiceConnected()` 表示服务连接完成，`onReady()` 才是本例开始调用相机功能的位置。`HazardRecognition` 是业务客户端名称，不是密钥。SDK 回调通过主线程更新页面，并通过会话编号忽略退出页面后才到达的旧回调。

### 2. 查询尺寸，启动共享 NV21 输出

| SDK 方法 / 参数 | 本例取值与用途 |
| --- | --- |
| `CameraShareHelper()` | 创建相机共享辅助对象，由 Activity 持有，退出时用于释放同一会话。 |
| `getSupportedPreviewSizes()` | 查询设备支持尺寸；本例从纯相机选项中选择宽高为偶数、不超过 1280×720 的最大尺寸。 |
| `initNv21ExportWithConfig(enableMix, config, callback)` | 启动带参数的共享帧输出。 |
| `enableMix=false` | 接收纯相机画面，不叠加眼镜屏幕内容。 |
| `CameraShareConfig.previewWidth / previewHeight` | 使用前面查询选中的尺寸。 |
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
- 恢复前台：重新注册客户端并开启取帧。后台期间保留的是有限的业务去重数据，不是相机流。

本例接收眼镜系统服务共享的 NV21，应用不直接调用 Android Camera，也不声明或申请 `CAMERA` 权限。该路径已在 RG_glasses 实机验证预览和识别。没有调用 ASR、TTS 或手机 SDK，也没有向眼镜 SDK 填写 AK/SK。

### 4. SDK 取帧后，业务逻辑在哪里实现

| 能力 | 实现位置 | 客户可参考的用法 |
| --- | --- | --- |
| OpenGL 预览 | [Nv21PreviewView.kt](app/src/main/java/com/rokid/industry/hazardrecognition/Nv21PreviewView.kt) | `submit(frame)` 显示最新帧，`submit(null)` 清空预览。 |
| 画面去重和批量调度 | [FrameQueue.kt](app/src/main/java/com/rokid/industry/hazardrecognition/FrameQueue.kt) | `offer → begin → complete`；最多三帧一批、一个请求进行中。 |
| 视觉模型请求 | [InspectionClient.kt](app/src/main/java/com/rokid/industry/hazardrecognition/InspectionClient.kt)、[InspectionProtocol.kt](app/src/main/java/com/rokid/industry/hazardrecognition/InspectionProtocol.kt) | NV21 转 JPEG，再通过 OkHttp 调用兼容接口；模型 Key 仅用于该 HTTP 请求。 |
| 隐患去重 | [HazardLedger.kt](app/src/main/java/com/rokid/industry/hazardrecognition/HazardLedger.kt) | `known()` 提供摘要，`merge(...)` 根据场景和对象合并记录。 |
| 法规映射和结果展示 | [RegulationCatalog.kt](app/src/main/java/com/rokid/industry/hazardrecognition/RegulationCatalog.kt)、[HazardTableView.kt](app/src/main/java/com/rokid/industry/hazardrecognition/HazardTableView.kt) | 本地映射法条，`render(answer)` 替换表格，由 Activity 控制显示 10 秒。 |

上表属于 Demo 业务实现，可按客户场景替换；它们不是眼镜 SDK 内置的识别、去重或大模型服务。

## 大模型接入与客户配置

本地配置的 DeepSeek 接入仅用于演示“眼镜取帧 → 调用模型 → 展示结果”的流程，不提供面向客户正式业务的大模型服务或账号密钥。正式项目的大模型接入与业务功能需要由客户根据自身需求实现和维护，可使用客户自己的视觉模型服务或网关。

Rokid SDK 负责获取 NV21 画面，大模型调用由 Demo 的 HTTP 业务代码完成，模型 Key 与 Rokid SDK 的 AK/SK 无关。下列配置用例用于帮助客户理解和验证接入流程。

```text
眼镜 SDK 共享 NV21 → 本地筛帧 → JPEG / Base64
  → Wi-Fi / HTTPS → 客户视觉模型接口（或客户网关 → 内部视觉模型）
  → 结构化识别结果 → 本地法规映射 → 眼镜纵向表格
```

### 用例一：配置兼容接口，接入客户自己的视觉模型

在当前工程目录复制 [通用配置模板](inspection.properties.example) 为 `inspection.properties`。如果本机已有该文件，直接编辑，避免覆盖现有配置。

```sh
cp inspection.properties.example inspection.properties
```

配置示意如下，地址和模型名均需替换为客户实际值，Key 填在本机文件中：

```properties
# 完整的 HTTPS 请求地址，不是仅填写服务域名
endpoint=https://your-gateway.example.com/v1/chat/completions
# 填写该服务实际部署的视觉模型名称
model=your-vision-model
# 在本机填写客户自己的 API Key 或网关访问令牌；示例故意留空
apiKey=
# 每秒本地采样目标，范围 1–3，不代表云端每秒完成的推理次数
sampleFps=3
```

| 配置项 | 客户需要确认的内容 |
| --- | --- |
| `endpoint` | 可从眼镜 Wi-Fi 网络访问的完整 HTTPS Chat Completions 地址，包含实际请求路径；本例不会自动补 `/v1` 或 `/chat/completions`，也不跟随重定向。 |
| `model` | 实际可调用的视觉模型名称；必须能理解图片，普通文本模型无法完成此用例。 |
| `apiKey` | 通过 `Authorization: Bearer …` 发送的模型密钥或网关令牌。当前校验要求非空，即使是自建服务也需要配置；其他鉴权方式需在网关适配，或修改 `ModelConfig` 与 `InspectionClient`。 |
| `sampleFps` | 本地筛帧频率，默认 3。单批 1–3 张图，单个请求进行中不再并发上传。旧配置 `intervalSeconds` 不再生效。 |

配置在构建时写入 `BuildConfig`，修改后需要重新构建并安装 APK；仅编辑文件或重启已安装应用不会生效。未配置模型时可看预览，但不会上传画面。

### 用例二：使用 DeepSeek 示例

复制 [DeepSeek 配置模板](inspection.deepseek.properties.example) 为 `inspection.properties`，在本机填入自己的 Key；已有配置时直接编辑对应字段。

本例的 DeepSeek 配置使用 `https://api.deepseek.com/chat/completions` 和 `deepseek-v4-flash-vision-exp`，接口能力及账号可用性参见[官方视觉文档](https://api-docs.deepseek.com/guides/vision/)。代码仅在地址主机为 `api.deepseek.com` 时添加 `thinking.type=disabled` 和 `response_format.type=json_object`。使用客户网关转发 DeepSeek 时，网关可自行补充这些参数；普通兼容接口不会收到这些专用字段。

### 密钥与交付方式

仓库中的两份 `.properties.example` 均只保留中文配置说明和空的 `apiKey=`，不提供个人账号密钥。真实 Key 仅填写到本机的 `inspection.properties`，该文件已加入 `.gitignore`；提交代码时保留空模板，不要强制加入本地配置。

当前 Demo 会把本机配置写入 APK，因此使用真实 Key 构建的 APK 仅用于私有演示，不作为客户通用安装包。交付源码由客户填写自己的配置；分发应用时建议走客户网关，将上游模型密钥保存在服务端，并扩展客户端登录和短期令牌获取流程。本例尚未实现动态令牌获取，仅替换网关地址不会自动具备该能力。客户端不记录请求正文或主动保存图片，图像及本地去重记录只在内存中维护；服务端是否存储由客户自己的实现决定。

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

### 文件索引与验证说明

- `InspectionActivity.kt`：相机生命周期、约 3 FPS 调度、错误退避与手势。
- `FrameQueue.kt`：NV21 特征、相似画面缓存及有界批量队列。
- `HazardLedger.kt`：同场景/对象隐患合并。
- `RegulationCatalog.kt`：按类别提供已核对的法规参考和简短要求。
- `HazardTableView.kt`：最新结果的纵向字段/内容表格。
- `InspectionClient.kt` / `InspectionProtocol.kt`：多图 HTTPS 请求和严格结果解析。
- `Nv21PreviewView.kt`：OpenGL 预览。

22 项单元测试全部通过，覆盖多图序列/帧编号、拒绝/截断/矛盾结果、DeepSeek 参数、非法法规输入、近似画面过滤、队列上限、过期帧、缓存期限、手动复检、错误后重试、隐患合并以及不同位置不误合并。上一版已通过 RG_glasses 真机验证相机、DeepSeek 返回及画面去重；本版新增了已识别/识别中重复画面状态区分的单元测试。实际去重效果仍需用真实九小场所、多角度和多个相似对象进一步验收。

沿用原工程 AGP 8.4.2 / Kotlin 2.2.0，旧版 Lint 会报告 Kotlin 元数据兼容性问题，Kotlin 静态分析并不完整；报告还包含 SDK 自带工具类警告。`app/lint.xml` 仅忽略本应用未调用的第三方通知工具类权限提示。
