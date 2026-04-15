package com.rokid.glass

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.ProgressBar
import com.rokid.glass.base.BaseActivity
import com.rokid.glesse.R

class TestWebViewActivity : BaseActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    companion object {
        private const val EXTRA_URL = "extra_url"

        fun start(context: Context, url: String) {
            val intent = android.content.Intent(context, TestWebViewActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_network)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        val url = intent.getStringExtra(EXTRA_URL) ?: "https://www.baidu.com"
        setupWebView(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(url: String) {
        val webSettings = webView.settings
        // 基本设置
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false
        webSettings.loadsImagesAutomatically = true //支持自动加载图片
        webSettings.javaScriptCanOpenWindowsAutomatically = true
        webSettings.allowFileAccess = true
        // 自适应屏幕
        webSettings.useWideViewPort = true
        webSettings.loadWithOverviewMode = true
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // 加载URL
        webView.loadUrl(url)
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
            stopLoading()
            webChromeClient = null
            destroy()
        }
        super.onDestroy()
    }
}