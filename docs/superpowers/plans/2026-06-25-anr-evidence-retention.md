# ANR Evidence Retention Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement bounded, value-based main-thread evidence retention so historical slow messages, referenced stack samples, short-message burst summaries, and retention metadata survive ordinary Handler churn and appear in compatible JSON reports.

**Architecture:** Keep `MainLooperTimelineCollector` focused on Looper Printer parsing, current-message state, and slow-stack sampling. Add a pure Kotlin `MainThreadEvidenceStore` that owns recent history, slow history, aggregated bursts, retained stack samples, and retention counters; during snapshot construction the runtime passes current-message stack samples from the collector into the store so in-flight evidence is not lost. Expose retained fields through `AnrSnapshot`, `AnrReportJsonEncoder`, and `AttributionAnalyzer`.

**Tech Stack:** Android Gradle Plugin 8.5.2, Kotlin 1.9.22, Java 17, JUnit 4, existing SDK module `:anr-monitor-sdk`.

---

## Source Spec

Implement the reviewed spec:

- `docs/superpowers/specs/2026-06-25-anr-evidence-retention-design.md`

The key compatibility rule is: keep `mainThread.history`, `mainThread.current`, `mainThread.stackFrames`, and `mainThread.stackSamples`; add `mainThread.slowHistory`, `mainThread.aggregatedBursts`, and `mainThread.retention`.

## File Structure

- Create: `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/MainThreadEvidence.kt`
  - Defines `MainThreadEvidenceSnapshot` and `MainThreadRetentionStats`.
  - Holds immutable, Android-free domain state for retained evidence.
- Modify: `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrSnapshot.kt`
  - Adds defaulted fields for `slowHistoryMessages`, `aggregatedBursts`, and `mainThreadRetention`.
- Create: `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/core/timeline/MainThreadEvidenceStore.kt`
  - Owns bounded recent history, slow history, burst aggregation, retained stack samples, and counters.
  - Has no Android framework dependency.
- Create: `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/core/timeline/MainThreadEvidenceStoreTest.kt`
  - Proves retention behavior before wiring it into Looper and JSON.
- Modify: `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/looper/MainLooperTimelineCollector.kt`
  - Replaces direct `MessageRingBuffer` writes and stack sample exposure with calls to `MainThreadEvidenceStore`.
  - Keeps a small accessor for current-message sample records because samples for an in-flight message have not reached the store yet.
- Modify: `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/looper/MainLooperTimelineCollectorTest.kt`
  - Updates tests to assert through the evidence store.
- Modify: `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/AnrMonitorRuntime.kt`
  - Constructs `MainThreadEvidenceStore` and builds snapshots from its stable snapshot.
- Modify: `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter/encoder/AnrReportJsonEncoder.kt`
  - Emits `slowHistory`, `aggregatedBursts`, and `retention`.
- Modify: `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/reporter/encoder/AnrReportJsonEncoderTest.kt`
  - Adds encoder coverage for new fields and legacy-field compatibility.
- Modify: `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzer.kt`
  - Reads `slowHistoryMessages` before ordinary `historyMessages`.
  - Reads `aggregatedBursts` for message storm evidence when Pending queue evidence is absent or shallow.
  - Mentions slow-history truncation in unknown results.
- Modify: `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzerTest.kt`
  - Adds attribution coverage for retained slow history, aggregated bursts, and evidence gaps.
- Modify: `README.md`
  - Documents the new main-thread fields.
- Modify: `docs-anr/102-ANR监控SDK服务端消费协议.md`
  - Defines server-consumption semantics for the new fields.
- Modify: `docs-anr/104-ANR监控JSON日志根因排查指南.md`
  - Updates reading order to prefer `slowHistory` before ordinary `history`.
- Modify: `docs-anr/99-ANR监控SDK设计开发文档.md`
  - Aligns existing aggregation and slow-message design with the implementation.

## Repo-Specific Guardrails

- Before editing each Kotlin symbol named below, run GitNexus impact analysis with `direction: "upstream"` and report risk. If any result is `HIGH` or `CRITICAL`, stop and ask the user before editing.
- Use these repo skills before implementation work: `test-driven-development`, `kotlin-basics`, `kotlin-naming`, `kotlin-functions`, `kotlin-data-classes`, and `kotlin-documentation`.
- Do not change `minSdk`, `compileSdk`, AGP, Kotlin version, or add dependencies.
- Do not change upload semantics or server behavior beyond compatible JSON fields.
- Do not modify `dist/` or generated build outputs.
- Leave unrelated dirty files untouched, especially `AGENTS.md` unless the user explicitly asks.

---

### Task 1: Add Immutable Main-Thread Evidence Models

**Files:**
- Create: `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/MainThreadEvidence.kt`
- Modify: `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrSnapshot.kt`
- Test: `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/reporter/encoder/AnrReportJsonEncoderTest.kt`

- [x] **Step 1: Run impact analysis before editing `AnrSnapshot`**

Run GitNexus:

```text
impact({
  repo: "Vibe-ANR-Monitoring",
  target: "AnrSnapshot",
  direction: "upstream",
  maxDepth: 2,
  limit: 50
})
```

Expected: risk may be medium because many tests construct `AnrSnapshot`; proceed only if risk is not high or critical.

- [x] **Step 2: Write a failing encoder compatibility test**

Add this test method near the other `mainThread` report tests in `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/reporter/encoder/AnrReportJsonEncoderTest.kt`:

```kotlin
/**
 * 新增主线程证据字段必须在快照默认值为空时保持可编码，避免破坏旧构造路径。
 */
@Test
fun encodeIncludesEmptyMainThreadEvidenceDefaults(): Unit {
    val report: AnrReport = AnrReport.empty(
        appId = "demo",
        environment = "test",
    )

    val json: String = AnrReportJsonEncoder().encode(report = report)

    assertTrue(json.contains("\"mainThread\""))
    assertTrue(json.contains("\"history\":[]"))
    assertTrue(json.contains("\"slowHistory\":[]"))
    assertTrue(json.contains("\"aggregatedBursts\":[]"))
    assertTrue(json.contains("\"retention\""))
    assertTrue(json.contains("\"historyLimit\":0"))
    assertTrue(json.contains("\"slowHistoryLimit\":0"))
    assertTrue(json.contains("\"aggregatedBurstLimit\":0"))
    assertTrue(json.contains("\"stackSampleLimit\":0"))
    assertTrue(json.contains("\"truncated\":false"))
}
```

- [x] **Step 3: Run the failing test**

Run:

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.reporter.encoder.AnrReportJsonEncoderTest.encodeIncludesEmptyMainThreadEvidenceDefaults
```

Expected: FAIL because `slowHistory`, `aggregatedBursts`, and `retention` are not encoded yet.

- [x] **Step 4: Create `MainThreadEvidence.kt`**

Create `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/MainThreadEvidence.kt`:

```kotlin
package com.valiantyan.anrmonitor.domain.model

/**
 * 主线程证据保留层的一次不可变快照。
 *
 * @property historyMessages 最近完成的主线程消息窗口。
 * @property slowHistoryMessages 不被普通短消息淘汰的历史慢消息和关键组件消息。
 * @property aggregatedBursts 连续短消息风暴的聚合记录。
 * @property stackSamples 当前消息或慢历史消息引用的栈采样记录。
 * @property retention 本次快照的保留上限、裁剪计数和聚合统计。
 */
data class MainThreadEvidenceSnapshot(
    val historyMessages: List<MessageRecord> = emptyList(),
    val slowHistoryMessages: List<MessageRecord> = emptyList(),
    val aggregatedBursts: List<MessageRecord> = emptyList(),
    val stackSamples: List<StackSampleRecord> = emptyList(),
    val retention: MainThreadRetentionStats = MainThreadRetentionStats.empty(),
)

/**
 * 主线程证据保留状态，帮助报告读者判断证据是否完整。
 *
 * @property historyLimit 最近历史消息窗口容量。
 * @property slowHistoryLimit 慢历史消息窗口容量。
 * @property aggregatedBurstLimit 聚合短消息窗口容量。
 * @property stackSampleLimit 栈采样保留容量。
 * @property historyDroppedCount 最近历史窗口因容量淘汰的记录数。
 * @property slowHistoryDroppedCount 慢历史窗口因容量淘汰的记录数。
 * @property aggregatedMessageCount 被折叠进聚合记录的原始短消息数量。
 * @property aggregationEnabled 是否启用连续短消息聚合。
 * @property truncated 任一有界窗口因容量限制丢弃证据时为 true。
 */
