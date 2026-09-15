# -*- coding: utf-8 -*-
"""
FanFilm - źródło: KissKH (dramy azjatyckie - oryginalne audio + wybór napisów)
Copyright (C) 2026 :)

Dystrybuowane na licencji MIT <https://mit-license.org>

kkey liczony przez source_utils.enc_dec_result() (jak videasy/vidcore/multivid);
dec-kisskh zwraca surowy SRT, więc wołany osobno. Napisy przez window property
'source.subtitles' (player.py), jak w wrzucaj.py.
"""

from __future__ import annotations

import json
import os
import re
from typing import Any, ClassVar, Dict, List, Optional, TYPE_CHECKING
from urllib.parse import urlencode, parse_qs

from lib.ff import requests, cleantitle, control
from lib.ff.source_utils import (DEFAULT_UA, append_headers, enc_dec_result,
                                 search_queries_extended, build_alias_list,
                                 is_asian_content, ShowData, ShowDataDict, show_data_asdict)
from lib.ff.log_utils import fflog, fflog_exc

if TYPE_CHECKING:
    from lib.ff.item import FFItem
    from lib.sources import SourceItem, SourceTitleAlias


_BASE = 'https://kisskh.do'
_SCHEME = 'kisskh://'
_STREAM_HEADERS = {'User-Agent': DEFAULT_UA, 'Referer': _BASE + '/'}
_WANTED_LANGS = ('pl', 'en')
_RE_ARTICLE = re.compile(r'^(?:the|an?)\s+', re.I)
_SUB_DIR = os.path.join(control.transPath('special://temp'), 'kisskh_subs')


def _clean(raw: str) -> str:
    return cleantitle.get_simple(_RE_ARTICLE.sub('', raw or ''))


# ─── source ──────────────────────────────────────────────────────────────────────

