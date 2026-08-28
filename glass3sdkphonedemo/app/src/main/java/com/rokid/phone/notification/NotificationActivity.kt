package com.rokid.phone.notification

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.promeg.pinyinhelper.Pinyin

import com.rokid.phone.R
import com.rokid.phone.databinding.ActivityNotificationBinding
import com.rokid.phone.notification.adapter.AppInfo
import com.rokid.phone.notification.adapter.AppListItem
import com.rokid.phone.notification.adapter.ApplicationAdapter
import com.rokid.phone.notification.service.MessageNotificationListenerService
import com.rokid.phone.utils.SPUtil
import com.rokid.phone.utils.SpKeyConstant.PREF_KEY_ENABLE_ALL_NOTIFICATION
import com.rokid.phone.utils.SpKeyConstant.PREF_KEY_ENABLE_NOTIFICATION_MESSAGE
import com.rokid.phone.utils.SpKeyConstant.PREF_KEY_ONLY_LOCKSCREEN_NOTIFY
import com.rokid.phone.notification.ui.EnableNotificationDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.text.contains

class NotificationActivity : FragmentActivity() {
    private val mHandler = Handler(Looper.getMainLooper())
    private var appssList = mutableListOf<String>()
    private lateinit var applicationAdapter: ApplicationAdapter
    private lateinit var binding: ActivityNotificationBinding

    companion object {
        const val TAG = "NotificationActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityNotificationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initViews()
    }

    fun initViews() {
        binding.layoutTitle.ivBack.setOnClickListener {
            saveAppSetting()
            this.finish()
        }
        binding.switchEnableNotification.setOnCheckedChangeListener { _, isCheck ->
            Log.d(TAG, "[Notification] PREF_KEY_ENABLE_NOTIFICATION_MESSAGE OnChecked=$isCheck")
            SPUtil.getInstance(this).putBoolean(PREF_KEY_ENABLE_NOTIFICATION_MESSAGE, isCheck)
            updateUI(isCheck)
            saveAppSetting()
        }
        binding.switchLockscreenNotify.setOnCheckedChangeListener { _, isCheck ->
            SPUtil.getInstance(this).putBoolean(PREF_KEY_ONLY_LOCKSCREEN_NOTIFY, isCheck)
        }
        binding.switchEnableAllNotification.setOnCheckedChangeListener(enableAllNotificationListener)
        binding.rlEnableNotification.setOnClickListener {
            Log.d(TAG, "[Notification] setOnClickListener")
            if (!isNotificationPermissionGranted(this)) {
                Toast.makeText(this, getString(R.string.notification_error_toast), Toast.LENGTH_SHORT).show()
            }
            if (!isNotificationListenerEnabled(this) && !isFinishing && !isDestroyed) {
                showEnableNotificationDialog()
            }
        }

//        binding.sideIndexView.setOnIndexTouchListener { letter ->
//            val position = applicationAdapter.getPositionForSection(letter.first())
//            if (position >= 0) {
//                binding.recyclerView.scrollToPosition(position)
//            }
//        }
    }


