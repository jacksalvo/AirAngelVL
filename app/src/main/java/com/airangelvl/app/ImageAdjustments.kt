package com.airangelvl.app

data class ImageAdjustments(
    val brightness: Int = 0,
    val contrast: Int = 100,
    val saturation: Int = 100
) {
    fun isDefault(): Boolean = brightness == 0 && contrast == 100 && saturation == 100
}
