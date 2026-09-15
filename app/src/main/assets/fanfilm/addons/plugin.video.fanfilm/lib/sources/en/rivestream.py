# -*- coding: utf-8 -*-
"""
FanFilm - źródło: RiveStream (rivestream.app)
Copyright (C) 2026 :)

Dystrybuowane na licencji MIT <https://mit-license.org>

API: GET https://www.rivestream.app/api/backendfetch
  ?requestID={movie|tv}VideoProvider&id={tmdbId}&service={provider}
  &secretKey={...}[&season={s}&episode={e}]
-> { data: { sources: [{quality,url,source,format}], captions: [...] } }

`secretKey` to hash odtworzony z zaciemnionego JS-a strony (bez niego 403).
UWAGA przy edycji: JS `^` zwraca signed Int32, więc `.toString(16)` na
ujemnym wyniku daje string ze znakiem "-" (np. "-89907c9") - to zamierzone,
backend tego oczekuje, nie "naprawiać".

11 providerów (`service=`) odpytywanych równolegle. Pole "quality" bywa
prawdziwą rozdzielczością, rozdzielczością+językiem, albo nic nie mówiącą
nazwą mirrora ("tcloud") - dla tych ostatnich dociągamy master.m3u8
(parse_hls_variants w source_utils.py).

Usługa "drivedownload" pominięta - to Google Drive, bez wsparcia Range
(patrz notatki z sesji DahmerMovies).
"""

from __future__ import annotations

import base64
import re
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import ClassVar, Dict, List, Optional, Tuple, TYPE_CHECKING
from urllib.parse import urlencode, parse_qs

from lib.ff import requests
from lib.ff.source_utils import DEFAULT_UA, append_headers, get_quality, parse_hls_variants
from lib.ff.log_utils import fflog, fflog_exc

if TYPE_CHECKING:
    from lib.ff.item import FFItem
    from lib.sources import SourceItem, SourceTitleAlias


_BASE = 'https://www.rivestream.app'
_API = f'{_BASE}/api/backendfetch'
_SCHEME = 'rivestream://'
# Encodes (tmdb, type, service, index[, season, episode]) — resolve() re-fetches
# that exact service call fresh at play time instead of storing a short-lived
# signed URL discovered during sources().
_ITEM_SCHEME = 'rivestream_item://'

_HEADERS: Dict[str, str] = {'User-Agent': DEFAULT_UA, 'Accept': 'application/json'}
_STREAM_HEADERS: Dict[str, str] = {'User-Agent': DEFAULT_UA, 'Referer': _BASE + '/'}

# Shared across the module - reused connections instead of one per source() call.
_session = requests.Session()
_session.headers.update(_HEADERS)

_SERVICES = (
    'apex', 'pulse', 'solstice', 'quasar', 'horizon', 'primevids',
    'flowcast', 'asiacloud', 'citadel', 'hindicast', 'guru',
)

# const.sources.language_order (lib/ff/sources.py sourcesFilter) is a closed
# list: pl, mul, multi, en, de, fr, it, es, pt, ko, ru, -, ''. Anything else
# raises KeyError there and silently kills the WHOLE source list, not just
# ours - see _parse_quality_label below for how 'language' is kept inside it.

_RE_RES_LABEL = re.compile(r'(\d{3,4})p', re.IGNORECASE)

# ─── secretKey (reverse-engineered from the site's minified JS) ───────────────

_C_ARR = [
    "4Z7lUo", "gwIVSMD", "PLmz2elE2v", "Z4OFV0", "SZ6RZq6Zc", "zhJEFYxrz8", "FOm7b0",
    "axHS3q4KDq", "o9zuXQ", "4Aebt", "wgjjWwKKx", "rY4VIxqSN", "kfjbnSo", "2DyrFA1M",
    "YUixDM9B", "JQvgEj0", "mcuFx6JIek", "eoTKe26gL", "qaI9EVO1rB", "0xl33btZL",
    "1fszuAU", "a7jnHzst6P", "wQuJkX", "cBNhTJlEOf", "KNcFWhDvgT", "XipDGjST",
    "PCZJlbHoyt", "2AYnMZkqd", "HIpJh", "KH0C3iztrG", "W81hjts92", "rJhAT",
    "NON7LKoMQ", "NMdY3nsKzI", "t4En5v", "Qq5cOQ9H", "Y9nwrp", "VX5FYVfsf",
    "cE5SJG", "x1vj1", "HegbLe", "zJ3nmt4OA", "gt7rxW57dq", "clIE9b", "jyJ9g",
    "B5jXjMCSx", "cOzZBZTV", "FTXGy", "Dfh1q1", "ny9jqZ2POI", "X2NnMn", "MBtoyD",
    "qz4Ilys7wB", "68lbOMye", "3YUJnmxp", "1fv5Imona", "PlfvvXD7mA", "ZarKfHCaPR",
    "owORnX", "dQP1YU", "dVdkx", "qgiK0E", "cx9wQ", "5F9bGa", "7UjkKrp", "Yvhrj",
    "wYXez5Dg3", "pG4GMU", "MwMAu", "rFRD5wlM",
]
_MASK32 = 0xFFFFFFFF


