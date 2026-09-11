package com.pict.metatool.data.batch

import android.content.ContentResolver
import android.net.Uri
import com.pict.metatool.data.source.SafSource
import com.pict.metatool.domain.batch.BatchTarget
import com.pict.metatool.domain.model.ImageItem

/**
 * 把一串地址补成带来源信息的批量目标（docs/07 T5.4）。
 *
 * ### 为什么非补不可
 * 批量页是从图库多选跳过来的，路由里只有地址。若拿「末段当文件名 + 格式未知」的占位目标
 * 直接预览，`BatchPreviewer` 判可写性只能得到「未知格式 → 写不进去」，于是四个好好的 JPEG
 * 全被标成「不支持原地写元数据」——真机实测踩到的就是这个。名称还会显示成
 * `primary:Download/…` 这种 documentId，用户根本认不出是哪张图。
 *
 * ### FR-32 不破
 * 这里只做一次属性查询（名称 / MIME / 大小 / 可写位），**不读文件内容、不建临时文件、不写任何东西**。
 * 但查询是阻塞 IO，调用方负责扔到 `Dispatchers.IO`：几十上百张连着查会卡住界面。
 *
 * ### 为什么不在预览时再查
 * 图库列表里已经有一份属性快照（`SafSource.itemOf` 查出来的）。这里再查一次同样列的开销虽小，
 * 但**与图库里显示的名字 / 只读标记可能对不上**——用户会看到同张图两个说法。
 */
class SafBatchTargets(private val resolver: ContentResolver) {

    /**
     * 逐个查属性；查询失败的地址仍保留成「来源未知」的目标。
     *
     * 保留而不是丢弃，是因为「读不到」的原因要靠读取时才能说清楚（文件被删、授权过期……），
     * 提前丢掉只会让用户少看到一张图、还不告诉为什么。空地址直接跳过。
     */
    fun of(uris: List<String>): List<BatchTarget> = uris
        .filter { it.isNotBlank() }
        .map { targetOf(it) }

    private fun targetOf(raw: String): BatchTarget {
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
            ?: return unknown(raw)
        val item = runCatching { SafSource(resolver).itemOf(uri, ImageItem.Origin.FILE_PICKER) }
            .getOrNull()
            ?: return unknown(raw)
        return targetOf(item)
    }

    /** 查不到时的兜底：名字退化成地址末段，格式留未知（读取时会给出准确错误）。 */
    private fun unknown(raw: String): BatchTarget =
        BatchTarget.of(raw, raw.substringAfterLast('/'))

    companion object {

        /**
         * [ImageItem] → [BatchTarget]（纯函数，单测直接喂参数）。
         *
         * 名称、格式、可写位照搬图库那份快照。`origin` 不参与转换：批量目标不关心图是怎么进来的，
         * 查属性时随手给的 `FILE_PICKER` 也只是接口要一个值而已。
         */
        fun targetOf(item: ImageItem): BatchTarget = BatchTarget(
            uri = item.uri,
            info = item.toSourceInfo(),
            writable = item.writable,
        )
    }
}
