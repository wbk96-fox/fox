"""Turn FanFilm's playback output into a normalised media descriptor.

FanFilm hands back a single string, in one of the three shapes Kodi understands:

``http://host/file.mp4``
    direct stream

``http://host/file.m3u8|User-Agent=x&Referer=y``
    stream plus request headers (Kodi's pipe convention, values URL-encoded)

``DRM|{'protocol': 'mpd', 'manifest': '…', 'licence_type': 'com.widevine.alpha', …}``
    DASH/Widevine, with a Python dict literal carrying the licence details

Collapsing all of that back to a bare URL loses the headers, the MIME type and
the licence, which is exactly what AGENTS.md §32 forbids. This module parses each
shape into an explicit descriptor that the Kotlin side maps onto its existing
player contract.

Whether a stream should go through the adaptive pipeline is decided from the same
inputs FanFilm's own ``lib/ff/player.py`` uses — the source's ``attr.play`` mode,
the ``isa.enabled`` setting and ``Source.is_m3u8()`` — so behaviour tracks
upstream instead of being guessed here.
"""

from __future__ import annotations

import ast
import re
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import parse_qsl, unquote, urlparse

from . import bridge

#: Stream container/protocol classification handed to the player.
STREAM_DIRECT = "direct"
STREAM_HLS = "hls"
STREAM_DASH = "dash"
STREAM_SMOOTH = "smoothstreaming"
STREAM_STACK = "stack"
STREAM_PLUGIN = "plugin"
STREAM_LOCAL = "local"

_MPD = re.compile(r"\.mpd(\?|$|\|)", re.IGNORECASE)
_M3U8 = re.compile(r"\.m3u8(\?|$|\|)", re.IGNORECASE)
_ISM = re.compile(r"/manifest(\?|$|\|)|\.ism(/|\?|$|\|)", re.IGNORECASE)

_MIME_BY_STREAM = {
    STREAM_HLS: "application/x-mpegURL",
    STREAM_DASH: "application/dash+xml",
    STREAM_SMOOTH: "application/vnd.ms-sstr+xml",
}

# Header names Kodi carries in the pipe suffix that are request headers rather
# than inputstream directives.
_HEADER_KEYS = {
    "user-agent",
    "referer",
    "referrer",
    "origin",
    "cookie",
    "authorization",
    "accept",
    "accept-language",
    "accept-encoding",
    "range",
    "x-forwarded-for",
    "verifypeer",
}


class UnplayableSource(ValueError):
    """The resolved value cannot be turned into something the player accepts."""


def _split_pipe(url: str) -> Tuple[str, str]:
    """Split Kodi's ``url|headers`` form, tolerating pipes inside the query."""
    index = url.find("|")
    if index < 0:
        return url, ""
    return url[:index], url[index + 1:]


def parse_headers(blob: str) -> Dict[str, str]:
    """Parse Kodi's ``Key=Value&Key2=Value2`` header suffix.

    Values are URL-encoded by convention but not always; ``parse_qsl`` keeps
    unencoded values intact and ``unquote`` is idempotent for them.
    """
    headers: Dict[str, str] = {}
    if not blob:
        return headers
    for key, value in parse_qsl(blob, keep_blank_values=True):
        name = unquote(key).strip()
        if not name:
            continue
        headers[name] = unquote(value)
    return headers


def classify(url: str) -> str:
    lowered = url.lower()
    if lowered.startswith("plugin://"):
        return STREAM_PLUGIN
    if lowered.startswith("stack://"):
        return STREAM_STACK
    if "://" not in url:
        return STREAM_LOCAL
    if _MPD.search(url):
        return STREAM_DASH
    if _M3U8.search(url):
        return STREAM_HLS
    if _ISM.search(url):
        return STREAM_SMOOTH
    return STREAM_DIRECT


