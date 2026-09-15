# -*- coding: UTF-8 -*-
"""
FanFilm - źródło: seriale.vip
Copyright (C) 2026 :)

Dystrybuowane na licencji MIT <https://mit-license.org>

Strona oparta na DLE (DataLife Engine).
Playlisty seriali/filmów serwowane przez AJAX:
  POST /engine/ajax/controller.php?mod=playlists
  body: news_id=<id>&xfield=pl
"""

from __future__ import annotations

import binascii
import json
import re
from typing import ClassVar, List, Optional, TYPE_CHECKING
from urllib.parse import quote_plus, urljoin, urlencode

from lib.ff import cleantitle, control, requests
from lib.ff.item import FFItem
from lib.ff.log_utils import fflog, fflog_exc
from lib.ff.source_utils import search_queries_extended, year_matches, append_headers

if TYPE_CHECKING:
    from lib.sources import SourceItem, SourceTitleAlias

_BASE = 'https://seriale.vip'
_PLAYLIST_URL = _BASE + '/engine/ajax/controller.php?mod=playlists'
_SEARCH_URL = _BASE + '/index.php'

_UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
       '(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36')

_HEADERS = {
    'User-Agent': _UA,
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    'Accept-Language': 'pl,en-US;q=0.7,en;q=0.3',
    'Referer': _BASE + '/',
}
_AJAX_HEADERS = {
    **_HEADERS,
    'X-Requested-With': 'XMLHttpRequest',
    'Content-Type': 'application/x-www-form-urlencoded',
}

_VIDARA_BASE = 'https://vidara.to'


