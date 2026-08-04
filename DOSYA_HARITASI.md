# Dosya Haritası

Her push'ta güncelle. Amaç: dosyaları körlemesine okumadan doğru yere gitmek.

## Kod (app/src/main/java/com/dronecamera/app/)

### MainActivity.kt (~700 satır) — tüm uygulama akışı
Bölge başlıklarıyla gezin (`// ---- ...` yorum satırları):
- Alanlar (~55-95): `mode, lensRange, durationSec, zoomOut, curve, countdownSec,
  lensThreshold, lockExposure, stabilization, showGrid, useFrontCamera` + `refreshers`
- `Ayar saklama`: `loadPrefs()/savePrefs()` — SharedPreferences "drone_camera";
  `savePreset/loadPreset/hasPreset` (3 şablon slotu, "p<slot>_*" anahtarları)
- `UI yardimcilari`: `dp/isBusy/haptic/setVisible/makeChip/styleChip/refreshAll`,
  jenerik `addChipRow()`, `addSwitchRow()`
- `Menu kurulumu`: `buildModeCarousel()` (nokta göstergeli mod şeridi),
  `buildLensSegments()` + `updateLensLabels()/zoomBoundsFor()/fmtZoom()` (çiplerde
  gerçek aralık yazar: "TELE 4–15x"), `buildSettingsSheet()` (tüm ayar satırları),
  `addThresholdRow()` (canlı kalibrasyon SeekBar), `setupControls()`,
  `addPresetRow()`, `toggleSettings()`, `applyModeToUi()`, `sizeSettingsSheet()`
  (yatayda panel yüksekliğini ekrana uydurur), `runRehearsal()` (PROVA: kayıtsız
  hızlı rampa önizlemesi), `updateGalleryThumb()/openLastVideo()`
- `Kamera`: `startCamera()`, `bindCamera()` (sabitleme için 3 kademeli fallback:
  önizleme→video→kapalı), `chooseCameraSelector()`, `observeZoom()`,
  `applyStartZoom()`, `applyCaptureOptions(locked)` (AE/AWB + odak kilidi, EIS)
- `Zoom sekanslari`: `resolveZoomRange()` (mod + lensRange + eşik → aralık),
  `curveInterpolator()`, `buildSequence()`, `buildStepSequence()`
- `Cekim akisi`: `onShutter()`, `withCountdown()/cancelCountdown()`, `takePhoto()`,
  `startRecording(sequence?)`, `stopShot()`, `setRecordingUi()`

### CameraMode.kt (~90 satır)
`ZoomCurve`, `LensRange` (TELE/MAIN/FULL), `CameraMode` (DRONE/REVEAL/PAN/
BOOMERANG/STEP/TIMELAPSE/TWO_POINT/VERTIGO/DRONIE) + `usesFrontCamera/
allowsLensRange/usesDirection/usesCurve/directionIsHorizontal`, `FramePoint`
(İKİ NOKTA kadrajı: zoom + merkez).

### ProgressRing.kt (~60 satır)
Deklanşörün çevresindeki ilerleme halkası (`progress` 0..1).

### ZoomSequence.kt (~180 satır)
`ZoomSegment` artık KADRAJ taşır: zoom + kırpma merkezi (cx, cy). Ramp zoom'u
logaritmik, merkezi doğrusal yorumlar. `List<ZoomSegment>.frameAt(ms, out)`
herhangi bir andaki [zoom, cx, cy] değerini yazar (kırpma her karede bunu
çağırır; dizi dışarıdan verilir ki kare başına nesne üretilmesin) ve
`ZoomSequencePlayer`. ÖNEMLİ: `emitZoom()` optik istekleri ~30 Hz'e seyreltir
(MIN_INTERVAL_MS/MIN_ZOOM_STEP) — titreme çözümünün kalbi, dokunurken dikkat.

### ZoomSurfaceProcessor.kt (~400 satır)
Donanım zoom tavanının (bu cihazda 10x) üstünü sağlayan GPU kırpma hattı.
`SurfaceProcessor` uygular; kareleri OES dokusundan alıp kırpılmış olarak hem
önizlemeye hem kayda çizer. `zoomProvider` ayarlıysa değer her kare için oradan
okunur (akıcılığın kaynağı). `setCenter()` kırpma penceresini kaydırır (dokunarak
kadraj merkezi). `beginTimelapse/endTimelapse` video çıkışına kareleri seyreltip
zaman damgalarını sıkıştırır — timelapse böyle üretilir, önizleme gerçek zamanlı
kalır. `SoftZoomEffect` = CameraEffect sarmalayıcısı.

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
