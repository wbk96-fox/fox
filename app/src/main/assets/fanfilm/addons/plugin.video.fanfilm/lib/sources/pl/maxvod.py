# -*- coding: utf-8 -*-
"""
FanFilm - źródło: maxvod.tv
Copyright (C) 2026 :)

Dystrybuowane na licencji MIT <https://mit-license.org>

Wyszukiwanie po /search, odtwarzacz zwraca listę SOURCES (uuid + mediaType) w
inline JS, a /api/player/url/{uuid} + /player/play/{uuid} (Location header)
daje właściwy link: bezpośredni mp4 (mediaType=video) albo embed zewnętrznego
hostingu jak voe.sx/vidoza.net (mediaType=iframe, przez resolveurl). Wszystkie
zapytania wymagają Refereru z domeny maxvod.tv.
"""

from __future__ import annotations

import json
import re
import time
from typing import ClassVar, Dict, List, Optional, Tuple, TYPE_CHECKING

from lib.ff import requests
from lib.ff import cleantitle
from lib.ff.source_utils import (DEFAULT_UA, append_headers, get_quality,
                                 get_lang_by_type, search_queries_extended)
from lib.ff.log_utils import fflog, fflog_exc

if TYPE_CHECKING:
    from lib.ff.item import FFItem
    from lib.sources import SourceItem, SourceTitleAlias


_RE_SOURCES = re.compile(r'const\s+SOURCES\s*=\s*(\[.*?\]);')
_RE_YEAR = re.compile(r'bi-calendar3[^>]*></i>\s*(\d{4})')
_RE_TITLE_YEAR = re.compile(r'^(.*?)\s*\((\d{4})(?:-\d{4})?\)\s*$')
_RE_SLUG_YEAR = re.compile(r'-(\d{4})$')
# maxvod throttles per-IP with a HTTP 200 page carrying this notice instead of results
_RATE_LIMIT_MARKER = 'Zbyt wiele zapyta'
_QUALITY_MAP = {'4K': '4K', 'HD': '1080p', 'SD': 'SD', 'CAM': 'CAM',
                'WYSOKA': '1080p', 'NISKA': '720p'}


# ─── source ──────────────────────────────────────────────────────────────────────

