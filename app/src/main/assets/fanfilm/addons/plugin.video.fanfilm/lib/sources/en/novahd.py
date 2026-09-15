# -*- coding: utf-8 -*-
"""
FanFilm - source: NovaHD (novahd.cc)
Copyright (C) 2026 :)

MIT licensed <https://mit-license.org>

/api/sources?type={movie|show}&tmdbId={tmdb}[&season=&episode=] streams
provider links (Falcon, Viper, Wolf, Onyx, Puma, Lynx, ...) as ndjson,
one JSON object per line, terminated by {"done":true}. Only needs an
x-nova-visitor header (any random UUID, client-generated, no handshake).

Segment URLs are short-lived tokens, so sources() only encodes a
provider "recipe" (tmdb/type/season/episode/provider), not the raw CDN
link. resolve() re-fetches right before playback to keep the token as
fresh as possible.
"""

from __future__ import annotations

import json
import uuid
from typing import ClassVar, Dict, List, Optional, TYPE_CHECKING
from urllib.parse import urlencode, parse_qs

from lib.ff import requests
from lib.ff.source_utils import FF_UA, append_headers
from lib.ff.log_utils import fflog, fflog_exc

if TYPE_CHECKING:
    from lib.ff.item import FFItem
    from lib.sources import SourceItem, SourceTitleAlias


_BASE = 'https://novahd.cc'
_SOURCES_URL = _BASE + '/api/sources'

_HEADERS: Dict[str, str] = {
    'User-Agent': FF_UA,
    'Referer': _BASE + '/',
}

_SCHEME = 'novahd://'
_PLAY_SCHEME = 'novahdplay://'

_LANG_MAP = {
    'english': 'en', 'french': 'fr', 'spanish': 'es', 'latino': 'es',
    'german': 'de', 'deutsch': 'de', 'italian': 'it', 'portuguese': 'pt',
    'português': 'pt', 'russian': 'ru', 'polish': 'pl', 'dutch': 'nl',
    'japanese': 'ja', 'korean': 'ko', 'chinese': 'zh', 'arabic': 'ar',
    'turkish': 'tr',
}


class source:
    priority: ClassVar[int] = 1
    language: ClassVar[List[str]] = ['en']

    ffitem: 'FFItem'

    def __init__(self) -> None:
        self.session = requests.Session()
        self.session.headers.update(_HEADERS)
        self.session.headers['x-nova-visitor'] = str(uuid.uuid4())

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

        query = _build_query(params)
        entries = self._fetch_entries(query)
        items = [item for entry in entries if (item := _make_item(query, entry))]
        fflog(f'found {len(items)} streams')
        return items

    def resolve(self, url: str) -> Optional[str]:
        params = _decode_play(url)
        if not params.get('tmdb'):
            return None

        entries = self._fetch_entries(_build_query(params))
        provider = params.get('provider', '')
        entry = next((e for e in entries if e.get('provider') == provider), None)
        if entry is None and entries:
            entry = entries[0]
        return _stream_url(entry) if entry else None

    # ── helpers ─────────────────────────────────────────────────────────────

    def _fetch_entries(self, query: Dict[str, str]) -> List[dict]:
        entries: List[dict] = []
        seen_urls: set = set()
        try:
            resp = self.session.get(
                f'{_SOURCES_URL}?{urlencode(query)}',
                headers={'Accept': 'application/x-ndjson'},
                timeout=25,
                stream=True,
            )
            if not resp.ok:
                fflog(f'novahd: sources HTTP {resp.status_code}')
                return []

            for line in resp.iter_lines(decode_unicode=True):
                if not line or not line.strip():
                    continue
                try:
                    chunk = json.loads(line)
                except ValueError:
                    continue
                for entry in chunk.get('sources') or []:
                    if entry.get('url') and entry['url'] not in seen_urls:
                        seen_urls.add(entry['url'])
                        entries.append(entry)
                if chunk.get('done'):
                    break
        except Exception:
            fflog_exc()
        return entries


def _build_query(params: Dict[str, str]) -> Dict[str, str]:
    media_type = 'show' if params.get('type') in ('tv', 'show') else 'movie'
    query = {'type': media_type, 'tmdbId': params['tmdb']}
    if media_type == 'show':
        query['season'] = params.get('s') or params.get('season', '1')
        query['episode'] = params.get('e') or params.get('episode', '1')
    return query


def _make_item(query: Dict[str, str], entry: dict) -> Optional[dict]:
    provider = entry.get('provider') or 'NovaHD'
    language_label = entry.get('language') or ''

    play_params = {'tmdb': query['tmdbId'], 'type': query['type'], 'provider': provider}
    if query['type'] == 'show':
        play_params['season'] = query['season']
        play_params['episode'] = query['episode']

    item = {
        'source': f'NovaHD {provider}',
        'quality': _quality_of(entry.get('quality', '')),
        'language': _lang_code(language_label),
        'url': _PLAY_SCHEME + urlencode(play_params),
        'direct': True,
        'debridonly': False,
    }
    if 'sub' in language_label.lower():
        item['info2'] = 'Napisy'
    return item


def _stream_url(entry: dict) -> Optional[str]:
    raw_url = entry.get('url')
    if not raw_url:
        return None
    stream_url = f'isa+{raw_url}' if entry.get('type') == 'hls' else raw_url
    return stream_url + append_headers(_HEADERS)


def _quality_of(raw: str) -> str:
    q = (raw or '').strip().lower()
    if q in ('4k', '8k'):
        return '4K'
    if q in ('1080', '1080p', '1080i', '1440p', '2k', 'fhd'):
        return '1080p'
    if q in ('720', '720p', 'hd'):
        return '720p'
    if q in ('480', '480p', 'sd'):
        return 'SD'
    return 'HD'


def _lang_code(label: str) -> str:
    if not label:
        return 'en'
    first_word = label.split()[0].lower()
    return _LANG_MAP.get(first_word, 'en')


def _decode_scheme(url: str, scheme: str) -> Dict[str, str]:
    if not url.startswith(scheme):
        return {}
    parsed = parse_qs(url[len(scheme):], keep_blank_values=True)
    return {k: v[0] for k, v in parsed.items()}


def _decode(url: str) -> Dict[str, str]:
    return _decode_scheme(url, _SCHEME)


def _decode_play(url: str) -> Dict[str, str]:
    return _decode_scheme(url, _PLAY_SCHEME)


if __name__ == '__main__':
    try:
        from lib.ff.cmdline import DebugArgumentParser as ArgumentParser
    except ImportError:
        from argparse import ArgumentParser
    parser = ArgumentParser(description='Test NovaHD source provider')
    parser.add_argument('tmdb_id', help='TMDB ID')
    parser.add_argument('--type', default='movie', choices=['movie', 'tv'])
    parser.add_argument('--season', type=int, default=1)
    parser.add_argument('--episode', type=int, default=1)
    args = parser.parse_args()

    src = source()
    if args.type == 'movie':
        url = _SCHEME + urlencode({'type': 'movie', 'tmdb': args.tmdb_id})
    else:
        url = _SCHEME + urlencode({'type': 'tv', 'tmdb': args.tmdb_id})
        url = f'{url}&s={args.season}&e={args.episode}'

    from pprint import pprint
    pprint(src.sources(url, [], []))
