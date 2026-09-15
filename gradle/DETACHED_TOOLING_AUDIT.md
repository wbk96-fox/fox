# Detached Gradle tooling audit

Audit date: 2026-09-13  
Scope: the 15 exact components and 28 artifacts in `detached-tooling-manifest.tsv` which are selected by AGP/KSP/lint tooling outside the committed named-configuration lock states.

## Contract and measured coverage

- Version selection: **15/15 manifest-pinned exact coordinates**; no ranges, `latest`, `+` or `SNAPSHOT`.
- Byte integrity: **28/28 artifacts have exactly one SHA-256** in `verification-metadata.xml`.
- Local-byte check: **28/28 cached files matched** their committed SHA-256 on 2026-09-13.
- Repository distribution check: **28/28 artifact URLs were available** from the configured official distribution repository (Google Maven or Maven Central) on 2026-09-13.
- Gradle lock state: **0/15 components are lockfile-covered**. This is intentional and must not be described as lock coverage: the exact-coordinate manifest supplies the selection pin and strict dependency verification supplies byte integrity.
- CI consumer: **15/15 and 28/28** are resolved by `verifyDependencySupplyChain` in both PR and non-dry-run release workflows.
- Android runtime: **0/15** are APK/runtime dependencies through this audit view; all entries are Gradle/JVM build tooling.
- Exact build provenance: **0/15 cryptographically attested**. Official repository origin, coordinates and bytes are verified, but an independently reproducible source-commit-to-published-byte attestation was not established. This is a documented `PROVENANCE_GAP`, not a checksum or version-selection gap.

The fail-hard audit result after the final restoration was:

```text
4 projects / 222 resolvable configurations / 568 unique external artifact files
15/15 detached components selected / 28/28 detached artifacts SHA-256 verified
```

## Component matrix

Common values unless overridden below:

- verification: `MANIFEST_PINNED + SHA256_MATCH`
- lock status: `NOT_LOCK_COVERED`
- CI usage: `PR + RELEASE verifyDependencySupplyChain`
- runtime usage: `BUILD_TOOLING_ONLY; NOT_APK_RUNTIME`
- provenance status: `PARTIAL / PROVENANCE_GAP` — official distribution endpoint verified; exact cryptographic build attestation absent
- decision: `PIN`

