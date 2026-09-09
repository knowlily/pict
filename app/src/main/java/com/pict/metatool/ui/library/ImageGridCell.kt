package com.pict.metatool.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.pict.metatool.R
import com.pict.metatool.domain.format.ByteSizeFormatter
import com.pict.metatool.domain.model.ImageItem
import com.pict.metatool.ui.theme.PictMotion
import com.pict.metatool.ui.theme.PictSpacing

/**
 * 网格中的一张图（docs/06 §3.1）。
 *
 * 缩略图直接用 `content://` URI 交给 Coil：Coil 3 的 Android 端自带 `ContentUriFetcher`，
 * 不需要自己写 Fetcher（docs/07 T1.9 括号里的说法是 Coil 2 时代的写法）。
 * 尺寸由 composable 的约束决定，Coil 会据此下采样，不会把原图整张读进内存。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageGridCell(
    item: ImageItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // docs/06 §4：缩略图圆角 12 dp。
    val shape = RoundedCornerShape(12.dp)
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(PictSpacing.xxs),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                    } else {
                        Modifier
                    },
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                ),
        ) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(item.uri)
                    .crossfade(PictMotion.Quick)
                    .build(),
                contentDescription = if (selected) {
                    stringResource(R.string.library_thumb_desc_selected, item.displayName)
                } else {
                    item.displayName
                },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { ThumbSkeleton() },
                error = { ThumbError() },
                success = { SubcomposeAsyncImageContent() },
            )

            if (selected) {
                SelectionBadge(modifier = Modifier.align(Alignment.TopEnd).padding(PictSpacing.xs))
            }
        }

        Text(
            text = item.displayName,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = if (item.writable) {
                stringResource(R.string.library_item_meta, item.format.label, ByteSizeFormatter.format(item.sizeBytes))
            } else {
                stringResource(
                    R.string.library_item_meta_readonly,
                    item.format.label,
                    ByteSizeFormatter.format(item.sizeBytes),
                )
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 骨架屏（docs/06 §5：加载用灰色圆角块，不用转圈遮罩）。 */
@Composable
private fun ThumbSkeleton(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** 加载失败：卡片内嵌错误，不弹窗（docs/06 §5）。 */
@Composable
private fun ThumbError(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = stringResource(R.string.library_thumb_failed),
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 选中徽标：主色圆底 + 勾。 */
@Composable
private fun SelectionBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(14.dp),
        )
    }
}
