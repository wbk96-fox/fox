"""Directory listings, resolved playback and log routing.

FanFilm's shim collects ``xbmcplugin.addDirectoryItem`` calls into a
``PluginDirectory`` and hands it to whatever callback
``fake_api.set_print_list_callback`` registered — upstream registers a terminal
printer. FOX.TV registers a serialiser instead, so the FanFilm browser screen
renders the plugin's real directory.

Three plugin outcomes must also reach Kotlin:

``xbmcplugin.setResolvedUrl``  the plugin resolved a playable item
``xbmc.Player().play``          the plugin started playback itself
``xbmc.executebuiltin``         PlayMedia / RunPlugin / Container.Update

All of them are tagged with the run id so a superseded run cannot hijack the UI.
"""

from __future__ import annotations

import json
import threading
from typing import Any, Dict, List, Optional

from . import bridge, ui

_installed = False
_lock = threading.RLock()

#: Set by runner.py; marks that the current run produced a terminal outcome
#: (directory, resolved url, playback) so the runner does not also report
#: "finished with nothing".
_state = threading.local()


def mark_terminal() -> None:
    _state.terminal = True


def clear_terminal() -> None:
    if hasattr(_state, "terminal"):
        del _state.terminal


def had_terminal() -> bool:
    return bool(getattr(_state, "terminal", False))


# ---------------------------------------------------------------------------
# serialisation
# ---------------------------------------------------------------------------

_INFO_GETTERS = (
    ("title", "getTitle"),
    ("originalTitle", "getOriginalTitle"),
    ("plot", "getPlot"),
    ("plotOutline", "getPlotOutline"),
    ("tagline", "getTagLine"),
    ("mpaa", "getMpaa"),
    ("mediaType", "getMediaType"),
    ("tvShowTitle", "getTVShowTitle"),
)

_NUMERIC_GETTERS = (
    ("year", "getYear"),
    ("rating", "getRating"),
    ("duration", "getDuration"),
    ("season", "getSeason"),
    ("episode", "getEpisode"),
    ("playCount", "getPlayCount"),
)

_ART_KEYS = ("thumb", "poster", "fanart", "banner", "clearlogo", "landscape", "icon")


def _call(target: Any, name: str) -> Any:
    getter = getattr(target, name, None)
    if not callable(getter):
        return None
    try:
        return getter()
    except Exception:  # noqa: BLE001 - a single bad tag must not drop the row
        return None


def item_to_dict(entry: Any) -> Dict[str, Any]:
    """Serialise one ``xbmcplugin._Item``."""
    list_item = entry.item
    payload: Dict[str, Any] = {
        "url": str(entry.url or ""),
        "folder": bool(entry.folder),
        "label": _call(list_item, "getLabel") or "",
        "label2": _call(list_item, "getLabel2") or "",
        "art": {},
        "info": {},
        "uniqueIds": {},
        "contextMenu": [],
    }

    for key in _ART_KEYS:
        try:
            value = list_item.getArt(key)
        except Exception:  # noqa: BLE001
            value = None
        if value:
            payload["art"][key] = str(value)

    tag = _call(list_item, "getVideoInfoTag")
    if tag is not None:
        for key, getter in _INFO_GETTERS:
            value = _call(tag, getter)
            if value:
                payload["info"][key] = str(value)
        for key, getter in _NUMERIC_GETTERS:
            value = _call(tag, getter)
            if value in (None, "", 0, 0.0):
                continue
            payload["info"][key] = value
        for service in ("imdb", "tmdb", "tvdb", "trakt"):
            try:
                unique = tag.getUniqueID(service)
            except Exception:  # noqa: BLE001
                unique = None
            if unique:
                payload["uniqueIds"][service] = str(unique)

    menu = getattr(list_item, "_ListItem__menu", None)
    if isinstance(menu, list):
        payload["contextMenu"] = [
            {"label": str(label), "action": str(action)} for label, action in menu
        ]

    return payload


def directory_to_dict(directory: Any, run_id: int) -> Dict[str, Any]:
    return {
        "runId": int(run_id),
        "category": str(getattr(directory, "category", "") or ""),
        "view": str(getattr(directory, "view", "") or ""),
        "outcome": "EMPTY_DIRECTORY" if not getattr(directory, "items", []) else "SUCCESS_DIRECTORY",
        "items": [item_to_dict(entry) for entry in getattr(directory, "items", [])],
    }


# ---------------------------------------------------------------------------
# callbacks
# ---------------------------------------------------------------------------

def _on_directory(directory: Any) -> None:
    run_id = ui.current_run()
    if run_id and bridge.is_cancelled(run_id):
        bridge.log(f"dropping directory for cancelled run {run_id}", bridge.LOG_DEBUG)
        return
    host = bridge.host()
    if host is None:
        return
    mark_terminal()
    try:
        payload = directory_to_dict(directory, run_id)
        host.onDirectory(json.dumps(payload, ensure_ascii=False))
    except Exception as exc:  # noqa: BLE001
        bridge.error(f"failed to deliver directory: {exc}")


def _on_log(message: str, level: int) -> None:
    bridge.log(str(message), int(level))