class source:
    ffitem: 'FFItem'

    priority: ClassVar[int] = 1
    language: ClassVar[List[str]] = ['pl']

    def __init__(self) -> None:
        self.base_link = 'https://maxvod.tv'
        self.session = requests.Session()
        self.session.headers.update({
            'User-Agent': DEFAULT_UA,
            'Referer': self.base_link + '/',
        })

    # ── public api ─────────────────────────────────────────────────────────

    def movie(self, imdb: str, title: str, localtitle: str,
              aliases: 'list[SourceTitleAlias]', year: str) -> Optional[str]:
        return self._find(title, localtitle, year, 'movie')

    def tvshow(self, imdb: str, tvdb: str, tvshowtitle: str, localtvshowtitle: str,
               aliases: 'list[SourceTitleAlias]', year: str) -> Optional[str]:
        return self._find(tvshowtitle, localtvshowtitle, year, 'series')

    def episode(self, url: Optional[str], imdb: str, tvdb: str, title: str,
                premiered: str, season: str, episode: str) -> Optional[str]:
        if not url:
            return None
        return f'{url}?sezon={int(season)}&odcinek={int(episode)}'

    def sources(self, url: Optional[str], hostDict: List[str],
                hostprDict: List[str]) -> 'List[SourceItem]':
        if not url:
            return []
        player_path = self._player_path(url)
        if not player_path:
            return []

        resp = self._get(f'{self.base_link}{player_path}')
        if resp is None or not resp.ok:
            fflog(f'player HTTP {getattr(resp, "status_code", "n/a")}')
            return []

        # for a season/episode that doesn't exist on the site, the player silently
        # serves S01E01 instead of erroring out - catch that before trusting SOURCES
        wanted = _episode_pair(url, 'sezon', 'odcinek')
        if wanted and wanted != _episode_pair(resp.text, 'EP_SEZON', 'EP_ODCINEK'):
            fflog(f'episode mismatch: wanted {wanted}')
            return []

        results: 'List[SourceItem]' = []
        for src in self._extract_sources(resp.text):
            item = self._build_source(src)
            if item is not None:
                results.append(item)

        fflog(f'{len(results)} source(s)')
        return results

    def resolve(self, url: str) -> Optional[str]:
        # maxvod points at CDN hosts (bare "IP:port") that are often down/geo-blocked;
        # a dead one hangs Kodi ~90s on CCurlFile::Stat, so bail out with a clean
        # DeadLink instead. Probed here (on click) - not during source search.
        if not self._reachable(url.split('|', 1)[0]):
            fflog(f'unreachable stream host: {url[:60]}')
            return None
        return url

    # ── helpers ────────────────────────────────────────────────────────────

    def _player_path(self, url: str) -> Optional[str]:
        if url.startswith('/movie/'):
            return f'/player/{url[len("/movie/"):]}'
        if url.startswith('/series/'):
            return f'/player/series/{url[len("/series/"):]}'
        return None

    def _get(self, url: str, **kwargs) -> 'Optional[requests.Response]':
        """GET that transparently rides out maxvod's per-IP throttle (a HTTP 200
        page with _RATE_LIMIT_MARKER instead of content) with one short retry."""
        for attempt in range(2):
            try:
                resp = self.session.get(url, timeout=15, **kwargs)
            except Exception:
                fflog_exc()
                return None
            if resp.ok and _RATE_LIMIT_MARKER in resp.text:
                fflog(f'rate-limited on {url} (attempt {attempt + 1})')
                if attempt == 0:
                    time.sleep(5)
                    continue
                return None
            return resp
        return None

    def _find(self, title: str, localtitle: str, year: str, media_type: str) -> Optional[str]:
        targets = {cleantitle.normalize(cleantitle.getsearch(t))
                  for t in (title, localtitle) if t}
        try:
            for query in search_queries_extended(localtitle, title):
                candidates = self._search(query, media_type)
                fallback_slug = ''
                for slug, cand_title in candidates:
                    clean_title, cand_year = _split_title_year(cand_title)
                    # maxvod lists combined "Localtitle / Origtitle" labels, so match on any part too
                    cand_names = {cleantitle.normalize(cleantitle.getsearch(p))
                                  for p in (clean_title, *clean_title.split('/'))}
                    if not (cand_names & targets):
                        continue
                    if not year:
                        # no year to check against - first name match is as good as any
                        fallback_slug = fallback_slug or slug
                        continue
                    # trust the year in the label, then a matching "-YYYY" slug suffix
                    # (most slugs have one), and only pay for a detail fetch otherwise
                    if cand_year:
                        year_match = cand_year == str(year)
                    elif _slug_year(slug) == str(year):
                        year_match = True
                    else:
                        year_match = self._check_year(media_type, slug, year)
                    if year_match:
                        return f'/{media_type}/{slug}'
                if fallback_slug:
                    return f'/{media_type}/{fallback_slug}'
        except Exception:
            fflog_exc()
        return None

    def _search(self, query: str, media_type: str) -> 'List[Tuple[str, str]]':
        resp = self._get(f'{self.base_link}/search', params={'q': query})
        if resp is None or not resp.ok:
            return []
        pattern = re.compile(rf'href="/{media_type}/([a-z0-9-]+)".*?<img[^>]*alt="([^"]*)"', re.DOTALL)
        results = pattern.findall(resp.text)
        fflog(f'search {query!r} ({media_type}) -> {len(results)} result(s)')
        return results

    def _check_year(self, media_type: str, slug: str, year: str) -> bool:
        resp = self._get(f'{self.base_link}/{media_type}/{slug}')
        if resp is None or not resp.ok:
            return False
        match = _RE_YEAR.search(resp.text)
        return bool(match) and match.group(1) == str(year)

    def _extract_sources(self, player_html: str) -> 'List[Dict]':
        match = _RE_SOURCES.search(player_html)
        if not match:
            return []
        try:
            return json.loads(match.group(1))
        except json.JSONDecodeError:
            fflog_exc()
            return []

    def _build_source(self, src: 'Dict') -> 'Optional[SourceItem]':
        uuid = src.get('uuid')
        is_direct = src.get('mediaType') == 'video'
        if not uuid or src.get('mediaType') not in ('video', 'iframe'):
            return None
        stream_url = self._resolve_stream(uuid, src.get('variant', ''))
        if not stream_url:
            return None

        lang, info = get_lang_by_type(str(src.get('langLabel') or src.get('langKey') or ''))
        if is_direct:
            headers = {'User-Agent': DEFAULT_UA, 'Referer': self.base_link + '/'}
            stream_url += append_headers(headers)

        label = _host_label(stream_url) or 'MaxVOD'

        return {
            'source': label,
            'quality': _quality(str(src.get('quality') or '')),
            'language': lang or 'pl',
            'info': info,
            'url': stream_url,
            'direct': is_direct,
            'debridonly': False,
            'filename': '',
            'premium': False,
        }

    def _reachable(self, url: str) -> bool:
        """Cheap liveness probe - any HTTP reply means the host is up; a connection
        error or timeout means Kodi would just hang on it, so treat it as dead."""
        try:
            resp = self.session.get(url, stream=True, timeout=6)
            resp.close()
            return True
        except Exception:
            return False

    def _resolve_stream(self, uuid: str, variant: str = '') -> Optional[str]:
        try:
            api_url = f'{self.base_link}/api/player/url/{uuid}'
            if variant == 'original':
                api_url += '?original=1'
            resp = self._get(api_url)
            if resp is None or not resp.ok:
                return None
            rel = resp.json().get('url')
            if not rel:
                return None
            play_resp = self._get(f'{self.base_link}{rel}', allow_redirects=False)
            return play_resp.headers.get('Location') if play_resp is not None else None
        except Exception:
            fflog_exc()
            return None


