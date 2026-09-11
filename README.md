# Glass3 SDK Demo

面向 Rokid Glass3 的示例工程，涵盖眼镜 SDK、手机与眼镜协同、行业应用和音视频传输。

## 眼镜端 Demo · glassdemo

演示拍照录像、相机共享与 NV21 取帧、消息通信、语音交互及设备状态读取，供眼镜应用开发参考。

[查看眼镜端 Demo 说明](glassdemo/README.md)

## 手机端 Demo · glass3sdkphonedemo

演示 Android 手机连接眼镜、双端消息与文件传输、实时音视频接收，以及通知和设备管理。

[查看手机端 Demo 说明](glass3sdkphonedemo/README.md)

## 行业应用 · 隐患识别

运行在眼镜上，通过 Wi-Fi 调用视觉大模型识别消防隐患，展示现场预览和识别结果，无需手机 App。DeepSeek 仅作接入示例，开发者可自行配置或接入其他视觉模型。

[查看隐患识别 Demo 说明](industry/hazard-recognition/README.md)

## 音视频采集与浏览器传输 · glass3-media-streaming

演示获取眼镜 NV21 视频帧和 PCM 音频，并通过 WebRTC 将音视频传输到 PC 浏览器；也可单独参考原始媒体采集功能。

[查看音视频采集与传输说明](glass3-media-streaming/README.md)

## 示例使用声明

各工程仅提供代码参考和使用示例，不强制采用相同方案。开发者可按实际需求调整实现，具体用法见各工程 README。