def _u32(x: int) -> int:
    return x & _MASK32


def _shl(x: int, n: int) -> int:
    return _u32(x << (n & 31))


def _shr(x: int, n: int) -> int:
    n &= 31
    return (_u32(x) >> n) if n else _u32(x)


def _js_hex(v: int) -> str:
    """JS `n.toString(16)`: negative Int32 -> '-' + hex(abs(n)), not two's complement."""
    v &= _MASK32
    if v >= 0x80000000:
        return '-' + format(0x100000000 - v, 'x')
    return format(v, 'x')


def _pad8(s: str) -> str:
    """JS `.padStart(8,'0')`: prepend zeros to the raw string, oblivious to any
    leading '-' (e.g. "-4a8343".padStart(8,"0") == "0-4a8343", NOT "-04a8343" —
    `str.zfill` is minus-aware and gives the wrong answer here, so plain
    left-padding is used instead)."""
    return s if len(s) >= 8 else '0' * (8 - len(s)) + s


def _hash_a(s: str) -> str:
    t = 0
    for n, ch in enumerate(s):
        r = ord(ch)
        sdbm = _u32(r + _shl(t, 6) + _shl(t, 16) - t)
        t = sdbm
        rot = n % 5
        i_val = _u32(_shl(t, rot) | _shr(t, 32 - rot if rot else 32))
        rrot = n % 7
        r_mix = _u32(_shl(r, rrot) | _shr(r, 8 - rrot))
        t = _u32(t ^ _u32(i_val ^ r_mix))
        t = _u32(t + _u32(_shr(t, 11) ^ _shl(t, 3)))
    t = _u32(t ^ _shr(t, 15))
    t = _u32((0xFFFF & t) * 49842 + _shl((_shr(t, 16) * 49842) & 0xFFFF, 16))
    t = _u32(t ^ _shr(t, 13))
    t = _u32((0xFFFF & t) * 40503 + _shl((_shr(t, 16) * 40503) & 0xFFFF, 16))
    t = _u32(t ^ _shr(t, 16))
    return _pad8(_js_hex(t))


def _hash_b(s: str) -> str:
    n = _u32(3735928559 ^ len(s))
    for e, ch in enumerate(s):
        r = ord(ch)
        r = _u32(r ^ (_u32(131 * e + 89) ^ _shl(r, e % 5)) & 255)
        n = _u32(_u32(_shl(n, 7) | _shr(n, 25)) ^ r)
        i_ = (0xFFFF & n) * 60205
        o_ = _shl((_shr(n, 16) * 60205) & _MASK32, 16)
        n = _u32(i_ + o_)
        n = _u32(n ^ _shr(n, 11))
    n = _u32(n ^ _shr(n, 15))
    n = _u32((0xFFFF & n) * 49842 + _shl((_shr(n, 16) * 49842) & _MASK32, 16))
    n = _u32(n ^ _shr(n, 13))
    n = _u32((0xFFFF & n) * 40503 + _shl((_shr(n, 16) * 40503) & _MASK32, 16))
    n = _u32(n ^ _shr(n, 16))
    n = _u32((0xFFFF & n) * 10196 + _shl((_shr(n, 16) * 10196) & _MASK32, 16))
    n = _u32(n ^ _shr(n, 15))
    return _pad8(_js_hex(n))


def _compute_secret(value: Optional[str]) -> str:
    if value is None:
        return 'rive'
    r = str(value)
    try:
        num = float(r)
        idx = int(num) % len(_C_ARR)
        mix_char = _C_ARR[idx]
        pos = int(num % len(r)) // 2
    except ValueError:
        total = sum(ord(c) for c in r)
        mix_char = _C_ARR[total % len(_C_ARR)]
        pos = (total % len(r)) // 2
    mixed = r[:pos] + mix_char + r[pos:]
    h1 = _hash_a(mixed)
    h2 = _hash_b(h1)
    return base64.b64encode(h2.encode()).decode()


