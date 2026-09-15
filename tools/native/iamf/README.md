# Reproducible FOX.TV IAMF decoder build

This directory rebuilds only the native IAMF payloads used by
`app/libs/lib-decoder-iamf-release.aar`. It does not modify `app/libs`
automatically. Promotion to the application is a separate, reviewed step.

## Provenance

| Component | Pinned source | License |
| --- | --- | --- |
| Media3 IAMF JNI wrapper | `androidx/media` tag `1.8.0`, commit `b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9` | Apache-2.0 |
| IAMF decoder core | `AOMediaCodec/libiamf` tag `v1.1.0`, commit `f06e919e2ad5502a2adc4bdd4e146f2e7e7ffb63` | BSD-3-Clause-Clear plus AOM patent license |
| Original binary container | Git blob `741d9e3f2b8931623267e36641bedc7b1a15680b`, SHA-256 `087a237d730868144d16aceea40a87992699efbff511245e81cdc0c71dbe7c96` | Mixed as above |

The original AAR is byte-identical in the audited FOX.TV, POCO reference, and
pinned Nuvio snapshot. That proves shared binary-artifact lineage. It does not,
by itself, prove that one complete repository was copied from another or prove
the exact historical source commit used to build the original native objects.

`libiamf` `v1.1.0` is the minimum-delta source pin: its exported functional
symbol fingerprint matches the original AAR. The later audited commit
`a9460ed8e5d98494661308a9dd00c3c57de6e543` adds 13 exports and would be a
functional decoder upgrade, so it is intentionally not used here.

Official sources:

- [AndroidX Media3](https://github.com/androidx/media)
- [AOMedia libiamf](https://github.com/AOMediaCodec/libiamf)
- [Android 16 KB page-size guidance](https://developer.android.com/guide/practices/page-sizes)

## Patch and toolchain

The Media3 1.8.0 wrapper already supplied `-z,max-page-size=16384` to
`iamfJNI`, but not to the linked `iamf` target. NDK r28c also did not produce
uniform 16 KB alignment for the audited 32-bit core builds without explicit
target options. `16kb-linker.patch` therefore applies both
`max-page-size=16384` and `common-page-size=16384` specifically to `iamf` and
`iamfJNI`; it does not alter global linker flags.

The fail-closed build pins:

- Android NDK `28.2.13676358` (r28c), LLVM `19.0.1`
- CMake `3.31.6`
- Ninja `1.12.1`
- Android native API `21`
- `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`
- one native build worker at a time
- `llvm-strip --strip-unneeded`
- fixed `SOURCE_DATE_EPOCH=1731028505`
- path normalization with `-ffile-prefix-map`

Path normalization is required because GNU build-id is calculated before debug
sections are stripped. Without it, otherwise identical release ELFs differed
only in `.note.gnu.build-id` when built from different absolute directories.
Two clean builds from distinct paths produced byte-identical ELFs and AARs
after normalization.

The expected warnings that Opus, FDK-AAC, and FLAC were not found preserve the
minimal historical build shape. Adding those optional libraries would be a
functional change and requires a separate compatibility decision.

## Build

The repacker requires the original AAR as its immutable Java/manifest
container. After the production AAR has been promoted, recover that input from
the preserved baseline commit:

```bash
mkdir -p build/iamf-rebuild/input
git show 20806f2de93554d8e01498e919fa6e6ecc45cc7a:app/libs/lib-decoder-iamf-release.aar \
  > build/iamf-rebuild/input/lib-decoder-iamf-release.original.aar
bash tools/native/iamf/rebuild.sh \
  build/iamf-rebuild/input/lib-decoder-iamf-release.original.aar
```

The script uses sparse, detached source checkouts and refuses to reuse or
delete an existing work directory. Pass unique second and third arguments for
an independent reproduction:

```bash
bash tools/native/iamf/rebuild.sh \
  build/iamf-rebuild/input/lib-decoder-iamf-release.original.aar \
  build/iamf-rebuild/repro-second \
  build/iamf-rebuild/output/lib-decoder-iamf-release.second.aar
```

The canonical output SHA-256 is
`c6fd3e65f8e4f343ddd6edb1ca4fb6dfa37f669afd5af4ab760995e6e03083d1`.
All native hashes are in `SHA256SUMS` and are enforced by `rebuild.sh`.

## Verification gates

`repack_aar.py` accepts only the pinned base SHA-256, validates safe and unique
ZIP entries, replaces exactly eight expected `jni/<abi>/libiamf*.so` payloads,
and writes stable entry order, timestamps, permissions, and storage method.
Every non-native logical payload, including `classes.jar`, remains byte-for-byte
identical to the base.

`verify_aar.py` compares base and candidate and fails on:

- entry-set or non-native payload drift;
- wrong ELF class or machine for an ABI;
- any `LOAD` segment below `0x4000` alignment;
- changed `DT_NEEDED` or `DT_SONAME`;
- changed defined or undefined dynamic-symbol fingerprints.

The resulting native artifact is build- and ABI-verified. Real IAMF decoding on
an Android device remains a separate runtime release gate and must not be
inferred from this static PASS.

## License material

The following files are exact copies from the pinned source checkouts:

- `LICENSE.androidx-media` — Apache-2.0
- `LICENSE.libiamf` — BSD-3-Clause-Clear
- `PATENTS.libiamf` — Alliance for Open Media Patent License 1.0

The pinned Media3 checkout contains no root `NOTICE` file. Copyright headers in
the JNI source remain in the upstream checkout and patch context.
