# -*- coding: utf-8 -*-
"""
FanFilm – source: MegaCloudX (megacloudx.net)
Copyright (C) 2026 :)

Dystrybuowane na licencji MIT <https://mit-license.org>

Film:    GET megacloudx.net/mv/{imdb}/{tmdb}/  → 302 → /e/{id}  → var HLS / var TRACKS
Serial:  GET megacloudx.net/pl/{show_tmdb}/{season}/{episode}/  → 302 → /e/{id}

Napisy: manifest HLS wzbogacony o EXT-X-MEDIA z ISO językami, serwowany in-memory
przez lokalny http_server (klucz /nm_mcx_{file_code}.m3u8).
ISA pobiera konkretny VTT dopiero po wyborze języka przez usera.
"""

from __future__ import annotations

import json
import re
from typing import ClassVar, Dict, List, Optional, TYPE_CHECKING
from urllib.parse import urlencode, parse_qs, urljoin

from lib.ff import requests
from lib.ff.source_utils import append_headers, probe_m3u8_quality
from lib.ff.resolve_utils import build_isa_url
from lib.ff.log_utils import fflog, fflog_exc
from lib.service.client import service_client

if TYPE_CHECKING:
    from lib.ff.item import FFItem
    from lib.sources import SourceItem, SourceTitleAlias


_BASE = 'https://megacloudx.net'
_UA = ('Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 '
       '(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36')
_HEADERS = {
    'User-Agent': _UA,
    'Referer': 'https://sflix.ws/',
}
_STREAM_HEADERS = {
    'User-Agent': _UA,
    'Referer': f'{_BASE}/',
    'Origin': _BASE,
}
_SCHEME = 'megacloudx://'
_RE_HLS = re.compile(r'var\s+HLS\s*=\s*"([^"]+)"')
_RE_TRACKS = re.compile(r'var\s+TRACKS\s*=\s*(\[[^\]]*\])')
_RE_FILE_CODE = re.compile(r'var\s+FILE_CODE\s*=\s*"([^"]+)"')
_MCX_FRAG = '#mcx='

_LANG_MAP = {
    'english': 'en', 'french': 'fr', 'spanish': 'es', 'german': 'de',
    'italian': 'it', 'portuguese': 'pt', 'russian': 'ru', 'polish': 'pl',
    'dutch': 'nl', 'japanese': 'ja', 'korean': 'ko', 'chinese': 'zh',
    'arabic': 'ar', 'turkish': 'tr', 'swedish': 'sv', 'danish': 'da',
    'norwegian': 'no', 'finnish': 'fi', 'czech': 'cs', 'hungarian': 'hu',
    'romanian': 'ro', 'ukrainian': 'uk', 'thai': 'th', 'malay': 'ms',
    'indonesian': 'id', 'hindi': 'hi',
}


