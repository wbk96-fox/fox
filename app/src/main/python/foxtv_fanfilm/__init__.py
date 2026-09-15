"""FOX.TV ↔ FanFilm bridge.

FanFilm is a real Kodi Python addon. FOX.TV runs it on CPython (Chaquopy) and
supplies the pieces Kodi would normally provide:

* the ``special://`` filesystem (:mod:`.paths`),
* an interactive surface — dialogs, keyboard, notifications, progress — routed
  to Android (:mod:`.ui`),
* the addon service lifecycle (:mod:`.service`),
* settings persistence readable from both sides (:mod:`.settings`).

The Kodi API objects themselves (``xbmc``, ``xbmcgui``, ``xbmcplugin``,
``xbmcaddon``, ``xbmcvfs``, ``xbmcdrm``) come from FanFilm's own upstream shim
in ``plugin.video.fanfilm/lib/fake``. FOX.TV does not reimplement them; it
extends the shim through the callback hooks upstream exposes
(``fake_api.set_print_list_callback`` / ``set_print_log_callback``) and by
replacing the handful of members that must reach a real UI.

Everything Kotlin calls is re-exported here so the Java side only ever needs
``Python.getInstance().getModule("foxtv_fanfilm")``.
"""

from __future__ import annotations

from .environment import configure, diagnostics, is_configured, shutdown
from .provider import discover_sources, resolve_source
from .runner import cancel_run, run_plugin
from .settings import read_settings, write_setting
from .version import BRIDGE_VERSION

__all__ = [
    "BRIDGE_VERSION",
    "cancel_run",
    "configure",
    "diagnostics",
    "discover_sources",
    "is_configured",
    "read_settings",
    "resolve_source",
    "run_plugin",
    "shutdown",
    "write_setting",
]
