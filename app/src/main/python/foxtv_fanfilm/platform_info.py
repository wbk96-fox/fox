"""Truthful answers for ``getCondVisibility`` and ``getInfoLabel``.

FanFilm's shim answers every ``xbmc.getCondVisibility`` with ``True`` and every
``xbmc.getInfoLabel`` with ``""``. Both are wrong in ways that matter:

* ``getCondVisibility('System.Platform.Windows')`` returning ``True`` makes
  ``inputstreamhelper`` believe it is on Windows *and* Android *and* webOS. The
  Windows branch tries to download a Widevine CDM, which cannot work and which
  FOX.TV must not attempt (AGENTS.md §30).
* ``getCondVisibility('System.HasAddon(x)')`` returning ``True`` makes FanFilm
  offer integrations with addons that are not installed.
* ``getInfoLabel('System.BuildVersion')`` returning ``""`` breaks version
  comparisons in ResolveURL and inputstreamhelper.

Only the conditions and labels the vendored addons actually query are answered;
anything else is logged at debug level and answered conservatively (``False`` /
``""``) so an unhandled key is discoverable rather than silently fabricated.
"""

from __future__ import annotations

import re
import sys
import threading
from typing import Callable, Dict, Optional

from . import bridge, paths

_installed = False
_lock = threading.RLock()

#: Kodi version FanFilm should believe it is running under.
#:
#: The shim declares 22.3.4 for ``xbmcaddon.Addon('xbmc')`` and FanFilm's
#: ``lib/kodi.py`` branches on it (``kodi.version < 21`` picks the legacy
#: inputstream property names). Reporting the same number here keeps the two
#: consistent; FOX.TV implements the modern property set.
KODI_VERSION = "22.3.4"
KODI_MAJOR = 22

_HAS_ADDON = re.compile(r"System\.HasAddon\(([^)]*)\)", re.IGNORECASE)
_ADDON_ENABLED = re.compile(r"System\.AddonIsEnabled\(([^)]*)\)", re.IGNORECASE)


def _is_android() -> bool:
    # CPython on Android exposes sys.getandroidapilevel; this is the same test
    # FanFilm's own lib/ff/kotools.get_platform() uses.
    return hasattr(sys, "getandroidapilevel")


def _installed_addon(addon_id: str) -> bool:
    addon_id = addon_id.strip().strip("'\"")
    if not addon_id:
        return False
    try:
        return (paths.addons_root() / addon_id).is_dir()
    except Exception:  # noqa: BLE001
        return False


def cond_visibility(condition: str) -> bool:
    text = str(condition or "").strip()
    lowered = text.lower()

    if lowered == "system.platform.android":
        return _is_android()
    if lowered in ("system.platform.linux", "system.platform.linux.raspberrypi"):
        # Android is Linux, but Kodi reports them as distinct platforms and
        # inputstreamhelper relies on that distinction.
        return not _is_android() and sys.platform.startswith("linux")
    if lowered == "system.platform.windows":
        return sys.platform.startswith("win")
    if lowered in ("system.platform.osx", "system.platform.darwin"):
        return sys.platform == "darwin"
    if lowered == "system.platform.webos":
        return False
    if lowered in ("system.platform.tvos", "system.platform.ios"):
        return False

    if match := _HAS_ADDON.search(text):
        return _installed_addon(match.group(1))
    if match := _ADDON_ENABLED.search(text):
        return _installed_addon(match.group(1))

    if lowered.startswith("window.isvisible("):
        # FOX.TV renders progress in its own UI; the plugin only uses this to
        # decide whether to reuse an existing dialog.
        return False
    if lowered == "player.playing":
        return _player_playing()
    if lowered == "listitem.isresumable":
        # Resume state belongs to FOX.TV's watch-progress store, not to the addon.
        return False

    bridge.log(f"unhandled getCondVisibility({text!r}) → False", bridge.LOG_DEBUG)
    return False


def _player_playing() -> bool:
    host = bridge.host()
    if host is None:
        return False
    try:
        return bool(host.isPlaybackActive())
    except Exception:  # noqa: BLE001
        return False


_LABEL_HANDLERS: Dict[str, Callable[[], str]] = {}


def info_label(label: str) -> str:
    text = str(label or "").strip()
    lowered = text.lower()

    if lowered == "system.buildversion":
        return KODI_VERSION
    if lowered == "system.osversioninfo":
        return _os_version()
    if lowered == "system.uptime":
        return _uptime()
    if lowered == "system.friendlyname":
        return "FOX.TV"

    handler = _LABEL_HANDLERS.get(lowered)
    if handler is not None:
        try:
            return handler()
        except Exception as exc:  # noqa: BLE001
            bridge.log(f"info label {text!r} handler failed: {exc}", bridge.LOG_DEBUG)
            return ""

    if lowered.startswith(("player.", "videoplayer.", "listitem.")):
        return _playback_label(text)

    bridge.log(f"unhandled getInfoLabel({text!r}) → ''", bridge.LOG_DEBUG)
    return ""


def _playback_label(label: str) -> str:
    """Ask FOX.TV for a player/list info label.

    Only the player-related family is forwarded: those are the labels FanFilm's
    service uses to follow playback (progress, ids, filename). Kotlin answers from
    the live player state, or with an empty string when nothing is playing.
    """
    host = bridge.host()
    if host is None:
        return ""
    try:
        value = host.playbackInfoLabel(str(label))
    except Exception as exc:  # noqa: BLE001
        bridge.log(f"playbackInfoLabel({label!r}) failed: {exc}", bridge.LOG_DEBUG)
        return ""
    return "" if value is None else str(value)


def _os_version() -> str:
    if _is_android():
        try:
            return f"Android API {sys.getandroidapilevel()}"
        except Exception:  # noqa: BLE001
            return "Android"
    import platform

    return f"{platform.system()} {platform.release()}"


def _uptime() -> str:
    import time

    seconds = int(time.monotonic())
    hours, remainder = divmod(seconds, 3600)
    minutes = remainder // 60
    return f"{hours}:{minutes:02d}"


def install() -> None:
    """Replace the shim's blanket answers. Idempotent."""
    global _installed
    with _lock:
        if _installed:
            return
        import xbmc

        xbmc.getCondVisibility = cond_visibility
        xbmc.getInfoLabel = info_label
        # getInfoImage shares the label namespace; an image path we cannot supply
        # is better reported as absent than as a bogus path.
        xbmc.getInfoImage = lambda infotag: ""

        _installed = True
        bridge.log(
            f"platform conditions installed (android={_is_android()}, kodi={KODI_VERSION})",
            bridge.LOG_INFO,
        )


def describe() -> Dict[str, object]:
    return {
        "android": _is_android(),
        "kodiVersion": KODI_VERSION,
        "kodiMajor": KODI_MAJOR,
        "platform": sys.platform,
    }
