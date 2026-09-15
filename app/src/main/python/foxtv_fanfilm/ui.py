"""Route FanFilm's interactive Kodi surface to Android.

FanFilm's shim implements ``xbmcgui.Dialog``, ``xbmc.Keyboard``,
``xbmcgui.DialogProgress`` and friends as console/no-op objects — correct for
its desktop mode, useless inside an Android TV app. This module swaps the
members that must reach a real user for implementations that call into Kotlin.

Contract with Kotlin
--------------------
Every request carries the ``run_id`` of the plugin invocation that produced it.
Kotlin answers with a JSON envelope::

    {"state": "ok" | "cancelled" | "stale" | "timeout" | "unavailable",
     "value": <payload>}

* ``ok``          the user answered; ``value`` holds the answer
* ``cancelled``   the user dismissed the dialog
* ``stale``       the request belongs to a superseded run — treat as cancelled
* ``timeout``     nobody answered in time — treat as cancelled
* ``unavailable`` no UI is attached (headless/unit test) — fall back to a default

Anything other than ``ok`` maps onto the value Kodi returns when a user cancels,
so FanFilm takes its normal "user backed out" path instead of seeing a bogus
positive answer.

Blocking rules
--------------
A dialog blocks the calling Python worker thread only. The bridge never holds
the plugin execution lock while waiting for a user (see
:func:`foxtv_fanfilm.runner.released_lock`), so a second run can supersede the
first and a cancel actually lands.
"""

from __future__ import annotations

import json
import threading
from typing import Any, Dict, List, Optional, Sequence, Tuple

from . import bridge

#: Set by runner.py for the duration of a plugin invocation.
_current = threading.local()

#: Installed once; guards against double patching when the module reloads.
_installed = False

NOTIFY_INFO = "info"
NOTIFY_WARNING = "warning"
NOTIFY_ERROR = "error"


def set_current_run(run_id: int) -> None:
    _current.run_id = int(run_id)


def clear_current_run() -> None:
    if hasattr(_current, "run_id"):
        del _current.run_id


def current_run() -> int:
    return int(getattr(_current, "run_id", 0))


def _unwrap(raw: Any) -> Tuple[str, Any]:
    """Split Kotlin's JSON envelope into ``(state, value)``."""
    if raw is None:
        return "unavailable", None
    try:
        payload = json.loads(str(raw))
    except (TypeError, ValueError):
        return "unavailable", None
    if not isinstance(payload, dict):
        return "unavailable", None
    return str(payload.get("state", "unavailable")), payload.get("value")


def _ask(method: str, *args: Any) -> Tuple[str, Any]:
    """Invoke a blocking Kotlin dialog method with the lock released."""
    host = bridge.host()
    if host is None:
        return "unavailable", None
    run_id = current_run()
    if run_id and bridge.is_cancelled(run_id):
        return "cancelled", None

    from .runner import released_lock

    try:
        with released_lock():
            raw = getattr(host, method)(run_id, *args)
    except Exception as exc:  # noqa: BLE001 - a UI failure must not kill the scan
        bridge.error(f"dialog {method} failed: {exc}")
        return "unavailable", None
    return _unwrap(raw)


# ---------------------------------------------------------------------------
# xbmcgui.Dialog
# ---------------------------------------------------------------------------

def _dialog_ok(_self, heading: str = "", message: str = "", *args: Any, **kwargs: Any) -> bool:
    state, _ = _ask("dialogOk", str(heading), _join_lines(message, args))
    return state == "ok"


def _dialog_yesno(
    _self,
    heading: str = "",
    message: str = "",
    nolabel: str = "",
    yeslabel: str = "",
    *args: Any,
    **kwargs: Any,
) -> bool:
    state, value = _ask(
        "dialogYesNo",
        str(heading),
        _join_lines(message, args),
        str(kwargs.get("yeslabel", yeslabel) or ""),
        str(kwargs.get("nolabel", nolabel) or ""),
    )
    if state != "ok":
        return False
    return bool(value)


