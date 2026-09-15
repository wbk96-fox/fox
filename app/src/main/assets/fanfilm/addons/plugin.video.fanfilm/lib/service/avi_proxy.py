"""Range proxy that pre-caches an OpenDML AVI's scattered index.

A >2 GB AVI keeps its index chained through the file: an ``indx`` superindex in
the header points at ~40 ``ix##`` chunks spread across the whole file, and ffmpeg
reads every one of them while opening - over a slow CDN that alone blows Kodi's
30 s demuxer timeout. On the first GET this proxy fetches the header, the tail
and all ``ix##`` windows in parallel and serves them from RAM, so ffmpeg opens
against localhost; playback bytes then pass straight through. Only the file
currently being played is held (a new file's build drops the previous one).

``http_request`` routes ``*.avi`` paths here; ``ff.player`` rewrites an ``*.avi``
play URL to ``<service>/<b64-of-cdn-url>.avi``.
"""
from __future__ import annotations
from typing import TYPE_CHECKING
from typing_extensions import TypeAlias
import base64
import struct
import time
from concurrent.futures import ThreadPoolExecutor
from threading import Lock

import requests
from requests.adapters import HTTPAdapter

from ..ff.log_utils import fflog, fflog_exc

if TYPE_CHECKING:
    from .http_request import RequestHandler

#: one pre-fetched span: (absolute offset in the file, its bytes)
Span: TypeAlias = 'tuple[int, bytes]'
#: a built index: (file size, spans sorted by offset)
Index: TypeAlias = 'tuple[int, list[Span]]'

HDR_BYTES: int = 2 << 20        # header window scanned for the superindex
TAIL_BYTES: int = 12 << 20      # legacy idx1 tail kept in RAM
IX_PAD: int = 2 << 20           # extra bytes cached after each ix## chunk
READ_BYTES: int = 1 << 20      # passthrough read size
WORKERS: int = 48              # parallel index fetches

_session: requests.Session = requests.Session()
_session.mount('https://', HTTPAdapter(pool_connections=8, pool_maxsize=WORKERS + 8))
_pool: ThreadPoolExecutor = ThreadPoolExecutor(max_workers=WORKERS)
#: url -> (size, spans); holds only the file currently being played (see _index_for)
_cache: dict[str, Index] = {}
_url_locks: dict[str, Lock] = {}   # one build lock per url, so a stuck host blocks only its own file
_guard: Lock = Lock()              # guards _cache and _url_locks (held only for dict ops, never I/O)
fflog('[aviproxy] loaded')


def proxy_path(cdn_url: str) -> str:
    """``<b64-of-cdn-url>.avi`` - prepend the service base URL to reach this proxy."""
    return base64.urlsafe_b64encode(cdn_url.encode()).decode() + '.avi'


def _fetch(url: str, user_agent: str, low: int, high: int) -> bytes:
    """GET bytes ``low..high`` inclusive, or ``b''`` on any failure."""
    try:
        response = _session.get(url, headers={'User-Agent': user_agent, 'Range': f'bytes={low}-{high}'},
                                timeout=30, verify=False)
        try:
            return response.content if response.status_code in (200, 206) else b''
        finally:
            response.close()
    except Exception:
        return b''


def _remote_size(url: str, user_agent: str) -> int:
    """File size from a one-byte range request, or 0 if unknown."""
    try:
        response = _session.get(url, headers={'User-Agent': user_agent, 'Range': 'bytes=0-0'},
                                timeout=20, verify=False)
        response.close()
        content_range: str = response.headers.get('Content-Range', '')
        if '/' in content_range:
            return int(content_range.rsplit('/', 1)[-1])
        return int(response.headers.get('Content-Length') or 0)
    except Exception:
        return 0


def _ix_chunks(header: bytes) -> list[tuple[int, int]]:
    """``(offset, size)`` of every ``ix##`` chunk listed in the header's ``indx`` superindexes.

    After the 8-byte ``indx`` FourCC+size come wLongsPerEntry u16, bIndexSubType
    u8, bIndexType u8 (0 == index of indexes), nEntriesInUse u32, dwChunkId u32,
    dwReserved[3]; then the entries, each qwOffset u64, dwSize u32, dwDuration u32.
    """
    chunks: list[tuple[int, int]] = []
    cursor: int = 0
    while True:
        found: int = header.find(b'indx', cursor)
        if found < 0 or found + 32 > len(header):
            return chunks
        cursor = found + 4
        longs_per_entry, _subtype, index_type = struct.unpack_from('<HBB', header, found + 8)
        entries: int = struct.unpack_from('<I', header, found + 12)[0]
        if index_type != 0 or longs_per_entry != 4 or not 0 < entries <= 4096:
            continue
        for slot in range(entries):
            entry_at: int = found + 32 + slot * 16
            if entry_at + 16 > len(header):
                break
            offset, size, _duration = struct.unpack_from('<QII', header, entry_at)
            if offset:
                chunks.append((offset, size or 262144))


