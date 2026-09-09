package com.pict.metatool.core.result

import com.pict.metatool.core.error.PictError
import com.pict.metatool.core.error.PictFailure

/**
 * 领域层统一返回类型（docs/02 §4）。
 * 不使用异常传递业务失败：可预期的失败（不可写、格式不支持）是返回值的一部分。
 */
sealed interface PictResult<out T> {

    data class Success<out T>(val value: T) : PictResult<T>

    data class Failure(val failure: PictFailure) : PictResult<Nothing> {
        val code: String get() = failure.code
        val error: PictError get() = failure.error
    }

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.value

    fun failureOrNull(): PictFailure? = (this as? Failure)?.failure
}

inline fun <T, R> PictResult<T>.map(transform: (T) -> R): PictResult<R> = when (this) {
    is PictResult.Success -> PictResult.Success(transform(value))
    is PictResult.Failure -> this
}

inline fun <T> PictResult<T>.onSuccess(action: (T) -> Unit): PictResult<T> {
    if (this is PictResult.Success) action(value)
    return this
}

inline fun <T> PictResult<T>.onFailure(action: (PictFailure) -> Unit): PictResult<T> {
    if (this is PictResult.Failure) action(failure)
    return this
}

inline fun <T> PictResult<T>.getOrElse(fallback: (PictFailure) -> T): T = when (this) {
    is PictResult.Success -> value
    is PictResult.Failure -> fallback(failure)
}

fun <T> PictResult<T>.getOrThrow(): T = when (this) {
    is PictResult.Success -> value
    is PictResult.Failure -> throw PictException(failure)
}

/** 仅在「调用方明确不处理、必须冒泡」的边界使用。 */
class PictException(val failure: PictFailure) :
    RuntimeException("${failure.code}: ${failure.detail ?: failure.error.name}", failure.cause)

fun <T> successOf(value: T): PictResult<T> = PictResult.Success(value)

fun <T> failureOf(
    error: PictError,
    detail: String? = null,
    cause: Throwable? = null,
): PictResult<T> = PictResult.Failure(PictFailure(error = error, detail = detail, cause = cause))
