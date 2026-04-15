package com.rokid.glass.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import com.rokid.glass.MyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart


/**
 * 通过Class跳转界面
 */
fun Activity.startActivityForResult(cls: Class<*>, requestCode: Int) {
    startActivityForResult(cls, requestCode, null)
}

/**
 * 含有Bundle通过Class跳转界面
 */
fun Activity.startActivityForResult(cls: Class<*>, requestCode: Int, bundle: Bundle?) {
    val intent = Intent()
    intent.setClass(this, cls)
    if (bundle != null) {
        intent.putExtras(bundle)
    }
    startActivityForResult(intent, requestCode)
}

fun View.setOnClickListener1(onClickListener: (View) -> Unit) {
    setOnClickListener {
        if (isFastClick()) {
            return@setOnClickListener
        }
        onClickListener.invoke(this)
    }
}


/**
 * 含有Bundle通过Class跳转界面
 * @param cls 跳转界面
 */
@JvmOverloads
fun Activity.startActivity(cls: Class<*>, bundle: Bundle? = null) {
    val intent = Intent()
    intent.setClass(this, cls)
    if (bundle != null) {
        intent.putExtras(bundle)
    }
    startActivity(intent)
}

/**
 * 获取字体所对应的像素
 */
fun getSp(sp: Float): Float {
    val resources = Resources.getSystem()
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
}

/**
 * 上一次点击的默认时间0
 */
var mLastClickTime: Long = 0
private const val MIN_CLICK_DELAY_TIME: Long = 600

/**
 * 是否快速点击  true 是  false 否
 * @param minClickDelayTime 单位毫秒
 */
fun isFastClick(minClickDelayTime: Long = MIN_CLICK_DELAY_TIME): Boolean {
    val currentTime = System.currentTimeMillis()
    if (currentTime - mLastClickTime > minClickDelayTime) {
        mLastClickTime = currentTime
        return false
    }
    return true
}


/**
 * 上一次点击的默认时间0
 */
private var mLastClickTime2: Long = 0

/**
 * 上一次点击的默认时间0
 */
private var mLastClickTime3: Long = 0

/**
 * 是否快速点击  true 是  false 否
 * @param minClickDelayTime 单位毫秒
 */
fun isFastClick2(minClickDelayTime: Long = 300): Boolean {
    val currentTime = System.currentTimeMillis()
    if (currentTime - mLastClickTime2 > minClickDelayTime) {
        mLastClickTime2 = currentTime
        return false
    }
    return true
}


/**
 * 是否快速点击  true 是  false 否
 * @param minClickDelayTime 单位毫秒
 */
fun isFastClick3(minClickDelayTime: Long = 500): Boolean {
    val currentTime = System.currentTimeMillis()
    if (currentTime - mLastClickTime3 > minClickDelayTime) {
        mLastClickTime3 = currentTime
        return false
    }
    return true
}


/**
 * 是否快速点击  true 是  false 否
 * @param minClickDelayTime 单位毫秒
 */
fun isFastConnectToBle(minClickDelayTime: Long = 3000): Boolean {
    val currentTime = System.currentTimeMillis()
    if (currentTime - mLastClickTime3 > minClickDelayTime) {
        mLastClickTime3 = currentTime
        return false
    }
    return true
}


fun Float.sp2px(): Float {
    val displayMetrics = MyApplication.mContext!!.resources.displayMetrics
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, this, displayMetrics)
}

fun Int.dp2px(): Int {
    val displayMetrics = MyApplication.mContext!!.resources.displayMetrics
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), displayMetrics)
        .toInt()
}

fun Float.dp2px(): Float {
    val displayMetrics = MyApplication.mContext!!.resources.displayMetrics
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this, displayMetrics)
}

fun Float.px2dip(): Float {
    val density = MyApplication.mContext!!.resources.displayMetrics.density
    return this / density + 0.5f
}

/**
 * 获取application中指定的meta-data
 *
 * @return 如果没有获取成功(没有对应值 ， 或者异常)，则返回值为空
 */
fun getAppMetaData(ctx: Context?, key: String?): String? {
    if (ctx == null || TextUtils.isEmpty(key)) {
        return null
    }
    try {
        val packageManager = ctx.packageManager ?: return null
        //注意此处为ApplicationInfo，因为友盟设置的meta-data是在application标签中
        val applicationInfo =
            packageManager.getApplicationInfo(ctx.packageName, PackageManager.GET_META_DATA)
                ?: return null
        if (applicationInfo.metaData != null) {
            //key要与manifest中的配置文件标识一致
            return applicationInfo.metaData.getString(key)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        e.printStackTrace()
    }
    return null
}

/**
 * 倒计时的协程
 */
fun countDownCoroutines(
    total: Int,
    scope: CoroutineScope,
    onTick: ((Int) -> Unit)? = null,
    onStart: (() -> Unit)? = null,
    onFinish: (() -> Unit)? = null,
    intervalTime: Long = 1000,
): Job {
    return flow {
        for (i in (total - 1) downTo 0) {
            emit(i)
            delay(intervalTime)
        }
    }.flowOn(Dispatchers.IO)
        .onStart { onStart?.invoke() }
        .onCompletion { onFinish?.invoke() }
        .onEach { onTick?.invoke(it) }
        .launchIn(scope)
}