# FOX.TV — Source-of-Truth Inventory (ETAP 3A / 9A.1)

- DATA: 2026-09-14 · BRANCH: `foxtv/enterprise-impl` · BAZA: `20806f2` (v1.1.1)
- NARZĘDZIA: `find`/`grep`/`git status` (read-only); zero reset/stash/checkout; zero POCO.
- NIE liczymy „91k plików jako źródeł”: poniżej rzeczywista granica źródeł vs artefaktów.

## 1. Moduły Gradle (settings.gradle.kts, wiersze 67–70)

| Moduł | Warunek | Rola |
|---|---|---|
| `:app` | zawsze | jedyna aplikacja (single FULL release; flava playstore/full usunięte) |
| `:baselineprofile` | zawsze | benchmarki startu (instrumentacja) |
| `:ffmpeg-decoder-downmix` | `includeFfmpegDecoderProject` (flaga) | source-mode budowa FFmpeg `n7.1.5`; tryb audit-only wymuszony bramką supply chain |

Included builds: **brak**. `buildSrc`: **brak**. Repozytoria: google/mavenCentral/jitpack (`FAIL_ON_PROJECT_REPOS`).

## 2. Drzewo projektu — klasyfikacja (część 1: źródła)

| Path | Classification | production_reachable | build_generated | Reason / evidence |
|---|---|---|---|---|
| `app/src/main/java` (957 plików) | SOURCE (+VENDORED_SOURCE w pakietach obcych) | TAK | NIE | jedyne źródło APK; `com/foxtv/app/**` = produkcja FOX; `com/lagradost/cloudstream3/**` = vendored Cloudstream v4.7.0 (nazwy/klasy MUST-KEEP); `eu/kanade/tachiyomi/**` = minimalny host Aniyomi (Apache-2.0, licencje w `app/src/main/assets/licenses/`); `uy/kohesive/injekt/**` = vendored Injekt (MIT, pin `65b04400cf379aa952dce00443f57b81f9a936ea`) |
| `app/src/test/java` (219) | TEST_SOURCE | NIE | NIE | JVM suite (świeża bramka: 1344/0/0/1) |
| `app/src/androidTest/java` (9) | TEST_SOURCE (instrumentacja) | NIE | NIE | w tym live gate `foxtvLiveAnime`; poza `check`/unit/release |
| `app/src/main/python` (29) | SOURCE (Python/Chaquopy) | TAK | NIE | most `foxtv_fanfilm` + vendored `pyqrcode` (BSD); Chaquopy 17.0.0 + Python 3.11 (pin z powodu armeabi-v7a urządzenia) |
| `app/src/main/cpp` (2) | SOURCE (JNI) | TAK | NIE | `dovi_bridge.cpp` + `CMakeLists.txt`; prefiks `Java_com_foxtv_app...`; CMake fail-closed |
| `DV7/` (8) | NATIVE_ARTIFACT | TAK (link-time) | NIE (prebuilt z dowodem `PHASE2_*`) | statyczne `libdovi.a` ×4 ABI + nagłówki |
| `app/libs/*.aar` (12 + README) | LOCAL_DEPENDENCY | TAK | NIE (prebuilt) | fork Media3 ×10 (common/exoplayer/exoplayer-hls/extractor/datasource/datasource-okhttp/decoder-av1/decoder-ffmpeg/decoder-iamf/decoder-mpegh), `nextlib-mediainfo-local`, `quickjs-kt-android-1.0.5-playtorrio`; inventory `tools/native/NATIVE_ARTIFACTS.lock.json` |
| `app/src/main/jniLibs/{4 ABI}/libtorrserver.so` | NATIVE_ARTIFACT | TAK | NIE | statyczne Go executables TorrServer `MatriX.141` (rewizja źródła potwierdzona; audyt linkage=static) |
| `app/src/main/assets` (1330) | VENDORED_SOURCE + licencje | TAK | NIE | `fanfilm/` 5 addonów + `MANIFEST.json` (sha256); `licenses/` |
| `app/src/main/res` | SOURCE (zasoby) | TAK | NIE | 94/94 plików FOX.TV potwierdzone generatorem |
| `app/src/main/generated` (0) | GENERATED | NIE | TAK (pusty) | brak zawartości |
| `ffmpeg-decoder-downmix/` (281) | SOURCE + TOOLING | warunkowo | NIE | builder FFmpeg; audit-only `preBuild` fail-closed |
| `baselineprofile/` (3) | TEST_SOURCE | NIE | NIE | start-up profiling |

## 2b. Drzewo projektu — klasyfikacja (część 2: tooling, dokumentacja, artefakty)

