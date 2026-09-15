# -*- coding: utf-8 -*-
"""
FanFilm - source: 4KHDHub
Copyright (C) 2026 :)

Dystrybuowane na licencji MIT <https://mit-license.org>

Domain self-heals via phisher98/TVVVV's domains.json (key "4khdhub").
Releases are scraped straight off the movie/show page; final links go
through HubCloud-family lockers (same extractor CloudStream ships) -
see _resolve_locker. HubDrive links are dropped, that domain is currently
login-gated.
"""

from __future__ import annotations

import json
import re
import time
from typing import ClassVar, List, Optional, TYPE_CHECKING
from urllib.parse import urljoin, urlparse

from lib.ff import cache, cleantitle, control, requests
from lib.ff.client import parseDOM
from lib.ff.item import FFItem
from lib.ff.source_utils import DEFAULT_UA, quality_from_resolution, search_queries, year_matches
from lib.ff.log_utils import fflog, fflog_exc

if TYPE_CHECKING:
    from lib.sources import SourceItem, SourceTitleAlias

_DOMAINS_URL = 'https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json'
_DEFAULT_BASE = 'https://4khdhub.one'
_DOMAINS_TTL = 12 * 3600

_HEADERS = {'User-Agent': DEFAULT_UA}

_SLUG_TYPE_RE = re.compile(r'-(movie|series)-\d+/?$')
_TITLE_YEAR_RE = re.compile(r'^(.*?)\s*\((\d{4})\)\s*$')
_EPISODE_BADGE_RE = re.compile(r'Episode-0*(\d+)', re.I)
_SEASON_NUM_RE = re.compile(r'\d+')
_RESOLUTION_RE = re.compile(r'(\d{3,4})[pP]')
_SIZE_RE = re.compile(r'(\d+(?:\.\d+)?\s*[GM]B)', re.I)

# Non-Western dubs bundled alongside English are still just an English source
# for a PL/EN audience, not a meaningful MULTI - same policy as netmirror.py's
# _NON_WESTERN_LANGS. 4KHDHub titles spell languages out by name, not code.
_NON_WESTERN_NAMES = frozenset({
    'hindi', 'tamil', 'telugu', 'kannada', 'malayalam', 'bengali', 'marathi',
    'punjabi', 'gujarati', 'urdu', 'odia', 'assamese', 'bhojpuri',
})
_LANG_NAME_RE = re.compile(
    r'\b(Hindi|Tamil|Telugu|Kannada|Malayalam|Bengali|Marathi|Punjabi|Gujarati|'
    r'Urdu|Odia|Assamese|Bhojpuri|English|French|German|Spanish|Italian|'
    r'Japanese|Korean|Chinese|Russian|Polish|Portuguese)\b',
    re.I,
)

_SCHEME = 'fourkhdhub:'

_session = requests.Session()

_base_cache: Optional[str] = None
_base_cache_ts = 0.0


def _base_url() -> str:
    global _base_cache, _base_cache_ts
    if _base_cache and (time.time() - _base_cache_ts) < _DOMAINS_TTL:
        return _base_cache
    row = cache.cache_get('fourkhdhub_base', control.providercacheFile)
    if row and row.get('value') and (time.time() - row.get('date', 0)) < _DOMAINS_TTL:
        _base_cache, _base_cache_ts = row['value'], row['date']
        return _base_cache
    try:
        resp = _session.get(_DOMAINS_URL, headers=_HEADERS, timeout=10)
        base = (resp.json() or {}).get('4khdhub') if resp.status_code == 200 else None
    except Exception:
        base = None
    _base_cache = (base or _DEFAULT_BASE).rstrip('/')
    _base_cache_ts = time.time()
    cache.cache_insert('fourkhdhub_base', _base_cache, control.providercacheFile)
    return _base_cache


def _search(query: str) -> list[dict]:
    """One search page -> [{'href', 'title', 'type'}]. type: 'movie'/'series'/None."""
    base = _base_url()
    try:
        resp = _session.get(f'{base}/', params={'s': query}, headers=_HEADERS, timeout=15)
        if resp.status_code != 200:
            return []
    except Exception:
        fflog_exc()
        return []
    hrefs = parseDOM(resp.text, 'a', attrs={'class': 'movie-card'}, ret='href')
    labels = parseDOM(resp.text, 'a', attrs={'class': 'movie-card'}, ret='aria-label')
    results = []
    for href, label in zip(hrefs, labels):
        match = _SLUG_TYPE_RE.search(href)
        results.append({
            'href': href if href.startswith('http') else urljoin(base + '/', href.lstrip('/')),
            'title': re.sub(r'\s+details\s*$', '', label or '', flags=re.I).strip(),
            'type': match.group(1) if match else None,
        })
    return results


