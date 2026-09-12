package com.pict.metatool.core.error

import androidx.annotation.StringRes
import com.pict.metatool.R

/**
 * 全局错误码（docs/01 §6）。
 *
 * 约定：
 * - `code` 是稳定标识，用于日志与导出报告，**不要**跟着文案一起改。
 * - `messageRes` 是给用户看的一句话，不含技术细节。
 * - 新增错误码必须同步 docs/01 §6 与 docs/08 的测试用例。
 */
enum class PictError(
    val code: String,
    @param:StringRes val messageRes: Int,
) {
    IO_OPEN("E-IO-OPEN", R.string.error_io_open),
    IO_READ("E-IO-READ", R.string.error_io_read),
    IO_WRITE("E-IO-WRITE", R.string.error_io_write),
    STORAGE_READONLY("E-STORAGE-READONLY", R.string.error_storage_readonly),
    STORAGE_FULL("E-STORAGE-FULL", R.string.error_storage_full),
    DECODE("E-DECODE", R.string.error_decode),
    ENCODE_UNSUPPORTED("E-ENCODE-UNSUPPORTED", R.string.error_encode_unsupported),
    META_PARSE("E-META-PARSE", R.string.error_meta_parse),
    META_WRITE("E-META-WRITE", R.string.error_meta_write),
    META_VERIFY("E-META-VERIFY", R.string.error_meta_verify),
    FIELD_INVALID("E-FIELD-INVALID", R.string.error_field_invalid),
    BACKUP_MISSING("E-BACKUP-MISSING", R.string.error_backup_missing),
    BACKUP_EXPIRED("E-BACKUP-EXPIRED", R.string.error_backup_expired),
    UNDO_DONE("E-UNDO-DONE", R.string.error_undo_done),
    OOM("E-OOM", R.string.error_oom),
    CANCEL("E-CANCEL", R.string.error_cancel),
    UNKNOWN("E-UNKNOWN", R.string.error_unknown),
    ;

    companion object {
        fun fromCode(code: String): PictError = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * 携带上下文的失败对象。`detail` 只进日志/报告，不直接展示给用户。
 */
data class PictFailure(
    val error: PictError,
    val detail: String? = null,
    val cause: Throwable? = null,
) {
    val code: String get() = error.code
}