def _dialog_select(
    _self,
    heading: str = "",
    list: Sequence[Any] = (),  # noqa: A002 - Kodi's parameter name
    autoclose: int = 0,
    preselect: int = -1,
    useDetails: bool = False,  # noqa: N803 - Kodi's parameter name
    *args: Any,
    **kwargs: Any,
) -> int:
    options = _labels(list)
    state, value = _ask(
        "dialogSelect",
        str(heading),
        json.dumps(options, ensure_ascii=False),
        int(preselect),
        False,
    )
    if state != "ok":
        return -1
    try:
        index = int(value)
    except (TypeError, ValueError):
        return -1
    return index if 0 <= index < len(options) else -1


def _dialog_multiselect(
    _self,
    heading: str = "",
    options: Sequence[Any] = (),
    autoclose: int = 0,
    preselect: Optional[Sequence[int]] = None,
    useDetails: bool = False,  # noqa: N803
    *args: Any,
    **kwargs: Any,
) -> Optional[List[int]]:
    labels = _labels(options)
    state, value = _ask(
        "dialogMultiSelect",
        str(heading),
        json.dumps(labels, ensure_ascii=False),
        json.dumps(list(preselect or [])),
    )
    if state != "ok" or not isinstance(value, list):
        return None
    return [int(i) for i in value if isinstance(i, (int, float)) and 0 <= int(i) < len(labels)]


def _dialog_input(
    _self,
    heading: str = "",
    defaultt: str = "",  # noqa: N803 - Kodi's parameter name (sic)
    type: int = 0,  # noqa: A002
    option: int = 0,
    autoclose: int = 0,
    *args: Any,
    **kwargs: Any,
) -> str:
    state, value = _ask(
        "dialogInput",
        str(heading),
        str(defaultt or ""),
        int(type),
    )
    if state != "ok" or value is None:
        return ""
    return str(value)


def _dialog_numeric(
    _self,
    type: int = 0,  # noqa: A002
    heading: str = "",
    defaultt: str = "",  # noqa: N803
    bMask: bool = False,  # noqa: N803
    *args: Any,
    **kwargs: Any,
) -> str:
    state, value = _ask("dialogNumeric", str(heading), str(defaultt or ""), int(type))
    if state != "ok" or value is None:
        return ""
    return str(value)


def _dialog_browse(
    _self,
    type: int = 0,  # noqa: A002
    heading: str = "",
    shares: str = "",
    mask: str = "",
    useThumbs: bool = False,  # noqa: N803
    treatAsFolder: bool = False,  # noqa: N803
    defaultt: str = "",  # noqa: N803
    *args: Any,
    **kwargs: Any,
) -> str:
    # FOX.TV does not expose a filesystem browser to addons: FanFilm only uses
    # this for its download destination, which is not reachable from FOX.TV's UI.
    # Returning the Kodi "cancelled" value keeps the caller on its normal path.
    bridge.warn(f"Dialog.browse({heading!r}) is not supported by FOX.TV")
    return str(defaultt or "")


def _dialog_notification(
    _self,
    heading: str = "",
    message: str = "",
    icon: str = "",
    time: int = 5000,
    sound: bool = True,
    *args: Any,
    **kwargs: Any,
) -> None:
    host = bridge.host()
    if host is None:
        bridge.log(f"[notification] {heading}: {message}", bridge.LOG_INFO)
        return
    try:
        host.notification(str(heading), str(message), _icon_to_level(icon), int(time))
    except Exception as exc:  # noqa: BLE001
        bridge.warn(f"notification failed: {exc}")


def _dialog_textviewer(
    _self, heading: str = "", text: str = "", usemono: bool = False, *args: Any, **kwargs: Any
) -> None:
    _ask("dialogOk", str(heading), str(text))


