# -*- coding: utf-8 -*-
"""
FanFilm - source: Filmu (box.filmu.in / vidbolt.xyz backend)

Dystrybuowane na licencji MIT <https://mit-license.org>

box.filmu.in is a client-agnostic multi-extractor backend used internally
by vidbolt.xyz (one of ~8 embed players fronted by the cineby.rocks router).
It has no CORS/session gating for server-to-server calls - only a static
`x-api-key` header, hardcoded in vidbolt.xyz's public JS bundle (extracted
by grepping the minified source, no network capture needed):

    GET https://box.filmu.in/scrape/<Extractor>/<movie|tv>/<imdbId or "tmdb"+tmdbId>
        ?tmdbId=<id>&title=<title>&year=<year>[&season=<s>&episode=<e>]
    Header: x-api-key: 09eb429913afb6b1cc90f23746f41fb3279aed77726c625c40672b81444c0bac

Querying an unknown extractor name returns the full valid list in the error
body - that is how _EXTRACTORS below was enumerated. One extractor, Quasar,
is not on that list and only reachable through vidbolt.xyz's own same-origin
proxy (`/api/scraper?path=...`, no key needed there) - most sources it
returns are direct CDN links with a request-scoped `auth_key`/`expire` pair
baked into the URL and need no extra headers at all (verified with a bare
curl - same "no hotlink protection once you have the URL" shape as Cineby).

UHDMovies is deliberately excluded - it serves Google Drive download links,
which are unstable/rate-limited (see [[dahmermovies-removed]] session notes
on Drive's lack of proper Range support).

Quality/type/headers come back per-source already normalized by the
backend, so there is nothing to reverse-engineer there - just relay it.
Several extractors' `auth_key`/`expire` tokens look short-lived, so sources()
stores (extractor, mode, index) rather than the raw URL and resolve()
re-fetches that one extractor fresh right before playback, same pattern as
rivestream.py.
"""

from __future__ import annotations

import re
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import ClassVar, TYPE_CHECKING
from typing_extensions import TypeAlias
from urllib.parse import urlencode, parse_qs

from lib.ff import requests
from lib.ff.source_utils import DEFAULT_UA, append_headers
from lib.ff.log_utils import fflog, fflog_exc

if TYPE_CHECKING:
    from lib.ff.item import FFItem
    from lib.sources import SourceItem, SourceTitleAlias

# (raw source dict from the extractor's JSON, extractor name, index within
# that extractor's own source list) - what sources()/resolve() pass around
# instead of a raw URL, since auth_key/expire tokens look short-lived.
_RawSource: TypeAlias = 'tuple[dict, str, int]'

_UNRELIABLE_PROXY: str = 'scraper.vidbolt.xyz'
_BOX_BASE: str = 'https://box.filmu.in'
_BOX_KEY: str = '09eb429913afb6b1cc90f23746f41fb3279aed77726c625c40672b81444c0bac'
_VIDBOLT_BASE: str = 'https://vidbolt.xyz'
_HEADERS: dict[str, str] = {'User-Agent': DEFAULT_UA, 'Accept': 'application/json'}
_SCHEME: str = 'filmu://'
_ITEM_SCHEME: str = 'filmu_item://'

# name -> access mode ('box' = direct box.filmu.in call with x-api-key,
# 'vidbolt' = same-origin vidbolt.xyz proxy, no key). box.filmu.in's error
# body (bogus extractor name) revealed ~28 extractors total, but most came
# back empty for every test title tried (Inception/Barbie/The Matrix) and
# one, NetNaija, returned mismatched results for unrelated titles - so this
# list only keeps the ones with confirmed, real, playable output. UHDMovies
# was excluded separately (Google Drive - unstable).
_EXTRACTORS: dict[str, str] = {
    'Vaplayer': 'box',
    'Videasy': 'box',
    'Quasar': 'vidbolt',
    'Moviebox': 'vidbolt',  # only reachable this way - box.filmu.in's own "MovieBox" is empty
}

_session: requests.Session = requests.Session()
_session.headers.update(_HEADERS)

