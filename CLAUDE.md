# Drone Kamera — Proje Notları

Android kamera uygulaması (Kotlin + CameraX). Telefon zoom'uyla drone benzeri
video çekimleri yapar. Kullanıcı dili: Türkçe. Cihaz: Honor Magic 8 Pro
(0.5x–15x; telefoto lens geçiş eşiği ~3.5x).

## Token tasarrufu kuralları (ÖNEMLİ)

- Önce `DOSYA_HARITASI.md` oku, sadece ilgili dosya/fonksiyona git. Her dosyayı okuma.
- Her push'ta `DOSYA_HARITASI.md`'yi ve gerekirse bu dosyayı güncelle.
- MainActivity'nin tamamını okumak yerine haritadaki bölge başlıklarına göre offset/limit ile oku.

## Derleme ve dağıtım

- Yerel derleme YOK (dl.google.com proxy'de engelli). Tek doğrulama yolu: push → GitHub Actions.
- CI: `.github/workflows/build-apk.yml` → debug APK derler + `son-surum` tag'li Release'e `DroneKamera.apk` olarak ekler.
- Kullanıcının indirme linki (sabit): https://github.com/SilverxireB/Dronecamera/releases/download/son-surum/DroneKamera.apk
- CI durumunu `api.github.com`'a curl ile SORMA (proxy engelliyor). GitHub MCP `actions_list` kullan; çıktı büyükse kaydedilen dosyada `grep -o '"head_sha":"..."...conclusion'` ile bak.
- Bekleme: arka plan Bash sleep-timer (~3.5 dk) → bitince MCP ile kontrol.

## Git

- Branch: `claude/drone-zoom-camera-app-jagwlb`. Commit'ler Türkçe, `-c user.email="doganbaharozu@gmail.com" -c user.name="SilverxireB"` ile.

## Mimari kararlar (tekrar tartışma)

- Zoom rampaları logaritmik uzayda (algısal sabit hız) → `ZoomSequencePlayer`.
- Lens geçişi sıçramalarına karşı: çekimde AE/AWB kilidi + EIS (`applyCaptureOptions`).
- Honor fiziksel kamerayı uygulamalara AÇMIYOR → tek lens modu mantıksal kameraya
  düşer. ÇÖZÜM: zoom aralığını tek bir lensin bölgesinde tut (`LensRange`):
  TELE = eşik+0.2 → max, MAIN = 1x → eşik-0.2 (`zoomBoundsFor`).
  Honor Magic 8 Pro'da telefoto TAM 3.7x'te (85mm) devreye giriyor — varsayılan eşik 3.7.
- UI TUZAĞI: dikey LinearLayout'a eklenen View varsayılan MATCH_PARENT genişlik alır;
  HorizontalScrollView içinde bu genişlik 0'a çöker ve yazı tek harfe kırpılır.
  Kodla üretilen her etikete AÇIKÇA WRAP_CONTENT layoutParams ver.
  Eşik kullanıcı tarafından canlı kalibre edilir (ayarlarda SeekBar).
- Titreme nedenleri ve çözümleri: (1) ValueAnimator 120 Hz'de zoom isteği yağdırıyordu
  → `ZoomSequencePlayer.emitZoom` ~30 Hz'e seyreltir; (2) zoom sırasında otomatik odak
  "av"a çıkıyordu → çekimde odak+AE+AWB kilidi; (3) sabitleme → `bestStabilizationMode()`
  cihazın desteklediği en iyi modu seçer (önizleme sabitlemesi > EIS > kapalı).
- DİKKAT: CameraX 1.3.4'te `Preview.Builder.setPreviewStabilizationEnabled` ve
  `VideoCapture.Builder.setVideoStabilizationEnabled` YOK (1.4 ile geldi) — derleme
  hatası verir. Sabitleme Camera2 interop `CONTROL_VIDEO_STABILIZATION_MODE` ile yapılır.
- CI log okuma: `get_job_logs` (failed_only, tail_lines=600) — logs_url'i curl ile
  indirme proxy'de engelli.
- UI: buzlu cam (glass) paneller, çipler kodda üretilir (`buildChips`), XML'de boş
  `optionsRow`/`modeRow` konteynerleri var. Tek vurgu rengi `accentIce` (#9BE8FF).
- Ekran yönü serbest (manifest'te screenOrientation YOK, configChanges var);
  dönüşte `onConfigurationChanged` targetRotation + panel yüksekliğini günceller.
- minSdk 26, target/compile 34, CameraX 1.3.4, AGP 8.4.2, JDK 17 (CI).
- İmza: `app/keystore/dronecamera.keystore` (parola/alias: dronecamera) depoda;
  debug+release aynı anahtarla imzalanır ki güncellemeler üzerine kurulabilsin.
  Bilinçli karar (kişisel yan-yükleme uygulaması, Play'e çıkmayacak) — değiştirme.

- Cihaz uygulamalara TEK arka kamera ve 10x tavan veriyor (teşhisle doğrulandı;
  Honor'un 15x'i kendi yazılım büyütmesi). 10x üstü `ZoomSurfaceProcessor` ile
  GPU kırpmasından geliyor: efektif zoom = optik × yazılım kırpması.
- Akıcılığın anahtarı: "Akıcı" motorda optik zoom çekim boyunca SABİT kalır,
  rampanın tamamı kırpmayla yapılır ve değer HER KAREDE hesaplanır
  (`zoomProvider`). Donanımın kademeli zoom adımları böylece hiç devreye girmez.
- Yazılım zoom açıkken kayıt 4K; 1080p'ye kırparken 2 kata kadar kayıpsız.

## Bekleyen fikirler

Timelapse+zoom, zoom sırasında foto serisi, slow-motion, özne takibi (ML Kit).
