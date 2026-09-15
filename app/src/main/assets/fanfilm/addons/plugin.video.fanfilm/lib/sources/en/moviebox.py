# -*- coding: utf-8 -*-
"""
FanFilm - source: MovieBox (api3.aoneroom.com)
Copyright (C) 2026 :)

Dystrybuowane na licencji MIT <https://mit-license.org>

Real backend behind the MovieBox app. Every request needs signed headers:
  - x-client-token: "{ts},{md5(reversed(str(ts)))}"
  - x-tr-signature: "{ts}|2|{base64(HMAC-MD5(secret, canonical))}"
  - Bearer token from one GET to /tab/ranking-list, read out of its `x-user`
    response header (JSON with a "token" field), not the body.

subjectType: 1 = movie, 2 = one season of a show (each its own subject, e.g.
"Breaking Bad S1".."S5"). play-info takes se=/ep= on that subjectId directly.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import re
import secrets
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from typing import ClassVar, List, Optional, TYPE_CHECKING
from urllib.parse import urlparse, parse_qs, urljoin

from lib.ff import cache, cleantitle, control, requests, source_utils
from lib.ff.item import FFItem
from lib.ff.source_utils import append_headers, convert_size, quality_from_resolution, search_queries, year_matches
from lib.ff.log_utils import fflog, fflog_exc
from lib.service.client import service_client

if TYPE_CHECKING:
    from lib.sources import SourceItem, SourceTitleAlias

_BASE = 'https://api3.aoneroom.com'
_SEARCH_PATH = '/wefeed-mobile-bff/subject-api/search/v2'
_PLAYINFO_PATH = '/wefeed-mobile-bff/subject-api/play-info'
_CAPTIONS_PATH = '/wefeed-mobile-bff/subject-api/get-stream-captions'
_PING_URL = _BASE + '/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1'

_PKG = 'com.community.oneroom'
_VERSION_NAME = '3.0.13.0325.03'
_VERSION_CODE = 50020088
_UA = f'{_PKG}/{_VERSION_CODE} (Linux; U; Android 13; en_US; sdk_gphone64_x86_64; Build/TQ3A.230901.001; Cronet/145.0.7582.0)'

# Double base64 in the app: decoding the constant once more gives the real HMAC key.
_SECRET_DEFAULT = base64.b64decode(base64.b64decode('NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=='))

# subjectType=2 can be one season ("Breaking Bad S5") or a range ("Reacher S1-S4").
_SEASON_RANGE_RE = re.compile(r'\bS(\d+)(?:-S?(\d+))?\s*$', re.I)
# Trailing "[English]"/"[Multi Audio]"-style annotation some entries carry.
_BRACKET_SUFFIX_RE = re.compile(r'\s*\[[^\]]*\]\s*$')

_SCHEME = 'moviebox:'

_session = requests.Session()

_device_id_cache: Optional[str] = None
_token_cache: dict[str, tuple[str, float]] = {}
_token_cache_lock = threading.Lock()
_BEARER_TTL = 20 * 86400  # JWT itself is valid ~90d - refresh well before that


def _device_id() -> str:
    """Stable per-install random id, persisted so it doesn't change every process."""
    global _device_id_cache
    if _device_id_cache:
        return _device_id_cache
    row = cache.cache_get('moviebox_device_id', control.providercacheFile)
    if row and row.get('value'):
        _device_id_cache = row['value']
        return _device_id_cache
    _device_id_cache = secrets.token_hex(16)
    cache.cache_insert('moviebox_device_id', _device_id_cache, control.providercacheFile)
    return _device_id_cache


