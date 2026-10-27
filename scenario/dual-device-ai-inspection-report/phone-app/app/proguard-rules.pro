# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# 保留泛型、注解和内部类等元信息。
# Preserve metadata such as generic signatures, annotations, and inner classes.
# Gson/Retrofit/Kotlin 相关库可能会读取这些信息来做反射解析。
# Libraries such as Gson, Retrofit, and Kotlin may read this metadata through reflection.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,Exceptions

# 保留 Android 系统通过 Manifest 或框架反射创建的组件。
# Preserve components created by Android through the Manifest or framework reflection.
# 这些类名不能被混淆，否则 Application、Activity、Service 等可能无法启动。
# Obfuscating these class names may prevent Application, Activity, Service, and similar components from starting.
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
# Preserve field names in Gson data models.
# 当前项目部分 JSON 模型没有使用 @SerializedName，字段名被混淆后会导致序列化/反序列化失败。
# Some JSON models in this project do not use @SerializedName, so obfuscated field names would break serialization and deserialization.
-keepclassmembers class com.rokid.phone.data.** {
    <fields>;
}
-keepclassmembers class com.rokid.phone.ui.classicbt.model.** {
    <fields>;
}
-keepclassmembers class com.rokid.phone.ai.** {
    <fields>;
}

# 保留显式标记的类和字段。
# Preserve explicitly annotated classes and fields.
# @Keep 标记表示代码主动要求不要混淆，@SerializedName 字段需要保留给 Gson 使用。
# @Keep explicitly requests that code remain unobfuscated; @SerializedName fields must be retained for Gson.
-keep class androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
    @com.google.gson.annotations.SerializedName <fields>;
}

# 保留 Gson TypeToken 的匿名泛型子类。
# Preserve anonymous generic subclasses of Gson TypeToken.
# Rokid SDK 内部使用 object : TypeToken<...>() 解析泛型数据，混淆后如果该匿名类的 Signature
# The Rokid SDK uses object : TypeToken<...>() to parse generic data. If the anonymous class's Signature
# 被 R8 移除，Gson 会抛出 Missing type parameter。
# is removed by R8 during obfuscation, Gson throws Missing type parameter.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }

# 保留 Kotlin Flow/协程接口名。
# Preserve Kotlin Flow and coroutine interface names.
-keep interface kotlinx.coroutines.flow.** { *; }
-keep interface kotlin.coroutines.Continuation { *; }

# 保留 RFM Lite JNI 桥接类。
# Preserve RFM Lite JNI bridge classes.
-keep class com.rokid.sprite.aiapp.** { *; }

# 保留 Rokid SDK 边界上的类和回调接口实现。
# Preserve classes and callback interface implementations at the Rokid SDK boundary.
# SDK 内部可能通过接口、反射或跨模块回调访问这些类型，混淆后容易影响连接、消息、OTA 等流程。
# The SDK may access these types through interfaces, reflection, or cross-module callbacks; obfuscation can disrupt connections, messages, OTA updates, and similar operations.
-keep class com.rokid.security.** { *; }
-keep class com.rokid.phone.** implements com.rokid.security.** { *; }
-keep class * implements com.rokid.security.** { *; }
-dontwarn com.rokid.security.**

# 忽略网络库里的可选依赖警告。
# Ignore warnings about optional dependencies in networking libraries.
# Retrofit/OkHttp 在不同运行环境下会引用一些可选类，未打包进 APK 时不影响当前 Android 运行。
# Retrofit and OkHttp reference optional classes in different environments; excluding them from the APK does not affect this Android runtime.
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn okhttp3.**
-dontwarn okio.**

# 忽略厂商/平台可选类警告。
# Ignore warnings about optional vendor or platform classes.
# Rokid/JIT/MinIO 等依赖会引用系统隐藏 API、证书硬件、snappy 池化等可选能力；
# Dependencies such as Rokid, JIT, and MinIO reference optional capabilities including hidden system APIs, certificate hardware, and snappy pooling;
# 这些类在特定设备或运行环境中才存在，R8 构建阶段不需要把它们当成错误。
# these classes exist only on certain devices or runtimes, so R8 need not treat them as build errors.
-dontwarn android.os.SystemProperties
-dontwarn android.util.Pools$SimplePool
-dontwarn cn.com.jit.**
-dontwarn hsic.com.skfcertclient.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn java.beans.**
-dontwarn org.xerial.snappy.pool.**
