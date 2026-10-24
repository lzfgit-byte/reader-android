package com.ghs.mobile.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.ghs.mobile.GhsApp
import com.ghs.mobile.core.engine.net.UserAgents
import com.ghs.mobile.core.script.WebViewProxy

/**
 * 把「取页用的 WebView」显示出来。
 *
 * 背景：无头 WebView 取页失败时（403 / 挑战页 / 需要登录），用户看不到页面，
 * 也没法手动完成验证。这个页面用同一套 UA/代理/语言设置重新打开同一个地址，
 * 让用户能看见并操作；关掉后回到应用继续原流程。
 */
class WebViewMirrorActivity : ComponentActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) {
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 24, 24, 12)
        }
        bar.addView(
            TextView(this).apply {
                text = url
                textSize = 11f
                maxLines = 2
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        bar.addView(
            Button(this).apply {
                text = "关闭"
                setOnClickListener { finish() }
            },
        )
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val view = WebView(this)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        // 与取页通道保持一致：桌面 UA + 中文
        view.settings.userAgentString = UserAgents.DESKTOP_CHROME
        view.webViewClient = WebViewClient()
        runCatching { WebViewProxy.apply(GhsApp.instance.proxy.rules.value) }
        view.loadUrl(url, mapOf("Accept-Language" to UserAgents.ACCEPT_LANGUAGE))
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    companion object {
        private const val EXTRA_URL = "url"

        fun start(context: Context, url: String) {
            context.startActivity(
                Intent(context, WebViewMirrorActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
