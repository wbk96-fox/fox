# -*- coding: utf-8 -*-
"""
FanFilm - source: CineSU (cine.su)

Dystrybuowane na licencji MIT <https://mit-license.org>

cine.su is a Next.js SPA that plays everything off one CDN ("glendale"
backend). The player builds the master-playlist URL entirely client-side
from the TMDB id - no search, no per-title API call, no signed/expiring
token:

    https://glendale-plumbing.com/c/v1/<token>/master.m3u8

`<token>` is a deterministic keyed scramble of "m:<tmdb>:0:0" (movies) or
"s:<tmdb>:<season>:<episode>" (episodes), reproduced in _token() below
straight from the site's minified player chunk. The CDN enforces
Referer: https://cine.su/ (any other/absent Referer -> 403), nothing else.

The /c/v1/ endpoint sources on demand: available titles answer in <0.5s,
unavailable ones hang until the request times out (no fast 404) - hence
the short timeout in sources().

If the CDN host or key ever rotates, re-extract the constants from the
player chunk (search the loaded _next/static/chunks/*.js for "/c/v1/").
"""

from __future__ import annotations

from typing import ClassVar, List, Optional, TYPE_CHECKING
from urllib.parse import urlencode, parse_qs

from lib.ff import requests
from lib.ff.source_utils import DEFAULT_UA, parse_hls_variants, quality_from_resolution
from lib.ff.resolve_utils import build_isa_url
from lib.ff.log_utils import fflog, fflog_exc

if TYPE_CHECKING:
    from lib.ff.item import FFItem
    from lib.sources import SourceItem, SourceTitleAlias

_BASE = 'https://cine.su'
_CDN = 'https://glendale-plumbing.com'
_SCHEME = 'cinesu://'

_HEADERS = {'User-Agent': DEFAULT_UA, 'Referer': _BASE + '/', 'Origin': _BASE}

_session = requests.Session()
_session.headers.update(_HEADERS)

# ─── token (reverse-engineered from the site's minified player chunk) ─────────

_L3 = [17, 91, 203, 44, 8, 177, 62, 239, 119, 3, 154, 81, 28, 210, 101, 7]
_KEY_HEX = '224eff10e662e9635c9f671cf46351dcd69af42b1edd56f5e5fa21751f44b9c8'
_PREFIX = '4860ac8bfddb'
_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'
_M = 0xFFFFFFFF


def _oe(value: int) -> int:
    t = value & _M
    t ^= t >> 16
    t = (t * 0x7FEB352D) & _M
    t ^= t >> 15
    t = (t * 0x846CA68B) & _M
    t ^= t >> 16
    return t & _M


def _keystream(length: int) -> bytes:
    key = _KEY_HEX.encode()
    out = bytearray(max(32, min(128, length + 17)))
    s = 0x811C9DC5
    for i in range(len(out)):
        s ^= key[i % len(key)]
        s = _oe((s + _L3[i % len(_L3)] + 0x9E3779B1 * i) & _M)
        out[i] = s & 0xFF
    return bytes(out)


def _b64url(data: bytes) -> str:
    out = []
    for i in range(0, len(data), 3):
        chunk = data[i:i + 3]
        b0 = chunk[0]
        b1 = chunk[1] if len(chunk) > 1 else None
        b2 = chunk[2] if len(chunk) > 2 else None
        out.append(_ALPHABET[b0 >> 2])
        out.append(_ALPHABET[((3 & b0) << 4) | ((b1 or 0) >> 4)])
        if b1 is None:
            break
        out.append(_ALPHABET[((15 & b1) << 2) | ((b2 or 0) >> 6)])
        if b2 is None:
            break
        out.append(_ALPHABET[63 & b2])
    return ''.join(out)


