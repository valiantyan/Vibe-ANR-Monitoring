package com.valiantyan.vibeanrmonitoring

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * ANR SDK 示例入口，提供全量验收所需的主线程慢消息、消息风暴、忙等和等待类场景。
 */
class MainActivity : AppCompatActivity() {
    // 主线程 Handler，用于构造大量 pending message 的验收场景。
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    // Sync Barrier 泄漏场景，单独封装反射和 token 记录逻辑。
    private val syncBarrierLeakScenario: SyncBarrierLeakScenario by lazy {
        SyncBarrierLeakScenario(context = this)
    }

    /**
     * 初始化 demo 按钮，让手动验收可以直接触发不同 ANR 证据路径。
     */
    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.currentSlowButton).setOnClickListener {
            blockMainThread()
        }
        findViewById<Button>(R.id.messageStormButton).setOnClickListener {
            postMessageStorm()
        }
        findViewById<Button>(R.id.currentBusyButton).setOnClickListener {
            runBusyLoop()
        }
        findViewById<Button>(R.id.binderLikeButton).setOnClickListener {
            waitBinderLikeLock()
        }
        findViewById<Button>(R.id.syncBarrierLeakButton).setOnClickListener {
            runSyncBarrierLeak()
        }
    }

    // 故意阻塞主线程，用于验证 Watchdog 疑似 ANR 捕获链路。
    private fun blockMainThread(): Unit {
        Thread.sleep(6_000L)
    }

    // 快速投递大量主线程消息，用于验证 pending 队列和历史消息窗口。
    private fun postMessageStorm(): Unit {
        repeat(times = 2_000) { index ->
            mainHandler.post {
                val ignoredValue: Int = index * index
                ignoredValue.toString()
            }
        }
        Thread.sleep(6_000L)
    }

    // 主线程持续忙等，用于验收当前消息慢且 CPU 占用较高的场景。
    private fun runBusyLoop(): Unit {
        val endAt: Long = System.currentTimeMillis() + 6_000L
        var ignoredValue: Double = 0.0
        while (System.currentTimeMillis() < endAt) {
            ignoredValue += Math.sqrt(42.0)
        }
        ignoredValue.toString()
    }

    // 模拟同步跨进程调用中的等待窗口，用于手动观察等待类主线程栈证据。
    private fun waitBinderLikeLock(): Unit {
        val lock: Any = Any()
        synchronized(lock) {
            Thread.sleep(6_000L)
        }
    }

    // 泄漏 Sync Barrier，用于验证 nativePollOnce 表象背后的队列根因。
    private fun runSyncBarrierLeak(): Unit {
        syncBarrierLeakScenario.run()
    }
}
