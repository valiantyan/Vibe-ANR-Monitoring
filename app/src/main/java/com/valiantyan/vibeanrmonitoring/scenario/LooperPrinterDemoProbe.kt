package com.valiantyan.vibeanrmonitoring.scenario

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log

/**
 * Looper Printer 竞争 Demo 探针，隔离真实 Android Looper 操作，便于场景类单测。
 */
interface LooperPrinterDemoProbe {
    /**
     * 启动 worker Looper 并安装独立 Printer，用于证明多 Looper 不是主 Looper 单槽位竞争。
     */
    fun startWorkerLooperPrinter(): Unit

    /**
     * 替换主 Looper Printer，用于模拟 SDK 安装后三方 SDK 后装抢占。
     */
    fun replaceMainLooperPrinter(): Unit

    /**
     * 释放 Demo 创建的 worker Looper，避免 Activity 销毁后线程残留。
     */
    fun shutdown(): Unit
}

/**
 * 真实 Android Looper Printer 探针，供 Demo 页面和 adb intent 触发。
 */
class AndroidLooperPrinterDemoProbe : LooperPrinterDemoProbe {
    // worker 线程只用于验证“不同 Looper 的 Printer 不会互相竞争”。
    private var workerThread: HandlerThread? = null

    /**
     * 启动后台 [Looper] 并安装 Printer，主 Looper 不会被修改。
     */
    override fun startWorkerLooperPrinter(): Unit {
        val thread: HandlerThread = workerThread ?: HandlerThread(WORKER_THREAD_NAME).also {
            newThread: HandlerThread ->
            newThread.start()
            workerThread = newThread
        }
        thread.looper.setMessageLogging { line: String ->
            Log.d(TAG_WORKER_PRINTER, line)
        }
        Handler(thread.looper).post {
            Log.w(TAG_WORKER_LOOPER, "worker looper printer installed")
        }
        Log.w(TAG, "worker looper printer installed, main looper printer untouched")
    }

    /**
     * 后装主 Looper Printer，模拟第三方 SDK 覆盖 SDK 已安装的链式 Printer。
     */
    override fun replaceMainLooperPrinter(): Unit {
        Looper.getMainLooper().setMessageLogging { line: String ->
            Log.d(TAG_THIRD_PARTY_PRINTER, line)
        }
        Log.w(TAG, "main looper printer replaced by demo third-party printer")
    }

    /**
     * 停止 Demo worker 线程，避免重复进入页面时保留旧线程。
     */
    override fun shutdown(): Unit {
        workerThread?.quitSafely()
        workerThread = null
    }

    private companion object {
        /**
         * Demo 探针自身日志标签。
         */
        private const val TAG: String = "LooperPrinterProbe"

        /**
         * worker Looper 线程名。
         */
        private const val WORKER_THREAD_NAME: String = "DemoWorkerLooper"

        /**
         * worker Looper 可见日志标签。
         */
        private const val TAG_WORKER_LOOPER: String = "DemoWorkerLooper"

        /**
         * worker Printer 可见日志标签。
         */
        private const val TAG_WORKER_PRINTER: String = "DemoWorkerPrinter"

        /**
         * 模拟三方主 Looper Printer 的可见日志标签。
         */
        private const val TAG_THIRD_PARTY_PRINTER: String = "DemoThirdPartyPrinter"
    }
}
