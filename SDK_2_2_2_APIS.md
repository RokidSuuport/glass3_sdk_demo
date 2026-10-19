# SDK 2.2.2 新接口 Demo

本说明对应 2026-10-12 核对的 SDK 源码与本机实际解析的发布物。新增能力直接整合进原有页面，不设置独立的“SDK 新接口”入口。

完整方法签名、参数单位、返回值、线程、回调、错误码和代码示例见 [方法与回调接入参考](SDK_2_2_2_API_REFERENCE.md)。该参考按指定提交 `c2b9677` 核对，并明确区分已接入 Demo 的功能与仅提供文档示例的方法。

工具与长任务暂不向第三方开放，不包含在本 Demo。

| 原有入口 | 整合的能力 |
| --- | --- |
| 消息发送 → 在线 / 离线 TTS | 新会话接口、ADD/FLUSH、取消、状态 |
| SDK 拍照录像 → 共享相机 | Surface 契约/水印、NV21 Options ByteBuffer/byte[] 与 Config V1 ByteBuffer、I420 租约模式 |
| 首页 → SDK 拍照录像 | 配置拍照、Options 拍照录像、小数倍率、照片复用分辨率与录像水印 |
| 手机端 → 视频接收 / 发送消息 | 远端能力支持下的小数倍率、文件字节进度 |

## 版本与升级文档的差异

用户提供的升级文档更新于 2026-09-10，对应 Open SDK `2.2.2-E-20260909.072238-3`。开始核对时缓存为 `20261009.040617-10`；构建时 SNAPSHOT 元数据已经更新，最终采用 **`2.2.2-E-20261012.062012-11`**。SNAPSHOT 会变化；复现时需要核对实际解析的 AAR 和配套系统服务。

原 SDK 工作区 HEAD 仍为 `11a8234336eed0dfc938134d87def1fd512aa058`，没有修改。初次集成按**发布物 sources.jar + AAR 字节码**核对；本次补文档又在独立临时检出中核对了指定提交 `c2b9677b4e2096e0b121946cc057f5b411d0fb63`，包括 Surface 契约接口。实时远端 `develop/industry` 已为 `b5711b925bd820f9966651c140cc67117370cee6`；其后续变更属于 OTA，本次列出的接口没有变化。

最终 Open AAR SHA-256：`4c05de7f825082dce0bee9f86792eaaec66c4eb20d1c627fb2082ee491b0e4d1`；对应 base-data `2.2.2-E-20261012.061917-11`、base-common `2.2.2-E-20261012.061800-22`。

当前版本与 9 月文档有以下差异：

- `CameraMediaOptions.takePhoto` 和底层 `takePhotoWithOptions` 多了必填的 `reuseOpenedResolution: Boolean`。Demo 使用当前五参数 Helper 签名。该 AIDL 变化仍使用原方法编号，不能仅凭两端都写着 `2.2.2-E-SNAPSHOT` 判断兼容。
- `CameraShareConfigV2.previewWidth/previewHeight` 按**期望最终输出尺寸**填写，例如横向 1080P 为 `1920×1080`。相机支持列表和源尺寸回调可能使用相机源坐标，不能直接混用；实际消费 NV21/I420 以每帧宽高为准。
- 最新 Phone API 包含新增文件传输字节进度回调、`ExtConfig.configuredReportEnabledOrNull()` 和倍率扩展，不能用“Phone SDK 本次没有新增业务方法”概括本次核对的源码。新增回调的三参→单参兼容方向、字节字段缺省和配置 null 语义见接入参考。
- 10 月 12 日包另增 `queryCameraSurfaceContract` / `startCameraShareWithContract` 和 `CameraSurfaceContract`，可以在创建 Surface 前协商精确 buffer 尺寸。旧服务的安全探测返回 0，不能把未知事务当成有效契约。

本次不修改 Maven 版本与仓库设置，不强制覆盖 POM 的传递依赖，不修改 SDK 源码。

## 新增 API 与眼镜端入口

