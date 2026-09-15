# -*- coding: utf-8 -*-
"""
FanFilm - wspólny resolver: 111477.xyz "Mint" proxy (p.111477.xyz/bulk)
Copyright (C) 2026 :)

Dystrybuowane na licencji MIT <https://mit-license.org>

a.111477.xyz nie serwuje już plików bezpośrednio - każdy link przekierowuje
na p.111477.xyz/bulk, oficjalne narzędzie właściciela strony ("Mint proxy
links", https://p.111477.xyz/), chronione Cloudflare Turnstile. Wymaga
ważnego ciasteczka cf_clearance, synchronizowanego z przeglądarki przez
userscript FanFilm (const.tune.service.web_server.cookies - ten sam
mechanizm co cda-hd.cc/filmyonline.cc/zaluknij.cc). Użytkownik musi od
czasu do czasu odwiedzić https://p.111477.xyz/ w tej przeglądarce -
cf_clearance wygasa.

POST https://p.111477.xyz/bulk  body: paths=<raw a.111477.xyz URL>
-> czysty URL *.workers.dev/d/... - bez ciasteczek, z pełnym Range
(zweryfikowane: 206 Partial Content). Ważny 24h / do 50 użyć, stąd mint()
wołane leniwie w resolve(), nigdy podczas sources().

Limit strony: 10 linków/minutę/IP - licznik poniżej dzielony między
wszystkimi wołającymi w procesie.
"""

from __future__ import annotations

import threading
import time
from collections import deque
from typing import Optional

from lib.ff import control, requests
from lib.ff.settings import settings
from lib.ff.source_utils import DEFAULT_UA, setting_cookie
from lib.ff.log_utils import fflog, fflog_exc

_MINT_URL = 'https://p.111477.xyz/bulk'
_COOKIE_SETTING = 'dahmer111477.cookies_cf'
_UA_SETTING = 'dahmer111477.user_agent'

_RATE_LIMIT_CALLS = 10
_RATE_LIMIT_WINDOW = 60.0  # seconds

# Kodi runs the addon as a fresh process per navigation, so an in-memory
# cache is useless here - use a Window(10000) property instead, which lives
# in the running Kodi GUI process and survives across script invocations.
_EXPIRED_PROP = 'FanFilm.dahmer111477.cf_expired'
_EXPIRED_TTL = 300.0  # seconds - stop trusting a stale "expired" flag after this

_session = requests.Session()
_call_times: deque = deque(maxlen=_RATE_LIMIT_CALLS)
_rate_lock = threading.Lock()


def _mark_expired(expired: bool) -> None:
    win = control.window()
    if expired:
        win.setProperty(_EXPIRED_PROP, str(time.time()))
    else:
        win.clearProperty(_EXPIRED_PROP)


def known_expired() -> bool:
    """No-network check: did the last real mint() call fail with a cf_clearance
    (HTTP 403) error? Cleared as soon as mint() succeeds again. Deliberately
    NOT a fresh probe against the site - p.111477.xyz's own rate limiting is
    touchy enough that spending an extra request just to check would eat into
    the budget real mint() calls need."""
    win = control.window()
    ts = win.getProperty(_EXPIRED_PROP)
    if not ts:
        return False
    try:
        if time.time() - float(ts) > _EXPIRED_TTL:
            win.clearProperty(_EXPIRED_PROP)
            return False
    except ValueError:
        win.clearProperty(_EXPIRED_PROP)
        return False
    return True


def unusable() -> bool:
    """No-network check for sources(): true if there's no cf_clearance cookie
    synced at all, or the last real mint() attempt proved it's dead."""
    cf_cookie = setting_cookie(setting_name=_COOKIE_SETTING, cookie_name='cf_clearance')
    return not cf_cookie or known_expired()


def _throttle() -> None:
    """Block until issuing another mint call stays under 10/minute."""
    with _rate_lock:
        now = time.monotonic()
        if len(_call_times) == _RATE_LIMIT_CALLS:
            elapsed = now - _call_times[0]
            wait = _RATE_LIMIT_WINDOW - elapsed
            if wait > 0:
                fflog(f'111477 mint: rate limit, waiting {wait:.1f}s')
                time.sleep(wait)
        _call_times.append(time.monotonic())


def mint(raw_url: str) -> Optional[str]:
    """POST a raw a.111477.xyz file URL to the site's own "Mint" tool and
    return the resulting clean, Range-capable direct URL - or None if no
    valid cf_clearance cookie is synced yet (or it expired)."""
    cf_cookie = setting_cookie(setting_name=_COOKIE_SETTING, cookie_name='cf_clearance')
    if not cf_cookie:
        fflog('111477 mint: no cf_clearance cookie synced yet - visit https://p.111477.xyz/ '
              'in a browser with the FanFilm userscript installed')
        return None

    ua = settings.getString(_UA_SETTING).strip(' "\'') or DEFAULT_UA

    _throttle()
    try:
        resp = _session.post(
            _MINT_URL,
            data={'paths': raw_url},
            cookies={'cf_clearance': cf_cookie},
            headers={'User-Agent': ua, 'Referer': 'https://p.111477.xyz/'},
            timeout=20,
        )
    except Exception:
        fflog_exc()
        return None

    if resp.status_code == 429:
        fflog('111477 mint: HTTP 429 (server-side rate limit despite local throttling)')
        return None
    if not resp.ok:
        fflog(f'111477 mint: HTTP {resp.status_code} (cf_clearance likely expired)')
        _mark_expired(True)
        return None

    text = resp.text.strip()
    if not text.startswith('http'):
        fflog(f'111477 mint: unexpected response: {text[:200]!r}')
        return None
    # One path in -> one URL out, but be defensive about trailing content.
    _mark_expired(False)
    return text.splitlines()[0].strip()
