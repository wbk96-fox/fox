"""
Shared low-level helpers used by FanFilm scrapers.

  URL builders (used by any scraper):
    build_isa_url(url, referer, origin, ua, cookie)  → isa+url|headers
    build_drmff(manifest, widevine_url, lic_referer, ua)  → DRMFF|{repr(adaptive_data)}

  Free-proxy IP rotation:
    request_with_proxy_rotation(attempt) — retry a request through rotating
      free HTTP proxies until it succeeds. Bypass for per-IP rate-limits.
    get_free_proxies(max_age) — fetch & cache a pool of free HTTP proxies.

Full embed resolvers live in ``lib.resolvers``:
    cloudnestra (vsembed.ru / vidsrc.me / vidsrc-embed.ru → cloudnestra m3u8)
    swish       (lookmovie2.skin / vidapi.xyz → JW-packer m3u8)
"""

from __future__ import annotations

import re
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from random import shuffle
from urllib.parse import urlencode

from . import cache, control, requests
from .source_utils import DEFAULT_UA
from .log_utils import fflog


# ══════════════════════════════════════════════════════════════════════════════
# URL builders
# ══════════════════════════════════════════════════════════════════════════════

def build_isa_url(url: str, referer: str, origin: str | None = None, ua: str = DEFAULT_UA, cookie: str | None = None) -> str:
    """Build isa+url|headers string for ISA/HLS playback.

    Header order: Referer, Origin, User-Agent, Cookie.
    Pass origin=None to omit it (e.g. when only Referer is needed).
    Pass ua=None to omit User-Agent.
    """
    parts = [f'Referer={referer}']
    if origin:
        parts.append(f'Origin={origin}')
    if ua:
        parts.append(f'User-Agent={ua}')
    if cookie:
        parts.append(f'Cookie={cookie}')
    return f'isa+{url}|{"&".join(parts)}'


def build_drmff(manifest: str, widevine_url: str | None = None, lic_referer: str | None = None, ua: str = DEFAULT_UA) -> str:
    """Build DRMFF|{repr(adaptive_data)} string for MPD/DASH streams.

    Used by scrapers that return Ninateka-style DASH+Widevine content.
    With widevine_url=None returns an unencrypted DASH manifest entry.
    """
    adaptive_data: dict[str, str] = {
        'protocol': 'mpd',
        'mimetype': 'application/dash+xml',
        'manifest': manifest,
        'licence_type': '',
        'licence_url': '',
        'licence_header': '',
        'post_data': '',
        'response_data': '',
    }
    if widevine_url:
        lic_headers = {'User-Agent': ua, 'Referer': lic_referer or '', 'Content-Type': ''}
        adaptive_data['licence_type'] = 'com.widevine.alpha'
        adaptive_data['licence_url'] = widevine_url
        adaptive_data['licence_header'] = urlencode(lic_headers)
        adaptive_data['post_data'] = 'R{SSM}'
    return f'DRMFF|{repr(adaptive_data)}'


# ══════════════════════════════════════════════════════════════════════════════
# Free-proxy IP rotation — shared bypass for per-IP rate-limits / quota captchas
# ══════════════════════════════════════════════════════════════════════════════

_PROXY_LIST_SOURCES = (
    ('https://api.proxyscrape.com/v4/free-proxy-list/get'
     '?request=display_proxies&protocol=http&proxy_format=ipport&format=text'),
    'https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt',
)
_PROXY_RE = re.compile(r'^\d{1,3}(?:\.\d{1,3}){3}:\d{2,5}$')


def get_free_proxies(max_age: int = 1800) -> list[str]:
    """Return a pool of free HTTP proxies as 'ip:port' strings.

    Fetched from public lists and cached (~30 min) so repeated calls within a
    session are cheap. Returns an empty list if nothing could be fetched.
    """
    row = cache.cache_get('ff_free_proxies', control.providercacheFile)
    if row:
        try:
            if row['value'] and (time.time() - row['date']) < max_age:
                return [p for p in row['value'].split(',') if p]
        except (KeyError, IndexError, TypeError):
            pass

    proxies: list[str] = []
    for url in _PROXY_LIST_SOURCES:
        try:
            resp = requests.Session().get(url, timeout=15)
            if not resp:
                continue
            proxies = [ln.strip() for ln in resp.text.splitlines() if _PROXY_RE.match(ln.strip())]
            if proxies:
                break
        except Exception:
            continue

    if proxies:
        cache.cache_insert('ff_free_proxies', ','.join(proxies), control.providercacheFile)
    else:
        fflog('get_free_proxies: could not fetch any proxy list')
    return proxies


def request_with_proxy_rotation(attempt, *, attempts: int = 40, parallel: int = 20):
    """Retry ``attempt`` through rotating free proxies until it succeeds.

    ``attempt(proxies)`` receives a dict ready for requests' ``proxies=`` kwarg
    ({'http': 'http://ip:port', 'https': 'http://ip:port'}) and must return the
    desired result on success, or ``None`` if that proxy was blocked /
    rate-limited / dead (the helper then moves on to the next one).

    Strategy: try the last known-good proxy first, then a shuffled pool of free
    proxies in parallel batches. The first non-``None`` result wins and its
    proxy is remembered for next time. Returns that result, or ``None`` if
    every attempt failed.

    This is a generic bypass for *per-IP* gates (rate-limits, quota captchas).
    It does NOT help against full-site Cloudflare challenges — those need a
    `cf_clearance` cookie instead (see `setting_cookie`).
    """
    def _run(proxy):
        try:
            return proxy, attempt({'http': f'http://{proxy}', 'https': f'http://{proxy}'})
        except Exception:
            return proxy, None

    # 1) reuse the last proxy that worked — it likely still has free quota
    last_good = cache.cache_value('ff_proxy_last_good', control.providercacheFile, default='')
    if last_good:
        _, result = _run(last_good)
        if result is not None:
            fflog(f'proxy rotation: reused last-good proxy {last_good}')
            return result

    # 2) rotate the free pool
    pool = [p for p in get_free_proxies() if p != last_good]
    if not pool:
        fflog('proxy rotation: no free proxies available')
        return None
    shuffle(pool)
    pool = pool[:attempts]

    with ThreadPoolExecutor(max_workers=parallel) as ex:
        futures = [ex.submit(_run, p) for p in pool]
        for fut in as_completed(futures):
            proxy, result = fut.result()
            if result is not None:
                for f in futures:
                    f.cancel()
                cache.cache_insert('ff_proxy_last_good', proxy, control.providercacheFile)
                fflog(f'proxy rotation: success via {proxy}')
                return result

    fflog(f'proxy rotation: all {len(pool)} proxies failed')
    return None
