# 双端 AI 查验与手机报告 Demo

项目名：`dual-device-ai-inspection-report`

这个项目把隐患查验的眼镜端和手机端整理成了两个独立 App。眼镜负责看现场、查验隐患并把结果传给手机；手机负责连接眼镜、保存记录和生成 PDF 报告。

## 两个应用

| 应用 | 主要用途 |
|---|---|
| `glass-app` | 实时预览、离线“识别”指令、触摸板/指环触发、调用视觉模型或 SDK |
| `phone-app` | 蓝牙/P2P 连接、电量状态、AI 记录与报告、相册和设置 |

安装包放在 `artifacts`：

```text
dual-ai-inspection-glasses-debug.apk
dual-ai-inspection-phone-report-debug.apk
```

## 快速体验

1. 分别把两个 APK 安装到眼镜和手机。
2. 打开手机端，授予蓝牙权限并连接 `Glass3_*` 设备，等待蓝牙和 P2P 配对完成。
3. 打开眼镜端，对现场说“识别”，或单击触摸板/指环。
4. 识别结果会同步到手机。进入手机端“AI 查验”，选择一条记录并生成报告。

手机主页面还会显示眼镜电量和 P2P 状态，并可进入相册或设置页。

眼镜端不配置视觉模型时会使用 SDK 兜底；配置自己的模型后可以直接调用兼容 Chat Completions 的图片接口。

## 工程结构

```text
dual-device-ai-inspection-report/
├── glass-app/      独立眼镜端 Gradle 工程
├── phone-app/      独立手机端 Gradle 工程
└── artifacts/      Debug APK 与 SHA-256
```

两个工程都可以单独用 Android Studio 打开，也可以用各自的 Gradle Wrapper 构建：

```bash
cd glass-app
./gradlew assembleDebug

cd ../phone-app
./gradlew assembleDebug
```

## 配置视觉模型

视觉模型配置位于 `glass-app/inspection.properties`。在其中填写模型地址、模型名和 API Key 后重新构建即可。`engine` 支持：

- `sdk`：使用 SDK 自带识别能力。
- `api`：调用配置的视觉模型。
- `auto`：优先调用模型，失败时回退 SDK；未配置时直接使用 SDK。
