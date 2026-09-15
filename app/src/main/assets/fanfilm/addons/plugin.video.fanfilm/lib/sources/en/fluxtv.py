# -*- coding: utf-8 -*-
"""
FanFilm - source: NflixMovies (nflixmovies.app, "FluxTV" main server on fluxtv.cc)

Distributed under the MIT license <https://mit-license.org>

nflixmovies.app is the branded player fluxtv.cc iframes for its default
"FluxTV" server. It runs its own backend (guest-token auth + a
mint-direct API) that itself aggregates several real providers - no
client-side crypto or JS challenge to reverse, unlike Cineby/Vidking.

Flow:
  1. GET /api/auth/guest -> sets an `sv_guest` session cookie (no CAPTCHA,
     plain HTTP works fine - verified with curl, no browser needed).
  2. GET /api/mint-direct/api/v1/play?id={tmdb}&type=movie|tv[&season=&episode=]
     -> JSON with a `sources` list per provider. Only the `movieboxtv`
     entries are used: they carry a `progressiveUrl` that is a plain,
     already-signed CDN mp4 link (Aliyun OSS), playable directly with no
     extra headers, no inputstream.adaptive. A secondary `nflixmovies`
     HLS entry sometimes appears too, but 403s from outside the site's
     own edge network, so it is skipped.

The signed mp4 URL's expiry is comfortably far out (hours), so sources()
resolves eagerly and resolve() is a no-op passthrough.
"""

from __future__ import annotations

from typing import ClassVar, Dict, List, Optional, TYPE_CHECKING
from urllib.parse import urlencode, parse_qs

from lib.ff import requests
from lib.ff.source_utils import get_quality
from lib.ff.log_utils import fflog, fflog_exc

if TYPE_CHECKING:
    from lib.ff.item import FFItem
    from lib.sources import SourceItem, SourceTitleAlias


_UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
       '(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36')
_BASE = 'https://nflixmovies.app'
_GUEST_URL = f'{_BASE}/api/auth/guest'
_PLAY_URL = f'{_BASE}/api/mint-direct/api/v1/play'
_SCHEME = 'nflixmovies://'


# ─── source ──────────────────────────────────────────────────────────────────────

class source:
    priority: ClassVar[int] = 1
    language: ClassVar[List[str]] = ['en']

    ffitem: 'FFItem'

    def __init__(self) -> None:
        self.session = requests.Session()
        self.session.headers.update({'User-Agent': _UA, 'Accept': 'application/json'})

    # ── public api ─────────────────────────────────────────────────────────

    def movie(self, imdb: str, title: str, localtitle: str,
              aliases: 'list[SourceTitleAlias]', year: str) -> Optional[str]:
        tmdb = str(self.ffitem.tmdb_id or '')
        if not tmdb:
            return None
        return self._encode(type='movie', tmdb=tmdb)

    def tvshow(self, imdb: str, tvdb: str, tvshowtitle: str, localtvshowtitle: str,
               aliases: 'list[SourceTitleAlias]', year: str) -> Optional[str]:
        show_item = getattr(self.ffitem, 'show_item', None)
        tmdb = str((show_item.tmdb_id if show_item else None) or self.ffitem.tmdb_id or '')
        if not tmdb:
            return None
        return self._encode(type='tv', tmdb=tmdb)

    def episode(self, url: Optional[str], imdb: str, tvdb: str, title: str,
                premiered: str, season: str, episode: str) -> Optional[str]:
        if not url:
            return None
        return f'{url}&s={int(season)}&e={int(episode)}'

    def sources(self, url: Optional[str], hostDict: List[str],
                hostprDict: List[str]) -> 'List[SourceItem]':
        if not url:
            return []
        params = self._decode(url)
        tmdb = params.get('tmdb')
        media_type = params.get('type', 'movie')
        if not tmdb:
            return []

        if not self._ensure_guest():
            return []

        q: Dict[str, str] = {'id': tmdb, 'type': media_type}
        if media_type == 'tv':
            q['season'] = params.get('s', '1')
            q['episode'] = params.get('e', '1')

        try:
            r = self.session.get(_PLAY_URL, params=q, timeout=8)
        except Exception:
            fflog_exc()
            return []
        if not r.ok:
            return []
        try:
            data = r.json()
        except Exception:
            return []
        if not data.get('ok'):
            return []

        results: List[dict] = []
        seen: set = set()
        for entry in (data.get('sources') or data.get('menuSources') or []):
            if entry.get('provider') != 'movieboxtv':
                continue
            play_url = entry.get('progressiveUrl')
            if not play_url or play_url in seen:
                continue
            seen.add(play_url)
            quality = get_quality(str(entry.get('quality') or entry.get('progressiveQuality') or ''))
            results.append({
                'source': 'NFLIXMOVIES',
                'quality': quality,
                'language': 'en',
                'url': play_url,
                'direct': True,
                'debridonly': False,
            })

        if not results and data.get('provider') == 'movieboxtv' and data.get('directUrl'):
            quality = get_quality(str(data.get('quality') or ''))
            results.append({
                'source': 'NFLIXMOVIES',
                'quality': quality,
                'language': 'en',
                'url': data['directUrl'],
                'direct': True,
                'debridonly': False,
            })

        fflog(f'nflixmovies: found {len(results)} stream(s)')
        return results

    def resolve(self, url: str) -> Optional[str]:
        return url

    # ── helpers ────────────────────────────────────────────────────────────

    @staticmethod
    def _encode(**kwargs: str) -> str:
        return _SCHEME + urlencode({k: v for k, v in kwargs.items() if v is not None})

    @staticmethod
    def _decode(url: str) -> Dict[str, str]:
        if not url.startswith(_SCHEME):
            return {}
        qs = url[len(_SCHEME):]
        parsed = parse_qs(qs, keep_blank_values=True)
        return {k: v[0] for k, v in parsed.items()}

    def _ensure_guest(self) -> bool:
        if self.session.cookies.get('sv_guest'):
            return True
        try:
            r = self.session.get(_GUEST_URL, timeout=10)
            return r.ok
        except Exception:
            fflog_exc()
            return False


if __name__ == '__main__':
    try:
        from lib.ff.cmdline import DebugArgumentParser as ArgumentParser
    except ImportError:
        from argparse import ArgumentParser
    parser = ArgumentParser(description='Test NflixMovies source provider')
    parser.add_argument('tmdb_id', help='TMDB ID')
    parser.add_argument('--type', default='movie', choices=['movie', 'tv'])
    parser.add_argument('--season', type=int, default=None)
    parser.add_argument('--episode', type=int, default=None)
    args = parser.parse_args()
    src = source()
    url = source._encode(type=args.type, tmdb=args.tmdb_id)
    if args.type == 'tv':
        url = f'{url}&s={args.season}&e={args.episode}'
    try:
        from pprint import pprint
        pprint(src.sources(url, [], []))
    except Exception as e:
        print(f'Error: {e}')
