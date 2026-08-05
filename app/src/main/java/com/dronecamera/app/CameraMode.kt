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

/**
 * Cekim modlari. Hepsi kadraji (zoom + kirpma merkezi) zaman icinde
 * hareket ettirerek video ceker.
 */
enum class CameraMode(val labelRes: Int) {
    /** Sabit merkez, zoom rampasi. */
    DRONE(R.string.mode_drone),

    /** Ozneden acilis: secilen noktadan baslayip genise ve ortaya acilir. */
    REVEAL(R.string.mode_reveal),

    /** Gidis-donus zoom. */
    BOOMERANG(R.string.mode_boomerang),

    /** Duraklamali kademeli zoom. */
    STEP(R.string.mode_step),

    /** Uzun cekim, hizlandirilmis video. */
    TIMELAPSE(R.string.mode_timelapse),

    /** Kullanicinin kurdugu iki kadraj arasinda gecis. */
    TWO_POINT(R.string.mode_two_point),

    /** Dolly zoom: kullanici yururken zoom ters yonde acilir. */
    VERTIGO(R.string.mode_vertigo),

    /** On kamerayla uzaklasan selfie. */
    DRONIE(R.string.mode_dronie);

    val usesFrontCamera: Boolean
        get() = this == DRONIE

    /** Lens menzili secimi bu modda anlamli mi? */
    val allowsLensRange: Boolean
        get() = this != VERTIGO && this != DRONIE

    /** Yon secimi (uzaklasma/yaklasma) bu modda anlamli mi? */
    val usesDirection: Boolean
        get() = this != TWO_POINT && this != VERTIGO && this != DRONIE

    /**
     * Kadraj merkezi secimi bu modda anlamli mi? DRONE her zaman ortadan
     * calisir; merkez secimi yalnizca acilis ve iki nokta modlarinda
     * kullanilir, boylece modlar birbirinin ayni olmaz.
     */
    val usesCenter: Boolean
        get() = this == REVEAL || this == TWO_POINT

    /** Hiz egrisi secilebilir mi (timelapse her zaman dogrusaldir)? */
    val usesCurve: Boolean
        get() = this != TIMELAPSE

    companion object {
        /**
         * Serit sirasi: gunluk kullanilan dort mod basta, denemelik olanlar
         * arkada kalir. Enum sirasi degil bu liste gosterilir.
         */
        val DISPLAY_ORDER = listOf(
            DRONE, REVEAL, TWO_POINT, TIMELAPSE, BOOMERANG, STEP, VERTIGO, DRONIE
        )
    }
}

/**
 * Kullanicinin kurdugu bir kadraj: efektif zoom + kirpma merkezi.
 *
 * `basis`, merkezin OLCULDUGU optik zoom degeridir (secme ekranindaki genis
 * kadraj). Cekimde optik taban baska bir deger olabilecegi icin merkez bu
 * referansla olceklenmek zorunda — aksi halde ikinci sahne, ilkinin
 * goruldugu genis alanin disina dusuyor ve kadraj hic varamiyordu.
 */
data class FramePoint(val zoom: Float, val cx: Float, val cy: Float, val basis: Float)

/**
 * Kadraj secme ekraninin hangi kadraji duzenledigi.
 * START = ACILIS modunun baslangic cercevesi (zoom sabit, yalnizca yer secilir),
 * SCENE_A/SCENE_B = IKI NOKTA modunun ilk ve son sahnesi (yer + zoom).
 */
enum class PickSlot(val labelRes: Int) {
    START(R.string.pick_start),
    SCENE_A(R.string.point_a),
    SCENE_B(R.string.point_b)
}
