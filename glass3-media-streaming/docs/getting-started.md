# 5 分钟上手

本页面面向第一次拿到项目的使用者。目标是先让“一台 Glass3 向一个 PC 浏览器发送音视频”跑通，再决定是否二次集成。

如果只想验证原始视频/音频，准备 Java、Android SDK 和 ADB 后，执行第 5、6、8 节即可；无需安装或启动 PC 接收服务。接入自己的应用见 [原始媒体采集接入](media-capture-integration.md)。

下文用 `<PROJECT_ROOT>` 表示项目根目录，用 `<PC-IP>` 表示 PC 在当前局域网中的地址。尖括号内容必须替换成真实值。

## 1. 检查环境

工作目录：任意目录。

```bash
node --version
java -version
adb devices
```

预期结果：Node.js 不低于 18，Java 为 17，`adb devices` 下出现眼镜序列号且状态为 `device`。

如果失败：先执行 `adb get-state` 判断连接状态；若为 `unauthorized`，在眼镜上确认调试授权。Java 或 Node.js 版本不符时先切换版本再继续。

## 2. 安装 PC 接收端依赖

工作目录：`<PROJECT_ROOT>/web-stream-receiver`。

```bash
cd <PROJECT_ROOT>/web-stream-receiver
npm install
```

预期结果：依赖安装完成，没有 error 级错误。

如果失败：执行 `npm config get registry` 检查仓库地址，再执行 `npm cache verify` 检查缓存，然后重试。

## 3. 启动 PC 接收端

工作目录：`<PROJECT_ROOT>`。

```bash
cd <PROJECT_ROOT>
./scripts/start-web-stream-receiver.sh
```

预期结果：终端显示浏览器地址和信令地址，服务持续运行。

如果失败：在项目根目录执行 `npm --prefix web-stream-receiver start` 查看原始错误；端口占用时关闭占用 TCP 8080 的程序后重试。

## 4. 找到 PC 地址并打开页面

macOS 可执行：

```bash
ipconfig getifaddr en0
```

Linux 可执行：

```bash
hostname -I
```

在 PC 浏览器打开 `http://<PC-IP>:8080/`，再检查 `http://<PC-IP>:8080/health`。健康检查预期返回状态为 ok 的 JSON。

如果失败：执行 `curl http://<PC-IP>:8080/health`。本机可访问但眼镜不可访问时，检查 PC 防火墙、Wi-Fi 客户端隔离、访客网络和 VPN 路由。

## 5. 构建眼镜应用

工作目录：`<PROJECT_ROOT>`。

```bash
./android/gradlew -p android :glass-stream-sender:assembleDebug
```

预期产物：`<PROJECT_ROOT>/android/glass-stream-sender/build/outputs/apk/debug/glass-stream-sender-debug.apk`。

如果失败：执行 `./android/gradlew -p android projects`，确认 Android SDK、JDK 17、依赖仓库和 `glass-stream-sender` 模块均可用。

## 6. 安装到眼镜

工作目录：`<PROJECT_ROOT>`。

```bash
adb install -r android/glass-stream-sender/build/outputs/apk/debug/glass-stream-sender-debug.apk
```

预期结果：终端显示 `Success`。

如果失败：先执行 `adb get-state`；安装策略拒绝时联系设备管理员开放安装权限，版本签名冲突时先确认旧应用数据是否可以清除。

## 7. 开始浏览器推流

1. PC 页面点击“开始接收”。
2. 眼镜打开 Glass3 音视频传输应用。
3. 输入 `ws://<PC-IP>:8080/ws`，保留默认房间 `default`。
4. 点击“连接并传输”。眼镜状态应依次经过等待接收端、协商中、传输中。
5. 浏览器收到音频轨道后点击“开启声音”。浏览器的自动播放策略要求这个人工操作。

预期结果：浏览器出现眼镜画面并能听到声音，统计区域持续更新分辨率、FPS、码率、丢包和延迟。

如果失败：眼镜显示的稳定错误码可直接在 [错误对照表](troubleshooting.md) 中查找；先确认地址不是 `localhost`、浏览器已经开始接收、两端网络可互访。

## 8. 只验证原始媒体采集

眼镜应用进入“原始媒体采集”页面后点击开始。该页面不需要 PC 服务，也不使用 WebRTC。

预期结果：页面持续显示 NV21 分辨率和帧大小、PCM 采样率和帧大小，并可保存 JPEG 与 WAV 验证文件。

如果失败：先退出扫码、录像及其他相机应用，重新进入；再按 `SDK_NOT_READY`、`CAMERA_IN_USE`、`VIDEO_FRAME_TIMEOUT` 或音频类错误进行排查。

## 正常运行的判断标准

- 原始媒体采集能持续获得真实 NV21 和 PCM，不是模拟数据。
- 浏览器画面持续更新，开启声音后有连续音频。
- 点击停止或退出页面后相机、麦克风、WebSocket 和 WebRTC 均被释放。
- 再次开始仍能恢复，不需要重启眼镜。