data class MainThreadRetentionStats(
    val historyLimit: Int,
    val slowHistoryLimit: Int,
    val aggregatedBurstLimit: Int,
    val stackSampleLimit: Int,
    val historyDroppedCount: Long,
    val slowHistoryDroppedCount: Long,
    val aggregatedMessageCount: Long,
    val aggregationEnabled: Boolean,
    val truncated: Boolean,
) {
    companion object {
        /**
         * 空报告或旧测试构造路径使用的明确默认值。
         */
        fun empty(): MainThreadRetentionStats {
            return MainThreadRetentionStats(
                historyLimit = 0,
                slowHistoryLimit = 0,
                aggregatedBurstLimit = 0,
                stackSampleLimit = 0,
                historyDroppedCount = 0L,
                slowHistoryDroppedCount = 0L,
                aggregatedMessageCount = 0L,
                aggregationEnabled = false,
                truncated = false,
            )
        }
    }
}
```

- [x] **Step 5: Extend `AnrSnapshot` with defaulted fields**

Modify the KDoc in `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrSnapshot.kt` by adding these properties after `historyMessages` and `stackSamples`:

```kotlin
 * @property slowHistoryMessages 被价值保留层独立保存的历史慢消息和关键组件慢消息。
 * @property aggregatedBursts 连续短消息风暴的聚合摘要。
 * @property mainThreadRetention 主线程证据窗口的容量、淘汰和聚合统计。
```

Then add defaulted constructor fields:

```kotlin
    val historyMessages: List<MessageRecord>,
    val slowHistoryMessages: List<MessageRecord> = emptyList(),
    val aggregatedBursts: List<MessageRecord> = emptyList(),
    val pendingQueue: PendingQueueSnapshot,
    val mainThreadStack: StackTraceSnapshot,
    val stackSamples: List<StackSampleRecord> = emptyList(),
    val mainThreadRetention: MainThreadRetentionStats = MainThreadRetentionStats.empty(),
```

Keep every existing constructor parameter and default value unchanged.

- [x] **Step 6: Run compile to verify the model change**

Run:

```bash
./gradlew :anr-monitor-sdk:compileDebugKotlin
```

Expected: PASS. Existing `AnrSnapshot` call sites should compile because the new fields have defaults.

- [x] **Step 7: Commit the model skeleton**

Run:

```bash
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/MainThreadEvidence.kt anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrSnapshot.kt anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/reporter/encoder/AnrReportJsonEncoderTest.kt
git commit -m "feat: add main thread evidence models"
```

---

### Task 2: Implement `MainThreadEvidenceStore`

**Files:**
- Create: `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/core/timeline/MainThreadEvidenceStore.kt`
- Create: `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/core/timeline/MainThreadEvidenceStoreTest.kt`

- [x] **Step 1: Write failing store tests**

Create `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/core/timeline/MainThreadEvidenceStoreTest.kt`:

```kotlin
package com.valiantyan.anrmonitor.core.timeline

import com.valiantyan.anrmonitor.domain.model.MessageRecord
import com.valiantyan.anrmonitor.domain.model.MessageRecordKind
import com.valiantyan.anrmonitor.domain.model.StackSampleRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证主线程证据保留层按价值而不是单一时间窗口保留关键证据。
 */
class MainThreadEvidenceStoreTest {
    @Test
    fun snapshotKeepsSlowMessageAfterShortMessageChurn(): Unit {
        val store = MainThreadEvidenceStore(
            historyLimit = 4,
            slowHistoryLimit = 2,
            aggregatedBurstLimit = 2,
            stackSampleLimit = 4,
            slowMessageMs = 1_000L,
            shortMessageAggregateMs = 300L,
            messageBurstCountThreshold = 20,
        )
        val slow = message(seq = 1L, wallMs = 1_500L, sampleStackIds = listOf("sample-slow"))
        val sample = StackSampleRecord(
            stackId = "sample-slow",
            frames = listOf("com.example.Slow.run(Slow.kt:10)"),
            hitCount = 1,
        )

        store.addFinishedMessage(record = slow, stackSamples = listOf(sample))
        (2L..501L).forEach { seq ->
            store.addFinishedMessage(record = message(seq = seq, wallMs = 1L))
        }

        val snapshot = store.snapshot(currentMessage = null)

        assertFalse(snapshot.historyMessages.map { record -> record.seq }.contains(1L))
        assertEquals(listOf(1L), snapshot.slowHistoryMessages.map { record -> record.seq })
        assertEquals(listOf("sample-slow"), snapshot.stackSamples.map { record -> record.stackId })
        assertTrue(snapshot.retention.historyDroppedCount > 0L)
        assertTrue(snapshot.retention.truncated)
    }

    @Test
    fun addFinishedMessageAggregatesContiguousShortBurst(): Unit {
        val store = MainThreadEvidenceStore(
            historyLimit = 10,
            slowHistoryLimit = 2,
            aggregatedBurstLimit = 2,
            stackSampleLimit = 4,
            slowMessageMs = 1_000L,
            shortMessageAggregateMs = 300L,
            messageBurstCountThreshold = 3,
        )

        store.addFinishedMessage(record = message(seq = 1L, wallMs = 10L))
        store.addFinishedMessage(record = message(seq = 2L, wallMs = 20L))
        store.addFinishedMessage(record = message(seq = 3L, wallMs = 30L))

        val snapshot = store.snapshot(currentMessage = null)

        assertEquals(1, snapshot.aggregatedBursts.size)
        assertEquals(MessageRecordKind.AGGREGATED, snapshot.aggregatedBursts.first().kind)
        assertEquals(3, snapshot.aggregatedBursts.first().count)
        assertEquals(60L, snapshot.aggregatedBursts.first().wallMs)
        assertEquals(3L, snapshot.retention.aggregatedMessageCount)
        assertTrue(snapshot.historyMessages.any { record -> record.kind == MessageRecordKind.AGGREGATED })
    }

    @Test
    fun criticalComponentAboveAggregateThresholdEntersSlowHistory(): Unit {
        val store = MainThreadEvidenceStore(
            historyLimit = 4,
            slowHistoryLimit = 2,
            aggregatedBurstLimit = 2,
            stackSampleLimit = 4,
            slowMessageMs = 1_000L,
            shortMessageAggregateMs = 300L,
            messageBurstCountThreshold = 20,
        )

        store.addFinishedMessage(
            record = message(
                seq = 7L,
                wallMs = 300L,
                targetClass = "android.app.ActivityThread\$H",
                isCriticalComponent = true,
            ),
        )

        assertEquals(listOf(7L), store.snapshot(currentMessage = null).slowHistoryMessages.map { record -> record.seq })
    }

    @Test
    fun snapshotReturnsImmutableCopies(): Unit {
        val store = MainThreadEvidenceStore(
            historyLimit = 2,
            slowHistoryLimit = 2,
            aggregatedBurstLimit = 2,
            stackSampleLimit = 2,
            slowMessageMs = 1_000L,
            shortMessageAggregateMs = 300L,
            messageBurstCountThreshold = 20,
        )

        store.addFinishedMessage(record = message(seq = 1L, wallMs = 10L))
        val first = store.snapshot(currentMessage = null)
        store.addFinishedMessage(record = message(seq = 2L, wallMs = 10L))
        val second = store.snapshot(currentMessage = null)

        assertEquals(listOf(1L), first.historyMessages.map { record -> record.seq })
        assertEquals(listOf(1L, 2L), second.historyMessages.map { record -> record.seq })
    }

    private fun message(
        seq: Long,
        wallMs: Long,
        targetClass: String = "android.os.Handler",
        callbackClass: String? = "com.example.RefreshRunnable",
        what: Int? = 1,
        isCriticalComponent: Boolean = false,
        sampleStackIds: List<String> = emptyList(),
    ): MessageRecord {
        return MessageRecord(
            seq = seq,
            kind = MessageRecordKind.HISTORY,
            messageType = "looper_dispatch",
            what = what,
            targetClass = targetClass,
            callbackClass = callbackClass,
            isCriticalComponent = isCriticalComponent,
            startUptimeMs = seq * 10L,
            endUptimeMs = seq * 10L + wallMs,
            wallMs = wallMs,
            cpuMs = wallMs,
            sampleStackIds = sampleStackIds,
        )
    }
}
```

- [x] **Step 2: Run store tests to verify missing class failure**

Run:

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.core.timeline.MainThreadEvidenceStoreTest
```