def _dialog_contextmenu(_self, list: Sequence[Any] = (), *args: Any, **kwargs: Any) -> int:  # noqa: A002
    options = _labels(list)
    state, value = _ask(
        "dialogSelect", "", json.dumps(options, ensure_ascii=False), -1, False
    )
    if state != "ok":
        return -1
    try:
        index = int(value)
    except (TypeError, ValueError):
        return -1
    return index if 0 <= index < len(options) else -1


def _icon_to_level(icon: str) -> str:
    text = str(icon or "").upper()
    if "ERROR" in text:
        return NOTIFY_ERROR
    if "WARNING" in text:
        return NOTIFY_WARNING
    return NOTIFY_INFO


def _join_lines(message: Any, extra: Sequence[Any]) -> str:
    parts = [str(message or "")]
    parts.extend(str(item) for item in extra if item not in (None, ""))
    return "\n".join(part for part in parts if part)


def _labels(items: Sequence[Any]) -> List[str]:
    """Flatten a Kodi select list, which may hold strings or ``ListItem``s."""
    labels: List[str] = []
    for item in items or ():
        if isinstance(item, str):
            labels.append(item)
            continue
        getter = getattr(item, "getLabel", None)
        if callable(getter):
            try:
                label = getter() or ""
            except Exception:  # noqa: BLE001
                label = ""
            label2 = ""
            getter2 = getattr(item, "getLabel2", None)
            if callable(getter2):
                try:
                    label2 = getter2() or ""
                except Exception:  # noqa: BLE001
                    label2 = ""
            labels.append(f"{label} — {label2}" if label2 else label)
            continue
        labels.append(str(item))
    return labels


# ---------------------------------------------------------------------------
# xbmc.Keyboard
# ---------------------------------------------------------------------------

class _Keyboard:
    """``xbmc.Keyboard`` backed by the Android input dialog."""

    def __init__(self, line: str = "", heading: str = "", hidden: bool = False) -> None:
        self._text = str(line or "")
        self._heading = str(heading or "")
        self._hidden = bool(hidden)
        self._confirmed = False

    def doModal(self, autoclose: int = 0) -> None:  # noqa: N802 - Kodi API
        state, value = _ask(
            "dialogInput",
            self._heading,
            self._text,
            1 if self._hidden else 0,
        )
        if state == "ok" and value is not None:
            self._text = str(value)
            self._confirmed = True
        else:
            self._confirmed = False

    def setHeading(self, heading: str) -> None:  # noqa: N802
        self._heading = str(heading or "")

    def setDefault(self, line: str = "") -> None:  # noqa: N802
        self._text = str(line or "")

    def setHiddenInput(self, hidden: bool = False) -> None:  # noqa: N802
        self._hidden = bool(hidden)

    def getText(self) -> str:  # noqa: N802
        return self._text

    def isConfirmed(self) -> bool:  # noqa: N802
        return self._confirmed


# ---------------------------------------------------------------------------
# progress dialogs
# ---------------------------------------------------------------------------

class ProgressReporter:
    """Progress sink handed to FanFilm's ``get_sources``.

    Implements upstream's ``ProgressDialogProtocol``. ``iscanceled`` is the hook
    FanFilm's own scan loop polls, so returning ``True`` genuinely aborts the
    provider sweep rather than just hiding a dialog.
    """

    def __init__(self, run_id: int, label: str = "") -> None:
        self.run_id = int(run_id)
        self.label = label
        self._closed = False

    # ProgressDialogProtocol -------------------------------------------------
    def update(
        self,
        percent: int,
        message: str = "",
        *,
        providers: Optional[Sequence[str]] = None,
    ) -> None:
        if self._closed:
            return
        host = bridge.host()
        if host is None:
            return
        try:
            host.onScanProgress(
                self.run_id,
                max(0, min(100, int(percent))),
                str(message or ""),
                json.dumps(list(providers or []), ensure_ascii=False),
            )
        except Exception as exc:  # noqa: BLE001
            bridge.warn(f"progress update failed: {exc}")

    def iscanceled(self) -> bool:  # noqa: N802 - Kodi API spelling
        return bridge.is_cancelled(self.run_id)

    # Kodi DialogProgress compatibility ------------------------------------
    def create(self, heading: str = "", message: str = "") -> None:
        self.update(0, message or heading)

    def close(self) -> None:
        self._closed = True