def _drm_payload(raw: str) -> Dict[str, Any]:
    """Parse the dict literal FanFilm appends to a ``DRM…`` URL.

    ``ast.literal_eval`` is used rather than ``eval``: the payload comes from a
    provider response and must never be executable.
    """
    _, _, blob = raw.partition("|")
    blob = blob.strip()
    if not blob:
        raise UnplayableSource("DRM source carries no licence payload")
    try:
        parsed = ast.literal_eval(blob)
    except (ValueError, SyntaxError) as exc:
        raise UnplayableSource(f"malformed DRM payload: {exc}") from exc
    if not isinstance(parsed, dict):
        raise UnplayableSource("DRM payload is not a mapping")
    return {str(key): value for key, value in parsed.items()}


def _drm_descriptor(raw: str) -> Dict[str, Any]:
    payload = _drm_payload(raw)
    manifest = str(payload.get("manifest") or payload.get("url") or "").strip()
    if not manifest:
        raise UnplayableSource("DRM source has no manifest URL")

    protocol = str(payload.get("protocol") or "").lower()
    stream_type = {
        "mpd": STREAM_DASH,
        "dash": STREAM_DASH,
        "hls": STREAM_HLS,
        "ism": STREAM_SMOOTH,
    }.get(protocol, classify(manifest))

    licence_type = str(payload.get("licence_type") or payload.get("license_type") or "").strip()
    licence_url = str(payload.get("licence_url") or payload.get("license_url") or "").strip()
    licence_headers = parse_headers(str(payload.get("licence_header") or ""))
    manifest_url, manifest_header_blob = _split_pipe(manifest)
    request_headers = parse_headers(manifest_header_blob)

    descriptor: Dict[str, Any] = {
        "url": manifest_url,
        "streamType": stream_type,
        "mimeType": str(payload.get("mimetype") or _MIME_BY_STREAM.get(stream_type, "")),
        "headers": request_headers,
        "cookies": _cookies_from_headers(request_headers),
        "referer": _referer(request_headers),
        "adaptive": True,
        "drm": {
            "scheme": licence_type or "com.widevine.alpha",
            "licenseUrl": licence_url,
            "licenseHeaders": licence_headers,
            "postData": str(payload.get("post_data") or ""),
            "responseData": str(payload.get("response_data") or ""),
        },
        "subtitles": _subtitles(payload),
    }
    if not licence_url:
        # A Widevine stream without a licence server cannot be decrypted; say so
        # rather than handing the player a manifest it will fail on with a
        # meaningless codec error.
        raise UnplayableSource("DRM source has no licence server URL")
    return descriptor


def _cookies_from_headers(headers: Dict[str, str]) -> Dict[str, str]:
    """Extract a cookie jar from a ``Cookie:`` header."""
    cookies: Dict[str, str] = {}
    for name, value in headers.items():
        if name.lower() != "cookie":
            continue
        for pair in value.split(";"):
            key, _, val = pair.partition("=")
            key = key.strip()
            if key:
                cookies[key] = val.strip()
    return cookies


def _referer(headers: Dict[str, str]) -> str:
    for name, value in headers.items():
        if name.lower() in ("referer", "referrer"):
            return value
    return ""


def _subtitles(payload: Dict[str, Any]) -> List[Dict[str, str]]:
    raw = payload.get("subtitles") or payload.get("subs") or []
    tracks: List[Dict[str, str]] = []
    if isinstance(raw, str):
        raw = [raw]
    if isinstance(raw, dict):
        raw = [{"language": key, "url": value} for key, value in raw.items()]
    for entry in raw or []:
        if isinstance(entry, str):
            tracks.append({"url": entry, "language": "", "label": ""})
        elif isinstance(entry, dict):
            url = str(entry.get("url") or entry.get("path") or "")
            if not url:
                continue
            tracks.append(
                {
                    "url": url,
                    "language": str(entry.get("language") or entry.get("lang") or ""),
                    "label": str(entry.get("label") or entry.get("name") or ""),
                }
            )
    return tracks


