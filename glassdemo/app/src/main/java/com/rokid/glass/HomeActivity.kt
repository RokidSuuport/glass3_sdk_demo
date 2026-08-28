package com.rokid.glass


import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.rokid.glass.adapter.HomeAdapter
import com.rokid.glass.annotation.MenuConfigType
import com.rokid.glass.base.BaseActivity
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.component.MenuItem
import com.rokid.glass.data.GlobalData
import com.rokid.glass.speech.privateservice.PrivateSpeechActivity
import com.rokid.glass.utils.DeviceUtil
import com.rokid.glass.utils.DeviceUtil.isGlassLegFold
import com.rokid.glass.utils.DeviceUtil.isGlassTackOn
import com.rokid.glass.utils.FileSizeUtil
import com.rokid.glass.utils.GlassSdkUtils
import com.rokid.glass.utils.HeadPoseTracker
import com.rokid.glass.utils.LongPressDetector
import com.rokid.glass.utils.collect
import com.rokid.glesse.R
import com.rokid.glesse.databinding.ActivityHomeBinding
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.sdk.base.data.offlineCmd.bean.VoiceAction
import com.rokid.security.glass3.sdk.base.data.offlineCmd.listener.IVoiceCallback
import com.rokid.security.system.server.media.callback.VideoCallback
import kotlinx.coroutines.flow.drop
import kotlin.math.ceil


class HomeActivity : BaseActivity() {

    private val TAG = "HomeActivity"
    private lateinit var binding: ActivityHomeBinding
    private lateinit var directionTracker: HeadPoseTracker
    private var workHomeAdapter: HomeAdapter? = null
    private val menuList: MutableList<MenuItem> = ArrayList()
    private val showList: MutableList<MenuItem> = ArrayList()
    private var focusPosition = 0
    private val showMenuMax = 3
    private var currentPageNum = 1
    private var totalPageNum = 1
    private var bluetoothAdapter: BluetoothAdapter? = null
    private lateinit var snowVoiceAction: VoiceAction
    private lateinit var jdVoiceAction: VoiceAction
    private lateinit var zyVoiceAction: VoiceAction

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 设置应用开机自启动
        DeviceUtil.setSystemProp("persist.vendor.boot.pkg", this.packageName)
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 明确声明允许外部应用发送广播
            registerReceiver(eventReceiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(eventReceiver, intentFilter, RECEIVER_EXPORTED)
        }

