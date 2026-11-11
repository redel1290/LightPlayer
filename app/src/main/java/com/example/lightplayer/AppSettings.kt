package com.example.lightplayer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ключі окремих вимикачів ефектів. */
object Fx {
    const val DYNAMIC = "dynamic"     // кольори з шпалер (Material You)
    const val COVER = "cover"         // колір інтерфейсу з обкладинки
    const val GRADIENT = "gradient"   // градієнтний фон
    const val ANIMGRAD = "animgrad"   // живий (рухомий) градієнт
    const val EQ = "eq"               // еквалайзер біля треку
    const val PULSE = "pulse"         // пульсація обкладинки
    const val GLOW = "glow"           // сяйво навколо обкладинки
    const val LISTANIM = "listanim"   // анімації списку і серця
    const val FADE = "fade"           // плавні переходи
    val all = listOf(DYNAMIC, COVER, GRADIENT, ANIMGRAD, EQ, PULSE, GLOW, LISTANIM, FADE)
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

    // --- власний градієнт ---
    var gradCustom by mutableStateOf(prefs.getBoolean("gradCustom", false))
        private set
    var grad1 by mutableIntStateOf(prefs.getInt("grad1", 0xFF8E2DE2.toInt()))
        private set
    var grad2 by mutableIntStateOf(prefs.getInt("grad2", 0xFF00C6FF.toInt()))
        private set
    var gradStrength by mutableFloatStateOf(prefs.getFloat("gradStrength", 0.55f))
        private set

    fun setCustom(v: Boolean) {
        gradCustom = v
        prefs.edit().putBoolean("gradCustom", v).apply()
    }

    fun setColors(c1: Int, c2: Int) {
        grad1 = c1
        grad2 = c2
        prefs.edit().putInt("grad1", c1).putInt("grad2", c2).apply()
    }

    fun setStrength(v: Float, persist: Boolean) {
        gradStrength = v
        if (persist) prefs.edit().putFloat("gradStrength", v).apply()
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