# lib/ff/sources.py sourcesFilter has a closed language_order list (pl, mul,
# multi, en, de, fr, it, es, pt, ko, ru, -, '') - anything else raises
# KeyError there and kills the WHOLE source list, not just ours.
_LANG_MAP: dict[str, str] = {
    'english': 'en', 'original': 'en', 'german': 'de', 'french': 'fr',
    'italian': 'it', 'spanish': 'es', 'portuguese': 'pt', 'korean': 'ko',
    'russian': 'ru', 'polish': 'pl',
}
_NON_EN_REAL_LANGS: set[str] = {
    'hindi', 'tamil', 'telugu', 'malayalam', 'kannada', 'bengali', 'punjabi',
    'marathi', 'gujarati', 'ost', 'dual audio', 'multi-audio',
}


# ─── source ──────────────────────────────────────────────────────────────────────

class source:
    priority: ClassVar[int] = 1
    language: ClassVar[list[str]] = ['en']

    ffitem: FFItem

    # ── public api ─────────────────────────────────────────────────────────

    def movie(self, imdb: str, title: str, localtitle: str,
              aliases: list[SourceTitleAlias], year: str) -> str | None:
        tmdb = str(self.ffitem.tmdb_id or '')
        if not tmdb or not title:
            return None
        return _SCHEME + urlencode({'type': 'movie', 'tmdb': tmdb, 'imdb': imdb or '',
                                     'title': title, 'year': str(year or '')})

    def tvshow(self, imdb: str, tvdb: str, tvshowtitle: str, localtvshowtitle: str,
               aliases: list[SourceTitleAlias], year: str) -> str | None:
        show_item = getattr(self.ffitem, 'show_item', None)
        tmdb = str((show_item.tmdb_id if show_item else None) or self.ffitem.tmdb_id or '')
        if not tmdb or not tvshowtitle:
            return None
        return _SCHEME + urlencode({'type': 'tv', 'tmdb': tmdb, 'imdb': imdb or '',
                                     'title': tvshowtitle, 'year': str(year or '')})

    def episode(self, url: str | None, imdb: str, tvdb: str, title: str,
                premiered: str, season: str, episode: str) -> str | None:
        if not url:
            return None
        return f'{url}&s={int(season)}&e={int(episode)}'

    def sources(self, url: str | None, hostDict: list[str],
                hostprDict: list[str]) -> list[SourceItem]:
        if not url:
            return []
        params = _decode(url)
        if not params.get('tmdb') or not params.get('title'):
            return []

        raw_items: list[_RawSource] = []
        with ThreadPoolExecutor(max_workers=len(_EXTRACTORS)) as executor:
            futures = {
                executor.submit(self._fetch_extractor, name, mode, params): name
                for name, mode in _EXTRACTORS.items()
            }
            for future in as_completed(futures, timeout=25):
                try:
                    for item in future.result():
                        raw_items.append(item)
                except Exception:
                    fflog_exc()

        fflog(f'filmu: {len(raw_items)} raw sources from {len(_EXTRACTORS)} extractors')

        result: list[SourceItem] = []
        for raw, extractor, index in raw_items:
            stream_url = raw.get('url')
            if not stream_url:
                continue
            quality = _norm_quality(raw.get('quality') or '')
            language, info = _norm_lang(raw.get('language') or '')
            item_url = _ITEM_SCHEME + urlencode({**params, 'extractor': extractor, 'index': index})
            result.append({
                'source': _clean_name(raw.get('name'), extractor),
                'quality': quality,
                'language': language,
                'info': info,
                'url': item_url,
                'direct': True,
                'debridonly': False,
            })

        fflog(f'filmu: {len(result)} playable sources')
        return result

    def resolve(self, url: str) -> str | None:
        if not url.startswith(_ITEM_SCHEME):
            return None
        params = _decode(url, scheme=_ITEM_SCHEME)
        extractor = params.get('extractor')
        mode = _EXTRACTORS.get(extractor or '')
        try:
            index = int(params.get('index', ''))
        except ValueError:
            return None
        if not extractor or not mode:
            return None

        items: list[_RawSource] = self._fetch_extractor(extractor, mode, params)
        if index >= len(items):
            fflog(f'filmu: resolve - index {index} out of range for {extractor!r} (link expired?)')
            return None
        raw = items[index][0]
        stream_url = raw.get('url')
        if not stream_url:
            return None
        headers = raw.get('headers') or {}
        final = stream_url + (append_headers(headers) if headers else '')
        is_hls = 'm3u8' in (raw.get('type') or '') or '.m3u8' in stream_url
        return f'isa+{final}' if is_hls else final

    # ── helpers ────────────────────────────────────────────────────────────

    @staticmethod
    def _fetch_extractor(extractor: str, mode: str,
                         params: dict[str, str]) -> list[_RawSource]:
        """Hit one extractor (direct box.filmu.in or via vidbolt's proxy)."""
        try:
            media_type = params.get('type', 'movie')
            path_id = params.get('imdb') or f"tmdb{params['tmdb']}"
            query: dict[str, str] = {
                'tmdbId': params['tmdb'],
                'title': params['title'],
                'year': params.get('year', ''),
            }
            if media_type == 'tv':
                query['season'] = params.get('s', '1')
                query['episode'] = params.get('e', '1')
            scrape_path = f'/scrape/{extractor}/{media_type}/{path_id}'

            if mode == 'box':
                resp = _session.get(f'{_BOX_BASE}{scrape_path}', params=query,
                                    headers={'x-api-key': _BOX_KEY}, timeout=12)
            else:
                full_path = f'{scrape_path}?{urlencode(query)}'
                resp = _session.get(f'{_VIDBOLT_BASE}/api/scraper',
                                    params={'path': full_path}, timeout=12)
            if not resp.ok:
                return []
            data = resp.json()
            sources = data.get('sources') or []
            # A few extractors (e.g. VidNest) return a host-relative "/proxy/..."
            # URL - resolve it against whichever origin actually answered.
            origin = _BOX_BASE if mode == 'box' else _VIDBOLT_BASE
            for source_item in sources:
                if source_item.get('url', '').startswith('/'):
                    source_item['url'] = origin + source_item['url']
            # scraper.vidbolt.xyz's own relay (as opposed to wormhole.filmu.in's,
            # which has been reliable) has been seen hanging Kodi's player for
            # minutes on a dead upstream instead of failing fast - drop it here
            # rather than risk that at playback time.
            sources = [source_item for source_item in sources
                      if _UNRELIABLE_PROXY not in source_item.get('url', '')]
            return [(source_item, extractor, idx)
                    for idx, source_item in enumerate(sources) if source_item.get('url')]
        except Exception:
            fflog(f'filmu: extractor {extractor!r} failed, continuing')
            return []