def _resolve_locker(url: str) -> Optional[str]:
    """HubCloud-family locker page -> direct playable URL."""
    try:
        if 'hubcloud.php' not in url:
            base = f"{urlparse(url).scheme}://{urlparse(url).netloc}"
            resp = _session.get(url, headers=_HEADERS, timeout=15)
            hrefs = parseDOM(resp.text, 'a', attrs={'id': 'download'}, ret='href')
            if not hrefs:
                return None
            href = hrefs[0]
            url = href if href.startswith('http') else f"{base.rstrip('/')}/{href.lstrip('/')}"

        resp = _session.get(url, headers=_HEADERS, timeout=15)
        html = resp.text
        buttons = parseDOM(html, 'a', attrs={'class': 'btn[^"]*'})
        button_hrefs = parseDOM(html, 'a', attrs={'class': 'btn[^"]*'}, ret='href')

        for text, link in zip(buttons, button_hrefs):
            label = re.sub('<[^>]+>', '', text).strip().lower()
            if 'buzzserver' in label:
                buzz = _session.get(f'{link}/download', headers={**_HEADERS, 'Referer': link}, timeout=15, allow_redirects=False)
                redirect = buzz.headers.get('hx-redirect') or buzz.headers.get('HX-Redirect')
                if redirect:
                    return redirect
                continue
            if 'pixeldra' in label or 'pixel' in label:
                if 'download' in link:
                    return link
                pbase = f"{urlparse(link).scheme}://{urlparse(link).netloc}"
                return f"{pbase}/api/file/{link.rsplit('/', 1)[-1]}?download"
            if any(name in label for name in ('fsl server', 'download file', 's3 server', 'fslv2', 'mega server', 'pdl server')):
                return link
        # Fallback: an unrecognised single button is usually still a direct link.
        return button_hrefs[0] if button_hrefs else None
    except Exception:
        fflog_exc()
        return None


def _release_size(block_html: str) -> str:
    """Size badge lives next to the title, not inside file-title's own text."""
    match = _SIZE_RE.search(block_html)
    return match.group(1) if match else ''


def _releases_from_download_items(html: str) -> list[dict]:
    """Parse a sequence of div.download-item blocks into release dicts."""
    blocks = parseDOM(html, 'div', attrs={'class': 'download-item[^"]*'})
    releases = []
    for block in blocks:
        file_title = parseDOM(block, 'div', attrs={'class': 'file-title'})
        title_text = file_title[0].strip() if file_title else ''
        hrefs = parseDOM(block, 'a', attrs={'class': 'btn[^"]*'}, ret='href')
        if not hrefs:
            continue
        releases.append({'title': title_text, 'links': hrefs, 'size': _release_size(block)})
    return releases


# ─── source ──────────────────────────────────────────────────────────────────────

