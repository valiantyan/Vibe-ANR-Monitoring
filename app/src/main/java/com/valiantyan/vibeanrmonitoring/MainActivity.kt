package com.valiantyan.vibeanrmonitoring

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.valiantyan.vibeanrmonitoring.scenario.BinderCrossProcessBlockScenario
import com.valiantyan.vibeanrmonitoring.scenario.BroadcastTimeoutScenario
import com.valiantyan.vibeanrmonitoring.scenario.ContentProviderBlockScenario
import com.valiantyan.vibeanrmonitoring.scenario.CurrentSlowInputScenario
import com.valiantyan.vibeanrmonitoring.scenario.GcMemoryChurnScenario
import com.valiantyan.vibeanrmonitoring.scenario.IoDatabaseFileBlockScenario
import com.valiantyan.vibeanrmonitoring.scenario.MainThreadCpuBusyScenario
import com.valiantyan.vibeanrmonitoring.scenario.MessageStormScenario
import com.valiantyan.vibeanrmonitoring.scenario.ProcessCpuContentionScenario
import com.valiantyan.vibeanrmonitoring.scenario.ServiceTimeoutScenario
import com.valiantyan.vibeanrmonitoring.scenario.SyncBarrierLeakScenario
import com.valiantyan.vibeanrmonitoring.scenario.ThreadPoolExhaustionWaitScenario

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

    // Binder 跨进程阻塞场景，点击时主线程同步调用远端 AIDL。
    private lateinit var binderCrossProcessBlockScenario: BinderCrossProcessBlockScenario

    // Sync Barrier 泄漏场景，单独封装反射和 token 记录逻辑。
    private val syncBarrierLeakScenario: SyncBarrierLeakScenario by lazy {
        SyncBarrierLeakScenario(context = this)
    }

    // BroadcastReceiver 超时场景，按钮只负责发送广播，真正阻塞入口在 Receiver。
    private val broadcastTimeoutScenario: BroadcastTimeoutScenario by lazy {
        BroadcastTimeoutScenario(context = this)
    }

    // Service 超时场景，按钮只负责启动 Service，真正阻塞入口在 Service。
    private val serviceTimeoutScenario: ServiceTimeoutScenario by lazy {
        ServiceTimeoutScenario(context = this)
    }

    // ContentProvider 阻塞场景，按钮只负责发起查询，真正阻塞入口在 Provider.query。
    private val contentProviderBlockScenario: ContentProviderBlockScenario by lazy {
        ContentProviderBlockScenario(context = this)
    }

    // IO / 数据库 / 文件阻塞场景，按钮点击后在主线程执行同步文件和 SQLite 操作。
    private val ioDatabaseFileBlockScenario: IoDatabaseFileBlockScenario by lazy {
        IoDatabaseFileBlockScenario(context = this)
    }

    // 线程池耗尽 + 主线程等待场景，按钮点击后占满线程池并等待排队 Future 结果。
    private val threadPoolExhaustionWaitScenario: ThreadPoolExhaustionWaitScenario =
        ThreadPoolExhaustionWaitScenario()

    // GC / 内存抖动场景，按钮点击后在主线程制造大量对象分配和 GC 压力。
    private val gcMemoryChurnScenario: GcMemoryChurnScenario = GcMemoryChurnScenario()

    // 进程内 CPU 竞争场景，按钮点击后启动后台 CPU 竞争线程并保留当前消息观察窗口。
    private val processCpuContentionScenario: ProcessCpuContentionScenario =
        ProcessCpuContentionScenario()

    /**
     * 初始化 demo 按钮，让手动验收可以直接触发不同 ANR 证据路径。
     */
    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        binderCrossProcessBlockScenario = BinderCrossProcessBlockScenario(context = this)
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
            binderCrossProcessBlockScenario.run()
        }
        findViewById<Button>(R.id.syncBarrierLeakButton).setOnClickListener {
            runSyncBarrierLeak()
        }
        findViewById<Button>(R.id.broadcastTimeoutButton).setOnClickListener {
            broadcastTimeoutScenario.run()
        }
        findViewById<Button>(R.id.serviceTimeoutButton).setOnClickListener {
            serviceTimeoutScenario.run()
        }
        findViewById<Button>(R.id.contentProviderBlockButton).setOnClickListener {
            contentProviderBlockScenario.run()
        }
        findViewById<Button>(R.id.ioDatabaseFileBlockButton).setOnClickListener {
            ioDatabaseFileBlockScenario.run()
        }
        findViewById<Button>(R.id.threadPoolExhaustionWaitButton).setOnClickListener {
            threadPoolExhaustionWaitScenario.run()
        }
        findViewById<Button>(R.id.gcMemoryChurnButton).setOnClickListener {
            gcMemoryChurnScenario.run()
        }
        findViewById<Button>(R.id.processCpuContentionButton).setOnClickListener {
            processCpuContentionScenario.run()
        }
        runScenarioFromIntent(intent = intent)
    }

    /**
     * Activity 已在前台时允许 adb 通过 intent extra 触发 Demo 场景，避免自动化验收依赖坐标点击。
     */
    override fun onNewIntent(intent: Intent): Unit {
        super.onNewIntent(intent)
        setIntent(intent)
        runScenarioFromIntent(intent = intent)
    }

    /**
     * Activity 销毁时释放 Binder 远端服务绑定。
     */
    override fun onDestroy(): Unit {
        if (::binderCrossProcessBlockScenario.isInitialized) {
            binderCrossProcessBlockScenario.release()
        }
        super.onDestroy()
    }

    // 泄漏 Sync Barrier，用于验证 nativePollOnce 表象背后的队列根因。
    private fun runSyncBarrierLeak(): Unit {
        syncBarrierLeakScenario.run()
    }

    // 只暴露 Demo 验收入口，实际执行仍复用按钮背后的同一个场景类。
    private fun runScenarioFromIntent(intent: Intent?): Unit {
        val scenarioId: String = intent?.getStringExtra(EXTRA_DEMO_SCENARIO) ?: return
        Log.w(TAG, "run demo scenario from intent: $scenarioId")
        when (scenarioId) {
            ioDatabaseFileBlockScenario.id -> ioDatabaseFileBlockScenario.run()
            threadPoolExhaustionWaitScenario.id -> threadPoolExhaustionWaitScenario.run()
            gcMemoryChurnScenario.id -> gcMemoryChurnScenario.run()
            processCpuContentionScenario.id -> processCpuContentionScenario.run()
        }
    }

    private companion object {
        /**
         * Demo 页面日志标签，便于手动验收确认 intent 触发入口。
         */
        private const val TAG: String = "MainActivity"

        /**
         * Demo 自动化验收场景 ID extra key。
         */
        private const val EXTRA_DEMO_SCENARIO: String = "anr_demo_scenario"
    }
}
