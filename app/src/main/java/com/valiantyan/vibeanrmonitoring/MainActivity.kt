package com.valiantyan.vibeanrmonitoring

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 应用入口页面，保持与 Android Studio Empty Views Activity 模板一致的单 Activity 结构。
 */
class MainActivity : AppCompatActivity() {
    /**
     * 初始化入口布局，后续 ANR 监控页面可以在 [R.layout.activity_main] 上继续扩展。
     */
    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
