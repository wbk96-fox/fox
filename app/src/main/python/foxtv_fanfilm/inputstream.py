"""InputStream Helper adapter.

``plugin.video.fanfilm``'s ``lib/ff/player.py`` uses the real
``script.module.inputstreamhelper``::

    is_helper = inputstreamhelper.Helper(protocol, drm=drm)
    if not is_helper.check_inputstream():
        return cancel(ffitem)
    listitem.setProperty('inputstream', is_helper.inputstream_addon)

``check_inputstream()`` asks "is ``inputstream.adaptive`` installed and enabled,
and can this device decrypt Widevine?". On Kodi it answers by inspecting the
binary addon and, off Android, by downloading a CDM. FOX.TV has neither: adaptive
playback is Media3's DASH/HLS/SmoothStreaming pipeline and Widevine comes from
Android's ``MediaDrm``.

So the question is answered truthfully, against the real player, instead of being
stubbed ``True``:

* protocol support → does the build contain the matching Media3 module?
* Widevine support → ``MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID)`` plus the
  security level actually provisioned on the device.

``inputstream_addon`` keeps returning ``inputstream.adaptive``. That string is
Kodi's marker for "hand this item to the adaptive pipeline"; FanFilm writes it
into a ListItem property that :mod:`foxtv_fanfilm.media` turns into the
descriptor's ``adaptive`` flag. Renaming it would only break the addon's own
version checks.

Nothing here bypasses DRM. If the device cannot decrypt a stream,
``check_inputstream()`` returns ``False`` and FanFilm cancels playback, which is
the same outcome Kodi produces.
"""

from __future__ import annotations

import json
import threading
from typing import Any, Dict, Optional

from . import bridge

_installed = False
_lock = threading.RLock()

#: Kodi protocol name → the capability key Kotlin reports on.
_PROTOCOL_CAPABILITY = {
    "mpd": "dash",
    "ism": "smoothstreaming",
    "hls": "hls",
    "rtmp": "rtmp",
}

_WIDEVINE = "widevine"

_capabilities: Optional[Dict[str, Any]] = None


def capabilities(refresh: bool = False) -> Dict[str, Any]:
    """Player capabilities as reported by Kotlin.

    Shape::

        {"protocols": {"dash": true, "hls": true, "smoothstreaming": true,
                       "rtmp": false},
         "drm": {"widevine": true, "widevineSecurityLevel": "L1",
                 "playready": false, "clearkey": true}}
    """
    global _capabilities
    if _capabilities is not None and not refresh:
        return _capabilities
    host = bridge.host()
    if host is None:
        _capabilities = {"protocols": {}, "drm": {}, "available": False}
        return _capabilities
    try:
        raw = host.playerCapabilities()
        parsed = json.loads(str(raw)) if raw else {}
        if not isinstance(parsed, dict):
            parsed = {}
    except Exception as exc:  # noqa: BLE001
        bridge.warn(f"could not read player capabilities: {exc}")
        parsed = {}
    parsed.setdefault("protocols", {})
    parsed.setdefault("drm", {})
    parsed["available"] = True
    _capabilities = parsed
    return _capabilities


def supports_protocol(protocol: str) -> bool:
    key = _PROTOCOL_CAPABILITY.get(str(protocol).lower())
    if key is None:
        return False
    caps = capabilities()
    if not caps.get("available"):
        # No host attached (unit tests): report unsupported rather than claiming
        # a capability that was never verified.
        return False
    return bool(caps["protocols"].get(key, False))


def supports_widevine() -> bool:
    caps = capabilities()
    if not caps.get("available"):
        return False
    return bool(caps["drm"].get(_WIDEVINE, False))


def widevine_security_level() -> str:
    caps = capabilities()
    return str(caps.get("drm", {}).get("widevineSecurityLevel", "") or "")


def install() -> None:
    """Point ``inputstreamhelper.Helper`` at the real player. Idempotent."""
    global _installed
    with _lock:
        if _installed:
            return
        try:
            import inputstreamhelper
        except ImportError as exc:
            bridge.error(f"inputstreamhelper is not importable: {exc}")
            return

        helper = inputstreamhelper.Helper
        original_init = helper.__init__

        def check_inputstream(self) -> bool:  # noqa: ANN001
            protocol = str(getattr(self, "protocol", "") or "")
            drm = getattr(self, "drm", None)

            if not supports_protocol(protocol):
                bridge.warn(
                    f"adaptive playback unavailable for protocol {protocol!r}; "
                    "FanFilm will cancel this source"
                )
                return False

            if drm == _WIDEVINE:
                if not supports_widevine():
                    bridge.warn(
                        "Widevine is not available on this device; "
                        "the DRM source cannot be played"
                    )
                    return False
                level = widevine_security_level()
                bridge.log(
                    f"Widevine available (security level {level or 'unknown'})",
                    bridge.LOG_INFO,
                )
            elif drm:
                bridge.warn(f"unsupported DRM scheme {drm!r}")
                return False

            return True

        def inputstream_version(self) -> str:  # noqa: ANN001
            # FanFilm compares this against config.HLS_MINIMUM_IA_VERSION. Media3's
            # HLS support is well past every threshold inputstreamhelper knows
            # about, so report a version that reflects "modern adaptive pipeline"
            # rather than a fabricated addon version.
            return "99.0.0"

        def info_dialog(self) -> None:  # noqa: ANN001
            caps = capabilities(refresh=True)
            bridge.log(f"inputstream capabilities: {caps}", bridge.LOG_INFO)

        def noop(self, *args: Any, **kwargs: Any) -> bool:  # noqa: ANN001
            return True

        def patched_init(self, protocol, drm=None):  # noqa: ANN001
            # Keep upstream validation (it raises on unknown protocol/DRM) but skip
            # the proxy-opener installation, which would rewire urllib globally.
            from inputstreamhelper import config
            from inputstreamhelper.api import InputStreamException

            self.protocol = protocol
            self.drm = drm
            if protocol not in config.INPUTSTREAM_PROTOCOLS:
                raise InputStreamException("UnsupportedProtocol")
            self.inputstream_addon = config.INPUTSTREAM_PROTOCOLS[protocol]
            if drm:
                if drm not in config.DRM_SCHEMES:
                    raise InputStreamException("UnsupportedDRMScheme")
                self.drm = config.DRM_SCHEMES[drm]

        helper.__init__ = patched_init
        helper.check_inputstream = check_inputstream
        helper._inputstream_version = inputstream_version
        helper.info_dialog = info_dialog
        # Installation / enabling / CDM download are meaningless without Kodi's
        # binary addons. Answering "already fine" is correct here because the
        # actual capability question is answered by check_inputstream above.
        helper._install_inputstream = noop
        helper._has_inputstream = noop
        helper._inputstream_enabled = noop
        helper._enable_inputstream = noop
        helper._supports_hls = noop
        helper._check_drm = noop

        _installed = True
        bridge.log("inputstreamhelper bound to the FOX.TV player", bridge.LOG_INFO)
        # Keep a reference so the original is recoverable in diagnostics.
        helper._foxtv_original_init = original_init


def describe() -> Dict[str, Any]:
    return {"installed": _installed, "capabilities": capabilities()}