| Name | Source | Manifest version | Resolved version | Artifact SHA-256 (artifact = hash) | Verification | Lock | CI / runtime | Provenance | Decision |
|---|---|---:|---:|---|---|---|---|---|---|
| `com.android.tools.build:aapt2` | Google Maven / Android build tools | `8.13.2-14304508` | `8.13.2-14304508` | `aapt2-8.13.2-14304508-linux.jar` = `839609d6d776d6dd60a02aa577d97193ce3e650cf1deaabf062321e23bbd6bf6`<br>`aapt2-8.13.2-14304508.pom` = `7e085634f503d42a4f3ea14488c25d4a573914db23c64a5a05a0c5c186c93112` | PASS | NO | CI / build-only | PARTIAL / GAP | PIN |
| `com.google.devtools.ksp:symbol-processing-aa-embeddable` | Maven Central / official `google/ksp` release | `2.3.12` | `2.3.12` | JAR = `862f0042fc2467cdcf7752e15880f97396a29e2469741dc6453f37584f1b4c69`<br>POM = `e15b1eb427a85eeb5aad9941bf0d465e249cb260dbfb80e6b424215f26817dc2` | PASS | NO | CI / build-only | PARTIAL / GAP | PIN |
| `com.android.tools:play-sdk-proto` | Google Maven / Android tools | `31.13.2` | `31.13.2` | JAR = `c6fc15a5c203064cfd2c8a176fdeac72ae0a2d743ec47a2e66a0238d8d870b6b`<br>POM = `6e345e9ecc22ad3ff37ec679dd0a2f45cdcb7d6fe6aec3247b002c89b36a5461` | PASS | NO | CI / build-only | PARTIAL / GAP | PIN |
| `com.android.tools.external.com-intellij:intellij-core` | Google Maven / repackaged IntelliJ tooling | `31.13.2` | `31.13.2` | JAR = `9a6faa6061d0f3d54a64decb61944c1b2c6927f8d325cd298c82c2a8d867ee68`<br>POM = `21f7df870290113ee81b1c5a373c9cc99a3c252a3fbc76737c08532e5e910e51` | PASS | NO | CI / build-only | PARTIAL / GAP | PIN |
| `com.android.tools.external.com-intellij:kotlin-compiler` | Google Maven / repackaged Kotlin compiler tooling | `31.13.2` | `31.13.2` | JAR = `552dfaffe295d08504870816c27fc09007e1231fb9b14c1ff9bbf861f9b35990`<br>POM = `2b2294ed4c07646b7ca29a362e04e5fbe489c5b3cf1d2bc4620a5c317666435f` | PASS | NO | CI / build-only | PARTIAL / GAP | PIN |
| `com.android.tools.external.org-jetbrains:uast` | Google Maven / repackaged JetBrains UAST | `31.13.2` | `31.13.2` | JAR = `78f18ac2b2509fb6cb19058e8fc9585c361b97990dd7d5db0c2a94744dfb0a96`<br>POM = `5b5c6c6b71f9815ee531560946bf579b7b9fa806aa9dfbf1b567ed6aa598ee18` | PASS | NO | CI / build-only | PARTIAL / GAP | PIN |
| `com.android.tools.lint:lint` | Google Maven / Android lint | `31.13.2` | `31.13.2` | JAR = `7f875a980ee23916439d368d073cfbc2ee4e4d99ffe1b3e13da794fef347f29a`<br>POM = `c8a5bb4a6ffbb17c07ff72e52c446e0271fd2b3e16c08be37207fd77008ae93e` | PASS | NO | CI / build-only | PARTIAL / GAP | PIN |
| `com.android.tools.lint:lint-api` | Google Maven / Android lint | `31.13.2` | `31.13.2` | JAR = `8f770657dba33f305e583c62953a4f174c75a7b1cd2da7d31134be36a96ae2ac`<br>POM = `664d7824766a943679cc031f516bd94843a8ba99cfd8ed0c33e68e8fbcc75407` | PASS | NO | CI / build-only | PARTIAL / GAP | PIN |
| `com.android.tools.lint:lint-checks` | Google Maven / Android lint | `31.13.2` | `31.13.2` | JAR = `3b64f395ae17fcea104882b00a4acdc7dc691f5daca5dff2bdde89fa052bb199`<br>POM = `6298a80b18b08ff03b3c939424df874c68f449c1591cfa69680e93e3e516e6a2` | PASS | NO | CI / build-only | PARTIAL / GAP | PIN |
| `com.android.tools.lint:lint-gradle` | Google Maven / Android lint | `31.13.2` | `31.13.2` | JAR = `a42b6a41c436d90ca31a13d67afba1157b157efc892a7496f67432bf8a831cbd`<br>POM = `cbd02ebf01bc37b150ad7ace3f2d533c8ebb0b490e8584e09c9bc4152ad85e66` | PASS | NO | CI / build-only | PARTIAL / GAP | PIN |
| `commons-codec:commons-codec` | Maven Central / Apache Commons Codec | `1.10` | `1.10` | JAR = `4241dfa94e711d435f29a4604a3e2de5c4aa3c165e23bd066be6fc1fc4309569`<br>POM = `bdb8db7012d112a6e3ea8fdb7c510b300d99eff0819d27dddba9c43397ea4cfb` | PASS | NO | CI / build-only | PARTIAL / GAP | PIN |
| `org.apache:apache` | Maven Central / Apache parent POM | `15` | `15` | POM = `36c2f2f979ac67b450c0cb480e4e9baf6b40f3a681f22ba9692287d1139ad494` | PASS | NO | CI / build-only | PARTIAL / GAP | PIN |
| `org.apache.commons:commons-parent` | Maven Central / Apache Commons parent POM | `35` | `35` | POM = `7098a1ab8336ecd4c9dc21cbbcac869f82c66f64b8ac4f7988d41b4fcb44e49a` | PASS | NO | CI / build-only | PARTIAL / GAP | PIN |
| `org.apache.httpcomponents:httpclient` | Maven Central / Apache HttpComponents | `4.5.6` | `4.5.6` | JAR = `c03f813195e7a80e3608d0ddd8da80b21696a4c92a6a2298865bf149071551c7`<br>POM = `7efc1241e73e7fbb268bfd33242d11ebd3ca07061d7d85f2962dc32a0f0b8855` | PASS | NO | CI / build-only | PARTIAL / GAP | PIN |
| `org.codehaus.groovy:groovy` | Maven Central / Apache Groovy | `3.0.22` | `3.0.22` | JAR = `c92c92c4b9b183f9981ba7399f36592e5e3ad6f4cdac7101b5a22cc17998d13f`<br>POM = `51b731e5cff121efd6d320f4a801cd616869c5bed4e9c0e353b29060b1d2355f` | PASS | NO | CI / build-only | PARTIAL / GAP | PIN |