`IMediaServer` 在 9 月文档及 10 月 9 日包中为 33 个方法；10 月 12 日包为 **35 个，相对 2.2.0-E 新增 14 个**，下表列出 Demo 接入位置；已移除的临时操作入口注明保留的接口说明：

| 新增方法 | Demo 入口 / 对应封装 |
| --- | --- |
| `takePhotoWithConfig` | SDK 保留 V1 配置拍照能力；本 Demo 拍照录像页不再单设 V1 模式，需验证中间版本服务时可直接调用 |
| `takePhotoWithOptions` | SDK 拍照录像 → Options 模式；`CameraMediaOptions.takePhoto` |
| `startCameraI420ExportWithConfig` | 相机共享 → I420 模式；整数配置 |
| `startCameraI420ExportWithOptions` | 相机共享 → I420 模式；小数倍率配置 |
| `stopCameraI420Export` | 停止导出或退出页；`releaseI420Export` |
| `releaseCameraI420Frame` | 每帧 `finally { frame.close() }`，由租约归还槽位 |
| `startCameraNv21ExportWithOptions` | 相机共享 → NV21 模式；`initNv21BufferExportWithOptions` / `initNv21ExportWithOptions` |
| `startCameraShareWithOptions` | 相机共享 → Surface 预览 |
| `getMediaCapabilities` | SDK 拍照录像 / 相机共享的能力查询与启动检查 |
| `getCameraZoomState` | SDK 拍照录像 / 相机共享的实际状态查询 |
| `startRecordWithOptions` | SDK 拍照录像 → Options 模式；`CameraMediaOptions.startRecord` |
| `updateCameraShareWatermarkText` | Demo 仅保留水印开关；自定义及更新已有文字的接口说明保留在水印介绍与接入参考中 |
| `queryCameraSurfaceContract` | Surface 启动前通过 `SurfaceShareContractProbe.read` 安全探测精确缓冲尺寸 |
| `startCameraShareWithContract` | Surface 页 → 有有效契约时携带同一契约启动；旧服务保持兼容路径 |

相机共享页面提供输出尺寸、帧率、小数倍率及会话状态。SDK 拍照录像分别提供“拍照设置”和“录像设置”，包含各自的分辨率、倍率及专用选项；主页面摘要只显示尺寸和倍率，相机模式在设置内查看和切换。镜腿前后滑动按画面从左到右、从上到下选择可见按钮，单击执行当前高亮项；切换模式后隐藏项不参与导航。分辨率和倍率使用预设选项，已移除自定义数字输入及对应编辑代码；匹配当前值的预设优先选中，“取消”也可滑动选中。触摸与镜腿导航共用当前选中状态。

倍率确认按钮用两行短文说明“设置并确认倍率／后续处理需实际倍率时”，不再单列说明或在按钮上展示超时时长；协程调用仍保留有界超时并处理 null。NV21 的“查询能力和实际状态”打开独立、可滚动的结果页，结果不会被配置面板隐藏或被实时帧率覆盖。V2 查询当前输出会话；V1 只展示设备能力及实际帧信息，并注明没有本输出的倍率确认，不以全局倍率冒充当前输出的实际倍率。

I420 设置页将分辨率/帧率、V1/V2 接口/帧处理方式、倍率/画面与倍率、开始/状态配成两列小按钮，常用设置优先一屏显示。接口选择与同步/异步处理是正式使用选项，默认可见；可选的画面开关和会话倍率控制收在“画面与倍率”中。同步模式在帧回调内复制预览数据；异步模式将租约交给有界工作队列，工作线程完成真实预览数据复制后归还。GL 线程渲染自有副本，不再访问已归还的租约；没有人为 150ms 延迟或为制造丢帧而等待。队满、异常和停止时的归还行为由测试代码验证。倍率接口按实际场景使用：`setZoom` 用于实时提交倍率，`setZoomAndAwait` 用于后续处理依赖已确认实际倍率的协程流程，`followShared` 撤销本输出的独立倍率意图；确认超时须处理 null，不能用请求值代替实际值。关闭画面显示时，导出页每秒刷新实际接收帧率、累计帧数、归还/待归还/丢帧/归还失败计数，以及 Y/U/V 步长、原始帧时间戳（纳秒）和最近收帧间隔。只保存帧元数据，不持有租约；没有新帧时帧率降为零、收帧间隔继续增加。重新导出后重新计数。停止导出位于导出画面，停止后返回设置并保留本轮计数。“查看导出状态”统一展示设备能力、当前输出状态与帧归还统计：能力查询和租约统计用途不同，但不再拆成容易混淆的两个入口。同步和异步模式都统计实际收到的帧及 `close()` 正常返回次数；关闭失败单列，不能算作已归还。这是 Demo 本地释放调用统计，不是服务端逐帧确认。导出中有在途帧正常，停止并处理完在途帧后待归还应为零。

