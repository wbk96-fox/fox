# -*- coding: utf-8 -*-
"""
FanFilm - źródło: DahmerMovies (a.111477.xyz)
Copyright (C) 2026 :)

Dystrybuowane na licencji MIT <https://mit-license.org>

Trzy równoległe strategie szukania plików w a.111477.xyz (połączone z
dawnego vidzee.py - obie serwowały ten sam katalog): (A) bezpośredni listing
po tytule+roku, (B) API dl.vidzee.wtf po tmdb id, (C) indeks xc-vod-files +
Xtream Codes API -> folder_url -> listing. ("vcd"/cinedown, trzeci backend
starego vidzee.py, wyleciał - domena nie istnieje.)

Odtwarzanie idzie przez lib/resolvers/_mint111477.py (zob. tamten docstring)
- a.111477.xyz same w sobie nie serwuje już plików.
"""

from __future__ import annotations

import re
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import ClassVar, Dict, List, Optional, TYPE_CHECKING
from urllib.parse import urlencode, parse_qs, quote

from lib.ff import control, requests
from lib.ff.settings import settings
from lib.ff.source_utils import DEFAULT_UA, parse_source_quality_lang, convert_size, setting_cookie
from lib.ff.log_utils import fflog, fflog_exc
from lib.kolang import L

if TYPE_CHECKING:
    from lib.ff.item import FFItem
    from lib.sources import SourceItem, SourceTitleAlias


_API = 'https://a.111477.xyz'
_VIDZEE_API = 'https://dl.vidzee.wtf'
_XVOD_INDEX = 'https://xc-vod-files.pages.dev'
_XTREAM_API = 'https://xtream-vod.data-search.workers.dev'

_SCHEME = 'dahmer://'
_MAX_RESULTS = 20

_FILE_EXT_RE = re.compile(r'\.(mkv|mp4|avi|webm|iso)$', re.IGNORECASE)

# a.111477.xyz's directory listing rows expose structured data-* attributes.
_RE_ROW = re.compile(r'<tr\s+data-entry[^>]*>(.*?)</tr>', re.DOTALL)
_RE_NAME = re.compile(r'data-name="([^"]*)"')
_RE_URL = re.compile(r'data-url="([^"]*)"')
_RE_SIZE = re.compile(r'data-sort="(\d+)"')

_COOKIE_SETTING = 'dahmer111477.cookies_cf'
_UA_SETTING = 'dahmer111477.user_agent'

# Proactive cookie validation cache — Window property shared across
# Kodi script invocations.  Avoids hammering p.111477.xyz on every
# sources() call while still catching expired cookies within 60 seconds.
_VALIDATE_PROP = 'FanFilm.dahmer111477.cookie_valid'
_VALIDATE_TTL = 60.0  # re-probe after 60 seconds

_HEADERS = {'User-Agent': DEFAULT_UA}
_VIDZEE_HEADERS = {
    'User-Agent': DEFAULT_UA,
    'Referer': 'https://player.vidzee.wtf/',
    'Origin': 'https://player.vidzee.wtf',
}

# Shared across the module - reused connections instead of one per request.
_session = requests.Session()


def _validate_cookie() -> bool:
    """Proactively test the cf_clearance cookie against p.111477.xyz.

    Returns True if the cookie is accepted (or was confirmed within the last
    _VALIDATE_TTL seconds).  Returns False if the cookie is missing, expired
    or rejected — the caller should abort the search and notify the user.

    Cached for _VALIDATE_TTL seconds to avoid hammering the server on every
    sources() call.  If the cookie dies between probes, the next sources()
    call will catch it immediately.
    """
    cf_cookie = setting_cookie(setting_name=_COOKIE_SETTING, cookie_name='cf_clearance')
    if not cf_cookie:
        return False

    # Check cache — was the cookie confirmed recently?
    win = control.window()
    ts = win.getProperty(_VALIDATE_PROP)
    if ts:
        try:
            if time.time() - float(ts) < _VALIDATE_TTL:
                return True
        except ValueError:
            pass

    # Probe the server.
    ua = settings.getString(_UA_SETTING).strip(' "\'') or DEFAULT_UA
    try:
        resp = _session.get(
            'https://p.111477.xyz/',
            cookies={'cf_clearance': cf_cookie},
            headers={'User-Agent': ua, 'Referer': 'https://p.111477.xyz/'},
            timeout=10,
            allow_redirects=False,
        )
        if resp.status_code in (403, 503):
            fflog(f'dahmermovies: cf_clearance rejected (HTTP {resp.status_code}) — cookie expired')
            win.clearProperty(_VALIDATE_PROP)
            return False
        if resp.status_code == 200 and '<title>Just a moment' in resp.text[:500]:
            fflog('dahmermovies: cf_clearance rejected (Cloudflare challenge) — cookie expired')
            win.clearProperty(_VALIDATE_PROP)
            return False
        win.setProperty(_VALIDATE_PROP, str(time.time()))
        return True
    except Exception:
        # Network error — don't kill the search, let strategies fail individually.
        fflog_exc()
        return True


