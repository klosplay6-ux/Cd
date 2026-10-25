# Local AI Coder

Android'de tamamen yerel çalışan GGUF/llama.cpp tabanlı kodlama asistanı (PocketMine-MP plugin analizi odaklı).

## Durum

| Aşama | Durum |
|---|---|
| 1. Proje iskeleti, Material 3 UI, SAF dosya seçici, aktif "+" menüsü, ek kartları | Tamamlandı |
| 2. llama.cpp + JNI + gerçek inference | Uygulandı; cihazda doğrulama gerekiyor |
| 3. PHAR/ZIP analiz motoru | PHAR tar analizi ve güvenli prompt bağlamı uygulandı; ZIP sertleştirmesi devam ediyor |
| 4. Bağlam yönetimi | Bekliyor |
| 5. Testler, export, release APK | Bekliyor |

Not: Uygulama artık resmi llama.cpp Android binding yaklaşımıyla gerçek GGUF yükleme ve token akışlı yerel inference kullanır. Model yoksa sahte yanıt üretmez. Yanıt üretimi sırasında gönderme düğmesi durdurma düğmesine dönüşür ve kısmi yanıt korunur.

## Derleme

1. Depoyu alt modüllerle birlikte klonlayın: `git clone --recurse-submodules <repo-url>`; sonradan aldıysanız `git submodule update --init --recursive` çalıştırın.
2. Android Studio (Koala veya yeni) ile depo kökünü açın; SDK 34, NDK `26.3.11579264`, CMake `3.22.1` ve JDK 17 gerekir.
3. Gradle wrapper jar'ı yoksa proje kökünde `gradle wrapper --gradle-version 8.7` çalıştırın.
4. Debug APK: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/`
5. Release APK (imzalama yapılandırması eklenene kadar unsigned): `./gradlew assembleRelease`

## Mimari (hedef)

`ui/` · `model/` · `inference/` · `chat/` · `attachments/` · `archive/` · `analysis/` · `storage/` · `settings/`

Şu an mevcut: `ui/chat`, `ui/theme`, `attachments`, `chat`, `inference`, `app/src/main/cpp`.

## GGUF kullanımı

Sohbet ekranındaki `+` menüsünden **GGUF model ekle** seçilir. Dosya SAF üzerinden okunur, `.gguf` uzantısı ve `GGUF` magic değeri doğrulanır, ardından uygulamanın özel `filesDir/models` dizinine kopyalanır. Model, llama.cpp JNI katmanı ile yüklenir; desteklenmeyen mimari veya bozuk model kullanıcıya hata olarak gösterilir. Yanıtlar native inference motorundan token token akışla gelir.

llama.cpp, MIT lisanslı resmi Git alt modülü olarak sabit bir commit’e pinlenmiştir. Android arm64-v8a build’i için OpenMP, OpenSSL ve llamafile kapalıdır; CPU backend’i cihazın desteklediği özelliklere göre çalışır.

PHAR ekleri çalıştırılmadan PHP'nin native manifest biçimi veya tar tabanlı arşiv olarak okunur. Dosya ağacı ve güvenli metin kaynakları (PHP, `plugin.yml`, YAML/JSON ve benzeri) model bağlamına eklenir; mutlak yollar, `..` içeren yollar, aşırı boyut ve aşırı giriş sayısı reddedilir. Native PHAR içindeki zlib sıkıştırmalı dosyalar açılır; bzip2 girdileri desteklenmiyorsa açıkça reddedilir.

## Dosya erişimi

Depolama izni istenmez; tüm dosyalar sistem dosya seçicisi (SAF) ile alınır. Dosyalar hiçbir zaman çalıştırılmaz.
