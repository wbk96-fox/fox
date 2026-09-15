"""FanFilm as a FOX.TV source provider.

FOX.TV lists FanFilm alongside its native scrapers, Stremio addons, torrents and
debrid services in one Source Picker (AGENTS.md §33/§39). That requires source
data, not a plugin-owned dialog, so this module drives FanFilm's public API
directly:

* :func:`discover_sources` → ``lib.ff.sources.sources.get_sources()``
* :func:`resolve_source`   → ``lib.ff.sources.sources.resolve_source()``

Both run under the shared execution lock from :mod:`.runner`, both honour
cancellation, and resolution goes through the real ResolveURL registry inside
``resolve_source`` — no shortcut resolver, no synthesised endpoint.

Sources are addressed from Kotlin by a stable token so a specific row in the
picker maps back to the exact ``Source`` object that produced it. The objects
themselves stay on the Python side; only their serialised description crosses the
bridge.
"""

from __future__ import annotations

import hashlib
import json
import threading
import time
from typing import Any, Dict, List, Optional, Tuple

from . import bridge, media, runner, ui

#: How long a discovery result stays addressable for resolution.
_CACHE_TTL_SECONDS = 30 * 60
#: Upper bound on retained discovery batches (memory guard, §61).
_CACHE_MAX_BATCHES = 8

_cache_lock = threading.RLock()
#: token → (created_at, {source_token: Source})
_batches: Dict[str, Tuple[float, Dict[str, Any]]] = {}

_MEDIA_TYPE_MOVIE = "movie"
_MEDIA_TYPE_SHOW = "show"


class ProviderError(RuntimeError):
    """Discovery or resolution failed in a way the UI should explain."""


# ---------------------------------------------------------------------------
# bootstrap
# ---------------------------------------------------------------------------

_bootstrap_lock = threading.Lock()
_bootstrapped = False


def _bootstrap() -> None:
    """Perform the import sequence ``default.py`` performs before touching the API.

    ``lib.autoinstall`` must be imported first (it forces the ``lib`` package to
    initialise), ``const`` next, and ``lib.preenter.preinit()`` registers the
    cleanup hook FanFilm expects to exist. Skipping any of it leaves module-level
    state half-built and surfaces later as obscure attribute errors.
    """
    global _bootstrapped
    with _bootstrap_lock:
        if _bootstrapped:
            return
        import lib.autoinstall  # noqa: F401  (import for side effect, as upstream does)
        from const import const  # noqa: F401
        from lib.preenter import preinit

        preinit()
        _bootstrapped = True
        bridge.log("FanFilm plugin bootstrap complete", bridge.LOG_INFO)


def _media_ref(request: Dict[str, Any]):
    """Build a ``MediaRef`` from the ids FOX.TV holds (TMDB preferred, IMDb next)."""
    from lib.defs import MediaRef, VideoIds

    media_type = str(request.get("mediaType") or "").lower()
    if media_type in ("tv", "series", "show"):
        ref_type = _MEDIA_TYPE_SHOW
    elif media_type in ("movie", "film"):
        ref_type = _MEDIA_TYPE_MOVIE
    else:
        raise ProviderError(f"unsupported media type {media_type!r}")

    tmdb = request.get("tmdbId")
    imdb = str(request.get("imdbId") or "").strip()

    ffid = 0
    if tmdb:
        try:
            ffid = VideoIds.make_ffid(tmdb=int(tmdb))
        except (TypeError, ValueError):
            ffid = 0
    if not ffid and imdb.startswith("tt"):
        ffid = VideoIds.make_ffid(imdb=imdb)
    if not ffid:
        raise ProviderError("neither a TMDB nor an IMDb id was supplied")

    season = request.get("season")
    episode = request.get("episode")
    if ref_type == _MEDIA_TYPE_SHOW:
        season = int(season) if season not in (None, "") else None
        episode = int(episode) if episode not in (None, "") else None
    else:
        season = episode = None

    return MediaRef(ref_type, ffid, season, episode)


def _find_item(ref):
    from const import const
    from lib.ff.info import ffinfo

    item = ffinfo.find_item(ref, details=const.sources.info_details)
    if item is None:
        raise ProviderError(f"FanFilm could not resolve metadata for {ref}")
    return item


def _query_for(item, ref) -> Dict[str, Any]:
    """Build FanFilm's ``SourceSearchQuery``, exactly as ``sources._play`` does."""
    if ref.type == _MEDIA_TYPE_SHOW and (show_item := getattr(item, "show_item", None)):
        vtag = show_item.getVideoInfoTag()
        premiered = show_item.date
    else:
        vtag = item.getVideoInfoTag()
        premiered = item.date

    title = vtag.getTitle()
    english = ""
    getter = getattr(vtag, "getEnglishTitle", None)
    if callable(getter):
        english = getter() or ""
    english = english or vtag.getOriginalTitle() or title

    return {
        "title": english,
        "localtitle": title,
        "originalname": vtag.getOriginalTitle(),
        "year": vtag.getYear(),
        "imdb": vtag.getUniqueID("imdb"),
        "tmdb": vtag.getUniqueID("tmdb"),
        "season": item.season,
        "episode": item.episode,
        "tvshowtitle": english if ref.type == _MEDIA_TYPE_SHOW else "",
        "premiered": str(premiered or ""),
        "ffitem": item,
    }