        binding.myKeyboard.setOnKeyListener { view, keyCode, event ->
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    when (event.keyCode) {
                        // 音量减小键,对应 keycode 值：25
                        KeyEvent.KEYCODE_VOLUME_DOWN -> {
                            Log.d(TAG, "setOnKeyListener keyCode=${keyCode}，音量减小键")
                            LongPressDetector.getInstance().startDetect(
                                keyCode = event.keyCode,
                                onLongPressStart = {
                                    Log.d(TAG, "长按开始")
                                },
                                onLongPressEnd = {
                                    Log.d(TAG, "长按结束")
                                }
                            )
                        }
                        // 音量增大键,对应 keycode 值：24
                        KeyEvent.KEYCODE_VOLUME_UP -> {
                            Log.d(TAG, "setOnKeyListener keyCode=${keyCode}，音量增大键")
                            LongPressDetector.getInstance().startDetect(
                                keyCode = event.keyCode,
                                onLongPressStart = {
                                    Log.d(TAG, "长按开始")
                                },
                                onLongPressEnd = {
                                    Log.d(TAG, "长按结束")
                                }
                            )
                        }

                        KeyEvent.KEYCODE_ENTER -> {
                            Log.d(TAG, "setOnKeyListener 键盘回车事件")
                            jumpPage()
                        }

                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            Log.d(TAG, "setOnKeyListener 键盘往前键")
                            onGlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)
                        }

                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            Log.d(TAG, "setOnKeyListener 键盘往后键")
                            onGlassKeyEvent(GlassKeyEvent.KEYCODE_BEHIND)
                        }

                        KeyEvent.KEYCODE_BACK -> {
                            Log.d(TAG, "setOnKeyListener 鼠标右击返回事件")
                            finish()
                        }
                    }
                }
            }
            true
        }

        val freeSpace = FileSizeUtil.getSdCardAvailableBytes()   // 单位：字节
        val fileSize = FileSizeUtil.formatFileSize(freeSpace.toDouble())
        Log.d(TAG, "----剩余存储空间：$fileSize")
        val SAFE_MARGIN = 1000L * 1024 * 1024  // 1000MB
        if (freeSpace < SAFE_MARGIN) {
            // 存储空间不足，不允许继续录像
        }
        initSDK()

        directionTracker = HeadPoseTracker(this) { azimuth ->
            // yaw：-180° ~ +180°
            // 0 = 启动时朝向 偏航角
//            Log.d("AI-GLASS", "头部偏航角 = $azimuth°")
        }
        directionTracker.start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 优先将事件分发给keyMark视图
        binding.myKeyboard.dispatchKeyEvent(event)
        return true
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        when (keyEvent) {
            GlassKeyEvent.KEYCODE_FRONT -> {
                workHomeAdapter?.toNextItem()
            }

            GlassKeyEvent.KEYCODE_BEHIND -> {
                workHomeAdapter?.toPreviousItem()
            }

            GlassKeyEvent.KEYCODE_CLICK -> {
                jumpPage()
            }
        }
        return super.onGlassKeyEvent(keyEvent)
    }

    private fun jumpPage() {
        val startIndex = (currentPageNum - 1) * showMenuMax
        var endIndex = startIndex + showMenuMax
        if (endIndex > menuList.size) {
            endIndex = menuList.size
        }
        workHomeAdapter?.getFocusPosition()?.let { position ->
            if (position < endIndex - startIndex) { // 只处理有效菜单项点击
                jump2NextScene(position)
            }
        }
    }

    @SuppressLint("UseKtx")
    private fun initSDK() {
        GlassSdkUtils.initSdk()
        GlobalData.sdkInitState.drop(1).collect(lifecycleScope) {
            if (it) {
                Log.d(TAG, "SDK 初始化成功")
                initIconData()
                //离线语音指令,当说开始检查将会打印开始检查，推荐3-5个词，不要有叠音
                jdVoiceAction = VoiceAction("开始检查", "kai shi jian cha", object : IVoiceCallback.Stub() {
                    override fun onVoiceTriggered() {
                        Log.e(TAG, "开始检查")
                    }
                })
                GlassSdk.getGlassOfflineCmdService()?.add(jdVoiceAction)
                // 获取离线文本转语音管理模块
//                GlassSdk.getGlassOfflineTtsService()?.playTtsMsg("进入眼镜端演示工程")
                Log.d(TAG, "---眼镜SN号=${GlassSdk.getGlassDeviceService()?.serialNumber}")
                Log.d(TAG, "---电量值=${GlassSdk.getGlassDeviceService()?.deviceStatusInfo?.powerValue}")
                //离线语音指令, 推荐3-5个词，不要有叠音
                snowVoiceAction = VoiceAction("应急照明", "ying ji zhao ming", object : IVoiceCallback.Stub() {
                    override fun onVoiceTriggered() {
                        Log.e(TAG, "应急照明")
                    }
                })
                GlassSdk.getGlassOfflineCmdService()?.add(snowVoiceAction)
//                AudioService.start(this)
//                GlassSdk.getGlassDeviceService()?.switchMicScene(3)
//                startActivity(Intent(this, IdentificationActivity::class.java))
//              //                startActivity(Intent(this, OfflineCmdTestActivity::class.java))
//                startActivity(Intent(this, QRCodeActivity::class.java))
//                startActivity(Intent(this, QRCameraActivity::class.java))
                // setSystemTime 接收 Unix 时间戳（毫秒）。
//                 GlassSdk.getGlassDeviceService()?.setSystemTime(1770014682490)
                // 配置相机指示灯：true 开启，false 关闭；修改后需重启眼镜生效。
                GlassSdk.getGlassDeviceService()?.setCameraLedEnable(false)
                Log.e(DeviceUtil.TAG, "----是否带上眼镜： $isGlassTackOn")
                Log.e(DeviceUtil.TAG, "----是否折叠眼镜腿： $isGlassLegFold")

//                val min = 20
//                // 主页录像仅用于视频演示，不持续占用麦克风，避免 ASR/录音功能启动失败。
//                val enableAudio = false
//                val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath + "/video"
//                Log.d(TAG, "onCreate: path = $path")
//                val recordConfig = RecordConfig(
//                    path, min, enableAudio,
//                    width = PreviewResolution.ResolutionInfo_1080P_Land.width,
//                    height = PreviewResolution.ResolutionInfo_1080P_Land.height
//                )
//                // 开始视频录制
//                GlassSdk.getGlassMediaService()?.startRecord(videoCallback, recordConfig)
            } else {
                Log.d(TAG, "SDK 初始化失败")
            }
        }
        GlobalData.btConnectState.drop(1).collect(lifecycleScope) { connectState ->
            if (connectState) {
                binding.tvBluetoothStatus.text = "蓝牙已连接"
            } else {
                binding.tvBluetoothStatus.text = "蓝牙未连接"
            }
        }
        GlobalData.p2pConnectState.drop(1).collect(lifecycleScope) { connectState ->
            if (connectState) {
                binding.tvp2pStatus.text = "P2P已连接"
            } else {
                binding.tvp2pStatus.text = "P2P未连接"
            }
        }
        GlobalData.ringConnectState.drop(1).collect(lifecycleScope) { connectState ->
            if (connectState) {
                binding.ringStatus.text = "指环已连接"
            } else {
                binding.ringStatus.text = "指环未连接"
            }
        }
        binding.workHomeRecyclerLaunch.run {
            // 使用水平方向的LinearLayoutManager排列首页菜单
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false).apply {
                // 关闭平滑滚动条计算
                isSmoothScrollbarEnabled = false
            }
            // 禁用所有滚动和越界效果
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // 设置Adapter（确保Item宽度固定为1/3屏幕）
            adapter = HomeAdapter(context).also {
                workHomeAdapter = it
            }
        }

