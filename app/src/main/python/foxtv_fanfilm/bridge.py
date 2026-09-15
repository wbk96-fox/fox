"""Access to the Kotlin side of the bridge.

Every call from Python into FOX.TV goes through :func:`host`, which resolves
``com.foxtv.app.core.fanfilm.FanFilmBridge`` once and caches it. The class
exposes only ``@JvmStatic`` members so Chaquopy can call them without an
instance.

Log levels mirror Kodi's ``xbmc.LOGDEBUG..LOGFATAL`` so the shim's own
``xbmc.log`` calls map straight onto Android log priorities.
"""

from __future__ import annotations

import threading
from typing import Any, Optional

LOG_DEBUG = 0
LOG_INFO = 1
LOG_WARNING = 2
LOG_ERROR = 3
LOG_FATAL = 4

_BRIDGE_CLASS = "com.foxtv.app.core.fanfilm.FanFilmBridge"

_lock = threading.Lock()
_host: Optional[Any] = None
_unavailable_reason: Optional[str] = None


def host() -> Optional[Any]:
    """Return the Kotlin bridge class, or ``None`` when running outside the app.

    Returning ``None`` instead of raising keeps the module importable in a plain
    CPython process, which is what the unit tests use.
    """
    global _host, _unavailable_reason
    if _host is not None:
        return _host
    with _lock:
        if _host is not None:
            return _host
        if _unavailable_reason is not None:
            return None
        try:
            from java import jclass  # type: ignore[import-not-found]
        except ImportError as exc:  # not running under Chaquopy
            _unavailable_reason = f"java bridge unavailable: {exc}"
            return None
        try:
            _host = jclass(_BRIDGE_CLASS)
        except Exception as exc:  # noqa: BLE001 - any failure means "no host"
            _unavailable_reason = f"{_BRIDGE_CLASS} unavailable: {exc}"
            return None
        return _host


def has_host() -> bool:
    return host() is not None


def log(message: str, level: int = LOG_DEBUG) -> None:
    """Forward a log line to Android, falling back to stdout."""
    bridge = host()
    if bridge is None:
        print(f"[fanfilm:{level}] {message}")
        return
    try:
        bridge.log(str(message), int(level))
    except Exception:  # noqa: BLE001 - logging must never raise
        print(f"[fanfilm:{level}] {message}")


def warn(message: str) -> None:
    log(message, LOG_WARNING)


def error(message: str) -> None:
    log(message, LOG_ERROR)


def is_cancelled(run_id: int) -> bool:
    """Whether Kotlin has cancelled *run_id*.

    Polled from FanFilm's own scan loop through the injected progress dialog, so
    cancellation actually stops the underlying work rather than just detaching
    the UI.
    """
    if run_id <= 0:
        return False
    bridge = host()
    if bridge is None:
        return False
    try:
        return bool(bridge.isRunCancelled(int(run_id)))
    except Exception:  # noqa: BLE001
        return False


def next_run_id() -> int:
    bridge = host()
    if bridge is None:
        return 0
    try:
        return int(bridge.nextRunId())
    except Exception:  # noqa: BLE001
        return 0
