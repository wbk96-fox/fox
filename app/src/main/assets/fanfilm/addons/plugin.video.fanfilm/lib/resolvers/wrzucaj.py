"""
FanFilm's custom Wrzucaj resolver.

Resolves public ``wrzucaj.pl/embed/<id>/v`` pages (the ones ekino's
``play.ekino.link`` player iframes point at) to a direct CDN ``.mp4`` URL.
Shares the embed scrape with the ekino scraper via
``lib.ff.source_utils.wrzucaj_embed_sources`` — see ``lib/sources/pl/wrzucaj.py``
``WrzucajVideo._expand`` for the original (logged-in) source of the same markup.
"""

from resolveurl.lib import helpers
from resolveurl.resolver import ResolveUrl, ResolverError

from lib.ff.source_utils import FF_UA, wrzucaj_embed_sources


class WrzucajResolver(ResolveUrl):
    name = 'Wrzucaj'
    domains = ['wrzucaj.pl']
    pattern = r'(?://|\.)(wrzucaj\.pl)/embed/([0-9]+)'

    def get_media_url(self, host, media_id):
        web_url = self.get_url(host, media_id)
        variants = wrzucaj_embed_sources(web_url, ua=FF_UA)
        if variants:
            headers = {'User-Agent': FF_UA, 'Referer': 'https://wrzucaj.pl/'}
            return variants[0]['url'] + helpers.append_headers(headers)

        raise ResolverError('File Not Found or Removed')

    def get_url(self, host, media_id):
        return self._default_get_url(host, media_id, template='https://{host}/embed/{media_id}/v')