Expected: FAIL with unresolved reference `MainThreadEvidenceStore`.

- [x] **Step 3: Implement `MainThreadEvidenceStore`**

Create `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/core/timeline/MainThreadEvidenceStore.kt`:

```kotlin
package com.valiantyan.anrmonitor.core.timeline

import com.valiantyan.anrmonitor.domain.model.MainThreadEvidenceSnapshot
import com.valiantyan.anrmonitor.domain.model.MainThreadRetentionStats
import com.valiantyan.anrmonitor.domain.model.MessageRecord
import com.valiantyan.anrmonitor.domain.model.MessageRecordKind
import com.valiantyan.anrmonitor.domain.model.StackSampleRecord

/**
 * 按证据价值保留主线程消息、慢消息、短消息风暴和栈采样。
 */
class MainThreadEvidenceStore(
    private val historyLimit: Int,
    private val slowHistoryLimit: Int,
    private val aggregatedBurstLimit: Int,
    private val stackSampleLimit: Int,
    private val slowMessageMs: Long,
    private val shortMessageAggregateMs: Long,
    private val messageBurstCountThreshold: Int = DEFAULT_MESSAGE_BURST_COUNT_THRESHOLD,
) {
    private val historyMessages: ArrayDeque<MessageRecord> = ArrayDeque()
    private val slowHistoryMessages: ArrayDeque<MessageRecord> = ArrayDeque()
    private val aggregatedBursts: ArrayDeque<MessageRecord> = ArrayDeque()
    private val stackSamplesById: LinkedHashMap<String, StackSampleRecord> = linkedMapOf()
    private var burstAccumulator: BurstAccumulator? = null
    private var historyDroppedCount: Long = 0L
    private var slowHistoryDroppedCount: Long = 0L
    private var aggregatedMessageCount: Long = 0L
    private var stackSampleDroppedCount: Long = 0L
    private var aggregatedBurstDroppedCount: Long = 0L

    /**
     * 记录一条已完成主线程消息，并按价值决定是否进入慢历史或聚合窗口。
     */
    @Synchronized
    fun addFinishedMessage(
        record: MessageRecord,
        stackSamples: List<StackSampleRecord> = emptyList(),
    ): Unit {
        rememberStackSamples(records = stackSamples)
        if (shouldKeepAsSlowHistory(record = record)) {
            flushBurst()
            appendHistory(record = record)
            appendSlowHistory(record = record)
            trimStackSamples(currentMessage = null)
            return
        }
        if (tryAccumulateBurst(record = record)) {
            return
        }
        flushBurst()
        appendHistory(record = record)
        trimStackSamples(currentMessage = null)
    }

    /**
     * 生成疑似 ANR 快照使用的不可变证据副本。
     */
    @Synchronized
    fun snapshot(
        currentMessage: MessageRecord?,
        currentStackSamples: List<StackSampleRecord> = emptyList(),
    ): MainThreadEvidenceSnapshot {
        flushBurst()
        rememberStackSamples(records = currentStackSamples)
        trimStackSamples(currentMessage = currentMessage)
        return MainThreadEvidenceSnapshot(
            historyMessages = historyMessages.toList(),
            slowHistoryMessages = slowHistoryMessages.toList(),
            aggregatedBursts = aggregatedBursts.toList(),
            stackSamples = stackSamplesById.values.toList(),
            retention = retentionStats(),
        )
    }

    /**
     * 清空运行态证据，供停止或测试重置时释放窗口。
     */
    @Synchronized
    fun clear(): Unit {
        historyMessages.clear()
        slowHistoryMessages.clear()
        aggregatedBursts.clear()
        stackSamplesById.clear()
        burstAccumulator = null
        historyDroppedCount = 0L
        slowHistoryDroppedCount = 0L
        aggregatedMessageCount = 0L
        stackSampleDroppedCount = 0L
        aggregatedBurstDroppedCount = 0L
    }

    private fun shouldKeepAsSlowHistory(record: MessageRecord): Boolean {
        return record.wallMs >= slowMessageMs ||
            record.sampleStackIds.isNotEmpty() ||
            record.isCriticalComponent && record.wallMs >= shortMessageAggregateMs
    }

    private fun tryAccumulateBurst(record: MessageRecord): Boolean {
        if (messageBurstCountThreshold <= 1 && record.wallMs < shortMessageAggregateMs) {
            return false
        }
        val key = BurstKey.from(record = record)
        val current = burstAccumulator
        if (current == null || current.key != key) {
            flushBurst()
            burstAccumulator = BurstAccumulator.from(record = record, key = key)
            return true
        }
        current.add(record = record)
        if (current.count >= messageBurstCountThreshold || current.wallMs >= shortMessageAggregateMs) {
            flushBurst()
        }
        return true
    }

    private fun flushBurst(): Unit {
        val current = burstAccumulator ?: return
        burstAccumulator = null
        if (current.count <= 1) {
            appendHistory(record = current.toSingleRecord())
            return
        }
        val aggregate = current.toAggregatedRecord()
        appendHistory(record = aggregate)
        appendAggregatedBurst(record = aggregate)
        aggregatedMessageCount += current.count.toLong()
    }

    private fun appendHistory(record: MessageRecord): Unit {
        if (historyLimit <= 0) {
            historyDroppedCount += 1L
            return
        }
        while (historyMessages.size >= historyLimit) {
            historyMessages.removeFirst()
            historyDroppedCount += 1L
        }
        historyMessages.addLast(record)
    }

    private fun appendSlowHistory(record: MessageRecord): Unit {
        if (slowHistoryLimit <= 0) {
            slowHistoryDroppedCount += 1L
            return
        }
        while (slowHistoryMessages.size >= slowHistoryLimit) {
            slowHistoryMessages.removeFirst()
            slowHistoryDroppedCount += 1L
        }
        slowHistoryMessages.addLast(record)
    }

    private fun appendAggregatedBurst(record: MessageRecord): Unit {
        if (aggregatedBurstLimit <= 0) {
            aggregatedBurstDroppedCount += 1L
            return
        }
        while (aggregatedBursts.size >= aggregatedBurstLimit) {
            aggregatedBursts.removeFirst()
            aggregatedBurstDroppedCount += 1L
        }
        aggregatedBursts.addLast(record)
    }

    private fun rememberStackSamples(records: List<StackSampleRecord>): Unit {
        records.forEach { record ->
            stackSamplesById[record.stackId] = record
        }
    }

    private fun trimStackSamples(currentMessage: MessageRecord?) {
        if (stackSampleLimit <= 0) {
            stackSampleDroppedCount += stackSamplesById.size.toLong()
            stackSamplesById.clear()
            return
        }
        val referencedIds = linkedSetOf<String>()
        currentMessage?.sampleStackIds.orEmpty().forEach { stackId -> referencedIds += stackId }
        slowHistoryMessages.forEach { record -> referencedIds += record.sampleStackIds }
        val referencedSamples = stackSamplesById.filterKeys { stackId -> stackId in referencedIds }
        val unreferencedSamples = stackSamplesById.filterKeys { stackId -> stackId !in referencedIds }
        val retained = LinkedHashMap<String, StackSampleRecord>()
        referencedSamples.entries.takeLast(stackSampleLimit).forEach { entry ->
            retained[entry.key] = entry.value
        }
        if (retained.size < stackSampleLimit) {
            unreferencedSamples.entries.takeLast(stackSampleLimit - retained.size).forEach { entry ->
                retained[entry.key] = entry.value
            }
        }
        stackSampleDroppedCount += (stackSamplesById.size - retained.size).coerceAtLeast(0).toLong()
        stackSamplesById.clear()
        stackSamplesById.putAll(retained)
    }

    private fun retentionStats(): MainThreadRetentionStats {
        return MainThreadRetentionStats(
            historyLimit = historyLimit.coerceAtLeast(minimumValue = 0),
            slowHistoryLimit = slowHistoryLimit.coerceAtLeast(minimumValue = 0),
            aggregatedBurstLimit = aggregatedBurstLimit.coerceAtLeast(minimumValue = 0),
            stackSampleLimit = stackSampleLimit.coerceAtLeast(minimumValue = 0),
            historyDroppedCount = historyDroppedCount,
            slowHistoryDroppedCount = slowHistoryDroppedCount,
            aggregatedMessageCount = aggregatedMessageCount,
            aggregationEnabled = messageBurstCountThreshold > 1 || shortMessageAggregateMs > 0L,
            truncated = historyDroppedCount > 0L ||
                slowHistoryDroppedCount > 0L ||
                aggregatedBurstDroppedCount > 0L ||
                stackSampleDroppedCount > 0L,
        )
    }

    private data class BurstKey(
        val targetClass: String,
        val callbackClass: String?,
        val what: Int?,
        val messageType: String,
    ) {
        companion object {
            fun from(record: MessageRecord): BurstKey {
                return BurstKey(
                    targetClass = record.targetClass,
                    callbackClass = record.callbackClass,
                    what = record.what,
                    messageType = record.messageType,
                )
            }
        }
    }

    private data class BurstAccumulator(
        val key: BurstKey,
        val firstRecord: MessageRecord,
        var lastEndUptimeMs: Long?,
        var wallMs: Long,
        var cpuMs: Long,
        var count: Int,
    ) {
        fun add(record: MessageRecord): Unit {
            lastEndUptimeMs = record.endUptimeMs
            wallMs += record.wallMs
            cpuMs += record.cpuMs
            count += 1
        }

        fun toSingleRecord(): MessageRecord {
            return firstRecord
        }

        fun toAggregatedRecord(): MessageRecord {
            return firstRecord.copy(
                kind = MessageRecordKind.AGGREGATED,
                endUptimeMs = lastEndUptimeMs,
                wallMs = wallMs,
                cpuMs = cpuMs,
                count = count,
                sampleStackIds = emptyList(),
            )
        }

        companion object {
            fun from(
                record: MessageRecord,
                key: BurstKey,
            ): BurstAccumulator {
                return BurstAccumulator(
                    key = key,
                    firstRecord = record,
                    lastEndUptimeMs = record.endUptimeMs,
                    wallMs = record.wallMs,
                    cpuMs = record.cpuMs,
                    count = 1,
                )
            }
        }
    }

    private companion object {
        private const val DEFAULT_MESSAGE_BURST_COUNT_THRESHOLD: Int = 20
    }
}
```