# ---------------------------------------------------------------------------
# serialisation
# ---------------------------------------------------------------------------

def _source_token(batch: str, index: int, source: Any) -> str:
    """Stable id for one discovered source.

    Derived from the batch, the position and the URL so a repeated discovery of
    the same title produces the same token for the same source, which lets the UI
    keep selection across a refresh.
    """
    digest = hashlib.sha256()
    digest.update(batch.encode("utf-8"))
    digest.update(str(index).encode("utf-8"))
    digest.update(str(getattr(source, "url", "")).encode("utf-8"))
    digest.update(str(getattr(source, "provider", "")).encode("utf-8"))
    return digest.hexdigest()[:24]


def _meta_get(source: Any, key: str, default: Any = "") -> Any:
    meta = getattr(source, "meta", None)
    if isinstance(meta, dict):
        value = meta.get(key, default)
        return default if value is None else value
    return default


def _serialise_source(batch: str, index: int, source: Any) -> Dict[str, Any]:
    attr = getattr(source, "attr", None)
    size_bytes = _meta_get(source, "filesize_bytes", 0)
    try:
        size_bytes = int(size_bytes or 0)
    except (TypeError, ValueError):
        size_bytes = 0

    languages = _meta_get(source, "language_list", None)
    if not isinstance(languages, (list, tuple)):
        single = str(_meta_get(source, "language", "")).strip()
        languages = [single] if single else []

    return {
        "token": _source_token(batch, index, source),
        "provider": str(getattr(source, "provider", "")),
        "hosting": str(getattr(source, "hosting", "")),
        "label": str(_meta_get(source, "label", "")),
        "info": str(_meta_get(source, "info", "")),
        "info2": str(_meta_get(source, "info2", "")),
        "quality": str(_meta_get(source, "quality", "")),
        "size": str(_meta_get(source, "size", "")),
        "sizeBytes": size_bytes,
        "filename": str(_meta_get(source, "filename", "")),
        "languages": [str(item) for item in languages if str(item).strip()],
        "debrid": str(_meta_get(source, "debrid", "")),
        "premium": bool(_meta_get(source, "premium", False)),
        "onAccount": bool(_meta_get(source, "on_account", False)),
        "direct": bool(_meta_get(source, "direct", False)),
        "local": bool(_meta_get(source, "local", False)),
        "external": bool(_meta_get(source, "external", False)),
        "resolved": bool(getattr(source, "resolved", False)),
        "playMode": str(getattr(attr, "play", "") or "") if attr is not None else "",
        "icon": str(_meta_get(source, "icon", "")),
        # `fake` marks FanFilm's own "no sources found" placeholder entry. It is
        # reported rather than silently filtered so the UI can explain the
        # outcome instead of showing an unplayable row (or, worse, playing it).
        "placeholder": bool(_meta_get(source, "fake", False)),
    }


def _remember(batch: str, sources: List[Any]) -> Dict[str, Any]:
    mapping = {
        _source_token(batch, index, source): source for index, source in enumerate(sources)
    }
    with _cache_lock:
        _batches[batch] = (time.monotonic(), mapping)
        _evict_locked()
    return mapping


def _evict_locked() -> None:
    now = time.monotonic()
    for token in [t for t, (created, _) in _batches.items() if now - created > _CACHE_TTL_SECONDS]:
        _batches.pop(token, None)
    while len(_batches) > _CACHE_MAX_BATCHES:
        oldest = min(_batches, key=lambda token: _batches[token][0])
        _batches.pop(oldest, None)


def forget(batch: str) -> None:
    with _cache_lock:
        _batches.pop(batch, None)


def _lookup(batch: str, token: str) -> Any:
    with _cache_lock:
        entry = _batches.get(batch)
        if entry is None:
            raise ProviderError("source list expired; run the search again")
        _, mapping = entry
        source = mapping.get(token)
    if source is None:
        raise ProviderError("selected source is no longer available")
    return source


# ---------------------------------------------------------------------------
# public API
# ---------------------------------------------------------------------------

