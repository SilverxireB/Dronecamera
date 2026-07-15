# Drone Kamera 🚁📷

Telefonun zoom özelliğini kullanarak **drone benzeri çekimler** yapan Android kamera uygulaması.

## Nasıl çalışır?

**Drone modu**nda kayıt başladığında kamera **15x** zoom'dan başlar ve seçtiğin süre içinde
(varsayılan **15 saniye**) çok dengeli bir şekilde **0.5x**'e geçiş yaparak video çeker.
Sonuç, bir drone'un özneden uzaklaşarak yükselmesine benzeyen etkileyici bir çekimdir.

Geçişin akıcı görünmesi için zoom **logaritmik uzayda** animasyonlanır: büyütme oranı her an
sabit hızda değişir, böylece geçiş gözle bakıldığında hiç hızlanıp yavaşlamıyormuş gibi
dengeli görünür. Başta ve sonda kısa bir sabit tutuş vardır.

## Özellikler

- 🎬 Tek tuşla drone çekimi: kayıt + otomatik zoom geçişi
- ↔️ Yön seçimi: **Uzaklaşma** (15x → 0.5x) veya **Yaklaşma** (0.5x → 15x)
- ⏱️ Süre seçimi: 10 / 15 / 20 / 30 saniye
- 📊 Canlı zoom göstergesi, geri sayım ve ilerleme çubuğu
- 💾 Videolar `Filmler/DroneCamera` klasörüne MP4 olarak kaydedilir
- 📱 Cihazın desteklediği zoom aralığına otomatik uyum (15x veya 0.5x desteklenmiyorsa
  en yakın değerlere kırpılır)

## APK nasıl alınır?

Her push'ta GitHub Actions otomatik olarak APK derler:

1. GitHub'da **Actions** sekmesine git
2. En son **APK Derle** çalıştırmasına tıkla
3. Sayfanın altındaki **Artifacts** bölümünden `DroneCamera-debug-apk` dosyasını indir
4. Zip'in içindeki `app-debug.apk` dosyasını telefonuna at ve kur
   (bilinmeyen kaynaklardan kuruluma izin vermen gerekir)

## Kendin derlemek istersen

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Gereksinimler: JDK 17, Android SDK 34.

## Teknik detaylar

- **Kotlin** + **CameraX** (Preview + VideoCapture)
- Minimum Android 8.0 (API 26), hedef Android 14 (API 34)
- Zoom animasyonu: `ValueAnimator` + logaritmik interpolasyon
  (`zoom(t) = start · (end/start)^t`)

> Not: 0.5x ultra geniş açıya zoom ile geçiş, telefonun ana kamera üzerinden
> lensler arası geçişi desteklemesine bağlıdır (çoğu modern telefonda desteklenir).
> Uygulama cihazın bildirdiği min/max zoom aralığını otomatik kullanır.