def _adaptive_requested(source: Any, stream_type: str) -> bool:
    """Mirror ``lib/ff/player.py``'s ISA decision.

    ``attr.play`` is the per-source override ('isa' / 'direct' / 'auto'); in auto
    mode Kodi consults the ``isa.enabled`` setting and only uses the adaptive
    pipeline for HLS. DASH always needs it.
    """
    if stream_type == STREAM_DASH:
        return True
    mode = ""
    attr = getattr(source, "attr", None)
    if attr is not None:
        mode = str(getattr(attr, "play", "") or "")
    if mode == "isa":
        return True
    if mode == "direct":
        return False
    if stream_type != STREAM_HLS:
        return False
    try:
        from lib.ff.settings import settings

        return bool(settings.getBool("isa.enabled"))
    except Exception as exc:  # noqa: BLE001
        bridge.log(f"isa.enabled unreadable ({exc}); defaulting to direct HLS", bridge.LOG_DEBUG)
        return False


def describe(resolved: str, source: Any = None) -> Dict[str, Any]:
    """Build the descriptor for a resolved FanFilm URL.

    Raises :class:`UnplayableSource` when the value cannot be played, so callers
    surface a typed error instead of starting the player on garbage.
    """
    raw = (resolved or "").strip()
    if not raw:
        raise UnplayableSource("provider returned an empty URL")

    if raw.upper().startswith("DRM"):
        descriptor = _drm_descriptor(raw)
        descriptor["adaptive"] = True
        return descriptor

    url, header_blob = _split_pipe(raw)
    url = url.strip()
    if not url:
        raise UnplayableSource("provider returned only headers, no URL")

    stream_type = classify(url)
    if stream_type == STREAM_LOCAL and not url.startswith("/"):
        raise UnplayableSource(f"unsupported URL form: {url[:120]!r}")

    all_pairs = parse_headers(header_blob)
    headers = {k: v for k, v in all_pairs.items() if k.lower() in _HEADER_KEYS}
    extras = {k: v for k, v in all_pairs.items() if k.lower() not in _HEADER_KEYS}
    if extras:
        # Kodi treats unknown pipe keys as ListItem properties (inputstream
        # directives, mimetype overrides, …). Keep them: the Kotlin side decides
        # what it can honour, and dropping them silently would lose DRM config.
        bridge.log(f"non-header pipe properties: {sorted(extras)}", bridge.LOG_DEBUG)

    descriptor: Dict[str, Any] = {
        "url": url,
        "streamType": stream_type,
        "mimeType": extras.get("mimetype", _MIME_BY_STREAM.get(stream_type, "")),
        "headers": headers,
        "cookies": _cookies_from_headers(headers),
        "referer": _referer(headers),
        "properties": extras,
        "adaptive": _adaptive_requested(source, stream_type),
        "drm": None,
        "subtitles": [],
    }

    if stream_type == STREAM_STACK:
        # stack:// is Kodi's multi-part playlist ("part1 , part2"). Expose the
        # parts so the player can queue them instead of failing on the scheme.
        descriptor["parts"] = [
            part.strip() for part in url[len("stack://"):].split(" , ") if part.strip()
        ]

    if stream_type == STREAM_PLUGIN:
        descriptor["pluginTarget"] = _plugin_target(url)

    host = urlparse(url).hostname or ""
    descriptor["host"] = host.lower()
    return descriptor


def _plugin_target(url: str) -> Dict[str, str]:
    """Describe a ``plugin://`` URL so Kotlin can decide whether it can serve it.

    The only such URLs FanFilm produces come from its YouTube-based providers
    (``plugin://plugin.video.youtube/play/?video_id=…``). FOX.TV has its own
    YouTube playback, so the addon id and parameters are passed through rather
    than being resolved here.
    """
    parsed = urlparse(url)
    return {
        "addonId": parsed.netloc,
        "path": parsed.path,
        "query": parsed.query,
        "videoId": dict(parse_qsl(parsed.query)).get("video_id", ""),
    }
