#!/usr/bin/env python3
"""Audit IAMF AAR payload identity, ABI, ELF alignment, and dynamic ABI."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import tempfile
import zipfile

ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
LIBRARIES = ("libiamf.so", "libiamfJNI.so")
NATIVE_ENTRIES = frozenset(
    f"jni/{abi}/{library}" for abi in ABIS for library in LIBRARIES
)
EXPECTED_ELF = {
    "arm64-v8a": ("ELF64", "AArch64"),
    "armeabi-v7a": ("ELF32", "ARM"),
    "x86": ("ELF32", "Intel 80386"),
    "x86_64": ("ELF64", "Advanced Micro Devices X86-64"),
}
MINIMUM_LOAD_ALIGNMENT = 0x4000


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_aar(path: Path) -> tuple[list[str], dict[str, bytes]]:
    with zipfile.ZipFile(path, "r") as archive:
        infos = archive.infolist()
        names = [info.filename for info in infos]
        if len(names) != len(set(names)):
            raise ValueError(f"Duplicate ZIP entries in {path}")
        for info in infos:
            name = info.filename
            pure_path = PurePosixPath(name)
            if (
                not name
                or pure_path.is_absolute()
                or ".." in pure_path.parts
                or "\\" in name
            ):
                raise ValueError(f"Unsafe ZIP entry name in {path}: {name!r}")
            if stat.S_ISLNK(info.external_attr >> 16):
                raise ValueError(f"Symbolic link entry in {path}: {name}")
        payloads = {info.filename: archive.read(info) for info in infos}
    actual_native = {
        name
        for name in names
        if name.startswith("jni/") and not name.endswith("/")
    }
    if actual_native != NATIVE_ENTRIES:
        raise ValueError(
            f"Unexpected native entry set in {path}: "
            f"missing={sorted(NATIVE_ENTRIES - actual_native)}, "
            f"unexpected={sorted(actual_native - NATIVE_ENTRIES)}"
        )
    return names, payloads


def run_readelf(readelf: Path, *arguments: str, elf: Path) -> str:
    completed = subprocess.run(
        [str(readelf), *arguments, str(elf)],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return completed.stdout


def parse_header(readelf: Path, elf: Path) -> tuple[str, str]:
    output = run_readelf(readelf, "-hW", elf=elf)
    elf_class_match = re.search(
        r"^[ \t]*Class:[ \t]*(\S+)[ \t]*$", output, re.MULTILINE
    )
    machine_match = re.search(
        r"^[ \t]*Machine:[ \t]*(.+?)[ \t]*$", output, re.MULTILINE
    )
    if elf_class_match is None or machine_match is None:
        raise ValueError(f"Unable to parse ELF header: {elf}")
    return elf_class_match.group(1), machine_match.group(1)


def parse_load_alignments(readelf: Path, elf: Path) -> tuple[int, ...]:
    output = run_readelf(readelf, "-lW", elf=elf)
    alignments: list[int] = []
    for line in output.splitlines():
        fields = line.split()
        if fields and fields[0] == "LOAD":
            alignments.append(int(fields[-1], 0))
    if not alignments:
        raise ValueError(f"ELF contains no LOAD segments: {elf}")
    return tuple(alignments)


def parse_dynamic(readelf: Path, elf: Path) -> tuple[tuple[str, ...], str | None]:
    output = run_readelf(readelf, "-dW", elf=elf)
    needed = tuple(
        match.group(1)
        for match in re.finditer(r"\(NEEDED\).*?\[(.+?)\]", output)
    )
    soname_match = re.search(r"\(SONAME\).*?\[(.+?)\]", output)
    return needed, soname_match.group(1) if soname_match else None


def parse_dynamic_symbols(
    readelf: Path, elf: Path
) -> tuple[frozenset[tuple[str, str, str, str]], frozenset[tuple[str, str, str, str]]]:
    output = run_readelf(readelf, "--dyn-syms", "--wide", elf=elf)
    defined: set[tuple[str, str, str, str]] = set()
    undefined: set[tuple[str, str, str, str]] = set()
    for line in output.splitlines():
        fields = line.split(None, 7)
        if len(fields) != 8 or not fields[0].endswith(":"):
            continue
        symbol_type, binding, visibility, section, name = (
            fields[3],
            fields[4],
            fields[5],
            fields[6],
            fields[7],
        )
        name = re.sub(r"\s+\(\d+\)$", "", name)
        if not name:
            continue
        fingerprint = (symbol_type, binding, visibility, name)
        if section == "UND":
            undefined.add(fingerprint)
        else:
            defined.add(fingerprint)
    return frozenset(defined), frozenset(undefined)


def format_symbol(symbol: tuple[str, str, str, str]) -> str:
    return "/".join(symbol)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--readelf", required=True, type=Path)
    parser.add_argument(
        "--allow-import-delta",
        action="store_true",
        help="Report but do not fail on undefined dynamic-symbol differences",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    base = args.base.resolve()
    candidate = args.candidate.resolve()
    readelf = args.readelf.absolute()
    for path in (base, candidate, readelf):
        if not path.is_file():
            raise FileNotFoundError(path)

    base_names, base_payloads = read_aar(base)
    candidate_names, candidate_payloads = read_aar(candidate)
    errors: list[str] = []

    if set(base_names) != set(candidate_names):
        errors.append("AAR entry set differs from the base")

    for name in sorted(set(base_names) - NATIVE_ENTRIES):
        if base_payloads[name] != candidate_payloads.get(name):
            errors.append(f"Non-native payload differs: {name}")

    print(f"base_sha256={sha256_file(base)}")
    print(f"candidate_sha256={sha256_file(candidate)}")
    print(
        f"non_native_entries={len(set(base_names) - NATIVE_ENTRIES)} logical_identity=PASS"
        if not errors
        else f"non_native_entries={len(set(base_names) - NATIVE_ENTRIES)} logical_identity=FAIL"
    )

    with tempfile.TemporaryDirectory(prefix="foxtv-iamf-audit-") as directory:
        audit_root = Path(directory)
        for entry in sorted(NATIVE_ENTRIES):
            _, abi, library = entry.split("/")
            base_elf = audit_root / "base" / abi / library
            candidate_elf = audit_root / "candidate" / abi / library
            base_elf.parent.mkdir(parents=True, exist_ok=True)
            candidate_elf.parent.mkdir(parents=True, exist_ok=True)
            base_elf.write_bytes(base_payloads[entry])
            candidate_elf.write_bytes(candidate_payloads[entry])

            base_header = parse_header(readelf, base_elf)
            candidate_header = parse_header(readelf, candidate_elf)
            expected_header = EXPECTED_ELF[abi]
            if candidate_header != expected_header:
                errors.append(
                    f"{entry}: header {candidate_header!r}, expected {expected_header!r}"
                )
            if candidate_header != base_header:
                errors.append(
                    f"{entry}: candidate header differs from base {base_header!r}"
                )

            alignments = parse_load_alignments(readelf, candidate_elf)
            invalid_alignments = [
                alignment
                for alignment in alignments
                if alignment < MINIMUM_LOAD_ALIGNMENT
                or alignment & (alignment - 1) != 0
            ]
            if invalid_alignments:
                errors.append(
                    f"{entry}: invalid LOAD alignment(s) "
                    f"{[hex(value) for value in invalid_alignments]}"
                )

            base_needed, base_soname = parse_dynamic(readelf, base_elf)
            candidate_needed, candidate_soname = parse_dynamic(readelf, candidate_elf)
            if candidate_needed != base_needed:
                errors.append(
                    f"{entry}: DT_NEEDED differs; "
                    f"base={base_needed}, candidate={candidate_needed}"
                )
            if candidate_soname != base_soname:
                errors.append(
                    f"{entry}: DT_SONAME differs; "
                    f"base={base_soname!r}, candidate={candidate_soname!r}"
                )

            base_exports, base_imports = parse_dynamic_symbols(readelf, base_elf)
            candidate_exports, candidate_imports = parse_dynamic_symbols(
                readelf, candidate_elf
            )
            if candidate_exports != base_exports:
                added = sorted(candidate_exports - base_exports)
                removed = sorted(base_exports - candidate_exports)
                errors.append(
                    f"{entry}: exported dynamic ABI differs; "
                    f"added={[format_symbol(value) for value in added]}, "
                    f"removed={[format_symbol(value) for value in removed]}"
                )
            import_added = sorted(candidate_imports - base_imports)
            import_removed = sorted(base_imports - candidate_imports)
            if import_added or import_removed:
                message = (
                    f"{entry}: undefined dynamic symbols differ; "
                    f"added={[format_symbol(value) for value in import_added]}, "
                    f"removed={[format_symbol(value) for value in import_removed]}"
                )
                if args.allow_import_delta:
                    print(f"WARN {message}")
                else:
                    errors.append(message)

            print(
                f"PASS {entry} sha256={sha256_bytes(candidate_payloads[entry])} "
                f"header={candidate_header[0]}/{candidate_header[1]} "
                f"load_align={','.join(hex(value) for value in alignments)} "
                f"needed={','.join(candidate_needed)} exports={len(candidate_exports)} "
                f"imports={len(candidate_imports)}"
            )

    if errors:
        for error in errors:
            print(f"FAIL {error}")
        print(f"audit=FAIL errors={len(errors)}")
        return 1

    print("audit=PASS errors=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
