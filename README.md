# glass3sdkdemo

#### 介绍
glass3眼镜端和手机端demo示例项目

#### 文档地址
https://x-docs.rokid.com/docs/

#### Android SDK 本地配置

`local.properties` 包含开发者电脑上的 Android SDK 绝对路径，不应提交到 Git。
用 Android Studio 打开 `glassdemo` 或 `glass3sdkphonedemo` 时，IDE 会根据本机 SDK 设置生成该文件。

使用命令行构建时，可以设置 `ANDROID_HOME`，或在对应子工程中创建不提交的
`local.properties`：

```properties
sdk.dir=/absolute/path/to/Android/sdk
```
