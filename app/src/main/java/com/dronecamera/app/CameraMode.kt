package com.dronecamera.app

/** Zoom rampalarinda kullanilan hiz egrileri. */
enum class ZoomCurve(val labelRes: Int) {
    CINEMATIC(R.string.curve_cinematic),
    LINEAR(R.string.curve_linear),
    AGGRESSIVE(R.string.curve_aggressive)
}

/**
 * Cekimin hangi lens bolgesinde kalacagi.
 *
 * Cihaz fiziksel kamerayi uygulamalara acmadigi icin lens degisimini ancak
 * zoom araligini tek bir lensin bolgesinde tutarak engelleyebiliyoruz.
 * Esik (threshold) kullanici tarafindan kalibre edilir; Honor Magic 8 Pro'da
 * telefoto gecisi ~3.5x civarindadir.
 */
enum class LensRange(val labelRes: Int) {
    /** Telefoto bolgesi: esigin ustunden cihazin azami zoom'una kadar. */
    TELE(R.string.range_tele),

    /** Ana kamera bolgesi: 1x'ten esigin hemen altina kadar. */
    MAIN(R.string.range_main),

    /** Cihazin tum araligi. En genis menzil ama lens gecisleri gorunur. */
    FULL(R.string.range_full);

    val isSingleLens: Boolean get() = this != FULL
}

/** Uygulamanin cekim modlari. */
enum class CameraMode(val labelRes: Int) {
    PHOTO(R.string.mode_photo),
    VIDEO(R.string.mode_video),
    DRONE(R.string.mode_drone),
    BOOMERANG(R.string.mode_boomerang),
    STEP(R.string.mode_step),
    VERTIGO(R.string.mode_vertigo),
    DRONIE(R.string.mode_dronie);

    /** Otomatik zoom sekansiyla video ceken modlar. */
    val isZoomMode: Boolean
        get() = this == DRONE || this == BOOMERANG || this == STEP ||
            this == VERTIGO || this == DRONIE

    val usesFrontCamera: Boolean
        get() = this == DRONIE

    val recordsVideo: Boolean
        get() = this != PHOTO

    /** Lens menzili secimi bu modda anlamli mi? */
    val allowsLensRange: Boolean
        get() = isZoomMode && this != VERTIGO && this != DRONIE
}