class source:
    ffitem: FFItem

    priority: ClassVar[int] = 1
    language: ClassVar[List[str]] = ['pl']

    def __init__(self):
        self.session = requests.Session()

    # ── public API ──────────────────────────────────────────────────────────

    def movie(self, imdb: str, title: str, localtitle: str, aliases: list, year: str) -> Optional[str]:
        return self._find(title, localtitle, year, 'movie')

    def tvshow(self, imdb: str, tvdb: str, tvshowtitle: str, localtvshowtitle: str, aliases: list, year: str) -> Optional[str]:
        return self._find(tvshowtitle, localtvshowtitle, year, 'tvshow')

    def episode(self, url: Optional[str], imdb: str, tvdb: str, title: str, premiered: str, season: str, episode: str) -> Optional[str]:
        if not url:
            return None
        news_id = _news_id_from_url(url)
        if not news_id:
            return None
        embed_url = self._get_episode_embed(news_id, int(season), int(episode))
        fflog(f'episode embed url: {embed_url}')
        return embed_url

    def sources(self, url: Optional[str], hostDict: List[str], hostprDict: List[str]) -> 'List[SourceItem]':
        results: List[SourceItem] = []
        if not url:
            return results
        try:
            if 'seriale.vip' in url:
                news_id = _news_id_from_url(url)
                if not news_id:
                    return results
                embed_url = self._get_movie_embed(news_id)
            else:
                embed_url = url
            if embed_url:
                stream_url = self._resolve_embed(embed_url)
                if stream_url:
                    host = _host_label(embed_url)
                    results.append({
                        'source': host,
                        'quality': '1080p',
                        'language': 'pl',
                        'url': stream_url,
                        'info': 'LEKTOR',
                        'direct': True,
                        'debridonly': False,
                        'premium': False,
                    })
        except Exception:
            fflog_exc()
        fflog(f'sources: {len(results)}')
        return results

    def resolve(self, url: str) -> Optional[str]:
        return url

    # ── embed resolvers ─────────────────────────────────────────────────────

    def _resolve_embed(self, embed_url: str) -> Optional[str]:
        host = _host_label(embed_url)
        if host == 'vidara':
            stream_url = self._resolve_vidara(embed_url)
        elif host in ('veev', 'poophq', 'doods'):
            stream_url = self._resolve_veev(embed_url)
        else:
            fflog(f'unknown embed host: {host}')
            return None
        if not stream_url:
            return None
        base_url = stream_url.partition('|')[0]
        if '.m3u8' in base_url or '.mpd' in base_url:
            return f'isa+{stream_url}'
        return stream_url

    def _resolve_vidara(self, embed_url: str) -> Optional[str]:
        filecode = embed_url.rstrip('/').split('/')[-1]
        try:
            resp = self.session.post(
                f'{_VIDARA_BASE}/api/stream',
                data=json.dumps({'filecode': filecode}).encode(),
                headers={
                    'User-Agent': _UA,
                    'Referer': f'{_VIDARA_BASE}/e/{filecode}',
                    'Origin': _VIDARA_BASE,
                    'Content-Type': 'application/json',
                },
                timeout=15,
            )
            streaming_url = resp.json().get('streaming_url')
            if streaming_url:
                hdrs = {'User-Agent': _UA, 'Referer': _VIDARA_BASE + '/', 'Origin': _VIDARA_BASE}
                return streaming_url + append_headers(hdrs)
        except Exception:
            fflog_exc()
        return None

    def _resolve_veev(self, embed_url: str) -> Optional[str]:
        try:
            headers = {'User-Agent': _UA, 'Referer': embed_url}
            resp = self.session.get(embed_url, headers=headers, timeout=15)
            final_url = resp.url
            media_id = final_url.rstrip('/').split('/')[-1]
            html = resp.text
            items = re.findall(
                r'''[\.\s'](?:fc|_vvto\[[^\]]*)(?:['\]]*)?\s*[:=]\s*['"]([^'"]+)''', html
            )
            for fc_candidate in reversed(items):
                ch = _veev_lzw_decode(fc_candidate)
                if ch == fc_candidate:
                    continue
                params = {'op': 'player_api', 'cmd': 'gi', 'file_code': media_id, 'ch': ch, 'ie': 1}
                dl_url = urljoin(final_url, '/dl') + '?' + urlencode(params)
                raw = self.session.get(dl_url, headers=headers, timeout=15).text
                file_data = json.loads(raw).get('file')
                if file_data and file_data.get('file_status') == 'OK':
                    stream_url = _veev_decode_url(
                        _veev_lzw_decode(file_data['dv'][0]['s']),
                        _veev_build_array(ch)[0],
                    )
                    hdrs = {'User-Agent': _UA, 'Referer': embed_url, 'verifypeer': 'false'}
                    return stream_url + append_headers(hdrs)
                return None
        except Exception:
            fflog_exc()
        return None

    # ── search & playlist ───────────────────────────────────────────────────

    def _find(self, title: str, localtitle: str, year: str, media_type: str) -> Optional[str]:
        try:
            for query in search_queries_extended(localtitle or title, title or localtitle):
                if result := self._search(query, year, media_type):
                    return result
                control.sleep(400)
        except Exception:
            fflog_exc()
        return None

    def _search(self, query: str, year: str, media_type: str) -> Optional[str]:
        fflog(f'query={query!r} year={year} type={media_type}')
        try:
            resp = self.session.post(
                _SEARCH_URL,
                data={'do': 'search', 'subaction': 'search', 'story': query},
                headers=_HEADERS,
                timeout=20,
            )
        except Exception:
            fflog_exc()
            return None

        if resp.status_code != 200:
            fflog(f'search HTTP {resp.status_code}')
            return None

        # Result cards: <a class="poster ...">...</a> — isolate each block first so
        # the title <h3> is not accidentally picked up from the next card.
        clean_query = cleantitle.get_simple(query)
        exp_year = int(year) if year and year.isdigit() else 0
        movie_paths = {'films', 'animation', 'anime', 'reality'}

        matched: List[tuple] = []
        for card_match in re.finditer(
            r'<a\s[^>]*class="[^"]*poster[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>',
            resp.text,
            re.DOTALL,
        ):
            href = card_match.group(1)
            block = card_match.group(2)

            url_match = re.search(
                r'(films|serials|animation|anime|reality)/(\d+)-', href
            )
            if not url_match:
                continue
            path, news_id = url_match.group(1), url_match.group(2)

            is_movie = path in movie_paths
            if media_type == 'movie' and not is_movie:
                continue
            if media_type == 'tvshow' and is_movie:
                continue

            if exp_year and not year_matches(href, exp_year):
                continue

            title_match = re.search(
                r'<h3[^>]*class="[^"]*poster__title[^"]*"[^>]*>([^<]+)</h3>', block
            )
            if not title_match:
                continue
            title_clean = cleantitle.get_simple(title_match.group(1).strip())
            if clean_query and clean_query not in title_clean:
                continue

            matched.append((href, news_id))

        if matched:
            fflog(f'found: {matched[0][0]}')
            return matched[0][0]
        return None

    def _fetch_playlist(self, news_id: str) -> Optional[str]:
        try:
            resp = self.session.post(
                _PLAYLIST_URL,
                data={'news_id': news_id, 'xfield': 'pl'},
                headers=_AJAX_HEADERS,
                timeout=20,
            )
            data = resp.json()
            if data.get('success'):
                return data['response']
        except Exception:
            fflog_exc()
        return None

    def _get_movie_embed(self, news_id: str) -> Optional[str]:
        html = self._fetch_playlist(news_id)
        if not html:
            return None
        match = re.search(
            r'data-file="(https://(?:veev\.to|vidara\.(?:so|to)|vidaraa\.cc|streamix\.so|stmix\.io)/(?:e|v)/[^"]+)"',
            html,
        )
        return match.group(1) if match else None

    def _get_episode_embed(self, news_id: str, season: int, episode: int) -> Optional[str]:
        html = self._fetch_playlist(news_id)
        if not html:
            return None

        season_idx = season - 1

        # Each <li data-id="X_Y_Z"> (depth=2) names a provider+language track.
        # Collect all LEKTOR tracks in playlist order — we'll pick the first that
        # has the requested episode (respects correct per-season numbering from Vidara
        # over cumulative-episode numbering from VEEV).
        list_items = re.findall(r'<li data-id="([^"]+)">([^<]+)</li>', html)
        lektor_prefixes: List[str] = []
        for data_id, label in list_items:
            if data_id.count('_') == 2 and label.strip().upper() == 'LEKTOR':
                lektor_prefixes.append(data_id)

        all_video_items = re.findall(r'<li data-file="([^"]+)" data-id="([^"]+)">', html)

        def _pick_from_prefix(prefix: str) -> Optional[str]:
            target_id = f'{prefix}_{season_idx}'
            season_eps = [ep_url for ep_url, did in all_video_items if did == target_id]
            if season_eps and 1 <= episode <= len(season_eps):
                return season_eps[episode - 1]
            return None

        for prefix in lektor_prefixes:
            ep_url = _pick_from_prefix(prefix)
            if ep_url:
                return ep_url

        fflog(f'episode not found: s{season}e{episode}')
        return None