# ─── module helpers ──────────────────────────────────────────────────────────────


def _episode_pair(text: str, key1: str, key2: str) -> 'Optional[Tuple[str, str]]':
    match = re.search(rf'{key1}\D*?(\d+).*?{key2}\D*?(\d+)', text, re.DOTALL)
    return match.groups() if match else None


def _slug_year(slug: str) -> str:
    match = _RE_SLUG_YEAR.search(slug)
    return match.group(1) if match else ''


def _split_title_year(raw: str) -> 'Tuple[str, str]':
    # cleantitle.getsearch() doesn't strip parens, so a year suffixed to the title must be cut separately
    match = _RE_TITLE_YEAR.match(raw)
    if not match:
        return raw, ''
    return match.group(1), match.group(2)


def _quality(raw: str) -> str:
    """maxvod reports a coarse label ('HD'/'4K'/...) or nothing at all for its own
    CDN 'Player 1' source; get_quality() only parses detailed resolution strings
    ('1080p' etc.) and would misread both 'HD' and empty as SD."""
    upper = raw.strip().upper()
    if not upper:
        return '1080p'
    return _QUALITY_MAP.get(upper) or get_quality(raw)


def _host_label(url: str) -> str:
    """Extract a short host label from a stream URL (e.g. 'Voe' from 'https://voe.sx/...').
    Bare IP addresses (maxvod CDN) return empty string so caller can fall back."""
    from urllib.parse import urlparse
    host = urlparse(url).hostname or ''
    # bare IP — maxvod CDN, not a named host
    if host and host[0].isdigit():
        return ''
    # strip leading 'www.'
    if host.startswith('www.'):
        host = host[4:]
    # take the domain name before TLD as label, capitalised
    parts = host.split('.')
    if len(parts) >= 2:
        return parts[-2].capitalize()
    return host.capitalize()
