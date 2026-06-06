package com.valiantyan.vibeanrmonitoring

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * ANR SDK 示例入口，提供阶段一验收所需的主线程慢消息、消息风暴和 SP apply 场景。
 */
class MainActivity : AppCompatActivity() {
    // 主线程 Handler，用于构造大量 pending message 的验收场景。
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    /**
     * 初始化三个 demo 按钮，让手动验收可以直接触发不同 ANR 证据路径。
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
        findViewById<Button>(R.id.spApplyButton).setOnClickListener {
            writeSharedPreferencesBurst()
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

    // 批量触发 SharedPreferences apply，并阻塞主线程以暴露等待链路证据。
    private fun writeSharedPreferencesBurst(): Unit {
        val preferences: SharedPreferences = getSharedPreferences(
            "anr_demo_prefs",
            MODE_PRIVATE,
        )
        repeat(times = 500) { index ->
            preferences.edit()
                .putString("key_$index", "value_$index")
                .apply()
        }
        Thread.sleep(4_000L)
    }
}
