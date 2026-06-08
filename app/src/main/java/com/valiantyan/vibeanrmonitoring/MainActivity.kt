package com.valiantyan.vibeanrmonitoring

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.valiantyan.vibeanrmonitoring.scenario.CurrentSlowInputScenario
import com.valiantyan.vibeanrmonitoring.scenario.MainThreadCpuBusyScenario
import com.valiantyan.vibeanrmonitoring.scenario.MessageStormScenario
import com.valiantyan.vibeanrmonitoring.scenario.SyncBarrierLeakScenario

/**
 * ANR SDK 示例入口，提供全量验收所需的主线程慢消息、消息风暴、忙等和等待类场景。
 */
class MainActivity : AppCompatActivity() {
    // 当前慢消息场景，用独立类承载根因入口，便于 JSON 栈中直接定位。
    private val currentSlowInputScenario: CurrentSlowInputScenario = CurrentSlowInputScenario()

    // 消息风暴场景，用独立类投递大量同类 Pending 消息，便于 JSON 归因为 MESSAGE_STORM。
    private val messageStormScenario: MessageStormScenario = MessageStormScenario()

    // 当前消息 CPU 忙等场景，用独立类承载根因入口，便于和 Thread.sleep 等待类场景区分。
    private val mainThreadCpuBusyScenario: MainThreadCpuBusyScenario = MainThreadCpuBusyScenario()

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
            currentSlowInputScenario.run()
        }
        findViewById<Button>(R.id.messageStormButton).setOnClickListener {
            messageStormScenario.run()
        }
        findViewById<Button>(R.id.currentBusyButton).setOnClickListener {
            mainThreadCpuBusyScenario.run()
        }
        findViewById<Button>(R.id.binderLikeButton).setOnClickListener {
            waitBinderLikeLock()
        }
        findViewById<Button>(R.id.syncBarrierLeakButton).setOnClickListener {
            runSyncBarrierLeak()
        }
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
