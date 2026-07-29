# MC Camera

Kamerayı canlı olarak **Minecraft blok mozaiğine** çeviren Android uygulaması.
Görüntü ızgaraya bölünür, her hücrenin ortalama rengi hesaplanır ve o renge en
yakın bloğun dokusu hücrenin içine basılır — hepsi GPU'da, kare başına.

<p align="center">
  <em>Kamera → 512 px ara tampon → mipmap ile hücre ortalaması → renk küpünden blok seçimi → atlas dokusu</em>
</p>

## Özellikler

- **Gerçek zamanlı önizleme.** Efekt fotoğraf çekildikten sonra değil, kamera
  açıkken uygulanır. Tüm iş bir OpenGL ES 3.0 fragment shader'ında yapılır.
- **Seçilebilir blok yoğunluğu.** 16 / 24 / 32 / 48 / 64 / 96 / 128 blok
  (ekran genişliğine düşen blok sayısı). Hücreler her zaman kare kalır.
- **Kendi resource pack'in.** Ayarlardan bir `.zip` seç, uygulama içindeki blok
  dokularını okuyup paleti yeniden kurar. Seçim cihazda saklanır.
- **Işık uyumu.** Blok dokusunu sahnenin parlaklığına göre koyultup açar, böylece
  gölgeler kaybolmaz. Kapatınca saf doku renkleri kullanılır.
- **Ön/arka kamera**, tek dokunuşla fotoğraf çekme, `Pictures/MC Camera`
  klasörüne PNG kaydetme.

## Nasıl çalışıyor

| Aşama | Ne yapıyor |
| --- | --- |
| 1. Kamera → tampon | CameraX önizlemesi harici bir OES dokusuna gelir, ilk geçiş bunu döndürüp kırparak `ızgara × 2ⁿ` boyutlu bir FBO'ya yazar. |
| 2. Ortalama | FBO'nun mipmap zinciri oluşturulur. `n.` seviyedeki tek bir teksel, bir ızgara hücresinin tam ortalama rengidir — döngü yok. |
| 3. Blok seçimi | 32×32×32'lik bir renk küpü (2B dokuya serilmiş LUT), her RGB değeri için en yakın bloğun atlas koordinatını ve parlaklığını tutar. Arama CPU'da paket yüklenirken bir kez yapılır. |
| 4. Boyama | Hücre içindeki konuma göre bloğun 16×16 dokusu atlastan `GL_NEAREST` ile okunur. |

Palet en fazla 64 bloktan oluşur. Yüzlerce doku içeren paketlerde bloklar
*farthest-point* seçimiyle ayıklanır, yani 300 tonu grinin arasından renk uzayını
en geniş kapsayan 64 tanesi seçilir.

## Resource pack yükleme

Uygulama **hiçbir Minecraft dokusu içermez**; yerleşik palet kod içinde
üretilen özgün 16×16 dokulardan oluşur. Gerçek dokular için:

1. Ayarlar → **Resource pack yükle**
2. Bir `.zip` seç (`assets/minecraft/textures/block/` klasörü olan herhangi bir paket)

Yalnızca **kare ve saydam olmayan** dokular kullanılır: cam, yaprak, fidan gibi
delikli dokular mozaikte boşluk bırakacağı için elenir; animasyonlu dokular da
uzun şerit halinde saklandıkları için kare testine takılır.

## Derleme

```bash
./gradlew assembleDebug
```

Gereken: JDK 17, Android SDK 35. Minimum Android 8.0 (API 26), OpenGL ES 3.0.

## CI / Release

- `.github/workflows/build.yml` — her push ve PR'da debug APK derler, artifact
  olarak yükler ve lint çalıştırır.
- `.github/workflows/release.yml` — `v*` biçiminde bir tag push edildiğinde
  release APK derleyip GitHub Release'e ekler.

```bash
git tag v0.1.0 && git push origin v0.1.0
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
├── MainActivity.kt              # izinler, kontroller, yaşam döngüsü
├── camera/CameraController.kt   # CameraX → SurfaceTexture
├── gl/
│   ├── MosaicRenderer.kt        # iki geçişli render, kare yakalama
│   ├── Shaders.kt               # GLSL kaynakları
│   └── GlUtils.kt               # program/doku yardımcıları + mat3
├── palette/
│   ├── BlockPalette.kt          # atlas + renk küpü (LUT) üretimi
│   ├── DefaultPack.kt           # kod içinde üretilen yerleşik dokular
│   ├── ResourcePackLoader.kt    # .zip okuma ve filtreleme
│   └── PaletteStore.kt          # paleti diskte saklama
├── ui/SettingsSheet.kt          # ayarlar alt sayfası
└── util/ImageSaver.kt           # MediaStore'a kaydetme
```

## Lisans

MIT — bkz. [LICENSE](LICENSE).

Minecraft, Mojang Studios'un tescilli markasıdır. Bu proje Mojang veya Microsoft
ile ilişkili değildir ve oyuna ait hiçbir varlık içermez.
