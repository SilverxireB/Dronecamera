package com.dronecamera.app

/** Zoom rampalarinda kullanilan hiz egrileri. */
enum class ZoomCurve { CINEMATIC, LINEAR, AGGRESSIVE }

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
}