| Path | Classification | production_reachable | build_generated | Reason / evidence |
|---|---|---|---|---|
| `app/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` | TOOLING (build scripts) | TAK (konfiguracja) | NIE | strict supply chain, runner androidTest, splits ABI |
| `gradle/` (wrapper, `libs.versions.toml`, `verification-metadata.xml`, `detached-tooling-manifest.tsv`, `DETACHED_TOOLING_AUDIT.md`) | TOOLING + DOCUMENTATION | TAK (konfiguracja) | NIE | katalog 56 versions / 93 libraries / 11 plugins; strict verification |
| `tools/` (14) | TOOLING | NIE | NIE | `native/audit_native_artifacts.py`, `NATIVE_ARTIFACTS.lock.json`, runbooki |
| `scripts/` (19) | TOOLING | NIE | NIE | `vendor_fanfilm_addons.py`, `device_runtime_check.sh`, generator brandingu FOX.TV |
| `docs/` | DOCUMENTATION | NIE | NIE | utworzone w etapie 3A (ten inwentarz + macierze źródeł) |
| `semantic-review/` (11) | DOCUMENTATION | NIE | NIE | raporty przeglądów (najnowszy: OPEN P1=0, OPEN P2=0) |
| `.kiro/` | DOCUMENTATION / stan | NIE | NIE | `FOX_IMPLEMENTATION_STATE.md`, specs, research fixtures (pin Aniyomi APK) |
| `AGENTS.md`, `PROJECT_ARCHITECTURE.md`, `README.md`, `CONTRIBUTING.md`, `LICENSE`, `release_test_cases.csv` | DOCUMENTATION | NIE | NIE | master prompt, architektura, atrybucje |
| `FOX_TV.png`, `icon.png` | DOCUMENTATION (brand root) | NIE | NIE | assety dokumentacyjne repo |
| `compose_stability_config.conf` | TOOLING | TAK (kompilacja) | NIE | Compose stability |
| `local.properties`, `local.example.properties` | LOCAL (prywatna konfiguracja) | NIE | NIE | sekrety/piny — NIE USUWAĆ |
| `build/`, `.gradle/`, `app/build/` | BUILD_ARTIFACT / CACHE | NIE | TAK | ignorowane; nie są źródłem prawdy |
| `.github/workflows/*` | TOOLING (CI) | NIE | NIE | wrapper-validation, native audit, release pipelines |
| `assets/brand/*.png` (2) | **UNKNOWN — BLOKUJE** | NIE (brak konsumentów) | NIE | patrz sekcja 3 |
| `org/libtorrent4j/swig/byte_vector.class` (1) | **UNKNOWN — BLOKUJE** | NIE (brak referencji) | TAK (`.class`) | patrz sekcja 3 |

## 3. UNKNOWN — decyzje wymagane (blokujące)

### UNKNOWN-1: `org/libtorrent4j/swig/byte_vector.class`
- path: `org/libtorrent4j/swig/byte_vector.class`
- symbol: `org.libtorrent4j.swig.byte_vector` (skompilowana klasa Java)
- reason: samotny `.class` w korzeniu repo, poza jakimkolwiek source root; `grep -rln libtorrent4j` po `app/src/main/java` i skryptach Gradle = 0 trafień; śledzony w git (czysty)
- blocking evidence: `.class` jest wyjściem kompilatora i nigdy nie trafia do APK (źródła APK: wyłącznie `app/src/main/java` + AAR/`jniLibs`/assets)
- decyzja do podjęcia: **REMOVE (kandydat silny)** lub DOCUMENTATION przy dowodzie pochodzenia; NIE jest produkcyjny

### UNKNOWN-2: `assets/brand/app_logo_mark.png`, `assets/brand/app_logo_wordmark.png`
- path: `assets/brand/`
- symbol: 2×PNG brandu FOX.TV w korzeniu repo
- reason: brak referencji w `scripts/`, `tools/`, `app/` (grep 0 trafień); produkcyjny branding istnieje w `app/src/main/res` (94/94 potwierdzone)
- blocking evidence: brak konsumentów; możliwe role: INPUT generatora brandingu albo martwa kopia
- decyzja do podjęcia: **TOOLING_INPUT (udokumentować)** albo **REMOVE**; NIE jest produkcyjny

## 4. Obszary źródeł — policzone

| Obszar | Pliki | Uwagi |
|---|---|---|
| Kotlin main | 957 | FOX + vendored Cloudstream/Aniyomi-host/Injekt |
| Kotlin test (JVM) | 219 | w tym AniNekoContract 7, Transport 5, Aggregation 9 |
| Kotlin androidTest | 9 | instrumentacja; poza `check` |
| Python | 29 | most FanFilm + pyqrcode |
| C/C++ (app) | 2 | dovi_bridge + CMake |
| Native prebuilt | 12 AAR + 4 `.so` + 4 `libdovi.a` | audyt `PASS_WITH_FINDINGS` 0 errors |
| Assets | 1330 | FanFilm vendored + licenses |
| FFmpeg moduł | 281 | warunkowy, source-mode |
| Tooling | 33 | scripts + tools |
