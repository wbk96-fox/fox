# -*- coding: utf-8 -*-
"""
FanFilm - źródło: VixSrc (vixsrc.to)
Copyright (C) 2026 :)

Dystrybuowane na licencji MIT <https://mit-license.org>

Własny CDN z HLS 1080p. Flow:
  GET /api/movie/{tmdb} lub /api/tv/{tmdb}/{s}/{e}
  → embed HTML → window.masterPlaylist.params{token,expires}
  → GET /playlist/{id}?ub=1&token=...&expires=...&h=1  (master m3u8)
  → jeśli multi-audio: ISA config z media_audio_langcode_default=en
"""

from __future__ import annotations

import json
import re
from typing import ClassVar, Dict, List, Optional, TYPE_CHECKING
from urllib.parse import urlencode, parse_qs

from lib.ff import requests
from lib.ff.source_utils import append_headers
from lib.ff.log_utils import fflog, fflog_exc

if TYPE_CHECKING:
    from lib.ff.item import FFItem
    from lib.sources import SourceItem, SourceTitleAlias


_BASE = 'https://vixsrc.to'
_UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
       '(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36')
_HEADERS: Dict[str, str] = {
    'User-Agent': _UA,
    'Referer': f'{_BASE}/',
    'Origin': _BASE,
}
_STREAM_HEADERS: Dict[str, str] = {
    'Referer': f'{_BASE}/',
    'User-Agent': _UA,
}
_SCHEME = 'vixsrc://'


class source:
    priority: ClassVar[int] = 1
    language: ClassVar[List[str]] = ['en']

    ffitem: 'FFItem'

    def __init__(self) -> None:
        self.session = requests.Session()
        self.session.headers.update(_HEADERS)

    # ── public api ────────────────────────────────────────────────────────────

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
        try:
            return self._fetch(params)
        except Exception:
            fflog_exc()
            return []

    def resolve(self, url: str) -> Optional[str]:
        return url

    # ── helpers ───────────────────────────────────────────────────────────────

    def _fetch(self, params: Dict[str, str]) -> 'List[SourceItem]':
        tmdb = params['tmdb']
        media_type = params.get('type', 'movie')
        season = params.get('s', '')
        episode = params.get('e', '')

        if media_type == 'tv':
            api_path = f'/api/tv/{tmdb}/{season}/{episode}'
        else:
            api_path = f'/api/movie/{tmdb}'

        api_resp = self.session.get(f'{_BASE}{api_path}', timeout=10)
        if not api_resp.ok:
            fflog(f'api {api_path} → {api_resp.status_code}')
            return []

        src_path = api_resp.json().get('src', '')
        if not src_path:
            fflog('no src in api response')
            return []

        embed_resp = self.session.get(f'{_BASE}{src_path}', timeout=10)
        if not embed_resp.ok:
            fflog(f'embed → {embed_resp.status_code}')
            return []

        mp_block = re.search(r'window\.masterPlaylist\s*=\s*(\{.+?\})\s*\n', embed_resp.text, re.DOTALL)
        if not mp_block:
            fflog('masterPlaylist block not found in embed')
            return []
        mp_text = mp_block.group(1)

        token_match = re.search(r"['\"]token['\"]\s*:\s*['\"]([^'\"]+)['\"]", mp_text)
        expires_match = re.search(r"['\"]expires['\"]\s*:\s*['\"]([^'\"]+)['\"]", mp_text)
        url_match = re.search(r"\burl\s*:\s*['\"]([^'\"]+)['\"]", mp_text)

        token = token_match.group(1) if token_match else None
        expires = expires_match.group(1) if expires_match else None
        playlist_url_raw = url_match.group(1) if url_match else None

        if not token or not expires or not playlist_url_raw:
            fflog('could not extract masterPlaylist from embed')
            return []

        playlist_base = playlist_url_raw if playlist_url_raw.startswith('http') else f'{_BASE}{playlist_url_raw}'
        sep = '&' if '?' in playlist_base else '?'
        playlist_url = f'{playlist_base}{sep}ub=1&token={token}&expires={expires}&h=1'

        m3u8_resp = self.session.get(playlist_url, timeout=10)
        if not m3u8_resp.ok:
            fflog(f'playlist → {m3u8_resp.status_code}')
            return []

        audio_langs = set(re.findall(r'#EXT-X-MEDIA:TYPE=AUDIO[^\n]*LANGUAGE="([^"]+)"', m3u8_resp.text))
        en_lang = next((lang for lang in audio_langs if lang.startswith('en')), None)
        language = 'en' if (not audio_langs or en_lang) else ''
        is_multi = en_lang is not None and len(audio_langs) > 1
        info = 'Multi' if is_multi else ''

        stream_headers = append_headers(_STREAM_HEADERS)
        if is_multi:
            isa_config = json.dumps({'media_audio_langcode_default': en_lang})
            final_url = f'isa+{playlist_url}{stream_headers}|{isa_config}'
        else:
            final_url = f'isa+{playlist_url}{stream_headers}'

        fflog(f'resolved → audio={audio_langs} multi={is_multi}')
        return [{
            'source': 'VIXSRC',
            'quality': '1080p',
            'language': language,
            'info': info or '',
            'url': final_url,
            'direct': True,
            'debridonly': False,
        }]


def _decode(url: str) -> Dict[str, str]:
    if not url.startswith(_SCHEME):
        return {}
    parsed = parse_qs(url[len(_SCHEME):], keep_blank_values=True)
    return {key: val[0] for key, val in parsed.items()}


if __name__ == '__main__':
    try:
        from lib.ff.cmdline import DebugArgumentParser as ArgumentParser
    except ImportError:
        from argparse import ArgumentParser
    parser = ArgumentParser(description='Test VixSrc source provider')
    parser.add_argument('tmdb_id', help='TMDB ID')
    parser.add_argument('--type', default='movie', choices=['movie', 'tv'])
    parser.add_argument('--season', type=int, default=None)
    parser.add_argument('--episode', type=int, default=None)
    args = parser.parse_args()
    src = source()
    url = _SCHEME + urlencode({'type': args.type, 'tmdb': args.tmdb_id})
    if args.type == 'tv' and args.season and args.episode:
        url = f'{url}&s={args.season}&e={args.episode}'
    from pprint import pprint
    pprint(src.sources(url, [], []))
