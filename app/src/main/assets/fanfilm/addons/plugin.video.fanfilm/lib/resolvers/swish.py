# -*- coding: utf-8 -*-
"""
Swish (lookmovie2.skin / vidapi.xyz) resolver — function-based.

Used by 2embed.cc and onlyflix.to: the scraper has a swish player ID and
calls ``resolve_swish(id)`` directly. Not registered in the ResolveUrl
registry because the input is a bare ID, not a URL.

Public API:
    resolve_swish(swish_id, referer) → isa+url|headers or None
      Fetches lookmovie2.skin/e/{id}, unpacks JW packer, extracts hls3/hls2.
"""

from __future__ import annotations

import re
from typing import Optional

from lib.ff import requests
from lib.ff.source_utils import FF_UA
from lib.ff.resolve_utils import build_isa_url
from lib.ff.log_utils import fflog, fflog_exc


def resolve_swish(swish_id: str, referer: str = "https://stream.vidapi.xyz/") -> Optional[str]:
    """Resolve a swish player ID → ISA HLS URL."""
    try:
        sess = requests.Session()
        sess.headers.update({"User-Agent": FF_UA, "Referer": referer})
        embed_url = f"https://lookmovie2.skin/e/{swish_id}"
        resp = sess.get(embed_url, timeout=15)
        m3u8 = _lm2_extract_m3u8(resp.text)
        if not m3u8:
            fflog(f"swish: no m3u8 for {swish_id}")
            return None
        return build_isa_url(m3u8, "https://lookmovie2.skin/", ua=FF_UA)
    except Exception:
        fflog_exc()
        return None


def _lm2_extract_m3u8(html: str) -> Optional[str]:
    """Decode JW packer from lookmovie2.skin page and return best stream URL.

    hls3 is preferred: real MPEG-TS segments (served as .woff2 but video/MP2T).
    hls4 is skipped: its segments are anti-leech fake PNGs for non-browser clients.
    hls2 is fallback: external CDN, often 403 without browser session.
    """
    m = re.search(
        r"eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',\d+,\d+,'(.*?)'\.split\('\|'\)\)\)",
        html, re.S
    )
    if not m:
        return None
    packed, keys_str = m.group(1), m.group(2)
    k = keys_str.split('|')

    def decode_token(mo):
        tok = mo.group(0)
        idx = int(tok, 36) if tok else 0
        return k[idx] if idx < len(k) and k[idx] else tok

    decoded = re.sub(r'\b[0-9a-z]+\b', decode_token, packed)

    hls3 = re.search(r'"hls3"\s*:\s*"(https?://[^"]+)"', decoded)
    if hls3:
        return hls3.group(1)
    hls2 = re.search(r'"hls2"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"', decoded)
    if hls2:
        return hls2.group(1)
    return None