NV21 与 Surface 配置区都提供“倍率”参数（带 × 单位），V2 模式在旁边显示 SDK 已确认的“实际倍率”；尚无有效状态时显示“待确认”，NV21 V1 则明确提示无会话倍率确认。选择倍率后使用原来的应用配置/应用并预览。NV21 V2 保留“仅应用倍率（不重启）”；Surface 预览页使用“切换倍率”，每次点击提交下一档倍率，不停止或重启该输出。实际倍率不从所选值推算，仅在 `ready=true` 且 `appliedZoom` 有效时显示。

倍率接口的实际用法：启动 V2 输出时通过 `CameraShareConfigV2.zoom = CameraZoomConfig.explicit(ratio)` 提交初始倍率；运行中通常使用 `session.setZoom(ratio)`，返回 0 只代表接受请求，显示值等 `listener.onStateChanged` 的 `ready=true` 和 `appliedZoom`。需要在协程中等待确认再继续处理时，直接使用 `session.setZoomAndAwait(ratio, timeoutMs)`，它内部会调用 `setZoom`，不是对上一个请求的只读查询，不需先点设置再点确认。Demo 的 2000ms 是等待上限，提前就绪提前返回；实际倍率可能被设备能力或共享相机策略改变。返回 null 代表没有确认结果，超时不会撤销已接受的请求。获取已确认倍率后，消费缓存帧仍需核对配置代次，不能默认旧缓存已更新。只查询用 `refreshState()` 或本地 `state`；`followShared()` 撤销本输出的显式倍率，不保证回到 1×。

Surface 进入页面即自动按默认参数（1280×720、24 FPS、1×、防抖关闭、水印关闭）开始预览，参数收在预览页的“设置”按钮中。设置页采用两列小按钮，取消重复的完整状态及长段介绍，保留分辨率、帧率、倍率/实际值、防抖、水印、能力与状态及应用/返回；水印仅保留开关，使用固定示例文字与时间戳，在“应用并预览”后生效。介绍说明 SDK 支持通过 `TextWatermarkItem.text` 自定义文字，以及用原输出 callback Binder 调用 `updateCameraShareWatermarkText` 修改已有项；Demo 不提供文字编辑器或独立更新文字按钮。预览页保留设置、停止和切换倍率；跟随共享与设置并确认倍率收在设置页“倍率控制”，能力与状态统一为设置页单一入口；未开始或已停止时不显示运行操作。跟随共享会撤销本页显式倍率，不保证回到 1×。查询立即打开独立可滚动结果面板，显示本页预览是否开启、查询时机、支持的能力、实际倍率、帧率、防抖和输出尺寸，不被实时状态覆盖；镜腿前后滑动查看，单击返回。进入后台会释放相机，回到前台时自动使用最近已应用的参数恢复预览（保留实时倍率/跟随共享意图，不应用尚未提交的参数修改）。主动停止后返回配置并保持停止，重新预览需手动应用；启动失败会显示原因并返回配置，不自动循环重试。

Surface 预览控件本身按实际画面宽高比做 FIT_CENTER 测量并居中，不再先铺满剩余区域后用 `TextureView.setTransform` 非等比缩放补偿。未协商契约时，直出与水印两条路径均按实际回调更新显示尺寸；直出仅更新显示比例，不在回调中修改已经提交给相机的缓冲尺寸。已协商的缓冲契约仍优先。状态区高度变化、查询面板及按钮布局变化不能改变画面内容比例。

