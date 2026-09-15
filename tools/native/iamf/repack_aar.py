#!/usr/bin/env python3
"""Deterministically replace only the pinned IAMF JNI payloads in an AAR."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path, PurePosixPath
import stat
import tempfile
import zipfile

BASE_AAR_SHA256 = "087a237d730868144d16aceea40a87992699efbff511245e81cdc0c71dbe7c96"
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
LIBRARIES = ("libiamf.so", "libiamfJNI.so")
NATIVE_ENTRIES = frozenset(
    f"jni/{abi}/{library}" for abi in ABIS for library in LIBRARIES
)
FIXED_ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_entry_name(name: str) -> None:
    path = PurePosixPath(name)
    if not name or path.is_absolute() or ".." in path.parts or "\\" in name:
        raise ValueError(f"Unsafe ZIP entry name: {name!r}")


def read_aar(path: Path) -> tuple[list[str], dict[str, bytes]]:
    with zipfile.ZipFile(path, "r") as archive:
        infos = archive.infolist()
        names = [info.filename for info in infos]
        if len(names) != len(set(names)):
            raise ValueError(f"Duplicate ZIP entries in {path}")
        for info in infos:
            validate_entry_name(info.filename)
            mode = info.external_attr >> 16
            if stat.S_ISLNK(mode):
                raise ValueError(f"Symbolic link entry is not allowed: {info.filename}")
        return names, {info.filename: archive.read(info) for info in infos}


def validate_native_entries(names: set[str], source: str) -> None:
    actual = {
        name
        for name in names
        if name.startswith("jni/") and not name.endswith("/")
    }
    if actual != NATIVE_ENTRIES:
        missing = sorted(NATIVE_ENTRIES - actual)
        unexpected = sorted(actual - NATIVE_ENTRIES)
        raise ValueError(
            f"Unexpected native entry set in {source}; "
            f"missing={missing}, unexpected={unexpected}"
        )


def make_zip_info(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, FIXED_ZIP_TIMESTAMP)
    info.create_system = 3
    info.compress_type = zipfile.ZIP_STORED
    info.extra = b""
    info.comment = b""
    info.internal_attr = 0
    if name.endswith("/"):
        info.external_attr = (0o40755 << 16) | 0x10
    else:
        info.external_attr = 0o100644 << 16
    return info


def verify_payloads(
    base_payloads: dict[str, bytes],
    candidate_path: Path,
    staged_payloads: dict[str, bytes],
) -> None:
    candidate_names, candidate_payloads = read_aar(candidate_path)
    if set(candidate_names) != set(base_payloads):
        raise ValueError("Candidate AAR entry set differs from the base AAR")
    validate_native_entries(set(candidate_names), str(candidate_path))

    for name, base_data in base_payloads.items():
        expected = staged_payloads[name] if name in NATIVE_ENTRIES else base_data
        actual = candidate_payloads[name]
        if actual != expected:
            raise ValueError(
                f"Candidate entry differs from its expected logical payload: {name}"
            )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, type=Path, help="Pinned original AAR")
    parser.add_argument(
        "--jni-dir",
        required=True,
        type=Path,
        help="Directory containing <abi>/libiamf*.so",
    )
    parser.add_argument("--output", required=True, type=Path, help="Output AAR")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    base = args.base.resolve()
    jni_dir = args.jni_dir.resolve()
    output = args.output.resolve()

    if not base.is_file():
        raise FileNotFoundError(f"Base AAR does not exist: {base}")
    if base == output:
        raise ValueError("Refusing an in-place repack; use a separate audited output path")
    actual_base_sha256 = sha256_file(base)
    if actual_base_sha256 != BASE_AAR_SHA256:
        raise ValueError(
            "Base AAR SHA-256 mismatch: "
            f"expected {BASE_AAR_SHA256}, got {actual_base_sha256}"
        )

    base_names, base_payloads = read_aar(base)
    validate_native_entries(set(base_names), str(base))

    staged_payloads: dict[str, bytes] = {}
    for entry in sorted(NATIVE_ENTRIES):
        relative = Path(*PurePosixPath(entry).parts[1:])
        source = jni_dir / relative
        if not source.is_file() or source.is_symlink():
            raise FileNotFoundError(f"Missing regular staged ELF: {source}")
        staged_payloads[entry] = source.read_bytes()

    output.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            dir=output.parent,
            prefix=f".{output.name}.",
            suffix=".tmp",
            delete=False,
        ) as temporary:
            temporary_path = Path(temporary.name)

        with zipfile.ZipFile(
            temporary_path,
            mode="w",
            compression=zipfile.ZIP_STORED,
            allowZip64=True,
            strict_timestamps=True,
        ) as archive:
            archive.comment = b""
            for name in sorted(base_names):
                data = staged_payloads.get(name, base_payloads[name])
                if name.endswith("/") and data:
                    raise ValueError(f"Directory entry has a non-empty payload: {name}")
                archive.writestr(make_zip_info(name), data)

        verify_payloads(base_payloads, temporary_path, staged_payloads)
        os.replace(temporary_path, output)
        temporary_path = None
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)

    print(f"base_sha256={actual_base_sha256}")
    for entry in sorted(NATIVE_ENTRIES):
        print(f"{entry}={sha256_bytes(staged_payloads[entry])}")
    print(f"output_sha256={sha256_file(output)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