def _set_resolved_url(handle: int, succeeded: bool, listitem: Any) -> None:
    run_id = ui.current_run()
    if run_id and bridge.is_cancelled(run_id):
        return
    host = bridge.host()
    if host is None:
        return
    mark_terminal()
    url = _call(listitem, "getPath") or ""
    label = _call(listitem, "getLabel") or ""
    mime = ""
    properties: Dict[str, str] = {}
    prop_map = getattr(listitem, "_ListItem__prop", None)
    if isinstance(prop_map, dict):
        properties = {str(k): str(v) for k, v in prop_map.items()}
        mime = properties.get("mimetype", "")
    try:
        host.onResolved(
            int(run_id),
            bool(succeeded),
            str(url),
            str(label),
            str(mime),
            json.dumps(properties, ensure_ascii=False),
        )
    except Exception as exc:  # noqa: BLE001
        bridge.error(f"failed to deliver resolved url: {exc}")


def _player_play(
    _self,
    item: Any = "",
    listitem: Any = None,
    windowed: bool = False,
    startpos: int = -1,
    *args: Any,
    **kwargs: Any,
) -> None:
    run_id = ui.current_run()
    if run_id and bridge.is_cancelled(run_id):
        return
    host = bridge.host()
    if host is None:
        return
    mark_terminal()
    url = item if isinstance(item, str) else ""
    if not url and listitem is not None:
        url = _call(listitem, "getPath") or ""
    properties: Dict[str, str] = {}
    if listitem is not None:
        prop_map = getattr(listitem, "_ListItem__prop", None)
        if isinstance(prop_map, dict):
            properties = {str(k): str(v) for k, v in prop_map.items()}
    try:
        host.onPlay(int(run_id), str(url), json.dumps(properties, ensure_ascii=False))
    except Exception as exc:  # noqa: BLE001
        bridge.error(f"failed to deliver playback request: {exc}")


def _executebuiltin(command: str, wait: bool = False, *args: Any, **kwargs: Any) -> None:
    """Handle the builtins FanFilm actually issues; log the rest.

    Kodi's builtin namespace is enormous. Only the commands the vendored addons
    use are implemented, and anything else is logged at debug rather than
    silently swallowed, so an unhandled builtin is discoverable.
    """
    text = str(command or "").strip()
    lowered = text.lower()
    host = bridge.host()

    def argument() -> str:
        start = text.find("(")
        end = text.rfind(")")
        if start < 0 or end <= start:
            return ""
        return text[start + 1:end].strip().strip("'\"")

    if lowered.startswith("playmedia("):
        if host is not None:
            mark_terminal()
            host.onPlay(int(ui.current_run()), argument(), "{}")
        return
    if lowered.startswith("runplugin(") or lowered.startswith("container.update("):
        target = argument().split(",")[0].strip().strip("'\"")
        if target and host is not None:
            try:
                host.onNavigate(int(ui.current_run()), target,
                                lowered.startswith("container.update("))
            except Exception as exc:  # noqa: BLE001
                bridge.error(f"failed to deliver navigation request: {exc}")
        return
    if lowered.startswith("container.refresh"):
        if host is not None:
            try:
                host.onRefresh(int(ui.current_run()))
            except Exception as exc:  # noqa: BLE001
                bridge.error(f"failed to deliver refresh request: {exc}")
        return
    if lowered.startswith("notification("):
        parts = [part.strip().strip("'\"") for part in argument().split(",")]
        heading = parts[0] if parts else ""
        message = parts[1] if len(parts) > 1 else ""
        if host is not None:
            host.notification(heading, message, ui.NOTIFY_INFO, 5000)
        return
    if lowered.startswith(("action(", "dialog.close(", "activatewindow(", "setfocus(", "skin.")):
        # Purely skin/window level; there is no equivalent in FOX.TV's UI and the
        # plugin does not depend on the effect.
        bridge.log(f"ignoring skin builtin: {text}", bridge.LOG_DEBUG)
        return

    bridge.log(f"unhandled executebuiltin: {text}", bridge.LOG_DEBUG)


def install() -> None:
    """Register the callbacks and replace the terminal-oriented members."""
    global _installed
    with _lock:
        if _installed:
            return

        import xbmc
        import xbmcplugin
        from lib.fake import fake_api

        fake_api.set_print_list_callback(_on_directory)
        fake_api.set_print_log_callback(_on_log)

        xbmcplugin.setResolvedUrl = _set_resolved_url
        xbmc.Player.play = _player_play
        xbmc.executebuiltin = _executebuiltin

        _installed = True
        bridge.log("directory/playback callbacks installed", bridge.LOG_INFO)


def reset_directory() -> None:
    """Start a fresh ``PluginDirectory`` and reset FanFilm navigation context for the next run.

    ``xbmcplugin._directory`` is module-level state shared by every invocation,
    exactly as in Kodi. The runner serialises plugin runs, so resetting here is
    safe and prevents one run's items leaking into the next.
    """
    import xbmcplugin

    xbmcplugin._directory = xbmcplugin.PluginDirectory()
    # The upstream addon resets this only as part of Kodi's process lifecycle.
    # FOX.TV deliberately keeps one interpreter alive, so clear the module-level
    # navigation state explicitly before the next invocation.
    try:
        from lib.ff.menu import KodiDirectory
        KodiDirectory.THIS_URL = None
        KodiDirectory.INFO = None
        KodiDirectory.REFRESH_DONE = False
        KodiDirectory.CREATED = False
        KodiDirectory.FOCUS_INDEX = -1
        KodiDirectory.CONTENT = ()
    except Exception as exc:  # noqa: BLE001 - reset must never prevent a run
        bridge.warn(f"FanFilm per-run menu reset failed: {exc}")


def current_items() -> List[Dict[str, Any]]:
    import xbmcplugin

    return [item_to_dict(entry) for entry in xbmcplugin._directory.items]


def current_category() -> Optional[str]:
    import xbmcplugin

    return getattr(xbmcplugin._directory, "category", None)