**旧 Surface 对照入口（默认隐藏）：**菜单隐藏“Surface 原始页面（旧接口兼容）”，镜腿导航跳过该项；按钮、`surface_legacy` 路由和页面实现仍保留，需要恢复入口时将按钮设为可见即可。“Surface 旧接口兼容”恢复远端 `origin/main`（`d6462a2`）的原始 `SurfaceShareFragment`，仅改类名为 `LegacySurfaceShareFragment` 以与新页面并存。沿用 `initSurfaceWithConfig(1280×720, 15 FPS, 1×)`、原 GL 渲染器、回调宽高换算、FIT_CENTER 顶点缩放和原有定时配置切换；旧入口不增加 Options、倍率会话或能力查询。新 Surface 功能保留独立的 AIDL 预览入口；已移除重复的“Surface Helper / GL / 新接口”页面。

此前 Demo 把额外新增的无配置 `initSurface(callback)` 分支也称为“原始接口”，但远端原页面实际传入显式 `CameraShareConfig`，二者启动参数不同。该默认参数分支不再放入远端旧页面的兼容入口。使用同一个 `glass3.open.sdk:2.2.2-E-20261012.062012-11`、同台眼镜和同一 1280×720 配置进行实际画面对照：远端 GL 页面与当前显式 Config 页面比例一致。撤回此前根据默认参数分支推导的“远端旧 Surface 存在 SDK 拉伸缺陷”结论；本次只调整 Demo，未修改 SDK。收到帧、倍率 ready 或外框比例正确均不能单独证明内容没有拉伸，验收仍需检查真实渲染画面。

NV21 预览仅显示请求参数、实际帧尺寸、会话倍率结果以及实测接收/预览帧率、预览丢帧数；已移除逐帧倍率限制说明和 `Actual FPS=unknown; EIS=unknown` 占位行。接收帧率按实际回调计数，预览帧率按实际渲染计数；二者不等于请求目标或服务上报的配置帧率。SDK 上报的帧率、防抖值仍在状态查询中展示，未收到对应回调时标为“未上报”，不以请求值冒充实际值，也不单凭未上报判断 SDK 故障。

NV21 设置页缩小按钮与间距，数据接口、分辨率、帧率、倍率和防抖默认显示；接口说明、实时倍率控制和能力查询收在“倍率与状态”，折叠时镜腿导航跳过隐藏项。设置区保留“数据接口”选择，三种方式共用原来的应用配置、预览、帧统计与停止流程，切换后需要应用配置重新启动：

| 数据接口 | SDK 调用 | 适用场景与验证边界 |
| --- | --- | --- |
| `OPTIONS_BUFFER`（默认） | `initNv21BufferExportWithOptions(false, configV2, ConfiguredNv21BufferCallback)` | 新开发优先使用；只读借用 `ByteBuffer`，回调带配置代次，可结合会话状态确认帧属于当前倍率。 |
| `OPTIONS_ARRAY` | `initNv21ExportWithOptions(false, configV2, Nv21Callback)` | 适配只接收 `byte[]` / Kotlin `ByteArray` 的已有算法；支持小数倍率和 V2 会话，但回调没有配置代次，不能把最新倍率状态直接标到每帧上。 |
| `CONFIG_BUFFER` | `initNv21BufferExportWithConfig(false, configV1, Nv21BufferCallback)` | 保留 `CameraShareConfig.zoomLevel` 的整数倍率，同时借用只读 `ByteBuffer`；本路径不创建 V2 倍率会话。 |

