package com.rokid.glass

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import com.rokid.glass.base.BaseActivity
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glesse.R
import kotlin.math.abs

/**
 * 本页面用于验证 AR 眼镜触摸板与 WebView 的交互。
 *
 * 主要能力：
 * 1. 使用本地 HTML，不访问网络，便于在眼镜端稳定测试。
 * 2. 触摸板前后轻滑：切换上一个/下一个卡片，并把目标卡片滚到合适位置。
 * 3. 触摸板点击：触发当前高亮卡片的点击态。
 * 4. WebView 首帧黑底 + 延迟显示，避免单绿色 AR 眼镜上出现白色闪屏。
 *
 * 设计说明：
 * - 不直接使用 WebView.scrollBy() 滚动网页内容，因为部分眼镜系统 WebView 上
 *   原生 scrollY 可能不等于网页 document 的 scrollTop，导致页面看起来不动。
 * - Android 侧统一通过 evaluateJavascript 调用网页内部函数，让网页自己滚动和维护高亮态。
 */
class TestWebViewActivity : BaseActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    // 一次触摸手势的起点，用于判断 ACTION_UP 时是点击还是滑动。
    private var touchDownX = 0f
    private var touchDownY = 0f

    // 上一次 MOVE 的坐标，用于计算连续 MOVE 的增量滚动距离。
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // 手势开始时间和移动统计，只用于 Logcat 调试输出。
    private var touchDownTime = 0L
    private var moveCount = 0
    private var gestureScrollY = 0

    // 页面完成加载后才允许执行网页滚动/点击 JS，避免 JS 函数还未注册。
    private var pageFinished = false

    companion object {
        private const val TAG = "TestWebViewActivity"
        private const val EXTRA_URL = "extra_url"

        // 抬手时位移小于该阈值，认为是触摸板点击。
        private const val CLICK_MAX_DISTANCE_PX = 12f

        // 真实 MotionEvent MOVE 转成网页滚动距离的倍率。
        // 目前前后轻滑主要走 DPAD 按键路径，这个值用于有连续触摸事件的设备。
        private const val TOUCH_SCROLL_SCALE = 1.4f

        // DPAD_UP/DOWN 作为普通滚动兜底时的步长。
        private const val KEY_SCROLL_STEP_PX = 150

        // 选中上/下一个卡片时，网页端 requestAnimationFrame 动画时长。
        private const val KEY_SCROLL_DURATION_MS = 260

        /**
         * 保留 url 参数是为了兼容旧调用方；当前 Activity 固定加载本地 HTML。
         */
        fun start(context: Context, url: String = "") {
            val intent = Intent(context, TestWebViewActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 单绿色 AR 眼镜上白底闪屏非常明显，所以在 setContentView 前就把窗口刷成黑色。
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.decorView.setBackgroundColor(Color.BLACK)
        setContentView(R.layout.activity_test_network)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webSettings = webView.settings

        // 本地 HTML 中需要执行滚动/选中 JS，所以必须打开 JavaScript。
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true

        // 测试页完全本地生成，不需要缓存，避免调试时旧页面残留。
        webSettings.cacheMode = WebSettings.LOAD_NO_CACHE

        // 关闭缩放相关能力，避免触摸板手势被 WebView 当成缩放/控件操作。
        webSettings.setSupportZoom(false)
        webSettings.builtInZoomControls = false
        webSettings.displayZoomControls = false
        webSettings.loadsImagesAutomatically = true //支持自动加载图片
        webSettings.javaScriptCanOpenWindowsAutomatically = false
        webSettings.allowFileAccess = false

        // 自适应眼镜屏幕尺寸。
        webSettings.useWideViewPort = true
        webSettings.loadWithOverviewMode = true
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // 让 WebView 获取焦点，才能收到 DPAD_LEFT / DPAD_RIGHT / ENTER 等按键。
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        // 防闪屏：WebView 自身也设置黑底，并隐藏滚动条/越界效果。
        webView.setBackgroundColor(Color.BLACK)
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.requestFocus()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                // 首帧内容已经可见时再显示 WebView，减少加载期间的白色/透明闪屏。
                showWebView()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // 页面完成后，网页里的 __glassXXX 函数已经注册，可以执行滚动和点击逻辑。
                pageFinished = true
                showWebView()
                evaluateScrollInfo("页面加载完成")
            }
        }

        // 加载本地 HTML，不访问网络。
        webView.loadDataWithBaseURL(
            "https://local.touchpad/",
            buildLocalHtml(),
            "text/html",
            "UTF-8",
            null
        )

        // 某些系统 WebView 回调不稳定，兜底显示，避免一直 invisible 造成黑屏。
        webView.postDelayed({ showWebView() }, 800)
        Log.i(TAG, "本地触摸板事件测试页已加载，日志只输出到 Logcat")
    }

    private fun showWebView() {
        if (::webView.isInitialized && webView.visibility != View.VISIBLE) {
            webView.visibility = View.VISIBLE
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        event?.let {
            when (it.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 记录手势起点和上一次 MOVE 坐标。
                    touchDownX = it.x
                    touchDownY = it.y
                    lastTouchX = it.x
                    lastTouchY = it.y
                    touchDownTime = it.eventTime
                    moveCount = 0
                    gestureScrollY = 0
                    Log.i(TAG, "触摸按下 down=(${it.x.toInt()}, ${it.y.toInt()}) pointer=${it.pointerCount}")
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    // 如果设备真的上报连续触摸 MOVE，就把 MOVE 增量转换为网页滚动。
                    moveCount++
                    scrollWebViewByTouch(it)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    logTouchFinish("触摸抬起", it)

                    // 位移很小认为是点击，点击当前网页里被高亮的卡片。
                    if (isTouchClick(it)) {
                        clickActiveCard("触摸板点击")
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    logTouchFinish("触摸取消", it)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            Log.i(TAG, "按键按下 keyCode=${event.keyCode} name=${KeyEvent.keyCodeToString(event.keyCode)}")
            when (event.keyCode) {
                // 眼镜触摸板前后轻滑一般会映射成 DPAD_LEFT / DPAD_RIGHT。
                // 这里不按固定距离滚动，而是稳定切换上/下一个卡片。
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    selectSiblingCard("向后轻滑/DPAD_LEFT", -1)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    selectSiblingCard("向前轻滑/DPAD_RIGHT", 1)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // 如需上下键按距离滚动，可恢复下面代码。
//                    scrollPageByKey("DPAD_DOWN", KEY_SCROLL_STEP_PX)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    // 如需上下键按距离滚动，可恢复下面代码。
//                    scrollPageByKey("DPAD_UP", -KEY_SCROLL_STEP_PX)
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    // 中键/回车点击当前高亮卡片。
                    clickActiveCard("按键点击/${KeyEvent.keyCodeToString(event.keyCode)}")
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        Log.i(TAG, "识别事件 ${glassKeyName(keyEvent)}")

        // BaseActivity 识别出的触摸板/中键单击，也映射到当前卡片点击。
        if (keyEvent == GlassKeyEvent.KEYCODE_CLICK) {
            clickActiveCard("GlassKeyEvent点击")
            return true
        }
        return super.onGlassKeyEvent(keyEvent)
    }

    // 处理返回键
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.apply {
            // 主动释放 WebView，避免 Activity 销毁后仍持有页面/JS 上下文。
            stopLoading()
            webChromeClient = null
            destroy()
        }
        super.onDestroy()
    }

    private fun scrollWebViewByTouch(event: MotionEvent) {
        val dx = event.x - lastTouchX
        val dy = event.y - lastTouchY
        lastTouchX = event.x
        lastTouchY = event.y

        // 取横向/纵向中更明显的那个方向作为滚动来源。
        // 取负号是为了让手指/触摸板滑动方向更接近自然滚动体验。
        val dominantDelta = if (abs(dx) >= abs(dy)) -dx else -dy
        val scrollY = (dominantDelta * TOUCH_SCROLL_SCALE).toInt()
        if (scrollY == 0) return

        scrollPageByTouch(scrollY)
        gestureScrollY += scrollY
    }

    private fun scrollPageByTouch(deltaY: Int) {
        scrollPageBy(deltaY, smooth = false, reason = "触摸板MOVE")
    }

    private fun scrollPageByKey(reason: String, deltaY: Int) {
        scrollPageBy(deltaY, smooth = true, reason = reason)
    }

    private fun selectSiblingCard(reason: String, step: Int) {
        if (!pageFinished) {
            Log.i(TAG, "页面未加载完成，忽略选中 reason=$reason step=$step")
            return
        }

        // step = 1 选下一个；step = -1 选上一个。
        // 网页端负责：清理旧 active/clicked、设置新 active、滚动到目标卡片。
        val script = """
            window.__glassSelectSibling($step, $KEY_SCROLL_DURATION_MS);
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            Log.i(TAG, "网页选中 reason=$reason result=$result")
        }
    }

    private fun scrollPageBy(deltaY: Int, smooth: Boolean, reason: String) {
        if (!pageFinished) {
            Log.i(TAG, "页面未加载完成，忽略滚动 reason=$reason deltaY=$deltaY")
            return
        }

        val duration = if (smooth) KEY_SCROLL_DURATION_MS else 0

        // 让网页 document 自己滚动，不使用 WebView.scrollBy。
        // 这样可以避免某些系统上 WebView.scrollY 与网页 scrollTop 不一致的问题。
        val script = """
            window.__glassScrollBy($deltaY, $smooth, $duration);
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            Log.i(TAG, "网页滚动 reason=$reason result=$result")
        }
    }

    private fun clickActiveCard(reason: String) {
        if (!pageFinished) {
            Log.i(TAG, "页面未加载完成，忽略点击 reason=$reason")
            return
        }

        // 点击网页当前 active 卡片，结果会返回 index/title/clicked。
        webView.evaluateJavascript("window.__glassClickActive();") { result ->
            Log.i(TAG, "网页点击 reason=$reason result=$result")
        }
    }

    private fun evaluateScrollInfo(reason: String) {
        val script = """
            window.__glassScrollInfo();
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            Log.i(TAG, "$reason scrollInfo=$result")
        }
    }

    private fun logTouchFinish(prefix: String, event: MotionEvent) {
        val dx = event.x - touchDownX
        val dy = event.y - touchDownY
        val duration = event.eventTime - touchDownTime
        val distanceX = abs(dx)
        val distanceY = abs(dy)
        val direction = when {
            distanceX <= CLICK_MAX_DISTANCE_PX && distanceY <= CLICK_MAX_DISTANCE_PX -> "点击"
            distanceX >= distanceY && dx < 0 -> "向前滑"
            distanceX >= distanceY && dx > 0 -> "向后滑"
            dy > 0 -> "向下滑"
            else -> "向上滑"
        }
        Log.i(
            TAG,
            "$prefix $direction start=(${touchDownX.toInt()}, ${touchDownY.toInt()}) " +
                    "end=(${event.x.toInt()}, ${event.y.toInt()}) dx=${dx.toInt()} dy=${dy.toInt()} " +
                    "duration=${duration}ms moves=$moveCount gestureScrollY=$gestureScrollY"
        )
    }

    private fun isTouchClick(event: MotionEvent): Boolean {
        // ACTION_DOWN 到 ACTION_UP 的位移很小，就认为是轻点。
        return abs(event.x - touchDownX) <= CLICK_MAX_DISTANCE_PX &&
                abs(event.y - touchDownY) <= CLICK_MAX_DISTANCE_PX
    }

    private fun glassKeyName(keyEvent: Int): String {
        return when (keyEvent) {
            GlassKeyEvent.KEYCODE_FRONT -> "向前滑 KEYCODE_FRONT($keyEvent)"
            GlassKeyEvent.KEYCODE_BEHIND -> "向后滑 KEYCODE_BEHIND($keyEvent)"
            GlassKeyEvent.KEYCODE_CLICK -> "触摸板/中键单击 KEYCODE_CLICK($keyEvent)"
            GlassKeyEvent.KEYCODE_DOUBLE_CLICK -> "触摸板/中键双击 KEYCODE_DOUBLE_CLICK($keyEvent)"
            GlassKeyEvent.KEYCODE_DOUBLE_FRONT -> "向前双击 KEYCODE_DOUBLE_FRONT($keyEvent)"
            GlassKeyEvent.KEYCODE_DOUBLE_BEHIND -> "向后双击 KEYCODE_DOUBLE_BEHIND($keyEvent)"
            GlassKeyEvent.KEYCODE_BACK -> "返回 KEYCODE_BACK($keyEvent)"
            GlassKeyEvent.KEYCODE_DPAD_DOWN -> "下键 KEYCODE_DPAD_DOWN($keyEvent)"
            else -> "未知事件($keyEvent)"
        }
    }

    private fun buildLocalHtml(): String {
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
              <title>AR 眼镜触摸板事件</title>
              <style>
                /* 所有元素使用 border-box，避免选中态 border-width 变化导致尺寸计算过于意外。 */
                * { box-sizing: border-box; }
                html, body {
                  margin: 0;
                  width: 100%;
                  min-height: 100%;
                }
                /* page 是本地测试页的根容器。 */
                .page {
                  min-height: 100vh;
                  padding: 12px;
                }
                h1 {
                  margin: 0 0 8px;
                  font-size: 22px;
                  color: #7dff88;
                }
                .hint {
                  margin-top: 8px;
                  padding: 8px;
                  border: 1px solid #145c18;
                  border-radius: 8px;
                  font-size: 14px;
                  line-height: 1.45;
                  color: #b8ffc0;
                }
                /* 未选中卡片：背景透明，只保留一条浅色边框。 */
                .card {
                  margin-top: 10px;
                  padding: 12px;
                  border: 1px solid #145c18;
                  border-radius: 8px;
                  background: transparent;
                  color: #d6ffd9;
                  font-size: 15px;
                  line-height: 1.5;
                  transition: border-color 140ms ease, border-width 140ms ease;
                }
                /* 当前焦点卡片：背景仍透明，只加深边框。 */
                .card.active {
                  border-color: #04c700;
                  border-width: 2px;
                  background: transparent;
                }
                /* 点击后的卡片：用更亮边框表示已触发点击。 */
                .card.clicked {
                  border-color: #40ff5e;
                  border-width: 2px;
                  background: transparent;
                }
                .index {
                  display: inline-block;
                  min-width: 34px;
                  color: #7dff88;
                  font-weight: bold;
                }
                /* 底部留白让最后一个卡片也能滚到屏幕中部附近，否则最后项容易被倒数第二项抢焦点。 */
                .bottom-spacer {
                  height: 45vh;
                }
              </style>
              <script>
                (function () {
                  // state 保存网页侧滚动和焦点状态。
                  var state = {
                    // 当前动画的目标 scrollTop；连续滑动时会基于 target 累加，手感更连贯。
                    target: null,
                    // requestAnimationFrame id，用于取消上一段未完成动画。
                    raf: 0,
                    // 当前 active 卡片的 data-index。
                    activeIndex: -1,
                    // 程序主动选择卡片时锁住目标，避免滚动动画过程中被“屏幕中心最近项”覆盖。
                    lockedActiveCard: null
                  };

                  // 获取页面真实滚动元素。不同 WebView 可能是 documentElement 或 body。
                  function scrollElement() {
                    return document.scrollingElement || document.documentElement || document.body;
                  }

                  // 当前网页滚动位置。
                  function currentTop() {
                    var el = scrollElement();
                    return el.scrollTop || window.pageYOffset || 0;
                  }

                  // 最大可滚动位置。
                  function maxTop() {
                    var el = scrollElement();
                    return Math.max(0, el.scrollHeight - window.innerHeight);
                  }

                  // 将滚动目标限制在 0..maxTop，避免越界。
                  function clampTop(top) {
                    return Math.max(0, Math.min(maxTop(), top));
                  }

                  // 设置网页 scrollTop。若没有锁定目标卡片，则顺便按屏幕中心更新 active 卡片。
                  function setTop(top) {
                    window.scrollTo(0, top);
                    if (!state.lockedActiveCard) {
                      updateActiveCard();
                    }
                  }

                  // 统一的动画滚动函数：
                  // - smooth=false 时立即跳到目标位置。
                  // - smooth=true 时使用 requestAnimationFrame + easeOutCubic 做缓出滚动。
                  // - 若 lockedActiveCard 存在，动画结束时保持锁定目标为 active。
                  function scrollToTop(top, smooth, durationMs) {
                    var from = currentTop();
                    var target = clampTop(Number(top || 0));

                    if (state.raf) {
                      cancelAnimationFrame(state.raf);
                      state.raf = 0;
                    }

                    state.target = target;

                    if (!smooth || durationMs <= 0) {
                      setTop(target);
                      state.target = null;
                      if (state.lockedActiveCard) {
                        setActiveCard(state.lockedActiveCard);
                        state.lockedActiveCard = null;
                      } else {
                        updateActiveCard();
                      }
                      return;
                    }

                    var start = performance.now();
                    var distance = target - from;
                    var duration = Math.max(80, Number(durationMs || 260));

                    function step(now) {
                      var t = Math.min(1, (now - start) / duration);
                      var nextTop = from + distance * easeOutCubic(t);
                      setTop(nextTop);

                      if (t < 1 && Math.abs(target - nextTop) > 0.5) {
                        state.raf = requestAnimationFrame(step);
                      } else {
                        setTop(target);
                        state.raf = 0;
                        state.target = null;
                        if (state.lockedActiveCard) {
                          setActiveCard(state.lockedActiveCard);
                          state.lockedActiveCard = null;
                        } else {
                          updateActiveCard();
                        }
                      }
                    }

                    state.raf = requestAnimationFrame(step);
                  }

                  // 三次方缓出曲线，视觉上接近原生列表减速滚动。
                  function easeOutCubic(t) {
                    return 1 - Math.pow(1 - t, 3);
                  }

                  // 暴露给 Android 调试用，返回网页内部滚动信息。
                  window.__glassScrollInfo = function () {
                    var el = scrollElement();
                    return {
                      top: Math.round(currentTop()),
                      max: Math.round(maxTop()),
                      height: Math.round(el.scrollHeight),
                      viewport: Math.round(window.innerHeight)
                    };
                  };

                  // 自动焦点更新：
                  // 找离屏幕垂直中心最近的 .card，设为 active。
                  // 用户连续 MOVE 滚动时使用这个逻辑，焦点会跟着视口移动。
                  function updateActiveCard() {
                    var cards = Array.prototype.slice.call(document.querySelectorAll('.card'));
                    if (!cards.length) {
                      return null;
                    }

                    var centerY = window.innerHeight / 2;
                    var best = cards[0];
                    var bestDistance = Number.MAX_VALUE;

                    cards.forEach(function (card) {
                      var rect = card.getBoundingClientRect();
                      var cardCenter = rect.top + rect.height / 2;
                      var distance = Math.abs(cardCenter - centerY);
                      if (distance < bestDistance) {
                        bestDistance = distance;
                        best = card;
                      }
                    });

                    cards.forEach(function (card) {
                      card.classList.toggle('active', card === best);
                    });

                    state.activeIndex = Number(best.getAttribute('data-index') || -1);
                    return {
                      index: state.activeIndex,
                      title: best.getAttribute('data-title') || '',
                      top: Math.round(currentTop())
                    };
                  }

                  window.__glassUpdateActive = updateActiveCard;

                  // 程序指定某张卡片为 active。
                  // 切换 active 时会清除旧 active/clicked，保证界面只有一个当前项。
                  function setActiveCard(card) {
                    var cards = Array.prototype.slice.call(document.querySelectorAll('.card'));
                    cards.forEach(function (item) {
                      item.classList.remove('active');
                      item.classList.remove('clicked');
                    });
                    card.classList.add('active');
                    state.activeIndex = Number(card.getAttribute('data-index') || -1);
                  }

                  // 计算把指定卡片滚到屏幕中心所需的 scrollTop。
                  function targetTopForCenter(card) {
                    var rect = card.getBoundingClientRect();
                    var from = currentTop();
                    return clampTop(from + rect.top + rect.height / 2 - window.innerHeight / 2);
                  }

                  // Android 调用：轻滑选择上/下一个卡片。
                  // step = 1 表示下一个；step = -1 表示上一个。
                  window.__glassSelectSibling = function (step, durationMs) {
                    var cards = Array.prototype.slice.call(document.querySelectorAll('.card'));
                    if (!cards.length) {
                      return { index: -1, selected: false };
                    }

                    var active = document.querySelector('.card.active');
                    if (!active) {
                      updateActiveCard();
                      active = document.querySelector('.card.active') || cards[0];
                    }

                    var currentIndex = Math.max(0, cards.indexOf(active));
                    var nextIndex = Math.max(0, Math.min(cards.length - 1, currentIndex + Number(step || 0)));
                    var next = cards[nextIndex];
                    var targetTop = targetTopForCenter(next);
                    if (nextIndex === 0) {
                      // 选中第一个卡片时强制回到顶部，避免“居中第一个”导致页面顶部留空。
                      targetTop = 0;
                    }
                    // 锁住目标卡片，防止动画过程中自动中心检测把焦点抢回相邻卡片。
                    state.lockedActiveCard = next;
                    setActiveCard(next);
                    scrollToTop(targetTop, true, durationMs);

                    return {
                      index: Number(next.getAttribute('data-index') || -1),
                      title: next.getAttribute('data-title') || '',
                      selected: true,
                      listIndex: nextIndex,
                      total: cards.length,
                      targetTop: Math.round(targetTop)
                    };
                  };

                  // Android 调用：点击当前 active 卡片。
                  // clicked 只是视觉状态，实际业务可以在这里跳转、提交、发消息等。
                  window.__glassClickActive = function () {
                    var active = document.querySelector('.card.active');
                    if (!active) {
                      updateActiveCard();
                      active = document.querySelector('.card.active');
                    }
                    if (!active) {
                      return { index: -1, clicked: false };
                    }

                    document.querySelectorAll('.card.clicked').forEach(function (card) {
                      card.classList.remove('clicked');
                    });
                    active.classList.add('clicked');

                    return {
                      index: Number(active.getAttribute('data-index') || -1),
                      title: active.getAttribute('data-title') || '',
                      clicked: true,
                      top: Math.round(currentTop())
                    };
                  };

                  // Android 调用：按像素滚动网页内容。
                  // 目前 DPAD 前后轻滑走 __glassSelectSibling；连续 MotionEvent MOVE 会走这里。
                  window.__glassScrollBy = function (deltaY, smooth, durationMs) {
                    var from = currentTop();
                    // 连续滚动时，如果上一段动画还没结束，就基于上一次 target 继续累加。
                    var base = state.target === null ? from : state.target;
                    var target = clampTop(base + Number(deltaY || 0));

                    if (state.raf) {
                      cancelAnimationFrame(state.raf);
                      state.raf = 0;
                    }

                    state.target = target;

                    if (!smooth || durationMs <= 0) {
                      setTop(target);
                      state.target = null;
                      updateActiveCard();
                      return {
                        from: Math.round(from),
                        to: Math.round(target),
                        max: Math.round(maxTop()),
                        deltaY: Math.round(deltaY || 0),
                        animated: false
                      };
                    }

                    var start = performance.now();
                    var startTop = from;
                    var distance = target - startTop;
                    var duration = Math.max(80, Number(durationMs || 260));

                    function step(now) {
                      var t = Math.min(1, (now - start) / duration);
                      var nextTop = startTop + distance * easeOutCubic(t);
                      setTop(nextTop);

                      if (t < 1 && Math.abs(target - nextTop) > 0.5) {
                        state.raf = requestAnimationFrame(step);
                      } else {
                        setTop(target);
                        state.raf = 0;
                        state.target = null;
                        updateActiveCard();
                      }
                    }

                    state.raf = requestAnimationFrame(step);

                    return {
                      from: Math.round(from),
                      to: Math.round(target),
                      max: Math.round(maxTop()),
                      deltaY: Math.round(deltaY || 0),
                      animated: true,
                      duration: duration
                    };
                  };

                  // 用户/系统自然滚动时，节流更新 active 卡片。
                  window.addEventListener('scroll', function () {
                    if (state.scrollTicking) return;
                    state.scrollTicking = true;
                    requestAnimationFrame(function () {
                      state.scrollTicking = false;
                      updateActiveCard();
                    });
                  }, { passive: true });

                  // 页面刚加载时初始化第一个 active 卡片。
                  document.addEventListener('DOMContentLoaded', function () {
                    updateActiveCard();
                  });
                })();
              </script>
            </head>
            <body>
              <div class="page">
                <h1>触摸板滚动测试</h1>
                <div class="hint">
                  本页面完全本地加载，不请求网络。滑动眼镜触摸板时，WebView 会跟随滚动。
                  <br/><br/>
                  过滤命令：adb logcat -s TestWebViewActivity
                </div>
                ${buildDemoCards()}
                <div class="bottom-spacer"></div>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildDemoCards(): String {
        return (1..10).joinToString(separator = "\n") { index ->
            """
                <div class="card" data-index="$index" data-title="本地内容 #$index">
                  <span class="index">#$index</span>
                  这是一段本地网页内容，用于验证 AR 眼镜触摸板滚动。继续滑动触摸板，页面应该像鼠标滚轮或手势一样上下移动。
                </div>
            """.trimIndent()
        }
    }
}
