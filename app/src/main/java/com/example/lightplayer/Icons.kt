package com.example.lightplayer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private fun vec(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
        .build()

/** Мінімальний набір власних векторних іконок (замість емодзі, які мали жовтий фон). */
object Ic {
    val Play by lazy { vec("play", "M8,5v14l11,-7z") }
    val Pause by lazy { vec("pause", "M6,19h4V5H6v14zm8,-14v14h4V5h-4z") }
    val Next by lazy { vec("next", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z") }
    val Prev by lazy { vec("prev", "M6,6h2v12H6zm3.5,6l8.5,6V6z") }
    val Shuffle by lazy {
        vec(
            "shuffle",
            "M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.42,-1.41zM14.5,4l2.04,2.04L4,18.59 5.41,20 17.96,7.46 " +
                "20,9.5V4h-5.5zM14.83,13.41l-1.41,1.41 3.13,3.13L14.5,20H20v-5.5l-2.04,2.04 -3.13,-3.13z"
        )
    }
    val Repeat by lazy {
        vec("repeat", "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4z")
    }
    val RepeatOne by lazy {
        vec(
            "repeat_one",
            "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4zM13,15V9h-1l-2,1v1h1.5v4H13z"
        )
    }
    val Folder by lazy {
        vec("folder", "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z")
    }
    val Note by lazy {
        vec("note", "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z")
    }
}