`PASS` in this matrix means only that the selected coordinate equals the manifest pin and the locally materialized bytes equal the one committed SHA-256. It does not upgrade `NO` lock coverage or `PARTIAL / GAP` provenance to PASS.

## Upgrade research and decision

Local stack: AGP 8.13.2, Gradle 8.13, Kotlin 2.3.0, KSP 2.3.12, Chaquopy 17.0.0.

| Component | Local | Current upstream observed 2026-09-13 | Compatibility / relevance | Decision |
|---|---:|---:|---|---|
| AGP | 8.13.2 | 9.4.0 stable; 9.5.0-alpha05 preview | AGP 9.4 requires Gradle 9.6. Chaquopy 17.0 supports only through AGP 9.2. AGP 9 enables built-in Kotlin and requires project migration. No 9.2–9.4 release-note fix was found for `androidApis`, detached tooling or locking. | KEEP 8.13.2 for this task; evaluate AGP 9.x as a separate migration only after plugin compatibility is complete. |
| Gradle | 8.13 | 9.x available | Gradle 9.6 documentation still scopes locks to resolvable configurations. Detached configurations remain an open ecosystem limitation and may be created too late for normal analysis. | KEEP 8.13; manifest + SHA audit remains necessary. |
| KSP | 2.3.12 | 2.3.12 | Already latest. This release fixes Android KMP/Lint wiring and sets minimum AGP 8.12, but does not remove its standalone tooling artifact or provide detached lock state. | KEEP 2.3.12. |
| Kotlin | 2.3.0 | 2.4.20 | KSP 2.3 is version-independent, but a compiler update does not alter Gradle lock semantics and has a broad source/compiler regression surface. | Defer to the final upstream compatibility task; not a task #3 fix. |
| Chaquopy | 17.0.0 | 17.0.0 | Supports AGP 7.3–9.2 only; AGP 9.3/9.4 is outside the published matrix. | PIN; blocks AGP >9.2. |

### Sources

- [AGP 9.4 release and compatibility](https://developer.android.com/build/releases/gradle-plugin)
- [AGP 9.2 release and Gradle requirement](https://developer.android.com/build/releases/agp-9-2-0-release-notes)
- [AGP 9 built-in Kotlin migration](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Chaquopy version compatibility](https://chaquo.com/chaquopy/doc/current/versions.html)
- [KSP 2.3.12 release](https://github.com/google/ksp/releases/tag/2.3.12)
- [KSP 2.3.0 version decoupling](https://github.com/google/ksp/releases/tag/2.3.0)
- [Gradle 9.6 dependency locking](https://docs.gradle.org/9.6.0/userguide/dependency_locking.html)
- [Open Gradle detached-configuration limitation](https://github.com/gradle/gradle/issues/29489)
- [Open Gradle detached dependency-verification diagnostics issue](https://github.com/gradle/gradle/issues/29595)
- [Official AGP Maven metadata](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml)
- [Official KSP Maven metadata](https://repo1.maven.org/maven2/com/google/devtools/ksp/symbol-processing-gradle-plugin/maven-metadata.xml)
- [Official Kotlin Gradle plugin metadata](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml)

External-source content is paraphrased for licensing compliance.