def discover_sources(request_json: str, run_id: int) -> str:
    """Scan FanFilm providers. Returns JSON with the source list or a typed error."""
    run_id = int(run_id)
    try:
        request = json.loads(request_json) if request_json else {}
    except (TypeError, ValueError) as exc:
        return json.dumps(
            {"ok": False, "kind": "configuration", "message": f"bad request: {exc}"},
            ensure_ascii=False,
        )

    def action() -> Dict[str, Any]:
        _bootstrap()
        from lib.ff.sources import sources as SourcesApi

        ref = _media_ref(request)
        item = _find_item(ref)
        query = _query_for(item, ref)

        batch = hashlib.sha256(
            json.dumps(
                {
                    "ref": str(ref),
                    "title": query["title"],
                    "year": query["year"],
                    "run": run_id,
                },
                sort_keys=True,
            ).encode("utf-8")
        ).hexdigest()[:16]

        reporter = ui.ProgressReporter(run_id, query["title"])
        api = SourcesApi()
        # FanFilm's own _play() initialises the SourcesApi with getConstants()
        # before get_sources(): that scan builds `source_mods` (and the resolver
        # host list). Skipping it leaves source_mods as None and get_sources()
        # dies with "TypeError: 'NoneType' object is not iterable" (measured on
        # device when opening an episode's source list).
        api.getConstants(ffitem=item)
        kwargs = dict(query)
        kwargs.pop("ffitem", None)
        timeout = request.get("timeoutSeconds")

        found = api.get_sources(
            ffitem=item,
            progress_dialog=reporter,
            **({"timeout": int(timeout)} if timeout else {}),
            **kwargs,
        )
        reporter.close()

        if runner.is_cancelled(run_id):
            raise ProviderError("cancelled")

        found = list(found or [])
        _remember(batch, found)
        return {
            "batch": batch,
            "mediaRef": str(ref),
            "title": query["localtitle"] or query["title"],
            "year": query["year"],
            "sources": [
                _serialise_source(batch, index, source) for index, source in enumerate(found)
            ],
        }

    outcome = runner.run_locked(run_id, f"discover_sources(run={run_id})", action)
    if outcome.get("ok"):
        payload = outcome["value"]
        payload["ok"] = True
        return json.dumps(payload, ensure_ascii=False)
    return json.dumps(
        {
            "ok": False,
            "kind": outcome.get("kind", "provider"),
            "message": outcome.get("message", "source discovery failed"),
            "detail": outcome.get("detail", ""),
        },
        ensure_ascii=False,
    )


def resolve_source(request_json: str, run_id: int) -> str:
    """Resolve one discovered source through ResolveURL into a media descriptor."""
    run_id = int(run_id)
    try:
        request = json.loads(request_json) if request_json else {}
    except (TypeError, ValueError) as exc:
        return json.dumps(
            {"ok": False, "kind": "configuration", "message": f"bad request: {exc}"},
            ensure_ascii=False,
        )

    batch = str(request.get("batch") or "")
    token = str(request.get("token") or "")

    def action() -> Dict[str, Any]:
        _bootstrap()
        from lib.ff.sources import sources as SourcesApi

        source = _lookup(batch, token)
        if _meta_get(source, "fake", False):
            raise ProviderError(
                "FanFilm reported no usable sources for this title "
                "(the provider scan timed out or every host failed)"
            )

        api = SourcesApi()
        # Same SourcesApi initialisation contract as discover_sources above: the
        # resolve path reads `source_mods` to locate the provider module, so a
        # fresh instance must run getConstants() first (Source carries its ffitem).
        api.getConstants(ffitem=source.ffitem)
        resolved = api.resolve_source(source, info=False)
        if runner.is_cancelled(run_id):
            raise ProviderError("cancelled")
        if not resolved:
            # resolve_source() returns None for a dead link after having already
            # logged which resolver failed; surface it as a resolver error so the
            # fallback chain can move on to the next source.
            raise ResolverFailure(
                f"{getattr(source, 'hosting', 'host') or 'host'} did not return a stream"
            )

        descriptor = media.describe(resolved, source)
        descriptor["provider"] = str(getattr(source, "provider", ""))
        descriptor["hosting"] = str(getattr(source, "hosting", ""))
        descriptor["label"] = str(_meta_get(source, "label", ""))
        descriptor["quality"] = str(_meta_get(source, "quality", ""))
        descriptor["token"] = token
        descriptor["batch"] = batch
        return descriptor

    outcome = runner.run_locked(run_id, f"resolve_source(run={run_id})", action)
    if outcome.get("ok"):
        payload = outcome["value"]
        payload["ok"] = True
        return json.dumps(payload, ensure_ascii=False)

    kind = outcome.get("kind", "provider")
    message = outcome.get("message", "resolve failed")
    # Map the Python exception classes onto the typed error model the UI uses.
    detail = outcome.get("detail", "")
    if "ResolverFailure" in detail or isinstance(message, str) and "did not return a stream" in message:
        kind = "resolver"
    elif "UnplayableSource" in detail:
        kind = "playback"
    return json.dumps(
        {"ok": False, "kind": kind, "message": message, "detail": detail},
        ensure_ascii=False,
    )


class ResolverFailure(ProviderError):
    """A host was reachable but produced no stream (dead link, geo-block, …)."""


def diagnostics() -> str:
    with _cache_lock:
        batches = {
            token: {"age": round(time.monotonic() - created, 1), "sources": len(mapping)}
            for token, (created, mapping) in _batches.items()
        }
    return json.dumps({"batches": batches, "bootstrapped": _bootstrapped}, ensure_ascii=False)
