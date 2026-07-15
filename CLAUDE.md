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
  düşer; çözüm: başlangıç zoom'u eşik altına çekilebilir (2x–8x çipleri, varsayılan 3x).
- UI: buzlu cam (glass) paneller, çipler kodda üretilir (`buildChips`), XML'de boş
  `optionsRow`/`modeRow` konteynerleri var. Tek vurgu rengi `accentIce` (#9BE8FF).
- minSdk 26, target/compile 34, CameraX 1.3.4, AGP 8.4.2, JDK 17 (CI).

## Bekleyen fikirler

Timelapse+zoom, zoom sırasında foto serisi, slow-motion, özne takibi (ML Kit).
