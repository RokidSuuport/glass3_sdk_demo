# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# 保留泛型、注解、内部类等元信息（Gson/Retrofit/Kotlin 反射解析需要）。
# Preserve metadata such as generics, annotations and inner classes (needed by Gson/Retrofit/Kotlin reflection).
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,Exceptions

# 保留由 Manifest 或框架反射创建的系统组件，类名被混淆会导致无法启动。
# Keep components created through the Manifest or framework reflection; obfuscated names break startup.
-keep class com.rokid.glass.MyApplication { *; }
-keep class com.rokid.glass.**Activity { *; }
-keep class com.rokid.glass.**Service { *; }
-keep class com.rokid.glass.**Receiver { *; }
-keep class com.rokid.glass.**Provider { *; }
-keep class * extends android.app.Application { *; }
-keep class * extends android.app.Activity { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.content.ContentProvider { *; }

# 保留 Gson 数据模型的字段名（demo 未使用 @SerializedName，字段名被混淆会导致解析失败）。
# Preserve field names of Gson models; the demo does not use @SerializedName, so obfuscated field names break parsing.
-keepclassmembers class com.rokid.glass.bean.** {
    <fields>;
}
-keepclassmembers class com.rokid.glass.data.** {
    <fields>;
}

# 保留显式标记的类/字段。
# Preserve explicitly annotated classes and fields.
-keep class androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
    @com.google.gson.annotations.SerializedName <fields>;
}

# 保留 Gson TypeToken 的匿名泛型子类（签名被移除时会抛 Missing type parameter）。
# Preserve anonymous generic TypeToken subclasses; removing their signature makes Gson throw "Missing type parameter".
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }

# 保留 Kotlin 协程接口名。
# Preserve Kotlin coroutine interface names.
-keep interface kotlinx.coroutines.flow.** { *; }
-keep interface kotlin.coroutines.Continuation { *; }

# 保留 Rokid SDK 边界上的类与回调实现（SDK 可能通过接口/反射跨模块访问）。
# Keep classes and callback implementations at the Rokid SDK boundary; the SDK may reach them through interfaces or reflection.
-keep class com.rokid.security.** { *; }
-keep class com.rokid.glass.** implements com.rokid.security.** { *; }
-keep class * implements com.rokid.security.** { *; }
-dontwarn com.rokid.security.**

# 忽略可选依赖/隐藏 API 的构建期告警（运行期由设备能力决定是否存在）。
# Ignore build-time warnings for optional dependencies and hidden APIs; runtime availability depends on the device.
-dontwarn android.os.SystemProperties
-dontwarn android.util.Pools$SimplePool
-dontwarn com.rokid.display.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn java.beans.**
-dontwarn org.xerial.snappy.pool.**
