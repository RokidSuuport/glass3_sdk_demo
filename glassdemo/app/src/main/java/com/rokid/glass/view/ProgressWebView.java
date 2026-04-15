package com.rokid.glass.view;

import static com.rokid.glass.utils.ExtendUtilKt.dp2px;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.appcompat.app.AlertDialog;

import com.rokid.glesse.R;


public class ProgressWebView extends WebView {

    private ProgressBar mProgressBar;  //进度条
    private WebChromeClient.CustomViewCallback mCustomViewCallback;

    public ProgressWebView(Context context) {
        this(context, null);
    }

    public ProgressWebView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        initView(context);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initView(Context context) {
        //创建进度条
        mProgressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        //设置加载进度条的高度
        //进度条的高度，默认10px
        int progressHeight = 4;
        mProgressBar.setLayoutParams(new LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp2px(progressHeight), 0, 0));

        //进度条背景图
        GradientDrawable background = new GradientDrawable();
        background.setColor(getResources().getColor(android.R.color.white));
        background.setCornerRadius(dp2px(1));

        GradientDrawable gradientDrawable = new GradientDrawable();
        gradientDrawable.setCornerRadius(dp2px(1));
        gradientDrawable.setColor(getResources().getColor(R.color.green_normal_50));
        //进度颜色图
        ClipDrawable progress = new ClipDrawable(gradientDrawable, Gravity.START, ClipDrawable.HORIZONTAL);

        LayerDrawable layerDrawable = new LayerDrawable(new Drawable[]{background, progress});
        mProgressBar.setProgressDrawable(layerDrawable); //设置进度条图层

        //添加水平进度条到WebView
        addView(mProgressBar);

        WebSettings webSettings = getSettings();

        //开启js脚本支持
        webSettings.setJavaScriptEnabled(true);

        webSettings.setJavaScriptCanOpenWindowsAutomatically(true); //支持通过JS打开新窗口

        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT); //只要本地有，无论是否过期，或者no-cache，都使用缓存中的数据。
        webSettings.setDomStorageEnabled(true);
        //缓存模式如下：
        //LOAD_CACHE_ONLY: 不使用网络，只读取本地缓存数据
        //LOAD_DEFAULT: （默认）根据cache-control决定是否从网络上取数据。
        //LOAD_NO_CACHE: 不使用缓存，只从网络获取数据.
        //LOAD_CACHE_ELSE_NETWORK，只要本地有，无论是否过期，或者no-cache，都使用缓存中的数据。

        //适配手机大小
        //设置自适应屏幕，两者合用
        webSettings.setUseWideViewPort(true); //将图片调整到适合WebView的大小
        webSettings.setLoadWithOverviewMode(true); // 缩放至屏幕的大小

        //缩放操作
        webSettings.setSupportZoom(true); //支持缩放，默认为true。是下面那个的前提。
        webSettings.setBuiltInZoomControls(true); //设置内置的缩放控件。若为false，则该WebView不可缩放
        webSettings.setDisplayZoomControls(false); //隐藏原生的缩放控件

        //其他细节操作
        webSettings.setAllowFileAccess(true); //设置可以访问文件

        webSettings.setLoadsImagesAutomatically(true); //支持自动加载图片

        webSettings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.SINGLE_COLUMN);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        setWebChromeClient(new WVChromeClient());
        setWebViewClient(new WVClient());
    }


    //进度显示
    private class WVChromeClient extends WebChromeClient {

        //获得网页的加载进度并显示
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (newProgress >= 100) {
                mProgressBar.setVisibility(GONE);
            } else {
                if (mProgressBar.getVisibility() == GONE)
                    mProgressBar.setVisibility(VISIBLE);
                mProgressBar.setProgress(newProgress);
            }

            if (mWebViewListener != null) {
                mWebViewListener.onProgressChange(view, newProgress);
            }
            super.onProgressChanged(view, newProgress);
        }

        //支持javascript的警告框
        @Override
        public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
            new AlertDialog.Builder(getContext())
                    .setTitle("")
                    .setMessage(message)
                    .setPositiveButton("OK", (dialog, which) -> result.confirm())
                    .setCancelable(false)
                    .show();
            return true;
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            super.onReceivedTitle(view, title);
            if (mWebViewListener != null) {
                mWebViewListener.onSetTitle(title);
            }
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            super.onShowCustomView(view, callback);
            if (null == viewFullScreenCallback) {
                return;
            }
            viewFullScreenCallback.onShowCustomView(view, callback);
            mCustomViewCallback = callback;
        }

        @Override
        public void onHideCustomView() {
            super.onHideCustomView();
            if (null == viewFullScreenCallback) {
                return;
            }
            viewFullScreenCallback.onHideCustomView();
            if (mCustomViewCallback == null) {
                return;
            }
            try {
                mCustomViewCallback.onCustomViewHidden();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

    }

    private class WVClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            //在使得打开网页时不调用系统浏览器， 而是在本WebView中显示
            if (url == null) return false;
            try {
                if (url.startsWith("weixin://") //微信
                        || url.startsWith("alipays://") //支付宝
                        || url.startsWith("mailto://") //邮件
                        || url.startsWith("tel://")//电话
                        || url.startsWith("dianping://")//大众点评
                    //其他自定义的scheme
                ) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    mProgressBar.getContext().startActivity(intent);
                    return true;
                }
            } catch (Exception e) { //防止crash (如果手机上没有安装处理某个scheme开头的url的APP, 会导致crash)
                return true;//没有安装该app时，返回true，表示拦截自定义链接，但不跳转，避免弹出上面的错误页面
            }
            view.loadUrl(url);
            return true;
        }

        //webView默认是不处理https请求的，页面显示空白，需要进行如下设置：
        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.proceed();    //表示等待证书响应
            // handler.cancel();      //表示挂起连接，为默认方式
            // handler.handleMessage(null);    //可做其他处理
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            mProgressBar.setVisibility(GONE);
            if (mWebViewListener != null) {
                mWebViewListener.onPageFinish(view);
            }
        }

        //步骤1：写一个html文件（error_handle.html），用于出错时展示给用户看的提示页面
        //步骤2：将该html文件放置到代码根目录的assets文件夹下
        //步骤3：复写WebViewClient的onRecievedError方法
        //该方法传回了错误码，根据错误类型可以进行不同的错误分类处理
        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            switch (errorCode) {
                case 404:
//                    view.loadUrl("file:///android_assets/error_handle.html");
                    break;
            }
        }

    }

    private OnWebViewListener mWebViewListener;
    private ViewFullScreenCallback viewFullScreenCallback;

    public void setOnWebViewListener(OnWebViewListener listener) {
        this.mWebViewListener = listener;
    }

    //进度回调接口
    public interface OnWebViewListener {
        void onProgressChange(WebView view, int newProgress);

        void onPageFinish(WebView view);

        void onSetTitle(String title);
    }

    public interface ViewFullScreenCallback {
        void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback);

        void onHideCustomView();
    }

    public void setOnViewFullScreenCallback(ViewFullScreenCallback listener) {
        this.viewFullScreenCallback = listener;
    }

}