def _md5_hex(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()


def _x_client_token(ts: int) -> str:
    timestamp = str(ts)
    return f'{timestamp},{_md5_hex(timestamp[::-1].encode())}'


def _canonical_string(method: str, accept: str, content_type: str, url: str, body: Optional[str], ts: int) -> str:
    parsed = urlparse(url)
    path = parsed.path or ''
    query_params = parse_qs(parsed.query)
    query = '&'.join('&'.join(f'{key}={value}' for value in query_params[key]) for key in sorted(query_params))
    canonical_url = f'{path}?{query}' if query else path
    if body:
        body_bytes = body.encode('utf-8')
        body_hash = _md5_hex(body_bytes[:0x19000])
        body_length = str(len(body_bytes))
    else:
        body_hash = ''
        body_length = ''
    return '\n'.join([method.upper(), accept or '', content_type or '', body_length, str(ts), body_hash, canonical_url])


def _x_tr_signature(method: str, accept: str, content_type: str, url: str, body: Optional[str], ts: int) -> str:
    canonical = _canonical_string(method, accept, content_type, url, body, ts)
    signature = hmac.new(_SECRET_DEFAULT, canonical.encode('utf-8'), hashlib.md5).digest()
    return f'{ts}|2|{base64.b64encode(signature).decode()}'


def _client_info(minimal: bool = False) -> str:
    """Fake Android device fingerprint the app sends on every request."""
    device_id = _device_id()
    if minimal:
        # The bootstrap ping (fetching the anonymous bearer token) uses a shorter payload.
        payload = {
            'package_name': _PKG, 'version_name': _VERSION_NAME, 'version_code': _VERSION_CODE,
            'os': 'android', 'os_version': '13', 'device_id': device_id,
            'install_store': 'ps', 'system_language': 'en', 'net': 'NETWORK_WIFI',
            'region': 'US', 'timezone': 'Asia/Calcutta', 'sp_code': '',
        }
    else:
        payload = {
            'package_name': _PKG, 'version_name': _VERSION_NAME, 'version_code': _VERSION_CODE,
            'os': 'android', 'os_version': '13', 'install_ch': 'ps', 'device_id': device_id,
            'install_store': 'ps', 'gaid': '1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d',
            'brand': 'google', 'model': 'sdk_gphone64_x86_64', 'system_language': 'en',
            'net': 'NETWORK_WIFI', 'region': 'US', 'timezone': 'Asia/Calcutta', 'sp_code': '',
            'X-Play-Mode': '1', 'X-Idle-Data': '1', 'X-Family-Mode': '0', 'X-Content-Mode': '0',
        }
    return json.dumps(payload)


def _signed_headers(method: str, url: str, body: Optional[str], *, token: Optional[str] = None, minimal_client_info: bool = False) -> dict[str, str]:
    ts = int(time.time() * 1000)
    accept = 'application/json'
    content_type = 'application/json' if method == 'GET' else 'application/json; charset=utf-8'
    headers = {
        'user-agent': _UA,
        'accept': accept,
        'content-type': content_type,
        'connection': 'keep-alive',
        'x-client-token': _x_client_token(ts),
        'x-tr-signature': _x_tr_signature(method, accept, content_type, url, body, ts),
        'x-client-info': _client_info(minimal=minimal_client_info),
        'x-client-status': '0',
    }
    if token:
        headers['authorization'] = f'Bearer {token}'
    return headers


def _fetch_bearer_token() -> Optional[str]:
    """Mint a fresh anonymous bearer token - it rides back in the `x-user` response header."""
    try:
        headers = _signed_headers('GET', _PING_URL, None, minimal_client_info=True)
        resp = _session.get(_PING_URL, headers=headers, timeout=12)
        x_user = resp.headers.get('x-user') or resp.headers.get('X-User')
        if not x_user:
            return None
        return json.loads(x_user).get('token')
    except Exception:
        fflog_exc()
        return None


def _bearer_token(force: bool = False) -> Optional[str]:
    if not force:
        cached = _token_cache.get('bearer')
        if cached and (time.time() - cached[1]) < _BEARER_TTL:
            return cached[0]
    with _token_cache_lock:
        if not force:
            cached = _token_cache.get('bearer')
            if cached and (time.time() - cached[1]) < _BEARER_TTL:
                return cached[0]
            row = cache.cache_get('moviebox_bearer', control.providercacheFile)
            if row and row.get('value') and (time.time() - row.get('date', 0)) < _BEARER_TTL:
                _token_cache['bearer'] = (row['value'], row['date'])
                return row['value']
        token = _fetch_bearer_token()
        if token:
            now = time.time()
            _token_cache['bearer'] = (token, now)
            cache.cache_insert('moviebox_bearer', token, control.providercacheFile)
        return token


def _api_request(method: str, url: str, body: Optional[str] = None) -> Optional[dict]:
    """Signed GET/POST against the MovieBox API. Retries once with a fresh token on 401."""
    for retry in (False, True):
        token = _bearer_token(force=retry)
        headers = _signed_headers(method, url, body, token=token)
        try:
            if method == 'GET':
                resp = _session.get(url, headers=headers, timeout=15)
            else:
                resp = _session.post(url, data=body.encode('utf-8') if body else None, headers=headers, timeout=15)
        except Exception:
            fflog_exc()
            return None
        if resp.status_code == 401 and not retry:
            continue
        if resp.status_code != 200:
            return None
        try:
            data = resp.json()
        except Exception:
            return None
        if data.get('code') not in (0, None):
            return None
        return data.get('data')
    return None


def _search(query: str) -> list[dict]:
    url = _BASE + _SEARCH_PATH
    body = json.dumps({'page': 1, 'perPage': 20, 'keyword': query})
    data = _api_request('POST', url, body)
    if not data:
        return []
    subjects: list[dict] = []
    for block in data.get('results') or []:
        subjects.extend(block.get('subjects') or [])
    return subjects


_caption_cache: dict[tuple[str, str], list[dict]] = {}
_caption_cache_lock = threading.Lock()


def _fetch_captions(subject_id: str, stream_id: str) -> list[dict]:
    """extCaptions for one stream - cached so sources() and resolve() share it."""
    key = (subject_id, stream_id)
    with _caption_cache_lock:
        cached = _caption_cache.get(key)
        if cached is not None:
            return cached
    cap_url = f'{_BASE}{_CAPTIONS_PATH}?subjectId={subject_id}&streamId={stream_id}'
    data = _api_request('GET', cap_url)
    captions = (data or {}).get('extCaptions') or []
    with _caption_cache_lock:
        _caption_cache[key] = captions
    return captions


def _season_range_from_title(title: str) -> tuple[str, int, int]:
    """Strip trailing 'S<n>'/'S<n>-S<m>' + brackets -> (base_title, first, last).
    No marker -> (title, 1, 1)."""
    working = title
    for _ in range(2):  # bracket can come before or after the season marker
        match = _BRACKET_SUFFIX_RE.search(working)
        if not match:
            break
        working = working[:match.start()]
    match = _SEASON_RANGE_RE.search(working)
    if not match:
        return working.strip(), 1, 1
    first = int(match.group(1))
    last = int(match.group(2)) if match.group(2) else first
    return working[:match.start()].strip(), first, last


# ─── source ──────────────────────────────────────────────────────────────────────

class source:
    ffitem: FFItem

    priority: ClassVar[int] = 1
    language: ClassVar[List[str]] = ['en']

    # ── public api ─────────────────────────────────────────────────────────

    def movie(self, imdb: str, title: str, localtitle: str, aliases: list[SourceTitleAlias], year: str) -> Optional[str]:
        subject = self._find_subject(title, localtitle, year, want_season=False)
        return json.dumps({'id': subject['subjectId']}) if subject else None

    def tvshow(self, imdb: str, tvdb: str, tvshowtitle: str, localtvshowtitle: str, aliases: list[SourceTitleAlias], year: str) -> Optional[str]:
        # Season search needs a season number we don't have yet - defer to episode().
        return json.dumps({'title': tvshowtitle, 'localtitle': localtvshowtitle, 'year': year})

    def episode(self, url: Optional[str], imdb: str, tvdb: str, title: str, premiered: str, season: str, episode: str) -> Optional[str]:
        if not url:
            return None
        try:
            show = json.loads(url)
        except Exception:
            return None
        subject = self._find_subject(show.get('title', ''), show.get('localtitle', ''), show.get('year', ''), want_season=True, season=int(season))
        if not subject:
            return None
        return json.dumps({'id': subject['subjectId'], 'season': int(season), 'episode': int(episode)})

    def sources(self, url: Optional[str], hostDict: List[str], hostprDict: List[str]) -> 'List[SourceItem]':
        results: List[SourceItem] = []
        if not url:
            return results
        try:
            info = json.loads(url)
        except Exception:
            return results
        subject_id = info.get('id')
        if not subject_id:
            return results

        play_url = f'{_BASE}{_PLAYINFO_PATH}?subjectId={subject_id}'
        season, episode = info.get('season'), info.get('episode')
        if season and episode:
            play_url += f'&se={season}&ep={episode}'

        data = _api_request('GET', play_url)
        if not data:
            return results

        for stream in data.get('streams') or []:
            file_url = stream.get('url')
            if not file_url:
                continue
            # e.g. "1080" for a single-resolution MP4, or "1080,720,480" for an
            # adaptive entry listing every rung - either way we label by the ceiling.
            heights = [int(height_str) for height_str in re.findall(r'\d+', str(stream.get('resolutions') or ''))]
            height = max(heights, default=0)
            quality = quality_from_resolution(height=height)
            sign_cookie = stream.get('signCookie') or ''
            if sign_cookie:
                file_url += append_headers({'Cookie': sign_cookie})
            stream_id = str(stream.get('id', ''))
            locator = f'{_SCHEME}{subject_id}:{stream_id}:{file_url}'
            captions = _fetch_captions(str(subject_id), stream_id) if stream_id else []
            has_pl_sub = any((caption.get('lan') or '').lower() == 'pl' for caption in captions)
            results.append({
                'source': 'MovieBox',
                'quality': quality,
                'language': 'en',
                'url': locator,
                'info': stream.get('codecName', '') or '',
                'info2': 'NAPISY' if has_pl_sub else '',
                'size': convert_size(int(stream['size'])) if stream.get('size') else '',
                'filename': '',
                'direct': True,
                'debridonly': False,
            })
        fflog(f'sources: {len(results)}')
        return results

    def resolve(self, url: str) -> Optional[str]:
        if not url.startswith(_SCHEME):
            return url
        try:
            _, subject_id, stream_id, cdn_url = url.split(':', 3)
        except ValueError:
            return None
        self._attach_captions(subject_id, stream_id)
        # 'isa+' makes player.py parse the |Cookie=... suffix into ISA headers;
        # DRMFF's dict path does url.split('|')[-1] instead, which breaks here.
        if '.mpd' in cdn_url.split('|', 1)[0]:
            return f'isa+{cdn_url}'
        return cdn_url

    # ── helpers ────────────────────────────────────────────────────────────

    def _find_subject(self, title: str, localtitle: str, year: str, want_season: bool, season: int = 1) -> Optional[dict]:
        want_type = 2 if want_season else 1
        for query in search_queries(title, localtitle):
            fflog(f'query: {query!r}')
            target = cleantitle.get(query)
            for subject in _search(query):
                if subject.get('subjectType') != want_type:
                    continue
                base_title, season_first, season_last = _season_range_from_title(subject.get('title', ''))
                if cleantitle.get(base_title) != target:
                    continue
                if want_season and not (season_first <= season <= season_last):
                    continue
                if not want_season and year:
                    release_year = (subject.get('releaseDate') or '')[:4]
                    if release_year and not year_matches(release_year, int(year)):
                        continue
                fflog(f"matched {subject.get('title')!r} -> subjectId={subject.get('subjectId')}")
                return subject
        fflog(f'no match for {title!r}')
        return None

    def _attach_captions(self, subject_id: str, stream_id: str) -> None:
        if not stream_id:
            return
        captions = _fetch_captions(subject_id, stream_id)
        if not captions:
            return
        try:
            with ThreadPoolExecutor(max_workers=len(captions)) as pool:
                subtitle_urls = [url for url in pool.map(self._proxy_caption, captions) if url]
            if subtitle_urls:
                control.window().setProperty('source.subtitles', json.dumps(subtitle_urls))
                fflog(f'{len(subtitle_urls)} subtitle(s) passed to player')
        except Exception:
            fflog_exc()

    @staticmethod
    def _proxy_caption(caption: dict) -> Optional[str]:
        """Re-serve locally under a name Kodi can read a language code from -
        the CDN's hash filenames give it nothing to guess from otherwise."""
        file_url = caption.get('url')
        if not file_url:
            return None
        try:
            resp = _session.get(file_url, timeout=15)
            if resp.status_code != 200:
                return None
            ext = file_url.split('?', 1)[0].rsplit('.', 1)[-1] or 'srt'
            lan = caption.get('lan') or 'und'
            key = f'/moviebox_sub_{caption.get("id", "")}.{lan}.{ext}'
            service_client.set_media_files({key: resp.text})
            return urljoin(service_client.url, '/media') + key
        except Exception:
            fflog_exc()
            return None