Config V1 模式只提供整数倍率，禁用“仅应用倍率”和“跟随共享”；修改倍率后通过应用配置重启。能力/全局状态查询仍可用于诊断，但不能当作本 V1 输出的会话或帧证据。两种新增入口的完整 Kotlin 接入示例、回调所有权和停止清理见 [NV21 接入参考](SDK_2_2_2_API_REFERENCE.md#nv21-exports)。

当前 SDK 的 `byte[]` 路径是**每帧复制到两个复用数组**，在首次使用或尺寸变化时分配，不是每帧都新建大数组。调用方不得持有该数组跨后续回调；ByteBuffer 及其 duplicate/slice 也只能在当前回调内读取。异步算法/渲染需要在回调内复制到应用自己的有界复用缓冲。新开发优先 ByteBuffer，可省掉 SDK 的整帧数组复制，并避免业务端再逐帧分配数组。

SDK 拍照录像默认选择 `OPTIONS_V2`，主页面提供拍照、录像、停止录像、共享相机，以及独立的拍照设置和录像设置。分辨率不再各占一个开始按钮；设置选项并排为两列，分别保存拍照和录像参数。眼镜端分辨率与倍率只提供预设选择，不再提供自定义数字输入；JPEG 质量使用按钮切换，复用拍照分辨率和录像水印使用开关，录像保留应用倍率和跟随共享倍率。设备实际输出以 SDK 回调为准。

进入设置不会退出宿主页面或停止正在进行的录像。参数自动保存，用于下次操作；录像中修改倍率后点击“应用录像倍率”。默认参数为 V2、拍照/录像 1280×720、1×、JPEG 90、复用关闭、水印关闭。旧模式可在设置中明确切换，拍照保留原始 `takePhoto(resolutionType)` 预设尺寸，整数倍率通过旧 `zoomCamera` 接口提交。切到旧模式时倍率取整；升级前保存的非预设拍照尺寸仍兼容回退到 1280×720。

发送视频/停止发送、采集音频/停止采集位于消息发送页；AI 对话/停止对话位于首页“设备工具 / Device tools”入口；共享相机入口位于 SDK 拍照录像页。各页仍调用原有服务接口，离页只清理本页启动的任务。

默认使用 Legacy；`CameraMediaCalls.kt` 分开给出原拍照、V2 拍照、原录像、V2 录像四个真实 SDK 调用。V2 拍照通过 `CameraShareConfigV2` 提交期望输出尺寸和倍率，并通过本次请求的 `ICameraPhotoCallback` 接收成功或错误；V2 录像沿用原 `RecordConfig`，新增 `CameraZoomConfig`、可选 `TextWatermarkConfig` 和独占 `CameraZoomSession`。`CameraMediaOptions` 先检查服务能力；缺少能力会明确失败，不自动回退或重复发起旧接口。拍照结果尚未返回或录像尚未结束时，暂不允许切换 API 模式。媒体操作自动记录能力、实际倍率及具体 SDK 方法，不再提供独立查询按钮；能力或状态查询不受旧服务支持时只记录诊断信息，不阻断原接口调用。

`CONFIG_V1`（`takePhotoWithConfig(CameraShareConfig, path, quality)`）适用于只有 V1 配置拍照、还没有 Options 能力的中间版本服务；它没有对应的 V1 录像入口，也没有小数倍率或本次拍照专属结果回调。当前 Demo 目标是原接口兼容与最新 V2 接入，因此不再把 CONFIG_V1 当作第三种成套拍照录像模式。V2 不能兼容旧服务缺失的 AIDL 事务；在旧服务上应明确选择 Legacy，而不是用 V2 参数模拟旧方法。

需要兼容这类中间版本时，可直接复制 Demo 中的 `CameraMediaCalls.takeConfigV1Photo`，并提前注册原 `PhotoFileCallback`：

```kotlin
val config = CameraShareConfig(
    previewWidth = 1920,
    previewHeight = 1080,
    previewTargetFps = 24,
    enableVideoStabilization = false,
    zoomLevel = 1,
)
CameraMediaCalls.takeConfigV1Photo(config, jpegPath, 90)
// 完成结果仍由已注册的 PhotoFileCallback 返回；录像继续调用原 startRecord。
```

- 小数倍率使用 `CameraZoomConfig.explicit`、`CameraZoomSession.setZoom/followShared/refreshState` 和状态监听。`setZoom` 返回 0 只表示请求被接受；实际倍率看 `ready` 和 `appliedZoom`。`followShared` 撤回本输出的显式意图，不保证全局恢复 1×。`setZoomAndAwait` 是同一会话的可选协程便利方法，没有另设重复按钮。
- NV21 三种入口都在回调返回前完成尺寸校验及预览所需复制，不保留 SDK 缓冲引用。默认 Options ByteBuffer 只将 `ready=true`、合法缓冲尺寸且配置代次匹配的帧计为已确认帧；Options byte[] 和 Config V1 ByteBuffer 只统计接收帧与实际尺寸，不声称这些帧已达到最新倍率。沿用原预览与帧统计，不逐帧记录像素数据。
- I420 使用 `I420FrameLease`，包括被忽略的迟到帧也在 `finally` 归还。I420 缓冲池 generation 与 NV21 配置 generation 含义不同，不能混作就绪证据。示例没有实现异步 WebRTC 消费。
- NV21 保留原实时预览，I420 模式展示真实租约帧统计。Surface 先协商契约，成功后在创建 Surface 前设置精确 buffer 尺寸，后续不会再凭源尺寸回调交换宽高。非零但无法解码的契约明确报错。旧服务走明确的兼容路径；不能把水印 GL 输出规则套用到无水印 Camera2 直通路径。不能仅凭 FIT_CENTER 认定所有固件的拉伸问题已通过实机验证。
- Surface 手动持有原 callback 管理启动/停止，使用 `SurfaceTexture.timestamp` 报告已消费帧，帮助会话确认配置。未用系统时钟或毫秒值代替相机纳秒时间戳。
- 防抖（EIS）是 SDK 已有能力，保留原相机共享页面的配置，不列为本次新增能力。Surface 配置中显示“电子防抖（EIS）：开启／关闭”，默认关闭；用于减轻移动时的画面抖动，修改后需点击“应用并预览”。它对应 `enableVideoStabilization` 请求，可能裁剪画面并增加性能开销；按钮表示所选请求值，不能据此认定设备已启用防抖，实际以设备状态为准。
- 图片和录像沿用原页面的公共 Pictures 路径，录像目录为 `Pictures/video`。结果由回调确认，第三方应用不因收到路径而自动取得文件读取权限。原录像按钮保留 `1280×720`、`1920×1080`、`1080×1920` 三档、录音开启和每分钟分段配置；720P 保留 AR 混合配置。Options 模式在这些配置上增加倍率与水印。
- 页面退出停止本页输出、关闭本页录像会话并移除本页拍照监听。V2 拍照是一次性请求，退出丢弃迟到结果，无法按长期会话方式取消。

### 拍照与录像设置

SDK 拍照录像页分为拍照区和录像区，各自显示当前参数，并提供独立的设置入口。两页分别保存接口模式（默认 V2）、分辨率和倍率；拍照页另有 JPEG 质量及复用尺寸，录像页另有水印开关、应用当前录像倍率及跟随共享倍率。恢复默认仅重置所在页。升级时原共用参数自动作为两边的初始值。

设置保存在 Demo，拍照时使用拍照参数，开始录像时使用录像参数；录像中更新倍率需点击应用。旧接口的 zoomCamera 仍控制共享相机，不能把界面的独立配置理解成 SDK 提供独立旧版变焦会话。设置页留在原 Activity 内，打开设置不会因离开页面而停止当前录像。

### TTS 会话

消息发送页的按钮区和日志区各占半屏，分别滚动；消息发送页与设备工具页均已删除清空日志按钮及事件处理。TTS/ASR 保留在消息页，AI 对话、乐奇拦截器、二维码、Wi-Fi 和应用显示配置统一位于首页“设备工具 / Device tools”。

消息发送页中，TTS 播报模式与在线 ASR 并排在第一行，各占半行；第二行为在线播报/停止在线，第三行为离线播报/停止离线，均在同一语音框内。模式使用简短中英文标签，长状态自然换行，同一行等高。镜腿滑动顺序与布局一致，播报按钮后紧接对应停止按钮。模式说明不占用按钮区，点击模式、播报或停止按钮时，当前模式与说明自动显示在下方日志。模式只影响新提交的 TTS，不控制 ASR。

- 每次点击播报创建本页会话并保存真实 session ID，ADD 模式可连续提交多次。停止按钮仅取消点击时本页对应引擎的播放和排队请求；不因播放状态改变按钮文字或用途。
- QUEUED / STARTED / COMPLETED / CANCELED / ERROR 及 session ID 自动显示在下方日志。取消返回 true 只说明请求被接受，仍以终态回调或查询确认；UNKNOWN 不覆盖已收到的终态。
- Demo 使用进程内共享的会话桥统一管理远端监听，同一引擎的同一服务只使用一套监听，多页面按自己的请求归属接收事件。新服务需重新注册监听再提交播报；查询与取消固定在创建会话的服务上。
- 为保证“停止播报”定向作用于真实会话，页面确认会话 API v2 后，固定本次 SDK service 执行新 `speak` / `cancel` / 状态查询。Manager 的在线兼容回退可能生成本地正数 ID、离线回退可能返回 -1；这两种回退都不能保证本按钮的按会话停止，因此页面不隐式回退。服务不支持或不可用会直接显示原因。

默认使用 ADD：新播报加入相应引擎队列。FLUSH：下一次新播报替换同引擎的活动播报及待播队列，可能影响其他应用。在线和离线分别排队；切换模式本身不停止当前播报，停止按钮的取消范围不随模式改变。临时离页时取消本页会话，页面销毁时解除本页的事件接收。共享远端监听随进程管理，避免离线接口按 PID 注销时误清其他页面的监听。不调用共享 Manager 的 `release()`、`cancelAll()` 或 `cancelCurrent()`。这些应用级方法的完整说明保留在接口参考中。

状态查询能补回服务仍保留的 QUEUED / STARTED / COMPLETED / CANCELED / ERROR。当前离线服务发出终态后删除 owner，随后查询可能返回 UNKNOWN；在线服务保留最多 64 条历史。Demo 的共享会话桥绕过 Manager 的旧注册标记，按服务实例重新建立监听，收到终态后清理对应任务并忽略其迟到事件。服务死亡或明确换代时，将旧会话标为失效，而非伪造 COMPLETED/CANCELED；不向新服务查询或取消旧 ID。UNKNOWN 不当作结束，轮询也无法还原已经丢失的离线终态；要让查询接口完整补漏，仍需 SDK 服务端保留带归属的终态历史。

“设备工具 / Device tools”页新增 AI 对话、停止 AI 对话和注册乐奇拦截器三个按钮，与二维码、Wi-Fi 和应用显示配置同页，按钮区与纯文字日志区各占半屏，无清空日志或手动注销按钮。AI 使用 `startAiChat(false)`、`toAiChat(...)` 与 `endAiChat()`，同步调用和离页停止在串行工作线程执行，回调切回主线程显示；注册使用 `GlassSdk.registerLeqiInterceptor(this, interceptor)`，离开页面通过 `GlassSdk.unregisterLeqiInterceptor(interceptor)` 清理同一个 `LeqiInterceptor` 实例。注册后在本页喊“乐奇”，回调返回 `true` 拦截指令，次数和 extra 显示在下方日志，并以 `DeviceTools` 标签写入 logcat。按钮日志只确认调用返回，实际生效仍需看真实回调。离开页面时按对象注销，返回后需手动注册；不会清除后来替换它的其他拦截器。

消息发送页已移除“乐奇拦截器 A/B 测试”入口按钮；`TestLeqiActivity`、页面布局及 A/B 功能代码仍保留，供开发测试使用。该页面提供四个操作：**注册 A → 注册 B → 注销 A → 注销 B**。每步发一次真实乐奇指令，预期回调分别为 **A → B → B → 均无**，其中“注册 B 后注销 A，B 仍收到回调”验证 `GlassSdk.unregisterLeqiInterceptor(interceptor)` 按对象身份清理。A/B 是固定实例，注销操作始终调用 SDK；页面与 `LeqiAB` 日志分别记录 A/B 回调次数、时间及 extra，操作返回不作为测试通过依据。“本页预期拦截器”仅由本地操作推算，并非 SDK 查询值。进入页面后手动注册，离开时分别按对象注销 A/B，返回不会自动注册；计数在本页实例内累计。没有回调还需结合设备端指令产生记录判断。

## 手机端

视频接收页扩展示例支持 `GlassVideoStreamParam.zoomRatio`，同时显示远端声明的能力和 `appliedZoomRatio`。通过公开的 `MessageReceiver` 多监听总线读取原始 `VIDEO_STREAM_RESOLUTION_CHANGE`；只有该状态明确声明支持小数变焦时才启用选择；不能用本地模型默认值推断旧设备能力。先启动一次预览获取能力，再返回配置页选择倍率并重新启动。未设置新倍率时保持原来的流请求方式。

手机显示的是眼镜**最近上报的倍率**。当前 SDK 在流请求处发送该消息，没有持续倍率监听，消息也没有请求关联 ID，不能凭这条状态严格证明本次请求的帧已经达到所选倍率。断连会清除能力与选择，停止预览会清除实际倍率。

文件发送页使用新增 `FileReceiveListener.onProgressChanged(progress, transferredBytes, totalBytes)` 展示字节进度，并保留旧单参数回调。当前 SDK 的同一个 listener 可能接收 P2P 本地发送和对端进度，不能将每次通知都称为“对端已接收”。蓝牙等旧路径仍调用单参；三参默认实现转调单参，反方向不会自动转调。旧对端的缺省 `0/0` 不表示已完成，100% 也不能代替完成/失败回调。

`ExtConfig.configuredReportEnabledOrNull()` 可区分未配置（null）与明确 true/false；当前 Demo 没有 AppConfig 消费入口，本次列出差异，没有另造配置下发流程。

## 范围与验证

`acquireRtcWakeLease()` / `releaseRtcWakeLease()` 由服务端限制调用方，属于特定 RTC 集成，不作为普通第三方通用能力或页面开关；已移除的 crop、可信系统分析中间接口不重新引入。`DeviceExtra.capabilities`、`AiChatExtra.imageStreamTag`、`TtsExtra.sessionId`、`TokenResp.companyId` 等是公共模型字段，不是新的独立业务调用。

编译/测试验证与实机结果分开记录。当前示例不等于已完成新旧固件、系统签名权限和所有分辨率的设备验收。

实机日志可按 `MessageTts`、`CameraDemo` 过滤，并结合原消息页的结果日志核对：

- TTS：确认 API version、请求的 queueMode、session ID 和状态回调；COMPLETED 是服务报告的完成，听感仍需现场确认。
- 相机：Surface 核对协商 buffer 和持续帧统计；NV21 默认 Options ByteBuffer 核对 ready、generation 和匹配帧，另两种入口核对实际帧尺寸、持续帧统计和预览；I420 核对租约帧统计；拍照录像核对对应完成/错误回调。发出请求不视为完成。

本次新增的 NV21 Options byte[]、Config V1 ByteBuffer 入口尚未完成实机验收；不能从接口调用返回或既有 Options ByteBuffer 的测试结果推断这两条路径已通过。设备验证时分别选择三种接口并应用配置，核对日志中的具体方法、实际帧尺寸、连续预览，以及切换/退出后旧输出停止；Config V1 另核对整数倍率限制。

2026-10-12 验证记录：

- 眼镜端本次调整 TTS 配对按钮和重连处理后，执行 `:app:assembleDebug`、`:app:testDebugUnitTest` 和 `:app:compileDebugAndroidTestKotlin` 全部通过，83 项单元测试通过（0 失败），其中 15 项测试用模拟服务验证共享会话桥的注册、回调、重连和定向取消。布局设备测试只完成编译，未运行。
- 本次未安装到设备，未执行播报、拍照或录像实测，不能将编译与单测通过等同于设备验收。
- 手机端此前构建及 39 项单元测试通过；本次页面整合未改变手机端实现，没有重复运行手机端测试。