class source:
    priority: ClassVar[int] = 1
    language: ClassVar[List[str]] = ['en']

    ffitem: 'FFItem'

    def __init__(self) -> None:
        self.session = requests.Session()
        self.session.headers.update(_HEADERS)
        self._track_map: Dict[str, List[dict]] = {}

    # ── public api ─────────────────────────────────────────────────────────

    def movie(self, imdb: str, title: str, localtitle: str,
              aliases: 'list[SourceTitleAlias]', year: str) -> Optional[str]:
        tmdb = str(self.ffitem.tmdb_id or '')
        if not imdb or not tmdb:
            return None
        return _SCHEME + urlencode({'type': 'movie', 'imdb': imdb, 'tmdb': tmdb})

    def tvshow(self, imdb: str, tvdb: str, tvshowtitle: str, localtvshowtitle: str,
               aliases: 'list[SourceTitleAlias]', year: str) -> Optional[str]:
        show_item = getattr(self.ffitem, 'show_item', None)
        show_tmdb = str((show_item.tmdb_id if show_item else None) or self.ffitem.tmdb_id or '')
        if not show_tmdb:
            return None
        return _SCHEME + urlencode({'type': 'tv', 'show_tmdb': show_tmdb})

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
        if not params:
            return []
        try:
            embed_url = self._embed_url(params)
            if not embed_url:
                return []
            hls_url, file_code, tracks = self._fetch_embed(embed_url)
            if not hls_url:
                return []
            quality = probe_m3u8_quality(hls_url, _STREAM_HEADERS) or 'HD'
            if file_code and tracks:
                self._track_map[file_code] = tracks
            stream = f'isa+{hls_url}{_MCX_FRAG}{file_code or ""}{append_headers(_STREAM_HEADERS)}'
            item: 'SourceItem' = {
                'source': 'MegaCloudX',
                'quality': quality,
                'language': 'en',
                'url': stream,
                'direct': True,
                'debridonly': False,
            }
            if any('polish' in t.get('label', '').lower() for t in tracks):
                item['info2'] = 'Napisy'
            return [item]
        except Exception:
            fflog_exc()
            return []

    def resolve(self, url: str) -> Optional[str]:
        try:
            frag_idx = url.find(_MCX_FRAG)
            if frag_idx == -1:
                return url
            pipe_idx = url.find('|', frag_idx)
            file_code = url[frag_idx + len(_MCX_FRAG): pipe_idx if pipe_idx != -1 else None]
            hls_url = url[len('isa+'): frag_idx]
            headers_tail = url[pipe_idx:] if pipe_idx != -1 else ''

            tracks = self._track_map.get(file_code, [])
            if tracks:
                resp = self.session.get(hls_url, timeout=10, headers=_STREAM_HEADERS)
                if resp.ok:
                    key = f'/nm_mcx_{file_code}.m3u8'
                    service_client.set_media_files({key: _inject_subtitles(resp.text, tracks)})
                    proxy_url = urljoin(service_client.url, '/media') + key
                    fflog(f'{len(tracks)} subtitle track(s) injected into manifest')
                    return build_isa_url(proxy_url, f'{_BASE}/', ua=_UA)

            return hls_url + headers_tail
        except Exception:
            fflog_exc()
        return url

    # ── helpers ────────────────────────────────────────────────────────────

    def _embed_url(self, params: dict) -> Optional[str]:
        if params.get('type') == 'movie':
            imdb = params.get('imdb', '')
            tmdb = params.get('tmdb', '')
            if not imdb or not tmdb:
                return None
            return f'{_BASE}/mv/{imdb}/{tmdb}/'
        show_tmdb = params.get('show_tmdb', '')
        season = params.get('s', '')
        episode = params.get('e', '')
        if not show_tmdb or not season or not episode:
            return None
        return f'{_BASE}/pl/{show_tmdb}/{season}/{episode}/'

    def _fetch_embed(self, embed_url: str):
        resp = self.session.get(embed_url, allow_redirects=True, timeout=12)
        if not resp.ok:
            fflog(f'embed fetch failed: {resp.status_code} for {embed_url}')
            return None, None, []
        page = resp.text

        hls_match = _RE_HLS.search(page)
        if not hls_match:
            fflog(f'HLS var not found: {embed_url}')
            return None, None, []

        file_code = None
        fc_match = _RE_FILE_CODE.search(page)
        if fc_match:
            file_code = fc_match.group(1)

        tracks: List[dict] = []
        tracks_match = _RE_TRACKS.search(page)
        if tracks_match:
            try:
                tracks = [{'file': t['file'], 'label': t.get('label', '')}
                          for t in json.loads(tracks_match.group(1)) if t.get('file')]
            except (ValueError, KeyError):
                pass

        return hls_match.group(1), file_code, tracks


def _lang_code(label: str) -> str:
    first_word = label.split(' - ')[0].split()[0].lower() if label else ''
    return _LANG_MAP.get(first_word, 'und')


def _inject_subtitles(manifest: str, tracks: List[dict]) -> str:
    media_lines = []
    for track in tracks:
        lang = _lang_code(track.get('label', ''))
        default = 'YES' if lang == 'pl' else 'NO'
        media_lines.append(
            f'#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",'
            f'LANGUAGE="{lang}",NAME="{lang}",'
            f'DEFAULT={default},AUTOSELECT={default},FORCED=NO,'
            f'URI="{track.get("file", "")}"'
        )
    lines = manifest.splitlines()
    result = [lines[0]]
    result.extend(media_lines)
    for line in lines[1:]:
        if line.startswith('#EXT-X-STREAM-INF') and 'SUBTITLES=' not in line:
            line = line + ',SUBTITLES="subs"'
        result.append(line)
    return '\n'.join(result)


def _decode(url: str) -> dict:
    if not url.startswith(_SCHEME):
        return {}
    parsed = parse_qs(url[len(_SCHEME):], keep_blank_values=True)
    return {key: val[0] for key, val in parsed.items()}


if __name__ == '__main__':
    try:
        from lib.ff.cmdline import DebugArgumentParser as ArgumentParser
    except ImportError:
        from argparse import ArgumentParser
    parser = ArgumentParser(description='Test MegaCloudX source provider')
    parser.add_argument('--imdb', default='')
    parser.add_argument('--tmdb', default='')
    parser.add_argument('--show-tmdb', dest='show_tmdb', default='')
    parser.add_argument('--type', default='movie', choices=['movie', 'tv'])
    parser.add_argument('--season', type=int, default=None)
    parser.add_argument('--episode', type=int, default=None)
    args = parser.parse_args()
    src = source()
    if args.type == 'movie':
        url = _SCHEME + urlencode({'type': 'movie', 'imdb': args.imdb, 'tmdb': args.tmdb})
    else:
        url = _SCHEME + urlencode({'type': 'tv', 'show_tmdb': args.show_tmdb})
        url = f'{url}&s={args.season}&e={args.episode}'
    from pprint import pprint
    pprint(src.sources(url, [], []))
