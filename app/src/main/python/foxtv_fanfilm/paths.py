"""``special://`` filesystem for FOX.TV.

Kodi addresses everything through the ``special://`` protocol. FanFilm's
upstream shim (``lib/fake/xbmcvfs.translatePath``) only understands four exact
strings plus ``special://logpath``; anything with a sub-path, and the
``profile``/``masterprofile``/``skin``/``xbmc`` roots the vendored addons
actually use, would be handed to ``open()`` verbatim and fail.

This module owns the whole translation instead:

===============================  =========================================
``special://``                    <home>
``special://home/...``            <home>/...
``special://xbmc/...``            <home>/system-ish install tree
``special://xbmcbin/...``         binary-addon tree (empty on Android)
``special://userdata/...``        <home>/userdata/...
``special://masterprofile/...``   <home>/userdata/...
``special://profile/...``         <home>/userdata/... (single profile)
``special://database/...``        <home>/userdata/Database/...
``special://thumbnails/...``      <home>/userdata/Thumbnails/...
``special://temp/...``            <home>/temp/...
``special://logpath/...``         <home>/temp/...
``special://skin/...``            <home>/addons/skin.foxtv/...
``special://frameworks/...``      <home>/addons/
===============================  =========================================

Two mappings are deliberately not naive:

``special://xbmc/system/certs/cacert.pem``
    ResolveURL passes this to ``requests`` as a CA bundle. Kodi ships its own
    bundle there; FOX.TV points it at ``certifi``, which Chaquopy installs, so
    TLS verification stays enabled instead of being silently skipped.

``special://xbmcbin``
    Kodi's binary-addon directory. Android has no Kodi binary addons, so the
    directory exists but is empty; :mod:`.inputstream` answers
    ``inputstreamhelper``'s capability questions instead of pretending a
    ``.so`` is installed.

The layout is created once by :func:`prepare`; nothing else in the bridge is
allowed to build paths by string concatenation.
"""

from __future__ import annotations

import os
import threading
from pathlib import Path, PurePosixPath
from typing import Dict, Optional

from . import bridge

_lock = threading.RLock()

#: Kodi "home" for the embedded instance. Set by :func:`prepare`.
_home: Optional[Path] = None
#: Root the addon trees were installed into (read-only from Python's point of view).
_addons_root: Optional[Path] = None
#: Resolved CA bundle handed to ResolveURL.
_ca_bundle: Optional[Path] = None

_SKIN_ID = "skin.foxtv"

# Directories that must exist before FanFilm starts: it opens several of them
# without creating them first (kodidb reads kodi.log, settings writes
# addon_data, requests_cache creates its sqlite file, ...).
_REQUIRED_DIRS = (
    "userdata",
    "userdata/Database",
    "userdata/Thumbnails",
    "userdata/addon_data",
    "userdata/addon_data/plugin.video.fanfilm",
    "userdata/addon_data/script.module.resolveurl",
    "userdata/addon_data/script.module.inputstreamhelper",
    "temp",
    "cache",
    "system",
    "system/certs",
    f"addons/{_SKIN_ID}/media",
    "addons/packages",
    "binaddons",
)


class PathsNotReady(RuntimeError):
    """Raised when a translation is attempted before :func:`prepare`."""


def prepare(home: Path, addons_root: Path) -> Path:
    """Create the Kodi directory layout under *home* and remember the roots."""
    global _home, _addons_root, _ca_bundle
    with _lock:
        home = Path(home)
        for relative in _REQUIRED_DIRS:
            (home / relative).mkdir(parents=True, exist_ok=True)

        # lib/ff/kodidb.py reads kodi.log during import; an empty file is the
        # honest equivalent of a fresh Kodi install.
        log_file = home / "temp" / "kodi.log"
        if not log_file.exists():
            log_file.write_text("", encoding="utf-8")

        _home = home
        _addons_root = Path(addons_root)
        _ca_bundle = _install_ca_bundle(home)
        return home


def home() -> Path:
    if _home is None:
        raise PathsNotReady("special:// layout has not been prepared yet")
    return _home


def addons_root() -> Path:
    if _addons_root is None:
        raise PathsNotReady("special:// layout has not been prepared yet")
    return _addons_root


def addon_dir(addon_id: str) -> Path:
    return addons_root() / addon_id


def addon_data_dir(addon_id: str) -> Path:
    path = home() / "userdata" / "addon_data" / addon_id
    path.mkdir(parents=True, exist_ok=True)
    return path


def temp_dir() -> Path:
    path = home() / "temp"
    path.mkdir(parents=True, exist_ok=True)
    return path


def ca_bundle() -> Optional[Path]:
    return _ca_bundle


