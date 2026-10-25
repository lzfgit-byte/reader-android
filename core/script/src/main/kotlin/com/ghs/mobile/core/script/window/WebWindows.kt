package com.ghs.mobile.core.script.window

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WebView 窗口注册表 + 对象池。
 *
 * 取代过去"用到就 new 一个 WebView"的写法，把窗口的生命周期收到一处：
 * - [WindowReuse.SINGLETON]：按名字常驻一个（脚本引擎 / 取页 / 网页浏览 / 校验窗）
 * - [WindowReuse.PER_TASK]：用一次建一个
 * - [WindowReuse.POOL]：保活 `poolMin`、封顶 `poolMax`、空闲窗口由回收线程销毁（图片兜底）
 *
 * 线程约定：任何线程都可以调 [acquire] / [release]；创建 WebView 的动作内部切到主线程。
 */
object WebWindows {

    // ── 命名窗口（名字即注册表键） ───────────────────────────────────────────

    const val ENGINE = "engine"
    const val FETCH = "fetch"
    const val BROWSER = "browser"
    const val CHALLENGE = "challenge"
    const val IMAGE = "image"

    /** 配置脚本引擎：常驻，只加载自己的空白页，不需要可显示 */
    val ENGINE_SPEC = WindowSpec(ENGINE, WindowReuse.SINGLETON)

    /** 取页兜底：常驻隐藏窗口，可被「显示 webview」挂到前台，需要 Promise 回传桥 */
    val FETCH_SPEC = WindowSpec(FETCH, WindowReuse.SINGLETON, evalBridge = true)

    /** 网页浏览：可见窗口，离开页面即销毁 */
    val BROWSER_SPEC = WindowSpec(BROWSER, WindowReuse.SINGLETON)

    /**
     * 人机校验：可见窗口，每次校验一个新的（对齐 Electron 的 win_open），结束即销毁。
     *
     * **不伪装桌面 UA**：校验页要判断"这是不是一个真浏览器"，UA 与 JS 侧
     * platform / userAgentData 不一致会被直接判失败（表现为点完验证又刷新回验证页）。
     * 通过后拿到的凭证落在共享 CookieManager 上，取页仍可用桌面 UA。
     */
    val CHALLENGE_SPEC = WindowSpec(CHALLENGE, WindowReuse.PER_TASK, desktopUa = false)

    /** 图片兜底：池化，至少保活 1 个、最多 3 个 */
    val IMAGE_SPEC = WindowSpec(
        IMAGE,
        WindowReuse.POOL,
        poolMin = 1,
        poolMax = 3,
    )

    // ── 内部状态 ───────────────────────────────────────────────────────────

    private lateinit var appContext: Context
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private val singletons = HashMap<String, WebWindow>()
    private val pools = HashMap<String, MutableList<Entry>>()

    @Volatile
    private var trimmer: Thread? = null

    private class Entry(val window: WebWindow) {
        @Volatile
        var free = true
    }

