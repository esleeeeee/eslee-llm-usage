package com.eslee.llmusage.widget

enum class WidgetSize { XS, S, M, L, XL }
object WidgetLayoutResolver {
    fun resolve(width: Float, height: Float): WidgetSize = when {
        width < 110 || height < 72 -> WidgetSize.XS
        width < 180 || height < 110 -> WidgetSize.S
        width < 250 || height < 180 -> WidgetSize.M
        width < 320 || height < 250 -> WidgetSize.L
        else -> WidgetSize.XL
    }

    fun capacity(size: WidgetSize): Int = when (size) {
        WidgetSize.XS, WidgetSize.S -> 1
        WidgetSize.M -> 2
        WidgetSize.L -> 4
        WidgetSize.XL -> 6
    }
}
