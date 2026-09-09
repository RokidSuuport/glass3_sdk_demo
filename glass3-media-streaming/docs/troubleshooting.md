# 错误对照与排障

先以页面展示的稳定错误码定位，再收集对应日志。不要只凭“黑屏”或“没声音”判断硬件损坏。

| 现象 | 可能原因 | 处理步骤 | 错误码 | Logcat 关键字 | 仍未解决时收集的信息 |
| --- | --- | --- | --- | --- | --- |
| 点击开始后提示缺少权限 | 相机或录音权限未授予 | 到系统设置授权，返回应用后重新开始 | `PERMISSION_REQUIRED` | `Permission Denial`、`CAMERA denied`、`RECORD_AUDIO` | 权限页截图、应用包名、系统版本 |
| 一直无法准备采集 | Glass SDK 服务尚未绑定 | 开机完成后等待服务稳定，退出页面再进入 | `SDK_NOT_READY` | `Glass SDK`、`service not available`、`bindService` | 从开机到失败的完整日志、固件版本 |
| 采集中途停止 | SDK Binder 断开或系统服务重启 | 停止并重建采集对象，确认系统服务不再重启 | `SDK_DISCONNECTED` | `DeadObjectException`、`onBindingDied`、`binder` | 故障前后两分钟日志、系统服务进程状态 |
| 相机页面无画面且其他相机应用打开 | 扫码、录像或其他应用占用相机 | 退出占用应用，等待资源释放后重试 | `CAMERA_IN_USE` | `MAX_CAMERAS_IN_USE`、`Camera in use` | `dumpsys media.camera`、前台和后台应用列表 |
| 启动相机阶段超时 | CameraShare 或相机 HAL 未完成启动 | 停止后重试，首次开机时等待系统初始化 | `CAMERA_START_TIMEOUT` | `CameraShare`、`Camera not ready`、`start timeout` | 完整启动日志、相机 dumpsys、复现步骤 |
| 相机已启动但没有首帧 | NV21 图像管线未出帧 | 检查配置分辨率，关闭并重新打开采集 | `VIDEO_FRAME_TIMEOUT` | `first frame`、`NV21`、`frame timeout` | 配置参数、帧回调计数、CameraShare 日志 |
| 麦克风开始即失败 | 音频服务不可用或录音设备被占用 | 退出通话/录音应用，检查录音权限并重试 | `AUDIO_START_FAILED` | `audio.service`、`audio.primary.neo`、`AudioRecord`、`SIGABRT` | 音频 dumpsys、崩溃 tombstone、固件版本 |
| 音频状态已启动但持续无数据 | PCM 回调中断，或系统录音器重建后旧 Binder 回调失效 | WebRTC 推流入口会先释放旧信令席位、后台清理媒体，并按 1、2、4 秒有界重试；两次实际采集之间至少保留 5 秒设备恢复时间。纯取流入口返回错误后由调用方重新 `start`。同时确认 16 kHz 单声道 16 bit 配置 | `AUDIO_DATA_TIMEOUT` | `PCM`、`audio data timeout`、`unhealthy recorder` | PCM 回调计数、格式参数、故障前后日志 |
| 眼镜连接不到 PC | 地址错误、服务未启动、防火墙或网络隔离 | 用眼镜可达的 PC 地址，检查 `/health` 和 TCP 8080 | `SERVER_UNREACHABLE` | `WebSocket`、`connect failed`、`ECONNREFUSED` | 信令地址、两端 IP/路由、服务端日志 |
| 已连信令但一直等待 | 浏览器未点击开始、房间不一致或已有接收端 | 先启动浏览器接收，核对双方 roomId | `RECEIVER_NOT_READY` | `waiting receiver`、`room`、`receiver` | 双方 roomId、浏览器控制台、信令日志 |
| 浏览器不出画面且协商失败 | SDP、ICE 或 WebRTC PeerConnection 异常 | 重启双方会话，确认同网可直连 | `WEBRTC_NEGOTIATION_FAILED` | `offer`、`answer`、`ICE`、`PeerConnection` | 双方 SDP、ICE 状态、浏览器 WebRTC 内部页 |
| 推流后又断开 | Wi-Fi 切换、网络丢失或 WebSocket 关闭 | 恢复网络并等待有限重连，必要时手动重启 | `NETWORK_DISCONNECTED` | `network lost`、`WebSocket closed`、`ICE disconnected` | 网络切换时间、重连次数、两端日志 |
| 编译提示 `Unresolved reference: GlassMediaStreamer` | 未声明顶层推流坐标或仓库未生效 | 外部工程加入 Maven 仓库和 `glass3-media-streaming` 坐标后刷新依赖 | 构建错误 | `Could not find`、`Unresolved reference` | 模块构建文件、依赖树、仓库目录结构 |
| 编译提示 `Project with path could not be found` | 把本仓库的 `project(模块名)` 写进了外部工程 | 外部工程改用 `com.rokid.glass` Maven 坐标 | 构建错误 | `Project with path` | settings 文件、模块列表、依赖声明 |
| 编译提示 Rokid SDK class not found | Glass3 开放 SDK 仓库/依赖未配置或交付不完整 | 检查 Maven 目录和上游 SDK 访问权限，确认传递依赖可解析 | 构建错误 | `ClassNotFoundException`、`Could not resolve com.rokid` | 完整依赖树、Gradle 错误、交付目录清单 |

## 浏览器有画面但没有声音

先点击页面“开启声音”，这是浏览器自动播放策略要求。然后确认眼镜勾选音频、`audioBitrateBps` 在变化、`pcmUnderrunBytes` 没有持续快速增长。PC 自己的麦克风不参与此链路。

## 采集恢复顺序

1. 停止当前页面并等待资源释放。
2. 关闭扫码、录像、通话和其他占用摄像头/麦克风的应用。
3. 确认运行时权限与 Glass SDK 服务状态。
4. 先在“原始媒体采集”验证 NV21/PCM，再验证 WebRTC。
5. 仍失败时按表格最后一列收集信息，保留故障发生前后的连续日志。

如果日志中的 `RKGlassVoice recorder unhealthy, rebuilding` 持续按固定周期出现，且系统服务不再完成重建，这是设备媒体服务已经卡住，不是 WebRTC 协商问题。停止应用后仍不恢复时需重启眼镜，并把完整日志和固件版本提交给设备固件维护方；应用不会无限重试或在界面线程等待该系统服务。
