"""One-time interpreter setup for the embedded FanFilm addon.

Called once from Kotlin (``FanFilmPythonRuntime.ensureStarted``) after the addon
tree has been materialised on disk. Responsibilities, in order:

1. put the addon libraries on ``sys.path`` in the order Kodi would,
2. point FanFilm's shim at the Kodi home FOX.TV created,
3. install the FOX.TV extensions (``special://`` resolver, Android dialogs,
   working settings writers, directory/log callbacks),
4. start the addon's ``service.py`` the way Kodi's ``xbmc.service`` extension
   point does,
5. report a machine-readable diagnostic so Kotlin can fail fast.

Import order matters. ``plugin.video.fanfilm`` must come before its own
``lib/fake`` directory so ``import lib.fake.fake_api`` resolves, and ``lib/fake``
must precede everything else so ``import xbmc`` finds the shim rather than
failing.
"""

from __future__ import annotations

import json
import os
import sys
import threading
import traceback
from pathlib import Path
from typing import Any, Dict, List, Optional

from . import bridge, paths
from .version import BRIDGE_VERSION

FANFILM_ID = "plugin.video.fanfilm"
RESOLVEURL_ID = "script.module.resolveurl"
KODISIX_ID = "script.module.kodi-six"
INPUTSTREAMHELPER_ID = "script.module.inputstreamhelper"
THEMEPACK_ID = "script.fanfilm.media"

_lock = threading.RLock()
_configured = False
_diagnostics: Dict[str, Any] = {}


class ConfigurationError(RuntimeError):
    """Raised when the addon tree is unusable; the message reaches the UI."""


def is_configured() -> bool:
    return _configured


def prepare_embedded_lifecycle() -> None:
    """Disable Kodi's periodic process-exit policy inside the long-lived host.

    Kodi can intentionally terminate the Python process after N invocations.
    In FOX.TV the interpreter is intentionally long-lived, so inheriting that
    policy turns a successful navigation into an artificial SystemExit and can
    invalidate the next UI run. The host owns lifecycle here.
    """
    try:
        from const import const
        const.core.exit_every_nth = 0
        const.core.widgets_exit_every_nth = 0
    except Exception as exc:  # noqa: BLE001
        bridge.warn(f"could not disable embedded FanFilm exit policy: {exc}")


def diagnostics() -> str:
    return json.dumps(_diagnostics, ensure_ascii=False)


def configure(root_dir: str, options_json: str = "") -> str:
    """Prepare the interpreter. Returns a JSON diagnostic; never raises.

    *root_dir* is the directory Kotlin installed the addon tree into. It doubles
    as the Kodi home, which keeps both of the shim's addon-lookup strategies
    (``<addons>/<id>`` relative to ``lib/fake``, and ``KODI_PATH/addons/<id>``)
    pointing at the same files.
    """
    global _configured, _diagnostics
    with _lock:
        if _configured:
            return diagnostics()
        try:
            options = json.loads(options_json) if options_json else {}
        except (TypeError, ValueError):
            options = {}
        try:
            _diagnostics = _configure_locked(Path(root_dir), options)
            _configured = True
        except Exception as exc:  # noqa: BLE001 - the report is the contract
            _diagnostics = {
                "ok": False,
                "bridgeVersion": BRIDGE_VERSION,
                "error": str(exc),
                "errorType": type(exc).__name__,
                "traceback": traceback.format_exc(limit=12),
            }
            bridge.error(f"FanFilm configure failed: {exc}")
        return diagnostics()


def _configure_locked(root: Path, options: Dict[str, Any]) -> Dict[str, Any]:
    addons = root / "addons"
    if not addons.is_dir():
        raise ConfigurationError(f"addon tree missing at {addons}")

    plugin_root = addons / FANFILM_ID
    _require_dir(plugin_root, FANFILM_ID)
    _require_file(plugin_root / "addon.xml", f"{FANFILM_ID}/addon.xml")
    _require_file(plugin_root / "default.py", f"{FANFILM_ID}/default.py")
    _require_file(plugin_root / "service.py", f"{FANFILM_ID}/service.py")
    _require_dir(plugin_root / "lib" / "fake", f"{FANFILM_ID}/lib/fake")
    _require_dir(addons / THEMEPACK_ID, THEMEPACK_ID)
    _require_dir(addons / RESOLVEURL_ID / "lib", f"{RESOLVEURL_ID}/lib")
    _require_dir(addons / KODISIX_ID / "libs", f"{KODISIX_ID}/libs")
    _require_dir(addons / INPUTSTREAMHELPER_ID / "lib", f"{INPUTSTREAMHELPER_ID}/lib")

    paths.prepare(root, addons)
    paths.ensure_writable()

    _extend_sys_path(root, addons, plugin_root)

    # From here on the shim is importable.
    from lib.fake import fake_api

    fake_api.KODI_PATH = root
    locale = str(options.get("locale") or "pl-PL")
    api_language = options.get("apiLanguage") or locale
    fake_api.LOCALE = locale.replace("_", "-")

    paths.install()

    from . import listing, platform_info, settings as settings_bridge, ui

    platform_info.install()
    ui.install()
    settings_bridge.install()
    listing.install()

    # inputstreamhelper lives in its own addon and is only importable once the
    # sys.path entries above are in place.
    from . import inputstream

    inputstream.install()

    _apply_locale(api_language)
    _seed_environment(options)

    from . import service as service_bridge

    service_started = service_bridge.start(plugin_root)

    report = {
        "ok": True,
        "bridgeVersion": BRIDGE_VERSION,
        "python": sys.version.split()[0],
        "kodiHome": str(root),
        "addons": _addon_versions(addons),
        "specialPaths": paths.describe(),
        "caBundle": str(paths.ca_bundle() or ""),
        "bridgedApis": ui.describe(),
        "platform": platform_info.describe(),
        "inputstream": inputstream.describe(),
        "serviceStarted": service_started,
        "settingsPath": str(settings_bridge.values_path()),
    }
    bridge.log(
        "FanFilm environment ready: "
        f"python={report['python']} addons={report['addons']} service={service_started}",
        bridge.LOG_INFO,
    )
    return report


