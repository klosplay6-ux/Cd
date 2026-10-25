# Local AI Coder

Android'de tamamen yerel çalışan GGUF/llama.cpp tabanlı kodlama asistanı (PocketMine-MP plugin analizi odaklı).

## Durum

| Aşama | Durum |
|---|---|
| 1. Proje iskeleti, Material 3 UI, SAF dosya seçici, aktif "+" menüsü, ek kartları | Kaynak yazıldı, **henüz derlenmedi** |
| 2. llama.cpp + JNI + gerçek inference | Bekliyor |
| 3. PHAR/ZIP analiz motoru | Bekliyor |
| 4. Bağlam yönetimi | Bekliyor |
| 5. Testler, export, release APK | Bekliyor |

Not: Mesaj gönderildiğinde model yoksa uygulama bunu açıkça bildirir; sahte yanıt üretmez.

## Derleme

1. Android Studio (Koala veya yeni) ile `LocalAICoder/` klasörünü açın; SDK 34 ve JDK 17 gerekir.
2. Gradle wrapper jar'ı yoksa proje kökünde `gradle wrapper --gradle-version 8.7` çalıştırın.
3. Debug APK: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/`
4. Release APK (Aşama 5'te imzalama eklenecek): `./gradlew assembleRelease`

## Mimari (hedef)

`ui/` · `model/` · `inference/` · `chat/` · `attachments/` · `archive/` · `analysis/` · `storage/` · `settings/`

Şu an mevcut: `ui/chat`, `ui/theme`, `attachments`, `chat`.

## Dosya erişimi

Depolama izni istenmez; tüm dosyalar sistem dosya seçicisi (SAF) ile alınır. Dosyalar hiçbir zaman çalıştırılmaz.
