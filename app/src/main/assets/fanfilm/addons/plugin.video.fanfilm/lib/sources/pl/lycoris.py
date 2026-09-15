# -*- coding: utf-8 -*-
"""
FanFilm ‑ źródło: lycoris.cafe
Copyright (C) 2026 :)

Dystrybuowane na licencji MIT <https://mit-license.org>
"""

from __future__ import annotations

import re
from typing import List, Optional, TYPE_CHECKING, ClassVar

from lib.ff import requests, source_utils
from lib.ff.source_utils import sources_with_links
from lib.ff.log_utils import fflog, fflog_exc
from lib.ff.item import FFItem
from lib.api import kitsu as kitsu_api

if TYPE_CHECKING:
    from lib.sources import SourceItem
    from lib.ff.types import JsonData

_cfg: dict = {}  # session cache: {url, anon}

# (json_field_in_source, quality_label, base_info)
_QUALITY_MAP: List[tuple] = [
    ('FHD',       '1080p', ''),
    ('SourceMKV', '1080p', 'mkv'),
    ('HD',        '720p',  ''),
    ('SD',        'SD',    ''),
]


# ─── source ──────────────────────────────────────────────────────────────────────

class source:
    ffitem: FFItem

    priority: ClassVar[int] = 1
    language: ClassVar[List[str]] = ['pl']

    def __init__(self):
        self.session = requests.Session()

    # ── public api ─────────────────────────────────────────────────────────

    @fflog_exc
    def movie(self, imdb: str, title: str, localtitle: str,
              aliases: list, year: str) -> Optional[List[str]]:
        if not source_utils.is_anime(self.ffitem):
            return None
        tmdb = self.ffitem.tmdb_id
        if not tmdb:
            return None
        movie_info = kitsu_api.get_movie_info(tmdb)
        if not movie_info:
            return None
        anilist_id = movie_info.get('anilist_id')
        if not anilist_id:
            fflog(f'lycoris/movie: brak anilist_id dla tmdb={tmdb}')
            return None
        fflog(f'lycoris/movie: "{movie_info["title"]}" anilist_id={anilist_id}')
        return [f'{anilist_id}|1']

    def tvshow(self, imdb: str, tvdb: str, tvshowtitle: str, localtvshowtitle: str,
               aliases: list, year: str) -> str:
        return tvshowtitle

    @fflog_exc
    def episode(self, url: Optional[str], imdb: str, tvdb: str, title: str,
                premiered: str, season: str, episode: str) -> Optional[str]:
        if not self.ffitem.show_item or not source_utils.is_anime(self.ffitem):
            return None
        tmdb = self.ffitem.show_item.tmdb_id
        if not tmdb:
            return None

        cours = kitsu_api.get_cour_structure(tmdb)
        if not cours:
            fflog(f'lycoris: brak danych kitsu dla tmdb={tmdb}')
            return None

        cour, local_ep = kitsu_api.resolve_cour(cours, self.ffitem)
        if not cour or local_ep is None:
            fflog(f'lycoris: nie znaleziono coura dla tmdb={tmdb} S{season}E{episode}')
            return None

        anilist_id = cour.get('anilist_id')
        if not anilist_id:
            fflog(f'lycoris: brak anilist_id dla coura "{cour["title"]}"')
            return None

        fflog(f'lycoris: cour="{cour["title"]}" anilist_id={anilist_id} local_ep={local_ep}')
        return f'{anilist_id}|{local_ep}'

    @fflog_exc
    def sources(self, url: Optional[str], hostDict: List[str],
                hostprDict: List[str]) -> 'List[SourceItem]':
        if not url:
            return []
        if isinstance(url, list):
            url = url[0]

        anilist_id, ep_num_str = url.split('|')
        ep_num = int(ep_num_str)

        ep_data = self._fetch_episode(anilist_id, ep_num)
        if not ep_data:
            fflog(f'lycoris: anilist={anilist_id} ep={ep_num} — brak danych w Supabase')
            return []

        if ep_data.get('is_encrypted'):
            fflog(f'lycoris: anilist={anilist_id} ep={ep_num} — zaszyfrowany, pomijam')
            return []

        primary = ep_data.get('primary_source') or {}
        secondary = ep_data.get('secondary_source') or {}

        ep_title = ep_data.get('episode_title') or ''
        filename = f'lycoris/{anilist_id}/{ep_num:02d}'
        if ep_title:
            filename = f'{filename} {ep_title}'

        has_subs = bool((ep_data.get('subtitleLinks') or {}).get('PL'))

        candidates: List[SourceItem] = []
        for src_field, quality_label, base_info in _QUALITY_MAP:
            info_parts = [base_info] if base_info else []
            if has_subs:
                info_parts.append('NAPISY')
            info = ' '.join(info_parts)

            candidates.append({
                'source': 'LYCORIS PD',
                'quality': quality_label,
                'language': 'pl',
                'url': secondary.get(src_field) or '',
                'info': info,
                'filename': filename,
                'direct': True,
                'debridonly': False,
                'premium': False,
            })
            candidates.append({
                'source': 'LYCORIS OD',
                'quality': quality_label,
                'language': 'pl',
                'url': primary.get(src_field) or '',
                'info': info,
                'filename': filename,
                'direct': False,
                'debridonly': False,
                'premium': False,
            })

        result = sources_with_links(candidates)
        fflog(f'lycoris: {len(result)} sources dla anilist={anilist_id} ep={ep_num}')
        return result

    @fflog_exc
    def resolve(self, url: str) -> Optional[str]:
        if 'od.lk' not in url:
            return url

        resp = self.session.get(url, allow_redirects=False, timeout=10)
        if not resp:
            return None
        location = resp.headers.get('Location', '') or resp.headers.get('location', '')
        if resp.status_code not in (301, 302, 303, 307, 308) or not location:
            fflog(f'lycoris/od.lk: nieoczekiwana odpowiedź status={resp.status_code}')
            return None

        resp2 = self.session.get(location, timeout=10)
        if not resp2 or resp2.status_code != 200:
            fflog(f'lycoris/od.lk: błąd JSON status={resp2.status_code if resp2 else "?"}')
            return None
        try:
            data = resp2.json()
            download_url = data.get('download_link') or data.get('url') or data.get('link')
        except Exception:
            fflog('lycoris/od.lk: błąd parsowania JSON')
            return None

        if not download_url:
            fflog(f'lycoris/od.lk: brak URL w JSON, klucze={list(data.keys()) if isinstance(data, dict) else "?"}')
            return None

        fflog(f'lycoris/od.lk: → {download_url[:80]}')
        return download_url

    # ── helpers ────────────────────────────────────────────────────────────

    def _supabase_config(self) -> 'Optional[tuple]':
        if 'url' in _cfg:
            return _cfg['url'], _cfg['anon']
        resp = self.session.get('https://lycoris.cafe/', timeout=10)
        if not resp or resp.status_code != 200:
            fflog(f'lycoris: nie można pobrać config ({resp.status_code if resp else "?"})')
            return None
        url_match = re.search(r'"PUBLIC_SUPABASE_URL":"([^"]+)"', resp.text)
        key_match = re.search(r'"PUBLIC_SUPABASE_ANON_KEY":"([^"]+)"', resp.text)
        if not url_match or not key_match:
            fflog('lycoris: brak klucza Supabase w HTML strony')
            return None
        _cfg['url'] = url_match.group(1).rstrip('/')
        _cfg['anon'] = key_match.group(1)
        fflog(f'lycoris: config z {_cfg["url"]}')
        return _cfg['url'], _cfg['anon']

    def _fetch_episode(self, anilist_id: str, ep_num: int) -> 'Optional[JsonData]':
        config = self._supabase_config()
        if not config:
            return None
        supabase_url, anon_key = config
        headers = {
            'apikey': anon_key,
            'Authorization': f'Bearer {anon_key}',
        }
        resp = self.session.get(
            f'{supabase_url}/rest/v1/anime',
            headers=headers,
            params={
                'anilist_id': f'eq.{anilist_id}',
                'episode_number': f'eq.{ep_num}',
                'select': 'primary_source,secondary_source,is_encrypted,subtitleLinks,episode_title',
                'limit': '1',
            },
            timeout=10,
        )
        if not resp or resp.status_code != 200:
            fflog(f'lycoris/supabase: błąd status={resp.status_code if resp else "?"}')
            return None
        try:
            data = resp.json()
        except Exception:
            fflog('lycoris/supabase: błąd parsowania odpowiedzi')
            return None
        return data[0] if data else None
