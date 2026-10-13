package com.rokid.glass

import com.rokid.glass.utils.demoString

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
        // Configure the app to start automatically at boot.
        DeviceUtil.setSystemProp("persist.vendor.boot.pkg", this.packageName)
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 明确声明允许外部应用发送广播
            // Explicitly allow broadcasts from external applications.
            registerReceiver(eventReceiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(eventReceiver, intentFilter, RECEIVER_EXPORTED)
        }

        binding.myKeyboard.setOnKeyListener { view, keyCode, event ->
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    when (event.keyCode) {
                        // 音量减小键,对应 keycode 值：25
                        // Volume down key; its keycode is 25.
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
                        // Volume up key; its keycode is 24.
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

        // 单位：字节
        // Unit: bytes.
        val freeSpace = FileSizeUtil.getSdCardAvailableBytes()
        val fileSize = FileSizeUtil.formatFileSize(freeSpace.toDouble())
        Log.d(TAG, "----剩余存储空间：$fileSize")
        val SAFE_MARGIN = 1000L * 1024 * 1024  // 1000MB
        if (freeSpace < SAFE_MARGIN) {
            // 存储空间不足，不允许继续录像
            // Do not continue recording when storage space is insufficient.
        }
        initSDK()

        directionTracker = HeadPoseTracker(this) { azimuth ->
            // yaw：-180° ~ +180°
            // 0 = 启动时朝向 偏航角
            // Zero is the yaw angle corresponding to the orientation at startup.
        }
        directionTracker.start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 优先将事件分发给keyMark视图
        // Dispatch the event to the keyMark view first.
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
            // 只处理有效菜单项点击
            // Handle only clicks on valid menu items.
            if (position < endIndex - startIndex) {
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
                // 离线语音指令,当说开始检查将会打印开始检查，推荐3-5个词，不要有叠音
                // Offline voice command: saying "start inspection" logs the command; use 3-5 words and avoid repeated syllables.
                jdVoiceAction = VoiceAction("开始检查", "kai shi jian cha", object : IVoiceCallback.Stub() {
                    override fun onVoiceTriggered() {
                        Log.e(TAG, "开始检查")
                    }
                })
                GlassSdk.getGlassOfflineCmdService()?.add(jdVoiceAction)
                Log.d(TAG, "---眼镜SN号=${GlassSdk.getGlassDeviceService()?.serialNumber}")
                Log.d(TAG, "---电量值=${GlassSdk.getGlassDeviceService()?.deviceStatusInfo?.powerValue}")
                // 离线语音指令, 推荐3-5个词，不要有叠音
                // For offline voice commands, use 3-5 words and avoid repeated syllables.
                snowVoiceAction = VoiceAction("应急照明", "ying ji zhao ming", object : IVoiceCallback.Stub() {
                    override fun onVoiceTriggered() {
                        Log.e(TAG, "应急照明")
                    }
                })
                GlassSdk.getGlassOfflineCmdService()?.add(snowVoiceAction)
                // 配置相机指示灯：true 开启，false 关闭；修改后需重启眼镜生效。
                // Configure the camera indicator: true enables it and false disables it; restart the glasses for changes to take effect.
                GlassSdk.getGlassDeviceService()?.setCameraLedEnable(false)
                Log.e(DeviceUtil.TAG, "----是否带上眼镜： $isGlassTackOn")
                Log.e(DeviceUtil.TAG, "----是否折叠眼镜腿： $isGlassLegFold")

            } else {
                Log.d(TAG, "SDK 初始化失败")
            }
        }
        GlobalData.btConnectState.drop(1).collect(lifecycleScope) { connectState ->
            if (connectState) {
                binding.tvBluetoothStatus.text = demoString(R.string.s_bt_connected)
            } else {
                binding.tvBluetoothStatus.text = demoString(R.string.s_bt_idle)
            }
        }
        GlobalData.p2pConnectState.drop(1).collect(lifecycleScope) { connectState ->
            if (connectState) {
                binding.tvp2pStatus.text = demoString(R.string.s_p2p_connected)
            } else {
                binding.tvp2pStatus.text = demoString(R.string.s_p2p_idle)
            }
        }
        GlobalData.ringConnectState.drop(1).collect(lifecycleScope) { connectState ->
            if (connectState) {
                binding.ringStatus.text = demoString(R.string.s_ring_connected)
            } else {
                binding.ringStatus.text = demoString(R.string.s_ring_idle)
            }
        }
        binding.workHomeRecyclerLaunch.run {
            // 使用水平方向的LinearLayoutManager排列首页菜单
            // Arrange the home menu using a horizontal LinearLayoutManager.
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false).apply {
                // 关闭平滑滚动条计算
                // Disable smooth scrollbar calculations.
                isSmoothScrollbarEnabled = false
            }
            // 禁用所有滚动和越界效果
            // Disable all scrolling and overscroll effects.
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // 设置Adapter（确保Item宽度固定为1/3屏幕）
            // Set the adapter, ensuring that each item occupies one-third of the screen width.
            adapter = HomeAdapter(context).also {
                workHomeAdapter = it
            }
        }

    }

    /**
     * 视频录制回调
     * Video recording callback.
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
        GlassSdk.unregisterLeqiInterceptor()
        GlassSdkUtils.destroySdk()
    }

    private fun initIconData() {
        menuList.run {
            clear()
            add(
                MenuItem(
                    demoString(R.string.demo_send_messages),
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
                    demoString(R.string.demo_receive_messages),
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
                    demoString(R.string.demo_sdk_photo_video),
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
                    demoString(R.string.demo_device_tools),
                    MenuConfigType.MenuInfoType.DEVICE_TOOLS,
                    R.drawable.icon_device_tools,
                    R.drawable.icon_device_tools,
                    R.drawable.home_bg,
                    R.drawable.home_bg_focus,
                    false
                )
            )
            add(
                MenuItem(
                    demoString(R.string.demo_face_detection),
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
                    demoString(R.string.item_plate_recog),
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
                    demoString(R.string.demo_standalone_asr_tts),
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
     * Display the specified page.
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
                // 只处理有效菜单项点击
                // Handle only clicks on valid menu items.
                if (position < endIndex - startIndex) {
                    jump2NextScene(position)
                }
            }
        }
    }

    /**
     * 翻到上一页
     * Go to the previous page.
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
     * Go to the next page.
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
     * Navigate to the next scene.
     */
    private fun jump2NextScene(position: Int) {
        val actualPosition = (currentPageNum - 1) * showMenuMax + position
        Log.d(TAG, "jump2NextScene ${menuList[actualPosition].menuType}")
        if (actualPosition >= menuList.size) return
        when (menuList[actualPosition].menuType) {
            MenuConfigType.MenuInfoType.SEND_MSG -> {
                startActivity(Intent(this, SendMessageActivity::class.java))
            }

            MenuConfigType.MenuInfoType.RECEIVE_MSG -> {
                startActivity(Intent(this, MessageReceiveActivity::class.java))
            }

            MenuConfigType.MenuInfoType.SDK_TAKE_PHOTO -> {
                startActivity(Intent(this, SdkMediaActivity::class.java))
            }

            MenuConfigType.MenuInfoType.DEVICE_TOOLS -> {
                startActivity(Intent(this, DeviceToolsActivity::class.java))
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
    // Create the broadcast receiver.
    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return

            when (action) {
                // 单击眼镜腿物理按键
                // Single press of the physical temple button.
                MyApplication.ACTION_BUTTON_CLICK -> {
                    Log.d(TAG, "-----眼镜腿单击")
                    Toast.makeText(this@HomeActivity, demoString(R.string.demo_temple_button_single_press), Toast.LENGTH_SHORT).show()
                    // 消费单击事件，避免系统继续分发该广播。
                    // Consume the single-press event so the system does not continue dispatching the broadcast.
                    abortBroadcast()
                }
                // 双击眼镜腿物理按键
                // Double press of the physical temple button.
                MyApplication.ACTION_BUTTON_DOUBLE_CLICK -> {
                    Toast.makeText(this@HomeActivity, demoString(R.string.demo_temple_button_double_press), Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "-----眼镜腿双击")
                    // 消费双击事件，避免系统继续分发该广播。
                    // Consume the double-press event so the system does not continue dispatching the broadcast.
                    abortBroadcast()
                }
                // 非折叠状态，长按镜腿物理按键1秒
                // With the temples unfolded, hold the physical temple button for one second.
                // 系统可直接发送长按事件。
                // The system can send a long-press event directly.
                MyApplication.ACTION_LONG_PRESS -> {
                    Toast.makeText(this@HomeActivity, demoString(R.string.demo_temple_button_long_press), Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "-----眼镜腿长按")
                }
                // 同时演示通过按下/抬起事件自行识别 3 秒长按；业务接入时可任选一种方案。
                // Also demonstrate detecting a three-second long press from press/release events; applications can choose either approach.
                MyApplication.ACTION_BUTTON_DOWN -> {
                    mHandler.postDelayed(mRunnable, 3000)
                    Log.d(TAG, "ACTION_BUTTON_DOWN received")
                }
                // 抬起时取消尚未触发的长按任务。
                // On release, cancel any pending long-press task that has not fired.
                MyApplication.ACTION_BUTTON_UP -> {
                    mHandler.removeCallbacks(mRunnable)
                    Log.d(TAG, "ACTION_BUTTON_UP received")
                }

                MyApplication.ACTION_TAKE_STATUS -> {
                    /**
                     * 1是佩戴，0是摘下；
                     * 1 means the glasses are being worn; 0 means they have been removed.
                     */
                    if ("1" == intent.extras?.getString("glasses_take_state")) {
                        Toast.makeText(this@HomeActivity, demoString(R.string.demo_glasses_put_on), Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "-----已戴上眼镜")
                    } else if ("0" == intent.extras?.getString("glasses_take_state")) {
                        Toast.makeText(this@HomeActivity, demoString(R.string.demo_glasses_taken_off), Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "-----已摘下眼镜")
                    }
                }

                MyApplication.ACTION_LEG_STATUS -> {
                    /**
                     * 1是展开，0是折叠；
                     * 1 means the temples are unfolded; 0 means they are folded.
                     */
                    if ("1" == intent.extras?.getString("glasses_leg_state")) {
                        Toast.makeText(this@HomeActivity, demoString(R.string.demo_temples_unfolded), Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "-----眼镜腿已展开")
                    } else if ("0" == intent.extras?.getString("glasses_leg_state")) {
                        Toast.makeText(this@HomeActivity, demoString(R.string.demo_temples_folded), Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "-----眼镜腿已折叠")
                    }
                }
            }
        }
    }

    // 设置广播过滤器
    // Set the broadcast filter.
    private val intentFilter = IntentFilter().apply {
        addAction(MyApplication.ACTION_BUTTON_CLICK)
        addAction(MyApplication.ACTION_BUTTON_DOUBLE_CLICK)
        addAction(MyApplication.ACTION_LONG_PRESS)
        addAction(MyApplication.ACTION_BUTTON_DOWN)
        addAction(MyApplication.ACTION_BUTTON_UP)
        addAction(MyApplication.ACTION_TAKE_STATUS)
        addAction(MyApplication.ACTION_LEG_STATUS)
        // 设置高优先级确保优先接收广播
        // Set a high priority to receive the broadcast first.
        priority = 100
    }

}
