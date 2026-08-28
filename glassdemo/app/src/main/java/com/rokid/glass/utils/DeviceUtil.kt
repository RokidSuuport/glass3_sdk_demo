package com.rokid.glass.utils

import android.content.Context
import android.content.pm.PackageManager
import android.icu.text.SimpleDateFormat
import android.os.Build
import android.os.LocaleList
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.rokid.security.glass3.open.sdk.uitls.log.L
import java.io.BufferedReader
import java.io.FileReader
import java.io.IOException
import java.util.Date

/** 读取设备与系统信息的工具。 */
object DeviceUtil {
    val TAG: String = DeviceUtil::class.java.getSimpleName()

    @get:Throws(Exception::class)
    val version: Array<String?>
        /* 返回值依次为：内核版本、系统版本、设备型号、固件版本和品牌。 */
        get() {
            val version = arrayOf<String?>("null", "null", "null", "null", "null")
            val str1 = "/proc/version"
            val str2: String
            val arrayOfString: Array<String>?
            try {
                val localFileReader = FileReader(str1)
                val localBufferedReader = BufferedReader(
                    localFileReader, 8192
                )
                str2 = localBufferedReader.readLine()
                arrayOfString = str2.split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                version[0] = arrayOfString[2] //KernelVersion
                localBufferedReader.close()
            } catch (e: IOException) {
            }
            version[1] = Build.VERSION.RELEASE // system version
            version[2] = Build.MODEL //model
            version[3] = Build.DISPLAY //firmware version
            version[4] = Build.BRAND //ping pai
            return version
        }

    fun getVersionName(context: Context): String? {
        // 获取packagemanager的实例
        val packageManager = context.getPackageManager()
        // getPackageName()是你当前类的包名，0代表是获取版本信息
        try {
            val packInfo = packageManager.getPackageInfo(context.getPackageName(), 0)
            val version = packInfo.versionName
            return version
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
        return null
    }

    fun getVersionCode(context: Context): String? {
        // 获取packagemanager的实例
        val packageManager = context.getPackageManager()
        // getPackageName()是你当前类的包名，0代表是获取版本信息
        try {
            val packInfo = packageManager.getPackageInfo(context.getPackageName(), 0)
            val version = packInfo.versionCode
            return version.toString() + ""
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
        return null
    }

    val systemVersion: String?
        /**
         * 获得其中的当前版本
         *
         * @return
         */
        get() {
            try {
                val strBuildInfoArray = Build.FINGERPRINT.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val strBuildNumArray = strBuildInfoArray[1].split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                var buildNumber = strBuildNumArray[strBuildNumArray.size - 1]
                if (!isSpriteDisplaySys) {
                    buildNumber = buildNumber.replace("-150", "-151")
                }
                return buildNumber
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return ""
        }

    val currentThreeDigitVersion: String?
        /**
         * 获得当前的三位数字版本
         *
         * @return
         */
        get() {
            try {
                val numberText = systemVersion!!.split("-".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                return numberText[0]
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return ""
        }


    val systemModel: String?
        /**
         * 获取手机型号
         *
         * @return 手机型号
         */
        get() = Build.MODEL

    val deviceBrand: String?
        /**
         * 获取手机厂商
         *
         * @return 手机厂商
         */
        get() = Build.BRAND

    val nowDataTime: String?
        get() {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", LocaleList.getDefault().get(0))
            val dt = Date(System.currentTimeMillis())
            return sdf.format(dt)
        }

    val isHallMode: Boolean
        get() = "1" == getSystemProp("persist.vendor.rkd.ui_mode")

    val sn: String?
        get() = getSystemProp("ro.serialno")

    val deviceType: String?
        get() = getSystemProp("ro.boot.devicetypeid")

    fun setSystemProp(key: String?, value: String?) {
        try {
            val c = Class.forName("android.os.SystemProperties")
            val set = c.getMethod("set", String::class.java, String::class.java)
            set.invoke(c, key, value)
            Log.d(TAG, "setProp " + key + "、" + value)
        } catch (e: Exception) {
            L.e(TAG, "setSystemProp [" + key + "]:[" + value + "] error!")
        }
    }

    fun getSystemProp(key: String?): String? {
        var value: String? = null

        try {
            val systemProperties = Class.forName("android.os.SystemProperties")
            val method = systemProperties.getMethod("get", String::class.java)
            value = method.invoke(null, key) as String?
        } catch (e: Exception) {
            e.printStackTrace()
        }
        Log.d(TAG, "Prop " + key + "、" + value)
        return value
    }

    fun setGlassMode(is3D: Boolean) {
        Log.d(TAG, "setPlayMode call --> " + (if (is3D) "3d" else "2d"))

        try {
            val aClass = Class.forName("com.rokid.display.RokidDisplayManager")
            val instanceMethod = aClass.getDeclaredMethod("instance")
            val o = instanceMethod.invoke(null)
            val setAirDisplayMode = aClass.getDeclaredMethod("setAirDisplayMode", Int::class.javaPrimitiveType)
            setAirDisplayMode.invoke(o, if (is3D) 1 else 0)
            Log.d(TAG, "==set2DMode  success --> " + (if (is3D) "3d" else "2d"))
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }


    fun getGlassDeviceName(context: Context): String? {
        var deviceName = Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME)
        if (deviceName == null) {
            deviceName = Build.MODEL
        }
        return deviceName
    }


    /**
     * 判断眼镜是否带上
     * true 表示带上眼镜
     * false 表示未带上眼镜
     * 1是佩戴，0是摘下
     */
    val isGlassTackOn: Boolean get() = "1" == getSystemProp("vendor.rkd.glasses.is_take_on")

    /**
     * 获取眼镜是否折叠
     * true 表示折叠眼镜腿
     * false 表示未折叠眼镜腿
     * 1是展开，0是折叠
     */
    val isGlassLegFold: Boolean get() = "0" == getSystemProp("vendor.rkd.glasses.is_spread")

    val isSpriteDisplaySys: Boolean get() = "1" == getSystemProp("ro.boot.glassesWithPanel")

    /**
     * 重启设备
     */
    fun rebootDevice(context: Context) {
        L.v("DeviceUtil", "rebootDevice() call")
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager?
            if (powerManager != null) {
                L.v("DeviceUtil", "the powerManager reboot call")
                powerManager.reboot(null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
