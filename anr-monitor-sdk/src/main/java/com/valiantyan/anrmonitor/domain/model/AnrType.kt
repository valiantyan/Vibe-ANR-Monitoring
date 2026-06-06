package com.valiantyan.anrmonitor.domain.model

/**
 * 系统 ANR 组件类型，用于选择系统等待阈值和辅助解释 [AnrInfoSnapshot]。
 */
enum class AnrType {
    /**
     * 输入派发超时，通常对应前台 5 秒阈值。
     */
    INPUT,

    /**
     * Service 生命周期或执行超时。
     */
    SERVICE,

    /**
     * 前台 BroadcastReceiver 执行超时。
     */
    BROADCAST_FOREGROUND,

    /**
     * 后台 BroadcastReceiver 执行超时。
     */
    BROADCAST_BACKGROUND,

    /**
     * ContentProvider 发布或访问超时。
     */
    PROVIDER,

    /**
     * Activity 生命周期相关超时。
     */
    ACTIVITY,

    /**
     * Finalizer 相关超时。
     */
    FINALIZER,

    /**
     * 系统信息不足，无法稳定判断组件类型。
     */
    UNKNOWN,
}
