# FOX.TV — źródłowy decoder FFmpeg z downmixem

Aplikacja domyślnie używa przypiętego artefaktu
`app/libs/lib-decoder-ffmpeg-release.aar`. Moduł `:ffmpeg-decoder-downmix` jest
kontrolowaną ścieżką odtworzenia tego rozszerzenia z kodu, a nie automatycznym
pobieraniem „latest”.

## Przypięte wejścia

- Media3 API: `1.8.0`;
- FFmpeg: tag `n7.1.5`, commit
  `3a0867c2bfda4a4d4309ca1a8cbdc6175e67f587`;
- Android NDK: `28.2.13676358`;
- Android API/minSdk: `24`;
- ABI: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`;
- liczba workerów FFmpeg: domyślnie `1` (`FOX_FFMPEG_JOBS` może ustawić inną
  dodatnią wartość świadomie).

Obecny prebuilt ma SHA-256
`c88b83a15c1f89dd94006e185520a5c2b2703e58a4938f5a4a975af960584fb1`
i raportuje `Lavc61.3.100`. Nie ma wystarczającego dowodu jego dokładnego
commitu źródłowego. Source-mode używa nowszego, nadal zgodnego w ramach major
`libavcodec 61`, przypiętego maintenance release; nie wolno podmieniać
produkcyjnego AAR bez pełnego porównania API/ABI i testów odtwarzania.

## 1. Przygotowanie źródła

```bash
git clone https://github.com/FFmpeg/FFmpeg.git /absolute/path/to/FFmpeg
git -C /absolute/path/to/FFmpeg checkout --detach 3a0867c2bfda4a4d4309ca1a8cbdc6175e67f587
git -C /absolute/path/to/FFmpeg status --short
```

Ostatnia komenda nie może nic wypisać. Builder odrzuca inny commit i brudny
checkout.

## 2. Zbudowanie statycznych bibliotek dla czterech ABI

Katalog wyjściowy nie może istnieć; skrypt nigdy go nie nadpisuje. Przy błędzie
zostawia oddzielny katalog staging do diagnostyki.

```bash
cd /home/patrrwoj89/Pulpit/fox/PlayTorrioTVKT
FOX_FFMPEG_JOBS=1 \
  ./ffmpeg-decoder-downmix/src/main/jni/build_ffmpeg.sh \
  /absolute/path/to/FFmpeg \
  /absolute/path/to/foxtv-ffmpeg-build \
  /home/patrrwoj89/Android/Sdk/ndk/28.2.13676358
```

Zestaw dekoderów jest stały i wynika z `FfmpegLibrary`: AAC, MP3, AC-3,
E-AC-3, TrueHD, DTS, Vorbis, Opus, AMR-NB/WB, FLAC, ALAC, PCM mu-law/A-law,
H.264 i HEVC. Włączony jest również encoder AC-3 używany przez optical
passthrough/transcode. Skrypt buduje out-of-tree i zapisuje markery rewizji,
API, NDK oraz codec set.

## 3. Włączenie modułu źródłowego

W `local.properties` ustaw:

```properties
USE_LOCAL_FFMPEG_DECODER=true
FFMPEG_SOURCE_DIR=/absolute/path/to/FFmpeg
FFMPEG_BUILD_DIR=/absolute/path/to/foxtv-ffmpeg-build
```

Alternatywnie flagę można podać jako
`-PuseLocalFfmpegDecoder=true` albo `USE_LOCAL_FFMPEG_DECODER=true` w
environment. `settings.gradle.kts` i `app/build.gradle.kts` korzystają z jednej
wyliczonej wartości: moduł jest dołączany tylko w source-mode, a prebuilt AAR
nie trafia wtedy na classpath.

Walidator `verifyFfmpegSourceInputs` sprawdza czysty checkout na dokładnym commicie,
wszystkie markery, wygenerowany `config_components.h`, nagłówki i trzy statyczne
biblioteki dla każdego ABI przed `preBuild`. Builder i standalone audytor wymagają
rzeczywistego włączenia dokładnie 16 decoderów oraz encodera AC-3. CMake ponownie
sprawdza wejścia i linkuje `libffmpegJNI.so` z jawnymi flagami 16 KB, RELRO oraz NOW.

## 4. Wymagane gate’y przed podmianą AAR

1. `:ffmpeg-decoder-downmix:verifyFfmpegSourceInputs`;
2. `:ffmpeg-decoder-downmix:assembleRelease`;
3. kontrola czterech ELF: class/machine, wszystkie `LOAD >= 0x4000`, RELRO,
   `DT_NEEDED`, eksporty JNI i brak nierozwiązanych zależności;
4. porównanie publicznych klas i sześciu rozszerzeń renderer API używanych przez
   player;
5. kompilacja aplikacji wyłącznie ze source-mode;
6. testy player/audio: decode, downmix, center-mix, normalizacja, AC-3 optical,
   fallback;
7. runtime na urządzeniu dla wszystkich faktycznie wspieranych ABI.

Dopóki te gate’y nie przejdą, domyślny prebuilt pozostaje `PIN`, a zbudowany
source AAR jest kandydatem, nie artefaktem produkcyjnym.

## Źródła pierwotne

- [Media3 1.8.0 — CMake decoder_ffmpeg](https://github.com/androidx/media/blob/1.8.0/libraries/decoder_ffmpeg/src/main/jni/CMakeLists.txt)
- [Media3 1.8.0 — instrukcja FFmpeg](https://github.com/androidx/media/blob/1.8.0/libraries/decoder_ffmpeg/README.md)
- [FFmpeg n7.1.5](https://github.com/FFmpeg/FFmpeg/releases/tag/n7.1.5)
- [Android — obsługa stron 16 KB](https://developer.android.com/guide/practices/page-sizes)

Content was rephrased for compliance with licensing restrictions.