class source:
    ffitem: FFItem

    priority: ClassVar[int] = 1
    language: ClassVar[List[str]] = ['en']

    # ── public api ─────────────────────────────────────────────────────────

    def movie(self, imdb: str, title: str, localtitle: str, aliases: list[SourceTitleAlias], year: str) -> Optional[str]:
        href = self._find_page(title, localtitle, year, want_type='movie')
        return json.dumps({'href': href}) if href else None

    def tvshow(self, imdb: str, tvdb: str, tvshowtitle: str, localtvshowtitle: str, aliases: list[SourceTitleAlias], year: str) -> Optional[str]:
        return json.dumps({'title': tvshowtitle, 'localtitle': localtvshowtitle, 'year': year})

    def episode(self, url: Optional[str], imdb: str, tvdb: str, title: str, premiered: str, season: str, episode: str) -> Optional[str]:
        if not url:
            return None
        try:
            show = json.loads(url)
        except Exception:
            return None
        href = self._find_page(show.get('title', ''), show.get('localtitle', ''), show.get('year', ''), want_type='series')
        if not href:
            return None
        return json.dumps({'href': href, 'season': int(season), 'episode': int(episode)})

    def sources(self, url: Optional[str], hostDict: List[str], hostprDict: List[str]) -> 'List[SourceItem]':
        results: List[SourceItem] = []
        if not url:
            return results
        try:
            info = json.loads(url)
        except Exception:
            return results
        href = info.get('href')
        if not href:
            return results
        try:
            resp = _session.get(href, headers=_HEADERS, timeout=15)
            if resp.status_code != 200:
                return results
            html = resp.text
        except Exception:
            fflog_exc()
            return results

        season, episode = info.get('season'), info.get('episode')
        if season and episode:
            releases = self._episode_releases(html, season, episode)
        else:
            releases = _releases_from_download_items(html)

        for release in releases:
            if 'english' not in release['title'].lower():
                continue  # Hindi/regional-only release, not useful for FanFilm's EN audience
            heights = [int(h) for h in _RESOLUTION_RE.findall(release['title'])]
            quality = quality_from_resolution(height=max(heights, default=0))
            langs = {name.lower() for name in _LANG_NAME_RE.findall(release['title'])}
            extra_langs = langs - {'english'} - _NON_WESTERN_NAMES
            info2 = 'MULTI' if extra_langs else ''
            for link in release['links']:
                if 'hubdrive' in urlparse(link).netloc:
                    continue  # currently serves a login-gated dashboard, not a file page
                locator = f'{_SCHEME}{link}'
                results.append({
                    'source': '4KHDHub',
                    'quality': quality,
                    'language': 'en',
                    'url': locator,
                    'info': info2,
                    'size': release.get('size', ''),
                    'filename': release['title'],
                    'direct': False,
                    'debridonly': False,
                })
        fflog(f'sources: {len(results)}')
        return results

    def resolve(self, url: str) -> Optional[str]:
        if not url.startswith(_SCHEME):
            return url
        locker_url = url[len(_SCHEME):]
        return _resolve_locker(locker_url)

    # ── helpers ────────────────────────────────────────────────────────────

    def _find_page(self, title: str, localtitle: str, year: str, want_type: str) -> Optional[str]:
        for query in search_queries(title, localtitle):
            fflog(f'query: {query!r}')
            target = cleantitle.get(query)
            for candidate in _search(query):
                if candidate['type'] != want_type:
                    continue
                if cleantitle.get(candidate['title']) != target:
                    continue
                if not year:
                    fflog(f"matched {candidate['title']!r} -> {candidate['href']}")
                    return candidate['href']
                # Search results carry no year - confirm via the detail page's h1.
                try:
                    resp = _session.get(candidate['href'], headers=_HEADERS, timeout=15)
                    page_title = parseDOM(resp.text, 'h1', attrs={'class': 'page-title'})
                except Exception:
                    page_title = []
                match = _TITLE_YEAR_RE.match(page_title[0].strip()) if page_title else None
                if match and not year_matches(match.group(2), int(year)):
                    continue
                fflog(f"matched {candidate['title']!r} -> {candidate['href']}")
                return candidate['href']
        fflog(f'no match for {title!r}')
        return None

    @staticmethod
    def _episode_releases(html: str, season: int, episode: int) -> list[dict]:
        # a season can span several blocks (different release groups) - collect all
        season_blocks = parseDOM(html, 'div', attrs={'class': 'season-item episode-item[^"]*'})
        releases = []
        for block in season_blocks:
            season_label = parseDOM(block, 'div', attrs={'class': 'episode-number'})
            season_num_match = _SEASON_NUM_RE.search(season_label[0]) if season_label else None
            if not season_num_match or int(season_num_match.group(0)) != season:
                continue
            episode_items = parseDOM(block, 'div', attrs={'class': 'episode-download-item'})
            for item in episode_items:
                badge = parseDOM(item, 'span', attrs={'class': 'badge-psa'})
                episode_match = _EPISODE_BADGE_RE.search(badge[0]) if badge else None
                if not episode_match or int(episode_match.group(1)) != episode:
                    continue
                file_title = parseDOM(item, 'div', attrs={'class': 'episode-file-title'})
                hrefs = parseDOM(item, 'a', attrs={'class': 'btn[^"]*'}, ret='href')
                if hrefs:
                    releases.append({
                        'title': file_title[0].strip() if file_title else '',
                        'links': hrefs,
                        'size': _release_size(item),
                    })
        return releases
