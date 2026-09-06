package com.airangelvl.app.camera

enum class PreviewScaleMode {
    FILL,
    FIT
}

enum class PreviewAspectMode(val ratio: Double?, val scaleMode: PreviewScaleMode) {
    FULL_SCREEN(null, PreviewScaleMode.FIT),
    ASPECT_16_9(16.0 / 9.0, PreviewScaleMode.FIT),
    ASPECT_5_4(5.0 / 4.0, PreviewScaleMode.FIT),
    ASPECT_4_3(4.0 / 3.0, PreviewScaleMode.FIT)
}