- [x] **Step 4: Run store tests**

Run:

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.core.timeline.MainThreadEvidenceStoreTest
```

Expected: PASS.

- [x] **Step 5: Commit the store**

Run:

```bash
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/core/timeline/MainThreadEvidenceStore.kt anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/core/timeline/MainThreadEvidenceStoreTest.kt
git commit -m "feat: retain layered main thread evidence"
```

---

### Task 3: Wire Looper Collector and Runtime to the Evidence Store

**Files:**
- Modify: `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/looper/MainLooperTimelineCollector.kt`
- Modify: `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/looper/MainLooperTimelineCollectorTest.kt`
- Modify: `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/AnrMonitorRuntime.kt`

- [x] **Step 1: Run impact analysis before editing collector and runtime symbols**

Run GitNexus:

```text
impact({
  repo: "Vibe-ANR-Monitoring",
  target: "MainLooperTimelineCollector",
  direction: "upstream",
  maxDepth: 2,
  limit: 50
})
impact({
  repo: "Vibe-ANR-Monitoring",
  target: "buildSnapshot",
  file_path: "anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/AnrMonitorRuntime.kt",
  direction: "upstream",
  maxDepth: 2,
  limit: 50
})
```

Expected: report direct callers and risk. Proceed only if neither result is high or critical.

- [x] **Step 2: Update Looper collector tests to use evidence store**

In `MainLooperTimelineCollectorTest`, replace `MessageRingBuffer` imports/usages with `MainThreadEvidenceStore`.

Use this helper in the test class:

```kotlin
private fun evidenceStore(): MainThreadEvidenceStore {
    return MainThreadEvidenceStore(
        historyLimit = 4,
        slowHistoryLimit = 2,
        aggregatedBurstLimit = 2,
        stackSampleLimit = 4,
        slowMessageMs = 1_000L,
        shortMessageAggregateMs = 300L,
        messageBurstCountThreshold = 20,
    )
}
```

Update the first test setup and assertion:

```kotlin
val store: MainThreadEvidenceStore = evidenceStore()
val collector: MainLooperTimelineCollector = MainLooperTimelineCollector(
    clock = clock,
    threadCpuClock = cpuClock,
    sanitizer = ClassNameSanitizer(privacyMode = AnrPrivacyMode.SAFE),
    evidenceStore = store,
)

collector.onLooperLog(line = ">>>>> Dispatching to Handler (android.os.Handler) {12345} null: 1")
collector.onLooperLog(line = "<<<<< Finished to Handler (android.os.Handler) {12345} null")

val history = store.snapshot(currentMessage = null).historyMessages
assertNull(collector.currentMessage())
assertEquals(1, history.size)
assertEquals(MessageRecordKind.HISTORY, history.first().kind)
assertEquals(150L, history.first().wallMs)
assertEquals(70L, history.first().cpuMs)
```

Update the slow-sample test setup and sample assertion:

```kotlin
val store: MainThreadEvidenceStore = evidenceStore()
val collector: MainLooperTimelineCollector = MainLooperTimelineCollector(
    clock = clock,
    threadCpuClock = cpuClock,
    sanitizer = ClassNameSanitizer(privacyMode = AnrPrivacyMode.SAFE),
    evidenceStore = store,
    slowMessageMs = 1_000L,
    stackSampleIntervalMs = 500L,
    slowMessageSampler = SlowMessageStackSampler(
        maxSamplesPerMessage = 3,
        frameProvider = { listOf("com.example.Feature.render(Feature.kt:42)") },
    ),
)

collector.onLooperLog(line = ">>>>> Dispatching to Handler (android.os.Handler) {12345} null: 1")
val current = requireNotNull(collector.currentMessage())
val samples: List<StackSampleRecord> = store.snapshot(
    currentMessage = current,
    currentStackSamples = collector.stackSamplesFor(sampleStackIds = current.sampleStackIds),
).stackSamples

assertEquals(1, current.sampleStackIds.size)
assertEquals(1, samples.size)
assertEquals(70L, current.cpuMs)
assertEquals(current.sampleStackIds.first(), samples.first().stackId)
```

- [x] **Step 3: Run Looper collector tests to verify constructor failure**

Run:

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.looper.MainLooperTimelineCollectorTest
```

Expected: FAIL because `MainLooperTimelineCollector` still expects `historyBuffer`.

- [x] **Step 4: Modify `MainLooperTimelineCollector` constructor and write path**

In `MainLooperTimelineCollector.kt`:

Remove:

```kotlin
import com.valiantyan.anrmonitor.core.timeline.MessageRingBuffer
```

Add:

```kotlin
import com.valiantyan.anrmonitor.core.timeline.MainThreadEvidenceStore
```

Change constructor parameter:

```kotlin
    private val evidenceStore: MainThreadEvidenceStore,
```

Remove `historyMessages()` from the collector. Replace the old all-window `stackSamples()` accessor with a narrow current-message bridge because the evidence store owns completed-message samples, but samples collected while the current message is still running have not yet been handed to the store.

Add this method near `currentMessage()`:

```kotlin
/**
 * 返回当前消息引用的栈采样记录。已完成消息的样本由 MainThreadEvidenceStore 持有。
 */
fun stackSamplesFor(sampleStackIds: List<String>): List<StackSampleRecord> {
    if (sampleStackIds.isEmpty()) {
        return emptyList()
    }
    synchronized(sampleLock) {
        return sampleStackIds.mapNotNull { stackId -> stackSamplesById[stackId] }
    }
}
```

In `finishCurrentRecord`, replace:

```kotlin
historyBuffer.add(record = record.copy(targetClass = sanitizedEndTarget.ifBlank { record.targetClass }))
currentRecordStart = null
```

with:

```kotlin
val finishedRecord = record.copy(targetClass = sanitizedEndTarget.ifBlank { record.targetClass })
val samples: List<StackSampleRecord> = sampleStackIds.mapNotNull { stackId ->
    synchronized(sampleLock) {
        stackSamplesById[stackId]
    }
}
evidenceStore.addFinishedMessage(
    record = finishedRecord,
    stackSamples = samples,
)
currentRecordStart = null
```

Keep `currentMessage()` unchanged so current-message sampling still works while a dispatch is running.

- [x] **Step 5: Modify `AnrMonitorRuntime` to construct and read the evidence store**

In `AnrMonitorRuntime.kt`, remove:

```kotlin
import com.valiantyan.anrmonitor.core.timeline.MessageRingBuffer
```

Add:

```kotlin
import com.valiantyan.anrmonitor.core.timeline.MainThreadEvidenceStore
import com.valiantyan.anrmonitor.domain.model.MainThreadEvidenceSnapshot
```

