package com.dronecamera.app

/** Zoom rampalarinda kullanilan hiz egrileri. */
enum class ZoomCurve(val labelRes: Int) {
    CINEMATIC(R.string.curve_cinematic),
    LINEAR(R.string.curve_linear)
}

/**
 * Cekimin hangi lens bolgesinde kalacagi.
 *
 * Cihaz fiziksel kamerayi uygulamalara acmadigi icin lens degisimini ancak
 * zoom araligini tek bir lensin bolgesinde tutarak engelleyebiliyoruz.
 * Esik (threshold) kullanici tarafindan kalibre edilir; Honor Magic 8 Pro'da
 * telefoto gecisi 3.7x'tedir (85mm).
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

/** Uygulamanin cekim modlari. Hepsi otomatik zoom sekansiyla video ceker. */
enum class CameraMode(val labelRes: Int) {
    DRONE(R.string.mode_drone),
    BOOMERANG(R.string.mode_boomerang),
    STEP(R.string.mode_step),
    VERTIGO(R.string.mode_vertigo),
    DRONIE(R.string.mode_dronie);

    val usesFrontCamera: Boolean
        get() = this == DRONIE

    /** Lens menzili secimi bu modda anlamli mi? */
    val allowsLensRange: Boolean
        get() = this != VERTIGO && this != DRONIE
}
