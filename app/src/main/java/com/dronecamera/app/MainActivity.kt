package com.dronecamera.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.MediaStore
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.dronecamera.app.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null
    private var activeRecording: Recording? = null
    private var sequencePlayer: ZoomSequencePlayer? = null
    private var countdownTimer: CountDownTimer? = null

    // Kullanici secimleri
    private var mode = CameraMode.DRONE
    private var durationSec = 15
    private var zoomOut = true
    private var curve = ZoomCurve.CINEMATIC
    private var countdownSec = 0
    private var lockExposure = true
    private var singleLens = false
    private var singleLensStartZoom = 3
    private var useFrontCamera = false

    private var isShotRunning = false
    private var isCountingDown = false

    // Dinamik olusturulan cip gorunumleri
    private val modeChips = linkedMapOf<CameraMode, TextView>()
    private val durationChips = linkedMapOf<Int, TextView>()
    private val curveChips = linkedMapOf<ZoomCurve, TextView>()
    private val countdownChips = linkedMapOf<Int, TextView>()
    private val startZoomChips = linkedMapOf<Int, TextView>()
    private lateinit var directionChip: TextView
    private lateinit var durationLabel: TextView
    private lateinit var curveLabel: TextView
    private lateinit var countdownLabel: TextView
    private lateinit var startZoomLabel: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.CAMERA] == true) {
            startCamera()
        } else {
            Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        buildChips()
        setupControls()
        updateUiForMode()

        if (hasPermission(Manifest.permission.CAMERA)) {
            startCamera()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    // ---------------------------------------------------------------- UI kurulum

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun makeChip(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textPrimary))
        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.chip_bg)
        setPadding(dp(14), dp(7), dp(14), dp(7))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = dp(6)
        layoutParams = lp
    }

    private fun makeLabel(textRes: Int): TextView = TextView(this).apply {
        text = getString(textRes)
        textSize = 12f
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textSecondary))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = dp(6)
        lp.marginStart = dp(8)
        layoutParams = lp
    }

    private fun <K> markSelected(group: Map<K, TextView>, selected: K) {
        group.forEach { (key, chip) ->
            val isSel = key == selected
            chip.background = ContextCompat.getDrawable(
                this, if (isSel) R.drawable.chip_bg_selected else R.drawable.chip_bg
            )
            chip.setTextColor(
                ContextCompat.getColor(this, if (isSel) R.color.textOnChip else R.color.textPrimary)
            )
        }
    }

    private fun buildChips() {
        // Mod seridi
        CameraMode.values().forEach { m ->
            val chip = makeChip(getString(m.labelRes))
            chip.setOnClickListener {
                if (isShotRunning || isCountingDown || mode == m) return@setOnClickListener
                mode = m
                updateUiForMode()
                bindCamera()
            }
            binding.modeRow.addView(chip)
            modeChips[m] = chip
        }

        // Sure
        durationLabel = makeLabel(R.string.label_duration)
        binding.optionsRow.addView(durationLabel)
        intArrayOf(10, 15, 20, 30).forEach { sec ->
            val chip = makeChip(getString(R.string.duration_fmt, sec))
            chip.setOnClickListener {
                if (isShotRunning || isCountingDown) return@setOnClickListener
                durationSec = sec
                markSelected(durationChips, sec)
            }
            binding.optionsRow.addView(chip)
            durationChips[sec] = chip
        }

        // Yon
        directionChip = makeChip(getString(R.string.dir_out))
        directionChip.setOnClickListener {
            if (isShotRunning || isCountingDown) return@setOnClickListener
            zoomOut = !zoomOut
            directionChip.text = getString(if (zoomOut) R.string.dir_out else R.string.dir_in)
        }
        binding.optionsRow.addView(directionChip)

        // Hiz egrisi
        curveLabel = makeLabel(R.string.label_curve)
        binding.optionsRow.addView(curveLabel)
        listOf(
            ZoomCurve.CINEMATIC to R.string.curve_cinematic,
            ZoomCurve.LINEAR to R.string.curve_linear,
            ZoomCurve.AGGRESSIVE to R.string.curve_aggressive
        ).forEach { (c, res) ->
            val chip = makeChip(getString(res))
            chip.setOnClickListener {
                if (isShotRunning || isCountingDown) return@setOnClickListener
                curve = c
                markSelected(curveChips, c)
            }
            binding.optionsRow.addView(chip)
            curveChips[c] = chip
        }

        // Geri sayim
        countdownLabel = makeLabel(R.string.label_countdown)
        binding.optionsRow.addView(countdownLabel)
        listOf(
            0 to R.string.countdown_off,
            3 to R.string.countdown_3,
            10 to R.string.countdown_10
        ).forEach { (sec, res) ->
            val chip = makeChip(getString(res))
            chip.setOnClickListener {
                if (isShotRunning || isCountingDown) return@setOnClickListener
                countdownSec = sec
                markSelected(countdownChips, sec)
            }
            binding.optionsRow.addView(chip)
            countdownChips[sec] = chip
        }

        // Tek lens modunda baslangic zoom secimi. Lens degisim esigi cihaza
        // gore degisir (Honor Magic 8 Pro'da ~3.5x); esigin altinda kalan bir
        // baslangic secilirse cekim boyunca hic lens gecisi olmaz.
        startZoomLabel = makeLabel(R.string.label_start_zoom)
        binding.optionsRow.addView(startZoomLabel)
        intArrayOf(2, 3, 4, 6, 8).forEach { z ->
            val chip = makeChip(getString(R.string.start_zoom_fmt, z))
            chip.setOnClickListener {
                if (isShotRunning || isCountingDown) return@setOnClickListener
                singleLensStartZoom = z
                markSelected(startZoomChips, z)
            }
            binding.optionsRow.addView(chip)
            startZoomChips[z] = chip
        }

        markSelected(modeChips, mode)
        markSelected(durationChips, durationSec)
        markSelected(curveChips, curve)
        markSelected(countdownChips, countdownSec)
        markSelected(startZoomChips, singleLensStartZoom)
    }

    private fun setupControls() {
        binding.shutterButton.setOnClickListener { onShutter() }

        binding.btnLock.setOnClickListener {
            if (isShotRunning || isCountingDown) return@setOnClickListener
            lockExposure = !lockExposure
            updateToggleTints()
        }
        binding.btnLens.setOnClickListener {
            if (isShotRunning || isCountingDown) return@setOnClickListener
            singleLens = !singleLens
            updateToggleTints()
            updateUiForMode()
            bindCamera()
        }
        binding.btnFlip.setOnClickListener {
            if (isShotRunning || isCountingDown) return@setOnClickListener
            useFrontCamera = !useFrontCamera
            bindCamera()
        }
        updateToggleTints()
    }

    private fun updateToggleTints() {
        val active = ContextCompat.getColor(this, R.color.accentIce)
        val idle = ContextCompat.getColor(this, R.color.textSecondary)
        binding.btnLock.setColorFilter(if (lockExposure) active else idle)
        binding.btnLens.setColorFilter(if (singleLens) active else idle)
        binding.btnFlip.setColorFilter(idle)
        binding.btnLock.alpha = if (lockExposure) 1f else 0.55f
        binding.btnLens.alpha = if (singleLens) 1f else 0.55f
    }

    private fun updateUiForMode() {
        markSelected(modeChips, mode)

        val zoomMode = mode.isZoomMode
        val showCurve = mode == CameraMode.DRONE ||
            mode == CameraMode.BOOMERANG || mode == CameraMode.DRONIE

        setVisible(durationLabel, zoomMode)
        durationChips.values.forEach { setVisible(it, zoomMode) }
        setVisible(directionChip, zoomMode && mode != CameraMode.VERTIGO)
        setVisible(curveLabel, showCurve)
        curveChips.values.forEach { setVisible(it, showCurve) }

        setVisible(binding.btnLens, zoomMode && !mode.usesFrontCamera)
        val showStartZoom = singleLens && zoomMode && !mode.usesFrontCamera &&
            mode != CameraMode.VERTIGO
        setVisible(startZoomLabel, showStartZoom)
        startZoomChips.values.forEach { setVisible(it, showStartZoom) }
        setVisible(binding.btnFlip, mode == CameraMode.PHOTO || mode == CameraMode.VIDEO)
        setVisible(binding.btnLock, mode.recordsVideo)

        when (mode) {
            CameraMode.VERTIGO -> showGuide(R.string.vertigo_guide)
            CameraMode.DRONIE -> showGuide(R.string.dronie_guide)
            else -> binding.guideText.visibility = View.GONE
        }
    }

    private fun showGuide(textRes: Int) {
        binding.guideText.text = getString(textRes)
        binding.guideText.visibility = View.VISIBLE
    }

    private fun setVisible(view: View, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- Kamera

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            provider = providerFuture.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = this.provider ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        try {
            provider.unbindAll()
            camera = if (mode == CameraMode.PHOTO) {
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture
                videoCapture = null
                provider.bindToLifecycle(this, chooseCameraSelector(provider), preview, capture)
            } else {
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.fromOrderedList(
                            listOf(Quality.FHD, Quality.HD, Quality.HIGHEST)
                        )
                    )
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                imageCapture = null
                videoCapture = capture
                provider.bindToLifecycle(this, chooseCameraSelector(provider), preview, capture)
            }
            observeZoom()
            applyCaptureOptions(lockAeAwb = false)
            camera?.cameraControl?.setZoomRatio(
                max(1f, camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f)
            )
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.camera_error, e.message), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Tek lens modunda cok lensli (logical) olmayan gercek fiziksel ana arka
     * kamera secilir; zoom tamamen dijital olur ve lens gecisi hic yasanmaz.
     * Cihaz boyle bir kamerayi acmiyorsa varsayilan kameraya donulur.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun chooseCameraSelector(provider: ProcessCameraProvider): CameraSelector {
        val wantsFront = mode.usesFrontCamera ||
            (useFrontCamera && (mode == CameraMode.PHOTO || mode == CameraMode.VIDEO))
        if (wantsFront) return CameraSelector.DEFAULT_FRONT_CAMERA
        if (!singleLens || !mode.isZoomMode) return CameraSelector.DEFAULT_BACK_CAMERA

        val mainPhysical = provider.availableCameraInfos.firstOrNull { info ->
            val c2 = Camera2CameraInfo.from(info)
            val facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
            val capabilities = c2.getCameraCharacteristic(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            )
            val isLogical = capabilities?.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
            ) == true
            facing == CameraCharacteristics.LENS_FACING_BACK &&
                !isLogical &&
                info.intrinsicZoomRatio in 0.95f..1.05f
        } ?: return CameraSelector.DEFAULT_BACK_CAMERA

        val targetId = Camera2CameraInfo.from(mainPhysical).cameraId
        return CameraSelector.Builder()
            .addCameraFilter { infos ->
                infos.filter { Camera2CameraInfo.from(it).cameraId == targetId }
                    .ifEmpty { infos }
            }
            .build()
    }

    private fun observeZoom() {
        camera?.cameraInfo?.zoomState?.observe(this) { state ->
            binding.zoomText.text = getString(R.string.zoom_format, state.zoomRatio)
        }
    }

    /**
     * Lens gecislerindeki ani parlaklik/renk sicramalarini azaltmak icin
     * cekim boyunca AE ve AWB kilitlenir; EIS her zaman aciktir.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyCaptureOptions(lockAeAwb: Boolean) {
        val cameraControl = camera?.cameraControl ?: return
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, lockAeAwb)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, lockAeAwb)
            .build()
        Camera2CameraControl.from(cameraControl).setCaptureRequestOptions(options)
    }

    // ---------------------------------------------------------------- Zoom sekanslari

    /** Modun hedef zoom araligini cihazin sinirlarina kirparak dondurur. */
    private fun resolveZoomRange(): Pair<Float, Float>? {
        val state = camera?.cameraInfo?.zoomState?.value ?: return null
        val (targetStart, targetEnd) = when (mode) {
            CameraMode.VERTIGO -> 2f to 1f
            CameraMode.DRONIE -> min(2.5f, state.maxZoomRatio) to state.minZoomRatio
            else -> if (singleLens) singleLensStartZoom.toFloat() to 1f else 15f to 0.5f
        }
        val start = min(targetStart, state.maxZoomRatio)
        val end = max(targetEnd, state.minZoomRatio)
        return if (zoomOut || mode == CameraMode.VERTIGO) start to end else end to start
    }

    private fun curveInterpolator() = when (curve) {
        ZoomCurve.CINEMATIC -> AccelerateDecelerateInterpolator()
        ZoomCurve.LINEAR -> LinearInterpolator()
        ZoomCurve.AGGRESSIVE -> DecelerateInterpolator(2f)
    }

    private fun buildSequence(startZoom: Float, endZoom: Float): List<ZoomSegment> {
        val total = durationSec * 1000L
        return when (mode) {
            CameraMode.DRONE, CameraMode.DRONIE -> listOf(
                ZoomSegment.Hold(startZoom, 700),
                ZoomSegment.Ramp(startZoom, endZoom, total, curveInterpolator()),
                ZoomSegment.Hold(endZoom, 700)
            )
            CameraMode.BOOMERANG -> listOf(
                ZoomSegment.Hold(startZoom, 500),
                ZoomSegment.Ramp(startZoom, endZoom, total / 2, curveInterpolator()),
                ZoomSegment.Hold(endZoom, 500),
                ZoomSegment.Ramp(endZoom, startZoom, total / 2, curveInterpolator()),
                ZoomSegment.Hold(startZoom, 500)
            )
            CameraMode.STEP -> buildStepSequence(startZoom, endZoom, total)
            CameraMode.VERTIGO -> listOf(
                ZoomSegment.Hold(startZoom, 1000),
                ZoomSegment.Ramp(startZoom, endZoom, total, LinearInterpolator()),
                ZoomSegment.Hold(endZoom, 700)
            )
            else -> emptyList()
        }
    }

    /** Geometrik araliklarla 4 kademeli, duraklamali zoom (hyper zoom). */
    private fun buildStepSequence(startZoom: Float, endZoom: Float, totalMs: Long): List<ZoomSegment> {
        val levels = 4
        val zooms = (0 until levels).map { i ->
            (startZoom * (endZoom / startZoom).toDouble().pow(i / (levels - 1.0))).toFloat()
        }
        val rampMs = 500L
        val holdMs = ((totalMs - rampMs * (levels - 1)) / levels).coerceAtLeast(300)
        val segments = mutableListOf<ZoomSegment>(ZoomSegment.Hold(zooms[0], holdMs))
        for (i in 1 until levels) {
            segments += ZoomSegment.Ramp(zooms[i - 1], zooms[i], rampMs, DecelerateInterpolator())
            segments += ZoomSegment.Hold(zooms[i], holdMs)
        }
        return segments
    }

    // ---------------------------------------------------------------- Cekim akisi

    private fun onShutter() {
        if (isCountingDown) {
            cancelCountdown()
            return
        }
        if (isShotRunning) {
            stopShot()
            return
        }
        when (mode) {
            CameraMode.PHOTO -> withCountdown { takePhoto() }
            CameraMode.VIDEO -> withCountdown { startRecording(null) }
            else -> {
                val (start, end) = resolveZoomRange() ?: return
                camera?.cameraControl?.setZoomRatio(start)
                withCountdown { startRecording(buildSequence(start, end)) }
            }
        }
    }

    private fun withCountdown(action: () -> Unit) {
        if (countdownSec == 0) {
            action()
            return
        }
        isCountingDown = true
        binding.countdownOverlay.visibility = View.VISIBLE
        countdownTimer = object : CountDownTimer(countdownSec * 1000L, 250) {
            override fun onTick(millisUntilFinished: Long) {
                binding.countdownOverlay.text = ((millisUntilFinished / 1000) + 1).toString()
            }

            override fun onFinish() {
                isCountingDown = false
                binding.countdownOverlay.visibility = View.GONE
                action()
            }
        }.start()
    }

    private fun cancelCountdown() {
        countdownTimer?.cancel()
        countdownTimer = null
        isCountingDown = false
        binding.countdownOverlay.visibility = View.GONE
    }

    private fun timestampName(prefix: String): String =
        prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

    private fun takePhoto() {
        val imageCapture = this.imageCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, timestampName("FOTO_"))
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DroneCamera")
            }
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()

        imageCapture.takePicture(
            output,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Toast.makeText(this@MainActivity, R.string.photo_saved, Toast.LENGTH_SHORT).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.photo_error, exception.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun startRecording(sequence: List<ZoomSegment>?) {
        val videoCapture = this.videoCapture ?: return

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, timestampName("DRONE_"))
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DroneCamera")
            }
        }
        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        val pending = videoCapture.output
            .prepareRecording(this, outputOptions)
            .apply { if (hasPermission(Manifest.permission.RECORD_AUDIO)) withAudioEnabled() }

        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    isShotRunning = true
                    setRecordingUi(true)
                    if (lockExposure) applyCaptureOptions(lockAeAwb = true)
                    if (sequence != null) {
                        sequencePlayer = ZoomSequencePlayer(
                            segments = sequence,
                            setZoom = { camera?.cameraControl?.setZoomRatio(it) },
                            onProgress = { fraction, _ ->
                                binding.progressBar.progress = (fraction * 100).toInt()
                            },
                            onEnd = { stopShot() }
                        ).also { it.start() }
                    }
                }
                is VideoRecordEvent.Status -> {
                    val sec = event.recordingStats.recordedDurationNanos / 1_000_000_000
                    binding.recTimer.text = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    isShotRunning = false
                    applyCaptureOptions(lockAeAwb = false)
                    setRecordingUi(false)
                    if (event.hasError()) {
                        Toast.makeText(
                            this, getString(R.string.record_error, event.error), Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(this, R.string.video_saved, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun stopShot() {
        sequencePlayer?.cancel()
        sequencePlayer = null
        activeRecording?.stop()
        activeRecording = null
    }

    private fun setRecordingUi(recording: Boolean) {
        binding.shutterButton.background = ContextCompat.getDrawable(
            this, if (recording) R.drawable.shutter_rec else R.drawable.shutter_idle
        )
        setVisible(binding.recDot, recording)
        setVisible(binding.recTimer, recording)
        binding.recTimer.text = getString(R.string.timer_zero)
        binding.progressBar.progress = 0
        setVisible(binding.progressBar, recording && mode.isZoomMode)
        binding.optionsScroll.alpha = if (recording) 0.4f else 1f
        binding.modeScroll.alpha = if (recording) 0.4f else 1f
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownTimer?.cancel()
        sequencePlayer?.cancel()
        activeRecording?.stop()
    }
}
