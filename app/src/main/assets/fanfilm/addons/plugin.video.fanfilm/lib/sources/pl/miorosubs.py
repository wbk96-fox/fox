# -*- coding: utf-8 -*-
"""
FanFilm - źródło: miorosubs.com
Copyright (C) 2026 :)

Dystrybuowane na licencji MIT <https://mit-license.org>

Flow:
  1. movie()/episode() → kitsu.py mapuje TMDB → cour (tytuł romaji + local_ep)
  2. ajax_search.php?q=<tytuł> → fuzzy wyszukiwarka strony zwraca listę
     kandydatów {id, title, title_english, title_polish, score}
  3. anime.php?id=<id> → lista odcinków "Odcinek N" → watch.php?id=<episode_id>
  4. watch.php?id=<episode_id> → JS `const playersData = [...]` z embed_code
     dla każdego playera (VK/RPM/UPN/SHORT/FILEMOON/HGLINK/STREAMP2P)
"""

from __future__ import annotations

import json
import re
from typing import Dict, List, Optional, Tuple, TYPE_CHECKING, ClassVar

from lib.ff import requests, source_utils
from lib.ff.source_utils import DEFAULT_UA, sources_with_links
from lib.ff.log_utils import fflog, fflog_exc
from lib.ff.item import FFItem
from lib.api import kitsu as kitsu_api

if TYPE_CHECKING:
    from lib.sources import SourceItem, SourceTitleAlias

_RE_EPISODE = re.compile(
    r'<a href="watch\.php\?id=(\d+)" class="ep-card">.*?'
    r'<span class="ep-card-num">Odcinek\s*(\d+)</span>',
    re.DOTALL,
)
_RE_SRC = re.compile(r'src=["\']([^"\']+)["\']', re.IGNORECASE)

#: Minimalny score z ajax_search.php akceptowany, gdy nie ma dopasowania dokładnego.
_SCORE_THRESHOLD = 60


# ─── source ──────────────────────────────────────────────────────────────────────

