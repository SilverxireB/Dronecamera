# Dosya Haritası

Her push'ta güncelle. Amaç: dosyaları körlemesine okumadan doğru yere gitmek.

## Kod (app/src/main/java/com/dronecamera/app/)

### MainActivity.kt (~560 satır) — tüm uygulama akışı
Bölge başlıklarıyla gezin (`// ---- ...` yorum satırları):
- Alanlar + izinler: dosya başı (~40-110). Kullanıcı seçimleri: `mode, durationSec,
  zoomOut, curve, countdownSec, lockExposure, singleLens, singleLensStartZoom, useFrontCamera`
- `UI kurulum` bölgesi: `dp/makeChip/makeLabel/markSelected` yardımcıları,
  `buildChips()` (mod/süre/yön/eğri/sayaç/başlangıç-zoom çipleri),
  `setupControls()` (deklanşör + üst bar ikonları), `updateToggleTints()`,
  `updateUiForMode()` (moda göre görünürlük), `showGuide()`
- `Kamera` bölgesi: `startCamera()`, `bindCamera()` (FOTO→ImageCapture,
  diğerleri→VideoCapture; mod değişiminde yeniden bağlanır),
  `chooseCameraSelector()` (ön kamera / tek-lens fiziksel kamera arama / fallback),
  `observeZoom()`, `applyCaptureOptions(lockAeAwb)` (AE/AWB kilidi + EIS)
- `Zoom sekansları` bölgesi: `resolveZoomRange()` (mod başına hedef aralık +
  cihaz sınırına kırpma), `curveInterpolator()`, `buildSequence()` (mod →
  segment listesi), `buildStepSequence()` (kademeli 4 seviye)
- `Cekim akisi` bölgesi: `onShutter()` (mod yönlendirme + iptal),
  `withCountdown()/cancelCountdown()`, `takePhoto()`, `startRecording(sequence?)`
  (VideoRecordEvent.Start/Status/Finalize), `stopShot()`, `setRecordingUi()`

### CameraMode.kt (~30 satır)
`ZoomCurve` enum (CINEMATIC/LINEAR/AGGRESSIVE) ve `CameraMode` enum
(PHOTO/VIDEO/DRONE/BOOMERANG/STEP/VERTIGO/DRONIE) + `isZoomMode/usesFrontCamera/recordsVideo`.

### ZoomSequence.kt (~90 satır)
`ZoomSegment` (Ramp: logaritmik uzayda eased rampa; Hold) ve `ZoomSequencePlayer`
(segmentleri sırayla oynatır; onProgress/onEnd; cancel).

## Kaynaklar (app/src/main/res/)

- `layout/activity_main.xml`: previewView, topBar (recDot/recTimer/zoomText/
  btnLock/btnLens/btnFlip), guideText, countdownOverlay, bottomPanel
  (progressBar, optionsScroll>optionsRow, modeScroll>modeRow, shutterButton)
- `values/strings.xml`: tüm TR metinler (mod adları, çip etiketleri, mesajlar)
- `values/colors.xml`: textPrimary/textSecondary/textOnChip/accentIce/recording
- `values/themes.xml`: Theme.DroneCamera (Material3 Dark NoActionBar)
- `drawable/`: glass_panel, chip_bg, chip_bg_selected (beyaz seçili çip),
  shutter_idle/shutter_rec, rec_dot, ic_lock/ic_lens/ic_flip (çizgi ikonlar),
  ic_launcher_foreground
- `mipmap-anydpi-v26/ic_launcher.xml`: adaptif ikon

## Yapılandırma

- `app/build.gradle.kts`: minSdk 26, compile/target 34, CameraX 1.3.4, viewBinding
- `app/src/main/AndroidManifest.xml`: CAMERA + RECORD_AUDIO izinleri, portrait
- `build.gradle.kts` / `settings.gradle.kts` / `gradle.properties`: standart
- `.github/workflows/build-apk.yml`: APK derle → artifact + `son-surum` Release
- `README.md`: kullanıcıya dönük tanıtım/kurulum (özellik ekleyince güncelle)