def _news_id_from_url(url: str) -> Optional[str]:
    match = re.search(r'/(?:films|serials|animation|anime|reality)/(\d+)-', url)
    return match.group(1) if match else None


def _host_label(url: str) -> str:
    try:
        from urllib.parse import urlparse
        host = urlparse(url).hostname or ''
        return host.split('.')[-2] if '.' in host else host
    except Exception:
        return 'embed'


# ── Veev decode helpers ──────────────────────────────────────────────────────

def _veev_lzw_decode(etext: str) -> str:
    result = []
    lut = {}
    n = 256
    c = etext[0]
    result.append(c)
    for char in etext[1:]:
        code = ord(char)
        nc = char if code < 256 else lut.get(code, c + c[0])
        result.append(nc)
        lut[n] = c + nc[0]
        n += 1
        c = nc
    return ''.join(result)


def _veev_js_int(x: str) -> int:
    return int(x) if x.isdigit() else 0


def _veev_build_array(encoded_string: str) -> list:
    d = []
    c = list(encoded_string)
    count = _veev_js_int(c.pop(0))
    while count:
        current_array = []
        for _ in range(count):
            current_array.insert(0, _veev_js_int(c.pop(0)))
        d.append(current_array)
        count = _veev_js_int(c.pop(0))
    return d


def _veev_decode_url(etext: str, tarray: list) -> str:
    ds = etext
    for t in tarray:
        if t == 1:
            ds = ds[::-1]
        ds = binascii.unhexlify(ds).decode('utf8')
        ds = ds.replace('dXRmOA==', '')
    return ds
