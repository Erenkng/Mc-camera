# MC Camera

Kamerayı canlı olarak **Minecraft blok mozaiğine** çeviren Android uygulaması.
Görüntü ızgaraya bölünür, her hücrenin ortalama rengi hesaplanır ve o renge en
yakın bloğun dokusu hücrenin içine basılır — hepsi GPU'da, kare başına.

## Özellikler

### Görüntü
- **Gerçek zamanlı önizleme.** Efekt fotoğraf çekildikten sonra değil, kamera
  açıkken uygulanır. Tüm iş bir OpenGL ES 3.0 fragment shader'ında yapılır.
- **Üç render modu.** *Bloklar* (doku basar), *Harita* (bloğun düz rengini
  basar, Minecraft map art görünümü), *Piksel* (paleti atlar, ham mozaik).
- **Blok yoğunluğu** 8'den 192'ye. Hücreler her zaman kare kalır.
- **Renk karıştırma (dithering).** 4×4 Bayer kernel'i ile komşu hücrelere farklı
  bloklar seçtirir; 64 renklik bir palet gökyüzü gradyanını bantlaşmadan verir.
- **3B kenar gölgesi.** Hücrenin sol-üstünü aydınlatıp sağ-altını koyultarak
  blokları kabartma gibi gösterir.
- **Blok çizgileri**, **ışık uyumu** (blok dokusunu sahnenin parlaklığına göre
  ölçekler), **parlaklık / kontrast / doygunluk** ayarları.

### Çekim
- **Yüksek çözünürlüklü fotoğraf.** Ekran çözünürlüğü yerine mozaiği blok başına
  8 veya 16 pikselle ekran dışı yeniden çizer — 64 bloklu bir ızgara 1024 px
  yerine 2160 px'e kadar çıkar. Uzun kenar 4096 px'te sınırlanır.
- **Video kaydı.** MediaCodec + EGL ile H.264 MP4, 720p veya 1080p. Kare CPU'ya
  hiç inmez: kayıt, fazladan bir çizim çağrısına mal olur.
- **Galeriden fotoğraf** seçip mozaiğe çevirme (EXIF yönü dahil).
- **Zamanlayıcı** (kapalı / 3 sn / 10 sn), **fener**, **ön/arka kamera**,
  **parmakla yakınlaştırma**, **dokunarak odaklama**, **görüntüyü dondurma**.
- Çıktılar `Pictures/MC Camera` ve `Movies/MC Camera` altına kaydedilir.

### Paletler
- **Paket kitaplığı.** Birden fazla resource pack saklanır, ayarlardan tek
  dokunuşla geçiş yapılır, istenmeyen silinir.
- **Palet boyutu** 16'dan 256 bloğa. Az blok daha oyun içi, çok blok daha sadık.
- **Yerleşik paket**: kod içinde üretilen 100+ özgün 16×16 doku (taş, ahşap,
  yün, beton, terracotta, cevherler…). Repoda hiçbir oyun varlığı yok.

## Nasıl çalışıyor

| Aşama | Ne yapıyor |
| --- | --- |
| 1. Kaynak → tampon | CameraX önizlemesi (veya içe aktarılan fotoğraf) döndürülüp kırpılır, renk ayarı uygulanır ve `ızgara × 2ⁿ` boyutlu bir FBO'ya yazılır. |
| 2. Ortalama | FBO'nun mipmap zinciri oluşturulur. `n.` seviyedeki tek bir teksel, bir ızgara hücresinin tam ortalama rengidir — shader'da döngü yok. |
| 3. Blok seçimi | 32×32×32'lik bir renk küpü (2B dokuya serilmiş LUT), her RGB değeri için en yakın bloğun atlas koordinatını ve parlaklığını tutar. |
| 4. Boyama | Hücre içindeki konuma göre bloğun 16×16 dokusu atlastan `GL_NEAREST` ile okunur, gölge/kenar efektleri uygulanır. |

**Renk eşleştirme OKLab'de yapılır.** Düz RGB mesafesi görsel olarak yanlış blok
seçer: yeşiller grileri ezer, koyu tonlar birbirine yapışır. OKLab mesafeleri
gözün "yakın" dediğiyle örtüştüğü için ten tonları, gökyüzü geçişleri ve gölgeler
tanınabilir kalır. Arama, paket yüklenirken CPU'da bir kez yapılır — shader'da
fragment başına yalnızca birkaç doku okuması kalır.

