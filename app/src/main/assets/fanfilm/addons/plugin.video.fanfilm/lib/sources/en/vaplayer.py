# -*- coding: utf-8 -*-
"""
FanFilm - źródło: VaPlayer (streamdata.vaplayer.ru)
Copyright (C) 2026 :)

Dystrybuowane na licencji MIT <https://mit-license.org>

API: GET https://streamdata.vaplayer.ru/api.php
  ?tmdb={id}&type={movie|tv}[&season={s}&episode={e}]
Zwraca tablicę HLS master.m3u8. Jakość przez WIDTH: ≥1920→1080p, ≥1280→720p.
Nie wymaga auth ani sesji.
"""

from __future__ import annotations

from typing import ClassVar, Dict, List, Optional, Tuple, TYPE_CHECKING
from urllib.parse import urlencode, parse_qs

from lib.ff import requests
from lib.ff.source_utils import FF_UA, append_headers, parse_hls_variants
from lib.ff.log_utils import fflog, fflog_exc

if TYPE_CHECKING:
    from lib.ff.item import FFItem
    from lib.sources import SourceItem, SourceTitleAlias


_API_URL = 'https://streamdata.vaplayer.ru/api.php'
_EMBED_ORIGIN = 'https://nextgencloudfabric.com'

_HEADERS: Dict[str, str] = {
    'User-Agent': FF_UA,
    'Referer': _EMBED_ORIGIN + '/',
    'Accept': 'application/json, text/javascript, */*; q=0.01',
    'X-Requested-With': 'XMLHttpRequest',
}
_STREAM_HEADERS: Dict[str, str] = {
    'User-Agent': FF_UA,
    'Referer': _EMBED_ORIGIN + '/',
}

_SCHEME = 'vaplayer://'

_WIDTH_TO_QUALITY: List[Tuple[int, str]] = [
    (3840, '4K'),
    (2560, '1440p'),
    (1920, '1080p'),
    (1280, '720p'),
]


class source:
    priority: ClassVar[int] = 1
    language: ClassVar[List[str]] = ['en']

    ffitem: 'FFItem'

    def __init__(self) -> None:
        self.session = requests.Session()
        self.session.headers.update(_HEADERS)

    # ── public api ──────────────────────────────────────────────────────────

    def movie(self, imdb: str, title: str, localtitle: str,
              aliases: 'list[SourceTitleAlias]', year: str) -> Optional[str]:
        tmdb = str(self.ffitem.tmdb_id or '')
        if not tmdb:
            return None
        return _SCHEME + urlencode({'type': 'movie', 'tmdb': tmdb})

    def tvshow(self, imdb: str, tvdb: str, tvshowtitle: str, localtvshowtitle: str,
               aliases: 'list[SourceTitleAlias]', year: str) -> Optional[str]:
        show_item = getattr(self.ffitem, 'show_item', None)
        tmdb = str((show_item.tmdb_id if show_item else None) or self.ffitem.tmdb_id or '')
        if not tmdb:
            return None
        return _SCHEME + urlencode({'type': 'tv', 'tmdb': tmdb})

    def episode(self, url: Optional[str], imdb: str, tvdb: str, title: str,
                premiered: str, season: str, episode: str) -> Optional[str]:
        if not url:
            return None
        return f'{url}&s={int(season)}&e={int(episode)}'

    def sources(self, url: Optional[str], hostDict: List[str],
                hostprDict: List[str]) -> 'List[SourceItem]':
        if not url:
            return []
        params = _decode(url)
        if not params.get('tmdb'):
            return []

        media_type = params.get('type', 'movie')
        tmdb = params['tmdb']

        api_params: Dict[str, str] = {'tmdb': tmdb, 'type': media_type}
        if media_type == 'tv':
            api_params['season'] = params.get('s', '1')
            api_params['episode'] = params.get('e', '1')

        results = self._fetch_streams(api_params)
        fflog(f'found {len(results)} streams')
        return results

    def resolve(self, url: str) -> Optional[str]:
        return url

    # ── helpers ─────────────────────────────────────────────────────────────

    def _fetch_streams(self, api_params: Dict[str, str]) -> List[dict]:
        try:
            resp = self.session.get(_API_URL, params=api_params, timeout=10)
            if not resp.ok:
                return []
            data = resp.json()
            if str(data.get('status_code', '')) != '200':
                fflog(f'API status: {data.get("status_code")}')
                return []
            stream_urls = data.get('data', {}).get('stream_urls', [])
            for master_url in stream_urls:
                if 'master.m3u8' not in master_url:
                    continue
                items = self._parse_master(master_url)
                if items:
                    return items
            return []
        except Exception:
            fflog_exc()
            return []

    def _parse_master(self, master_url: str) -> List[dict]:
        try:
            resp = self.session.get(master_url, timeout=10)
            if not resp.ok:
                return []
            items = []
            for width, _height, full_url in parse_hls_variants(resp.text, master_url):
                quality = _width_to_quality(width)
                if not quality:
                    continue
                items.append(_make_item(quality,
                                        f'isa+{full_url}{append_headers(_STREAM_HEADERS)}'))
            return items
        except Exception:
            fflog_exc()
            return []


def _width_to_quality(width: int) -> Optional[str]:
    for min_width, label in _WIDTH_TO_QUALITY:
        if width >= min_width:
            return label
    return None


def _make_item(quality: str, stream_url: str) -> dict:
    return {
        'source': 'VaPlayer',
        'quality': quality,
        'language': 'en',
        'url': stream_url,
        'direct': True,
        'debridonly': False,
    }


def _decode(url: str) -> Dict[str, str]:
    if not url.startswith(_SCHEME):
        return {}
    parsed = parse_qs(url[len(_SCHEME):], keep_blank_values=True)
    return {k: v[0] for k, v in parsed.items()}


if __name__ == '__main__':
    try:
        from lib.ff.cmdline import DebugArgumentParser as ArgumentParser
    except ImportError:
        from argparse import ArgumentParser
    parser = ArgumentParser(description='Test VaPlayer source provider')
    parser.add_argument('tmdb_id', help='TMDB ID')
    parser.add_argument('--type', default='movie', choices=['movie', 'tv'])
    parser.add_argument('--season', type=int, default=1)
    parser.add_argument('--episode', type=int, default=1)
    args = parser.parse_args()

    src = source()
    url = _SCHEME + urlencode({'type': args.type, 'tmdb': args.tmdb_id})
    if args.type == 'tv':
        url = f'{url}&s={args.season}&e={args.episode}'

    from pprint import pprint
    pprint(src.sources(url, [], []))
