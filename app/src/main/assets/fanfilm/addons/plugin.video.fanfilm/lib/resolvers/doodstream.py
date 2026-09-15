# -*- coding: utf-8 -*-
from __future__ import annotations

import re
import random
import string
import time
from urllib.parse import urljoin, quote_plus

from resolveurl.resolver import ResolveUrl, ResolverError

from lib.ff import requests
from lib.ff.log_utils import fflog

_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36'

_PRIMARY_HOSTS = ('doodstream.com', 'myvidplay.com', 'playmogo.com')
_FALLBACK_HOST = 'playmogo.com'


def _headers_str(headers: dict) -> str:
    return '|' + '&'.join(f'{k}={quote_plus(v)}' for k, v in headers.items())


class DoodStreamResolver(ResolveUrl):
    name = 'DoodStream'
    domains = [
        'dood.watch', 'doodstream.com', 'dood.to', 'dood.so', 'dood.cx', 'dood.la', 'dood.ws',
        'dood.sh', 'doodstream.co', 'dood.pm', 'dood.wf', 'dood.re', 'dood.yt', 'dooood.com',
        'dood.stream', 'ds2play.com', 'doods.pro', 'ds2video.com', 'd0o0d.com', 'do0od.com',
        'd0000d.com', 'd000d.com', 'dood.li', 'dood.work', 'dooodster.com', 'vidply.com',
        'all3do.com', 'do7go.com', 'doodcdn.io', 'doply.net', 'vide0.net', 'vvide0.com',
        'd-s.io', 'dsvplay.com', 'myvidplay.com', 'playmogo.com',
    ]
    pattern = (
        r'(?://|\.)((?:do*0*o*0*ds?(?:tream|ter|cdn)?|ds[2v](?:play|video)|(?:my)?v*id(?:pla?y|e0)|all3do|'
        r'd-s|do(?:7go|ply)|playmogo)\.'
        r'(?:[cit]om?|watch|s[ho]|cx|l[ai]|w[sf]|pm|re|yt|stream|pro|work|net))/(?:d|e)/([0-9a-zA-Z]+)'
    )

    def get_media_url(self, host: str, media_id: str) -> str:
        if host not in _PRIMARY_HOSTS:
            host = _FALLBACK_HOST

        web_url = f'https://{host}/d/{media_id}'
        headers = {'User-Agent': _UA, 'Referer': f'https://{host}/'}

        session = requests.Session()
        session.verify = False
        resp = session.get(web_url, headers=headers)
        if resp.status_code != 200:
            raise ResolverError(f'HTTP {resp.status_code} fetching {web_url}')

        # Follow redirect if any (dood mirrors redirect to each other)
        if resp.url != web_url:
            redirected_host = re.search(r'https?://([^/]+)', resp.url)
            if redirected_host:
                host = redirected_host.group(1)
                web_url = f'https://{host}/d/{media_id}'
        headers['Referer'] = web_url

        html = resp.text

        # Some entries use an iframe embed
        iframe_match = re.search(r'<iframe\s[^>]*src="([^"]+)', html)
        if iframe_match:
            embed_url = urljoin(web_url, iframe_match.group(1))
            html = session.get(embed_url, headers=headers).text
        else:
            embed_url = web_url.replace('/d/', '/e/')
            html = session.get(embed_url, headers=headers).text

        match = re.search(
            r'''dsplayer\.hotkeys[^']+'([^']+).+?function\s*makePlay.+?return[^?]+([^"]+)''',
            html, re.DOTALL,
        )
        if not match:
            raise ResolverError('doodstream: player pattern not found')

        token = match.group(2)
        pass2_url = urljoin(web_url, match.group(1))

        cdn_resp = session.get(pass2_url, headers=headers)
        cdn_base = cdn_resp.text.strip()
        fflog(f'[doodstream] cdn_base={cdn_base[:60]!r}')

        if 'cloudflarestorage.' in cdn_base:
            stream_url = cdn_base
        else:
            random_suffix = ''.join(random.choice(string.ascii_letters + string.digits) for _ in range(10))
            stream_url = cdn_base + random_suffix + token + str(int(time.time() * 1000))

        return stream_url + _headers_str(headers)

    def get_url(self, host: str, media_id: str) -> str:
        return f'https://{host}/d/{media_id}'
