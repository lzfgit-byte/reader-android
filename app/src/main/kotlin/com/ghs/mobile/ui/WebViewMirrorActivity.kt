package com.ghs.mobile.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.ghs.mobile.core.engine.net.UserAgents
import com.ghs.mobile.core.script.WebViewFallbackHub

/**
 * 显示「正在执行取页任务」的那个 WebView。
 *
 * 不是重新加载页面：直接取 [WebViewFallbackHub.currentView]，把它挂到本窗口，
 * 这样看到的就是任务真实状态（cookie、跳转、挑战页、登录页都在原实例里）。
 */
class WebViewMirrorActivity : ComponentActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val view = WebViewFallbackHub.currentView

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 24, 24, 12)
        }
        bar.addView(
            TextView(this).apply {
                text = if (view != null) "正在取页：$url" else "取页已结束：$url"
                textSize = 11f
                maxLines = 2
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        bar.addView(
            Button(this).apply {
                text = "复制地址"
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                    android.widget.Toast.makeText(this@WebViewMirrorActivity, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
        )
        bar.addView(
            Button(this).apply {
                // 隐藏：关窗口但取页任务继续，需要时再点「显示 webview」
                text = "隐藏"
                setOnClickListener { finish() }
            },
        )
        root.addView(bar)

        val frame = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                setStroke(2, android.graphics.Color.parseColor("#B9A7FF"))
                cornerRadius = 8f
            }
            setPadding(3, 3, 3, 3)
        }
        if (view != null) {
            // 同一个 View 不能有两个父容器：先从原容器摘下
            (view.parent as? ViewGroup)?.removeView(view)
            frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    // 关闭窗口只是不再显示：任务结束后 HttpScriptHost 会自行销毁该 WebView

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
