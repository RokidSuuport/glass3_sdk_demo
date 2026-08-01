# glassdemo

## 私有化在线语音配置

“在线ASR/TTS”中的公共语音服务复用眼镜系统的 GlassSdk 环境与鉴权，无需在应用中填写域名或密钥。

私有化语音服务使用 `online-speech` SDK，配置保存在项目的 `gradle.properties` 中并随项目提交：

```properties
online.demo.domain=api.rokid.com
online.demo.ak=your-ak
online.demo.sk=your-sk
online.demo.uid=demo-user
online.demo.deviceId=demo-device
online.demo.asrPath=/ar/audio/api/ws/asr/streaming
online.demo.ttsPath=/ar/audio/api/ws/tts
```

如需接入私有化部署，替换域名、AK/SK、UID、设备 ID 和接口路径即可。证书校验默认开启；只有本地调试自签名证书时才额外配置 `online.demo.trustAllCerts=true`。

#### 介绍
眼睛端demo项目