def _token(kind: str, tmdb: int, season: int = 0, episode: int = 0) -> str:
    """kind: 'movie' or 'show'. Episode fields are ignored for movies."""
    is_show = kind == 'show'
    s = max(1, season) if is_show else 0
    e = max(1, episode) if is_show else 0
    payload = f'{_PREFIX}:{kind[0]}:{int(tmdb)}:{s}:{e}'.encode()
    ks = _keystream(len(payload))
    out = bytearray(len(payload) + 2)
    out[0] = len(payload) & 0xFF
    out[1] = (len(payload) >> 8) & 0xFF
    c = (0x9E3779B9 ^ len(payload)) & _M
    for i in range(len(payload)):
        c = _oe((c + ks[i % len(ks)] + _L3[i % len(_L3)] + i) & _M)
        out[i + 2] = payload[i] ^ (c & 0xFF) ^ ks[(7 * i + 3) % len(ks)]
    return _b64url(bytes(out))


def _master_url(kind: str, tmdb: int, season: int = 0, episode: int = 0) -> str:
    return f'{_CDN}/c/v1/{_token(kind, tmdb, season, episode)}/master.m3u8'


def _decode(url: str) -> dict:
    parsed = parse_qs(url[len(_SCHEME):], keep_blank_values=True)
    return {k: v[0] for k, v in parsed.items()}


# ─── source ─────────────────────────────────────────────────────────────────────

class source:
    ffitem: 'FFItem'

    priority: ClassVar[int] = 1
    language: ClassVar[List[str]] = ['en']

    # ── public api ─────────────────────────────────────────────────────────

    def movie(self, imdb: str, title: str, localtitle: str,
              aliases: 'list[SourceTitleAlias]', year: str) -> Optional[str]:
        tmdb = str(self.ffitem.tmdb_id or '')
        return _SCHEME + urlencode({'kind': 'movie', 'tmdb': tmdb}) if tmdb else None

    def tvshow(self, imdb: str, tvdb: str, tvshowtitle: str, localtvshowtitle: str,
               aliases: 'list[SourceTitleAlias]', year: str) -> Optional[str]:
        show_item = getattr(self.ffitem, 'show_item', None)
        tmdb = str((show_item.tmdb_id if show_item else None) or self.ffitem.tmdb_id or '')
        return _SCHEME + urlencode({'kind': 'show', 'tmdb': tmdb}) if tmdb else None

    def episode(self, url: Optional[str], imdb: str, tvdb: str, title: str,
                premiered: str, season: str, episode: str) -> Optional[str]:
        if not url:
            return None
        return f'{url}&{urlencode({"s": int(season), "e": int(episode)})}'

    def sources(self, url: Optional[str], hostDict: List[str],
                hostprDict: List[str]) -> 'List[SourceItem]':
        results: List[SourceItem] = []
        if not url or not url.startswith(_SCHEME):
            return results
        try:
            params = _decode(url)
            tmdb = params.get('tmdb')
            if not tmdb:
                return results
            kind = params.get('kind', 'movie')
            season = int(params.get('s', 0) or 0)
            episode = int(params.get('e', 0) or 0)
            if kind == 'show' and not (season and episode):
                return results

            master = _master_url(kind, int(tmdb), season, episode)
            resp = _session.get(master, timeout=10)
            if not resp.ok or '#EXTM3U' not in resp.text:
                return results

            heights = {h for _w, h, _u in parse_hls_variants(resp.text, master)}
            widths = {w for w, _h, _u in parse_hls_variants(resp.text, master)}
            qualities = {quality_from_resolution(width=w) for w in widths} or {'SD'}

            locator = f'{_SCHEME}{master}'
            for quality in ('4K', '1080p', '720p', 'SD'):
                if quality in qualities:
                    results.append({
                        'source': 'CineSU',
                        'quality': quality,
                        'language': 'en',
                        'url': locator,
                        'info': '',
                        'direct': True,
                        'debridonly': False,
                    })
            fflog(f'cinesu: {len(results)} source(s) for {kind} tmdb={tmdb} '
                  f'heights={sorted(heights)}')
        except Exception:
            fflog_exc()
        return results

    def resolve(self, url: str) -> Optional[str]:
        if not url or not url.startswith(_SCHEME):
            return None
        master = url[len(_SCHEME):]
        # Deterministic URL - hand the master straight to ISA, it pulls the
        # variant playlist, audio, embedded subs and segments off the CDN
        # itself (all need the same Referer).
        return build_isa_url(master, _BASE + '/', ua=DEFAULT_UA)
