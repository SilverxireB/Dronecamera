# Dosya Haritası

Her push'ta güncelle. Amaç: dosyaları körlemesine okumadan doğru yere gitmek.

## Kod (app/src/main/java/com/dronecamera/app/)

### MainActivity.kt (~700 satır) — tüm uygulama akışı
Bölge başlıklarıyla gezin (`// ---- ...` yorum satırları):
- Alanlar (~55-95): `mode, lensRange, durationSec, zoomOut, curve, countdownSec,
  lensThreshold, lockExposure, stabilization, showGrid, useFrontCamera` + `refreshers`
- `Ayar saklama`: `loadPrefs()/savePrefs()` — SharedPreferences "drone_camera"
- `UI yardimcilari`: `dp/isBusy/haptic/setVisible/makeChip/styleChip/refreshAll`,
  jenerik `addChipRow()`, `addSwitchRow()`
- `Menu kurulumu`: `buildModeCarousel()` (nokta göstergeli mod şeridi),
  `buildLensSegments()` (TELE/MAIN/FULL), `buildSettingsSheet()` (tüm ayar satırları),
  `addThresholdRow()` (canlı kalibrasyon SeekBar), `setupControls()`,
  `toggleSettings()`, `applyModeToUi()`
- `Kamera`: `startCamera()`, `bindCamera()` (sabitleme için 3 kademeli fallback:
  önizleme→video→kapalı), `chooseCameraSelector()`, `observeZoom()`,
  `applyStartZoom()`, `applyCaptureOptions(locked)` (AE/AWB + odak kilidi, EIS)
- `Zoom sekanslari`: `resolveZoomRange()` (mod + lensRange + eşik → aralık),
  `curveInterpolator()`, `buildSequence()`, `buildStepSequence()`
- `Cekim akisi`: `onShutter()`, `withCountdown()/cancelCountdown()`, `takePhoto()`,
  `startRecording(sequence?)`, `stopShot()`, `setRecordingUi()`

### CameraMode.kt (~55 satır)
`ZoomCurve` (labelRes'li), `LensRange` (TELE/MAIN/FULL + `isSingleLens`),
`CameraMode` (PHOTO/VIDEO/DRONE/BOOMERANG/STEP/VERTIGO/DRONIE) +
`isZoomMode/usesFrontCamera/recordsVideo/allowsLensRange`.

### ZoomSequence.kt (~110 satır)
`ZoomSegment` (Ramp: logaritmik uzayda eased; Hold) ve `ZoomSequencePlayer`.
ÖNEMLİ: `emitZoom()` istekleri ~30 Hz'e seyreltir (MIN_INTERVAL_MS/MIN_ZOOM_STEP) —
titreme çözümünün kalbi, dokunurken dikkat.

## Kaynaklar (app/src/main/res/)

- `layout/activity_main.xml`: previewView, ızgara (4 guideline + 4 View + `gridGroup`),
  topBar (recDot/recTimer/zoomText), guideText, countdownOverlay,
  bottomPanel (progressBar, lensRow, modeScroll>modeRow, btnSettings/shutterButton/btnFlip),
  settingsScrim, settingsSheet (başlık + ScrollView 360dp > `settingsContent`)
- `values/strings.xml`: tüm TR metinler; `values/colors.xml`: textPrimary/Secondary/
  Tertiary/OnChip, accentIce, gridLine; `values/themes.xml`: Theme.AppCompat.NoActionBar
- `drawable/`: glass_panel, sheet_bg, sheet_handle, bottom_scrim, chip_bg,
  chip_bg_selected, progress_line, mode_dot, shutter_idle/rec, rec_dot,
  ic_settings/ic_close/ic_flip, ic_launcher_foreground

## Yapılandırma

- `app/build.gradle.kts`: minSdk 26, compile/target 34, CameraX 1.3.4, viewBinding,
  sabit imza (signingConfigs "shared" → app/keystore/dronecamera.keystore)
- `app/src/main/AndroidManifest.xml`: CAMERA + RECORD_AUDIO izinleri, portrait
- `.github/workflows/build-apk.yml`: APK derle → artifact + `son-surum` Release
- `README.md`: kullanıcıya dönük tanıtım/kurulum (özellik ekleyince güncelle)