class source:

    # set in ff/sources.py
    ffitem: FFItem

    priority: ClassVar[int] = 1
    language: ClassVar[List[str]] = ['pl']

    def __init__(self):
        self.base_link = 'https://miorosubs.com'
        self.search_link = f'{self.base_link}/ajax_search.php?q=%s'
        self.anime_link = f'{self.base_link}/anime.php?id=%s'
        self.watch_link = f'{self.base_link}/watch.php?id=%s'
        self.session = requests.Session()
        self.headers = {
            'User-Agent': DEFAULT_UA,
            'Accept-Language': 'pl,en-US;q=0.7,en;q=0.3',
            'Referer': self.base_link + '/',
        }
        # per-sesja cache: anime_id → {ep_num: watch_id}
        self._episode_map_cache: Dict[int, Dict[int, int]] = {}

    # ── public api ─────────────────────────────────────────────────────────

    @fflog_exc
    def movie(self, imdb: str, title: str, localtitle: str, aliases: list[SourceTitleAlias], year: str) -> Optional[str]:
        if not source_utils.is_anime(self.ffitem):
            return None
        tmdb = self.ffitem.tmdb_id
        movie_info = kitsu_api.get_movie_info(tmdb) if tmdb else None
        if not movie_info:
            fflog(f'miorosubs/movie: brak danych kitsu dla tmdb={tmdb}')
            return None

        kitsu_title = movie_info['title']
        anime_id = self._find_anime_id(kitsu_title)
        if not anime_id:
            fflog(f'miorosubs/movie: brak dopasowania dla "{kitsu_title}"')
            return None

        episodes = self._get_episode_map(anime_id)
        watch_id = episodes.get(1) or (min(episodes.values()) if episodes else None)
        if not watch_id:
            fflog(f'miorosubs/movie: anime_id={anime_id} brak odcinków')
            return None

        fflog(f'miorosubs/movie: "{kitsu_title}" anime_id={anime_id} → watch_id={watch_id}')
        return str(watch_id)

    def tvshow(self, imdb: str, tvdb: str, tvshowtitle: str, localtvshowtitle: str, aliases: list[SourceTitleAlias], year: str) -> str:
        return tvshowtitle

    @fflog_exc
    def episode(self, url: Optional[str], imdb: str, tvdb: str, title: str, premiered: str, season: str, episode: str) -> Optional[str]:
        if not self.ffitem.show_item or not source_utils.is_anime(self.ffitem):
            return None
        tmdb = self.ffitem.show_item.tmdb_id
        if not tmdb:
            return None

        cours = kitsu_api.get_cour_structure(tmdb)
        if not cours:
            fflog(f'miorosubs: brak danych kitsu dla tmdb={tmdb}')
            return None

        cour, local_ep = kitsu_api.resolve_cour(cours, self.ffitem)
        if not cour or local_ep is None:
            fflog(f'miorosubs: nie znaleziono coura dla tmdb={tmdb} S{season}E{episode}')
            return None

        kitsu_title = cour['title']
        anime_id = self._find_anime_id(kitsu_title)
        if not anime_id:
            fflog(f'miorosubs: brak dopasowania dla "{kitsu_title}"')
            return None

        episodes = self._get_episode_map(anime_id)
        watch_id = episodes.get(local_ep)
        if not watch_id:
            fflog(f'miorosubs: anime_id={anime_id} brak odcinka {local_ep}')
            return None

        fflog(f'miorosubs: cour="{kitsu_title}" anime_id={anime_id} local_ep={local_ep} → watch_id={watch_id}')
        return str(watch_id)

    @fflog_exc
    def sources(self, url: Optional[str], hostDict: List[str], hostprDict: List[str]) -> 'List[SourceItem]':
        sources: 'List[SourceItem]' = []
        if not url:
            return sources
        if isinstance(url, list):
            url = url[0]

        try:
            watch_id = int(url)
        except (TypeError, ValueError):
            return sources

        resp = self.session.get(self.watch_link % watch_id, headers=self.headers, timeout=15)
        if not resp or resp.status_code != 200:
            return sources

        players = self._parse_players(resp.text)
        filename = f'miorosubs/{watch_id}'

        for player_name, embed_url in players:
            sources.append({
                'source': player_name.upper(),
                'quality': '720p',
                'language': 'pl',
                'url': embed_url,
                'info': '',
                'filename': filename,
                'direct': False,
                'debridonly': False,
                'premium': False,
            })

        result = sources_with_links(sources)
        fflog(f'sources: {len(result)} dla watch_id={watch_id}')
        return result

    @fflog_exc
    def resolve(self, url: str) -> Optional[str]:
        return url

    # ── helpers ────────────────────────────────────────────────────────────

    def _find_anime_id(self, kitsu_title: str) -> Optional[int]:
        """Szuka anime po tytule kitsu (romaji) przez ajax_search.php (fuzzy score)."""
        kitsu_norm = kitsu_api.normalize_romaji(kitsu_title)

        resp = self.session.get(self.search_link % kitsu_title, headers=self.headers, timeout=15)
        if not resp or resp.status_code != 200:
            return None

        try:
            results = resp.json()
        except Exception:
            fflog_exc()
            return None

        if not results:
            fflog(f'miorosubs: brak wyników dla "{kitsu_title}"')
            return None

        fflog(f'miorosubs: wyniki dla "{kitsu_title}": '
              f'{[(r.get("id"), r.get("title"), r.get("score")) for r in results]}')

        # 1. dopasowanie dokładne (po normalizacji romaji) na dowolnym z pól tytułu
        for r in results:
            for key in ('title', 'title_english', 'title_polish'):
                val = r.get(key) or ''
                if val and kitsu_api.normalize_romaji(val) == kitsu_norm:
                    fflog(f'miorosubs: dopasowanie dokładne "{val}" → id={r["id"]}')
                    return r['id']

        # 2. najwyższy score, jeśli powyżej progu
        best = max(results, key=lambda r: r.get('score', 0))
        if best.get('score', 0) >= _SCORE_THRESHOLD:
            fflog(f'miorosubs: dopasowanie po score={best.get("score")} '
                  f'"{best.get("title")}" → id={best["id"]}')
            return best['id']

        fflog(f'miorosubs: brak wystarczająco pewnego dopasowania dla "{kitsu_title}" '
              f'(best score={best.get("score")})')
        return None

    def _get_episode_map(self, anime_id: int) -> Dict[int, int]:
        """Zwraca {numer_odcinka: watch_id} dla danego anime_id (z cache)."""
        if anime_id in self._episode_map_cache:
            return self._episode_map_cache[anime_id]

        episodes: Dict[int, int] = {}
        resp = self.session.get(self.anime_link % anime_id, headers=self.headers, timeout=15)
        if resp and resp.status_code == 200:
            for watch_id, ep_num in _RE_EPISODE.findall(resp.text):
                episodes[int(ep_num)] = int(watch_id)

        self._episode_map_cache[anime_id] = episodes
        return episodes

    def _parse_players(self, html: str) -> List[Tuple[str, str]]:
        """Parsuje `const playersData = [...]` ze strony watch.php → [(player_name, embed_url), ...]."""
        m = re.search(r'const\s+playersData\s*=\s*', html)
        if not m:
            return []

        try:
            data, _ = json.JSONDecoder().raw_decode(html, m.end())
        except Exception:
            fflog_exc()
            return []

        players: List[Tuple[str, str]] = []
        for player in data:
            name = player.get('player_name') or ''
            embed_code = player.get('embed_code') or ''
            if not name or not embed_code:
                continue
            src_match = _RE_SRC.search(embed_code)
            if not src_match:
                continue
            embed_url = src_match.group(1).strip()
            if not embed_url:
                continue
            players.append((name, embed_url))

        return players
