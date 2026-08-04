package com.dronecamera.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.MediaStore
import android.view.HapticFeedbackConstants
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
    private lateinit var prefs: SharedPreferences

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null
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
    private var showGrid = false
    private var useFrontCamera = false

    private var isShotRunning = false
    private var isCountingDown = false
    private var settingsOpen = false

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

        buildModeCarousel()
        buildLensSegments()
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
        stabilization = prefs.getBoolean("stabilization", stabilization)
        showGrid = prefs.getBoolean("showGrid", showGrid)
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
            .putBoolean("stabilization", stabilization)
            .putBoolean("showGrid", showGrid)
            .apply()
    }

    // ---------------------------------------------------------------- UI yardimcilari

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun isBusy() = isShotRunning || isCountingDown

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
        container: LinearLayout,
        titleRes: Int,
        items: List<Pair<T, String>>,
        current: () -> T,
        onSelect: (T) -> Unit
    ) {
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
            }
            row.addView(chip)
            key to chip
        }
        container.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
        refreshers += { chips.forEach { (key, chip) -> styleChip(chip, key == current()) } }
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
                savePrefs()
                applyModeToUi()
                refreshAll()
                bindCamera()
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
    private fun zoomBoundsFor(range: LensRange, deviceMin: Float, deviceMax: Float): Pair<Float, Float> =
        when (range) {
            LensRange.TELE -> min(lensThreshold + 0.2f, deviceMax) to deviceMax
            LensRange.MAIN -> 1f to min(lensThreshold - 0.2f, deviceMax)
            LensRange.FULL -> deviceMin to deviceMax
        }

    private fun buildSettingsSheet() {
        val content = binding.settingsContent

        addChipRow(
            content, R.string.label_duration,
            listOf(8, 10, 15, 20, 30).map { it to getString(R.string.duration_fmt, it) },
            { durationSec }, { durationSec = it }
        )
        addChipRow(
            content, R.string.label_direction,
            listOf(true to getString(R.string.dir_out), false to getString(R.string.dir_in)),
            { zoomOut }, { zoomOut = it }
        )
        addChipRow(
            content, R.string.label_curve,
            ZoomCurve.values().map { it to getString(it.labelRes) },
            { curve }, { curve = it }
        )
        addChipRow(
            content, R.string.label_countdown,
            listOf(
                0 to getString(R.string.countdown_off),
                3 to getString(R.string.countdown_3),
                10 to getString(R.string.countdown_10)
            ),
            { countdownSec }, { countdownSec = it }
        )

        addThresholdRow(content)

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
    }

    /**
     * Lens esigi kalibrasyonu: kaydirici oynatildikca onizleme o zoom degerine
     * gider, boylece kullanici goruntunun sicradigi noktayi kendi gozuyle
     * bulup esigi tam oraya ayarlar.
     */
    private fun addThresholdRow(container: LinearLayout) {
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
                if (fromUser && !isBusy()) camera?.cameraControl?.setZoomRatio(lensThreshold)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = savePrefs()
        })
        container.addView(bar)
        value.text = getString(R.string.zoom_format, lensThreshold)
        refreshers += { value.text = getString(R.string.zoom_format, lensThreshold) }
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
        binding.btnFlip.setOnClickListener {
            if (isBusy()) return@setOnClickListener
            haptic(it)
            useFrontCamera = !useFrontCamera
            bindCamera()
        }
        applyModeToUi()
    }

    private fun toggleSettings(open: Boolean) {
        if (isBusy() && open) return
        settingsOpen = open
        setVisible(binding.settingsSheet, open)
        setVisible(binding.settingsScrim, open)
        if (!open) applyStartZoom()
    }

    private fun gridVisibility() = if (showGrid) View.VISIBLE else View.GONE

    /** Moda gore hangi kontrollerin gorunecegini ayarlar. */
    private fun applyModeToUi() {
        setVisible(binding.lensScroll, mode.allowsLensRange)
        setVisible(binding.btnFlip, mode == CameraMode.PHOTO || mode == CameraMode.VIDEO)
        binding.gridGroup.visibility = gridVisibility()

        when (mode) {
            CameraMode.VERTIGO -> showGuide(R.string.vertigo_guide)
            CameraMode.DRONIE -> showGuide(R.string.dronie_guide)
            CameraMode.DRONE, CameraMode.BOOMERANG, CameraMode.STEP ->
                if (lensRange == LensRange.FULL) showGuide(R.string.full_range_warning)
                else binding.guideText.visibility = View.GONE
            else -> binding.guideText.visibility = View.GONE
        }
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
    private fun bindCamera() {
        val provider = this.provider ?: return
        val selector = chooseCameraSelector(provider)

        try {
            provider.unbindAll()
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            camera = if (mode == CameraMode.PHOTO) {
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture
                videoCapture = null
                provider.bindToLifecycle(this, selector, preview, capture)
            } else {
                // FHD, 4K'ya gore hem daha akici hem sabitlemeyi daha genis
                // destekler; titreme icin bilincli tercih.
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
                provider.bindToLifecycle(this, selector, preview, capture)
            }
            observeZoom()
            applyCaptureOptions(locked = false)
            applyStartZoom()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.camera_error, e.message), Toast.LENGTH_LONG)
                .show()
        }
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
     * Tek lens modunda cok lensli (logical) olmayan gercek fiziksel arka kamera
     * aranir. Cihaz bunu uygulamalara acmiyorsa (Honor boyle) varsayilan
     * kameraya donulur; lens gecisi bu durumda zoom araligini tek bir lensin
     * bolgesinde tutarak engellenir (bkz. resolveZoomRange).
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun chooseCameraSelector(provider: ProcessCameraProvider): CameraSelector {
        val wantsFront = mode.usesFrontCamera ||
            (useFrontCamera && (mode == CameraMode.PHOTO || mode == CameraMode.VIDEO))
        if (wantsFront) return CameraSelector.DEFAULT_FRONT_CAMERA
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

    private fun observeZoom() {
        camera?.cameraInfo?.zoomState?.observe(this) { state ->
            binding.zoomText.text = getString(R.string.zoom_format, state.zoomRatio)
            updateLensLabels()
        }
    }

    /** Onizlemeyi cekimin baslayacagi zoom degerine goturur (kadraj icin). */
    private fun applyStartZoom() {
        if (isBusy()) return
        val range = resolveZoomRange() ?: return
        camera?.cameraControl?.setZoomRatio(range.first)
        applyModeToUi()
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

        val (rawStart, rawEnd) = when (mode) {
            CameraMode.VERTIGO -> min(lensThreshold - 0.2f, deviceMax) to 1f
            CameraMode.DRONIE -> min(2.5f, deviceMax) to deviceMin
            else -> {
                val bounds = zoomBoundsFor(lensRange, deviceMin, deviceMax)
                bounds.second to bounds.first
            }
        }
        val start = rawStart.coerceIn(deviceMin, deviceMax)
        val end = rawEnd.coerceIn(deviceMin, deviceMax)
        val forward = zoomOut || mode == CameraMode.VERTIGO
        return if (forward) start to end else end to start
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
                ZoomSegment.Hold(startZoom, 800),
                ZoomSegment.Ramp(startZoom, endZoom, total, curveInterpolator()),
                ZoomSegment.Hold(endZoom, 800)
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

        when (mode) {
            CameraMode.PHOTO -> withCountdown { takePhoto() }
            CameraMode.VIDEO -> withCountdown { startRecording(null) }
            else -> {
                val range = resolveZoomRange() ?: return
                camera?.cameraControl?.setZoomRatio(range.first)
                withCountdown { startRecording(buildSequence(range.first, range.second)) }
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
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    Toast.makeText(this@MainActivity, R.string.photo_saved, Toast.LENGTH_SHORT)
                        .show()
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
                    if (lockExposure) applyCaptureOptions(locked = true)
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
        binding.modeScroll.alpha = if (recording) 0.35f else 1f
        binding.lensScroll.alpha = if (recording) 0.35f else 1f
        binding.btnSettings.alpha = if (recording) 0.35f else 1f
        binding.btnFlip.alpha = if (recording) 0.35f else 1f
        if (recording) binding.guideText.visibility = View.GONE else applyModeToUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownTimer?.cancel()
        sequencePlayer?.cancel()
        activeRecording?.stop()
    }
}