    /** 必须在 `Application.onCreate` 里调用一次 */
    fun initialize(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        if (trimmer == null) {
            trimmer = Thread(
                {
                    while (true) {
                        Thread.sleep(TRIM_INTERVAL_MS)
                        runCatching { trim() }
                    }
                },
                "ghs-webview-trim",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    val isReady: Boolean get() = ::appContext.isInitialized

    /**
     * 取一个窗口。
     *
     * - SINGLETON：优先复用；已被销毁则重建
     * - PER_TASK：每次新建
     * - POOL：优先复用空闲窗口；达到上限且都在忙时最多等 [POOL_WAIT_MS]，超时新建一个临时窗口
     *
     * 注意：创建/销毁都不在锁内做 —— WebView 必须在主线程建，主线程若同时来取窗口，
     * 持锁等主线程会直接死锁。
     */
    fun acquire(spec: WindowSpec): WebWindow {
        check(::appContext.isInitialized) { "WebWindows 未初始化" }
        if (spec.reuse == WindowReuse.POOL) return acquirePooled(spec)
        if (spec.reuse == WindowReuse.PER_TASK) return create(spec)

        singleton(spec.name)?.let { return it }
        val created = create(spec)
        synchronized(lock) {
            val raced = singletons[spec.name]?.takeIf { !it.isClosed }
            if (raced != null) {
                raced
            } else {
                singletons[spec.name] = created
                created
            }
        }.let { winner ->
            if (winner !== created) created.destroy()
            return winner
        }
    }

    private fun singleton(name: String): WebWindow? = synchronized(lock) {
        singletons[name]?.takeIf { !it.isClosed }
    }

    /** 归还窗口：池化窗口标记为空闲，一次性窗口直接销毁，常驻窗口什么也不做 */
    fun release(window: WebWindow) {
        var destroy = false
        synchronized(lock) {
            when (window.spec.reuse) {
                WindowReuse.SINGLETON -> Unit
                WindowReuse.PER_TASK -> destroy = true
                WindowReuse.POOL -> {
                    val entry = pools[window.spec.name]?.firstOrNull { it.window === window }
                    if (entry == null) destroy = true else entry.free = true
                }
            }
            (lock as Object).notifyAll()
        }
        if (destroy) window.destroy()
    }

    /** 按名字取当前实例（「显示 webview」用它拿到正在取页的那个窗口） */
    fun current(name: String): WebWindow? = synchronized(lock) {
        singletons[name]?.takeIf { !it.isClosed }
            ?: pools[name]?.firstOrNull { !it.free }?.window?.takeIf { !it.isClosed }
    }

    /** 关闭并移除某个窗口（窗口自己调用 destroy 时也会走到这里） */
    internal fun forget(window: WebWindow) {
        synchronized(lock) {
            if (singletons[window.spec.name] === window) singletons.remove(window.spec.name)
            pools[window.spec.name]?.removeAll { it.window === window }
            (lock as Object).notifyAll()
        }
    }

    /** 收回空闲窗口（池超过下限时按从旧到新销毁） */
    fun trim() {
        val doomed = mutableListOf<WebWindow>()
        synchronized(lock) {
            pools.forEach { (_, entries) ->
                val min = entries.firstOrNull()?.window?.spec?.poolMin ?: 0
                var index = entries.lastIndex
                while (entries.size > min && index >= 0) {
                    val entry = entries[index]
                    if (entry.free && !entry.window.isClosed) {
                        entries.removeAt(index)
                        doomed += entry.window
                    }
                    index -= 1
                }
            }
        }
        // destroy 会等主线程：必须在锁外做
        doomed.forEach { runCatching { it.destroy() } }
    }

    /** 进程退出 / 会话整体重置时调用 */
    fun destroyAll() {
        val all = synchronized(lock) {
            (singletons.values + pools.values.flatMap { list -> list.map { it.window } }).distinct()
        }
        all.forEach { runCatching { it.destroy() } }
    }

    // ── 内部实现 ───────────────────────────────────────────────────────────

    private fun acquirePooled(spec: WindowSpec): WebWindow {
        var waited = 0L
        while (true) {
            // 1) 有空闲窗口直接拿
            synchronized(lock) {
                val entries = pools.getOrPut(spec.name) { mutableListOf() }
                entries.removeAll { it.window.isClosed }
                entries.firstOrNull { it.free }?.let { entry ->
                    entry.free = false
                    return entry.window
                }
            }
            // 2) 未达上限：锁外新建（并发时可能短暂超限，回收线程会收敛）
            val size = synchronized(lock) { pools[spec.name]?.size ?: 0 }
            if (size < spec.poolMax) {
                val window = create(spec)
                synchronized(lock) {
                    val entries = pools.getOrPut(spec.name) { mutableListOf() }
                    entries += Entry(window).also { it.free = false }
                }
                return window
            }
            // 3) 达上限且全忙：有限等待，超时建临时窗口避免卡死
            if (waited >= POOL_WAIT_MS) return create(spec)
            synchronized(lock) { runCatching { (lock as Object).wait(POOL_POLL_MS) } }
            waited += POOL_POLL_MS
        }
    }

    /** 必须在主线程创建 WebView：Compose 侧调用时本就在主线程，后台线程则切过去等 */
    private fun create(spec: WindowSpec): WebWindow {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return WebWindow(spec, appContext)
        }
        var created: WebWindow? = null
        val latch = CountDownLatch(1)
        main.post {
            try {
                created = WebWindow(spec, appContext)
            } finally {
                latch.countDown()
            }
        }
        latch.await(CREATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return created ?: error("创建 WebView 窗口失败：${spec.name}")
    }

    private const val TRIM_INTERVAL_MS = 5_000L
    private const val POOL_WAIT_MS = 10_000L
    private const val POOL_POLL_MS = 200L
    private const val CREATE_TIMEOUT_MS = 10_000L
}
