# Local decoder binaries

## FFmpeg decoder

The binary FFmpeg extension was built with the following decoders:

```text
ENABLED_DECODERS=(vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd)
```

See the [Media3 FFmpeg build instructions](https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md).
The historical assembly command was:

```bash
./gradlew :extension-ffmpeg:bundleReleaseAar
```

## IAMF decoder

`lib-decoder-iamf-release.aar` retains the original Media3 1.8.0 Java API,
manifest, metadata, resources, and ProGuard payloads while replacing exactly
the eight `libiamf.so`/`libiamfJNI.so` files for `armeabi-v7a`, `arm64-v8a`,
`x86`, and `x86_64`.

Production AAR SHA-256:
`c6fd3e65f8e4f343ddd6edb1ca4fb6dfa37f669afd5af4ab760995e6e03083d1`.

Pinned native sources:

- AndroidX Media3 `1.8.0`, commit
  `b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9`;
- AOMedia `libiamf` `v1.1.0`, commit
  `f06e919e2ad5502a2adc4bdd4e146f2e7e7ffb63`;
- Android NDK `28.2.13676358` (r28c).

All `LOAD` segments in all eight native libraries are aligned to `0x4000`.
The candidate preserves the original `DT_NEEDED`, `DT_SONAME`, and dynamic
import/export fingerprints. The non-native AAR entries are logically
byte-identical to the original container.

The original AAR was byte-identical across the audited FOX.TV, POCO reference,
and Nuvio snapshot. This establishes shared artifact lineage, but not the exact
historical source commit used for that original binary. The `libiamf v1.1.0`
pin is based on the exact functional export fingerprint and minimum source
delta; it is not presented as cryptographic proof of the historical build.

Rebuild recipe, enforced hashes, independent AAR/ELF verifier, patch, and exact
license/patent texts are maintained in [`tools/native/iamf`](../../tools/native/iamf/README.md).
Static native verification does not replace real IAMF playback testing on a
device.
