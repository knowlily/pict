package com.pict.metatool.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pict.metatool.ui.PictApp
import com.pict.metatool.ui.theme.PictTheme

/**
 * 单 Activity 架构（docs/02 §3）：所有界面由 Compose 导航承载。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PictTheme {
                PictApp()
            }
        }
    }
}