def _install_ca_bundle(home: Path) -> Optional[Path]:
    """Expose a real CA bundle at Kodi's ``system/certs/cacert.pem`` location.

    ResolveURL hands that path to ``requests`` as ``verify=``. A missing file
    makes requests raise, and "fixing" it by disabling verification would be a
    silent security downgrade, so the bundle shipped with ``certifi`` is copied
    into place (copied rather than symlinked: the source lives inside
    Chaquopy's asset-backed importer and has no stable filesystem identity).
    """
    target = home / "system" / "certs" / "cacert.pem"
    try:
        import certifi

        source = Path(certifi.where())
        if not source.is_file():
            bridge.warn(f"certifi bundle missing at {source}")
            return None
        if not target.exists() or target.stat().st_size != source.stat().st_size:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.read_bytes())
        return target
    except Exception as exc:  # noqa: BLE001 - keep startup alive, report loudly
        bridge.warn(f"could not install CA bundle: {exc}")
        return None


# ---------------------------------------------------------------------------
# translation
# ---------------------------------------------------------------------------

def _roots() -> Dict[str, Path]:
    base = home()
    userdata = base / "userdata"
    return {
        "home": base,
        "xbmc": base,
        "xbmcbin": base / "binaddons",
        "userdata": userdata,
        "masterprofile": userdata,
        "profile": userdata,
        "database": userdata / "Database",
        "thumbnails": userdata / "Thumbnails",
        "temp": base / "temp",
        "logpath": base / "temp",
        "skin": base / "addons" / _SKIN_ID,
        "frameworks": base / "addons",
    }


def translate(path: str) -> str:
    """Translate a ``special://`` URL into an absolute filesystem path.

    Non-``special://`` input is returned unchanged, matching Kodi. Unknown roots
    are reported and mapped under ``temp/unmapped/<root>`` so a future addon
    cannot silently write to the process working directory.
    """
    if not isinstance(path, str):
        return str(path)
    raw = path
    if not raw.lower().startswith("special://"):
        return raw

    remainder = raw[len("special://"):]
    # Kodi tolerates both special://temp and special://temp/
    root, _, tail = remainder.partition("/")
    root = root.lower()

    if not root:
        return str(home())

    roots = _roots()
    base = roots.get(root)
    if base is None:
        base = home() / "temp" / "unmapped" / root
        base.mkdir(parents=True, exist_ok=True)
        bridge.warn(f"unmapped special:// root {root!r} (from {raw!r}) → {base}")

    if not tail:
        return str(base)

    # Reject traversal: an addon must not be able to reach outside the
    # sandboxed Kodi home through special://.
    parts = [segment for segment in PurePosixPath(tail).parts if segment not in ("", ".")]
    if any(segment == ".." for segment in parts):
        bridge.warn(f"rejecting traversal in {raw!r}")
        return str(base)

    resolved = base.joinpath(*parts)
    return str(resolved)


def install() -> None:
    """Replace the shim's ``translatePath`` with :func:`translate`.

    ``xbmcvfs.translatePath`` is re-exported by ``xbmc`` and ``kodi_six``, so all
    three module objects are patched. Patching the function in place (rather
    than editing the vendored addon) keeps the upstream tree pristine and
    updatable.
    """
    import xbmcvfs

    xbmcvfs.translatePath = translate
    # Kodi ≤18 name, still used by a few ResolveURL plugins.
    xbmcvfs.translatePathleg = translate

    try:
        import xbmc

        if hasattr(xbmc, "translatePath"):
            xbmc.translatePath = translate
    except Exception as exc:  # noqa: BLE001
        bridge.warn(f"could not patch xbmc.translatePath: {exc}")

    try:
        from kodi_six import xbmcvfs as kodi_six_vfs

        kodi_six_vfs.translatePath = translate
    except Exception:
        # kodi_six wraps the real modules lazily; if it is not importable yet the
        # wrapper will pick up the patched xbmcvfs when it is.
        pass

    bridge.log(f"special:// resolver installed (home={home()})", bridge.LOG_INFO)


def describe() -> Dict[str, str]:
    """Root table, for diagnostics and tests."""
    return {f"special://{name}": str(path) for name, path in sorted(_roots().items())}


def ensure_writable() -> None:
    """Fail fast if the Kodi home is not writable.

    Android can hand back a read-only data directory in some restore scenarios;
    discovering that here produces one clear error instead of a scatter of
    sqlite and settings failures later.
    """
    probe = home() / "temp" / ".foxtv-write-probe"
    try:
        probe.write_text("ok", encoding="utf-8")
        probe.unlink()
    except OSError as exc:
        raise RuntimeError(f"Kodi home is not writable: {home()} ({exc})") from exc


def relative_to_home(path: str | os.PathLike[str]) -> str:
    """Render *path* relative to the Kodi home for log messages."""
    try:
        return str(Path(path).relative_to(home()))
    except (ValueError, PathsNotReady):
        return str(path)