def _require_dir(path: Path, label: str) -> None:
    if not path.is_dir():
        raise ConfigurationError(f"required directory missing: {label} ({path})")


def _require_file(path: Path, label: str) -> None:
    if not path.is_file():
        raise ConfigurationError(f"required file missing: {label} ({path})")


def _extend_sys_path(root: Path, addons: Path, plugin_root: Path) -> None:
    """Prepend the addon library directories, preserving Kodi's precedence.

    Entries are inserted at the front in reverse order so the resulting prefix of
    ``sys.path`` matches ``ordered`` exactly.
    """
    ordered: List[Path] = [
        plugin_root,                      # const.py, cdefs.py, lib package
        plugin_root / "lib" / "fake",     # xbmc* shim — must beat anything else
        plugin_root / "lib" / "3rd",      # attrs, cattrs, cfscrape, PTN, segno, ...
        addons / RESOLVEURL_ID / "lib",
        addons / KODISIX_ID / "libs",
        addons / INPUTSTREAMHELPER_ID / "lib",
    ]
    for entry in reversed(ordered):
        text = str(entry)
        if text in sys.path:
            sys.path.remove(text)
        sys.path.insert(0, text)


def _apply_locale(api_language: str) -> None:
    from lib.fake import fake_api

    try:
        fake_api.set_locale(api=str(api_language))
    except Exception as exc:  # noqa: BLE001
        bridge.warn(f"could not set FanFilm API language: {exc}")


def _seed_environment(options: Dict[str, Any]) -> None:
    """Environment variables the addon and its libraries read directly."""
    os.environ["FOXTV_FANFILM_EMBEDDED"] = "1"
    # requests/urllib3 honour these; pointing them at the installed bundle keeps
    # TLS verification on for code paths that build their own session.
    bundle = paths.ca_bundle()
    if bundle is not None:
        os.environ.setdefault("REQUESTS_CA_BUNDLE", str(bundle))
        os.environ.setdefault("SSL_CERT_FILE", str(bundle))
    # Kodi exports HOME; some libraries fall back to it for cache directories.
    os.environ.setdefault("HOME", str(paths.home()))
    os.environ["TMPDIR"] = str(paths.temp_dir())
    if options.get("verbose"):
        os.environ["FOXTV_FANFILM_VERBOSE"] = "1"


def _addon_versions(addons: Path) -> Dict[str, str]:
    """Read the installed version of every addon, straight from addon.xml."""
    import re

    pattern = re.compile(r'<addon\b[^>]*\bversion="([^"]+)"')
    versions: Dict[str, str] = {}
    for addon_dir in sorted(p for p in addons.iterdir() if p.is_dir()):
        manifest = addon_dir / "addon.xml"
        if not manifest.is_file():
            continue
        try:
            text = manifest.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        match = pattern.search(text)
        versions[addon_dir.name] = match.group(1) if match else "?"
    return versions


def shutdown() -> str:
    """Stop background work and flush state. Safe to call more than once."""
    global _configured
    report: Dict[str, Any] = {}
    with _lock:
        try:
            from . import service as service_bridge

            report["serviceStopped"] = service_bridge.stop()
        except Exception as exc:  # noqa: BLE001
            report["serviceStopped"] = False
            report["serviceError"] = str(exc)

        if _configured:
            try:
                from . import settings as settings_bridge

                report["settingsFlushed"] = settings_bridge.flush()
            except Exception as exc:  # noqa: BLE001
                report["settingsFlushed"] = False
                report["settingsError"] = str(exc)

        try:
            import gc

            report["collected"] = gc.collect()
        except Exception:  # noqa: BLE001
            pass

        bridge.log(f"FanFilm shutdown: {report}", bridge.LOG_INFO)
        return json.dumps(report, ensure_ascii=False)


def plugin_root() -> Path:
    return paths.addon_dir(FANFILM_ID)


def optional_module(name: str) -> Optional[Any]:
    """Import *name* if it is available, otherwise report and return ``None``.

    Used for the addons FOX.TV deliberately does not bundle, so a missing
    optional dependency produces one explicit log line rather than a traceback
    from deep inside FanFilm.
    """
    try:
        return __import__(name)
    except ImportError as exc:
        bridge.log(f"optional module {name!r} unavailable: {exc}", bridge.LOG_DEBUG)
        return None
