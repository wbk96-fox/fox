# -*- coding: utf-8 -*-
"""
FanFilm – source: VidCore (vidcore.net)
Copyright (C) 2026 :)

Dystrybuowane na licencji MIT <https://mit-license.org>

Film:    GET vidcore.net/movie/{tmdb}  → extract 'en' token → enc-dec.app enc-vidcore
Serial:  GET vidcore.net/tv/{tmdb}/{s}/{e}  → ten sam flow

Flow:
  1. Pobierz stronę gracza, wyciągnij \\"en\\":\\"...\\" (token sesji)
  2. GET enc-dec.app/api/enc-vidcore?text={en}  → {servers, stream, token}
  3. POST servers z X-CSRF-Token  → zaszyfrowana lista serwerów
  4. POST dec-vidcore  → [{name, description, data}, ...]
  5. Dla każdego serwera: POST {stream}/{data}  → zaszyfrowane dane streamu
  6. POST dec-vidcore  → {url, tracks, noReferrer}
  7. Napisy: wstrzyknięcie EXT-X-MEDIA do manifestu HLS, serwowanego in-memory
"""

from __future__ import annotations

import re
import threading
from typing import ClassVar, Dict, List, Optional, TYPE_CHECKING
from urllib.parse import urlencode, parse_qs, urljoin

from lib.ff import requests
from lib.ff.source_utils import append_headers, probe_m3u8_quality, enc_dec_result
from lib.ff.resolve_utils import build_isa_url
from lib.ff.log_utils import fflog, fflog_exc
from lib.service.client import service_client

if TYPE_CHECKING:
    from lib.ff.item import FFItem
    from lib.sources import SourceItem, SourceTitleAlias


_BASE = 'https://vidcore.net'
_UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
       '(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36')
_PAGE_HEADERS = {'User-Agent': _UA}
_API_HEADERS = {
    'User-Agent': _UA,
    'Referer': f'{_BASE}/',
    'X-Requested-With': 'XMLHttpRequest',
}
_STREAM_HEADERS = {
    'User-Agent': _UA,
    'Referer': f'{_BASE}/',
}
_RE_EN = re.compile(r'\\"en\\":\\"([^"\\]+)\\"')
_RE_EN_RSC = re.compile(r'"en":"([^"]+)"')
_SCHEME = 'vidcore://'
_VC_FRAG = '#vc='

_LANG_MAP = {
    'english': 'en', 'french': 'fr', 'spanish': 'es', 'german': 'de',
    'italian': 'it', 'portuguese': 'pt', 'russian': 'ru', 'polish': 'pl',
    'dutch': 'nl', 'japanese': 'ja', 'korean': 'ko', 'chinese': 'zh',
    'arabic': 'ar', 'turkish': 'tr', 'swedish': 'sv', 'danish': 'da',
    'norwegian': 'no', 'finnish': 'fi', 'czech': 'cs', 'hungarian': 'hu',
    'romanian': 'ro', 'ukrainian': 'uk', 'thai': 'th', 'malay': 'ms',
    'indonesian': 'id', 'hindi': 'hi', 'croatian': 'hr', 'slovak': 'sk',
    'bulgarian': 'bg', 'serbian': 'sr', 'greek': 'el', 'hebrew': 'he',
    'latvian': 'lv', 'lithuanian': 'lt', 'slovenian': 'sl',
    'bosnian': 'bs', 'icelandic': 'is', 'mongolian': 'mn',
    'ukrainian': 'uk', 'persian': 'fa', 'vietnamese': 'vi',
    'bengali': 'bn', 'sinhala': 'si', 'tatar': 'tt',
}


