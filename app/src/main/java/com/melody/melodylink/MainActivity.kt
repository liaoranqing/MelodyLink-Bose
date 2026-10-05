package com.melody.melodylink

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Bose 专用精简版的模块信息页。模块的全部工作在 LSPosed 环境里由
 * [com.melody.melodylink.hook.HookModule] 完成；这个 Activity 只用来
 * 确认安装与展示版本/排查指引，因此不再携带 Compose 界面。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        fun addText(content: String, sizeSp: Float, bold: Boolean, color: Int) {
            val tv = TextView(this)
            tv.text = content
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            tv.setTextColor(color)
            if (bold) tv.setTypeface(tv.typeface, Typeface.BOLD)
            tv.setLineSpacing(0f, 1.25f)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = pad / 2
            tv.layoutParams = lp
            box.addView(tv)
        }

        val onSurface = 0xFF1B1B1B.toInt()
        val accent = 0xFF3F51B5.toInt()
        addText("MelodyLink Bose", 24f, true, onSurface)
        addText(
            "版本 " + BuildConfig.VERSION_NAME + "（versionCode " + BuildConfig.VERSION_CODE + "）· 目标 Melody 17.6.3",
            14f, false, onSurface
        )
        addText(
            "本版本只支持 Bose QuietComfort Ultra Earbuds 2。在 LSPosed 中启用本模块并勾选作用域 com.oplus.melody，然后重启 Melody 进程。",
            14f, false, onSurface
        )
        addText("排查日志：", 14f, true, accent)
        addText("adb logcat -d | grep -i melodylink\n先看 evt= 事件分布", 13f, false, onSurface)
        addText(
            "关键事件：bose.model.swap（3D 模型）、bose.image.applied（产品图）、bose.colorid.override（色彩伪装）、inject.verified（条目注入）",
            13f, false, onSurface
        )

        setContentView(box)
    }
}
