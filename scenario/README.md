# 眼镜端场景应用

每个子目录是可独立打开、构建和安装的 Android 工程。场景应用直接使用眼镜系统能力和 Wi-Fi 网络，不依赖手机端 App。

| 应用 | 目录 | 最小场景 | SDK 版本 |
| --- | --- | --- | --- |
| 音视频传输 | [glass3-media-streaming](glass3-media-streaming/) | 眼镜采集 NV21 视频和 PCM 音频 → WebRTC 传输到 PC 浏览器 | 2.2.0-E |
| 隐患识别 | [hazard-recognition](hazard-recognition/) | 实时预览 → NV21 抽帧 → 视觉大模型分析 → 眼镜显示结果 | 2.2.0-E |
| 双端AI查验 | [dual-device-ai-inspection-report](dual-device-ai-inspection-report/) | 眼镜采集现场画面 → 隐患识别 → 结果与图片同步手机 → 生成 PDF 报告 | 2.2.2-E |

后续可在这里新增其他场景应用目录；目前不提取公共模块，待多个应用存在稳定的复用需求后再拆分。
