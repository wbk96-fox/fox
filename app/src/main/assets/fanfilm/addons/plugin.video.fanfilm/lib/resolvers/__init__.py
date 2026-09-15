"""
Registry of FanFilm's custom resolvers.

Auto-discovers every ``ResolveUrl`` subclass defined in any module of this
package — drop a new file under ``lib/resolvers/`` with a class extending
``resolveurl.resolver.ResolveUrl`` and it gets picked up automatically.
Modules whose name starts with an underscore are skipped, as are classes
that are merely re-imported (only classes whose ``__module__`` matches the
discovered module count).

Modules that don't define any ``ResolveUrl`` subclass (e.g. function-based
resolvers like ``cloudnestra``) live happily in this package too — they
just aren't part of the URL-matching registry and are imported directly
by callers.

sources.py tries these first; whenever a local resolver does not return a
stream (no match, or matched-but-failed) it falls back to upstream
``resolveurl.HostedMediaFile``.
"""

from __future__ import annotations
import importlib
import inspect
import pkgutil
from urllib.parse import urlparse

from resolveurl.resolver import ResolveUrl

from ..ff.log_utils import fflog, fflog_exc


_RESOLVERS: list[type[ResolveUrl]] | None = None


def _discover_resolvers() -> list[type[ResolveUrl]]:
    found: list[type[ResolveUrl]] = []
    for mod_info in sorted(pkgutil.iter_modules(__path__), key=lambda m: m.name):
        if mod_info.name.startswith('_'):
            continue
        try:
            module = importlib.import_module(f'{__name__}.{mod_info.name}')
        except Exception as e:
            fflog.warning(f'[resolvers] failed to import {mod_info.name}: {e}')
            fflog_exc()
            continue
        for _name, obj in inspect.getmembers(module, inspect.isclass):
            if (issubclass(obj, ResolveUrl)
                    and obj is not ResolveUrl
                    and obj.__module__ == module.__name__):
                found.append(obj)
    return found


def get_resolvers() -> list[type[ResolveUrl]]:
    """List of every discovered resolver class."""
    global _RESOLVERS
    if _RESOLVERS is None:
        _RESOLVERS = _discover_resolvers()
    return _RESOLVERS


def _host_of(url: str) -> str:
    try:
        return (urlparse(url).hostname or '').lower()
    except Exception:
        return ''


def resolve_local(url: str) -> str | None:
    """Resolve ``url`` via a local "frozen" resolver.

    Returns the resolved stream URL on success, ``None`` if no local resolver
    matches the URL (caller should fall back to upstream resolveurl) or if a
    matched resolver raised / returned empty (we swallow the error and let the
    caller decide — current sources.py falls back to upstream).
    """
    if not url:
        return None
    host = _host_of(url)
    for cls in get_resolvers():
        try:
            inst = cls()
            if not inst.valid_url(url, host):
                continue
            host_id = inst.get_host_and_id(url)
            if not host_id:
                continue
            matched_host, media_id = host_id
            fflog(f'[resolvers] {cls.__name__} matched {url!r}')
            stream = inst.get_media_url(matched_host, media_id)
            if stream:
                return stream
            fflog.warning(f'[resolvers] {cls.__name__} returned empty for {url!r}')
            return None
        except Exception as e:
            fflog.warning(f'[resolvers] {cls.__name__} failed for {url!r}: {e}')
            fflog_exc()
            return None
    return None


def get_local_domains() -> list[str]:
    """Flat, deduped, lowercase list of every host claimed by local resolvers."""
    out: list[str] = []
    for cls in get_resolvers():
        for d in getattr(cls, 'domains', ()) or ():
            if d and d != '*':
                out.append(d.lower())
    return list(dict.fromkeys(out))