# ─── source ──────────────────────────────────────────────────────────────────────

class source:
    priority: ClassVar[int] = 1
    language: ClassVar[List[str]] = ['en']

    ffitem: 'FFItem'

    # ── public api ─────────────────────────────────────────────────────────

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
        tmdb = params.get('tmdb')
        if not tmdb:
            return []
        media_type = params.get('type', 'movie')
        request_id = f'{media_type}VideoProvider'

        extra: Dict[str, str] = {}
        if media_type == 'tv':
            extra['season'] = params.get('s', '1')
            extra['episode'] = params.get('e', '1')

        secret = _compute_secret(tmdb)

        raw_items: List[Tuple[dict, str, str, int]] = []  # (raw dict, provider name, service, index)
        with ThreadPoolExecutor(max_workers=len(_SERVICES)) as executor:
            futures = [
                executor.submit(self._fetch_service, request_id, tmdb, service, secret, extra)
                for service in _SERVICES
            ]
            for future in as_completed(futures, timeout=25):
                try:
                    for item in future.result():
                        raw_items.append(item)
                except Exception:
                    fflog_exc()

        fflog(f'rivestream: {len(raw_items)} raw sources from {len(_SERVICES)} services')

        # Second pass: resolve real resolutions for ambiguous quality labels
        # (e.g. "HLS", "tcloud") by reading the actual master.m3u8 playlist.
        # This is discovery-only (for the label shown in the list) — the token
        # in these URLs is short-lived, so it is never stored; resolve() below
        # re-fetches the same (service, index) fresh right before playback.
        result: List[dict] = []
        need_probe = [item for item in raw_items if not _RE_RES_LABEL.search(item[0].get('quality', ''))]
        probed: Dict[str, List[dict]] = {}
        if need_probe:
            with ThreadPoolExecutor(max_workers=min(8, len(need_probe))) as executor:
                futures = {
                    executor.submit(self._probe_master, raw['url']): raw['url']
                    for raw, *_rest in need_probe
                }
                for future in as_completed(futures, timeout=20):
                    stream_url = futures[future]
                    try:
                        probed[stream_url] = future.result()
                    except Exception:
                        probed[stream_url] = []

        for raw, provider, service, index in raw_items:
            stream_url = raw['url']
            quality, language, info = _parse_quality_label(raw.get('quality', ''))
            item_url = _ITEM_SCHEME + urlencode({
                'tmdb': tmdb, 'type': media_type, 'service': service, 'index': index,
                **extra,
            })
            variants = probed.get(stream_url)
            if variants:
                # Multiple resolutions from one raw source all lazily re-resolve to
                # the SAME (service, index) — resolve() hands the fresh master
                # playlist to inputstream.adaptive, which picks/switches quality
                # itself, so which specific height the user clicked doesn't matter.
                for width, height, _variant_url in variants:
                    result.append(_make_item(provider, f'{height}p', language, info, item_url))
                continue
            result.append(_make_item(provider, quality, language, info, item_url))

        fflog(f'rivestream: {len(result)} playable sources')
        return result

    def resolve(self, url: str) -> Optional[str]:
        if not url.startswith(_ITEM_SCHEME):
            return None
        params = _decode(url, scheme=_ITEM_SCHEME)
        tmdb, service = params.get('tmdb'), params.get('service')
        try:
            index = int(params.get('index', ''))
        except ValueError:
            return None
        if not tmdb or not service:
            return None
        media_type = params.get('type', 'movie')
        extra: Dict[str, str] = {}
        if media_type == 'tv':
            extra['season'] = params.get('s', '1')
            extra['episode'] = params.get('e', '1')

        items = self._fetch_service(f'{media_type}VideoProvider', tmdb, service, _compute_secret(tmdb), extra)
        if index >= len(items):
            fflog(f'rivestream: resolve - index {index} out of range for {service!r} (link expired?)')
            return None
        stream_url = items[index][0].get('url')
        if not stream_url:
            return None
        final = stream_url + append_headers(_STREAM_HEADERS)
        return f'isa+{final}' if '.m3u8' in stream_url else final

    # ── helpers ────────────────────────────────────────────────────────────

    @staticmethod
    def _fetch_service(request_id: str, tmdb: str, service: str, secret: str,
                       extra: Dict[str, str]) -> List[Tuple[dict, str, str, int]]:
        try:
            params = {'requestID': request_id, 'id': tmdb, 'service': service,
                      'secretKey': secret, **extra}
            resp = _session.get(_API, params=params, timeout=15)
            if not resp.ok:
                return []
            data = resp.json()
            sources = ((data or {}).get('data') or {}).get('sources') or []
            provider = (sources[0].get('source') if sources else None) or service.title()
            return [(s, provider, service, i) for i, s in enumerate(sources) if s.get('url')]
        except Exception:
            fflog(f'rivestream: service {service!r} failed, continuing')
            return []

    @staticmethod
    def _probe_master(master_url: str) -> List[Tuple[int, int, str]]:
        """Fetch a master.m3u8 and return [(width, height, variant_url), ...]."""
        try:
            resp = _session.get(master_url, headers=_STREAM_HEADERS, timeout=8)
            if not resp.ok:
                return []
            return parse_hls_variants(resp.text, master_url)
        except Exception:
            return []