_RE_NAME_JUNK: re.Pattern[str] = re.compile(r'\s*[\[|]|\s+-\s+')


def _clean_name(name: str | None, fallback: str) -> str:
    """Strip quality/language/server junk bundled into 'name' (e.g. "CastleTV [Tamil] | 720p" -> "CastleTV")."""
    name = (name or '').strip()
    match = _RE_NAME_JUNK.search(name)
    if match:
        name = name[:match.start()]
    return name.strip() or fallback


def _norm_quality(label: str) -> str:
    low = label.lower()
    if '2160' in low or '4k' in low:
        return '4K'
    if '1080' in low:
        return '1080p'
    if '720' in low:
        return '720p'
    if '480' in low or '576' in low or '360' in low:
        return 'SD'
    return 'HD'  # 'Auto' or unrecognised - assume decent, not SD


def _norm_lang(label: str) -> tuple[str, str]:
    low = label.strip().lower()
    if not low or low in _LANG_MAP:
        return _LANG_MAP.get(low, 'en'), ''
    if low in _NON_EN_REAL_LANGS:
        return 'multi', label.strip()
    return 'en', ''


def _decode(url: str, scheme: str = _SCHEME) -> dict[str, str]:
    if not url.startswith(scheme):
        return {}
    parsed = parse_qs(url[len(scheme):], keep_blank_values=True)
    return {key: values[0] for key, values in parsed.items()}


if __name__ == '__main__':
    try:
        from lib.ff.cmdline import DebugArgumentParser as ArgumentParser
    except ImportError:
        from argparse import ArgumentParser
    parser = ArgumentParser(description='Test Filmu source provider')
    parser.add_argument('tmdb_id', help='TMDB ID')
    parser.add_argument('--title', required=True)
    parser.add_argument('--year', default='')
    parser.add_argument('--imdb', default='')
    parser.add_argument('--type', default='movie', choices=['movie', 'tv'])
    parser.add_argument('--season', type=int, default=None)
    parser.add_argument('--episode', type=int, default=None)
    args = parser.parse_args()

    src = source()
    url = _SCHEME + urlencode({'type': args.type, 'tmdb': args.tmdb_id, 'imdb': args.imdb,
                               'title': args.title, 'year': args.year})
    if args.type == 'tv':
        url = f'{url}&s={args.season}&e={args.episode}'
    try:
        from pprint import pprint
        pprint(src.sources(url, [], []))
    except Exception as exc:
        print(f'Error: {exc}')
