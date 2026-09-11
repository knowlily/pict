package com.pict.metatool.data.job

import android.content.ContentResolver
import android.net.Uri
import com.pict.metatool.domain.job.JobReport

/**
 * 把报告写到用户挑的位置（FR-31 的「导出报告」）。
 *
 * 走 SAF：调用方先弹 `ACTION_CREATE_DOCUMENT` 拿到目标 [Uri]，这里只负责写。
 * 与项目里其它写操作一个态度——**写完读回来比一遍**（FR-33 的「写完读回校验」）：
 * 「写成功」这个返回值并不可靠（Network/云盘 Provider、配额、被系统掐断都会骗人），
 * 而报告导出的全部意义就是「事后能拿出来看」，写坏了却报成功是最坏的一种错。
 *
 * 编码一律 UTF-8；CSV 的 BOM 由 `JobReport.toCsv()` 自己带头（见那边的说明）。
 */
class JobReportExporter(private val resolver: ContentResolver) {

    /** 导出 CSV。返回是否**确认**写进去了。 */
    fun writeCsv(target: Uri, report: JobReport): Boolean = write(target, report.toCsv())

    /** 导出 JSON（含参数与种子，FR-31）。 */
    fun writeJson(target: Uri, report: JobReport): Boolean =
        write(target, JobReportJson.write(report))

    private fun write(target: Uri, text: String): Boolean = runCatching {
        val bytes = text.toByteArray(Charsets.UTF_8)
        // "wt" = 截断后写：同一个文件被导出第二次时不该把上一次的内容留在尾巴上
        val stream = resolver.openOutputStream(target, "wt") ?: return false
        stream.use { out ->
            out.write(bytes)
            out.flush()
        }
        val readBack = resolver.openInputStream(target)?.use { it.readBytes() } ?: return false
        readBack.contentEquals(bytes)
    }.getOrDefault(false)
}
