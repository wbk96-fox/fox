#!/usr/bin/env python3
"""Fail-closed audit of FOX.TV's explicitly declared local native inputs.

The default audit uses NATIVE_ARTIFACTS.lock.json and never scans build caches.
Optional, explicit paths can add a final APK, built Dovi outputs, or an FFmpeg
source build candidate. Warnings do not change the exit status unless
--strict-all-abis promotes 32-bit page-alignment findings to errors.
"""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
from typing import Any, Iterable
import zipfile

SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
JNI_PATTERN = re.compile(r"\b(Java_[A-Za-z0-9_]+)\s*\(")
AAR_LITERAL_PATTERN = re.compile(r'''["'](libs/[A-Za-z0-9_./-]+\.aar)["']''')
PROVENANCE_STATUSES = frozenset({"verified", "partial", "gap"})


@dataclass(frozen=True)
class Finding:
    severity: str
    code: str
    artifact: str
    message: str


class AuditFailure(RuntimeError):
    """A local input could not be audited reliably."""


class NativeAuditor:
    def __init__(
        self,
        root: Path,
        lock_path: Path,
        readelf: Path,
        ar: Path,
        nm: Path,
        strict_all_abis: bool,
    ) -> None:
        self.root = root.resolve()
        self.lock_path = lock_path.resolve()
        self.readelf = readelf.resolve()
        self.ar = ar.resolve()
        self.nm = nm.resolve()
        self.strict_all_abis = strict_all_abis
        self.findings: list[Finding] = []
        self.records: list[dict[str, Any]] = []
        self.collisions: dict[tuple[str, str, str], list[tuple[str, str]]] = {}
        self.lock = self._load_lock()
        self.policy = self.lock["policy"]
        self.expected_elf: dict[str, dict[str, str]] = self.policy["expectedElf"]
        self.minimum_alignment = int(self.policy["minimumLoadAlignment"])
        self.blocking_abis = frozenset(self.policy["blocking64BitAbis"])
        self.all_abis = tuple(self.policy["allAbis"])

    def add(self, severity: str, code: str, artifact: str, message: str) -> None:
        self.findings.append(Finding(severity, code, artifact, message))

    def error(self, code: str, artifact: str, message: str) -> None:
        self.add("ERROR", code, artifact, message)

    def warn(self, code: str, artifact: str, message: str) -> None:
        self.add("WARN", code, artifact, message)

    def _load_lock(self) -> dict[str, Any]:
        if not self.lock_path.is_file():
            raise AuditFailure(f"Brak lock file: {self.lock_path}")
        try:
            lock = json.loads(self.lock_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise AuditFailure(f"Nie można odczytać lock file: {error}") from error
        if lock.get("schemaVersion") != 1:
            raise AuditFailure(
                f"Nieobsługiwana wersja lock schema: {lock.get('schemaVersion')!r}"
            )
        if not isinstance(lock.get("artifacts"), list):
            raise AuditFailure("Lock file nie zawiera listy artifacts")
        return lock

    def repo_path(self, relative: str) -> Path:
        pure = PurePosixPath(relative)
        if (
            not relative
            or pure.is_absolute()
            or ".." in pure.parts
            or "\\" in relative
            or "\x00" in relative
        ):
            raise AuditFailure(f"Niebezpieczna ścieżka repozytorium: {relative!r}")
        candidate = self.root / Path(*pure.parts)
        current = self.root
        for part in pure.parts:
            current = current / part
            if current.is_symlink():
                raise AuditFailure(f"Symlink w ścieżce przypiętego artefaktu: {relative}")
        resolved = candidate.resolve(strict=False)
        try:
            resolved.relative_to(self.root)
        except ValueError as error:
            raise AuditFailure(f"Ścieżka wychodzi poza workspace: {relative}") from error
        return candidate

    @staticmethod
    def sha256_bytes(data: bytes) -> str:
        return hashlib.sha256(data).hexdigest()

    @staticmethod
    def sha256_file(path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
        return digest.hexdigest()

    def run_tool(self, tool: Path, *arguments: str) -> str:
        environment = os.environ.copy()
        environment["LC_ALL"] = "C"
        try:
            completed = subprocess.run(
                [str(tool), *arguments],
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env=environment,
                timeout=180,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            raise AuditFailure(f"Nie można uruchomić {tool.name}: {error}") from error
        if completed.returncode != 0:
            detail = completed.stderr.strip() or completed.stdout.strip()
            raise AuditFailure(
                f"{tool.name} zakończył się kodem {completed.returncode}: {detail}"
            )
        return completed.stdout

    def validate_lock(self) -> None:
        required_policy = {
            "minimumLoadAlignment",
            "blocking64BitAbis",
            "allAbis",
            "expectedElf",
            "maximumZipEntryBytes",
            "maximumZipTotalBytes",
            "maximumCompressionRatio",
            "androidSystemLibraries",
        }
        missing_policy = sorted(required_policy - self.policy.keys())
        if missing_policy:
            raise AuditFailure(f"Lock policy nie zawiera: {missing_policy}")
        if self.minimum_alignment <= 0 or self.minimum_alignment & (
            self.minimum_alignment - 1
        ):
            raise AuditFailure("minimumLoadAlignment musi być dodatnią potęgą dwóch")
        if set(self.expected_elf) != set(self.all_abis):
            raise AuditFailure("expectedElf i allAbis opisują różne zbiory ABI")

        seen_paths: set[str] = set()
        allowed_kinds = {"aar", "executable-elf", "static-archive"}
        for artifact in self.lock["artifacts"]:
            if not isinstance(artifact, dict):
                raise AuditFailure("Każdy artifacts[] musi być obiektem")
            path = artifact.get("path")
            kind = artifact.get("kind")
            digest = artifact.get("sha256")
            provenance_status = artifact.get("provenanceStatus")
            if not isinstance(path, str):
                raise AuditFailure("Artifact nie ma tekstowego path")
            self.repo_path(path)
            if path in seen_paths:
                raise AuditFailure(f"Powtórzona ścieżka w lock file: {path}")
            seen_paths.add(path)
            if kind not in allowed_kinds:
                raise AuditFailure(f"Nieobsługiwany kind dla {path}: {kind!r}")
            if not isinstance(digest, str) or not SHA256_PATTERN.fullmatch(digest):
                raise AuditFailure(f"Nieprawidłowy SHA-256 w lock file: {path}")
            if provenance_status not in PROVENANCE_STATUSES:
                raise AuditFailure(
                    f"Nieprawidłowy provenanceStatus dla {path}: {provenance_status!r}"
                )
            if kind == "aar":
                entries = artifact.get("nativeEntries")
                if not isinstance(entries, dict):
                    raise AuditFailure(f"AAR nie ma nativeEntries map: {path}")
                for entry, entry_digest in entries.items():
                    self.validate_zip_name(entry, path)
                    if not SHA256_PATTERN.fullmatch(str(entry_digest)):
                        raise AuditFailure(
                            f"Nieprawidłowy SHA-256 wpisu {entry} w {path}"
                        )
            else:
                abi = artifact.get("abi")
                if abi not in self.expected_elf:
                    raise AuditFailure(f"Nieprawidłowe ABI dla {path}: {abi!r}")

    def audit_declared_inventory(self) -> None:
        inventory = self.lock["inventory"]
        gradle_file = self.repo_path(inventory["gradleBuildFile"])
        if not gradle_file.is_file():
            self.error("INVENTORY_GRADLE_MISSING", str(gradle_file), "Brak build file")
            return
        build_text = gradle_file.read_text(encoding="utf-8")
        declared_aars = {
            f"app/{match}" for match in AAR_LITERAL_PATTERN.findall(build_text)
        }
        locked_aars = {
            item["path"] for item in self.lock["artifacts"] if item["kind"] == "aar"
        }
        if declared_aars != locked_aars:
            self.error(
                "INVENTORY_AAR_DRIFT",
                inventory["gradleBuildFile"],
                f"missing_in_lock={sorted(declared_aars - locked_aars)}, "
                f"stale_in_lock={sorted(locked_aars - declared_aars)}",
            )

        direct_root = self.repo_path(inventory["directSharedObjectRoot"])
        actual_direct = {
            path.relative_to(self.root).as_posix()
            for path in direct_root.glob("*/*.so")
            if path.is_file()
        }
        locked_direct = {
            item["path"]
            for item in self.lock["artifacts"]
            if item["kind"] == "executable-elf"
        }
        if actual_direct != locked_direct:
            self.error(
                "INVENTORY_DIRECT_SO_DRIFT",
                inventory["directSharedObjectRoot"],
                f"missing_in_lock={sorted(actual_direct - locked_direct)}, "
                f"stale_in_lock={sorted(locked_direct - actual_direct)}",
            )

        archive_pattern = inventory["doviArchivePattern"]
        if PurePosixPath(archive_pattern).is_absolute() or ".." in PurePosixPath(
            archive_pattern
        ).parts:
            raise AuditFailure(f"Niebezpieczny glob archiwów: {archive_pattern}")
        actual_archives = {
            path.relative_to(self.root).as_posix()
            for path in self.root.glob(archive_pattern)
            if path.is_file()
        }
        locked_archives = {
            item["path"]
            for item in self.lock["artifacts"]
            if item["kind"] == "static-archive"
        }
        if actual_archives != locked_archives:
            self.error(
                "INVENTORY_STATIC_ARCHIVE_DRIFT",
                archive_pattern,
                f"missing_in_lock={sorted(actual_archives - locked_archives)}, "
                f"stale_in_lock={sorted(locked_archives - actual_archives)}",
            )

    @staticmethod
    def validate_zip_name(name: str, source: str) -> None:
        path = PurePosixPath(name)
        raw_parts = name.split("/")
        content_parts = raw_parts[:-1] if name.endswith("/") else raw_parts
        if (
            not name
            or path.is_absolute()
            or "\\" in name
            or "\x00" in name
            or any(part in {"", ".", ".."} for part in content_parts)
        ):
            raise AuditFailure(f"Niebezpieczny wpis ZIP w {source}: {name!r}")

    def validate_zip_infos(
        self, archive: zipfile.ZipFile, source: str
    ) -> list[zipfile.ZipInfo]:
        infos = archive.infolist()
        names = [info.filename for info in infos]
        if len(names) != len(set(names)):
            raise AuditFailure(f"Powtórzone wpisy ZIP: {source}")
        normalized: dict[str, str] = {}
        total_size = 0
        for info in infos:
            self.validate_zip_name(info.filename, source)
            canonical = PurePosixPath(info.filename).as_posix().rstrip("/")
            previous = normalized.setdefault(canonical, info.filename)
            if previous != info.filename:
                raise AuditFailure(
                    f"Kolizja znormalizowanych ścieżek ZIP w {source}: "
                    f"{previous!r} i {info.filename!r}"
                )
            if info.flag_bits & 0x1:
                raise AuditFailure(f"Zaszyfrowany wpis ZIP w {source}: {info.filename}")
            mode = info.external_attr >> 16
            if mode and not (
                stat.S_ISREG(mode) or stat.S_ISDIR(mode) or info.is_dir()
            ):
                raise AuditFailure(
                    f"Link lub plik specjalny w ZIP {source}: {info.filename}"
                )
            if info.file_size > int(self.policy["maximumZipEntryBytes"]):
                raise AuditFailure(
                    f"Wpis ZIP przekracza limit w {source}: {info.filename}"
                )
            total_size += info.file_size
            if total_size > int(self.policy["maximumZipTotalBytes"]):
                raise AuditFailure(f"ZIP przekracza łączny limit danych: {source}")
            if info.file_size:
                if info.compress_size == 0:
                    raise AuditFailure(
                        f"Nieprawidłowy zerowy compressed size: {source}!/{info.filename}"
                    )
                ratio = info.file_size / info.compress_size
                if ratio > int(self.policy["maximumCompressionRatio"]):
                    raise AuditFailure(
                        f"Podejrzany compression ratio {ratio:.1f}: "
                        f"{source}!/{info.filename}"
                    )
        bad_crc = archive.testzip()
        if bad_crc is not None:
            raise AuditFailure(f"Błąd CRC w {source}: {bad_crc}")
        return infos

    def verify_locked_hash(self, path: Path, expected: str, display: str) -> str:
        actual = self.sha256_file(path)
        if actual != expected:
            self.error(
                "ARTIFACT_HASH_MISMATCH",
                display,
                f"SHA-256 actual={actual}, expected={expected}",
            )
        return actual

    def parse_elf_header(self, path: Path) -> tuple[str, str, str]:
        output = self.run_tool(self.readelf, "-hW", str(path))
        class_match = re.search(r"^\s*Class:\s*(\S+)\s*$", output, re.MULTILINE)
        machine_match = re.search(r"^\s*Machine:\s*(.+?)\s*$", output, re.MULTILINE)
        type_match = re.search(r"^\s*Type:\s*(\S+)", output, re.MULTILINE)
        if not class_match or not machine_match or not type_match:
            raise AuditFailure(f"Nie można odczytać nagłówka ELF: {path}")
        return class_match.group(1), machine_match.group(1), type_match.group(1)

    def parse_program_headers(
        self, path: Path
    ) -> tuple[list[dict[str, int]], bool, bool, bool, bool]:
        output = self.run_tool(self.readelf, "-lW", str(path))
        loads: list[dict[str, int]] = []
        has_relro = False
        has_non_executable_stack = False
        has_dynamic_segment = False
        has_interpreter = False
        saw_stack = False
        for raw_line in output.splitlines():
            fields = raw_line.split()
            if not fields:
                continue
            if fields[0] == "LOAD" and len(fields) >= 8:
                try:
                    loads.append(
                        {
                            "offset": int(fields[1], 0),
                            "virtualAddress": int(fields[2], 0),
                            "alignment": int(fields[-1], 0),
                        }
                    )
                except ValueError as error:
                    raise AuditFailure(
                        f"Nie można odczytać segmentu LOAD w {path}: {raw_line}"
                    ) from error
            elif fields[0] == "DYNAMIC":
                has_dynamic_segment = True
            elif fields[0] == "INTERP":
                has_interpreter = True
            elif fields[0] == "GNU_RELRO":
                has_relro = True
            elif fields[0] == "GNU_STACK" and len(fields) >= 8:
                saw_stack = True
                flags = "".join(fields[6:-1])
                has_non_executable_stack = "E" not in flags
        if not loads:
            raise AuditFailure(f"ELF nie ma segmentów LOAD: {path}")
        if not saw_stack:
            has_non_executable_stack = False
        return (
            loads,
            has_relro,
            has_non_executable_stack,
            has_dynamic_segment,
            has_interpreter,
        )

    def parse_dynamic(self, path: Path) -> tuple[list[str], str | None, bool, bool]:
        output = self.run_tool(self.readelf, "-dW", str(path))
        if re.search(r"there is no dynamic section", output, re.IGNORECASE):
            raise AuditFailure(
                f"ELF deklaruje PT_DYNAMIC, ale readelf nie odczytał dynamic section: {path}"
            )
        needed = re.findall(r"\(NEEDED\).*?\[(.+?)\]", output)
        soname_match = re.search(r"\(SONAME\).*?\[(.+?)\]", output)
        bind_now = bool(
            re.search(r"\(BIND_NOW\)", output)
            or re.search(r"\(FLAGS(?:_1)?\).*\bNOW\b", output)
        )
        text_rel = bool(re.search(r"\(TEXTREL\)", output))
        return needed, soname_match.group(1) if soname_match else None, bind_now, text_rel

    def parse_dynamic_exports(self, path: Path) -> set[str]:
        output = self.run_tool(self.readelf, "--dyn-syms", "--wide", str(path))
        exports: set[str] = set()
        for line in output.splitlines():
            fields = line.split(None, 7)
            if len(fields) != 8 or not fields[0].endswith(":"):
                continue
            section = fields[6]
            name = re.sub(r"\s+\(\d+\)$", "", fields[7]).strip()
            if section != "UND" and name:
                exports.add(name.split("@", 1)[0])
        return exports

    def alignment_severity(self, abi: str) -> str:
        if abi in self.blocking_abis or self.strict_all_abis:
            return "ERROR"
        return "WARN"

    def register_collision(
        self, scope: str, abi: str, name: str, display: str, digest: str
    ) -> None:
        self.collisions.setdefault((scope, abi, name), []).append((display, digest))

    def audit_elf(
        self,
        path: Path,
        display: str,
        abi: str,
        profile: str,
        expected_hash: str | None = None,
        expected_jni: Iterable[str] | None = None,
        logical_name: str | None = None,
        collision_scope: str = "locked-inputs",
        zip_alignment: int | None = None,
        zip_stored: bool | None = None,
    ) -> dict[str, Any]:
        if not path.is_file() or path.is_symlink():
            raise AuditFailure(f"Brak regularnego ELF: {path}")
        file_name = logical_name or path.name
        digest = self.sha256_file(path)
        if expected_hash and digest != expected_hash:
            self.error(
                "ELF_HASH_MISMATCH",
                display,
                f"SHA-256 actual={digest}, expected={expected_hash}",
            )
        expected_header = self.expected_elf.get(abi)
        if expected_header is None:
            raise AuditFailure(f"Nieobsługiwane ABI {abi!r}: {display}")
        elf_class, machine, elf_type = self.parse_elf_header(path)
        if elf_class != expected_header["class"] or machine != expected_header["machine"]:
            self.error(
                "ELF_ABI_MISMATCH",
                display,
                f"actual={elf_class}/{machine}, "
                f"expected={expected_header['class']}/{expected_header['machine']}",
            )
        if profile in {"shared", "executable"} and elf_type != "DYN":
            self.error(
                "ELF_TYPE",
                display,
                f"Oczekiwano PIE/shared ET_DYN, otrzymano {elf_type}",
            )

        (
            loads,
            relro,
            non_executable_stack,
            has_dynamic_segment,
            has_interpreter,
        ) = self.parse_program_headers(path)
        linkage = "dynamic" if has_dynamic_segment else "static"
        if profile == "shared" and not has_dynamic_segment:
            self.error(
                "ELF_DYNAMIC_SEGMENT_MISSING",
                display,
                "Profil shared wymaga PT_DYNAMIC; statyczny wyjątek dotyczy wyłącznie executable",
            )
        if has_interpreter and not has_dynamic_segment:
            self.error(
                "ELF_INTERP_WITHOUT_DYNAMIC",
                display,
                "ELF ma PT_INTERP bez PT_DYNAMIC",
            )
        low_alignments: set[int] = set()
        for segment in loads:
            alignment = segment["alignment"]
            if alignment <= 0 or alignment & (alignment - 1):
                self.error(
                    "ELF_LOAD_NOT_POWER_OF_TWO",
                    display,
                    f"p_align={alignment:#x}",
                )
                continue
            if segment["offset"] % alignment != segment["virtualAddress"] % alignment:
                self.error(
                    "ELF_LOAD_INCONGRUENT",
                    display,
                    f"offset={segment['offset']:#x}, vaddr={segment['virtualAddress']:#x}, "
                    f"p_align={alignment:#x}",
                )
            if alignment < self.minimum_alignment:
                low_alignments.add(alignment)
        if low_alignments:
            self.add(
                self.alignment_severity(abi),
                "ELF_LOAD_ALIGNMENT",
                display,
                f"p_align={','.join(hex(value) for value in sorted(low_alignments))} "
                f"< {self.minimum_alignment:#x} dla ABI {abi}",
            )

        if has_dynamic_segment and not relro:
            self.warn("ELF_RELRO_MISSING", display, "Dynamiczny ELF nie ma GNU_RELRO")
        if not non_executable_stack:
            self.warn(
                "ELF_STACK_POLICY",
                display,
                "Brak jawnego niewykonywalnego GNU_STACK albo stos jest wykonywalny",
            )

        needed: list[str] = []
        soname: str | None = None
        bind_now: bool | None = None
        text_rel = False
        if has_dynamic_segment:
            needed, soname, bind_now, text_rel = self.parse_dynamic(path)
            if text_rel:
                self.error("ELF_TEXTREL", display, "ELF zawiera DT_TEXTREL")
            if not bind_now:
                self.warn(
                    "ELF_NOW_MISSING",
                    display,
                    "Dynamiczny ELF nie ma BIND_NOW/DF_1_NOW",
                )
        for dependency in needed:
            if (
                not dependency
                or "/" in dependency
                or "\\" in dependency
                or dependency in {".", ".."}
            ):
                self.error(
                    "ELF_UNSAFE_NEEDED",
                    display,
                    f"Niebezpieczny DT_NEEDED={dependency!r}",
                )
        if soname and ("/" in soname or "\\" in soname or soname in {".", ".."}):
            self.error("ELF_UNSAFE_SONAME", display, f"Niebezpieczny SONAME={soname!r}")
        if profile == "shared" and soname and soname != file_name:
            self.warn(
                "ELF_SONAME_FILENAME_MISMATCH",
                display,
                f"SONAME={soname!r}, filename={file_name!r}",
            )

        exports = self.parse_dynamic_exports(path)
        jni_symbols = sorted(
            symbol
            for symbol in exports
            if symbol.startswith("Java_") or symbol == "JNI_OnLoad"
        )
        if expected_jni is not None:
            expected_set = set(expected_jni)
            missing = sorted(expected_set - exports)
            if missing:
                self.error(
                    "DOVI_JNI_MISSING",
                    display,
                    f"Brak eksportów JNI: {missing}",
                )
            forbidden_prefixes = self.lock["doviContract"]["forbiddenJniPrefixes"]
            forbidden = sorted(
                symbol
                for symbol in exports
                if any(symbol.startswith(prefix) for prefix in forbidden_prefixes)
            )
            if forbidden:
                self.error(
                    "DOVI_JNI_OLD_NAMESPACE",
                    display,
                    f"Stare eksporty JNI: {forbidden}",
                )

        if zip_stored is True and zip_alignment is not None:
            if zip_alignment % self.minimum_alignment:
                self.add(
                    self.alignment_severity(abi),
                    "APK_ZIP_ALIGNMENT",
                    display,
                    f"stored native data offset={zip_alignment:#x} nie jest wyrównany do "
                    f"{self.minimum_alignment:#x}",
                )

        self.register_collision(collision_scope, abi, file_name, display, digest)
        record = {
            "kind": "elf",
            "artifact": display,
            "scope": collision_scope,
            "fileName": file_name,
            "profile": profile,
            "abi": abi,
            "sha256": digest,
            "elfClass": elf_class,
            "machine": machine,
            "elfType": elf_type,
            "linkage": linkage,
            "hasDynamicSegment": has_dynamic_segment,
            "hasInterpreter": has_interpreter,
            "loadSegments": loads,
            "relro": relro if has_dynamic_segment else None,
            "bindNow": bind_now,
            "nonExecutableStack": non_executable_stack,
            "soname": soname,
            "needed": needed,
            "jniSymbols": jni_symbols,
        }
        if zip_stored is not None:
            record["zipStored"] = zip_stored
            record["zipDataOffset"] = zip_alignment
        self.records.append(record)
        return record

    def audit_aar(self, artifact: dict[str, Any]) -> None:
        display = artifact["path"]
        path = self.repo_path(display)
        if not path.is_file() or path.is_symlink():
            self.error("ARTIFACT_MISSING", display, "Brak regularnego AAR")
            return
        container_hash = self.verify_locked_hash(path, artifact["sha256"], display)
        expected_entries: dict[str, str] = artifact["nativeEntries"]
        try:
            with zipfile.ZipFile(path, "r") as archive:
                infos = self.validate_zip_infos(archive, display)
                names = {info.filename for info in infos if not info.is_dir()}
                if "classes.jar" not in names:
                    self.error("AAR_CLASSES_MISSING", display, "AAR nie zawiera classes.jar")
                actual_native = {
                    name for name in names if name.startswith("jni/")
                }
                if actual_native != set(expected_entries):
                    self.error(
                        "AAR_NATIVE_ENTRY_DRIFT",
                        display,
                        f"missing={sorted(set(expected_entries) - actual_native)}, "
                        f"unexpected={sorted(actual_native - set(expected_entries))}",
                    )
                self.records.append(
                    {
                        "kind": "container",
                        "artifact": display,
                        "containerType": "aar",
                        "sha256": container_hash,
                        "entries": len(infos),
                        "nativeEntries": len(actual_native),
                        "consumer": artifact["consumer"],
                        "provenanceStatus": artifact["provenanceStatus"],
                        "provenance": artifact["provenance"],
                    }
                )
                with tempfile.TemporaryDirectory(prefix="foxtv-native-aar-") as directory:
                    temporary_root = Path(directory)
                    for index, entry in enumerate(sorted(actual_native)):
                        parts = PurePosixPath(entry).parts
                        if (
                            len(parts) != 3
                            or parts[0] != "jni"
                            or parts[1] not in self.expected_elf
                            or not parts[2].endswith(".so")
                        ):
                            self.error(
                                "AAR_NATIVE_PATH",
                                f"{display}!/{entry}",
                                "Oczekiwano jni/<abi>/<name>.so",
                            )
                            continue
                        payload = archive.read(entry)
                        payload_hash = self.sha256_bytes(payload)
                        expected_hash = expected_entries.get(entry)
                        if expected_hash and payload_hash != expected_hash:
                            self.error(
                                "AAR_NATIVE_HASH_MISMATCH",
                                f"{display}!/{entry}",
                                f"actual={payload_hash}, expected={expected_hash}",
                            )
                        extracted = temporary_root / f"{index}-{parts[2]}"
                        extracted.write_bytes(payload)
                        self.audit_elf(
                            extracted,
                            f"{display}!/{entry}",
                            parts[1],
                            "shared",
                            expected_hash=expected_hash,
                            logical_name=parts[2],
                        )
        except (OSError, zipfile.BadZipFile, AuditFailure) as error:
            self.error("AAR_AUDIT_FAILED", display, str(error))

    def audit_candidate_aar(self, candidate_path: Path) -> None:
        path = candidate_path.resolve()
        display = str(path)
        if not path.is_file() or path.is_symlink():
            self.error("CANDIDATE_AAR_MISSING", display, "Brak regularnego AAR")
            return
        scope = f"candidate-aar:{display}"
        try:
            with zipfile.ZipFile(path, "r") as archive:
                infos = self.validate_zip_infos(archive, display)
                names = {info.filename for info in infos if not info.is_dir()}
                if "classes.jar" not in names:
                    self.error("AAR_CLASSES_MISSING", display, "AAR nie zawiera classes.jar")
                native_entries = sorted(name for name in names if name.startswith("jni/"))
                if not native_entries:
                    self.error("CANDIDATE_AAR_NATIVE_EMPTY", display, "Brak jni/<abi>/*.so")
                    return
                parsed_entries: list[tuple[str, str, str]] = []
                for entry in native_entries:
                    parts = PurePosixPath(entry).parts
                    if (
                        len(parts) != 3
                        or parts[0] != "jni"
                        or parts[1] not in self.expected_elf
                        or not parts[2].endswith(".so")
                    ):
                        self.error(
                            "AAR_NATIVE_PATH",
                            f"{display}!/{entry}",
                            "Oczekiwano jni/<abi>/<name>.so",
                        )
                        continue
                    parsed_entries.append((entry, parts[1], parts[2]))
                actual_abis = {abi for _, abi, _ in parsed_entries}
                if actual_abis != set(self.all_abis):
                    self.error(
                        "CANDIDATE_AAR_ABI_SET",
                        display,
                        f"actual={sorted(actual_abis)}, expected={sorted(self.all_abis)}",
                    )
                library_sets = {
                    abi: {name for _, entry_abi, name in parsed_entries if entry_abi == abi}
                    for abi in self.all_abis
                }
                if len({frozenset(value) for value in library_sets.values()}) != 1:
                    self.error(
                        "CANDIDATE_AAR_LIBRARY_SET",
                        display,
                        f"Niespójne biblioteki per ABI: {library_sets}",
                    )
                self.records.append(
                    {
                        "kind": "container",
                        "artifact": display,
                        "containerType": "candidate-aar",
                        "sha256": self.sha256_file(path),
                        "entries": len(infos),
                        "nativeEntries": len(native_entries),
                        "provenanceStatus": "candidate",
                    }
                )
                with tempfile.TemporaryDirectory(prefix="foxtv-native-candidate-aar-") as directory:
                    temporary_root = Path(directory)
                    for index, (entry, abi, file_name) in enumerate(parsed_entries):
                        extracted = temporary_root / f"{index}-{file_name}"
                        extracted.write_bytes(archive.read(entry))
                        expected_dovi = (
                            self.lock["doviContract"]["expectedJniSymbols"]
                            if file_name == "libdovi_bridge.so"
                            else None
                        )
                        self.audit_elf(
                            extracted,
                            f"{display}!/{entry}",
                            abi,
                            "shared",
                            expected_jni=expected_dovi,
                            logical_name=file_name,
                            collision_scope=scope,
                        )
        except (OSError, zipfile.BadZipFile, AuditFailure) as error:
            self.error("CANDIDATE_AAR_AUDIT_FAILED", display, str(error))

    def parse_archive_headers(self, output: str, display: str) -> list[tuple[str, str, str]]:
        headers: list[tuple[str, str, str]] = []
        blocks = re.split(r"(?m)(?=^File: )", output)
        for block in blocks:
            if not block.startswith("File: "):
                continue
            class_match = re.search(r"^\s*Class:\s*(\S+)\s*$", block, re.MULTILINE)
            machine_match = re.search(r"^\s*Machine:\s*(.+?)\s*$", block, re.MULTILINE)
            type_match = re.search(r"^\s*Type:\s*(\S+)", block, re.MULTILINE)
            if not class_match or not machine_match or not type_match:
                raise AuditFailure(f"Nie można odczytać członu archiwum: {display}")
            headers.append(
                (class_match.group(1), machine_match.group(1), type_match.group(1))
            )
        if not headers:
            raise AuditFailure(f"Archiwum nie zawiera rozpoznanych obiektów ELF: {display}")
        return headers

    def audit_static_archive(
        self,
        path: Path,
        display: str,
        abi: str,
        expected_hash: str | None,
        required_symbols: Iterable[str],
        provenance: dict[str, str] | None = None,
        allow_duplicate_member_names: bool = False,
    ) -> None:
        if not path.is_file() or path.is_symlink():
            self.error("ARTIFACT_MISSING", display, "Brak regularnego static archive")
            return
        digest = self.sha256_file(path)
        if expected_hash and digest != expected_hash:
            self.error(
                "ARCHIVE_HASH_MISMATCH",
                display,
                f"actual={digest}, expected={expected_hash}",
            )
        with path.open("rb") as stream:
            magic = stream.read(8)
        if magic == b"!<thin>\n":
            self.error("ARCHIVE_THIN", display, "Thin archive jest niedozwolone")
            return
        if magic != b"!<arch>\n":
            self.error("ARCHIVE_MAGIC", display, "Nieprawidłowy format ar")
            return
        try:
            members = [
                line.strip()
                for line in self.run_tool(self.ar, "t", str(path)).splitlines()
                if line.strip()
            ]
            if not members:
                raise AuditFailure("Archiwum nie zawiera członów")
            duplicate_members = {
                name: count
                for name, count in Counter(members).items()
                if count > 1
            }
            if duplicate_members and not allow_duplicate_member_names:
                self.error(
                    "ARCHIVE_DUPLICATE_MEMBER",
                    display,
                    f"Powtórzone nazwy członów: {duplicate_members}",
                )
            for member in members:
                pure = PurePosixPath(member)
                if pure.is_absolute() or ".." in pure.parts or "\\" in member:
                    self.error(
                        "ARCHIVE_UNSAFE_MEMBER",
                        display,
                        f"Niebezpieczna nazwa członu: {member!r}",
                    )

            header_output = self.run_tool(self.readelf, "-hW", str(path))
            headers = self.parse_archive_headers(header_output, display)
            if len(headers) != len(members):
                self.error(
                    "ARCHIVE_OBJECT_COUNT_MISMATCH",
                    display,
                    f"ar members={len(members)}, ELF headers={len(headers)}",
                )
            expected = self.expected_elf[abi]
            mismatches = sorted(
                {
                    f"{elf_class}/{machine}/{elf_type}"
                    for elf_class, machine, elf_type in headers
                    if elf_class != expected["class"]
                    or machine != expected["machine"]
                    or elf_type != "REL"
                }
            )
            if mismatches:
                self.error(
                    "ARCHIVE_ABI_MISMATCH",
                    display,
                    f"Nieprawidłowe obiekty: {mismatches}",
                )

            nm_output = self.run_tool(self.nm, "-g", "--defined-only", str(path))
            defined_symbols = {
                fields[-1]
                for line in nm_output.splitlines()
                if len(fields := line.split()) >= 2
            }
            missing_symbols = sorted(set(required_symbols) - defined_symbols)
            if missing_symbols:
                self.error(
                    "ARCHIVE_REQUIRED_SYMBOL_MISSING",
                    display,
                    f"Brak symboli: {missing_symbols}",
                )
            record: dict[str, Any] = {
                "kind": "static-archive",
                "artifact": display,
                "abi": abi,
                "sha256": digest,
                "members": len(members),
                "duplicateMemberNames": dict(sorted(duplicate_members.items())),
                "elfObjects": len(headers),
                "elfClass": expected["class"],
                "machine": expected["machine"],
                "requiredSymbols": sorted(required_symbols),
            }
            if provenance:
                record.update(provenance)
            self.records.append(record)
        except AuditFailure as error:
            self.error("ARCHIVE_AUDIT_FAILED", display, str(error))

    def audit_locked_artifacts(self) -> None:
        required_dovi_symbols = self.lock["doviContract"]["requiredArchiveSymbols"]
        for artifact in self.lock["artifacts"]:
            status = artifact["provenanceStatus"]
            if status != "verified":
                self.warn(
                    "PROVENANCE_GAP",
                    artifact["path"],
                    artifact["provenance"],
                )
            kind = artifact["kind"]
            try:
                if kind == "aar":
                    self.audit_aar(artifact)
                elif kind == "executable-elf":
                    path = self.repo_path(artifact["path"])
                    self.audit_elf(
                        path,
                        artifact["path"],
                        artifact["abi"],
                        "executable",
                        expected_hash=artifact["sha256"],
                    )
                    self.records.append(
                        {
                            "kind": "provenance",
                            "artifact": artifact["path"],
                            "consumer": artifact["consumer"],
                            "provenanceStatus": status,
                            "provenance": artifact["provenance"],
                        }
                    )
                elif kind == "static-archive":
                    self.audit_static_archive(
                        self.repo_path(artifact["path"]),
                        artifact["path"],
                        artifact["abi"],
                        artifact["sha256"],
                        required_dovi_symbols,
                        provenance={
                            "consumer": artifact["consumer"],
                            "provenanceStatus": status,
                            "provenance": artifact["provenance"],
                        },
                    )
            except (OSError, AuditFailure) as error:
                self.error("ARTIFACT_AUDIT_FAILED", artifact["path"], str(error))

    def audit_source_contracts(self) -> None:
        contract = self.lock["doviContract"]
        try:
            gradle_path = self.repo_path(contract["gradleBuildFile"])
            kotlin_path = self.repo_path(contract["kotlinFile"])
            source_path = self.repo_path(contract["sourceFile"])
            cmake_path = self.repo_path(contract["cmakeFile"])
            gradle_text = gradle_path.read_text(encoding="utf-8")
            kotlin_text = kotlin_path.read_text(encoding="utf-8")
            source_text = source_path.read_text(encoding="utf-8")
            cmake_text = cmake_path.read_text(encoding="utf-8")
            fqcn = contract["kotlinFqcn"]
            package_name, class_name = fqcn.rsplit(".", 1)
            if f"package {package_name}" not in kotlin_text or not re.search(
                rf"\bobject\s+{re.escape(class_name)}\b", kotlin_text
            ):
                self.error(
                    "DOVI_KOTLIN_FQCN",
                    contract["kotlinFile"],
                    f"Nie znaleziono object {fqcn}",
                )
            source_jni = set(JNI_PATTERN.findall(source_text))
            expected_jni = set(contract["expectedJniSymbols"])
            if source_jni != expected_jni:
                self.error(
                    "DOVI_SOURCE_JNI_SET",
                    contract["sourceFile"],
                    f"missing={sorted(expected_jni - source_jni)}, "
                    f"unexpected={sorted(source_jni - expected_jni)}",
                )
            forbidden = [
                prefix
                for prefix in contract["forbiddenJniPrefixes"]
                if prefix in source_text
            ]
            if forbidden:
                self.error(
                    "DOVI_SOURCE_OLD_NAMESPACE",
                    contract["sourceFile"],
                    f"Stare prefiksy: {forbidden}",
                )
            for linker_flag in (
                "max-page-size=16384",
                "common-page-size=16384",
            ):
                if linker_flag not in cmake_text:
                    self.error(
                        "DOVI_CMAKE_16KB_FLAG",
                        contract["cmakeFile"],
                        f"Brak {linker_flag}",
                    )
            gradle_fail_closed_tokens = (
                "fun parseRequiredBooleanProperty",
                '"true" -> true',
                '"false" -> false',
                '"DOVI_ENABLE_REAL_LINK",',
                "parseRequiredBooleanProperty(",
            )
            for token in gradle_fail_closed_tokens:
                if token not in gradle_text:
                    self.error(
                        "DOVI_GRADLE_FAIL_CLOSED",
                        contract["gradleBuildFile"],
                        f"Brak tokenu jawnej konfiguracji: {token}",
                    )
            cmake_fail_closed_tokens = (
                'set(DOVI_ENABLE_LIBDOVI "" CACHE STRING',
                'DOVI_ENABLE_LIBDOVI STREQUAL "ON"',
                'DOVI_ENABLE_LIBDOVI STREQUAL "OFF"',
                "message(FATAL_ERROR",
                "stub mode is permitted only by explicit OFF",
            )
            for token in cmake_fail_closed_tokens:
                if token not in cmake_text:
                    self.error(
                        "DOVI_CMAKE_FAIL_CLOSED",
                        contract["cmakeFile"],
                        f"Brak tokenu fail-closed: {token}",
                    )
            if "option(DOVI_ENABLE_LIBDOVI" in cmake_text:
                self.error(
                    "DOVI_CMAKE_IMPLICIT_STUB_DEFAULT",
                    contract["cmakeFile"],
                    "DOVI_ENABLE_LIBDOVI nie może mieć domyślnego OFF",
                )
            if cmake_text.count('if(DOVI_ENABLE_LIBDOVI STREQUAL "ON")') < 2:
                self.error(
                    "DOVI_CMAKE_INTENT_BRANCH",
                    contract["cmakeFile"],
                    "Rozwiązywanie i linkowanie muszą zależeć od jawnego ON, nie od efektu lookup",
                )
            self.records.append(
                {
                    "kind": "contract",
                    "artifact": "Dovi JNI source contract",
                    "kotlinFqcn": fqcn,
                    "failClosedRealLink": True,
                    "expectedJniSymbols": sorted(expected_jni),
                    "sourceJniSymbols": sorted(source_jni),
                }
            )
        except (OSError, AuditFailure) as error:
            self.error("DOVI_CONTRACT_AUDIT_FAILED", "Dovi", str(error))

        ffmpeg = self.lock["ffmpegSourceModeContract"]
        try:
            settings_path = self.repo_path(ffmpeg["settingsFile"])
            app_path = self.repo_path(ffmpeg["appBuildFile"])
            module_path = self.repo_path(ffmpeg["moduleBuildFile"])
            cmake_path = self.repo_path(ffmpeg["cmakeFile"])
            builder_path = self.repo_path(ffmpeg["builderFile"])
            texts = {
                "settings": settings_path.read_text(encoding="utf-8"),
                "app": app_path.read_text(encoding="utf-8"),
                "module": module_path.read_text(encoding="utf-8"),
                "cmake": cmake_path.read_text(encoding="utf-8"),
                "builder": builder_path.read_text(encoding="utf-8"),
            }
            required_tokens = {
                "settings": [
                    "foxtvUseLocalFfmpegDecoder",
                    'include(\":ffmpeg-decoder-downmix\")',
                    'gradleProperty(\"useLocalFfmpegDecoder\")',
                    'environmentVariable(\"USE_LOCAL_FFMPEG_DECODER\")',
                ],
                "app": [
                    "foxtvUseLocalFfmpegDecoder",
                    'project(\":ffmpeg-decoder-downmix\")',
                    'files(\"libs/lib-decoder-ffmpeg-release.aar\")',
                ],
                "module": [
                    ffmpeg["expectedTag"],
                    ffmpeg["expectedCommit"],
                    ffmpeg["expectedNdkRevision"],
                    "verifyFfmpegSourceInputs",
                    'requireMarker(\"SOURCE_TAG\"',
                    'requireMarker(\"NDK_VERSION\"',
                    'requireMarker(\"ENABLED_DECODERS\"',
                ],
                "cmake": [
                    "max-page-size=16384",
                    "common-page-size=16384",
                    "-Wl,-z,relro",
                    "-Wl,-z,now",
                ],
                "builder": [
                    ffmpeg["expectedTag"],
                    ffmpeg["expectedCommit"],
                    ffmpeg["expectedNdkRevision"],
                    'readonly ANDROID_API="24"',
                    "staging-$$",
                    "source.properties",
                ],
            }
            files_for_group = {
                "settings": ffmpeg["settingsFile"],
                "app": ffmpeg["appBuildFile"],
                "module": ffmpeg["moduleBuildFile"],
                "cmake": ffmpeg["cmakeFile"],
                "builder": ffmpeg["builderFile"],
            }
            for group, tokens in required_tokens.items():
                for token in tokens:
                    if token not in texts[group]:
                        self.error(
                            "FFMPEG_SOURCE_MODE_CONTRACT",
                            files_for_group[group],
                            f"Brak tokenu kontraktu: {token}",
                        )
            if "staging-${$}" in texts["builder"]:
                self.error(
                    "FFMPEG_STAGING_PID",
                    ffmpeg["builderFile"],
                    "Nieprawidłowe staging-${$}",
                )
            if not os.access(builder_path, os.X_OK):
                self.error(
                    "FFMPEG_BUILDER_NOT_EXECUTABLE",
                    ffmpeg["builderFile"],
                    "Skrypt nie ma executable bit",
                )
            try:
                self.run_tool(Path(shutil.which("bash") or "/bin/bash"), "-n", str(builder_path))
            except AuditFailure as error:
                self.error("FFMPEG_BUILDER_SYNTAX", ffmpeg["builderFile"], str(error))
            builder_decoders = set(
                re.findall(r'"([a-z0-9_]+)"', texts["builder"].split("readonly DECODERS=(", 1)[1].split(")", 1)[0])
            )
            expected_decoders = set(ffmpeg["expectedDecoders"])
            if builder_decoders != expected_decoders:
                self.error(
                    "FFMPEG_CODEC_SET",
                    ffmpeg["builderFile"],
                    f"missing={sorted(expected_decoders - builder_decoders)}, "
                    f"unexpected={sorted(builder_decoders - expected_decoders)}",
                )
            self.records.append(
                {
                    "kind": "contract",
                    "artifact": "FFmpeg source-mode contract",
                    "tag": ffmpeg["expectedTag"],
                    "commit": ffmpeg["expectedCommit"],
                    "ndkRevision": ffmpeg["expectedNdkRevision"],
                    "androidApi": ffmpeg["expectedAndroidApi"],
                    "abis": list(self.all_abis),
                    "decoders": ffmpeg["expectedDecoders"],
                }
            )
        except (OSError, IndexError, AuditFailure) as error:
            self.error("FFMPEG_CONTRACT_AUDIT_FAILED", "FFmpeg source-mode", str(error))

    def zip_data_offset(self, archive_path: Path, info: zipfile.ZipInfo) -> int:
        with archive_path.open("rb") as stream:
            stream.seek(info.header_offset)
            header = stream.read(30)
        if len(header) != 30:
            raise AuditFailure(f"Ucięty local ZIP header: {info.filename}")
        signature, *_, name_length, extra_length = struct.unpack("<IHHHHHIIIHH", header)
        if signature != 0x04034B50:
            raise AuditFailure(f"Nieprawidłowy local ZIP header: {info.filename}")
        return info.header_offset + 30 + name_length + extra_length

    def audit_apk(self, apk_path: Path) -> None:
        apk = apk_path.resolve()
        display = str(apk)
        if not apk.is_file() or apk.is_symlink():
            self.error("APK_MISSING", display, "Brak regularnego APK")
            return
        try:
            with zipfile.ZipFile(apk, "r") as archive:
                infos = self.validate_zip_infos(archive, display)
                native_infos = [
                    info
                    for info in infos
                    if not info.is_dir() and info.filename.startswith("lib/")
                ]
                self.records.append(
                    {
                        "kind": "container",
                        "artifact": display,
                        "containerType": "apk",
                        "sha256": self.sha256_file(apk),
                        "entries": len(infos),
                        "nativeEntries": len(native_infos),
                    }
                )
                if not native_infos:
                    self.error("APK_NATIVE_EMPTY", display, "APK nie zawiera lib/<abi>/*.so")
                    return
                with tempfile.TemporaryDirectory(prefix="foxtv-native-apk-") as directory:
                    temporary_root = Path(directory)
                    for index, info in enumerate(native_infos):
                        parts = PurePosixPath(info.filename).parts
                        if (
                            len(parts) != 3
                            or parts[0] != "lib"
                            or parts[1] not in self.expected_elf
                            or not parts[2].endswith(".so")
                        ):
                            self.error(
                                "APK_NATIVE_PATH",
                                f"{display}!/{info.filename}",
                                "Oczekiwano lib/<abi>/<name>.so",
                            )
                            continue
                        extracted = temporary_root / f"{index}-{parts[2]}"
                        extracted.write_bytes(archive.read(info))
                        data_offset = self.zip_data_offset(apk, info)
                        expected_dovi = (
                            self.lock["doviContract"]["expectedJniSymbols"]
                            if parts[2] == "libdovi_bridge.so"
                            else None
                        )
                        self.audit_elf(
                            extracted,
                            f"{display}!/{info.filename}",
                            parts[1],
                            "shared",
                            expected_jni=expected_dovi,
                            logical_name=parts[2],
                            collision_scope="apk",
                            zip_alignment=data_offset,
                            zip_stored=info.compress_type == zipfile.ZIP_STORED,
                        )
        except (OSError, zipfile.BadZipFile, AuditFailure) as error:
            self.error("APK_AUDIT_FAILED", display, str(error))

    def audit_dovi_built_root(self, built_root: Path) -> None:
        root = built_root.resolve()
        display = str(root)
        if not root.is_dir():
            self.error("DOVI_BUILD_ROOT_MISSING", display, "Brak katalogu")
            return
        candidates: dict[str, list[Path]] = {abi: [] for abi in self.all_abis}
        for path in root.rglob("libdovi_bridge.so"):
            if not path.is_file() or path.is_symlink():
                continue
            matching_abis = [abi for abi in self.all_abis if abi in path.parts]
            if len(matching_abis) == 1:
                candidates[matching_abis[0]].append(path)
            else:
                self.error(
                    "DOVI_BUILD_ABI_PATH",
                    str(path),
                    f"Nie można jednoznacznie ustalić ABI: {matching_abis}",
                )
        for abi, paths in candidates.items():
            if len(paths) != 1:
                self.error(
                    "DOVI_BUILD_OUTPUT_COUNT",
                    display,
                    f"ABI {abi}: oczekiwano jednego libdovi_bridge.so, znaleziono {len(paths)}",
                )
                continue
            try:
                self.audit_elf(
                    paths[0],
                    str(paths[0]),
                    abi,
                    "shared",
                    expected_jni=self.lock["doviContract"]["expectedJniSymbols"],
                    collision_scope="dovi-built",
                )
            except (OSError, AuditFailure) as error:
                self.error("DOVI_BUILD_AUDIT_FAILED", str(paths[0]), str(error))

    def git_output(self, source: Path, *arguments: str) -> str:
        git = shutil.which("git")
        if git is None:
            raise AuditFailure("Brak git w PATH")
        return self.run_tool(Path(git), "-C", str(source), *arguments).strip()

    def audit_ffmpeg_candidate(self, source_dir: Path, build_dir: Path) -> None:
        source = source_dir.resolve()
        build = build_dir.resolve()
        contract = self.lock["ffmpegSourceModeContract"]
        display = f"FFmpeg candidate: {build}"
        if not source.is_dir() or not (source / "configure").is_file():
            self.error("FFMPEG_SOURCE_MISSING", str(source), "Brak checkoutu/configure")
            return
        if not build.is_dir():
            self.error("FFMPEG_BUILD_MISSING", str(build), "Brak build root")
            return
        try:
            revision = self.git_output(source, "rev-parse", "HEAD")
            dirty = self.git_output(source, "status", "--porcelain", "--untracked-files=normal")
            if revision != contract["expectedCommit"]:
                self.error(
                    "FFMPEG_SOURCE_REVISION",
                    str(source),
                    f"actual={revision}, expected={contract['expectedCommit']}",
                )
            if dirty:
                self.error("FFMPEG_SOURCE_DIRTY", str(source), dirty)
            markers = {
                "SOURCE_COMMIT": contract["expectedCommit"],
                "SOURCE_TAG": contract["expectedTag"],
                "ANDROID_API": contract["expectedAndroidApi"],
                "NDK_VERSION": contract["expectedNdkRevision"],
                "ENABLED_DECODERS": " ".join(contract["expectedDecoders"]),
            }
            for name, expected in markers.items():
                marker = build / name
                if not marker.is_file():
                    self.error("FFMPEG_MARKER_MISSING", display, name)
                    continue
                actual = marker.read_text(encoding="utf-8").strip()
                if actual != expected:
                    self.error(
                        "FFMPEG_MARKER_MISMATCH",
                        str(marker),
                        f"actual={actual!r}, expected={expected!r}",
                    )
            for abi in self.all_abis:
                components = build / abi / "build/config_components.h"
                if not components.is_file():
                    self.error("FFMPEG_COMPONENTS_MISSING", str(components), abi)
                else:
                    component_text = components.read_text(encoding="utf-8")
                    enabled_decoders = {
                        match.group(1).lower()
                        for match in re.finditer(
                            r"^#define CONFIG_([A-Z0-9_]+)_DECODER 1$",
                            component_text,
                            re.MULTILINE,
                        )
                    }
                    expected_decoders = set(contract["expectedDecoders"])
                    if enabled_decoders != expected_decoders:
                        self.error(
                            "FFMPEG_CODEC_CONFIG",
                            str(components),
                            f"missing={sorted(expected_decoders - enabled_decoders)}, "
                            f"unexpected={sorted(enabled_decoders - expected_decoders)}",
                        )
                    if "#define CONFIG_AC3_ENCODER 1" not in component_text:
                        self.error(
                            "FFMPEG_AC3_ENCODER_CONFIG",
                            str(components),
                            "Encoder AC-3 nie jest włączony",
                        )
                install = build / abi / "install"
                header = install / "include/libavcodec/avcodec.h"
                if not header.is_file():
                    self.error("FFMPEG_HEADER_MISSING", str(header), abi)
                for library in ("avcodec", "avutil", "swresample"):
                    archive = install / f"lib/lib{library}.a"
                    self.audit_static_archive(
                        archive,
                        str(archive),
                        abi,
                        expected_hash=None,
                        required_symbols=(),
                        allow_duplicate_member_names=True,
                    )
            self.records.append(
                {
                    "kind": "candidate",
                    "artifact": display,
                    "source": str(source),
                    "revision": revision,
                    "abis": list(self.all_abis),
                }
            )
        except (OSError, AuditFailure) as error:
            self.error("FFMPEG_CANDIDATE_AUDIT_FAILED", display, str(error))

    def audit_dependency_closure(self) -> None:
        system_libraries = set(self.policy["androidSystemLibraries"])
        elf_records = [record for record in self.records if record["kind"] == "elf"]
        available: dict[tuple[str, str], set[str]] = {}
        for record in elf_records:
            available.setdefault((record["scope"], record["abi"]), set()).add(
                record["fileName"]
            )
        for record in elf_records:
            unresolved = sorted(
                {
                    dependency
                    for dependency in record["needed"]
                    if dependency not in system_libraries
                    and dependency
                    not in available.get((record["scope"], record["abi"]), set())
                }
            )
            if not unresolved:
                continue
            severity = "ERROR" if record["scope"] == "apk" else "WARN"
            self.add(
                severity,
                "ELF_NEEDED_UNRESOLVED",
                record["artifact"],
                f"Brak w scope={record['scope']}: {unresolved}",
            )

    def audit_collisions(self) -> None:
        for (scope, abi, name), values in sorted(self.collisions.items()):
            unique_hashes = {digest for _, digest in values}
            if len(unique_hashes) > 1:
                self.error(
                    "NATIVE_BASENAME_COLLISION",
                    f"{scope}:{abi}/{name}",
                    f"Różne payloady: {values}",
                )

    def result(self, optional_inputs: dict[str, Any]) -> dict[str, Any]:
        errors = sum(finding.severity == "ERROR" for finding in self.findings)
        warnings = sum(finding.severity == "WARN" for finding in self.findings)
        status = "FAIL" if errors else ("PASS_WITH_FINDINGS" if warnings else "PASS")
        return {
            "schemaVersion": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "workspace": str(self.root),
            "lockFile": str(self.lock_path),
            "strictAllAbis": self.strict_all_abis,
            "status": status,
            "errors": errors,
            "warnings": warnings,
            "optionalInputs": optional_inputs,
            "records": self.records,
            "findings": [asdict(finding) for finding in self.findings],
        }


def locate_tool(explicit: Path | None, *names: str) -> Path:
    if explicit is not None:
        path = explicit.resolve()
        if not path.is_file():
            raise AuditFailure(f"Brak narzędzia: {path}")
        return path
    for name in names:
        resolved = shutil.which(name)
        if resolved:
            return Path(resolved)
    raise AuditFailure(f"Brak narzędzia w PATH: {' lub '.join(names)}")


def markdown_escape(value: Any) -> str:
    return str(value).replace("|", "\\|").replace("\n", "<br>")


def render_markdown(result: dict[str, Any]) -> str:
    lines = [
        "# FOX.TV — raport audytu lokalnych artefaktów native",
        "",
        f"**Wynik:** `{result['status']}`  ",
        f"**Błędy blokujące:** {result['errors']}  ",
        f"**Ostrzeżenia:** {result['warnings']}  ",
        f"**Tryb strict dla wszystkich ABI:** `{str(result['strictAllAbis']).lower()}`  ",
        f"**Wygenerowano (UTC):** `{result['generatedAt']}`",
        "",
        "> PASS_WITH_FINDINGS oznacza poprawność sprawdzonych hashy/ELF/kontraktów, ale nie zamyka luk provenance ani runtime. Domyślnie wymaganie 16 KB blokuje ABI 64-bit; odstępstwa 32-bit są jawnie raportowane. `--strict-all-abis` zmienia je w błędy.",
        "",
        "## ELF",
        "",
        "| Artefakt | ABI | Profile / linkage | Class / machine | LOAD align | RELRO / NOW | DT_NEEDED | JNI |",
        "|---|---|---|---|---|---|---|---|",
    ]
    elf_records = [record for record in result["records"] if record["kind"] == "elf"]
    for record in elf_records:
        alignments = sorted(
            {segment["alignment"] for segment in record["loadSegments"]}
        )
        relro_status = (
            "N/A (static)"
            if record["relro"] is None
            else "PASS" if record["relro"] else "BRAK"
        )
        now_status = (
            "N/A (static)"
            if record["bindNow"] is None
            else "PASS" if record["bindNow"] else "BRAK"
        )
        lines.append(
            "| `{artifact}` | `{abi}` | `{profile}` / `{linkage}` | "
            "`{elfClass}` / `{machine}` | `{align}` | `{relro}` / `{now}` | "
            "`{needed}` | `{jni}` |".format(
                artifact=markdown_escape(record["artifact"]),
                abi=record["abi"],
                profile=record["profile"],
                linkage=record["linkage"],
                elfClass=record["elfClass"],
                machine=markdown_escape(record["machine"]),
                align=", ".join(hex(value) for value in alignments),
                relro=relro_status,
                now=now_status,
                needed=markdown_escape(", ".join(record["needed"]) or "—"),
                jni=len(record["jniSymbols"]),
            )
        )
    if not elf_records:
        lines.append("| — | — | — | — | — | — | — | — |")

    lines.extend(
        [
            "",
            "## Kontenery i archiwa",
            "",
            "| Artefakt | Typ | SHA-256 | Native / obiekty | Provenance |",
            "|---|---|---|---:|---|",
        ]
    )
    for record in result["records"]:
        if record["kind"] == "container":
            count = record.get("nativeEntries", 0)
            provenance = record.get("provenanceStatus", "n/d")
            lines.append(
                f"| `{markdown_escape(record['artifact'])}` | `{record['containerType']}` | "
                f"`{record['sha256']}` | {count} | `{provenance}` |"
            )
        elif record["kind"] == "static-archive":
            provenance = record.get("provenanceStatus", "candidate")
            lines.append(
                f"| `{markdown_escape(record['artifact'])}` | `static-archive` | "
                f"`{record['sha256']}` | {record['elfObjects']} | `{provenance}` |"
            )

    lines.extend(
        [
            "",
            "## Findings",
            "",
            "| Waga | Kod | Artefakt | Opis |",
            "|---|---|---|---|",
        ]
    )
    for finding in result["findings"]:
        lines.append(
            f"| **{finding['severity']}** | `{finding['code']}` | "
            f"`{markdown_escape(finding['artifact'])}` | {markdown_escape(finding['message'])} |"
        )
    if not result["findings"]:
        lines.append("| — | — | — | Brak |")

    optional = result["optionalInputs"]
    lines.extend(
        [
            "",
            "## Granice dowodu",
            "",
            f"- Finalny APK: `{'sprawdzony' if optional.get('apk') else 'NIE SPRAWDZONY'}`.",
            f"- Kandydackie AAR (`--candidate-aar`; kontener AAR i osadzone ELF), liczba wejść: `{len(optional.get('candidateAars', []))}`.",
            f"- Zbudowane outputy Dovi: `{'sprawdzone' if optional.get('doviBuiltRoot') else 'NIE SPRAWDZONE'}`.",
            "- Źródło i statyczny build FFmpeg (`--ffmpeg-source-dir` + "
            "`--ffmpeg-build-dir`; odrębny audyt, niezależny od AAR): "
            f"`{'PODANO DO AUDYTU' if optional.get('ffmpegSourceDir') and optional.get('ffmpegBuildDir') else 'NIE PODANO — NIE SPRAWDZONO'}`.",
            "- Domyślny zakres obejmuje wyłącznie jawnie zadeklarowane lokalne AAR, direct SO i libdovi.a. Native z Maven/Chaquopy wymaga finalnego APK.",
            "- Audyt statyczny nie jest testem uruchomienia JNI, dekodowania, TorrServer ani odtwarzania na urządzeniu.",
            "",
        ]
    )
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    script_root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=script_root)
    parser.add_argument("--lock", type=Path)
    parser.add_argument("--readelf", type=Path)
    parser.add_argument("--ar", type=Path)
    parser.add_argument("--nm", type=Path)
    parser.add_argument("--strict-all-abis", action="store_true")
    parser.add_argument("--apk", type=Path, help="Opcjonalny finalny APK lub split APK")
    parser.add_argument(
        "--candidate-aar",
        type=Path,
        action="append",
        default=[],
        help="Jawny wygenerowany AAR do kontroli ZIP/ABI/ELF; opcję można powtarzać",
    )
    parser.add_argument(
        "--dovi-built-root",
        type=Path,
        help="Jawny katalog zawierający po jednym libdovi_bridge.so dla czterech ABI",
    )
    parser.add_argument("--ffmpeg-source-dir", type=Path)
    parser.add_argument("--ffmpeg-build-dir", type=Path)
    parser.add_argument("--report-json", type=Path)
    parser.add_argument("--report-markdown", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = args.root.resolve()
    lock_path = (
        args.lock.resolve()
        if args.lock
        else root / "tools/native/NATIVE_ARTIFACTS.lock.json"
    )
    if bool(args.ffmpeg_source_dir) != bool(args.ffmpeg_build_dir):
        print(
            "ERROR: --ffmpeg-source-dir i --ffmpeg-build-dir muszą być podane razem",
            file=sys.stderr,
        )
        return 2
    try:
        auditor = NativeAuditor(
            root=root,
            lock_path=lock_path,
            readelf=locate_tool(args.readelf, "llvm-readelf", "readelf"),
            ar=locate_tool(args.ar, "llvm-ar", "ar"),
            nm=locate_tool(args.nm, "llvm-nm", "nm"),
            strict_all_abis=args.strict_all_abis,
        )
        auditor.validate_lock()
        auditor.audit_declared_inventory()
        auditor.audit_source_contracts()
        auditor.audit_locked_artifacts()
        optional_inputs = {
            "apk": str(args.apk.resolve()) if args.apk else None,
            "candidateAars": [str(path.resolve()) for path in args.candidate_aar],
            "doviBuiltRoot": (
                str(args.dovi_built_root.resolve()) if args.dovi_built_root else None
            ),
            "ffmpegSourceDir": (
                str(args.ffmpeg_source_dir.resolve()) if args.ffmpeg_source_dir else None
            ),
            "ffmpegBuildDir": (
                str(args.ffmpeg_build_dir.resolve()) if args.ffmpeg_build_dir else None
            ),
        }
        if args.apk:
            auditor.audit_apk(args.apk)
        for candidate_aar in args.candidate_aar:
            auditor.audit_candidate_aar(candidate_aar)
        if args.dovi_built_root:
            auditor.audit_dovi_built_root(args.dovi_built_root)
        if args.ffmpeg_source_dir and args.ffmpeg_build_dir:
            auditor.audit_ffmpeg_candidate(args.ffmpeg_source_dir, args.ffmpeg_build_dir)
        auditor.audit_dependency_closure()
        auditor.audit_collisions()
        result = auditor.result(optional_inputs)
    except (OSError, AuditFailure, KeyError, TypeError, ValueError) as error:
        print(f"ERROR: audit configuration failed: {error}", file=sys.stderr)
        return 2

    json_text = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    markdown_text = render_markdown(result)
    if args.report_json:
        args.report_json.parent.mkdir(parents=True, exist_ok=True)
        args.report_json.write_text(json_text, encoding="utf-8")
    if args.report_markdown:
        args.report_markdown.parent.mkdir(parents=True, exist_ok=True)
        args.report_markdown.write_text(markdown_text, encoding="utf-8")

    for finding in result["findings"]:
        print(
            f"{finding['severity']} {finding['code']} "
            f"{finding['artifact']}: {finding['message']}"
        )
    print(
        f"NATIVE_AUDIT={result['status']} "
        f"errors={result['errors']} warnings={result['warnings']} "
        f"records={len(result['records'])}"
    )
    return 1 if result["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
