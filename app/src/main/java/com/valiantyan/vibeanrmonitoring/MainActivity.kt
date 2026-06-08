package com.valiantyan.vibeanrmonitoring

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.valiantyan.vibeanrmonitoring.scenario.BinderCrossProcessBlockScenario
import com.valiantyan.vibeanrmonitoring.scenario.BroadcastTimeoutScenario
import com.valiantyan.vibeanrmonitoring.scenario.ContentProviderBlockScenario
import com.valiantyan.vibeanrmonitoring.scenario.CurrentSlowInputScenario
import com.valiantyan.vibeanrmonitoring.scenario.MainThreadCpuBusyScenario
import com.valiantyan.vibeanrmonitoring.scenario.MessageStormScenario
import com.valiantyan.vibeanrmonitoring.scenario.ServiceTimeoutScenario
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
}
