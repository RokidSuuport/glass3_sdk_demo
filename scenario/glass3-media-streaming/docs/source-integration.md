# 将源码组件接入自己的 Android 工程

如果直接使用本项目，Android Studio 打开 `android` 目录即可，模块已经配置好。本页只面向已有 Android 应用、需要复用组件的开发者。

## 1. 按目标复制组件

在自己工程根目录新建 `components` 文件夹，复制本项目 `android` 下的对应目录，保留其中的 `build.gradle`、`src` 和规则文件；不复制 `build`、缓存或本机 SDK 配置。

| 目标 | 需要复制的目录 | App 调用入口 |
| --- | --- | --- |
| 只取 NV21 / PCM | `glass3-media-capture` | `GlassMediaCapture` |
| 完整浏览器传输 | `glass3-media-capture`、`webrtc-transport`、`glass3-media-streaming` | `GlassMediaStreamer` |

例如只取流的目录是：

```text
你的 Android 工程/
├── app/
├── components/
│   └── glass3-media-capture/
│       ├── build.gradle
│       └── src/
├── build.gradle
└── settings.gradle
```

## 2. 注册模块并声明依赖

只取流：在自己工程的 `settings.gradle` 增加：

```groovy
include ':glass3-media-capture'
project(':glass3-media-capture').projectDir = file('components/glass3-media-capture')
```

在自己 App 的 `build.gradle` 的 `dependencies` 中增加：

```groovy
implementation project(':glass3-media-capture')
```

完整浏览器传输：在上述模块基础上，再在 `settings.gradle` 注册：

```groovy
include ':webrtc-transport', ':glass3-media-streaming'
project(':webrtc-transport').projectDir = file('components/webrtc-transport')
project(':glass3-media-streaming').projectDir = file('components/glass3-media-streaming')
```

App 依赖改为下面一项即可，它会传递采集 API 和内部传输依赖：

```groovy
implementation project(':glass3-media-streaming')
```

`project(':模块名')` 指的是当前工程注册的源码模块，不会自动下载本项目目录。路径不同就同步修改 `projectDir`。

## 3. 对齐构建环境

当前验证组合为 JDK 17、Gradle 8.11.1、Android Gradle Plugin 8.10.0、Kotlin 2.2.0、compileSdk / targetSdk 34、minSdk 29，设备 ABI 为 `arm64-v8a`。

自己工程的根 `build.gradle` 应能解析以下插件；已有相同配置不要重复添加，也不要覆盖自己的 applicationId、签名或产品配置：

```groovy
plugins {
    id 'com.android.application' version '8.10.0' apply false
    id 'com.android.library' version '8.10.0' apply false
    id 'org.jetbrains.kotlin.android' version '2.2.0' apply false
}
```

将 [本项目 settings.gradle](../android/settings.gradle) 中的 `pluginManagement` 和 `dependencyResolutionManagement` 仓库配置合并进自己的对应配置块，保留自己已有的依赖源。Glass3 SDK、WebRTC 等第三方依赖由组件的 `build.gradle` 声明。同步失败时先核对依赖源可达性和具体失败的依赖名称。

在 `gradle.properties` 启用 `android.useAndroidX=true`。App 的 Android 配置需包含：

```groovy
android {
    compileSdk 34
    defaultConfig {
        minSdk 29
        targetSdk 34
        ndk { abiFilters 'arm64-v8a' }
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = '17' }
}
```

不同开发机不影响源码模块的使用；不同插件/SDK 版本组合仍需在自己的工程验证编译和真机运行，不能保证所有版本无条件兼容。

## 4. 复制完整页面

- 只取流：选择 [Kotlin](code/kotlin/MediaCaptureActivity.kt) 或 [Java](code/java/MediaCaptureActivity.java)，并一起复制 [LatestVideoWorker.java](code/shared/LatestVideoWorker.java)。这是页面内部使用的小型有界工作队列，业务只调用 `GlassMediaCapture`。
- 浏览器传输：选择 [Kotlin](code/kotlin/StreamingActivity.kt) 或 [Java](code/java/StreamingActivity.java)，业务只调用 `GlassMediaStreamer`。

将文件放到 App 的 `src/main/java` 下对应包目录。示例可保留原包名；若修改包名，需同步修改 Manifest 的 Activity 名称和文件中的 import。

合并对应的完整应用配置：[只取流 Manifest](../verification/source-consumer/capture-app/src/main/AndroidManifest.xml)、[浏览器传输 Manifest](../verification/source-consumer/streaming-app/src/main/AndroidManifest.xml)。保留自己原有的 application 和启动页，只注册需要的 Activity。传输示例的明文网络设置仅用于局域网调试，公网应使用 HTTPS/WSS。

## 5. 编译与验证

在自己的工程运行 `./gradlew :app:assembleDebug`，安装到 Glass3，再打开复制的页面。

- 只取流：点击开始，NV21 和 PCM 信息分别持续变化；停止后回到空闲，再次开始能恢复。
- 浏览器传输：先按 [上手运行](getting-started.md) 启动接收端；PC 点击“开始接收”，再在眼镜输入 PC 页面显示的信令地址并开始传输。
- 失败时按页面错误码查 [错误对照](troubleshooting.md)，不要把没有抛异常或连接成功等同于已收到媒体。

本项目用 `sh scripts/verify-source-consumer.sh` 自动复制组件到独立临时工程，分别编译只取流和传输应用的 Kotlin / Java 页面，并验证只取流应用不依赖 WebRTC。该检查验证源码接入和示例编译，不代替客户设备上的实际采集与播放测试。
