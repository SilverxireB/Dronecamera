package com.dronecamera.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.net.Uri
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.core.ZoomState
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.dronecamera.app.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var sequencePlayer: ZoomSequencePlayer? = null
    private var countdownTimer: CountDownTimer? = null

    // --- Kullanici ayarlari (SharedPreferences'ta saklanir) ---
    private var mode = CameraMode.DRONE
    private var lensRange = LensRange.TELE
    private var durationSec = 15
    private var zoomOut = true
    private var curve = ZoomCurve.CINEMATIC
    private var countdownSec = 0
    private var lensThreshold = 3.7f // Honor Magic 8 Pro: telefoto 3.7x'te (85mm) devreye girer
    private var lockExposure = true
    private var stabilization = true
    /** Donanim tavaninin uzerine eklenen yazilimsal zoom carpani (1 = kapali). */
    private var softZoomMax = 1f
    private var softZoomLevel = 1f
    private var zoomProcessor: ZoomSurfaceProcessor? = null
    /** GPU kirpma hatti kurulabildi mi (kadraj merkezi ve timelapse buna bagli). */
    private var effectActive = true
    /** O an bagli kameranin on/arka olusu — gereksiz yeniden baglamayi onler. */
    private var boundFront = false
    private var lastOpticalRequested = 1f
    /**
     * Yazilim zoom acikken optik zoom cekim boyunca bu sabit degerde tutulur;
     * rampanin tamami kirpma ile yapilir. Deger onizlemede de gecerlidir, yani
     * kayit basladiginda optik zoom hic degismez — kaydin ilk saniyesindeki
     * yeniden odaklanma/bulaniklik boylece ortadan kalkar.
     */
    private var rampOpticalBasis = 0f
    private var showGrid = false
    private var recordUhd = true
    private var targetFps = 30
    private var muteAudio = false
    private var timelapseSpeed = 10
    /** Pozlama kaydiricisinin konumu (0..100); cihazin EV araligina eslenir. */
    private var exposurePercent = 50
    private var exposureLabel: TextView? = null
    /**
     * Kirpma merkezi EKRAN uzayinda saklanir (0..1, y asagi) — kullanicinin
     * gordugu duzlem budur. Kameradan gelen kare sensorun kendi yonunde
     * (genelde yatay) geldigi icin GL'e verilmeden once dondurulur.
     */
    private var centerScreenX = ZoomSegment.CENTER
    private var centerScreenY = ZoomSegment.CENTER
    /** Yukaridakinin GL doku uzayindaki karsiligi. */
    private var centerX = ZoomSegment.CENTER
    private var centerY = ZoomSegment.CENTER
    /** IKI NOKTA modunda kullanicinin kurdugu kadrajlar. */
    private var pointA: FramePoint? = null
    private var pointB: FramePoint? = null
    /** IKI NOKTA modunda parmakla kurulan canli efektif zoom. */
    private var composeZoom = 0f

    // Ayar paneli satirlari (moda gore gosterilip gizlenir)
    private var rowDuration: LinearLayout? = null
    private var rowDirection: LinearLayout? = null
    private var rowCurve: LinearLayout? = null
    private var rowTimelapse: LinearLayout? = null
    private var rowCountdown: LinearLayout? = null
    private var rowSoftZoom: LinearLayout? = null
    private var rowThreshold: LinearLayout? = null
    private var rowQuality: LinearLayout? = null
    private var rowFps: LinearLayout? = null
    private var rowExposure: LinearLayout? = null

    private var lastVideoUri: Uri? = null
    /** Timelapse ham kaydi; hizlandirma icin yeniden paketlenip silinir. */
    private var timelapseTemp: File? = null
    private var isProcessing = false
    /** Hedef secme ekrani acik mi (genis kadraj + cerceve). */
    private var isPicking = false
    private var isShotRunning = false
    private var isRehearsing = false
    private var isCountingDown = false
    private var settingsOpen = false
    private var pendingStartZoom = true
    private var observedZoom: LiveData<ZoomState>? = null

    /** Secili durumu yeniden boyayan fonksiyonlar (cip gruplari). */
    private val refreshers = mutableListOf<() -> Unit>()
    private val lensChips = linkedMapOf<LensRange, TextView>()

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

        prefs = getSharedPreferences("drone_camera", Context.MODE_PRIVATE)
        loadPrefs()
        // Onceki oturumdan yarim kalmis timelapse ham kaydi varsa yer kaplamasin.
        runCatching { File(cacheDir, TIMELAPSE_TEMP_NAME).delete() }

        buildModeCarousel()
        buildLensSegments()
        buildPointButtons()
        buildSettingsSheet()
        setupControls()
        refreshAll()

        if (hasPermission(Manifest.permission.CAMERA)) {
            startCamera()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    // ---------------------------------------------------------------- Ayar saklama

    private fun loadPrefs() {
        mode = runCatching { CameraMode.valueOf(prefs.getString("mode", mode.name)!!) }
            .getOrDefault(CameraMode.DRONE)
        lensRange = runCatching { LensRange.valueOf(prefs.getString("lensRange", lensRange.name)!!) }
            .getOrDefault(LensRange.TELE)
        curve = runCatching { ZoomCurve.valueOf(prefs.getString("curve", curve.name)!!) }
            .getOrDefault(ZoomCurve.CINEMATIC)
        durationSec = prefs.getInt("durationSec", durationSec)
        zoomOut = prefs.getBoolean("zoomOut", zoomOut)
        countdownSec = prefs.getInt("countdownSec", countdownSec)
        lensThreshold = prefs.getFloat("lensThreshold", lensThreshold)
        lockExposure = prefs.getBoolean("lockExposure", lockExposure)
        softZoomMax = prefs.getFloat("softZoomMax", softZoomMax)
        stabilization = prefs.getBoolean("stabilization", stabilization)
        showGrid = prefs.getBoolean("showGrid", showGrid)
        recordUhd = prefs.getBoolean("recordUhd", recordUhd)
        targetFps = prefs.getInt("targetFps", targetFps)
        muteAudio = prefs.getBoolean("muteAudio", muteAudio)
        timelapseSpeed = prefs.getInt("timelapseSpeed", timelapseSpeed)
        exposurePercent = prefs.getInt("exposurePercent", exposurePercent)
        pointA = loadPoint("A")
        pointB = loadPoint("B")
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("mode", mode.name)
            .putString("lensRange", lensRange.name)
            .putString("curve", curve.name)
            .putInt("durationSec", durationSec)
            .putBoolean("zoomOut", zoomOut)
            .putInt("countdownSec", countdownSec)
            .putFloat("lensThreshold", lensThreshold)
            .putBoolean("lockExposure", lockExposure)
            .putFloat("softZoomMax", softZoomMax)
            .putBoolean("stabilization", stabilization)
            .putBoolean("showGrid", showGrid)
            .putBoolean("recordUhd", recordUhd)
            .putInt("targetFps", targetFps)
            .putBoolean("muteAudio", muteAudio)
            .putInt("timelapseSpeed", timelapseSpeed)
            .putInt("exposurePercent", exposurePercent)
            .apply()
        savePoint("A", pointA)
        savePoint("B", pointB)
    }

    private fun loadPoint(key: String): FramePoint? {
        if (!prefs.getBoolean("pt${key}_set", false)) return null
        return FramePoint(
            prefs.getFloat("pt${key}_z", 1f),
            prefs.getFloat("pt${key}_x", ZoomSegment.CENTER),
            prefs.getFloat("pt${key}_y", ZoomSegment.CENTER)
        )
    }

    private fun savePoint(key: String, point: FramePoint?) {
        val editor = prefs.edit()
        if (point == null) {
            editor.putBoolean("pt${key}_set", false)
        } else {
            editor.putBoolean("pt${key}_set", true)
                .putFloat("pt${key}_z", point.zoom)
                .putFloat("pt${key}_x", point.cx)
                .putFloat("pt${key}_y", point.cy)
        }
        editor.apply()
    }

    // ---------------------------------------------------------------- Sablonlar

    /** Mevcut cekim ayarlarini bir slota kaydeder. */
    private fun savePreset(slot: Int) {
        prefs.edit()
            .putBoolean("p${slot}_set", true)
            .putString("p${slot}_mode", mode.name)
            .putString("p${slot}_lens", lensRange.name)
            .putString("p${slot}_curve", curve.name)
            .putInt("p${slot}_dur", durationSec)
            .putBoolean("p${slot}_out", zoomOut)
            .putInt("p${slot}_cd", countdownSec)
            .putFloat("p${slot}_soft", softZoomMax)
            .putBoolean("p${slot}_uhd", recordUhd)
            .putInt("p${slot}_fps", targetFps)
            .apply()
    }

    private fun hasPreset(slot: Int) = prefs.getBoolean("p${slot}_set", false)

    private fun loadPreset(slot: Int): Boolean {
        if (!hasPreset(slot)) return false
        mode = runCatching { CameraMode.valueOf(prefs.getString("p${slot}_mode", mode.name)!!) }
            .getOrDefault(mode)
        lensRange = runCatching { LensRange.valueOf(prefs.getString("p${slot}_lens", lensRange.name)!!) }
            .getOrDefault(lensRange)
        curve = runCatching { ZoomCurve.valueOf(prefs.getString("p${slot}_curve", curve.name)!!) }
            .getOrDefault(curve)
        durationSec = prefs.getInt("p${slot}_dur", durationSec)
        zoomOut = prefs.getBoolean("p${slot}_out", zoomOut)
        countdownSec = prefs.getInt("p${slot}_cd", countdownSec)
        softZoomMax = prefs.getFloat("p${slot}_soft", softZoomMax)
        recordUhd = prefs.getBoolean("p${slot}_uhd", recordUhd)
        targetFps = prefs.getInt("p${slot}_fps", targetFps)
        savePrefs()
        refreshAll()
        applyModeToUi()
        bindCamera()
        return true
    }

    // ---------------------------------------------------------------- UI yardimcilari

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun isBusy() = isShotRunning || isCountingDown || isRehearsing || isProcessing

    private fun haptic(view: View) =
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

    private fun setVisible(view: View, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun makeChip(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        setPadding(dp(14), dp(7), dp(14), dp(7))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = dp(8)
        layoutParams = lp
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.background = ContextCompat.getDrawable(
            this, if (selected) R.drawable.chip_bg_selected else R.drawable.chip_bg
        )
        chip.setTextColor(
            ContextCompat.getColor(this, if (selected) R.color.textOnChip else R.color.textPrimary)
        )
    }

    private fun refreshAll() = refreshers.forEach { it() }

    /** Baslik + yatay cip grubu ekler ve secim boyamasini kaydeder. */
    private fun <T> addChipRow(
        parent: LinearLayout,
        titleRes: Int,
        items: List<Pair<T, String>>,
        current: () -> T,
        onSelect: (T) -> Unit
    ): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(container)
        container.addView(TextView(this).apply {
            text = getString(titleRes)
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textTertiary))
            setPadding(dp(4), dp(14), 0, dp(8))
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = items.map { (key, label) ->
            val chip = makeChip(label)
            chip.setOnClickListener {
                if (isBusy()) return@setOnClickListener
                haptic(chip)
                onSelect(key)
                savePrefs()
                refreshAll()
                // Secim onizlemeye hemen yansisin (ikinci dokunus gerekmesin).
                applyStartZoom()
            }
            row.addView(chip)
            key to chip
        }
        container.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
        refreshers += { chips.forEach { (key, chip) -> styleChip(chip, key == current()) } }
        return container
    }

    /** Bolum basligi (ayar panelini gruplara ayirir). */
    private fun addSection(parent: LinearLayout, titleRes: Int) {
        parent.addView(TextView(this).apply {
            text = getString(titleRes)
            textSize = 10f
            letterSpacing = 0.2f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accentIce))
            setPadding(dp(4), dp(22), 0, 0)
        })
    }

    private fun addSwitchRow(
        container: LinearLayout,
        titleRes: Int,
        summaryRes: Int,
        current: () -> Boolean,
        onChange: (Boolean) -> Unit
    ) {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = getString(titleRes)
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textPrimary))
        })
        texts.addView(TextView(this).apply {
            text = getString(summaryRes)
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textTertiary))
        })
        val toggle = SwitchCompat(this).apply { isChecked = current() }
        toggle.setOnCheckedChangeListener { view, checked ->
            if (isBusy()) return@setOnCheckedChangeListener
            haptic(view)
            onChange(checked)
            savePrefs()
        }
        wrapper.addView(texts)
        wrapper.addView(toggle)
        container.addView(wrapper)
        refreshers += { toggle.isChecked = current() }
    }

    // ---------------------------------------------------------------- Menu kurulumu

    private fun buildModeCarousel() {
        val items = CameraMode.values().map { m ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(dp(13), dp(6), dp(13), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val label = TextView(this).apply {
                text = getString(m.labelRes)
                textSize = 12f
                letterSpacing = 0.08f
                maxLines = 1
                // Dikey LinearLayout varsayilan olarak MATCH_PARENT genislik verir;
                // yatay kaydirma icinde bu genislik sifira coker ve yazi kirpilir.
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(5), dp(5)).also { it.topMargin = dp(6) }
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.mode_dot)
            }
            item.addView(label)
            item.addView(dot)
            item.setOnClickListener {
                if (isBusy() || mode == m) return@setOnClickListener
                haptic(item)
                mode = m
                composeZoom = 0f
                savePrefs()
                applyModeToUi()
                refreshAll()
                // Kamera yalnizca on/arka degisiyorsa yeniden baglanir;
                // aksi halde sadece kadraj tazelenir (siyah ekran ve
                // baglama yarisi olmaz).
                if (m.usesFrontCamera != boundFront) bindCamera() else applyStartZoom()
            }
            binding.modeRow.addView(item)
            m to item
        }
        refreshers += {
            items.forEach { (m, item) ->
                val selected = m == mode
                val label = item.getChildAt(0) as TextView
                label.setTextColor(
                    ContextCompat.getColor(
                        this, if (selected) R.color.textPrimary else R.color.textTertiary
                    )
                )
                item.getChildAt(1).visibility = if (selected) View.VISIBLE else View.INVISIBLE
                if (selected) {
                    // Secili mod her zaman gorunur olsun diye seride ortala.
                    binding.modeScroll.post {
                        binding.modeScroll.smoothScrollTo(
                            item.left - (binding.modeScroll.width - item.width) / 2, 0
                        )
                    }
                }
            }
        }
    }

    /** Lens menzili: cekimin en belirleyici ayari, bu yuzden ana ekranda. */
    private fun buildLensSegments() {
        val segments = LensRange.values().map { range ->
            val chip = makeChip(getString(range.labelRes))
            chip.maxLines = 1
            chip.setOnClickListener {
                if (isBusy()) return@setOnClickListener
                haptic(chip)
                lensRange = range
                savePrefs()
                refreshAll()
                applyStartZoom()
            }
            binding.lensRow.addView(chip)
            range to chip
        }
        lensChips.putAll(segments)
        refreshers += {
            segments.forEach { (range, chip) -> styleChip(chip, range == lensRange) }
            updateLensLabels()
        }
    }

    private fun fmtZoom(value: Float): String =
        if (value < 1f) String.format(Locale.US, "%.1f", value)
        else String.format(Locale.US, "%.0f", value)

    /** Cip etiketlerine o menzilin gercek zoom araligini yazar (or. "TELE 4-15x"). */
    private fun updateLensLabels() {
        val state = camera?.cameraInfo?.zoomState?.value ?: return
        lensChips.forEach { (range, chip) ->
            val bounds = zoomBoundsFor(range, state.minZoomRatio, state.maxZoomRatio)
            chip.text = getString(
                R.string.lens_label_fmt,
                getString(range.labelRes),
                fmtZoom(bounds.first),
                fmtZoom(bounds.second)
            )
        }
    }

    /**
     * Bir menzilin alt/ust zoom sinirlari. Lens gecis esigi kullanicinin
     * kalibre ettigi degerdir; TELE esigin hemen ustunde baslar (telefotonun
     * kendi optik baslangici), GENIS esigin hemen altinda biter.
     */
    private fun zoomBoundsFor(range: LensRange, deviceMin: Float, deviceMax: Float): Pair<Float, Float> {
        val low = when (range) {
            LensRange.TELE -> min(lensThreshold + 0.2f, deviceMax)
            LensRange.MAIN -> 1f
            LensRange.FULL -> deviceMin
        }
        return low to opticalCeiling(range, deviceMax) * softZoomMax
    }

    /**
     * Bir menzilde optik zoom'un cikabilecegi en ust deger. Bunun uzeri
     * yazilimsal kirpma ile saglanir; boylece lens degismeden daha uzun
     * bir menzil elde edilir.
     */
    private fun opticalCeiling(range: LensRange, deviceMax: Float): Float = when (range) {
        LensRange.TELE, LensRange.FULL -> deviceMax
        LensRange.MAIN -> min(lensThreshold - 0.2f, deviceMax)
    }

    /**
     * Cekim boyunca optik zoom'un tutulacagi sabit deger. Kaydirma modunda
     * zoom sabittir; digerlerinde rampanin en dusuk ucudur.
     */
    private fun opticalBasisFor(range: Pair<Float, Float>): Float {
        return min(min(range.first, range.second), currentOpticalCeiling())
    }

    private fun currentOpticalCeiling(): Float {
        val deviceMax = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
        return if (mode.allowsLensRange) opticalCeiling(lensRange, deviceMax) else deviceMax
    }

    private fun updateZoomBadge(effective: Float) {
        binding.zoomText.text = getString(R.string.zoom_format, effective)
    }

    /** Yazilim kirpmasi mevcut mu (efekt hatti bagli mi). */
    private fun useSoftwareRamp(): Boolean = zoomProcessor != null

    /** Istenen efektif zoom'u optik + yazilimsal kirpma olarak ikiye boler. */
    private fun applyEffectiveZoom(effective: Float) {
        val optical = if (useSoftwareRamp() && rampOpticalBasis > 0f) {
            rampOpticalBasis
        } else {
            min(effective, currentOpticalCeiling())
        }
        lastOpticalRequested = optical
        camera?.cameraControl?.setZoomRatio(optical)
        softZoomLevel = (effective / optical).coerceIn(1f, MAX_SOFT_CROP)
        zoomProcessor?.zoom = softZoomLevel
        pushCenter()
        // Rozet dogrudan burada guncellenir: optik deger ayni kalip yalnizca
        // yazilim kirpmasi degistiginde zoom gozlemcisi tetiklenmiyor.
        updateZoomBadge(effective)
    }

    /**
     * IKI NOKTA modunun A/B kadraj tuslari.
     * Dokun: kayitli kadraja git · Basili tut: mevcut kadraji kaydet.
     */
    private fun buildPointButtons() {
        val entries = listOf(
            getString(R.string.point_a) to true,
            getString(R.string.point_b) to false
        )
        val chips = entries.map { (label, isA) ->
            val chip = makeChip(label)
            chip.setOnClickListener {
                if (isBusy()) return@setOnClickListener
                haptic(chip)
                val point = if (isA) pointA else pointB
                if (point == null) {
                    Toast.makeText(this, getString(R.string.point_empty, label), Toast.LENGTH_SHORT)
                        .show()
                } else {
                    centerScreenX = point.cx
                    centerScreenY = point.cy
                    pushCenter()
                    composeZoom = point.zoom
                    applyEffectiveZoom(point.zoom)
                    Toast.makeText(
                        this, getString(R.string.point_recalled, label), Toast.LENGTH_SHORT
                    ).show()
                }
            }
            chip.setOnLongClickListener {
                if (!isBusy()) {
                    haptic(chip)
                    val zoom = if (composeZoom > 0f) composeZoom else currentEffectiveZoom()
                    val point = FramePoint(zoom, centerScreenX, centerScreenY)
                    if (isA) pointA = point else pointB = point
                    savePrefs()
                    refreshAll()
                    applyModeToUi()
                    Toast.makeText(
                        this, getString(R.string.point_saved, label), Toast.LENGTH_SHORT
                    ).show()
                }
                true
            }
            binding.pointRow.addView(chip)
            Triple(label, isA, chip)
        }
        refreshers += {
            chips.forEach { (label, isA, chip) ->
                val point = if (isA) pointA else pointB
                chip.text = if (point == null) {
                    getString(R.string.point_slot_empty, label)
                } else {
                    getString(R.string.point_slot_set, label, fmtZoom(point.zoom))
                }
                styleChip(chip, point != null)
            }
        }
    }

    /** Onizlemede o an gecerli olan efektif zoom. */
    private fun currentEffectiveZoom(): Float =
        (camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f) * softZoomLevel

    private fun buildSettingsSheet() {
        val content = binding.settingsContent

        addSection(content, R.string.section_motion)
        rowDuration = addChipRow(
            content, R.string.label_duration,
            listOf(8, 10, 15, 20, 30).map { it to getString(R.string.duration_fmt, it) },
            { durationSec }, { durationSec = it }
        )
        rowDirection = addDirectionRow(content)
        rowCurve = addChipRow(
            content, R.string.label_curve,
            ZoomCurve.values().map { it to getString(it.labelRes) },
            { curve }, { curve = it }
        )
        rowTimelapse = addChipRow(
            content, R.string.label_timelapse,
            listOf(5, 10, 20).map { it to getString(R.string.timelapse_fmt, it) },
            { timelapseSpeed }, { timelapseSpeed = it }
        )
        rowCountdown = addChipRow(
            content, R.string.label_countdown,
            listOf(
                0 to getString(R.string.countdown_off),
                3 to getString(R.string.countdown_3),
                10 to getString(R.string.countdown_10)
            ),
            { countdownSec }, { countdownSec = it }
        )

        addSection(content, R.string.section_frame)
        rowSoftZoom = addChipRow(
            content, R.string.label_soft_zoom,
            listOf(
                1f to getString(R.string.soft_off),
                1.5f to getString(R.string.soft_fmt, "1.5"),
                2f to getString(R.string.soft_fmt, "2"),
                3f to getString(R.string.soft_fmt, "3")
            ),
            { softZoomMax }, { softZoomMax = it; bindCamera() }
        )
        rowThreshold = addThresholdRow(content)

        addSection(content, R.string.section_image)
        rowQuality = addChipRow(
            content, R.string.label_quality,
            listOf(
                true to getString(R.string.quality_uhd),
                false to getString(R.string.quality_fhd)
            ),
            { recordUhd }, { recordUhd = it; bindCamera() }
        )
        rowFps = addChipRow(
            content, R.string.label_fps,
            listOf(
                30 to getString(R.string.fps_30),
                60 to getString(R.string.fps_60)
            ),
            { targetFps }, { targetFps = it; applyCaptureOptions(locked = false) }
        )
        rowExposure = addExposureRow(content)

        addSection(content, R.string.section_system)
        addSwitchRow(
            content, R.string.opt_lock_title, R.string.opt_lock_summary,
            { lockExposure }, { lockExposure = it }
        )
        addSwitchRow(
            content, R.string.opt_stab_title, R.string.opt_stab_summary,
            { stabilization }, { stabilization = it; applyCaptureOptions(locked = false) }
        )
        addSwitchRow(
            content, R.string.opt_grid_title, R.string.opt_grid_summary,
            { showGrid }, { showGrid = it; binding.gridGroup.visibility = gridVisibility() }
        )
        addSwitchRow(
            content, R.string.opt_mute_title, R.string.opt_mute_summary,
            { muteAudio }, { muteAudio = it }
        )
        addPresetRow(content)
    }

    /**
     * Yon satiri: etiketler moda gore degisir (uzaklasma/yaklasma ya da
     * saga/sola), bu yuzden elle kurulur.
     */
    private fun addDirectionRow(parent: LinearLayout): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(container)
        container.addView(TextView(this).apply {
            text = getString(R.string.label_direction)
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textTertiary))
            setPadding(dp(4), dp(14), 0, dp(8))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = listOf(true, false).map { forward ->
            val chip = makeChip("")
            chip.setOnClickListener {
                if (isBusy()) return@setOnClickListener
                haptic(chip)
                zoomOut = forward
                savePrefs()
                refreshAll()
                applyStartZoom()
            }
            row.addView(chip)
            forward to chip
        }
        container.addView(row)
        refreshers += {
            chips.forEach { (forward, chip) ->
                chip.text = getString(if (forward) R.string.dir_out else R.string.dir_in)
                styleChip(chip, forward == zoomOut)
            }
        }
        return container
    }

    /** Cekim ayarlarinin tamamini saklayan uc sablon slotu. */
    private fun addPresetRow(container: LinearLayout) {
        container.addView(TextView(this).apply {
            text = getString(R.string.label_preset)
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textTertiary))
            setPadding(dp(4), dp(18), 0, dp(8))
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = (1..3).map { slot ->
            val chip = makeChip(getString(R.string.preset_fmt, slot))
            chip.setOnClickListener {
                if (isBusy()) return@setOnClickListener
                haptic(chip)
                val message = if (loadPreset(slot)) R.string.preset_loaded else R.string.preset_empty
                Toast.makeText(this, getString(message, slot), Toast.LENGTH_SHORT).show()
            }
            chip.setOnLongClickListener {
                if (!isBusy()) {
                    haptic(chip)
                    savePreset(slot)
                    refreshAll()
                    Toast.makeText(
                        this, getString(R.string.preset_saved, slot), Toast.LENGTH_SHORT
                    ).show()
                }
                true
            }
            row.addView(chip)
            slot to chip
        }
        container.addView(row)
        container.addView(TextView(this).apply {
            text = getString(R.string.preset_hint)
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textTertiary))
            setPadding(dp(4), dp(6), 0, 0)
        })
        refreshers += { chips.forEach { (slot, chip) -> styleChip(chip, hasPreset(slot)) } }
    }

    /** Pozlama telafisi kaydiricisi (cihazin EV araligina eslenir). */
    private fun addExposureRow(parent: LinearLayout): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(container)
        container.addView(TextView(this).apply {
            text = getString(R.string.label_exposure)
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textTertiary))
            setPadding(dp(4), dp(18), 0, dp(2))
        })
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accentIce))
            setPadding(dp(4), 0, 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        headerRow.addView(label)
        exposureLabel = label

        // Pozlamayi otomatige (0 EV) dondurmek icin kestirme.
        val reset = makeChip(getString(R.string.exposure_reset))
        reset.setOnClickListener {
            haptic(reset)
            exposurePercent = neutralExposurePercent()
            applyExposure()
            savePrefs()
            refreshAll()
        }
        headerRow.addView(reset)
        container.addView(headerRow)

        val bar = SeekBar(this).apply {
            max = 100
            progress = exposurePercent
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                exposurePercent = progress
                applyExposure()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = savePrefs()
        })
        container.addView(bar)
        refreshers += { bar.progress = exposurePercent }
        return container
    }

    /**
     * Lens esigi kalibrasyonu: kaydirici oynatildikca onizleme o zoom degerine
     * gider, boylece kullanici goruntunun sicradigi noktayi kendi gozuyle
     * bulup esigi tam oraya ayarlar.
     */
    private fun addThresholdRow(parent: LinearLayout): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(container)
        container.addView(TextView(this).apply {
            text = getString(R.string.label_threshold)
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textTertiary))
            setPadding(dp(4), dp(18), 0, dp(2))
        })
        val value = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accentIce))
            setPadding(dp(4), 0, 0, dp(2))
        }
        container.addView(value)
        container.addView(TextView(this).apply {
            text = getString(R.string.threshold_help)
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textTertiary))
            setPadding(dp(4), 0, 0, dp(6))
        })

        val bar = SeekBar(this).apply {
            max = 60 // 2.0x .. 8.0x, 0.1'lik adimlar
            progress = ((lensThreshold - 2f) * 10f).toInt().coerceIn(0, 60)
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                lensThreshold = 2f + progress / 10f
                value.text = getString(R.string.zoom_format, lensThreshold)
                // Canli kalibrasyon: onizlemeyi tam esik degerine goturur.
                // Kalibrasyon saf optik zoom ile yapilir; yazilim kirpmasi devre disi.
                if (fromUser && !isBusy()) {
                    softZoomLevel = 1f
                    zoomProcessor?.zoom = 1f
                    camera?.cameraControl?.setZoomRatio(lensThreshold)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = savePrefs()
        })
        container.addView(bar)
        value.text = getString(R.string.zoom_format, lensThreshold)
        refreshers += { value.text = getString(R.string.zoom_format, lensThreshold) }
        return container
    }

    private fun setupControls() {
        binding.shutterButton.setOnClickListener {
            haptic(it)
            onShutter()
        }
        binding.btnSettings.setOnClickListener {
            haptic(it)
            toggleSettings(!settingsOpen)
        }
        binding.btnCloseSettings.setOnClickListener {
            haptic(it)
            toggleSettings(false)
        }
        binding.settingsScrim.setOnClickListener { toggleSettings(false) }
        binding.progressRing.ringWidth = dp(4).toFloat()
        binding.progressRing.ringColor = ContextCompat.getColor(this, R.color.accentIce)
        binding.btnTarget.setOnClickListener {
            haptic(it)
            togglePicker(!isPicking)
        }
        binding.btnRehearse.setOnClickListener {
            haptic(it)
            runRehearsal()
        }
        binding.btnGallery.setOnClickListener {
            haptic(it)
            openGallery()
        }
        binding.btnGallery.setOnLongClickListener {
            haptic(it)
            if (!playLastVideo()) {
                Toast.makeText(this, R.string.no_video_app, Toast.LENGTH_SHORT).show()
            }
            true
        }
        // Cekim sirasinda onizlemeye dokunmak (odaklama/parmakla zoom) rampayi
        // bozar; kayit ve prova boyunca dokunuslar yutulur. Bos zamanda tek
        // dokunus kadraj merkezini, cift dokunus merkezi sifirlar.
        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // Jest akisinin bize gelmesi icin DOWN'i sahipleniyoruz; aksi
            // halde bazi durumlarda tek dokunus hic tetiklenmiyor.
            override fun onDown(e: MotionEvent): Boolean = true

            // Confirmed: cift dokunusun ilk vurusunda merkez bosuna kaymasin.
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (mode.usesCenter) setFrameCenter(e.x, e.y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (mode.usesCenter) resetFrameCenter()
                return true
            }
        })
        // IKI NOKTA modunda parmakla efektif zoom kurulur; diger modlarda
        // zoom rampanin kendisi tarafindan belirlendigi icin kapalidir.
        val pinchDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (mode != CameraMode.TWO_POINT || isBusy()) return false
                    val state = camera?.cameraInfo?.zoomState?.value ?: return false
                    val bounds = zoomBoundsFor(
                        lensRange, state.minZoomRatio, state.maxZoomRatio
                    )
                    val base = if (composeZoom > 0f) composeZoom else currentEffectiveZoom()
                    composeZoom = (base * detector.scaleFactor)
                        .coerceIn(bounds.first, bounds.second)
                    applyEffectiveZoom(composeZoom)
                    return true
                }
            }
        )
        binding.previewView.setOnTouchListener { _, event ->
            if (!isBusy()) {
                if (mode == CameraMode.TWO_POINT) pinchDetector.onTouchEvent(event)
                tapDetector.onTouchEvent(event)
            }
            // Dokunuslari her zaman tuketiyoruz: onizlemenin kendi odak/zoom
            // jestleri bizim kadraj yonetimimizle catisiyor.
            true
        }

        refreshers += { updateSummary() }
        sizeSettingsSheet()
        applyOrientationLayout()
        applyModeToUi()
    }

    private fun toggleSettings(open: Boolean) {
        if (isBusy() && open) return
        settingsOpen = open
        setVisible(binding.settingsSheet, open)
        setVisible(binding.settingsScrim, open)
        if (open) refreshAll() else applyStartZoom()
    }

    private fun gridVisibility() = if (showGrid) View.VISIBLE else View.GONE

    private fun currentRotation(): Int = binding.root.display?.rotation ?: Surface.ROTATION_0

    /** Kameranin karesinin ekranda dik gorunmesi icin gereken donus. */
    private fun sensorDegrees(): Int =
        camera?.cameraInfo?.getSensorRotationDegrees(currentRotation()) ?: 0

    /**
     * Ekran koordinatini (0..1, y asagi) GL doku uzayina (0..1, y yukari)
     * cevirir. Kare sensor yoninde geldigi icin bu donusum olmadan dokunulan
     * nokta yanlis eksene dusuyordu.
     */
    private fun screenToBuffer(screenX: Float, screenY: Float): Pair<Float, Float> {
        val sx = screenX.coerceIn(0f, 1f)
        val sy = screenY.coerceIn(0f, 1f)
        return when (sensorDegrees()) {
            90 -> sy to sx
            180 -> (1f - sx) to sy
            270 -> (1f - sy) to (1f - sx)
            else -> sx to (1f - sy)
        }
    }

    /**
     * Hedef secme ekrani: kirpma kaldirilip menzilin en genis kadraji
     * gosterilir ve uzerine cekimin baslangic cercevesi cizilir. 20x'te
     * ekranda gorunmeyen bir noktayi secmek aksi halde mumkun degildi.
     */
    private fun togglePicker(open: Boolean) {
        if (open && (isBusy() || !mode.usesCenter)) return
        isPicking = open
        binding.btnTarget.text = getString(
            if (open) R.string.target_done else R.string.target_pick
        )
        if (open) {
            showGuide(R.string.target_guide)
            // Genis kadraj: optik taban + kirpma yok.
            rampOpticalBasis.takeIf { it > 0f }?.let {
                camera?.cameraControl?.setZoomRatio(it)
                lastOpticalRequested = it
            }
            softZoomLevel = 1f
            zoomProcessor?.zoom = 1f
            zoomProcessor?.setCenter(ZoomSegment.CENTER, ZoomSegment.CENTER)
            updateTargetRect()
        } else {
            setVisible(binding.targetRect, false)
            applyStartZoom()
        }
    }

    /** Baslangic kadrajinin genis goruntu uzerindeki yerini cizer. */
    private fun updateTargetRect() {
        if (!isPicking) {
            setVisible(binding.targetRect, false)
            return
        }
        val range = resolveZoomRange() ?: return
        val basis = rampOpticalBasis.takeIf { it > 0f } ?: return
        val crop = (range.first / basis).coerceIn(1f, MAX_SOFT_CROP)
        val width = binding.previewView.width
        val height = binding.previewView.height
        if (width == 0 || height == 0) return

        val rectWidth = (width / crop).toInt()
        val rectHeight = (height / crop).toInt()
        binding.targetRect.layoutParams = binding.targetRect.layoutParams.apply {
            this.width = rectWidth
            this.height = rectHeight
        }
        binding.targetRect.translationX = centerScreenX * width - rectWidth / 2f
        binding.targetRect.translationY = centerScreenY * height - rectHeight / 2f
        binding.targetRect.requestLayout()
        setVisible(binding.targetRect, true)
    }

    /** Ekran uzayindaki merkezi doku uzayina cevirip islemciye gonderir. */
    private fun pushCenter() {
        val (bufferX, bufferY) = screenToBuffer(centerScreenX, centerScreenY)
        centerX = bufferX
        centerY = bufferY
        zoomProcessor?.setCenter(centerX, centerY)
    }

    /**
     * Kirpma penceresinin merkezini dokunulan noktaya tasir ve ayni noktaya
     * odaklanir. Boylece ozne kadrajin ortasinda olmak zorunda kalmaz; rampa
     * boyunca secilen nokta merkezde tutulur.
     */
    private fun setFrameCenter(x: Float, y: Float) {
        val width = binding.previewView.width.toFloat()
        val height = binding.previewView.height.toFloat()
        if (width <= 0f || height <= 0f) return

        centerScreenX = (x / width).coerceIn(0f, 1f)
        centerScreenY = (y / height).coerceIn(0f, 1f)

        // Hedef secme ekraninda kirpma uygulanmaz; yalnizca cerceve tasinir.
        if (isPicking) {
            updateTargetRect()
            showFocusMarker(x, y)
            return
        }
        pushCenter()

        // Kirpma penceresi tum kareyi kapliyorsa merkezi kaydiracak pay yoktur.
        if (zoomProcessor == null || softZoomLevel < 1.05f) {
            Toast.makeText(this, R.string.center_needs_zoom, Toast.LENGTH_SHORT).show()
        }

        runCatching {
            val point = binding.previewView.meteringPointFactory.createPoint(x, y)
            camera?.cameraControl?.startFocusAndMetering(
                FocusMeteringAction.Builder(point).build()
            )
        }
        showFocusMarker(x, y)
    }

    private fun resetFrameCenter() {
        centerScreenX = ZoomSegment.CENTER
        centerScreenY = ZoomSegment.CENTER
        pushCenter()
        showFocusMarker(binding.previewView.width / 2f, binding.previewView.height / 2f)
    }

    private fun showFocusMarker(x: Float, y: Float) {
        val marker = binding.focusMarker
        val half = dp(35).toFloat()
        marker.translationX = x - half
        marker.translationY = y - half
        marker.alpha = 1f
        marker.visibility = View.VISIBLE
        marker.animate().alpha(0f).setStartDelay(700).setDuration(400)
            .withEndAction { marker.visibility = View.GONE }
            .start()
    }

    /** 0 EV'ye (otomatik pozlama) karsilik gelen kaydirici konumu. */
    private fun neutralExposurePercent(): Int {
        val state = camera?.cameraInfo?.exposureState ?: return 50
        val range = state.exposureCompensationRange
        val span = (range.upper - range.lower).toFloat()
        if (span <= 0f) return 50
        return (-range.lower / span * 100f).toInt().coerceIn(0, 100)
    }

    /** Pozlama telafisini cihazin destekledigi araliga esleyerek uygular. */
    private fun applyExposure() {
        val info = camera?.cameraInfo ?: return
        val state = info.exposureState
        if (!state.isExposureCompensationSupported) {
            exposureLabel?.text = getString(R.string.exposure_unsupported)
            return
        }
        val range = state.exposureCompensationRange
        val index = (range.lower + (range.upper - range.lower) * (exposurePercent / 100f)).toInt()
        camera?.cameraControl?.setExposureCompensationIndex(index)
        exposureLabel?.text = getString(
            R.string.exposure_fmt, index * state.exposureCompensationStep.toFloat()
        )
    }

    /** Yatayda dikey alan kisitli oldugu icin alt panel sikistirilir. */
    private fun applyOrientationLayout() {
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.bottomPanel.setPadding(
            0, dp(if (landscape) 4 else 10), 0, dp(if (landscape) 8 else 22)
        )
    }

    /** Ayar paneli yatayda ekrani doldurmasin diye yuksekligi ekrana uydurulur. */
    private fun sizeSettingsSheet() {
        val maxHeight = (resources.displayMetrics.heightPixels * 0.45f).toInt()
        binding.settingsScroll.layoutParams = binding.settingsScroll.layoutParams.apply {
            height = min(dp(360), maxHeight)
        }
        binding.settingsScroll.requestLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sizeSettingsSheet()
        applyOrientationLayout()
        // Yalnizca hedef yonu guncellemek yetmiyor: GPU efekti devredeyken
        // goruntu hatti baglanma anindaki yone gore kuruluyor ve sahne donuk
        // kaliyor. Bu yuzden kamera yeni yonle bastan baglanir.
        if (!isBusy()) bindCamera() else videoCapture?.targetRotation = currentRotation()
    }

    override fun onResume() {
        super.onResume()
        // Uygulama one dondugunde kamera yeniden baglanabiliyor; optik zoom,
        // kirpma ve rozet birlikte yeniden kurulsun.
        if (!isBusy()) {
            pendingStartZoom = true
            binding.previewView.postDelayed({ if (!isBusy()) applyStartZoom() }, 400)
        }
    }

    /** Galeri uygulamasini acar; acilamazsa son videoyu oynatmaya duser. */
    private fun openGallery() {
        val gallery = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)
        if (runCatching { startActivity(gallery) }.isSuccess) return
        if (!playLastVideo()) {
            Toast.makeText(this, R.string.no_video_app, Toast.LENGTH_SHORT).show()
        }
    }

    /** Son cekilen videoyu dogrudan oynatir (kucuk resme basili tutunca). */
    private fun playLastVideo(): Boolean {
        val uri = lastVideoUri ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching { startActivity(intent) }.isSuccess
    }

    private fun updateGalleryThumb() {
        val uri = lastVideoUri
        if (uri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setVisible(binding.btnGallery, false)
            return
        }
        Thread {
            val size = Size(dp(96), dp(96))
            val thumb = runCatching { contentResolver.loadThumbnail(uri, size, null) }.getOrNull()
            runOnUiThread {
                if (thumb != null && !isFinishing) {
                    binding.btnGallery.setImageBitmap(thumb)
                    binding.btnGallery.clipToOutline = true
                    setVisible(binding.btnGallery, true)
                }
            }
        }.start()
    }

    /**
     * Prova: kayit yapmadan rampanin tamamini hizlica oynatir. Cekimin nereden
     * nereye acilacagini onceden gormeyi saglar, sonra baslangic kadrajina doner.
     */
    private fun runRehearsal() {
        if (isBusy()) return
        val range = resolveZoomRange() ?: return
        rampOpticalBasis = opticalBasisFor(range)
        applyEffectiveZoom(range.first)

        // Prova, modun kendi kadraj yolunu sikistirilmis surede oynatir.
        val full = buildSequence(range.first, range.second)
        val totalMs = full.sumOf { it.durationMs }.coerceAtLeast(1L)
        val scale = REHEARSAL_MS.toFloat() / totalMs
        val rehearsal = full.map { segment ->
            when (segment) {
                is ZoomSegment.Hold -> segment.copy(
                    durationMs = (segment.durationMs * scale).toLong().coerceAtLeast(1L)
                )
                is ZoomSegment.Ramp -> segment.copy(
                    durationMs = (segment.durationMs * scale).toLong().coerceAtLeast(1L)
                )
            }
        }
        if (rehearsal.isEmpty()) return

        isRehearsing = true
        binding.btnRehearse.text = getString(R.string.rehearse_running)

        val startedAt = SystemClock.elapsedRealtime()
        val basis = rampOpticalBasis
        val softwareRamp = zoomProcessor != null && basis > 0f
        zoomProcessor?.frameProvider = { out ->
            rehearsal.frameAt(SystemClock.elapsedRealtime() - startedAt, out)
            out[0] = (out[0] / basis).coerceIn(1f, MAX_SOFT_CROP)
        }

        sequencePlayer = ZoomSequencePlayer(
            segments = rehearsal,
            setZoom = { effective ->
                updateZoomBadge(effective)
                if (!softwareRamp) applyEffectiveZoom(effective)
            },
            onProgress = { fraction, _ ->
                binding.progressRing.progress = fraction
            },
            onEnd = {
                isRehearsing = false
                zoomProcessor?.frameProvider = null
                sequencePlayer = null
                binding.progressRing.progress = 0f
                binding.btnRehearse.text = getString(R.string.rehearse)
                applyStartZoom()
            }
        ).also { it.start() }
    }

    /** Moda gore hangi kontrollerin gorunecegini ayarlar. */
    private fun applyModeToUi() {
        setVisible(binding.lensScroll, mode.allowsLensRange)
        setVisible(binding.pointRow, mode == CameraMode.TWO_POINT)
        setVisible(binding.btnTarget, mode.usesCenter)
        if (!mode.usesCenter && isPicking) togglePicker(false)
        binding.gridGroup.visibility = gridVisibility()

        // Ayar satirlari yalnizca ilgili modlarda gorunur.
        rowDuration?.let { setVisible(it, true) }
        rowDirection?.let { setVisible(it, mode.usesDirection) }
        rowCurve?.let { setVisible(it, mode.usesCurve) }
        rowTimelapse?.let { setVisible(it, mode == CameraMode.TIMELAPSE) }
        rowCountdown?.let { setVisible(it, true) }
        rowSoftZoom?.let { setVisible(it, true) }
        rowThreshold?.let { setVisible(it, mode.allowsLensRange) }
        rowQuality?.let { setVisible(it, true) }
        rowFps?.let { setVisible(it, mode != CameraMode.TIMELAPSE) }
        rowExposure?.let { setVisible(it, true) }

        updateSummary()

        when (mode) {
            CameraMode.VERTIGO -> showGuide(R.string.vertigo_guide)
            CameraMode.DRONIE -> showGuide(R.string.dronie_guide)
            CameraMode.REVEAL -> showGuide(R.string.reveal_guide)
            CameraMode.TWO_POINT -> showGuide(R.string.two_point_guide)
            CameraMode.TIMELAPSE -> {
                val totalSec = durationSec * timelapseSpeed
                val shootTime = if (totalSec >= 60) {
                    getString(R.string.minutes_fmt, totalSec / 60f)
                } else {
                    getString(R.string.seconds_fmt, totalSec)
                }
                binding.guideText.text =
                    getString(R.string.timelapse_guide, shootTime, durationSec)
                binding.guideText.visibility = View.VISIBLE
            }
            else ->
                if (lensRange == LensRange.FULL) showGuide(R.string.full_range_warning)
                else binding.guideText.visibility = View.GONE
        }
    }

    /**
     * Deklansorun ustundeki tek satirlik cekim ozeti: basmadan once ne
     * olacagini okuyarak gorursun.
     */
    private fun updateSummary() {
        val sep = getString(R.string.summary_sep)
        val parts = mutableListOf<String>()

        val state = camera?.cameraInfo?.zoomState?.value
        if (mode == CameraMode.TWO_POINT) {
            val a = pointA
            val b = pointB
            parts += if (a != null && b != null) {
                "${fmtZoom(a.zoom)}x → ${fmtZoom(b.zoom)}x"
            } else {
                getString(R.string.point_missing)
            }
        } else if (state != null) {
            val range = resolveZoomRange()
            if (range != null) parts += "${fmtZoom(range.first)}–${fmtZoom(range.second)}x"
        }

        parts += if (mode == CameraMode.TIMELAPSE) {
            getString(R.string.duration_fmt, durationSec * timelapseSpeed)
        } else {
            getString(R.string.duration_fmt, durationSec)
        }
        if (mode.usesDirection) {
            parts += getString(if (zoomOut) R.string.dir_out else R.string.dir_in)
        }
        if (mode.usesCurve) parts += getString(curve.labelRes)

        binding.summaryText.text = parts.joinToString(sep)
    }

    private fun showGuide(textRes: Int) {
        binding.guideText.text = getString(textRes)
        binding.guideText.visibility = View.VISIBLE
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

    /**
     * Kullanim durumlarini baglar. Sabitleme destegi cihaza gore degistigi icin
     * once onizleme sabitlemesi (en akici), sonra video sabitlemesi, en son
     * sabitlemesiz deneme yapilir.
     */
    /**
     * Kullanim durumlarini baglar.
     *
     * GPU kirpma hatti (efekt) yalnizca ekstra zoom icin degil; kadraj merkezi
     * ve timelapse de ondan geciyor. Bu yuzden hat HER ZAMAN kurulmaya
     * calisilir; yalnizca cihaz kabul etmezse efektsiz devam edilir.
     * Basarisizlikta once cozunurluk dusurulur, efektten en son vazgecilir.
     */
    private fun bindCamera() {
        val provider = this.provider ?: return
        pendingStartZoom = true
        zoomProcessor?.release()
        zoomProcessor = null
        softZoomLevel = 1f

        val attempts = mutableListOf(true to recordUhd)
        if (recordUhd) attempts += true to false
        attempts += false to recordUhd
        if (recordUhd) attempts += false to false

        for ((withEffect, uhd) in attempts) {
            if (tryBind(provider, withEffect, uhd)) {
                if (!withEffect && effectActive) {
                    Toast.makeText(this, R.string.soft_zoom_unsupported, Toast.LENGTH_LONG).show()
                }
                effectActive = withEffect
                refreshAll()
                return
            }
            zoomProcessor?.release()
            zoomProcessor = null
        }
        Toast.makeText(this, getString(R.string.camera_error, ""), Toast.LENGTH_LONG).show()
    }

    private fun tryBind(
        provider: ProcessCameraProvider,
        withEffect: Boolean,
        uhd: Boolean
    ): Boolean {
        val selector = chooseCameraSelector(provider)
        return try {
            provider.unbindAll()
            val preview = Preview.Builder()
                .setTargetRotation(currentRotation())
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            // 4K, yazilim kirpmasi icin pay birakir: 1080p'ye kirparken 2 kata
            // kadar detay kaybi olmaz.
            val qualities = if (uhd) {
                listOf(Quality.UHD, Quality.FHD, Quality.HD)
            } else {
                listOf(Quality.FHD, Quality.HD)
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.fromOrderedList(qualities))
                .build()
            val capture = VideoCapture.withOutput(recorder)
            capture.targetRotation = currentRotation()
            videoCapture = capture

            val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture)

            if (withEffect) {
                val processor = ZoomSurfaceProcessor()
                zoomProcessor = processor
                group.addEffect(
                    SoftZoomEffect(processor) { error ->
                        // Efekt hatti calisirken hata verirse sessizce siyah
                        // ekranda kalmak yerine efektsiz devam et.
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                getString(R.string.soft_zoom_failed, error.message),
                                Toast.LENGTH_LONG
                            ).show()
                            effectActive = false
                            zoomProcessor?.release()
                            zoomProcessor = null
                            refreshAll()
                            tryBind(provider, withEffect = false, uhd = uhd)
                        }
                    }
                )
            }

            camera = provider.bindToLifecycle(this, selector, group.build())
            boundFront = mode.usesFrontCamera
            observeZoom()
            applyCaptureOptions(locked = false)
            applyExposure()
            applyStartZoom()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Tek lens modunda cok lensli (logical) olmayan gercek fiziksel arka kamera
     * aranir. Cihaz bunu uygulamalara acmiyorsa (Honor boyle) varsayilan
     * kameraya donulur; lens gecisi bu durumda zoom araligini tek bir lensin
     * bolgesinde tutarak engellenir (bkz. resolveZoomRange).
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun chooseCameraSelector(provider: ProcessCameraProvider): CameraSelector {
        if (mode.usesFrontCamera) return CameraSelector.DEFAULT_FRONT_CAMERA
        if (!lensRange.isSingleLens || !mode.allowsLensRange) {
            return CameraSelector.DEFAULT_BACK_CAMERA
        }

        val physical = provider.availableCameraInfos.firstOrNull { info ->
            val c2 = Camera2CameraInfo.from(info)
            val facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
            val capabilities = c2.getCameraCharacteristic(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            )
            val isLogical = capabilities?.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
            ) == true
            facing == CameraCharacteristics.LENS_FACING_BACK && !isLogical &&
                info.intrinsicZoomRatio in 0.95f..1.05f
        } ?: return CameraSelector.DEFAULT_BACK_CAMERA

        val targetId = Camera2CameraInfo.from(physical).cameraId
        return CameraSelector.Builder()
            .addCameraFilter { infos ->
                infos.filter { Camera2CameraInfo.from(it).cameraId == targetId }.ifEmpty { infos }
            }
            .build()
    }

    private val zoomObserver = Observer<ZoomState> { _ ->
        // Rozet yalnizca applyEffectiveZoom tarafindan yazilir. Gozlemci de
        // yazsaydi optik ve yazilim degerleri farkli anlarda guncellendigi
        // icin arada yanlis degerler gorunurdu.
        updateLensLabels()
        updateSummary()
        // Zoom durumu baglanmadan hemen sonra hazir olmayabiliyor; baslangic
        // zoom'unu ilk gecerli deger gelince uygula.
        if (pendingStartZoom) {
            pendingStartZoom = false
            applyStartZoom()
        }
    }

    private fun observeZoom() {
        observedZoom?.removeObserver(zoomObserver)
        val live = camera?.cameraInfo?.zoomState ?: return
        observedZoom = live
        live.observe(this, zoomObserver)
    }

    /** Onizlemeyi cekimin baslayacagi zoom degerine goturur (kadraj icin). */
    private fun applyStartZoom() {
        if (isBusy()) return
        val range = resolveZoomRange() ?: return
        rampOpticalBasis = opticalBasisFor(range)
        if (isPicking) {
            updateTargetRect()
            return
        }
        val target = if (mode == CameraMode.TWO_POINT && composeZoom > 0f) {
            composeZoom
        } else {
            range.first
        }
        applyEffectiveZoom(target)
        applyModeToUi()
    }

    /**
     * Cihazin destekledigi en iyi sabitleme modunu secer. Onizleme sabitlemesi
     * (API 33+) OIS ile birlikte calisir ve en akici sonucu verir; yoksa klasik
     * EIS'e, o da yoksa kapaliya duser.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun bestStabilizationMode(): Int {
        val off = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        val on = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
        if (!stabilization) return off
        val info = camera?.cameraInfo ?: return on
        val modes = runCatching {
            Camera2CameraInfo.from(info).getCameraCharacteristic(
                CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
            )
        }.getOrNull() ?: return on

        val previewStabilization = 2 // CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                modes.contains(previewStabilization) -> previewStabilization
            modes.contains(on) -> on
            else -> off
        }
    }

    /**
     * Cekim boyunca pozlama, renk ve odagi sabitler. Zoom sirasinda otomatik
     * odagin "av"a cikmasi goruntude nefes alma/titreme yaratir; kilit bunu
     * engeller. EIS her zaman aciktir.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyCaptureOptions(locked: Boolean) {
        val cameraControl = camera?.cameraControl ?: return
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                bestStabilizationMode()
            )
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, locked)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps)
            )
            .build()
        Camera2CameraControl.from(cameraControl).setCaptureRequestOptions(options)

        if (locked) {
            // Merkeze odaklanip kilitle; otomatik iptal kapali.
            runCatching {
                val point = binding.previewView.meteringPointFactory.createPoint(0.5f, 0.5f)
                cameraControl.startFocusAndMetering(
                    FocusMeteringAction.Builder(
                        point,
                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                    ).disableAutoCancel().build()
                )
            }
        } else {
            runCatching { cameraControl.cancelFocusAndMetering() }
        }
    }

    // ---------------------------------------------------------------- Zoom sekanslari

    /**
     * Modun ve secili lens menzilinin hedef zoom araligini, cihazin
     * sinirlarina kirparak dondurur.
     *
     * TELE ve MAIN araliklari esigin iki yaninda kalir; boylece cekim boyunca
     * tek lens kullanilir ve gecis sicramasi olmaz.
     */
    private fun resolveZoomRange(): Pair<Float, Float>? {
        val state = camera?.cameraInfo?.zoomState?.value ?: return null
        val deviceMin = state.minZoomRatio
        val deviceMax = state.maxZoomRatio

        // IKI NOKTA modunda araligi kullanicinin kurdugu kadrajlar belirler.
        if (mode == CameraMode.TWO_POINT) {
            val a = pointA
            val b = pointB
            if (a != null && b != null) return a.zoom to b.zoom
        }

        val (rawStart, rawEnd) = when (mode) {
            CameraMode.VERTIGO -> min(lensThreshold - 0.2f, deviceMax) to 1f
            CameraMode.DRONIE -> min(2.5f, deviceMax) to deviceMin
            else -> {
                val bounds = zoomBoundsFor(lensRange, deviceMin, deviceMax)
                bounds.second to bounds.first
            }
        }
        // Ust sinir cihazin optik tavani DEGIL, yazilim kirpmasiyla ulasilabilen
        // efektif tavandir; aksi halde istenen zoom optige geri kirpilir ve
        // yazilim zoom hic devreye girmez.
        val maxEffective = if (mode.allowsLensRange) {
            opticalCeiling(lensRange, deviceMax) * softZoomMax
        } else {
            deviceMax
        }
        val start = rawStart.coerceIn(deviceMin, maxEffective)
        val end = rawEnd.coerceIn(deviceMin, maxEffective)
        val forward = zoomOut || mode == CameraMode.VERTIGO
        return if (forward) start to end else end to start
    }

    private fun curveInterpolator() = when (curve) {
        ZoomCurve.CINEMATIC -> AccelerateDecelerateInterpolator()
        ZoomCurve.LINEAR -> LinearInterpolator()
    }

    private fun buildSequence(startZoom: Float, endZoom: Float): List<ZoomSegment> {
        val total = durationSec * 1000L
        return when (mode) {
            // DRONE klasik ortadan zoom; merkez secimi ACILIS modunda.
            CameraMode.DRONE, CameraMode.DRONIE -> listOf(
                ZoomSegment.Hold(startZoom, 800),
                ZoomSegment.Ramp(startZoom, endZoom, total, curveInterpolator()),
                ZoomSegment.Hold(endZoom, 800)
            )
            // Acilis: secilen noktadan baslar, genise acilirken merkez de ortaya kayar.
            CameraMode.REVEAL -> listOf(
                ZoomSegment.Hold(startZoom, 800, centerX, centerY),
                ZoomSegment.Ramp(
                    startZoom, endZoom, total, curveInterpolator(),
                    centerX, centerY, ZoomSegment.CENTER, ZoomSegment.CENTER
                ),
                ZoomSegment.Hold(endZoom, 800, ZoomSegment.CENTER, ZoomSegment.CENTER)
            )
            // Kaydirma: zoom sabit, kirpma penceresi bir uctan digerine gider.
            // Ucu 0/1 veriyoruz; golgeleyici zoom'a gore zaten kirpiyor, boylece
            // mevcut pay ne kadarsa o kadar genis kayar.
            // Iki nokta: kullanicinin kurdugu iki kadraj arasinda gecis.
            CameraMode.TWO_POINT -> {
                val a = pointA
                val b = pointB
                if (a == null || b == null) {
                    emptyList()
                } else {
                    // Kayitli kadrajlar ekran uzayindadir; doku uzayina cevrilir.
                    val (ax, ay) = screenToBuffer(a.cx, a.cy)
                    val (bx, by) = screenToBuffer(b.cx, b.cy)
                    listOf(
                        ZoomSegment.Hold(a.zoom, 600, ax, ay),
                        ZoomSegment.Ramp(
                            a.zoom, b.zoom, total, curveInterpolator(),
                            ax, ay, bx, by
                        ),
                        ZoomSegment.Hold(b.zoom, 600, bx, by)
                    )
                }
            }
            CameraMode.BOOMERANG -> listOf(
                ZoomSegment.Hold(startZoom, 500),
                ZoomSegment.Ramp(startZoom, endZoom, total / 2, curveInterpolator()),
                ZoomSegment.Hold(endZoom, 500),
                ZoomSegment.Ramp(endZoom, startZoom, total / 2, curveInterpolator()),
                ZoomSegment.Hold(startZoom, 500)
            )
            CameraMode.STEP -> buildStepSequence(startZoom, endZoom, total)
            CameraMode.TIMELAPSE -> listOf(
                // Gercek cekim suresi hiz carpani kadar uzundur; kayit
                // sirasinda kareler seyreltilip zaman damgalari sikistirildigi
                // icin video secilen surede oynar.
                ZoomSegment.Hold(startZoom, 800),
                ZoomSegment.Ramp(startZoom, endZoom, total * timelapseSpeed, LinearInterpolator()),
                ZoomSegment.Hold(endZoom, 800)
            )
            CameraMode.VERTIGO -> listOf(
                ZoomSegment.Hold(startZoom, 1000),
                ZoomSegment.Ramp(startZoom, endZoom, total, LinearInterpolator()),
                ZoomSegment.Hold(endZoom, 700)
            )
            else -> emptyList()
        }
    }

    /** Geometrik araliklarla 4 kademeli, duraklamali zoom (hyper zoom). */
    private fun buildStepSequence(
        startZoom: Float,
        endZoom: Float,
        totalMs: Long
    ): List<ZoomSegment> {
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
        if (settingsOpen) toggleSettings(false)

        if (mode == CameraMode.TWO_POINT && (pointA == null || pointB == null)) {
            Toast.makeText(this, R.string.point_missing, Toast.LENGTH_LONG).show()
            return
        }
        if (isPicking) togglePicker(false)
        val range = resolveZoomRange() ?: return
        rampOpticalBasis = opticalBasisFor(range)
        val sequence = buildSequence(range.first, range.second)
        if (sequence.isEmpty()) return
        // Kayit sekansin ILK KARESINDEN baslasin: zoom ve merkez birlikte
        // kurulur, aksi halde video baska bir kadrajdan baslayip atliyordu.
        applySequenceStart(sequence)

        withCountdown {
            // Odak/pozlama kilidi kayit BASLAMADAN once verilir ve oturmasi
            // beklenir; aksi halde otomatik odagin taramasi videonun ilk
            // saniyesine bulaniklik olarak yansiyor.
            if (lockExposure) applyCaptureOptions(locked = true)
            binding.previewView.postDelayed({ startRecording(sequence) }, FOCUS_SETTLE_MS)
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

    private fun startRecording(sequence: List<ZoomSegment>) {
        val videoCapture = this.videoCapture ?: return

        // Timelapse once gecici dosyaya cekilir; bitince yeniden paketlenerek
        // hizlandirilir ve galeriye oyle yazilir.
        val useTempFile = mode == CameraMode.TIMELAPSE &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val pendingRecording = if (useTempFile) {
            val temp = File(cacheDir, TIMELAPSE_TEMP_NAME)
            temp.delete()
            timelapseTemp = temp
            videoCapture.output.prepareRecording(
                this, FileOutputOptions.Builder(temp).build()
            )
        } else {
            timelapseTemp = null
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, timestampName("DRONE_"))
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DroneCamera")
                }
            }
            videoCapture.output.prepareRecording(
                this,
                MediaStoreOutputOptions.Builder(
                    contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                ).setContentValues(values).build()
            )
        }

        val pending = pendingRecording
            .apply {
                // Timelapse'te zaman sikistirildigi icin ses anlamsiz kalir.
                val wantsAudio = !muteAudio && mode != CameraMode.TIMELAPSE
                if (wantsAudio && hasPermission(Manifest.permission.RECORD_AUDIO)) withAudioEnabled()
            }

        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    isShotRunning = true
                    setRecordingUi(true)
                    if (mode == CameraMode.TIMELAPSE) {
                        zoomProcessor?.beginTimelapse(timelapseSpeed)
                    }
                    startZoomSequence(sequence)
                }
                is VideoRecordEvent.Status -> {
                    val sec = event.recordingStats.recordedDurationNanos / 1_000_000_000
                    binding.recTimer.text =
                        String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    isShotRunning = false
                    applyCaptureOptions(locked = false)
                    setRecordingUi(false)
                    if (event.hasError()) {
                        Toast.makeText(
                            this, getString(R.string.record_error, event.error), Toast.LENGTH_LONG
                        ).show()
                    } else {
                        val temp = timelapseTemp
                        if (temp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            finishTimelapse(temp)
                        } else {
                            lastVideoUri = event.outputResults.outputUri
                            updateGalleryThumb()
                            Toast.makeText(this, R.string.video_saved, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    /**
     * Zoom rampasini baslatir.
     *
     * "Akici" motorda optik zoom cekim boyunca sabit tutulur ve rampanin
     * tamami GPU kirpmasiyla yapilir: her kare icin tam deger hesaplandigi
     * ve donanimin kademeli zoom adimlari devreye girmedigi icin gecis
     * puruzsuzdur. "Net" motorda optik zoom rampayi takip eder (daha keskin
     * goruntu), kirpma da istekler arasindaki bosluklari doldurur.
     */
    /** Sekansin sifirinci anindaki kadraji (zoom + merkez) onizlemeye uygular. */
    private fun applySequenceStart(sequence: List<ZoomSegment>) {
        val basis = rampOpticalBasis.takeIf { it > 0f } ?: return
        val frame = FloatArray(3)
        sequence.frameAt(0L, frame)
        lastOpticalRequested = basis
        camera?.cameraControl?.setZoomRatio(basis)
        softZoomLevel = (frame[0] / basis).coerceIn(1f, MAX_SOFT_CROP)
        zoomProcessor?.zoom = softZoomLevel
        zoomProcessor?.setCenter(frame[1], frame[2])
        updateZoomBadge(frame[0])
    }

    private fun startZoomSequence(sequence: List<ZoomSegment>) {
        val processor = zoomProcessor
        val softwareRamp = processor != null && rampOpticalBasis > 0f
        val startedAt = SystemClock.elapsedRealtime()
        val basis = rampOpticalBasis

        // Optik zoom zaten onizlemede dogru degerde; burada DEGISTIRILMEZ.
        processor?.frameProvider = { out ->
            sequence.frameAt(SystemClock.elapsedRealtime() - startedAt, out)
            out[0] = (out[0] / basis).coerceIn(1f, MAX_SOFT_CROP)
        }

        sequencePlayer = ZoomSequencePlayer(
            segments = sequence,
            setZoom = { effective ->
                updateZoomBadge(effective)
                if (!softwareRamp) applyEffectiveZoom(effective)
            },
            onProgress = { fraction, remainingSec ->
                binding.progressRing.progress = fraction
                binding.remainText.text = getString(R.string.remaining_fmt, remainingSec)
            },
            onEnd = { stopShot() }
        ).also { it.start() }
    }

    /**
     * Timelapse ham kaydini hizlandirarak galeriye yazar. Yeniden kodlama
     * yapilmaz; yalnizca zaman damgalari olceklenir, bu yuzden islem kisadir
     * ve goruntu kalitesi birebir korunur.
     */
    private fun finishTimelapse(temp: File) {
        timelapseTemp = null
        val speed = timelapseSpeed
        isProcessing = true
        Toast.makeText(this, R.string.timelapse_processing, Toast.LENGTH_SHORT).show()

        Thread {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, timestampName("TIMELAPSE_"))
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DroneCamera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = runCatching {
                contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            }.getOrNull()

            var success = false
            if (uri != null) {
                success = runCatching {
                    contentResolver.openFileDescriptor(uri, "w")?.use { descriptor ->
                        TimelapseRemuxer.remux(temp, descriptor.fileDescriptor, speed)
                    } ?: false
                }.getOrDefault(false)

                if (success) {
                    runCatching {
                        contentResolver.update(
                            uri,
                            ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                            null,
                            null
                        )
                    }
                } else {
                    runCatching { contentResolver.delete(uri, null, null) }
                }
            }
            temp.delete()

            runOnUiThread {
                isProcessing = false
                if (success && uri != null) {
                    lastVideoUri = uri
                    updateGalleryThumb()
                    Toast.makeText(this, R.string.video_saved, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, R.string.timelapse_failed, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun stopShot() {
        zoomProcessor?.frameProvider = null
        zoomProcessor?.endTimelapse()
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
        setVisible(binding.remainText, recording)
        binding.recTimer.text = getString(R.string.timer_zero)
        binding.progressRing.progress = 0f
        binding.modeScroll.alpha = if (recording) 0.35f else 1f
        binding.lensScroll.alpha = if (recording) 0.35f else 1f
        binding.btnSettings.alpha = if (recording) 0.35f else 1f
        binding.btnRehearse.alpha = if (recording) 0.35f else 1f
        binding.btnGallery.alpha = if (recording) 0.35f else 1f
        if (recording) binding.guideText.visibility = View.GONE else applyModeToUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownTimer?.cancel()
        sequencePlayer?.cancel()
        activeRecording?.stop()
        zoomProcessor?.release()
        zoomProcessor = null
    }

    private companion object {
        /** Kirpma ile ulasilabilecek azami buyutme (asiri yumusamayi onler). */
        const val MAX_SOFT_CROP = 12f

        /** Odak/pozlama kilidinin oturmasi icin kayit oncesi beklenen sure. */
        const val FOCUS_SETTLE_MS = 600L

        /** Prova rampasinin suresi. */
        const val REHEARSAL_MS = 2000L

        /** Timelapse ham kaydinin gecici dosya adi. */
        const val TIMELAPSE_TEMP_NAME = "timelapse_raw.mp4"
    }
}
