package com.valiantyan.anrmonitor.domain.model

/**
 * 归因置信度，评审时用于区分可直接治理和需要线下复核的结论。
 */
enum class Confidence {
    /**
     * 多项证据一致，可以作为主要治理依据。
     */
    HIGH,

    /**
     * 关键证据存在但仍有缺口，需要结合辅助信息确认。
     */
    MEDIUM,

    /**
     * 仅有弱证据，适合作为排查线索。
     */
    LOW,

    /**
     * 没有足够证据判断。
     */
    UNKNOWN,
}
