package com.pict.metatool.ui.detail

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pict.metatool.R

/**
 * 详情页底部的「编辑元数据」入口（docs/06 §3.2）。
 *
 * 单独放一个文件，是因为它只依赖一个回调：详情页负责展示，编辑页负责改，
 * 跳转由导航层决定（和 `DetailScreen(onBack = ...)` 同一种写法）。
 */
@Composable
internal fun EditEntryBar(onClick: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(text = stringResource(R.string.edit_title))
            }
        }
    }
}
