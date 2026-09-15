# -*- coding: utf-8 -*-
"""
FanFilm's custom UPN Player resolver.

"UPN Player" is a white-labelled embed used across many rotating mirror
domains (e.g. mioro.upns.pro, tokyosubs.rpmhub.site — no shared registrable
suffix), so it can't be matched by a fixed domain list the way most resolvers
are. Every deployment shares the same backend API and the same fixed AES key,
so any host works as long as the URL has the player's `#<id>` shape.

Ported from skoruppa/plugin.video.docchipl (resources/lib/players/upn.py).
"""

from __future__ import annotations

import re
from urllib.parse import urljoin

from resolveurl.lib import helpers
from resolveurl.lib.pyaes.aes import AESModeOfOperationCBC
from resolveurl.lib.pyaes.util import strip_PKCS7_padding
from resolveurl.resolver import ResolveUrl, ResolverError

from lib.ff.source_utils import FF_UA

_DECRYPTION_KEY = bytes.fromhex('6b69656d7469656e6d75613931316361')


def _decrypt(encrypted_hex: str) -> str:
    payload = bytes.fromhex(encrypted_hex.strip())
    iv, ciphertext = payload[:16], payload[16:]
    cbc = AESModeOfOperationCBC(_DECRYPTION_KEY, iv)
    plaintext = b''.join(cbc.decrypt(ciphertext[i:i + 16]) for i in range(0, len(ciphertext), 16))
    return strip_PKCS7_padding(plaintext).decode('utf-8', errors='ignore')


class UpnResolver(ResolveUrl):
    name = 'UPN'
    domains = ['*']
    # Any host — the player id is what identifies it, not the domain (see module docstring).
    pattern = r'(?://)([^/#]+)/?#([a-zA-Z0-9]+)$'

    def get_media_url(self, host, media_id):
        web_url = self.get_url(host, media_id)
        headers = {'User-Agent': FF_UA, 'Referer': web_url}
        api_url = urljoin(web_url, f'/api/v1/video?id={media_id}&w=1920&h=1200&r=')

        resp = self.net.http_GET(api_url, headers=headers)
        if not resp or not resp.content:
            raise ResolverError('UPN: empty API response')

        try:
            decrypted = _decrypt(resp.content)
        except Exception:
            raise ResolverError('UPN: decrypt failed')

        source_match = re.search(r'"source"\s*:\s*"([^"]+)"', decrypted)
        if not source_match:
            raise ResolverError('UPN: no source in decrypted payload')

        stream_url = source_match.group(1).replace('\\/', '/')
        return stream_url + helpers.append_headers(headers)

    def get_url(self, host, media_id):
        return self._default_get_url(host, media_id, template='https://{host}/#{media_id}')