class source:
    priority: ClassVar[int] = 1
    language: ClassVar[List[str]] = ['en']

    ffitem: 'FFItem'

    def __init__(self) -> None:
        self.session = requests.Session()
        self._data_map: Dict[str, dict] = {}

    def movie(self, imdb: str, title: str, localtitle: str,
              aliases: 'list[SourceTitleAlias]', year: str) -> Optional[str]:
        tmdb = str(self.ffitem.tmdb_id or '')
        if not tmdb:
            return None
        return _SCHEME + urlencode({'type': 'movie', 'tmdb': tmdb})

    def tvshow(self, imdb: str, tvdb: str, tvshowtitle: str, localtvshowtitle: str,
               aliases: 'list[SourceTitleAlias]', year: str) -> Optional[str]:
        show_item = getattr(self.ffitem, 'show_item', None)
        show_tmdb = str((show_item.tmdb_id if show_item else None) or self.ffitem.tmdb_id or '')
        if not show_tmdb:
            return None
        return _SCHEME + urlencode({'type': 'tv', 'tmdb': show_tmdb})

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
            page_url = self._page_url(params)
            if not page_url:
                return []
            en_token = self._extract_en(page_url)
            if not en_token:
                fflog(f'en token not found for {page_url}')
                return []
            enc_info = self._enc_vidcore(en_token)
            if not enc_info:
                return []
            servers = self._fetch_servers(enc_info)
            if not servers:
                return []
            results: List[SourceItem] = []
            lock = threading.Lock()
            token = enc_info['token']
            threads = [
                threading.Thread(
                    target=self._fetch_stream,
                    args=(srv, enc_info['stream'], token, results, lock),
                    daemon=True,
                )
                for srv in servers
            ]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join(timeout=15)
            fflog(f'found {len(results)} VidCore streams')
            return results
        except Exception:
            fflog_exc()
            return []

    def resolve(self, url: str) -> Optional[str]:
        try:
            frag_idx = url.find(_VC_FRAG)
            if frag_idx == -1:
                return url
            pipe_idx = url.find('|', frag_idx)
            key = url[frag_idx + len(_VC_FRAG): pipe_idx if pipe_idx != -1 else None]
            m3u8_url = url[len('isa+'): frag_idx]
            headers_tail = url[pipe_idx:] if pipe_idx != -1 else ''

            data = self._data_map.get(key)
            tracks = data.get('tracks', []) if data else []

            if tracks:
                resp = self.session.get(m3u8_url, headers=_STREAM_HEADERS, timeout=10)
                if resp.ok:
                    manifest_key = f'/nm_vc_{key}.m3u8'
                    service_client.set_media_files(
                        {manifest_key: _inject_subtitles(resp.text, tracks)}
                    )
                    proxy_url = urljoin(service_client.url, '/media') + manifest_key
                    fflog(f'{len(tracks)} subtitle track(s) injected into VidCore manifest')
                    return build_isa_url(proxy_url, f'{_BASE}/', ua=_UA)

            return f'isa+{m3u8_url}{headers_tail}'
        except Exception:
            fflog_exc()
        return url

    def _page_url(self, params: dict) -> Optional[str]:
        tmdb = params.get('tmdb', '')
        if not tmdb:
            return None
        if params.get('type') == 'movie':
            return f'{_BASE}/movie/{tmdb}'
        season = params.get('s', '')
        episode = params.get('e', '')
        if not season or not episode:
            return None
        return f'{_BASE}/tv/{tmdb}/{season}/{episode}'

    def _extract_en(self, page_url: str) -> Optional[str]:
        page = self.session.get(page_url, headers=_PAGE_HEADERS, timeout=15).text
        match = _RE_EN.search(page)
        if match:
            return match.group(1)
        rsc = self.session.get(
            page_url,
            headers={**_PAGE_HEADERS, 'RSC': '1', 'Accept': 'text/x-component'},
            timeout=15,
        ).text
        match = _RE_EN_RSC.search(rsc)
        return match.group(1) if match else None

    def _enc_vidcore(self, en_token: str) -> Optional[dict]:
        return enc_dec_result('enc-vidcore', params={'text': en_token}, timeout=12)

    def _fetch_servers(self, enc_info: dict) -> List[dict]:
        headers = {**_API_HEADERS, 'X-CSRF-Token': enc_info['token']}
        servers_enc = self.session.post(enc_info['servers'], headers=headers, timeout=12).text
        return enc_dec_result('dec-vidcore', json_body={'text': servers_enc}) or []

    def _fetch_stream(self, server: dict, stream_base: str, token: str,
                      out: List[SourceItem], lock: threading.Lock) -> None:
        try:
            headers = {**_API_HEADERS, 'X-CSRF-Token': token}
            stream_url = f"{stream_base}/{server['data']}"
            stream_enc = self.session.post(stream_url, headers=headers, timeout=12).text
            stream_data = enc_dec_result('dec-vidcore', json_body={'text': stream_enc})
            if not isinstance(stream_data, dict):
                return
            m3u8_url = stream_data.get('url', '')
            if not m3u8_url:
                return
            tracks: List[dict] = stream_data.get('tracks') or []
            server_name = server.get('name', 'Unknown')
            quality = _server_quality(server_name, m3u8_url)
            key = f'{server_name.lower().replace(" ", "_")}_{hash(m3u8_url) & 0xFFFFFF:06x}'
            with lock:
                self._data_map[key] = {'tracks': tracks}
            item_url = f'isa+{m3u8_url}{_VC_FRAG}{key}{append_headers(_STREAM_HEADERS)}'
            has_polish = any('Polish' in t.get('label', '') or 'pl' == _lang_code(t.get('label', ''))
                             for t in tracks)
            item: SourceItem = {
                'source': f'VidCore {server_name}',
                'quality': quality,
                'language': 'en',
                'url': item_url,
                'direct': True,
                'debridonly': False,
            }
            if has_polish:
                item['info2'] = 'Napisy'
            with lock:
                out.append(item)
        except Exception:
            fflog_exc()


def _server_quality(name: str, url: str) -> str:
    name_lower = name.lower()
    if '4k' in name_lower:
        return '4K'
    probed = probe_m3u8_quality(url, _STREAM_HEADERS)
    if probed:
        return probed
    return 'HD'


def _lang_code(label: str) -> str:
    if not label:
        return 'und'
    first_word = label.split()[0].lower()
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
    return {k: val[0] for k, val in parsed.items()}


if __name__ == '__main__':
    try:
        from lib.ff.cmdline import DebugArgumentParser as ArgumentParser
    except ImportError:
        from argparse import ArgumentParser
    parser = ArgumentParser(description='Test VidCore source provider')
    parser.add_argument('--tmdb', required=True, help='TMDB ID')
    parser.add_argument('--type', default='movie', choices=['movie', 'tv'])
    parser.add_argument('--season', type=int, default=None)
    parser.add_argument('--episode', type=int, default=None)
    args = parser.parse_args()
    src = source()
    url = _SCHEME + urlencode({'type': args.type, 'tmdb': args.tmdb})
    if args.type == 'tv':
        url = f'{url}&s={args.season}&e={args.episode}'
    from pprint import pprint
    pprint(src.sources(url, [], []))