_NON_EN_LANGS = {
    'hindi', 'tamil', 'telugu', 'malayalam', 'kannada', 'bengali',
    'punjabi', 'marathi', 'gujarati', 'japanese',
}


def _parse_quality_label(label: str) -> Tuple[str, str, str]:
    """Map a provider's free-form quality/mirror label to (quality, language, info).

    `language` is constrained to what lib/ff/sources.py's closed
    `language_order` supports - only 'en'/'multi' are ever produced here (see
    module docstring for why anything else crashes the whole search).

    "{res}p | {Language}" labels (citadel) carry a real, trustworthy language
    name - trust it, and keep it in 'info' too (Hindi/Tamil/Telugu all share
    'multi' as their language bucket, so 'info' is the only thing that tells
    those apart in the source list). A bare "Multi-Audio"/"HLS"/mirror-
    codename label carries NO real evidence of which language is inside (a
    "Multi-Audio" solstice stream turned out to be German audio, not English,
    in testing) - so it is NOT guessed at; 'info' is left empty rather than
    surfacing a meaningless codename ("tcloud", "HLS") as if it were useful
    metadata.
    """
    parts = [p.strip() for p in label.split('|')]
    quality = get_quality(parts[0]) if _RE_RES_LABEL.search(parts[0]) else 'HD'
    has_lang_part = len(parts) > 1
    lang_part = (parts[1] if has_lang_part else parts[0]).strip().lower()

    if 'multi' in lang_part:
        return quality, 'multi', ''  # bundled multi-track, actual languages unknown
    if lang_part == 'polish':
        return quality, 'pl', parts[1] if has_lang_part else ''
    if lang_part == 'english':
        return quality, 'en', ''
    if lang_part in _NON_EN_LANGS:
        return quality, 'multi', parts[1] if has_lang_part else ''
    return quality, 'en', ''  # unrecognised mirror codename - no language signal either way


def _make_item(provider: str, quality: str, language: str, info: str,
               stream_url: str) -> dict:
    return {
        'source': provider,
        'quality': quality,
        'language': language,
        'info': info,
        'url': stream_url,
        'direct': True,
        'debridonly': False,
    }


def _decode(url: str, scheme: str = _SCHEME) -> Dict[str, str]:
    if not url.startswith(scheme):
        return {}
    parsed = parse_qs(url[len(scheme):], keep_blank_values=True)
    return {k: v[0] for k, v in parsed.items()}


if __name__ == '__main__':
    try:
        from lib.ff.cmdline import DebugArgumentParser as ArgumentParser
    except ImportError:
        from argparse import ArgumentParser
    parser = ArgumentParser(description='Test RiveStream source provider')
    parser.add_argument('tmdb_id', help='TMDB ID')
    parser.add_argument('--type', default='movie', choices=['movie', 'tv'])
    parser.add_argument('--season', type=int, default=1)
    parser.add_argument('--episode', type=int, default=1)
    args = parser.parse_args()

    src = source()
    url = _SCHEME + urlencode({'type': args.type, 'tmdb': args.tmdb_id})
    if args.type == 'tv':
        url = f'{url}&s={args.season}&e={args.episode}'

    from pprint import pprint
    pprint(src.sources(url, [], []))