class _BridgedProgress:
    """``xbmcgui.DialogProgress`` / ``DialogProgressBG`` replacement."""

    def __init__(self) -> None:
        self._reporter: Optional[ProgressReporter] = None

    def create(self, heading: str = "", message: str = "", *args: Any) -> None:
        self._reporter = ProgressReporter(current_run(), str(heading or ""))
        self._reporter.update(0, _join_lines(message, args))

    def update(self, percent: int = 0, message: str = "", *args: Any, **kwargs: Any) -> None:
        if self._reporter is None:
            self.create()
        assert self._reporter is not None
        self._reporter.update(percent, _join_lines(message, args))

    def iscanceled(self) -> bool:  # noqa: N802
        return bridge.is_cancelled(current_run())

    def close(self) -> None:
        if self._reporter is not None:
            self._reporter.close()
            self._reporter = None

    # DialogProgressBG extras
    def isFinished(self) -> bool:  # noqa: N802
        return self._reporter is None


# ---------------------------------------------------------------------------
# installation
# ---------------------------------------------------------------------------

def install() -> None:
    """Patch the shim's interactive members. Idempotent."""
    global _installed
    if _installed:
        return

    import xbmc
    import xbmcgui

    dialog = xbmcgui.Dialog
    dialog.ok = _dialog_ok
    dialog.yesno = _dialog_yesno
    dialog.select = _dialog_select
    dialog.multiselect = _dialog_multiselect
    dialog.input = _dialog_input
    dialog.numeric = _dialog_numeric
    dialog.browse = _dialog_browse
    dialog.browseSingle = _dialog_browse
    dialog.browseMultiple = _dialog_browse
    dialog.notification = _dialog_notification
    dialog.textviewer = _dialog_textviewer
    dialog.contextmenu = _dialog_contextmenu
    # yesnocustom adds a third button; FOX.TV renders it as a plain yes/no and
    # reports "custom" as cancelled, which is the branch FanFilm treats as
    # "user made no choice".
    dialog.yesnocustom = lambda self, heading="", message="", customlabel="", *a, **k: (
        1 if _dialog_yesno(self, heading, message) else 0
    )

    xbmcgui.DialogProgress = _BridgedProgress
    xbmcgui.DialogProgressBG = _BridgedProgress
    xbmc.Keyboard = _Keyboard

    _installed = True
    bridge.log("interactive Kodi surface bridged to Android", bridge.LOG_INFO)


def describe() -> Dict[str, bool]:
    """Which members were bridged — used by the Kodi API matrix test."""
    import xbmc
    import xbmcgui

    return {
        "Dialog.ok": xbmcgui.Dialog.ok is _dialog_ok,
        "Dialog.yesno": xbmcgui.Dialog.yesno is _dialog_yesno,
        "Dialog.select": xbmcgui.Dialog.select is _dialog_select,
        "Dialog.multiselect": xbmcgui.Dialog.multiselect is _dialog_multiselect,
        "Dialog.input": xbmcgui.Dialog.input is _dialog_input,
        "Dialog.numeric": xbmcgui.Dialog.numeric is _dialog_numeric,
        "Dialog.notification": xbmcgui.Dialog.notification is _dialog_notification,
        "Dialog.contextmenu": xbmcgui.Dialog.contextmenu is _dialog_contextmenu,
        "DialogProgress": xbmcgui.DialogProgress is _BridgedProgress,
        "DialogProgressBG": xbmcgui.DialogProgressBG is _BridgedProgress,
        "Keyboard": xbmc.Keyboard is _Keyboard,
    }
