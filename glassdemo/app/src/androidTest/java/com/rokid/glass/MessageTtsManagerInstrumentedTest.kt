package com.rokid.glass

import android.content.Intent
import android.media.AudioManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rokid.glass.speech.MessageTtsController
import com.rokid.glass.utils.GlassSdkUtils
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.tts.TtsSessionCallback
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** 在消息实际页面验证 Manager 播报、退出取消和监听清理。
 * Verify Manager playback, exit cancellation and listener cleanup in the actual message page.
 */
@RunWith(AndroidJUnit4::class)
class MessageTtsManagerInstrumentedTest {
    @Test fun leavingMessagePageCancelsSpeechAndDetachesBothManagerListeners() = verifyPlayback(false)

    @Test fun onlineSpeechCompletesAndPageExitCancelsPlaybackWithoutRetainingListeners() = verifyPlayback(true)

    private fun verifyPlayback(isOnline: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // 真机需保持唤醒，避免系统先触发 onStop 使退出清理掩盖注册检查。
        // Wake the device so system-driven onStop does not invalidate the foreground precondition.
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        val initialized = !GlassSdk.isReady()
        if (initialized) instrumentation.runOnMainSync { GlassSdkUtils.initSdk() }
        await("SDK connection") { GlassSdk.isReady() && if (isOnline) GlassSdk.getGlassTtsService() != null else GlassSdk.getGlassOfflineTtsService() != null }
        val online = GlassSdk.getGlassTtsManager()
        val offline = GlassSdk.getGlassOfflineTtsManager()
        val audio = instrumentation.targetContext.getSystemService(AudioManager::class.java)
        val events = CopyOnWriteArrayList<Pair<Long, Int>>()
        val observer = object : TtsSessionCallback() {
            override fun onStateChanged(sessionId: Long, state: Int, errorCode: Int, errorMessage: String?) {
                events.add(sessionId to state)
                android.util.Log.i("TtsManagerDeviceTest", "online=$isOnline session=$sessionId state=$state error=$errorCode message=$errorMessage")
            }
        }
        if (isOnline) online.addListener(observer) else offline.addListener(observer)
        val onlineBefore = listeners(online)
        val offlineBefore = listeners(offline)
        var worker: ExecutorService? = null
        var controller: MessageTtsController? = null
        try {
            val intent = Intent(instrumentation.targetContext, SendMessageActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<SendMessageActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    assertEquals("The actual page must be foreground", Lifecycle.State.RESUMED, activity.lifecycle.currentState)
                }
                await("both page listeners") {
                    listeners(online).size == onlineBefore.size + 1 && listeners(offline).size == offlineBefore.size + 1
                }
                scenario.onActivity { activity ->
                    val getter = activity.javaClass.getDeclaredMethod("getMessageTts").apply { isAccessible = true }
                    controller = getter.invoke(activity) as MessageTtsController
                    worker = controller!!.javaClass.getDeclaredField("worker").apply { isAccessible = true }
                        .get(controller) as ExecutorService
                    if (isOnline) controller!!.speak(true, "在线语音播报验证，现在开始播放。", 1)
                }
                if (isOnline) {
                    await("online speech completes successfully: $events") {
                        events.any { it.first > 0 && it.second == 2 }
                    }
                    assertTrue("Online completion must include a real start", events.any { it.second == 1 })
                }
                if (isOnline) await("audio output returns idle after completion") { !audio.isMusicActive }
                val previousIds = events.map { it.first }.toSet()
                scenario.onActivity {
                    controller!!.speak(isOnline,
                        "这是使用管理器进行的语音播报，退出页面后应该停止播放。".repeat(if (isOnline) 3 else 20), 1)
                }
                await("speech starts before exiting: $events") {
                    events.any { it.first > 0 && it.first !in previousIds && it.second == 1 }
                }
                val id = events.first { it.first > 0 && it.first !in previousIds && it.second == 1 }.first
                if (isOnline) await("online audio is actively playing before exit") { audio.isMusicActive }
                assertFalse("Exit must interrupt ongoing playback, not an already finished session",
                    events.any { it.first == id && it.second in listOf(2, 3, 4) })
                scenario.moveToState(Lifecycle.State.CREATED)
                await("speech canceled on exit") { events.any { it.first == id && it.second == 3 } }
                if (isOnline) await("online audio output stops after exit") { !audio.isMusicActive }
                await("both page listeners removed") {
                    listeners(online) == onlineBefore && listeners(offline) == offlineBefore
                }
                scenario.moveToState(Lifecycle.State.RESUMED)
                await("listeners reattached once") {
                    listeners(online).size == onlineBefore.size + 1 && listeners(offline).size == offlineBefore.size + 1
                }
            }
            assertTrue("Page worker must finish cleanup before terminating", worker!!.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(onlineBefore, listeners(online))
            assertEquals(offlineBefore, listeners(offline))
        } finally {
            if (isOnline) {
                online.cancelAll()
                online.removeListener(observer)
            } else {
                offline.cancelAll()
                offline.removeListener(observer)
            }
            if (initialized) instrumentation.runOnMainSync { GlassSdkUtils.destroySdk() }
        }
    }

    private fun listeners(manager: Any): Set<*> =
        (manager.javaClass.getDeclaredField("globalCallbacks").apply { isAccessible = true }
            .get(manager) as Set<*>).toSet()

    private fun await(message: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < until) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue(message, condition())
    }
}
