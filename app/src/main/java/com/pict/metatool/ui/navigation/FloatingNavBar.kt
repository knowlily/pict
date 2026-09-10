package com.pict.metatool.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pict.metatool.ui.theme.PictMotion
import com.pict.metatool.ui.theme.PictSpacing

/**
 * 悬浮底栏（docs/06 §2）。
 *
 * 与 Material 自带的 NavigationBar 的区别只有形状与留白：不贴屏幕底边，
 * 四周留白、大圆角、一道描边加一层投影，像一枚浮在内容上的胶囊。
 * 选中项用圆角承载色标出来，底色与图标色一起过渡；动效走 [PictMotion.quick]，
 * 底栏是高频点击的地方，过渡要短、不要弹。
 *
 * 无障碍：单项最小高度 56 dp 满足触摸目标；语义角色是 Tab，
 * selectable 自带「已选中」播报，图标与文字同用一份文案。
 */
@Composable
fun FloatingNavBar(
    items: List<PictDestination>,
    currentRoute: String?,
    onSelect: (PictDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = PictSpacing.lg, vertical = PictSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PictSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(PictSpacing.xs),
            ) {
                items.forEach { destination ->
                    FloatingNavItem(
                        destination = destination,
                        selected = currentRoute == destination.route,
                        onClick = { onSelect(destination) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.FloatingNavItem(
    destination: PictDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(destination.labelRes)
    val container by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = PictMotion.quick(),
        label = "navItemContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = PictMotion.quick(),
        label = "navItemContent",
    )

    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(container)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = PictSpacing.sm, vertical = PictSpacing.sm),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = label,
            tint = content,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(top = PictSpacing.xxs),
        )
    }
}