class source:
    ffitem: 'FFItem'

    priority: ClassVar[int] = 1
    language: ClassVar[List[str]] = ['en']

    def __init__(self) -> None:
        self.base_link = _BASE
        self.session = requests.Session()
        self.session.headers.update({
            'User-Agent': DEFAULT_UA,
            'Accept': 'application/json',
            'Referer': self.base_link + '/',
        })

    # ── public api ─────────────────────────────────────────────────────────

    def movie(self, imdb: str, title: str, localtitle: str,
              aliases: 'list[SourceTitleAlias]', year: str) -> Optional[str]:
        if not is_asian_content(self.ffitem):
            fflog('not an Asian title, skip')
            return None
        drama = self._find_drama(title, localtitle, aliases, year)
        if not drama:
            return None
        episode = self._pick_episode(self._get_detail(drama['id']), 1)
        return _encode(episode) if episode else None

    def tvshow(self, imdb: str, tvdb: str, tvshowtitle: str, localtvshowtitle: str,
               aliases: 'list[SourceTitleAlias]', year: str) -> ShowDataDict:
        return show_data_asdict(ShowData(tvshowtitle, localtvshowtitle, aliases, int(year)))

    def episode(self, url: 'ShowDataDict | None', imdb: str, tvdb: str, title: str,
                premiered: str, season: str, episode: str) -> Optional[str]:
        if not url:
            return None
        if not is_asian_content(self.ffitem.show_item):
            fflog(f'not an Asian title, skip ({title!r})')
            return None
        data = ShowData(**url)
        drama = self._find_drama(data.title, data.local_title, data.aliases, data.year, int(season))
        if not drama:
            return None
        ep = self._pick_episode(self._get_detail(drama['id']), float(episode))
        return _encode(ep) if ep else None

    def sources(self, url: Optional[str], hostDict: List[str],
                hostprDict: List[str]) -> 'List[SourceItem]':
        if not url:
            return []
        _, sub_count = _decode(url)
        item: 'SourceItem' = {
            'source': 'KissKH',
            'quality': '1080p',
            'language': 'en',
            'info': 'MULTI',
            'url': url,
            'direct': True,
            'debridonly': False,
        }
        if sub_count:
            item['info2'] = 'Napisy'
        return [item]

    def resolve(self, url: str) -> Optional[str]:
        try:
            episode_id, _ = _decode(url)
            if not episode_id:
                return None
            video_url = self._fetch_video_url(episode_id)
            if not video_url:
                return None

            sub_urls = self._fetch_subtitle_urls(episode_id)
            if sub_urls:
                control.window().setProperty('source.subtitles', json.dumps(sub_urls))

            final = video_url + append_headers(_STREAM_HEADERS)
            return f'isa+{final}' if '.m3u8' in video_url else final
        except Exception:
            fflog_exc()
            return None

    # ── helpers ────────────────────────────────────────────────────────────

    def _find_drama(self, title: str, localtitle: str, aliases: 'list[SourceTitleAlias]',
                    year: 'str | int', season: 'int | None' = None) -> Optional[Dict[str, Any]]:
        # exact match only (releaseDate/fuzzy both unreliable here)
        alias_titles = list(build_alias_list(title, localtitle, aliases or [], year))
        if title and localtitle and title != localtitle:
            alias_titles += [f'{localtitle} - {title}', f'{title} - {localtitle}']
        if season:
            alias_titles += [f'{t} Season {season}' for t in (title, localtitle) if t]
        alias_cleans = {_clean(a) for a in alias_titles}
        alias_cleans.discard('')
        if not alias_cleans:
            return None
        try:
            for query in search_queries_extended(title, localtitle):
                for item in self._search(query):
                    cand = _clean(item.get('title') or '')
                    if cand and cand in alias_cleans:
                        return item
        except Exception:
            fflog_exc()
        return None

    def _search(self, query: str) -> 'List[Dict[str, Any]]':
        try:
            resp = self.session.get(f'{self.base_link}/api/DramaList/Search',
                                    params={'q': query}, timeout=15)
            if not resp.ok:
                return []
            results = resp.json() or []
            fflog(f'search {query!r} -> {len(results)} result(s)')
            return results if isinstance(results, list) else []
        except Exception:
            fflog_exc()
            return []

    def _get_detail(self, drama_id: int) -> Dict[str, Any]:
        try:
            resp = self.session.get(f'{self.base_link}/api/DramaList/Drama/{drama_id}',
                                    params={'isq': 'false'}, timeout=15)
            return resp.json() or {} if resp.ok else {}
        except Exception:
            fflog_exc()
            return {}

    @staticmethod
    def _pick_episode(detail: Dict[str, Any], wanted: float) -> Optional[Dict[str, Any]]:
        for ep in detail.get('episodes') or []:
            try:
                if float(ep.get('number')) == wanted:
                    return ep
            except (TypeError, ValueError):
                continue
        return None

    def _fetch_video_url(self, episode_id: str) -> Optional[str]:
        kkey = enc_dec_result('enc-kisskh', params={'text': episode_id, 'type': 'vid'})
        if not kkey:
            fflog(f'no vid kkey for episode_id={episode_id}')
            return None
        resp = self.session.get(
            f'{self.base_link}/api/DramaList/Episode/{episode_id}.png',
            params={'err': 'false', 'ts': '', 'time': '', 'kkey': kkey},
            timeout=15,
        )
        if not resp.ok:
            return None
        return (resp.json() or {}).get('Video') or None

    def _fetch_subtitle_urls(self, episode_id: str) -> List[str]:
        try:
            sub_kkey = enc_dec_result('enc-kisskh', params={'text': episode_id, 'type': 'sub'})
            if not sub_kkey:
                return []
            resp = self.session.get(f'{self.base_link}/api/Sub/{episode_id}',
                                    params={'kkey': sub_kkey}, timeout=15)
            if not resp.ok:
                return []
            entries = {e.get('land'): e for e in resp.json() or [] if e.get('land') in _WANTED_LANGS}
            if not entries:
                return []
            os.makedirs(_SUB_DIR, exist_ok=True)
            paths = []
            for lang, entry in entries.items():
                if not entry.get('src'):
                    continue
                srt = self.session.get('https://enc-dec.app/api/dec-kisskh',
                                       params={'url': entry['src']}, timeout=15)
                if not srt.ok or not srt.text:
                    continue
                path = os.path.join(_SUB_DIR, f'{episode_id}.{lang}.srt')
                with open(path, 'w', encoding='utf-8') as f:
                    f.write(srt.text)
                paths.append(path)
            return paths
        except Exception:
            fflog_exc()
            return []


# ─── module helpers ──────────────────────────────────────────────────────────────


def _encode(episode: Dict[str, Any]) -> str:
    return _SCHEME + urlencode({'id': episode['id'], 'sub': episode.get('sub') or 0})


def _decode(url: str) -> 'tuple[str, int]':
    if not url.startswith(_SCHEME):
        return '', 0
    parsed = parse_qs(url[len(_SCHEME):])
    episode_id = parsed.get('id', [''])[0]
    sub_count = int(parsed.get('sub', ['0'])[0] or 0)
    return episode_id, sub_count
