# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# 保留泛型、注解和内部类等元信息。
# Gson/Retrofit/Kotlin 相关库可能会读取这些信息来做反射解析。
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,Exceptions

# 保留 Android 系统通过 Manifest 或框架反射创建的组件。
# 这些类名不能被混淆，否则 Application、Activity、Service 等可能无法启动。
-keep class com.rokid.phone.MyApplication { *; }
-keep class com.rokid.phone.**Activity { *; }
-keep class com.rokid.phone.**Service { *; }
-keep class com.rokid.phone.**Receiver { *; }
-keep class com.rokid.phone.**Provider { *; }
-keep class * extends android.app.Application { *; }
-keep class * extends android.app.Activity { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.content.ContentProvider { *; }

# 保留 Gson 数据模型的字段名。
# 当前项目部分 JSON 模型没有使用 @SerializedName，字段名被混淆后会导致序列化/反序列化失败。
-keepclassmembers class com.rokid.phone.data.** {
    <fields>;
}
-keepclassmembers class com.rokid.phone.utils.RKSystemInfo {
    <fields>;
}
-keepclassmembers class com.rokid.phone.utils.DeviceInfo {
    <fields>;
}
-keepclassmembers class com.rokid.phone.system.AlbumInfo {
    <fields>;
}
-keepclassmembers class com.rokid.phone.system.viewmodel.SystemOtaStatus {
    <fields>;
}
-keepclassmembers class com.rokid.phone.system.model.** {
    <fields>;
}
-keepclassmembers class com.rokid.phone.ui.classicbt.model.** {
    <fields>;
}
-keepclassmembers class com.rokid.phone.notification.adapter.** {
    <fields>;
}

# 保留显式标记的类和字段。
# @Keep 标记表示代码主动要求不要混淆，@SerializedName 字段需要保留给 Gson 使用。
-keep class androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
    @com.google.gson.annotations.SerializedName <fields>;
}

# 保留 Gson TypeToken 的匿名泛型子类。
# Rokid SDK 内部使用 object : TypeToken<...>() 解析泛型数据，混淆后如果该匿名类的 Signature
# 被 R8 移除，Gson 会抛出 Missing type parameter。
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }

# 保留 Kotlin Flow/协程接口名。
-keep interface kotlinx.coroutines.flow.** { *; }
-keep interface kotlin.coroutines.Continuation { *; }

# 保留 Retrofit 核心类型名。
-keep class retrofit2.** { *; }

# 保留 RFM Lite JNI 桥接类。
-keep class com.rokid.sprite.aiapp.** { *; }

# 保留 Rokid SDK 边界上的类和回调接口实现。
# SDK 内部可能通过接口、反射或跨模块回调访问这些类型，混淆后容易影响连接、消息、OTA 等流程。
-keep class com.rokid.security.** { *; }
-keep class com.rokid.phone.** implements com.rokid.security.** { *; }
-keep class * implements com.rokid.security.** { *; }
-dontwarn com.rokid.security.**

# 忽略网络库里的可选依赖警告。
# Retrofit/OkHttp 在不同运行环境下会引用一些可选类，未打包进 APK 时不影响当前 Android 运行。
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**

# 忽略 ffmpeg-kit 的平台可选类警告。
# native 媒体库会按平台加载能力，R8 检查到的部分类不是当前 APK 必需项。
-dontwarn com.arthenica.ffmpegkit.**

# 忽略厂商/平台可选类警告。
# Rokid/JIT/MinIO 等依赖会引用系统隐藏 API、证书硬件、snappy 池化等可选能力；
# 这些类在特定设备或运行环境中才存在，R8 构建阶段不需要把它们当成错误。
-dontwarn android.os.SystemProperties
-dontwarn android.util.Pools$SimplePool
-dontwarn cn.com.jit.**
-dontwarn hsic.com.skfcertclient.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn java.beans.**
-dontwarn org.xerial.snappy.pool.**
