# -*- coding: utf-8 -*-
"""
FanFilm - źródło: bajeczki24.pl
Copyright (C) 2026 :)

Dystrybuowane na licencji MIT <https://mit-license.org>
"""

from __future__ import annotations

import re
from typing import TYPE_CHECKING, List, Optional, ClassVar

from lib.ff import requests
from lib.ff import cleantitle
from lib.ff.item import FFItem
from lib.ff.source_utils import DEFAULT_UA, ShowData, ShowDataDict, show_data_asdict
from lib.ff.log_utils import fflog, fflog_exc

if TYPE_CHECKING:
    from lib.sources import SourceItem, SourceTitleAlias


_MEILI_BASE = 'https://api.bajeczki24.pl/radar/v1'
_MEILI_KEY = '118b6cd0fd246c02d4fe6465a1e5c16d7ce09fb67935180da44a060db68855c2'
_IDX_MOVIES = 'movies-v2'
_IDX_SHOWS = 'tv-shows-v2'


# ─── source ──────────────────────────────────────────────────────────────────────

class source:
    ffitem: FFItem

    priority: ClassVar[int] = 1
    language: ClassVar[List[str]] = ['pl']

    def __init__(self):
        self.base_link = 'https://bajeczki24.pl'
        self.headers = {
            'Referer': self.base_link,
            'User-Agent': DEFAULT_UA,
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
            'Accept-Language': 'pl,en-US;q=0.7,en;q=0.3',
        }
        self.api_headers = {
            'Authorization': f'Bearer {_MEILI_KEY}',
            'Content-Type': 'application/json',
            'User-Agent': DEFAULT_UA,
        }
        self.session = requests.Session()

    # ── public api ─────────────────────────────────────────────────────────

    def movie(self, imdb: str, title: str, localtitle: str, aliases: list[SourceTitleAlias], year: str) -> Optional[str]:
        return self._search_movie(title, localtitle, year)

    def tvshow(self, imdb: str, tvdb: str, tvshowtitle: str, localtvshowtitle: str, aliases: list[SourceTitleAlias], year: str) -> ShowDataDict:
        return show_data_asdict(ShowData(tvshowtitle, localtvshowtitle, aliases, int(year)))

    def episode(self, url: ShowDataDict | None, imdb: str, tvdb: str, title: str, premiered: str, season: str, episode: str) -> Optional[str]:
        if not url:
            return None
        data = ShowData(**url)
        return self._find_episode(data.title, data.local_title, data.year, season, episode)

    def sources(self, url: Optional[str], hostDict: List[str], hostprDict: List[str]) -> 'List[SourceItem]':
        try:
            if not url:
                return []

            resp = self.session.get(url, headers=self.headers, timeout=30, verify=False)
            if resp.status_code != 200:
                return []

            match_frame = re.search(r'<iframe.*?src="(.*?)"', resp.text)
            if not match_frame:
                return []

            frame = match_frame.group(1)
            host_match = re.search(r'https?://(?:www\.)?([^./]+)', frame)
            host_name = host_match.group(1) if host_match else frame

            return [{
                'source': host_name,
                'quality': '1080p',
                'language': 'pl',
                'url': frame,
                'info': '',
                'direct': False,
                'debridonly': False,
                'premium': False,
            }]
        except Exception:
            fflog_exc()
            return []

    def resolve(self, url: str) -> Optional[str]:
        return url

    # ── helpers ────────────────────────────────────────────────────────────

    def _search_api(self, query: str, index: str) -> list:
        try:
            resp = self.session.post(
                f'{_MEILI_BASE}/indexes/{index}/search',
                headers=self.api_headers,
                json={'q': query, 'limit': 10},
                timeout=15,
                verify=False,
            )
            if resp.status_code != 200:
                return []
            return resp.json().get('hits', [])
        except Exception:
            fflog_exc()
            return []

    def _find_hit(self, query: str, index: str, search_title_clean: str, year: Optional[str] = None) -> Optional[dict]:
        hits = self._search_api(query, index)
        fflog(f'search {index!r} q={query!r}: {len(hits)} hits')
        year_int = int(year) if year else None
        for hit in hits:
            hit_clean = cleantitle.get(hit.get('title', ''))
            if hit_clean != search_title_clean:
                continue
            if year_int and hit.get('releaseYear') and hit['releaseYear'] != year_int:
                continue
            return hit
        return None

    def _search_movie(self, title: str, localtitle: str, year: str) -> Optional[str]:
        try:
            if localtitle:
                hit = self._find_hit(localtitle, _IDX_MOVIES, cleantitle.get(localtitle), year)
                if hit:
                    return self.base_link + hit['path']
            if title and title.lower() != (localtitle or '').lower():
                hit = self._find_hit(title, _IDX_MOVIES, cleantitle.get(title), year)
                if hit:
                    return self.base_link + hit['path']
            return None
        except Exception:
            fflog_exc()
            return None

    def _find_episode(self, en_title: str, localtitle: str, year: int, season: str, episode: str) -> Optional[str]:
        fflog(f'searching episode: localtitle={localtitle!r} en={en_title!r} year={year} S{int(season):02d}E{int(episode):02d}')
        try:
            show_path = None
            year_str = str(year) if year else None

            if localtitle:
                hit = self._find_hit(localtitle, _IDX_SHOWS, cleantitle.get(localtitle), year_str)
                if hit:
                    show_path = hit['path']

            if not show_path and en_title and en_title.lower() != (localtitle or '').lower():
                hit = self._find_hit(en_title, _IDX_SHOWS, cleantitle.get(en_title), year_str)
                if hit:
                    show_path = hit['path']

            if not show_path:
                fflog(f'show not found: {localtitle!r} / {en_title!r}')
                return None

            episode_url = f'{self.base_link}{show_path}{int(season)}/{int(episode)}/'
            fflog(f'episode url: {episode_url}')
            return episode_url
        except Exception:
            fflog_exc()
            return None
