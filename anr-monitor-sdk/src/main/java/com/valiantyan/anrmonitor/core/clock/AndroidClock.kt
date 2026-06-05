package com.valiantyan.anrmonitor.core.clock

import android.os.SystemClock

/**
 * Android 运行时的 [Clock] 实现，统一封装 [SystemClock.uptimeMillis]。
 */
class AndroidClock : Clock {
    /**
     * 返回 Android uptime 毫秒值，避免受系统时间修改影响。
     *
     * @return [SystemClock.uptimeMillis] 当前值。
     */
    override fun uptimeMillis(): Long {
        return SystemClock.uptimeMillis()
    }
}
