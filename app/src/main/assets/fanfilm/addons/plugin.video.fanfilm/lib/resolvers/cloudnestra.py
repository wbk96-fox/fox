# -*- coding: utf-8 -*-
"""
Cloudnestra / vsembed resolver — function-based, called directly from scrapers.

Not registered in the auto-discovered ResolveUrl registry: the prorcp chain
needs more than a URL (a raw rcp hash and an entry path — 'rcp' for
vidsrc.me/vsembed.ru, 'rcpvip' for vidsrc-embed.ru), which doesn't fit the
host/media_id contract of resolveurl-style classes.

Public API:
    resolve_vsembed(embed_url)     → url|headers or None
      Flow: GET vsembed.ru/embed/... → rcp hash → prorcp hash → CDN m3u8

    resolve_rcp(rcp_hash, referer) → url|headers or None
      Use when rcp hash is already known (e.g. from vidsrc.me data-hash).

cloudnestra Turnstile-gates the rcp page per-IP; when the direct attempt is
blocked the whole chain is retried through rotating free proxies.
"""

from __future__ import annotations

import re
from typing import Dict, Optional
from urllib.parse import quote

from lib.ff import requests
from lib.ff.source_utils import FF_UA
from lib.ff.resolve_utils import request_with_proxy_rotation
from lib.ff.log_utils import fflog, fflog_exc


_CN_CDN_DOMAINS = [
    "neonhorizonworkshops.com",
    "wanderlynest.com",
    "orchidpixelgardens.com",
    "cloudnestra.com",
]


def resolve_vsembed(embed_url: str) -> Optional[str]:
    """Resolve vsembed.ru/embed/... → direct HLS m3u8 URL|headers string."""
    try:
        sess = requests.Session()
        sess.headers.update({"User-Agent": FF_UA})

        resp = sess.get(embed_url, timeout=15, headers={"Referer": "https://vsembed.ru/"})
        iframe_m = re.search(r'id="player_iframe" src="//cloudnestra\.com/rcp/([^"]+)"', resp.text)
        if not iframe_m:
            fflog(f"cloudnestra: no rcp hash in {embed_url}")
            return None
        return resolve_rcp(iframe_m.group(1), referer=embed_url, _sess=sess)

    except Exception:
        fflog_exc()
        return None


def resolve_rcp(rcp_hash: str, referer: str = "https://cloudnestra.com/",
                _sess=None, entry: str = "rcp") -> Optional[str]:
    """Resolve a cloudnestra rcp/rcpvip hash → HLS m3u8 URL|headers string.

    `entry` selects the first-stage path: "rcp" (default, used by vidsrc.me /
    vsembed.ru) or "rcpvip" (used by vidsrc-embed.ru). Both flow into the
    same prorcp → CDN chain.
    """
    result = _resolve_rcp_attempt(rcp_hash, referer, entry, _sess)
    if result is None:
        fflog("cloudnestra: direct attempt failed, rotating through free proxies")
        result = request_with_proxy_rotation(
            lambda proxies: _resolve_rcp_attempt(rcp_hash, referer, entry, None, proxies))
    return result


def _resolve_rcp_attempt(rcp_hash: str, referer: str, entry: str,
                         _sess=None, proxies: Optional[Dict[str, str]] = None) -> Optional[str]:
    """One full rcp → prorcp → CDN resolve attempt, optionally routed via a proxy."""
    try:
        if proxies:
            sess = requests.Session()
            sess.headers.update({"User-Agent": FF_UA})
            sess.proxies.update(proxies)
        else:
            sess = _sess or requests.Session()
            if not _sess:
                sess.headers.update({"User-Agent": FF_UA})

        resp2 = sess.get(
            f"https://cloudnestra.com/{entry}/{rcp_hash}", timeout=15,
            headers={"Referer": referer},
        )
        prorcp_m = re.search(r"src: '/prorcp/([^']+)'", resp2.text)
        if not prorcp_m:
            if not proxies:
                fflog(f"cloudnestra: no prorcp hash for {entry}/{rcp_hash[:20]}")
            return None
        prorcp_hash = prorcp_m.group(1)

        resp3 = sess.get(
            f"https://cloudnestra.com/prorcp/{prorcp_hash}", timeout=15,
            headers={"Referer": f"https://cloudnestra.com/{entry}/{rcp_hash}"},
        )
        content = resp3.text

        pass_m = re.search(r'pass_path = "//([^/]+)/rt_ping\.php"', content)
        tmstr_prefix = pass_m.group(1).split(".")[0] if pass_m else "tmstr5"

        path_m = re.search(r'https://[^.]+\.\{v\d+\}/pl/(H4sI[^ "]+)/master\.m3u8', content)
        if not path_m:
            if not proxies:
                fflog("cloudnestra: no m3u8 path in prorcp page")
            return None
        m3u8_path = path_m.group(1)

        for cdn in _CN_CDN_DOMAINS:
            m3u8_url = f"https://{tmstr_prefix}.{cdn}/pl/{m3u8_path}/master.m3u8"
            try:
                r = sess.get(m3u8_url, timeout=10, headers={"Referer": "https://cloudnestra.com/"})
                if r.status_code == 200 and b"#EXTM3U" in r.content[:20]:
                    fflog(f"cloudnestra: resolved via {cdn}")
                    return (
                        f"{m3u8_url}|User-Agent={quote(FF_UA)}"
                        f"&Referer=https%3A%2F%2Fcloudnestra.com%2F"
                    )
            except Exception:
                pass

        if not proxies:
            fflog("cloudnestra: all CDN domains failed")
        return None

    except Exception:
        if not proxies:
            fflog_exc()
        return None