    override fun onResume() {
        super.onResume()
        Log.d(
            TAG,
            "[Notification] isNotificationPermissionGranted:" + isNotificationPermissionGranted(this) + " isNotificationListenerEnabled:" + isNotificationListenerEnabled(
                this
            )
        )
//        if (!isNotificationPermissionGranted(this)) {
//            SPUtil.getInstance(this).putBoolean(PREF_KEY_ENABLE_NOTIFICATION_MESSAGE, false)
//            binding.switchEnableNotification.isChecked = false
//            binding.switchEnableNotification.isEnabled = false
//            binding.rlEnableNotification.alpha = 0.5f
//            updateUI(false)
//            Toast.makeText(this, getString(R.string.notification_error_toast), Toast.LENGTH_SHORT).show()
//        }
        if (!isNotificationListenerEnabled(this)) {
            SPUtil.getInstance(this).putBoolean(PREF_KEY_ENABLE_NOTIFICATION_MESSAGE, false)
            binding.switchEnableNotification.isChecked = false
            binding.switchEnableNotification.isEnabled = false
            binding.rlEnableNotification.alpha = 0.5f
            updateUI(false)
            mHandler.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    showEnableNotificationDialog()
                }
            }, 500)
        } else {
            binding.rlEnableNotification.alpha = 1.0f
            initNotificationListenerService()
            binding.switchEnableNotification.isEnabled = true
            val isChecked = SPUtil.getInstance(this).getBoolean(PREF_KEY_ENABLE_NOTIFICATION_MESSAGE, false)
            Log.d(TAG, "[Notification] PREF_KEY_ENABLE_NOTIFICATION_MESSAGE isChecked=$isChecked")
            binding.switchEnableNotification.isChecked = isChecked
            updateUI(isChecked)
        }
    }


    private var enableAllNotificationListener = CompoundButton.OnCheckedChangeListener { _, isCheck ->
        Log.d(TAG, "[Notification] switchEnableAllNotification isCheck=$isCheck")
        SPUtil.getInstance(this).putBoolean(PREF_KEY_ENABLE_ALL_NOTIFICATION, isCheck)
        // 所有都全部开启或者全部关闭
        if (::applicationAdapter.isInitialized) {
            applicationAdapter.setEnableAllNotification(isCheck)
            saveAppSetting()
        }
    }


    private fun initNotificationListenerService() {
        val enabledListeners =
            Settings.Secure.getString(baseContext.contentResolver, "enabled_notification_listeners")
        if (enabledListeners?.contains(baseContext.packageName) == true) {
            Log.d(TAG, "[Notification] NotificationActivity: initNotificationListenerService")
            val componentName = ComponentName(baseContext, MessageNotificationListenerService::class.java)
            val pm = baseContext.packageManager

            // 先禁用 Service
            pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )

            // 重新启用 Service
            pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } else {
            Log.d(TAG, ("[Notification] NotificationActivity: not notification listener permission!!"))
        }
    }

    private fun updateUI(isEnable: Boolean) {
        if (isEnable) {
            getInstalledApps()
        } else {
            binding.processLoading.visibility = View.GONE
            binding.recyclerView.visibility = View.GONE
            binding.tvNotificationFilterTips.visibility = View.GONE

            binding.rlEnableAllNotification.visibility = View.GONE
            binding.rlLockscreenNotify.visibility = View.GONE
        }
    }

    private lateinit var enableNotificationDialog: EnableNotificationDialog
    private fun showEnableNotificationDialog() {
        Log.d(TAG, "[Notification] showEnableNotificationDialog")
        if (!::enableNotificationDialog.isInitialized || !enableNotificationDialog.isVisible) {
            enableNotificationDialog = EnableNotificationDialog(
                cancelListener = {
                },
                openListener = {
                    openNotificationAccessSettings(this)
                }
            )

            if (!isFinishing && !enableNotificationDialog.isAdded) {
                enableNotificationDialog.show(supportFragmentManager, "EnableNotificationDialog")
            }
        }
    }


    private fun openNotificationAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }


    private fun saveAppSetting() {
        if (::applicationAdapter.isInitialized) {
            val appsList = applicationAdapter.getAllNotificationAppList()
            Log.d(TAG, "[Notification] appsList=$appsList")
            appsList.forEach {
                Log.e(TAG, it)
            }
            SPUtil.getInstance(this).saveListToPreferences(appsList)
        }
    }


    private fun getInstalledApps() {
        binding.processLoading.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE
        binding.tvNotificationFilterTips.visibility = View.GONE

//        binding.rlLockscreenNotify.visibility = View.VISIBLE
        binding.switchLockscreenNotify.isChecked =
            SPUtil.getInstance(baseContext).getBoolean(PREF_KEY_ONLY_LOCKSCREEN_NOTIFY, false)

        binding.rlEnableAllNotification.visibility = View.GONE
        binding.switchEnableAllNotification.isChecked =
            SPUtil.getInstance(baseContext).getBoolean(PREF_KEY_ENABLE_ALL_NOTIFICATION, false)

        CoroutineScope(Dispatchers.IO).launch {
            val pm: PackageManager = packageManager
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val appsList = SPUtil.getInstance(baseContext).getListFromPreferences()
            val filtered = installedApps.filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            val apps = filtered.map {
                val label = pm.getApplicationLabel(it).toString()
                val icon = pm.getApplicationIcon(it)
                AppInfo(
                    name = label,
                    icon = icon,
                    packageName = it.packageName,
                    isCheck = if (appsList.isEmpty()) false else appsList.contains(it.packageName),
                    firstLetter = getSortLetter(label)
                )
            }

            // 排序 + 分组
            val grouped = apps.groupBy { it.firstLetter }.toSortedMap() // A-Z排序
            val items = mutableListOf<AppListItem>()
            grouped.forEach { (letter, group) ->
                items.add(AppListItem.Header(letter.toString()))
                group.sortedBy { it.name }.forEach { app ->
                    items.add(AppListItem.App(app))
                }
            }

            withContext(Dispatchers.Main) {
                binding.processLoading.visibility = View.GONE
                binding.recyclerView.visibility = View.VISIBLE
                binding.tvNotificationFilterTips.visibility = View.VISIBLE
                binding.rlEnableAllNotification.visibility = View.VISIBLE
                Log.d(TAG, "[Notification] getInstalledApps apps.size = ${apps.size}, save appsList=${appsList}")
//                val applist = apps.filter { app ->
//                    pm.getLaunchIntentForPackage(app.packageName) != null
//                }.map { app ->
//                    AppInfo(
//                        name = pm.getApplicationLabel(app).toString(),
//                        packageName = app.packageName,
//                        icon = pm.getApplicationIcon(app),
//                        isCheck = if (appsList.isEmpty()) false else appsList.contains(app.packageName)
//                    )
//                }.sortedByDescending { appInfo -> // Sorting to prioritize apps in appsList
//                    appInfo.isCheck
//                }

                applicationAdapter = ApplicationAdapter(items) {
                    val isAllEnable = SPUtil.getInstance(baseContext).getBoolean(PREF_KEY_ENABLE_ALL_NOTIFICATION, false)
                    Log.d(TAG, "[Notification] onSwitchChecked isAllEnable=$isAllEnable")
                    if (isAllEnable) {
                        binding.switchEnableAllNotification.setOnCheckedChangeListener(null)
                        binding.switchEnableAllNotification.isChecked = false
                        SPUtil.getInstance(baseContext).putBoolean(PREF_KEY_ENABLE_ALL_NOTIFICATION, false)
                        binding.switchEnableAllNotification.setOnCheckedChangeListener(enableAllNotificationListener)
                    }
                    saveAppSetting()
                }
                binding.recyclerView.adapter = applicationAdapter
                binding.recyclerView.layoutManager = LinearLayoutManager(baseContext)

                // 设置侧边栏点击跳转
//                initSideBar(applicationAdapter.getIndexMap())
            }
        }
    }


    private fun isNotificationPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            Log.d(
                TAG,
                "[Notification] isNotificationPermissionGranted areNotificationsEnabled=${notificationManager.areNotificationsEnabled()}"
            )
            return notificationManager.areNotificationsEnabled()
        }
        return true
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        val packageName = context.packageName
        Log.d(
            TAG,
            "[Notification] isNotificationListenerEnabled packageName=${packageName}, enabledListeners=${enabledListeners}"
        )
        return enabledListeners != null && enabledListeners.contains(packageName)
    }

    private fun getSortLetter(name: String): Char {
        val pinyin = Pinyin.toPinyin(name.first().toString(), "").uppercase()
        val firstChar = pinyin.firstOrNull()
        return if (firstChar != null && firstChar in 'A'..'Z') firstChar else '#'
    }

}
