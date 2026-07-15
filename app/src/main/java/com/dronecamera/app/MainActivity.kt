package com.dronecamera.app

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var zoomAnimator: ValueAnimator? = null

    /** Drone cekiminin toplam suresi (saniye). */
    private var droneDurationSec = 15

    /** true: uzaklasma (15x -> 0.5x), false: yaklasma (0.5x -> 15x) */
    private var zoomOutMode = true

    /**
     * Tek lens modu: yalnizca ana fiziksel kamera kullanilir ve zoom 8x-1x
     * araliginda tamamen dijital yapilir; boylece hic lens gecisi olmaz.
     */
    private var singleLensMode = false

    private var isDroneShotRunning = false

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

        setupUi()

        if (hasPermission(Manifest.permission.CAMERA)) {
            startCamera()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    private fun setupUi() {
        binding.recordButton.setOnClickListener {
            if (isDroneShotRunning) cancelDroneShot() else startDroneShot()
        }

        binding.directionToggle.setOnClickListener {
            zoomOutMode = !zoomOutMode
            updateDirectionLabel()
        }
        updateDirectionLabel()

        binding.durationGroup.setOnCheckedChangeListener { _, checkedId ->
            droneDurationSec = when (checkedId) {
                R.id.duration10 -> 10
                R.id.duration20 -> 20
                R.id.duration30 -> 30
                else -> 15
            }
        }

        binding.singleLens.setOnCheckedChangeListener { _, checked ->
            singleLensMode = checked
            updateDirectionLabel()
            startCamera() // Kamerayi secilen moda gore yeniden bagla.
        }
    }

    private fun updateDirectionLabel() {
        binding.directionToggle.text = getString(
            when {
                zoomOutMode && singleLensMode -> R.string.mode_zoom_out_single
                zoomOutMode -> R.string.mode_zoom_out
                singleLensMode -> R.string.mode_zoom_in_single
                else -> R.string.mode_zoom_in
            }
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.FHD, Quality.HD, Quality.HIGHEST)
                    )
                )
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    chooseCameraSelector(provider),
                    preview,
                    videoCapture
                )
                observeZoom()
                applyCaptureOptions(lockAeAwb = false)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.camera_error, e.message), Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun observeZoom() {
        camera?.cameraInfo?.zoomState?.observe(this) { state ->
            binding.zoomText.text = getString(R.string.zoom_format, state.zoomRatio)
        }
    }

    /**
     * Tek lens modunda, cok lensli (logical) olmayan ve 1x'e karsilik gelen
     * gercek fiziksel ana arka kamerayi secmeye calisir. Bu kamerada zoom
     * tamamen dijitaldir, dolayisiyla lens gecisi hic olmaz. Cihaz boyle bir
     * kamerayi uygulamalara acmiyorsa varsayilan arka kameraya donulur
     * (zoom araligi yine 1x-8x ile sinirlanir).
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun chooseCameraSelector(provider: ProcessCameraProvider): CameraSelector {
        if (!singleLensMode) return CameraSelector.DEFAULT_BACK_CAMERA

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

    /**
     * Lens gecislerinde (telefoto -> ana -> ultra genis) olusan ani parlaklik ve
     * renk sicramalarini azaltmak icin cekim boyunca pozlama (AE) ve beyaz
     * dengesi (AWB) kilitlenir. Video stabilizasyonu da gecisleri yumusatir.
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

    /**
     * Cihazin destekledigi araliga gore hedef zoom degerlerini hesaplar.
     * Istenen: 15x -> 0.5x. Cihaz desteklemiyorsa en yakin degerlere kirpilir.
     */
    private fun resolveZoomRange(): Pair<Float, Float>? {
        val state = camera?.cameraInfo?.zoomState?.value ?: return null
        val targetStart = if (singleLensMode) SINGLE_LENS_START_ZOOM else TARGET_START_ZOOM
        val targetEnd = if (singleLensMode) SINGLE_LENS_END_ZOOM else TARGET_END_ZOOM
        val start = min(targetStart, state.maxZoomRatio)
        val end = max(targetEnd, state.minZoomRatio)
        return if (zoomOutMode) start to end else end to start
    }

    private fun startDroneShot() {
        val videoCapture = this.videoCapture ?: return
        val cameraControl = camera?.cameraControl ?: return
        val (startZoom, endZoom) = resolveZoomRange() ?: return

        val name = "DRONE_" + SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DroneCamera")
            }
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        // Kayit baslamadan once kamerayi baslangic zoom seviyesine getir.
        cameraControl.setZoomRatio(startZoom)

        val pending = videoCapture.output
            .prepareRecording(this, outputOptions)
            .apply { if (hasPermission(Manifest.permission.RECORD_AUDIO)) withAudioEnabled() }

        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    isDroneShotRunning = true
                    updateRecordingUi(true)
                    // Kisa sabit tutus sirasinda pozlama oturur; sonra AE/AWB
                    // kilitlenir ve akici zoom gecisi baslar.
                    binding.previewView.postDelayed({
                        if (binding.lockExposure.isChecked) {
                            applyCaptureOptions(lockAeAwb = true)
                        }
                        animateZoom(startZoom, endZoom)
                    }, HOLD_MS)
                }
                is VideoRecordEvent.Finalize -> {
                    isDroneShotRunning = false
                    applyCaptureOptions(lockAeAwb = false)
                    updateRecordingUi(false)
                    if (event.hasError()) {
                        Toast.makeText(
                            this,
                            getString(R.string.record_error, event.error),
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(this, R.string.video_saved, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /**
     * Zoom gecisini logaritmik uzayda animasyonlar: her karede zoom degeri
     * start * (end/start)^t olarak hesaplanir. Boylece buyutme orani sabit
     * hizda degisir ve gecis goze "drone ucusu" gibi dengeli gorunur.
     */
    private fun animateZoom(startZoom: Float, endZoom: Float) {
        if (!isDroneShotRunning) return
        val cameraControl = camera?.cameraControl ?: return

        val logStart = ln(startZoom.toDouble())
        val logEnd = ln(endZoom.toDouble())
        val durationMs = droneDurationSec * 1000L

        zoomAnimator?.cancel()
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val zoom = exp(logStart + (logEnd - logStart) * t).toFloat()
                cameraControl.setZoomRatio(zoom)
                val remaining = (durationMs * (1f - anim.animatedFraction) / 1000f)
                binding.countdownText.text = getString(R.string.countdown_format, remaining)
                binding.progressBar.progress = (anim.animatedFraction * 100).toInt()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Bitiste kisa tutus, sonra kaydi durdur.
                    binding.previewView.postDelayed({ stopDroneShot() }, HOLD_MS)
                }
            })
            start()
        }
    }

    private fun stopDroneShot() {
        zoomAnimator?.cancel()
        zoomAnimator = null
        activeRecording?.stop()
        activeRecording = null
    }

    private fun cancelDroneShot() {
        stopDroneShot()
    }

    private fun updateRecordingUi(recording: Boolean) {
        binding.recordButton.setText(
            if (recording) R.string.stop_shot else R.string.start_shot
        )
        binding.recordButton.setBackgroundColor(
            ContextCompat.getColor(this, if (recording) R.color.recording else R.color.accent)
        )
        binding.progressBar.progress = 0
        binding.progressBar.visibility =
            if (recording) android.view.View.VISIBLE else android.view.View.GONE
        binding.countdownText.visibility =
            if (recording) android.view.View.VISIBLE else android.view.View.GONE
        binding.directionToggle.isEnabled = !recording
        binding.lockExposure.isEnabled = !recording
        binding.singleLens.isEnabled = !recording
        for (i in 0 until binding.durationGroup.childCount) {
            binding.durationGroup.getChildAt(i).isEnabled = !recording
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        zoomAnimator?.cancel()
        activeRecording?.stop()
    }

    companion object {
        private const val FILENAME_FORMAT = "yyyyMMdd_HHmmss"
        private const val TARGET_START_ZOOM = 15f
        private const val TARGET_END_ZOOM = 0.5f
        private const val SINGLE_LENS_START_ZOOM = 8f
        private const val SINGLE_LENS_END_ZOOM = 1f
        private const val HOLD_MS = 700L
    }
}