Replace:

```kotlin
private val historyBuffer: MessageRingBuffer = MessageRingBuffer(capacity = config.historyBufferSize)
```

with:

```kotlin
private val mainThreadEvidenceStore: MainThreadEvidenceStore = MainThreadEvidenceStore(
    historyLimit = config.historyBufferSize,
    slowHistoryLimit = DEFAULT_SLOW_HISTORY_LIMIT,
    aggregatedBurstLimit = DEFAULT_AGGREGATED_BURST_LIMIT,
    stackSampleLimit = DEFAULT_RETAINED_STACK_SAMPLE_LIMIT,
    slowMessageMs = config.slowMessageMs,
    shortMessageAggregateMs = config.shortMessageAggregateMs,
)
```

Pass it to the collector:

```kotlin
evidenceStore = mainThreadEvidenceStore,
```

In `buildSnapshot`, compute current and evidence once:

```kotlin
val currentMessage = timelineCollector.currentMessage()
val currentStackSamples = timelineCollector.stackSamplesFor(
    sampleStackIds = currentMessage?.sampleStackIds.orEmpty(),
)
val mainThreadEvidence: MainThreadEvidenceSnapshot = mainThreadEvidenceStore.snapshot(
    currentMessage = currentMessage,
    currentStackSamples = currentStackSamples,
)
```

Then set snapshot fields:

```kotlin
currentMessage = currentMessage,
historyMessages = mainThreadEvidence.historyMessages,
slowHistoryMessages = mainThreadEvidence.slowHistoryMessages,
aggregatedBursts = mainThreadEvidence.aggregatedBursts,
pendingQueue = pendingQueue,
mainThreadStack = mainThreadStack,
stackSamples = mainThreadEvidence.stackSamples,
mainThreadRetention = mainThreadEvidence.retention,
```

Add constants to the companion object:

```kotlin
private const val DEFAULT_SLOW_HISTORY_LIMIT: Int = 20
private const val DEFAULT_AGGREGATED_BURST_LIMIT: Int = 20
private const val DEFAULT_RETAINED_STACK_SAMPLE_LIMIT: Int = 60
```

- [x] **Step 6: Run collector and SDK unit tests**

Run:

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.collector.looper.MainLooperTimelineCollectorTest
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.core.timeline.MainThreadEvidenceStoreTest
```

Expected: PASS.

- [x] **Step 7: Commit the wiring**

Run:

```bash
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/collector/looper/MainLooperTimelineCollector.kt anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/collector/looper/MainLooperTimelineCollectorTest.kt anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/internal/AnrMonitorRuntime.kt
git commit -m "feat: wire looper evidence store"
```

---

### Task 4: Encode New JSON Fields

**Files:**
- Modify: `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter/encoder/AnrReportJsonEncoder.kt`
- Modify: `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/reporter/encoder/AnrReportJsonEncoderTest.kt`

- [x] **Step 1: Run impact analysis before editing `AnrReportJsonEncoder`**

Run GitNexus:

```text
impact({
  repo: "Vibe-ANR-Monitoring",
  target: "AnrReportJsonEncoder",
  direction: "upstream",
  maxDepth: 2,
  limit: 50
})
```

Expected: report encoder consumers and risk. Proceed only if not high or critical.

- [x] **Step 2: Add a failing JSON field test with non-empty evidence**

Add this test to `AnrReportJsonEncoderTest`:

```kotlin
/**
 * 主线程慢历史、聚合风暴和保留状态必须进入兼容 JSON 扩展字段。
 */
@Test
fun encodeIncludesMainThreadRetainedEvidence(): Unit {
    val report: AnrReport = AnrReport.empty(
        appId = "demo",
        environment = "test",
    ).copy(
        snapshot = AnrReport.empty(appId = "demo", environment = "test").snapshot.copy(
            historyMessages = listOf(message(seq = 10L, kind = MessageRecordKind.HISTORY, wallMs = 20L, count = 1)),
            slowHistoryMessages = listOf(
                message(
                    seq = 1L,
                    kind = MessageRecordKind.HISTORY,
                    wallMs = 1_500L,
                    count = 1,
                    sampleStackIds = listOf("sample-slow"),
                ),
            ),
            aggregatedBursts = listOf(
                message(
                    seq = 2L,
                    kind = MessageRecordKind.AGGREGATED,
                    wallMs = 600L,
                    count = 30,
                ),
            ),
            stackSamples = listOf(
                StackSampleRecord(
                    stackId = "sample-slow",
                    frames = listOf("com.example.Slow.run(Slow.kt:10)"),
                    hitCount = 2,
                ),
            ),
            mainThreadRetention = MainThreadRetentionStats(
                historyLimit = 120,
                slowHistoryLimit = 20,
                aggregatedBurstLimit = 20,
                stackSampleLimit = 60,
                historyDroppedCount = 380L,
                slowHistoryDroppedCount = 0L,
                aggregatedMessageCount = 30L,
                aggregationEnabled = true,
                truncated = true,
            ),
        ),
    )

    val json: String = AnrReportJsonEncoder().encode(report = report)

    assertTrue(json.contains("\"slowHistory\""))
    assertTrue(json.contains("\"seq\":1"))
    assertTrue(json.contains("\"sampleStackIds\":[\"sample-slow\"]"))
    assertTrue(json.contains("\"aggregatedBursts\""))
    assertTrue(json.contains("\"kind\":\"AGGREGATED\""))
    assertTrue(json.contains("\"count\":30"))
    assertTrue(json.contains("\"retention\""))
    assertTrue(json.contains("\"historyDroppedCount\":380"))
    assertTrue(json.contains("\"aggregatedMessageCount\":30"))
    assertTrue(json.contains("\"truncated\":true"))
}
```

Add imports:

```kotlin
import com.valiantyan.anrmonitor.domain.model.MainThreadRetentionStats
import com.valiantyan.anrmonitor.domain.model.MessageRecord
import com.valiantyan.anrmonitor.domain.model.MessageRecordKind
import com.valiantyan.anrmonitor.domain.model.StackSampleRecord
```

Add helper at the bottom of the test class:

```kotlin
private fun message(
    seq: Long,
    kind: MessageRecordKind,
    wallMs: Long,
    count: Int,
    sampleStackIds: List<String> = emptyList(),
): MessageRecord {
    return MessageRecord(
        seq = seq,
        kind = kind,
        messageType = "looper_dispatch",
        what = null,
        targetClass = "android.os.Handler",
        callbackClass = "com.example.RefreshRunnable",
        isCriticalComponent = false,
        startUptimeMs = seq * 10L,
        endUptimeMs = seq * 10L + wallMs,
        wallMs = wallMs,
        cpuMs = wallMs,
        count = count,
        sampleStackIds = sampleStackIds,
    )
}
```

- [x] **Step 3: Run the failing encoder tests**

Run:

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.reporter.encoder.AnrReportJsonEncoderTest
```

Expected: FAIL until encoder emits new fields.

- [x] **Step 4: Update encoder imports and `mainThreadJson`**

In `AnrReportJsonEncoder.kt`, add:

```kotlin
import com.valiantyan.anrmonitor.domain.model.MainThreadRetentionStats
```

Change `mainThreadJson` fields to:

```kotlin
val fields: List<String> = listOf(
    "\"stackId\":${string(report.snapshot.mainThreadStack.stackId)}",
    "\"threadName\":${string(report.snapshot.mainThreadStack.threadName)}",
    "\"current\":${messageOrNull(record = report.snapshot.currentMessage)}",
    "\"history\":${messages(records = report.snapshot.historyMessages)}",
    "\"slowHistory\":${messages(records = report.snapshot.slowHistoryMessages)}",
    "\"aggregatedBursts\":${messages(records = report.snapshot.aggregatedBursts)}",
    "\"stackFrames\":${strings(values = report.snapshot.mainThreadStack.frames)}",
    "\"stackSamples\":${stackSamples(samples = report.snapshot.stackSamples)}",
    "\"retention\":${mainThreadRetention(stats = report.snapshot.mainThreadRetention)}",
)
```

Add this private method near `stackSamples`:

```kotlin
private fun mainThreadRetention(stats: MainThreadRetentionStats): String {
    val fields: List<String> = listOf(
        "\"historyLimit\":${stats.historyLimit}",
        "\"slowHistoryLimit\":${stats.slowHistoryLimit}",
        "\"aggregatedBurstLimit\":${stats.aggregatedBurstLimit}",
        "\"stackSampleLimit\":${stats.stackSampleLimit}",
        "\"historyDroppedCount\":${stats.historyDroppedCount}",
        "\"slowHistoryDroppedCount\":${stats.slowHistoryDroppedCount}",
        "\"aggregatedMessageCount\":${stats.aggregatedMessageCount}",
        "\"aggregationEnabled\":${stats.aggregationEnabled}",
        "\"truncated\":${stats.truncated}",
    )
    return "{${fields.joinToString(separator = ",")}}"
}
```

- [x] **Step 5: Run encoder tests**

Run:

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.reporter.encoder.AnrReportJsonEncoderTest
```

Expected: PASS.

- [x] **Step 6: Commit the JSON encoder**

Run:

```bash
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter/encoder/AnrReportJsonEncoder.kt anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/reporter/encoder/AnrReportJsonEncoderTest.kt
git commit -m "feat: encode retained main thread evidence"
```

---

### Task 5: Update Attribution to Consume Retained Evidence

**Files:**
- Modify: `anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzer.kt`
- Modify: `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzerTest.kt`

- [x] **Step 1: Run impact analysis before editing `AttributionAnalyzer`**

Run GitNexus:

```text
impact({
  repo: "Vibe-ANR-Monitoring",
  target: "AttributionAnalyzer",
  direction: "upstream",
  maxDepth: 2,
  limit: 50
})
```

Expected: report affected tests and assembler. Proceed only if risk is not high or critical.

- [x] **Step 2: Add failing attribution tests**

In `AttributionAnalyzerTest`, extend the `snapshot` helper with optional fields:

```kotlin
slowHistory: List<MessageRecord> = emptyList(),
aggregatedBursts: List<MessageRecord> = emptyList(),
mainThreadRetention: MainThreadRetentionStats = MainThreadRetentionStats.empty(),
```

Then pass them into `AnrSnapshot`:

```kotlin
slowHistoryMessages = slowHistory,
aggregatedBursts = aggregatedBursts,
mainThreadRetention = mainThreadRetention,
```

Add imports:

```kotlin
import com.valiantyan.anrmonitor.domain.model.MainThreadRetentionStats
```

Add these test methods:

```kotlin
/**
 * 慢历史保留层比最近 history 更能解释前序慢消息，应优先参与历史慢消息归因。
 */
@Test
fun analyzeReturnsHistorySlowFromSlowHistoryWhenRecentHistoryMissesIt(): Unit {
    val result = AttributionAnalyzer().analyze(
        snapshot = snapshot(
            current = message(seq = 9L, wallMs = 20L, cpuMs = 10L),
            history = listOf(message(seq = 10L, wallMs = 10L, cpuMs = 5L)),
            slowHistory = listOf(message(seq = 1L, wallMs = 7_000L, cpuMs = 20L)),
            pending = emptyList(),
            frames = emptyList(),
        ),
    )

    assertEquals(AnrAttributionCode.HISTORY_MESSAGE_SLOW, result.primaryCode)
    assertTrue(result.evidenceItems.contains("slow history message seq=1 wall=7000ms"))
}

/**
 * Pending 队列无法提供重复 target 时，聚合短消息摘要仍可识别消息风暴。
 */
@Test
fun analyzeReturnsMessageStormFromAggregatedBursts(): Unit {
    val result = AttributionAnalyzer().analyze(
        snapshot = snapshot(
            current = message(seq = 9L, wallMs = 20L, cpuMs = 10L),
            history = emptyList(),
            aggregatedBursts = listOf(
                message(
                    seq = 2L,
                    wallMs = 800L,
                    cpuMs = 600L,
                    count = 30,
                    kind = MessageRecordKind.AGGREGATED,
                ),
            ),
            pending = emptyList(),
            frames = emptyList(),
        ),
    )

    assertEquals(AnrAttributionCode.MESSAGE_STORM, result.primaryCode)
    assertTrue(result.evidenceItems.contains("aggregated burst seq=2 count=30 wall=800ms"))
}

/**
 * 慢历史窗口发生淘汰时，未知归因必须显式提示证据缺口。
 */
@Test
fun analyzeUnknownMentionsSlowHistoryTruncation(): Unit {
    val result = AttributionAnalyzer().analyze(
        snapshot = snapshot(
            current = null,
            history = emptyList(),
            pending = emptyList(),
            frames = emptyList(),
            mainThreadRetention = MainThreadRetentionStats(
                historyLimit = 120,
                slowHistoryLimit = 20,
                aggregatedBurstLimit = 20,
                stackSampleLimit = 60,
                historyDroppedCount = 0L,
                slowHistoryDroppedCount = 2L,
                aggregatedMessageCount = 0L,
                aggregationEnabled = true,
                truncated = true,
            ),
        ),
    )

    assertEquals(AnrAttributionCode.UNKNOWN_INSUFFICIENT_EVIDENCE, result.primaryCode)
    assertTrue(result.missingEvidence.contains("slow history dropped count=2"))
}
```

Update the `message` helper signature and return:

```kotlin
private fun message(
    seq: Long,
    wallMs: Long,
    cpuMs: Long,
    sampleStackIds: List<String> = emptyList(),
    count: Int = 1,
    kind: MessageRecordKind = MessageRecordKind.HISTORY,
): MessageRecord {
    return MessageRecord(
        seq = seq,
        kind = kind,
        messageType = "looper_dispatch",
        what = null,
        targetClass = "android.os.Handler",
        callbackClass = null,
        isCriticalComponent = false,
        startUptimeMs = 0L,
        endUptimeMs = wallMs,
        wallMs = wallMs,
        cpuMs = cpuMs,
        count = count,
        sampleStackIds = sampleStackIds,
    )
}
```

- [x] **Step 3: Run attribution tests to verify failure**

Run:

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.domain.analyzer.AttributionAnalyzerTest
```

Expected: FAIL until analyzer reads new fields.

- [x] **Step 4: Update analyzer ordering and helpers**

In `AttributionAnalyzer.analyze`, replace:

```kotlin
val stormResult: AttributionResult? = analyzeMessageStorm(summary = pendingSummary)
```

with:

```kotlin
val stormResult: AttributionResult? = analyzeMessageStorm(
    summary = pendingSummary,
    aggregatedBursts = snapshot.aggregatedBursts,
)
```

Replace:

```kotlin
val historyResult: AttributionResult? = analyzeHistory(history = snapshot.historyMessages)
```

with:

```kotlin
val historyResult: AttributionResult? = analyzeHistory(
    slowHistory = snapshot.slowHistoryMessages,
    history = snapshot.historyMessages,
)
```

Replace `analyzeHistory` with:

```kotlin
private fun analyzeHistory(
    slowHistory: List<MessageRecord>,
    history: List<MessageRecord>,
): AttributionResult? {
    val retainedSlowMessage: MessageRecord? = slowHistory.firstOrNull { record ->
        record.wallMs >= thresholds.suspectAnrMs
    }
    if (retainedSlowMessage != null) {
        return result(
            code = AnrAttributionCode.HISTORY_MESSAGE_SLOW,
            confidence = Confidence.MEDIUM,
            evidence = listOf("slow history message seq=${retainedSlowMessage.seq} wall=${retainedSlowMessage.wallMs}ms"),
            suggestion = "优先回看 slowHistory 中保留的前序慢消息和对应 stackSamples。",
        )
    }
    val slowMessage: MessageRecord = history.firstOrNull { record ->
        record.wallMs >= thresholds.suspectAnrMs
    } ?: return null
    return result(
        code = AnrAttributionCode.HISTORY_MESSAGE_SLOW,
        confidence = Confidence.MEDIUM,
        evidence = listOf("history message seq=${slowMessage.seq} wall=${slowMessage.wallMs}ms"),
        suggestion = "回看 ANR 前历史消息，而不是只按当前 Trace 派单。",
    )
}
```

Replace `analyzeMessageStorm` with:

```kotlin
private fun analyzeMessageStorm(
    summary: PendingQueueSummary,
    aggregatedBursts: List<MessageRecord>,
): AttributionResult? {
    if (summary.repeatedTargetCount >= thresholds.messageStormCount) {
        return result(
            code = AnrAttributionCode.MESSAGE_STORM,
            confidence = Confidence.MEDIUM,
            evidence = listOf("pending repeated target count=${summary.repeatedTargetCount}"),
            suggestion = "合并重复 Handler 消息，增加去重、防抖或队列清理。",
        )
    }
    val burst: MessageRecord = aggregatedBursts.firstOrNull { record ->
        record.count >= thresholds.messageStormCount || record.wallMs >= thresholds.slowMessageMs
    } ?: return null
    return result(
        code = AnrAttributionCode.MESSAGE_STORM,
        confidence = Confidence.MEDIUM,
        evidence = listOf("aggregated burst seq=${burst.seq} count=${burst.count} wall=${burst.wallMs}ms"),
        suggestion = "合并重复 Handler 消息，增加去重、防抖或队列清理。",
    )
}
```

