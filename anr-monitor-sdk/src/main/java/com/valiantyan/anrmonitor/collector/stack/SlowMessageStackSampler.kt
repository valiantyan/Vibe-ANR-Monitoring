package com.valiantyan.anrmonitor.collector.stack

import com.valiantyan.anrmonitor.domain.model.StackSampleRecord

/**
 * 慢消息中的栈采样聚合结果，用于把同一执行热点压缩成稳定证据。
 *
 * @property stackHash 栈帧内容生成的指纹。
 * @property frames 本次采样代表的主线程栈帧。
 * @property hitCount 同一 [stackHash] 在当前消息内命中的次数。
 */
data class StackSample(
    val stackHash: String,
    val frames: List<String>,
    val hitCount: Int,
)

/**
 * 慢消息堆栈采样器，按消息序号隔离采样，并按栈指纹合并重复热点。
 *
 * @param maxSamplesPerMessage 单条消息最多采集的栈样本次数。
 * @param frameProvider 主线程栈帧提供方，运行时可接入 [MainThreadStackCollector]。
 */
class SlowMessageStackSampler(
    private val maxSamplesPerMessage: Int,
    private val frameProvider: () -> List<String>,
) {
    // 不同消息的采样桶互相隔离，避免长消息结束时污染后续消息。
    private val samplesBySeq: MutableMap<Long, MutableMap<String, StackSample>> = mutableMapOf()

    /**
     * 为一条消息开启采样桶；重复开启同一 [seq] 会清理旧采样，匹配新的 dispatch 周期。
     *
     * @param seq 主线程消息序号。
     */
    @Synchronized
    fun startMessage(seq: Long): Unit {
        samplesBySeq[seq] = mutableMapOf()
    }

    /**
     * 采集一次主线程栈，并在达到 [maxSamplesPerMessage] 后停止写入，控制 SDK 自身成本。
     *
     * @param seq 主线程消息序号。
     */
    @Synchronized
    fun collectSample(seq: Long): Unit {
        val samples: MutableMap<String, StackSample> = samplesBySeq[seq] ?: return
        if (getTotalHitCount(samples = samples) >= maxSamplesPerMessage) {
            return
        }
        val frames: List<String> = frameProvider()
        val hash: String = getStackHash(frames = frames)
        val previous: StackSample? = samples[hash]
        samples[hash] = StackSample(
            stackHash = hash,
            frames = frames,
            hitCount = (previous?.hitCount ?: 0) + 1,
        )
    }

    /**
     * 结束指定消息采样并返回聚合结果，同时移除内部状态，防止采样桶泄漏。
     *
     * @param seq 主线程消息序号。
     * @return 当前消息内按栈指纹聚合后的采样列表。
     */
    @Synchronized
    fun finishMessage(seq: Long): List<StackSample> {
        return samplesBySeq.remove(seq)?.values?.toList().orEmpty()
    }

    /**
     * 返回指定消息当前已采集的栈样本，不移除内部状态，供疑似 ANR 快照关联当前消息。
     *
     * @param seq 主线程消息序号。
     * @return 当前消息内按栈指纹聚合后的采样记录。
     */
    @Synchronized
    fun snapshotSampleRecords(seq: Long): List<StackSampleRecord> {
        return samplesBySeq[seq]?.values
            ?.map { sample: StackSample -> sample.toRecord() }
            .orEmpty()
    }

    // 统计当前消息已经采集的次数，命中次数之和才代表真实采样成本。
    private fun getTotalHitCount(samples: Map<String, StackSample>): Int {
        return samples.values.sumOf { sample: StackSample -> sample.hitCount }
    }

    // 使用完整栈帧内容生成稳定指纹，便于报告侧按 [stackHash] 关联消息。
    private fun getStackHash(frames: List<String>): String {
        return frames.joinToString(separator = "\n").hashCode().toString()
    }

    // 将采样器内部模型转换为报告领域模型，避免上层依赖采样桶实现。
    private fun StackSample.toRecord(): StackSampleRecord {
        return StackSampleRecord(
            stackId = stackHash,
            frames = frames,
            hitCount = hitCount,
        )
    }
}