//
//        TestWebViewActivity.start(this, "https://www.so.com/")
//        val str = timestampToDateTime(1768467978416)
//        Log.d(TAG,"-----时间=${str}")
    }

    /**
     * 视频录制回调
     */
    private val videoCallback = object : VideoCallback.Stub() {
        override fun onError() {
            Log.d(TAG, "onError: ")
        }

        override fun onFinish() {
            Log.d(TAG, "onFinish: ")
        }

        override fun onNewFile(startTime: Long, endTime: Long, path: String, isLast: Boolean) {
            Log.d(TAG, "onNewFile: $path")
        }

        override fun onErrorWithDetail(code: Int, errorMsg: String) {
            Log.d(TAG, "onError: code = $code, errorMsg = $errorMsg")
        }

        override fun onStart() {
            Log.d(TAG, "onStart: ")
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(eventReceiver)
        if (::snowVoiceAction.isInitialized) {
            GlassSdk.getGlassOfflineCmdService()?.remove(snowVoiceAction)
        }
        if (::jdVoiceAction.isInitialized) {
            GlassSdk.getGlassOfflineCmdService()?.remove(jdVoiceAction)
        }
        if (::zyVoiceAction.isInitialized) {
            GlassSdk.getGlassOfflineCmdService()?.remove(zyVoiceAction)
        }
        if (::directionTracker.isInitialized) {
            directionTracker.stop()
        }
        GlassSdk.getGlassMediaService()?.stopRecord()
//        GlassSdk.getGlassMediaService()?.closeCamera(object : ICameraCloseCallback.Stub(){
//            override fun onClosed(success: Boolean) {
//                Log.d(TAG,"相机关闭结果----$success")
//            }
//        })
        GlassSdkUtils.destroySdk()
    }

    private fun initIconData() {
        menuList.run {
            clear()
            add(
                MenuItem(
//                    HomeMenuIndexEnum.AI_CHAT.chineseName,
                    "消息发送",
                    MenuConfigType.MenuInfoType.SEND_MSG,
                    R.mipmap.msg_send_select,
                    R.mipmap.msg_send_select,
                    R.drawable.home_bg,
                    R.drawable.home_bg_focus,
                    false
                )
            )
            add(
                MenuItem(
                    //   HomeMenuIndexEnum.IDCARD_RECOG.chineseName,
                    "消息接收",
                    MenuConfigType.MenuInfoType.RECEIVE_MSG,
                    R.mipmap.msg_receive_default,
                    R.mipmap.msg_receive_default,
                    R.drawable.home_bg,
                    R.drawable.home_bg_focus,
                    false
                )
            )
            add(
                MenuItem(
                    "SDK拍照录像",
                    MenuConfigType.MenuInfoType.SDK_TAKE_PHOTO,
                    R.mipmap.icon_take_photo,
                    R.mipmap.icon_take_photo,
                    R.drawable.home_bg,
                    R.drawable.home_bg_focus,
                    false
                )
            )
            add(
                MenuItem(
                    "人脸检测",
                    MenuConfigType.MenuInfoType.MENU_FACE_RECOG,
                    R.mipmap.icon_face_recog,
                    R.mipmap.icon_face_recog,
                    R.drawable.home_bg,
                    R.drawable.home_bg_focus,
                    true
                )
            )

            add(
                MenuItem(
//                    HomeMenuIndexEnum.LPR_RECOG.chineseName,
                    "车牌识别",
                    MenuConfigType.MenuInfoType.MENU_LPR_RECOG,
                    R.mipmap.icon_car,
                    R.mipmap.icon_car,
                    R.drawable.home_bg,
                    R.drawable.home_bg_focus,
                    false
                )
            )

            add(
                MenuItem(
                    "独立ASR/TTS",
                    MenuConfigType.MenuInfoType.ONLINE_ASR_TTS,
                    R.mipmap.app_online_speech,
                    R.mipmap.app_online_speech,
                    R.drawable.home_bg,
                    R.drawable.home_bg_focus,
                    false
                )
            )
        }
        totalPageNum = ceil(menuList.size.toDouble() / showMenuMax.toDouble()).toInt()
        if (totalPageNum <= 0) {
            totalPageNum = 1
        }
        currentPageNum = 1
        showPage(currentPageNum)
        workHomeAdapter?.toNextPage?.observe(this) {
            toNextPage()
        }
        workHomeAdapter?.toPreviousPage?.observe(this) {
            toPreviousPage()
        }
    }

    /**
     * 显示某页数
     */
    private fun showPage(pageIndex: Int) {
        Log.d(TAG, "pageIndex:$pageIndex")
        val startIndex = (pageIndex - 1) * showMenuMax
        var endIndex = startIndex + showMenuMax
        if (endIndex > menuList.size) {
            endIndex = menuList.size
        }
        showList.clear()
        showList.addAll(menuList.subList(startIndex, endIndex))
        workHomeAdapter?.run {
            setIconInfoList(showList)
            setFocusPosition(focusPosition)
            notifyDataSetChanged()
            setItemOnClickListener { position: Int, view: View? ->
                Log.d(TAG, "-->position: $position ，endIndex: $endIndex ，startIndex:${startIndex} ，focusPosition：" + focusPosition)
                if (position < endIndex - startIndex) { // 只处理有效菜单项点击
                    jump2NextScene(position)
                }
            }
        }
    }


    /**
     * 翻到上一页
     */
    private fun toPreviousPage() {
        if (currentPageNum > 1) {
            currentPageNum--
            focusPosition = (showMenuMax - 1)
            showPage(currentPageNum)
            Log.d(TAG, "进入上一页")
        }
    }

    /**
     * 向下翻页
     */
    private fun toNextPage() {
        if (currentPageNum < totalPageNum) {
            currentPageNum++
            focusPosition = 0
            showPage(currentPageNum)
            Log.d(TAG, "进入下一页")
        }
    }

    /**
     * 跳到下一个场景
     */
    private fun jump2NextScene(position: Int) {
        val actualPosition = (currentPageNum - 1) * showMenuMax + position
        Log.d(TAG, "jump2NextScene ${menuList[actualPosition].menuType}")
        if (actualPosition >= menuList.size) return
        when (menuList[actualPosition].menuType) {
            MenuConfigType.MenuInfoType.SEND_MSG -> {
//                if (!GlobalData.btConnectState.value) {
//                    Toast.makeText(this, "请先连接蓝牙", Toast.LENGTH_SHORT).show()
//                    return
//                }
                startActivity(Intent(this, SendMessageActivity::class.java))
            }

            MenuConfigType.MenuInfoType.RECEIVE_MSG -> {
                startActivity(Intent(this, MessageReceiveActivity::class.java))
            }

            MenuConfigType.MenuInfoType.SDK_TAKE_PHOTO -> {
                startActivity(Intent(this, SdkMediaActivity::class.java))
            }

            MenuConfigType.MenuInfoType.MENU_FACE_RECOG -> {
                startActivity(Intent(this, GlassFaceTrackActivity::class.java))
            }

            MenuConfigType.MenuInfoType.MENU_LPR_RECOG -> {
                startActivity(Intent(this, GlassLprTrackActivity::class.java))
            }

            MenuConfigType.MenuInfoType.ONLINE_ASR_TTS -> {
                startActivity(Intent(this, PrivateSpeechActivity::class.java))
            }
        }
    }

    private val mHandler = Handler(Looper.getMainLooper())
    private val mRunnable = Runnable { Log.d(TAG, "眼镜腿收到长点击事件") }

    // 创建广播接收器
    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return

            when (action) {
                // 单击眼镜腿物理按键
                MyApplication.ACTION_BUTTON_CLICK -> {
                    Log.d(TAG, "-----眼镜腿单击")
                    Toast.makeText(this@HomeActivity, "眼镜腿单击", Toast.LENGTH_SHORT).show()
                    // 消费单击事件，避免系统继续分发该广播。
                    abortBroadcast()
                }
                // 双击眼镜腿物理按键
                MyApplication.ACTION_BUTTON_DOUBLE_CLICK -> {
                    Toast.makeText(this@HomeActivity, "眼镜腿双击", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "-----眼镜腿双击")
                    // 消费双击事件，避免系统继续分发该广播。
                    abortBroadcast()
                }
                // 非折叠状态，长按镜腿物理按键1秒
                // 系统可直接发送长按事件。
                MyApplication.ACTION_LONG_PRESS -> {
                    Toast.makeText(this@HomeActivity, "眼镜腿长按", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "-----眼镜腿长按")
                }
                // 同时演示通过按下/抬起事件自行识别 3 秒长按；业务接入时可任选一种方案。
                MyApplication.ACTION_BUTTON_DOWN -> {
                    mHandler.postDelayed(mRunnable, 3000)
                    Log.d(TAG, "ACTION_BUTTON_DOWN received")
                }
                // 抬起时取消尚未触发的长按任务。
                MyApplication.ACTION_BUTTON_UP -> {
                    mHandler.removeCallbacks(mRunnable)
                    Log.d(TAG, "ACTION_BUTTON_UP received")
                }

                MyApplication.ACTION_TAKE_STATUS -> {
                    /**
                     * 1是佩戴，0是摘下；
                     */
                    if ("1" == intent.extras?.getString("glasses_take_state")) {
                        Toast.makeText(this@HomeActivity, "已戴上眼镜", Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "-----已戴上眼镜")
                    } else if ("0" == intent.extras?.getString("glasses_take_state")) {
                        Toast.makeText(this@HomeActivity, "已摘下眼镜", Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "-----已摘下眼镜")
                    }
                }

                MyApplication.ACTION_LEG_STATUS -> {
                    /**
                     * 1是展开，0是折叠；
                     */
                    if ("1" == intent.extras?.getString("glasses_leg_state")) {
                        Toast.makeText(this@HomeActivity, "眼镜腿已展开", Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "-----眼镜腿已展开")
                    } else if ("0" == intent.extras?.getString("glasses_leg_state")) {
                        Toast.makeText(this@HomeActivity, "眼镜腿已折叠", Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "-----眼镜腿已折叠")
                    }
                }
            }
        }
    }

    // 设置广播过滤器
    private val intentFilter = IntentFilter().apply {
        addAction(MyApplication.ACTION_BUTTON_CLICK)
        addAction(MyApplication.ACTION_BUTTON_DOUBLE_CLICK)
        addAction(MyApplication.ACTION_LONG_PRESS)
        addAction(MyApplication.ACTION_BUTTON_DOWN)
        addAction(MyApplication.ACTION_BUTTON_UP)
        addAction(MyApplication.ACTION_TAKE_STATUS)
        addAction(MyApplication.ACTION_LEG_STATUS)
        priority = 100 // 设置高优先级确保优先接收广播
    }

}