Palet en fazla 256 bloktan oluşur. Yüzlerce doku içeren paketlerde bloklar
*farthest-point* seçimiyle ayıklanır: 300 tonu grinin arasından renk uzayını en
geniş kapsayanlar seçilir.

## Resource pack yükleme

Uygulama **hiçbir Minecraft dokusu içermez**. Gerçek dokular için:

1. Ayarlar → **Resource pack yükle**
2. Bir `.zip` seç (`assets/<paket>/textures/block/` klasörü olan herhangi bir pack)

Yalnızca **kare ve saydam olmayan** dokular kullanılır: cam, yaprak, fidan gibi
delikli dokular mozaikte boşluk bırakacağı için elenir. Animasyonlu dokular hem
kare testine takılır hem de yanlarındaki `.mcmeta` dosyasından tespit edilip
atılır.

## Derleme

```bash
./gradlew testDebugUnitTest   # renk matematiği testleri
./gradlew assembleDebug
```

Gereken: JDK 17, Android SDK 35. Minimum Android 8.0 (API 26), OpenGL ES 3.0.

## CI / Release

- `.github/workflows/build.yml` — her push ve PR'da birim testleri çalıştırır,
  debug APK derler ve artifact olarak yükler.
- `.github/workflows/release.yml` — `v*` tag'i push edildiğinde ya da elle
  çalıştırıldığında (sürüm numarası girilir, tag otomatik oluşur) release APK
  derleyip GitHub Release'e ekler.

```bash
git tag v0.2.0 && git push origin v0.2.0
```

### İmzalama

Varsayılan olarak release APK debug anahtarıyla imzalanır — telefona kurulur ama
Play Store'a yüklenemez. Kendi anahtarınla imzalamak için şu repository
secret'larını ekle:

| Secret | İçerik |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.jks` çıktısı |
| `RELEASE_KEYSTORE_PASSWORD` | keystore parolası |
| `RELEASE_KEY_ALIAS` | anahtar takma adı |
| `RELEASE_KEY_PASSWORD` | anahtar parolası |

Secret varsa workflow otomatik olarak onu kullanır, yoksa debug anahtarına düşer.

## Proje yapısı

```
app/src/main/java/com/erenkng/mccamera/
├── MainActivity.kt              # izinler, kontroller, jestler, yaşam döngüsü
├── camera/CameraController.kt   # CameraX → SurfaceTexture, zoom/odak/fener
├── gl/
│   ├── MosaicRenderer.kt        # iki geçişli render, yakalama, video hedefi
│   ├── Shaders.kt               # GLSL kaynakları
│   └── GlUtils.kt               # program/doku yardımcıları + mat3
├── palette/
│   ├── Oklab.kt                 # algısal renk uzayı (saf Kotlin, test edilir)
│   ├── BlockPalette.kt          # atlas + renk küpü (LUT) + palet seçimi
│   ├── DefaultPack.kt           # kod içinde üretilen yerleşik dokular
│   ├── ResourcePackLoader.kt    # .zip okuma ve filtreleme
│   └── PackLibrary.kt           # paketleri diskte saklama
├── ui/
│   ├── AppSettings.kt           # tüm tercihler tek yerde
│   └── SettingsSheet.kt         # ayarlar alt sayfası
├── util/                        # galeriye kaydetme, fotoğraf yükleme
└── video/
    ├── VideoRecorder.kt         # MediaCodec + MediaMuxer
    └── EncoderSurface.kt        # kodlayıcının EGL pencere yüzeyi
```

## Bilinen sınırlar

- Ses kaydedilmez, bu yüzden mikrofon izni de istenmez.
- Video kare hızı kameranın besleme hızına bağlıdır; kamera takılırsa video da
  takılır.

## Lisans

MIT — bkz. [LICENSE](LICENSE).

Minecraft, Mojang Studios'un tescilli markasıdır. Bu proje Mojang veya Microsoft
ile ilişkili değildir ve oyuna ait hiçbir varlık içermez.