In `unknownResult`, add:

```kotlin
if (snapshot.mainThreadRetention.slowHistoryDroppedCount > 0L) {
    missingEvidence += "slow history dropped count=${snapshot.mainThreadRetention.slowHistoryDroppedCount}"
}
```

- [x] **Step 5: Run attribution tests**

Run:

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.domain.analyzer.AttributionAnalyzerTest
```

Expected: PASS.

- [x] **Step 6: Commit attribution**

Run:

```bash
git add anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzer.kt anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/domain/analyzer/AttributionAnalyzerTest.kt
git commit -m "feat: analyze retained main thread evidence"
```

---

### Task 6: Add End-to-End SDK Regression Coverage

**Files:**
- Modify: `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/acceptance/FullAcceptanceMatrixTest.kt`
- Modify: `anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/core/timeline/MainThreadEvidenceStoreTest.kt`

- [x] **Step 1: Add a focused store regression for the spec’s 500-message scenario**

Add this test to `MainThreadEvidenceStoreTest`:

```kotlin
@Test
fun slowMessageWithSamplesSurvivesFiveHundredLaterShortMessages(): Unit {
    val store = MainThreadEvidenceStore(
        historyLimit = 120,
        slowHistoryLimit = 20,
        aggregatedBurstLimit = 20,
        stackSampleLimit = 60,
        slowMessageMs = 1_000L,
        shortMessageAggregateMs = 300L,
        messageBurstCountThreshold = 20,
    )
    val slowSample = StackSampleRecord(
        stackId = "slow-stack",
        frames = listOf("com.example.Database.open(Database.kt:42)"),
        hitCount = 3,
    )

    store.addFinishedMessage(
        record = message(seq = 1L, wallMs = 4_500L, sampleStackIds = listOf("slow-stack")),
        stackSamples = listOf(slowSample),
    )
    (2L..501L).forEach { seq ->
        store.addFinishedMessage(
            record = message(
                seq = seq,
                wallMs = 1L,
                callbackClass = "com.example.ShortRunnable",
            ),
        )
    }

    val snapshot = store.snapshot(currentMessage = null)

    assertEquals(listOf(1L), snapshot.slowHistoryMessages.map { record -> record.seq })
    assertEquals(listOf("slow-stack"), snapshot.stackSamples.map { record -> record.stackId })
    assertTrue(snapshot.aggregatedBursts.isNotEmpty())
    assertTrue(snapshot.retention.historyDroppedCount > 0L)
}
```

- [x] **Step 2: Add acceptance coverage for docs and source contracts**

Add this test to `FullAcceptanceMatrixTest`:

```kotlin
/**
 * 主线程证据保留扩展必须同时进入服务端协议、排查指南和 SDK 源码。
 */
@Test
fun retainedMainThreadEvidenceContractIsDocumentedAndImplemented(): Unit {
    val rootDir: File = findProjectRoot()
    val protocolText: String = rootDir.resolve("docs-anr/102-ANR监控SDK服务端消费协议.md").readText()
    val guideText: String = rootDir.resolve("docs-anr/104-ANR监控JSON日志根因排查指南.md").readText()
    val readmeText: String = rootDir.resolve("README.md").readText()
    val snapshotText: String = rootDir.resolve(
        "anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/domain/model/AnrSnapshot.kt",
    ).readText()
    val encoderText: String = rootDir.resolve(
        "anr-monitor-sdk/src/main/java/com/valiantyan/anrmonitor/reporter/encoder/AnrReportJsonEncoder.kt",
    ).readText()

    listOf(
        protocolText,
        guideText,
        readmeText,
        snapshotText,
        encoderText,
    ).forEach { content: String ->
        assertContains(content, "slowHistory")
        assertContains(content, "aggregatedBursts")
        assertContains(content, "retention")
    }
    assertContains(protocolText, "historyDroppedCount")
    assertContains(protocolText, "slowHistoryDroppedCount")
    assertContains(guideText, "retention.truncated")
}
```

The JSON shape itself remains covered by `AnrReportJsonEncoderTest.encodeIncludesMainThreadRetainedEvidence`, which asserts:

```kotlin
assertTrue(json.contains("\"slowHistory\""))
assertTrue(json.contains("\"aggregatedBursts\""))
assertTrue(json.contains("\"retention\""))
assertTrue(json.contains("\"historyDroppedCount\""))
assertTrue(json.contains("\"sampleStackIds\":[\"slow-stack\"]"))
```

- [x] **Step 3: Run focused regression tests**

Run:

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest --tests com.valiantyan.anrmonitor.core.timeline.MainThreadEvidenceStoreTest --tests com.valiantyan.anrmonitor.acceptance.FullAcceptanceMatrixTest
```

Expected: PASS.

- [x] **Step 4: Commit regression coverage**

Run:

```bash
git add anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/core/timeline/MainThreadEvidenceStoreTest.kt anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/acceptance/FullAcceptanceMatrixTest.kt anr-monitor-sdk/src/test/java/com/valiantyan/anrmonitor/reporter/encoder/AnrReportJsonEncoderTest.kt
git commit -m "test: cover retained ANR evidence regression"
```

---

### Task 7: Update Documentation for the Compatible JSON Extension

**Files:**
- Modify: `README.md`
- Modify: `docs-anr/102-ANR监控SDK服务端消费协议.md`
- Modify: `docs-anr/104-ANR监控JSON日志根因排查指南.md`
- Modify: `docs-anr/99-ANR监控SDK设计开发文档.md`

- [x] **Step 1: Update `README.md` JSON guidance**

Near the existing `mainThread.stackSamples` explanation, add:

```markdown
`mainThread.history` 仍表示最近主线程消息窗口。为了避免历史慢消息被后续大量短消息挤出，报告还会输出 `mainThread.slowHistory`、`mainThread.aggregatedBursts` 和 `mainThread.retention`：

- `slowHistory`：独立保留历史慢消息、关联采样栈的消息，以及耗时达到聚合阈值的关键组件消息。
- `aggregatedBursts`：连续重复短消息的聚合摘要，`kind=AGGREGATED` 且 `count>1`。
- `retention`：说明各窗口上限、淘汰数量、聚合消息数量和本次证据是否发生裁剪。
```

Update the reading order paragraph to:

```markdown
排查根因时建议按这个顺序读：先用 `mainThread.stackFrames` 定位当前主线程现场，再看 `mainThread.current.wallMs`、`cpuMs` 和消息目标确认当前消息是否已经耗尽 ANR 窗口；如果最终现场不能单独解释根因，再看 `mainThread.slowHistory` 和它引用的 `stackSamples`，随后结合 `history`、`aggregatedBursts`、`pendingQueue`、`barrierEvidence` 和 `binderBlock` 判断是否存在前序慢消息、消息风暴、Barrier 或 Binder 阻塞。若 `retention.truncated=true`，排查结论要同时记录证据曾被裁剪。
```

- [x] **Step 2: Update server protocol docs**

In `docs-anr/102-ANR监控SDK服务端消费协议.md`, add field definitions under the `mainThread` section:

```markdown
| `mainThread.slowHistory` | array | 否 | 与 `history` 相同的 `MessageRecord` 结构；用于服务端优先消费历史慢消息和关键慢组件消息。 |
| `mainThread.aggregatedBursts` | array | 否 | `kind=AGGREGATED` 的短消息风暴摘要，`count` 表示聚合消息数，`wallMs/cpuMs` 表示聚合范围内累计耗时。 |
| `mainThread.retention` | object | 否 | 主线程证据保留状态，包含 `historyLimit`、`slowHistoryLimit`、`aggregatedBurstLimit`、`stackSampleLimit`、`historyDroppedCount`、`slowHistoryDroppedCount`、`aggregatedMessageCount`、`aggregationEnabled`、`truncated`。 |
```

Add server consumption guidance:

```markdown
服务端归因消费顺序建议：`current` 和 `stackFrames` 先判断当前消息；当前现场不足时优先读取 `slowHistory` 及其 `sampleStackIds` 对应的 `stackSamples`；再读取 `aggregatedBursts` 判断消息风暴；最后用 `history` 补齐最近时间线。`retention.truncated=true` 表示至少一个窗口因容量限制丢弃过证据，服务端展示时应避免写成“证据完整”。
```

