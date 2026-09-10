package com.pict.metatool.domain.plan

import com.pict.metatool.domain.model.FieldGroup
import com.pict.metatool.domain.model.TagKey
import com.pict.metatool.domain.model.TagValue

/**
 * 单条元数据编辑操作（docs/07 T2.1、docs/02 §4）。
 *
 * 设计约束：
 * - 本文件属于 domain 层，**不依赖任何 Android API**，可直接在 JVM 单测中构造；
 * - 操作只描述「要做什么」，不描述「怎么写」——折叠成目标 MetadataSet 由
 *   `EditPlanExecutor`（T2.2）负责，真正落盘由 data 层的 `MetadataWriter` 负责；
 * - 操作按 [EditPlan.operations] 的列表顺序依次生效，**后者覆盖前者**
 *   （docs/08：清除与设置冲突时后者胜）。
 *
 * 命名说明：docs/02 §4 把「应用预设」写作 `ApplyPreset`，docs/07 T2.1 的任务表
 * 记作 `PresetApply`；此处统一采用前者，语义与文档 §4 一致。
 *
 * GPS 编辑操作（[SetGps] / [JitterGps]）由 T2.8 定义，具体语义在 `GpsEditor`；
 * 整表清空（ClearAll）、格式转换（Convert）分别在 T2.9、Phase 4 引入，本任务不定义。
 */
sealed interface EditOperation {

    /**
     * 设置字段：存在则覆盖，不存在则新增。
     * 值类型不做校验，合法性交给 T2.2 折叠阶段按 FieldCatalog 判定。
     */
    data class SetField(val key: TagKey, val value: TagValue) : EditOperation

    /** 清除单个字段；字段本来不存在时为无操作。 */
    data class ClearField(val key: TagKey) : EditOperation

    /**
     * 清除整个分组。
     * 具体清哪些字段、哪些结构字段必须保护（如 FILE 组）由 T2.9 决定，
     * 这里只表达「用户想清空这一类」。
     */
    data class ClearGroup(val group: FieldGroup) : EditOperation

    /**
     * 时间整体平移。
     * 正值向未来、负值向过去；三个日期字段同步、OffsetTime 保留与溢出校验由
     * T2.7 实现，这里只承载偏移量。
     */
    data class TimeShift(val deltaMillis: Long) : EditOperation

    /**
     * 随机填充指定字段。
     * [seed] 相同则结果必须逐字段可复现（T3.6 验证跨 JVM 一致性），
     * 因此种子是操作的一部分而非全局配置。
     */
    data class RandomFill(val fields: Set<TagKey>, val seed: Long) : EditOperation

    /**
     * 写入 GPS 坐标（T2.8）：十进制度，负值即南纬/西经。
     *
     * `GPSLatitude/Longitude` 与其 `*Ref` 由 `GpsEditor` 一并写 —— 只改坐标不改 Ref
     * 会把南纬读成北纬，所以这里不提供「只写 Ref」或「只写坐标」的口子。
     *
     * @param altitudeMeters 为 null 表示不改海拔（保留源值）
     */
    data class SetGps(
        val latitude: Double,
        val longitude: Double,
        val altitudeMeters: Double? = null,
    ) : EditOperation

    /**
     * GPS 就近抖动（T2.8）：在源坐标 [radiusMeters] 米内随机取一点（球面等距近似）。
     *
     * [seed] 相同则结果必须逐字段可复现（同 T3.6 口径），因此种子是操作的一部分。
     */
    data class JitterGps(
        val radiusMeters: Double = GpsEditor.DEFAULT_JITTER_METERS,
        val seed: Long,
    ) : EditOperation

    /**
     * 应用预设。
     *
     * @param presetId 预设标识（T3.1 定义模型，这里只存 id，避免 domain/plan 反向依赖 domain/preset）
     * @param overwriteExisting false = 只填源文件缺失的字段，保留已有值（默认，更安全）
     */
    data class ApplyPreset(
        val presetId: String,
        val overwriteExisting: Boolean = false,
    ) : EditOperation
}

/**
 * 一次编辑的完整意图（docs/07 T2.1）。
 *
 * 不可变；构建后交给 T2.2 折叠成「目标 MetadataSet + 变更 diff」。
 *
 * 范围说明：输出目录/命名模板/并发数等批量选项属于 Phase 4-5 的扩展，
 * 元数据迁移策略（KEEP_ALL 等）由 T4.6 引入，本任务不预设这些字段。
 */
data class EditPlan(
    val operations: List<EditOperation> = emptyList(),

    /** 只计算 diff、不真正落盘（UI 的「预览」用）。 */
    val dryRun: Boolean = false,

    /** 覆盖目标文件前是否先备份（T2.11 的 BackupManager 消费此标志）。 */
    val backupBeforeOverwrite: Boolean = true,
) {

    val isEmpty: Boolean get() = operations.isEmpty()

    val size: Int get() = operations.size
}
