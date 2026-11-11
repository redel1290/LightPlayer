package com.example.lightplayer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ключі окремих вимикачів ефектів. */
object Fx {
    const val DYNAMIC = "dynamic"     // кольори з шпалер (Material You)
    const val COVER = "cover"         // колір з обкладинки
    const val GRADIENT = "gradient"   // градієнтний фон
    const val EQ = "eq"               // еквалайзер біля треку
    const val PULSE = "pulse"         // пульсація обкладинки
    const val LISTANIM = "listanim"   // анімації списку і серця
    const val FADE = "fade"           // плавні переходи
    val all = listOf(DYNAMIC, COVER, GRADIENT, EQ, PULSE, LISTANIM, FADE)
}

class AppSettings(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val map = mutableStateMapOf<String, Boolean>().apply {
        for (k in Fx.all) put(k, prefs.getBoolean(k, true))
    }

    fun on(key: String): Boolean = map[key] ?: true

    fun set(key: String, value: Boolean) {
        map[key] = value
        prefs.edit().putBoolean(key, value).apply()
    }
}

/** Обрані треки (зберігаються як набір id). */
class Favs(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("favs", Context.MODE_PRIVATE)

    var ids: Set<Long> by mutableStateOf(
        (prefs.getStringSet("ids", emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()
    )
        private set

    fun toggle(id: Long) {
        ids = if (id in ids) ids - id else ids + id
        prefs.edit().putStringSet("ids", ids.map { it.toString() }.toSet()).apply()
    }
}