- [x] **Step 3: Update JSON root-cause guide**

In `docs-anr/104-ANR监控JSON日志根因排查指南.md`, update the `mainThread` field table to include:

```markdown
| `mainThread.slowHistory` | 被独立保留的历史慢消息和关键慢组件消息 | 优先复核历史慢消息根因 |
| `mainThread.aggregatedBursts` | 连续重复短消息的聚合摘要 | 复核 `MESSAGE_STORM` |
| `mainThread.retention` | 主线程证据保留和裁剪状态 | 判断是否存在证据缺口 |
```

Update the `HISTORY_MESSAGE_SLOW` section to include:

```markdown
优先查看 `mainThread.slowHistory`。如果目标消息已经不在 `history`，但仍在 `slowHistory` 并且 `sampleStackIds` 能在 `stackSamples` 中找到对应栈，仍可按历史慢消息分析。若 `retention.slowHistoryDroppedCount > 0`，需要在结论中标注慢历史证据曾发生淘汰。
```

Update the `MESSAGE_STORM` section to include:

```markdown
除了 Pending 队列中的重复 target/callback，也要查看 `mainThread.aggregatedBursts`。`kind=AGGREGATED`、`count` 很大或累计 `wallMs` 很高时，说明报告已经把连续短消息折叠为摘要，不能再用 `history` 中短消息数量少来否定消息风暴。
```

- [x] **Step 4: Update design-development docs**

In `docs-anr/99-ANR监控SDK设计开发文档.md`, update the section describing `MessageRecord` history and short-message aggregation with:

```markdown
实现层使用 `MainThreadEvidenceStore` 分层保存主线程证据：`history` 只承担最近窗口兼容语义；`slowHistory` 独立保留慢消息、带采样栈的消息和关键组件慢消息；`aggregatedBursts` 记录连续重复短消息风暴；`retention` 输出窗口上限、淘汰计数和聚合计数。这样历史慢消息和它引用的 `stackSamples` 不会被普通短消息风暴静默挤出。
```

- [x] **Step 5: Run documentation self-check**

Run:

```bash
rg -n "slowHistory|aggregatedBursts|retention|historyDroppedCount|slowHistoryDroppedCount" README.md docs-anr/102-ANR监控SDK服务端消费协议.md docs-anr/104-ANR监控JSON日志根因排查指南.md docs-anr/99-ANR监控SDK设计开发文档.md
```

Expected: each edited document has at least one relevant hit.

- [x] **Step 6: Commit documentation**

Run:

```bash
git add README.md docs-anr/102-ANR监控SDK服务端消费协议.md docs-anr/104-ANR监控JSON日志根因排查指南.md docs-anr/99-ANR监控SDK设计开发文档.md
git commit -m "docs: document retained ANR evidence fields"
```

---

### Task 8: Final Verification and Change Detection

**Files:**
- Verify all files changed by Tasks 1-7.

- [x] **Step 1: Run SDK unit tests**

Run:

```bash
./gradlew :anr-monitor-sdk:testDebugUnitTest
```

Expected: PASS.

- [x] **Step 2: Run SDK Kotlin compile**

Run:

```bash
./gradlew :anr-monitor-sdk:compileDebugKotlin
```

Expected: PASS.

- [x] **Step 3: Run app unit tests if README or app-facing guidance changed**

Run:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: PASS. If this fails for an unrelated existing app issue, capture the failing test name and error before deciding whether to fix it.

- [x] **Step 4: Run GitNexus change detection before final commit or handoff**

Run GitNexus:

```text
detect_changes({
  repo: "Vibe-ANR-Monitoring",
  scope: "all"
})
```

Expected: changed symbols should be limited to evidence models/store, Looper timeline wiring, runtime snapshot construction, JSON encoder, attribution analyzer, tests, and docs.

- [x] **Step 5: Inspect git status**

Run:

```bash
git status --short
```

Expected: only intentional files from this plan are modified or all work is committed. Any pre-existing `AGENTS.md` change should remain untouched and uncommitted unless the user explicitly asks otherwise.

---

## Review Question Coverage

| Question | Answer in this plan |
| --- | --- |
| Can an implementer find which files to touch and why? | Yes. The File Structure section maps every created or modified file to one responsibility before tasks begin. |
| Does the plan preserve old JSON consumers? | Yes. Task 1 keeps new `AnrSnapshot` fields defaulted, and Task 4 adds JSON fields without removing `current`, `history`, `stackFrames`, or `stackSamples`. |
| Does it explain why increasing `historyBufferSize` is not the fix? | Yes. The Source Spec and Task 2 implement value-based retention with bounded slow history and aggregation rather than a larger single ring buffer. |
| Can current-message stack samples still appear in JSON? | Yes. Task 3 adds `MainLooperTimelineCollector.stackSamplesFor(...)` and passes those samples into `MainThreadEvidenceStore.snapshot(...)` while building the runtime snapshot. |
| Can completed slow-message samples survive after the message leaves recent history? | Yes. Task 2 stores samples with `addFinishedMessage(...)`, trims by references from `slowHistory`, and Task 6 proves the 500-short-message scenario. |
| Does the plan define what `aggregatedMessageCount` counts? | Yes. The model KDoc and tests define it as the number of original short messages folded into aggregate records. |
| Does attribution actually consume the new evidence? | Yes. Task 5 updates `AttributionAnalyzer` to read `slowHistoryMessages`, `aggregatedBursts`, and slow-history truncation. |
| Are docs and service protocol part of the deliverable? | Yes. Task 7 updates README, service protocol, JSON guide, and design-development docs; Task 6 adds acceptance coverage that scans docs and source contracts. |
| What prevents accidental broad or risky edits? | The Repo-Specific Guardrails require GitNexus impact analysis before editing each Kotlin symbol and stop on high or critical risk. |
| What final commands prove the implementation? | Task 8 runs SDK unit tests, SDK Kotlin compile, app unit tests for app-facing guidance, GitNexus `detect_changes`, and `git status --short`. |

## Three-Round Cross Review

### Round 1: Reviewer / Interviewer Questions

- Finding: The original plan said the store should retain stack samples, but did not explicitly answer whether current in-flight samples still reach JSON before a message finishes.
- Resolution: Task 3 now requires a narrow `stackSamplesFor(sampleStackIds)` bridge from `MainLooperTimelineCollector` and passes `currentStackSamples` into `MainThreadEvidenceStore.snapshot(...)`.

### Round 2: Implementer / Test Writer

- Finding: `aggregatedMessageCount` could be mistaken for accumulated wall time because the burst test also asserts `wallMs = 60`.
- Resolution: Task 2 asserts `aggregatedMessageCount = 3L` for three folded messages and the KDoc defines it as original message count.

### Round 3: Maintainer / Regression Reviewer

- Finding: Acceptance coverage should match the existing `FullAcceptanceMatrixTest` style, which scans docs and source contracts instead of constructing JSON reports.
- Resolution: Task 6 keeps JSON shape assertions in `AnrReportJsonEncoderTest` and adds a contract-scanning acceptance test for docs and source files.

## Self-Review

### Spec Coverage

- Preserves historical slow messages: Task 2 and Task 6.
- Keeps stack samples linked to retained slow messages and current messages: Task 2 and Task 3.
- Represents short-message storms as aggregates: Task 2, Task 5, and Task 6.
- Extends JSON compatibly: Task 1 and Task 4.
- Exposes retention and truncation metadata: Task 1, Task 2, Task 4, and Task 5.
- Keeps runtime overhead bounded: Task 2 uses bounded arrays and rolling burst state only.
- Updates docs and protocol guidance: Task 7.

### Placeholder Scan

The plan intentionally avoids placeholder markers and unfilled implementation slots. Every code-changing step includes concrete code or exact edits, and every test step includes an exact Gradle command and expected result.

### Type Consistency

- `MainThreadEvidenceStore.snapshot(currentMessage: MessageRecord?, currentStackSamples: List<StackSampleRecord>)` returns `MainThreadEvidenceSnapshot`.
- `AnrSnapshot` fields are consistently named `slowHistoryMessages`, `aggregatedBursts`, and `mainThreadRetention`.
- JSON fields are consistently named `slowHistory`, `aggregatedBursts`, and `retention`.
- Retention stats are consistently represented by `MainThreadRetentionStats`.