def _build(url: str, user_agent: str) -> Index:
    """Fetch the size, header, tail and every ``ix##`` window (in parallel)."""
    started: float = time.time()
    size: int = _remote_size(url, user_agent)
    header: bytes = _fetch(url, user_agent, 0, HDR_BYTES - 1) if size else b''
    if not header:
        fflog(f'[aviproxy] build failed - no size/header ({size=})')
        return 0, []
    windows: list[tuple[int, int]] = [(max(0, size - TAIL_BYTES), size - 1)]
    for offset, chunk_size in _ix_chunks(header):
        windows.append((offset, min(offset + chunk_size + IX_PAD, size) - 1))

    def fetch_window(window: tuple[int, int]) -> Span:
        return window[0], _fetch(url, user_agent, window[0], window[1])

    spans: list[Span] = sorted(
        [(0, header)] + [span for span in _pool.map(fetch_window, windows) if span[1]]
    )
    fflog(f'[aviproxy] index ready: {len(spans)} spans in {time.time() - started:.1f}s')
    return size, spans


def _index_for(url: str, user_agent: str) -> Index:
    """Cached :func:`_build` result for *url*.

    A failed build is not cached (so a transient host hiccup doesn't poison the
    file); the build lock is per-url (so a stuck host blocks only its own file);
    and a fresh build drops every other entry - a real build only ever happens
    when a new file starts playing, so at most one index sits in RAM. The index
    is valid for the life of the URL, which outlives any single playback.
    """
    with _guard:
        cached = _cache.get(url)
        if cached is not None:
            return cached
        build_lock = _url_locks.get(url)
        if build_lock is None:
            build_lock = _url_locks[url] = Lock()

    with build_lock:
        with _guard:
            cached = _cache.get(url)
        if cached is not None:
            return cached
        result: Index = _build(url, user_agent)
        if result[0] and result[1]:
            with _guard:
                _cache.clear()
                _cache[url] = result
                _url_locks.clear()           # keep both dicts down to just this file;
                _url_locks[url] = build_lock  # safe - any waiter already holds a reference
        return result


def _span_at(spans: list[Span], position: int) -> Span | None:
    for offset, data in spans:
        if offset <= position < offset + len(data):
            return offset, data
    return None


def _wanted_range(range_header: str, size: int) -> tuple[int, int]:
    if not range_header.startswith('bytes='):
        return 0, size - 1
    spec: str = range_header[6:].split(',', 1)[0].strip()
    if spec.startswith('-'):
        return max(0, size - int(spec[1:])), size - 1
    low, _, high = spec.partition('-')
    return int(low or 0), int(high) if high else size - 1


def handle(handler: RequestHandler) -> None:
    """Serve a ``<b64-url>.avi`` request straight onto ``handler.wfile``."""
    replied: bool = False
    try:
        token: str = handler.path.rsplit('/', 1)[-1].split('?', 1)[0][:-4]
        url: str = base64.urlsafe_b64decode(token.encode()).decode()
        user_agent: str = handler.headers.get('User-Agent', '')

        if handler.command == 'HEAD':                  # answer Kodi's Stat fast, don't build
            size: int = _remote_size(url, user_agent)
            if not size:
                handler.send_error(502)
                return
            handler.send_response(200)
            handler.send_header('Content-Type', 'video/x-msvideo')
            handler.send_header('Accept-Ranges', 'bytes')
            handler.send_header('Content-Length', str(size))
            handler.end_headers()
            return

        size, spans = _index_for(url, user_agent)      # first GET builds the index, then serves
        if not size:
            handler.send_error(502)
            return
        start, end = _wanted_range(handler.headers.get('Range', ''), size)
        end = min(end, size - 1)
        if start > end:
            handler.send_response(416)
            handler.send_header('Content-Range', f'bytes */{size}')
            handler.end_headers()
            return

        handler.send_response(206)
        replied = True
        handler.send_header('Content-Type', 'video/x-msvideo')
        handler.send_header('Accept-Ranges', 'bytes')
        handler.send_header('Content-Range', f'bytes {start}-{end}/{size}')
        handler.send_header('Content-Length', str(end - start + 1))
        handler.end_headers()

        position: int = start
        while position <= end:
            span: Span | None = _span_at(spans, position)
            if span is not None:
                offset, data = span
                take: int = min(len(data) - (position - offset), end - position + 1)
                handler.wfile.write(data[position - offset:position - offset + take])
                position += take
                continue
            response = _session.get(url, headers={'User-Agent': user_agent, 'Range': f'bytes={position}-{end}'},
                                    stream=True, timeout=30, verify=False)
            try:
                for chunk in response.iter_content(READ_BYTES):
                    handler.wfile.write(chunk)
                    position += len(chunk)
            finally:
                response.close()
            break
    except (BrokenPipeError, ConnectionResetError, TimeoutError):
        handler.close_connection = True                # client seeked away / stopped - expected
    except Exception:
        fflog_exc()
        if not replied:
            try:
                handler.send_error(500)
            except Exception:
                pass
        handler.close_connection = True