# ─── source ──────────────────────────────────────────────────────────────────────

class source:
    priority: ClassVar[int] = 1
    language: ClassVar[List[str]] = ['en']

    ffitem: 'FFItem'

    # ── public api ─────────────────────────────────────────────────────────

    def movie(self, imdb: str, title: str, localtitle: str,
              aliases: 'list[SourceTitleAlias]', year: str) -> Optional[str]:
        if not title or not year:
            return None
        tmdb = str(self.ffitem.tmdb_id or '')
        return self._encode(type='movie', title=title, year=str(year), tmdb=tmdb)

    def tvshow(self, imdb: str, tvdb: str, tvshowtitle: str, localtvshowtitle: str,
               aliases: 'list[SourceTitleAlias]', year: str) -> Optional[str]:
        if not tvshowtitle:
            return None
        show_item = getattr(self.ffitem, 'show_item', None)
        tmdb = str((show_item.tmdb_id if show_item else None) or self.ffitem.tmdb_id or '')
        return self._encode(type='tv', title=tvshowtitle, year=str(year or ''), tmdb=tmdb)

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
        if not params or not params.get('title'):
            return []

        media_type = params.get('type', 'movie')
        tmdb = params.get('tmdb', '')
        season = params.get('s')
        episode = params.get('e')

        if not _validate_cookie():
            fflog('dahmermovies: cf_clearance for p.111477.xyz expired or missing — hiding sources')
            control.groupedInfoDialog('dahmermovies_cf_expired', L(32848, 'Refresh cookies'),
                                       heading='DahmerMovies', icon='ERROR')
            return []

        strategies = [lambda: self._listing_strategy(params)]
        if tmdb:
            strategies.append(lambda: self._v8_strategy(tmdb, media_type, season, episode))
            strategies.append(lambda: self._xvod_strategy(tmdb, media_type, season, episode, params.get('title', '')))

        found: Dict[str, dict] = {}
        with ThreadPoolExecutor(max_workers=len(strategies)) as executor:
            futures = [executor.submit(fn) for fn in strategies]
            for future in as_completed(futures, timeout=25):
                try:
                    for entry in future.result():
                        found.setdefault(entry['url'], entry)
                except Exception:
                    fflog_exc()

        fflog(f'dahmermovies: {len(found)} unique files from {len(strategies)} strategies')

        entries = list(found.values())

        if media_type == 'tv' and episode:
            e_num = int(episode)
            pat = re.compile(rf'E0*{e_num}\b', re.I)
            filtered = [e for e in entries if pat.search(e['name'])]
            if filtered:
                entries = filtered

        parsed = [(e, *parse_source_quality_lang(e['name'])) for e in entries]
        parsed.sort(key=lambda t: 0 if t[1] == '4K' else 1)

        result: List[dict] = []
        for entry, quality, language, info in parsed[:_MAX_RESULTS]:
            result.append({
                'source': 'DahmerMovies',
                'quality': quality,
                'language': language or 'en',
                'url': entry['url'],
                'info': info,
                'size': convert_size(entry['size']) if entry.get('size') else '',
                'filename': entry['name'],
                'direct': True,
                'debridonly': False,
            })

        fflog(f'dahmermovies: {len(result)} sources')
        return result

    def resolve(self, url: str) -> Optional[str]:
        from lib.resolvers._mint111477 import mint
        minted = mint(url)
        if not minted:
            fflog('dahmermovies: mint failed - no valid cf_clearance cookie for p.111477.xyz '
                  '(visit https://p.111477.xyz/ in a browser with the FanFilm userscript)')
        return minted

    # ── strategy A: direct directory listing ──────────────────────────────

    def _listing_strategy(self, params: Dict[str, str]) -> List[dict]:
        title = params.get('title', '')
        year = params.get('year', '')
        media_type = params.get('type', 'movie')
        clean = title.replace(':', '')

        if media_type == 'tv':
            s = int(params.get('s') or 1)
            variants = [
                f'/tvs/{quote(clean)}/Season%20{s:02d}/',
                f'/tvs/{quote(clean)}/Season%20{s}/',
            ]
        else:
            variants = [f'/movies/{quote(f"{clean} ({year})")}/']

        for path in variants:
            dir_url = _API + path
            try:
                resp = _session.get(dir_url, headers=_HEADERS, timeout=10)
                if resp.ok:
                    return self._parse_listing(resp.text, dir_url)
            except Exception:
                continue
        return []

    @staticmethod
    def _parse_listing(html: str, base_url: str) -> List[dict]:
        out: List[dict] = []
        for row_m in _RE_ROW.finditer(html):
            row = row_m.group(1)
            name_m = _RE_NAME.search(row)
            url_m = _RE_URL.search(row)
            if not (name_m and url_m):
                continue
            name = name_m.group(1)
            if not _FILE_EXT_RE.search(name):
                continue
            size_m = _RE_SIZE.search(row)
            path = url_m.group(1)
            full_url = path if path.startswith('http') else _API + path
            out.append({'url': full_url, 'name': name, 'size': int(size_m.group(1)) if size_m else 0})
        return out

    # ── strategy B: v8 API (dl.vidzee.wtf) ─────────────────────────────────

    @staticmethod
    def _v8_strategy(tmdb: str, media_type: str, season: Optional[str], episode: Optional[str]) -> List[dict]:
        try:
            if media_type == 'tv':
                api_url = f'{_VIDZEE_API}/download/tv/v8/{tmdb}/{season or 1}/{episode or 1}'
            else:
                api_url = f'{_VIDZEE_API}/download/movie/v8/{tmdb}'
            resp = _session.get(api_url, headers=_VIDZEE_HEADERS, timeout=15)
            if not resp.ok:
                return []
            data = resp.json()
            out = []
            for item in data.get('links') or []:
                u = (item.get('url') or '').strip()
                if not u or 'a.111477.xyz' not in u:
                    continue
                out.append({'url': u, 'name': item.get('name') or '', 'size': item.get('size') or 0})
            return out
        except Exception:
            return []

    # ── strategy C: xvod (xc-vod-files.pages.dev + xtream-vod API) ────────

    def _xvod_strategy(self, tmdb: str, media_type: str, season: Optional[str],
                       episode: Optional[str], title: str) -> List[dict]:
        try:
            if media_type == 'tv':
                return self._xvod_tv(tmdb, season or '1', episode or '1', title)
            return self._xvod_movie(tmdb)
        except Exception:
            fflog_exc()
            return []

    @staticmethod
    def _xvod_movie(tmdb: str) -> List[dict]:
        api = f'{_XTREAM_API}/player_api.php?action=get_vod_info&vod_id={tmdb}'
        resp = _session.get(api, headers=_HEADERS, timeout=15)
        if not resp.ok:
            return []
        data = resp.json()
        direct = data.get('movie_data', {}).get('direct_source', '')
        if not direct:
            return []
        folder = direct.rsplit('/', 1)[0] + '/'
        resp2 = _session.get(folder, headers=_HEADERS, timeout=20)
        if not resp2.ok:
            return []
        return source._parse_listing(resp2.text, folder)

    def _xvod_tv(self, tmdb: str, season: str, episode: str, title: str) -> List[dict]:
        ep_code = f'S{int(season):02d}E{int(episode):02d}'
        folder_base = self._xvod_tv_folder(tmdb, title)
        if not folder_base:
            return []
        folder_base = folder_base.rstrip('/')
        for season_path in (f'{folder_base}/Season {int(season)}/', f'{folder_base}/Season {int(season):02d}/'):
            resp = _session.get(season_path, headers=_HEADERS, timeout=20)
            if resp.ok:
                all_files = self._parse_listing(resp.text, season_path)
                matched = [f for f in all_files if ep_code in f['name']]
                if not matched:
                    matched = [f for f in all_files if ep_code.lower() in f['name'].lower()]
                return matched
        return []

    @staticmethod
    def _xvod_tv_folder(tmdb: str, title: str) -> Optional[str]:
        if title:
            folder = f'{_API}/tvs/{quote(title, safe="")}/'
            resp = _session.get(folder, headers=_HEADERS, timeout=10)
            if resp.ok:
                return folder
        resp = _session.get(f'{_XVOD_INDEX}/series.json', headers=_HEADERS, timeout=20)
        if not resp.ok:
            return None
        for s in resp.json():
            if str(s.get('tmdb_id', '')) == str(tmdb):
                return s.get('folder_url')
        return None

    # ── url encode/decode ───────────────────────────────────────────────────

    @staticmethod
    def _encode(**kwargs: str) -> str:
        return _SCHEME + urlencode({k: v for k, v in kwargs.items() if v is not None})

    @staticmethod
    def _decode(url: str) -> Dict[str, str]:
        if not url.startswith(_SCHEME):
            return {}
        parsed = parse_qs(url[len(_SCHEME):], keep_blank_values=True)
        return {k: v[0] for k, v in parsed.items()}


if __name__ == '__main__':
    try:
        from lib.ff.cmdline import DebugArgumentParser as ArgumentParser
    except ImportError:
        from argparse import ArgumentParser
    parser = ArgumentParser(description='Test DahmerMovies source provider')
    parser.add_argument('--title', required=True)
    parser.add_argument('--year', default='')
    parser.add_argument('--tmdb', default='')
    parser.add_argument('--type', default='movie', choices=['movie', 'tv'])
    parser.add_argument('--season', type=int, default=None)
    parser.add_argument('--episode', type=int, default=None)
    args = parser.parse_args()
    src = source()
    url = source._encode(type=args.type, title=args.title, year=args.year, tmdb=args.tmdb)
    if args.type == 'tv':
        url = f'{url}&s={args.season}&e={args.episode}'
    try:
        from pprint import pprint
        pprint(src.sources(url, [], []))
    except Exception as e:
        print(f'Error: {e}')
