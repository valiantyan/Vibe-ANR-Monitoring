package com.valiantyan.anrmonitor.domain.model

/**
 * 一次 ANR 分析后的归因结果。
 *
 * @property primaryCode 最可信的主因。
 * @property secondaryCodes 可能共同参与的辅因。
 * @property confidence 归因置信度。
 * @property evidenceItems 支撑结论的证据摘要。
 * @property missingEvidence 影响置信度的缺失证据。
 * @property actionSuggestions 面向治理的动作建议。
 */
data class AttributionResult(
    val primaryCode: AnrAttributionCode,
    val secondaryCodes: List<AnrAttributionCode>,
    val confidence: Confidence,
    val evidenceItems: List<String>,
    val missingEvidence: List<String>,
    val actionSuggestions: List<String>,
)
