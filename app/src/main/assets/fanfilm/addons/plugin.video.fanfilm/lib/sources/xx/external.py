# -*- coding: utf-8 -*-
"""
FanFilm ‑ źródło: external based on JustWatch
Copyright (C) 2026 :)

Dystrybuowane na licencji MIT <https://mit-license.org>
"""

from __future__ import annotations
from typing import TYPE_CHECKING, Any, ClassVar, Iterator, Mapping, MutableMapping, Sequence
from typing_extensions import NotRequired, TypedDict
import base64
import hashlib
import hmac as _hmac
import json
# For playermb mylist support
import re
from urllib.parse import parse_qs, urlencode, quote_plus
import xml.etree.ElementTree as ET

from lib.ff import requests
import xbmcaddon
from lib.ff import cleantitle, control, apis, source_utils
from lib.ff.settings import settings
# from lib.ff.log_utils import LOGDEBUG, LOGERROR, LOGINFO, LOGWARNING, fflog
from const import const
from attr import frozen
from lib.ff.log_utils import fflog, fflog_exc
from lib.api.justwatch import JustWatchClient, JWOffers
if TYPE_CHECKING:
    from lib.ff.item import FFItem
    from lib.sources import SourceItem, SourceTitleAlias


# ─── Types ─────────────────────────────────────────────────────────────────────────

class PlayerTranslate(TypedDict):       # player.pl item/translate
    id: NotRequired[int]


class PlayerVod(TypedDict):             # player.pl product/vod
    id: NotRequired[int]
    uhd: NotRequired[bool]


class PbgResponse(TypedDict):           # Polsat Box Go navigation RPC
    result: NotRequired[Any]
    errors: NotRequired[Any]


# Language codes external.py is allowed to put into a source's `language` field —
# must be a subset of const.sources.language_order, or sourcesFilter's sort
# (lib/ff/sources.py, `language_order[d["language"]]`, a plain dict lookup with
# no fallback) raises KeyError for the whole source list, not just this stream.
_KNOWN_OFFER_LANGS = frozenset(const.sources.language_order) - {'mul', 'multi', '-', ''}


def _offer_language(offer: Mapping[str, Any]) -> tuple[str, str]:
    """Derive (language, info2) from a JustWatch offer's audio/subtitle language lists.

    'pl' wins if present (a PL user can pick it), else 'en', else a single other
    known language, else 'pl' (unknown/no data/ambiguous — never a code
    sourcesFilter can't sort). When more than one language is available, 'MULTI'
    is added to info2 regardless of which language ends up primary, so the badge
    doesn't hide that there are more tracks to choose from. Subtitle languages (if
    any) are listed in info2 too, since which one to use is picked in-player, not
    baked into the deep link.
    """
    audio = set(offer.get('audioLanguages') or ())
    subs = set(offer.get('subtitleLanguages') or ())
    langs = audio | subs
    if 'pl' in langs:
        lang = 'pl'
    elif 'en' in langs:
        lang = 'en'
    else:
        known = langs & _KNOWN_OFFER_LANGS
        lang = next(iter(known)) if len(known) == 1 else 'pl'
    info2_parts = []
    if len(langs) > 1:
        info2_parts.append('MULTI')
    if subs:
        info2_parts.append('NAPISY: ' + ', '.join(sorted(code.upper() for code in subs)))
    info2 = ' | '.join(info2_parts)
    return lang, info2


@frozen
# ─── ExternalSource ──────────────────────────────────────────────────────────────
class ExternalSource:
    id: str
    source: str
    addon: str
    packages: set[str]

    @property
    def enabled(self) -> bool:
        if const.dev.sources.force_all_sources:
            return True
        if not settings.getBool(f'external.enable.{self.id}'):
            return False
        if self.addon.lower() != self.addon:  # incorrect addon id (has upper characters)
            from pathlib import Path
            path = Path(control.addonPath).parent / self.addon
            return path.exists()
        return control.condVisibility(f'System.AddonIsEnabled({self.addon})')

    def icon(self) -> str | None:
        try:
            if icon := xbmcaddon.Addon(self.addon).getAddonInfo('icon'):
                return icon
        except RuntimeError:  # if no addon
            pass
        return None

    def offers(self, offers: Mapping[str, Any]) -> Iterator[Mapping[str, Any]]:
        """Iterate over matchig platforms (clearName)."""
        # TODO: check: flatrate, buy, rent
        for item in offers['flatrate']:
            if item['package']['clearName'] in self.packages:
                yield item

    def links(self, offers: Mapping[str, Any]) -> Iterator[str]:
        """Iterate over matchig platforms' (clearName) link."""
        for item in self.offers(offers):
            yield item['standardWebURL']

    def links_lang(self, offers: Mapping[str, Any]) -> Iterator[tuple[str, str, str]]:
        """Iterate over matching platforms' (url, language, info2) — see _offer_language."""
        for item in self.offers(offers):
            lang, info2 = _offer_language(item)
            yield item['standardWebURL'], lang, info2


external_sources: Mapping[str, ExternalSource] = {ext.id: ext for ext in (
    ExternalSource('netflix',      'Netflix',             'plugin.video.netflix',     {'Netflix'}),
    ExternalSource('prime',        'amazon prime',        'plugin.video.amazon-test', {'Amazon Prime Video'}),
    ExternalSource('max',          'max',                 'slyguy.max',               {'HBO Max'}),
    ExternalSource('disney',       'disney+',             'slyguy.disney.plus',       {'Disney Plus'}),
    ExternalSource('iplayer',      'bbc iplayer',         'plugin.video.iplayerwww',  {'bbc'}),  # no clearname
    ExternalSource('curstream',    'curiosity stream',    'slyguy.curiositystream',   {'Curiosity Stream'}),
    ExternalSource('hulu',         'hulu',                'slyguy.hulu',              {'Hulu'}),
    ExternalSource('paramount',    'paramount+',          'slyguy.paramount.plus',    {'Paramount'}),
    ExternalSource('playerpl',     'player pl',           'plugin.video.playermb',    {'Player'}),
    ExternalSource('playerpl_mtr', 'player pl (mtr)',     'plugin.video.player_pl',   {'Player'}),
    ExternalSource('skyott',       'sky showtime',        'plugin.video.skyott',      {'SkyShowtime'}),
    ExternalSource('cda',          'cda premium',         'plugin.video.phantom',     {'CDA Premium'}),
    ExternalSource('cda_mb',       'cda premium (mbebe)', 'plugin.video.cdaplMB',     {'CDA Premium'}),
    ExternalSource('cda_mtr',      'cda premium (mtr)',   'plugin.video.cda_premium', {'CDA Premium'}),
    ExternalSource('crunchyroll',  'crunchyroll',         'plugin.video.crunchyroll',
                   {'Crunchyroll', 'Crunchyroll Amazon Channel'}),
    ExternalSource('tvpvod',       'TVP VOD',             'plugin.video.TVP_VOD',     {'TVP'}),
    ExternalSource('polsatboxgo',  'polsat box go',       'plugin.video.polsatbox_go', {'Polsat Box Go'}),
)}


netflix_pattern = 'plugin://plugin.video.netflix/play/movie/%s'
netflix_show_pattern = 'plugin://plugin.video.netflix/play/show/%s/season/%s/episode/%s/'
prime_pattern = 'plugin://plugin.video.amazon-test/?asin=%s&mode=PlayVideo&name=None&adult=0&trailer=0&selbitrate=0'
max_pattern = 'plugin://slyguy.max/?_=play&_play=1&id='
disney_pattern = 'plugin://slyguy.disney.plus/?_=play&_play=1&content_id=%s&profile_id=%s'
disney_pattern_nocontentid = 'plugin://slyguy.disney.plus/?_=play&_play=1&deeplink_id=%s&profile_id=%s'
iplayer_pattern = 'plugin://plugin.video.iplayerwww/?url=%s&mode=202&name=null&iconimage=null&description=null&subtitles_url=&logged_in=False'
curstream_pattern = 'plugin://slyguy.curiositystream/?_=play&_play=1&id='
hulu_pattern = 'plugin://slyguy.hulu/?_=play&id='
paramount_pattern = 'plugin://slyguy.paramount.plus/?_=play&id='
playerpl_pattern = 'plugin://plugin.video.playermb/?mode=playvid&url={id}'
playerplmtr_pattern = 'plugin://plugin.video.player_pl/?mode=playVid&profile={profile}&eid={id}'
skyott_pattern = 'plugin://plugin.video.skyott/?action=play&slug='
disney_url_patterns = [
    re.compile(r'entity-([a-f0-9-]+)'),
    re.compile(r'/movies/([^/?]+)'),
    re.compile(r'/series/([^/?]+)'),
    re.compile(r'/video/([a-f0-9-]+)'),
]
cda_pattern = (
    'plugin://plugin.video.phantom/?url=https://www.cda.pl/video/{id}/vfilm'
    '&mode=DecodeLink&param0={{"id_stream":+"{id}/vfilm",+"url":+"https://www.cda.pl/video/{id}/vfilm",+"dursec":+0,+"savelvl":0,'
    '+"urlref":+"?mode=StreamBaseList"}}'
)
cdamb_pattern = 'plugin://plugin.video.cdaplMB/?mode=playvid&url={id}&page=1&moviescount=0&title={title}&image={image}?t=1'
cdamtr_pattern = 'plugin://plugin.video.cda_premium/?mode=playVid&vid={id}'
crunchyroll_episode_pattern = 'plugin://plugin.video.crunchyroll/video/{}/{}/{}'
crunchyroll_movie_pattern = 'plugin://plugin.video.crunchyroll/video/{}/{}'
tvpvod_pattern = 'plugin://plugin.video.TVP_VOD/?mode=playVid&eid=%s'
polsatboxgo_pattern = 'plugin://plugin.video.polsatbox_go/?mode=playCont&mediaid={id}&cpid=1&type=vod'

scraper_init = any(ext.enabled for ext in external_sources.values())

# Shared by the module-level helpers below (cda_alternatives, _pbg_find_episode_mediaid
# — no self available there) so repeated requests reuse one TCP/TLS connection.
_session = requests.Session()


# ─── source ──────────────────────────────────────────────────────────────────────

class source:
    ffitem: FFItem

    priority: ClassVar[int] = 1
    language: ClassVar[list[str]] = ['pl', 'en']

    has_color_identify2: bool = True
    use_premium_color: bool = True

    def __init__(self):
        self.domains = []
        self.base_link = ''
        self.session = requests.Session()
        self.tm_user = settings.getString('tmdb.api_key') or apis.tmdb_API
        self.country = settings.getString('external.country') or 'US'
        self.aliases = []
        self._jw_client = JustWatchClient(country=self.country, language=self.language[0])
        self._jw_client_us: JustWatchClient | None = None  # lazy — only needed as crunchyroll fallback

    # ── public api ─────────────────────────────────────────────────────────

    def movie(self, imdb: str, title: str, localtitle: str, aliases: list[SourceTitleAlias], year: str) -> str | None:
        if not scraper_init and not const.dev.sources.force_all_sources:
            return

        try:
            self.aliases.extend(aliases)
            url = {'imdb': imdb, 'title': title, 'localtitle': localtitle, 'year': year}
            url = urlencode(url)
            return url
        except Exception:
            fflog_exc()
            return

    def tvshow(self, imdb: str, tvdb: str, tvshowtitle: str, localtvshowtitle: str, aliases: list[SourceTitleAlias], year: str) -> str | None:
        if not scraper_init and not const.dev.sources.force_all_sources:
            return

        try:
            tmdb = self.ffitem.show_item.tmdb_id
            self.aliases.extend(aliases)
            url = {'imdb': imdb, 'tmdb': tmdb, 'tvshowtitle': tvshowtitle,
                'localtvshowtitle': localtvshowtitle, 'year': year}
            url = urlencode(url)
            return url
        except Exception:
            fflog_exc()
            return

    def episode(self, url: str | None, imdb: str, tvdb: str, title: str, premiered: str, season: str, episode: str) -> str | None:
        try:
            if url is None:
                return
            url = parse_qs(url)
            url = dict([(i, url[i][0]) if url[i] else (i, '') for i in url])
            url['title'], url['premiered'], url['season'], url['episode'] = (title, premiered, season, episode,)
            url = urlencode(url)
            return url
        except Exception:
            fflog_exc()
            return

    def sources(self, url: str | None, hostDict: list[str], hostprDict: list[str]) -> list[SourceItem] | None:
        return self._sources(url)

    def _get_jw_client_us(self) -> JustWatchClient:
        if self._jw_client_us is None:
            self._jw_client_us = JustWatchClient(country='US', language=self.language[0])
        return self._jw_client_us

    def _resolve_offers(
        self, jw_client: JustWatchClient, title: str, localtitle: str, year: str, imdb_id: str,
        content: str, data: dict[str, str],
    ) -> JWOffers | None:
        """Find the title/episode on JustWatch (via *jw_client*'s country) and return its offers."""
        jw_path = jw_client.find_title(title, localtitle, int(year), imdb_id, content_type=content)
        if not jw_path:
            return None

        if content == 'show':
            # Anime often has its cours grouped differently across sources — e.g. TMDB may
            # list one continuous season while JustWatch (mirroring Crunchyroll-style cour
            # splits) lists several. Passing TMDB's season/episode straight through would
            # look in the wrong JustWatch season (or overrun a short one). Use the show's
            # absolute episode number and let JustWatch's own season/episode structure
            # resolve it — no need for Kitsu here, JustWatch already exposes that structure.
            abs_ep = self.ffitem.absolute_episode_number() if source_utils.is_anime(self.ffitem) else None
            if abs_ep:
                node_id = jw_client.find_episode_node_id_by_absolute(jw_path, abs_ep)
            else:
                node_id = jw_client.find_episode_node_id(jw_path, int(data['season']), int(data['episode']))
        else:
            node_id = jw_client.get_movie_node_id(jw_path)

        if not node_id:
            return None
        return jw_client.get_offers(node_id)

    def _sources(self, url: str | None) -> list[SourceItem] | None:

        def jget(url: str, params: dict[str, str] | None = None) -> Any:
            return self.session.get(url, params=params).json()

        sources: list[SourceItem] = []
        if url is None:
            return sources

        data = parse_qs(url)
        data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])
        title = data['tvshowtitle'] if 'tvshowtitle' in data else data['title']
        localtitle = data['localtvshowtitle'] if 'localtvshowtitle' in data else data['localtitle']
        year = data['year']
        imdb_id = data.get('imdb', '')
        content = 'movie' if 'tvshowtitle' not in data else 'show'

        fflog(f'query: {title!r} imdb={imdb_id!r}')
        offers = self._resolve_offers(self._jw_client, title, localtitle, year, imdb_id, content, data)
        if not offers:
            fflog(f'external: {title!r} not found/available in {self.country!r}')
            return sources

        services: set[str] = set()
        try:
            services = {offer['package']['clearName'] for offer in offers['flatrate']}
        except KeyError:
            fflog_exc()
        fflog(f'justwatch offers: {sorted(services)}')
        # fflog(f'justwatch offers: {json.dumps(offers, indent=2)}')  # full JSON dump

        streams: list[tuple[ExternalSource, str] | MutableMapping[str, Any]] = []

        if (ext := external_sources.get('netflix')) and ext.enabled:
            try:
                for url, lang, info2 in ext.links_lang(offers):
                    nfx_id = url.rstrip('/').split('/')[-1]
                    if content == 'movie':
                        netflix_id = nfx_id
                        streams.append({'external_source': ext, 'url': netflix_pattern % netflix_id,
                            'language': lang, 'info2': info2})
                    else:
                        ep_titles = [t for t in (self.ffitem.vtag.getEnglishTitle(), self.ffitem.vtag.getOriginalTitle()) if t] or [data['title']]
                        netflix_id = source_utils.get_netflix_ep_id(nfx_id, data['season'], data['episode'], ep_titles)
                        if netflix_id:
                            ep_url = netflix_show_pattern % (nfx_id, netflix_id[1], netflix_id[0])
                            streams.append({'external_source': ext, 'url': ep_url, 'language': lang, 'info2': info2})
            except Exception:
                fflog_exc()

        if (ext := external_sources.get('prime')) and ext.enabled:
            try:
                for url, lang, info2 in ext.links_lang(offers):
                    prime_id = url.rstrip('/').split('gti=')[1]
                    streams.append({'external_source': ext, 'url': prime_pattern % prime_id,
                        'language': lang, 'info2': info2})
            except Exception:
                fflog_exc()
                pass

        if (ext := external_sources.get('max')) and ext.enabled:
            try:
                for url, lang, info2 in ext.links_lang(offers):
                    max_id = url.rstrip('/').split('/')[-1]
                    if content == 'movie':
                        max_id = max_id.split('?')[0]
                    streams.append({'external_source': ext, 'url': max_pattern + max_id,
                        'language': lang, 'info2': info2})
            except Exception:
                fflog_exc()

        if (ext := external_sources.get('skyott')) and ext.enabled:
            try:
                for url, lang, info2 in ext.links_lang(offers):
                    sott_id = url.split('https://www.skyshowtime.com/pl/stream')
                    streams.append({'external_source': ext, 'url': skyott_pattern + sott_id[1],
                        'language': lang, 'info2': info2})
            except Exception:
                fflog_exc()

        if (ext := external_sources.get('disney')) and ext.enabled:
            try:
                profile_id = 'default'
                content_id = None

                # Look up Disney+ offer
                dnp_offers = [o for o in offers.get('flatrate', []) if o.get(
                    'package', {}).get('clearName') in ext.packages]
                dnp_lang, dnp_info2 = _offer_language(dnp_offers[0]) if dnp_offers else ('pl', '')
                if dnp_offers:
                    deeplink_roku = dnp_offers[0].get('deeplinkRoku')
                    if deeplink_roku and '?' in deeplink_roku:
                        try:
                            query_params = parse_qs(deeplink_roku.split('?')[-1])
                            content_id = query_params.get('contentID', [''])[0]
                        except Exception:
                            pass

                if content_id:
                    streams.append({'external_source': ext, 'url': disney_pattern % (content_id, profile_id),
                        'language': dnp_lang, 'info2': dnp_info2})
                else:
                    # Process all Disney+ URLs with optimization
                    from urllib.parse import unquote

                    for url in ext.links(offers):
                        deeplink_id = None

                        # Decode URL if needed
                        if 'disneyplus.bn5x.net' in url or 'u=' in url:
                            if 'u=' in url:
                                try:
                                    url = unquote(url.split('u=')[1].split('&')[0])
                                except Exception:
                                    continue

                        # Find deeplink_id using precompiled patterns
                        if 'disneyplus.com' in url:
                            for pattern in disney_url_patterns:
                                match = pattern.search(url)
                                if match:
                                    deeplink_id = match.group(1)
                                    break

                            if deeplink_id:
                                streams.append({'external_source': ext, 'url': disney_pattern_nocontentid % (deeplink_id, profile_id),
                                    'language': dnp_lang, 'info2': dnp_info2})

            except Exception:
                fflog_exc()

        # Shared Player.pl data — computed once for both variants (playerpl and playerpl_mtr)
        _playerpl_ext = external_sources.get('playerpl')
        _playerplmtr_ext = external_sources.get('playerpl_mtr')
        _playerpl_any = (
            (_playerpl_ext and _playerpl_ext.enabled) or
            (_playerplmtr_ext and _playerplmtr_ext.enabled)
        )
        _playerpl_vod_id = None    # internal vod id (from translate API)
        _playerpl_aid = None       # article id (from URL)
        _playerpl_quality = None
        _playerpl_fallback = False  # True when translate failed — use aid directly
        _playerpl_lang, _playerpl_info2 = 'pl', ''

        if _playerpl_any:
            try:
                _plp_packages = set()
                if _playerpl_ext:
                    _plp_packages |= _playerpl_ext.packages
                if _playerplmtr_ext:
                    _plp_packages |= _playerplmtr_ext.packages
                plp_offers = [o for o in offers.get('flatrate', []) if o.get(
                    'package', {}).get('clearName') in _plp_packages]
                plp_url = plp_offers[0].get('standardWebURL') if plp_offers else None
                if plp_offers:
                    _playerpl_lang, _playerpl_info2 = _offer_language(plp_offers[0])
                if plp_url:
                    url_match = re.search(r'(?:/(?P<serial_slug>[^/]*)-odcinki,(?P<sid>\d+))?'
                                         r'/(?P<slug>[^/]+?)(?:,S(?P<season>\d+)E(?P<episode>\d+))?,(?P<id>\d+)$', plp_url)
                    if url_match:
                        _playerpl_aid = url_match.group('id')
                        fflog(f'playerpl: URL={plp_url} articleId={_playerpl_aid}')
                        params = {'platform': 'BROWSER'}
                        api_response: PlayerTranslate = jget(
                            f'https://player.pl/playerapi/item/translate?articleId={_playerpl_aid}', params=params)
                        fflog(f'playerpl: translate response={api_response}')
                        if not api_response or not api_response.get('id'):
                            fflog(f'playerpl: translate found no VOD, aid={_playerpl_aid} as fallback')
                            _playerpl_fallback = True
                        else:
                            internal_id = api_response['id']
                            vod_details: PlayerVod = jget(f"https://player.pl/playerapi/product/vod/{internal_id}", params=params)
                            if vod_details and vod_details.get('id'):
                                _playerpl_vod_id = vod_details['id']
                                _playerpl_quality = '4K' if vod_details.get('uhd') else '1080p'
                                fflog(f'playerpl: vod_id={_playerpl_vod_id} quality={_playerpl_quality}')
            except Exception:
                fflog_exc()

        def playerpl_source(ext: ExternalSource, url_pattern: str, profile: str | None = None):
            if not _playerpl_aid:
                return
            if _playerpl_fallback:
                streams.append({
                    'external_source': ext,
                    'url': url_pattern.format(id=_playerpl_aid, profile=profile),
                    'language': _playerpl_lang,
                    'info2': _playerpl_info2,
                })
            elif _playerpl_vod_id:
                streams.append({
                    'external_source': ext,
                    'url': url_pattern.format(id=_playerpl_vod_id, profile=profile),
                    'quality': _playerpl_quality,
                    'language': _playerpl_lang,
                    'info2': _playerpl_info2,
                })

        if _playerpl_ext and _playerpl_ext.enabled:
            playerpl_source(_playerpl_ext, playerpl_pattern)

        if _playerplmtr_ext and _playerplmtr_ext.enabled:
            try:
                profile = xbmcaddon.Addon('plugin.video.player_pl').getSetting('profile_uid')
            except RuntimeError:
                profile = ''
            playerpl_source(_playerplmtr_ext, playerplmtr_pattern, profile)

        if (ext := external_sources.get('iplayer')) and ext.enabled:
            try:
                for url, lang, info2 in ext.links_lang(offers):
                    streams.append({'external_source': ext, 'url': iplayer_pattern % quote_plus(url),
                        'language': lang, 'info2': info2})
            except Exception:
                fflog_exc()
                pass

        if (ext := external_sources.get('curstream')) and ext.enabled:
            try:
                for url, lang, info2 in ext.links_lang(offers):
                    cts_id = url.rstrip('/').split('/')[-1]
                    streams.append({'external_source': ext, 'url': curstream_pattern + cts_id,
                        'language': lang, 'info2': info2})
            except Exception:
                fflog_exc()

        if (ext := external_sources.get('hulu')) and ext.enabled:
            try:
                for url, lang, info2 in ext.links_lang(offers):
                    hulu_id = url.rstrip('/').split('/')[-1]
                    streams.append({'external_source': ext, 'url': hulu_pattern + hulu_id,
                        'language': lang, 'info2': info2})
            except Exception:
                fflog_exc()

        if (ext := external_sources.get('paramount')) and ext.enabled:
            try:
                for url, lang, info2 in ext.links_lang(offers):
                    pmp_id = (url.split('?')[0].split('/')[-1] if content ==
                              'movie' else re.findall('/video/(.+?)/', url)[0])
                    streams.append({'external_source': ext, 'url': paramount_pattern + pmp_id,
                        'language': lang, 'info2': info2})
            except Exception:
                fflog_exc()

        # CDA optimization: process all variants efficiently
        cda_extensions = []
        if (ext := external_sources.get('cda')) and ext.enabled:
            cda_extensions.append(('cda', ext, cda_pattern))
        if (ext := external_sources.get('cda_mb')) and ext.enabled:
            cda_extensions.append(('cda_mb', ext, cdamb_pattern))
        if (ext := external_sources.get('cda_mtr')) and ext.enabled:
            cda_extensions.append(('cda_mtr', ext, cdamtr_pattern))

        if cda_extensions:
            try:
                # Find CDA links from the first available extension
                cda_urls = []
                for _, ext, _ in cda_extensions:
                    cda_urls = list(ext.links(offers))
                    if cda_urls:
                        break

                if cda_urls:
                    # Cache for CDA variants — fetch once per URL
                    processed_urls = set()

                    for cda_url in cda_urls:
                        if cda_url in processed_urls:
                            continue
                        processed_urls.add(cda_url)

                        try:
                            cda_variants = cda_alternatives(cda_url)

                            for ext_type, ext, pattern in cda_extensions:
                                for variant, variant_url in cda_variants:
                                    if ext_type == 'cda_mb':
                                        stream_url = pattern.format(id=cda_id(variant_url),
                                                                    title=self.ffitem.title, image='')
                                    else:
                                        stream_url = pattern.format(id=cda_id(variant_url))

                                    streams.append({
                                        'url': stream_url,
                                        'external_source': ext,
                                        'info': variant,
                                    })
                        except Exception:
                            continue
            except Exception:
                fflog_exc()

        if (ext := external_sources.get('crunchyroll')) and ext.enabled:
            def add_crunchyroll(url: str, lang: str, info2: str) -> bool:
                # JustWatch sometimes returns Crunchyroll offers as an affiliate redirect
                # (e.g. https://crunchyroll.pxf.io/xxxx?u=<url-encoded target>&subId3=...)
                # instead of a direct link — unwrap it to get the real crunchyroll.com URL.
                # NOTE: can't gate this on 'crunchyroll.com' not in url — the encoded
                # target (dots aren't percent-escaped) already contains that substring.
                target = url
                if '?' in target:
                    qs = parse_qs(target.split('?', 1)[1])
                    if u := qs.get('u'):
                        target = u[0]
                # Extract ID from Crunchyroll URL
                # URL format: https://www.crunchyroll.com/watch/G31UV2Q23/the-slow-but-dangerous-life
                # NOTE: offers like "Crunchyroll Amazon Channel" sometimes point to an
                # app.primevideo.com URL instead — that's a distinct Amazon/GTI id space with
                # no crossover to Crunchyroll's own ids, so there's nothing to extract there.
                if '/watch/' not in target:
                    return False
                crunchyroll_id = target.split('/watch/')[1].split('/')[0]
                # Some Crunchyroll IDs end in a 4-letter language/country variant code
                # (e.g. GMEE00351144ENUS, GMEE00351144HIIN, GMEE00351144TAIN — the same
                # title exposed as separate localized objects). JustWatch doesn't always
                # give us the variant that's actually live yet (e.g. hi-IN/ta-IN dubs can
                # 403 with "availability window has not started" while en-US already
                # works) — force the en-US variant, which Crunchyroll provides for
                # virtually all of its catalog.
                suffix = crunchyroll_id[-4:]
                if len(crunchyroll_id) > 4 and suffix.isalpha() and suffix.isupper() and suffix != 'ENUS':
                    crunchyroll_id = crunchyroll_id[:-4] + 'ENUS'
                # Try movie format first: /video/{episode_id}/{stream_id}
                # If that fails, we could try episode format with the same ID for all parameters
                streams.append({'external_source': ext,
                    'url': crunchyroll_movie_pattern.format(crunchyroll_id, crunchyroll_id),
                    'language': lang, 'info2': info2})
                return True

            cr_found = False
            try:
                for url, lang, info2 in ext.links_lang(offers):
                    if add_crunchyroll(url, lang, info2):
                        cr_found = True
            except Exception:
                fflog_exc()

            # Crunchyroll is played through the user's own logged-in account, not a
            # region-locked JustWatch offer — a native crunchyroll.com link discovered via
            # JustWatch/US is just as playable as one from the configured country. So if the
            # configured country's offers didn't have one (e.g. only a "Crunchyroll Amazon
            # Channel" offer, which points at an Amazon URL with no Crunchyroll id in it),
            # retry against JustWatch/US before giving up.
            if not cr_found and self.country.upper() != 'US':
                try:
                    us_offers = self._resolve_offers(self._get_jw_client_us(), title, localtitle, year, imdb_id, content, data)
                    if us_offers:
                        fflog(f'crunchyroll: no native offer in {self.country!r}, trying US')
                        # fflog(f'crunchyroll/US offers: {json.dumps(us_offers, indent=2)}')  # full JSON dump
                        for url, lang, info2 in ext.links_lang(us_offers):
                            add_crunchyroll(url, lang, info2)
                except Exception:
                    fflog_exc()

        if (ext := external_sources.get('tvpvod')) and ext.enabled:
            try:
                for url in ext.links(offers):
                    if m := re.search(r'(\d+)$', url):
                        tvp_id = m.group(1)
                    else:
                        tvp_id = ''
                    streams.append((ext, tvpvod_pattern % tvp_id))
            except Exception:
                fflog_exc()
                pass

        # Movie URL: https://polsatboxgo.pl/wideo/film/{slug}/{mediaid}?pk=...
        # Show URL:  https://polsatboxgo.pl/wideo/{slug}/{cid}/autoplay
        if (ext := external_sources.get('polsatboxgo')) and ext.enabled:
            if content == 'movie':
                try:
                    for url in ext.links(offers):
                        path = url.split('?')[0].rstrip('/')
                        pbg_id = path.split('/')[-1]
                        if re.fullmatch(r'[0-9a-f]{32}', pbg_id):
                            streams.append((ext, polsatboxgo_pattern.format(id=pbg_id)))
                except Exception:
                    fflog_exc()
            elif content == 'show':
                try:
                    for url in ext.links(offers):
                        if m := re.search(r'/(\d+)/autoplay', url):
                            ep = _pbg_find_episode_mediaid(m.group(1), int(data['season']), int(data['episode']))
                            if ep:
                                mediaid, cpid = ep
                                streams.append(
                                    (ext, f"plugin://plugin.video.polsatbox_go/?mode=playCont&mediaid={mediaid}&cpid={cpid}&type=vod"))
                                break
                except Exception:
                    fflog_exc()

        # -------

        if streams:
            default = {'quality': '1080p', 'language': 'pl', 'direct': True,
                'debridonly': False, 'premium': True, 'external': True, 'info2': ''}
            for stream in streams:
                if isinstance(stream, MutableMapping):
                    stream_data = stream
                else:
                    stream_data = {'external_source': stream[0], 'url': stream[1]}
                ext: ExternalSource | None = stream_data.pop('external_source', None)
                if ext:
                    stream_data.setdefault('source', ext.source)
                    if quality := const.sources_dialog.external_quality_label.get(ext.source):
                        stream_data.setdefault('quality', quality)
                    if icon := ext.icon():
                        stream_data.setdefault('icon', icon)
                sources.append({**default, **stream_data})
            fflog(f'sources: {len(sources)}')
            return sources

    def resolve(self, url: str) -> str | None:
        return url


def cda_id(url: str) -> str:
    """Return cda id from cda url."""
    return re.sub(r'^.*video/([^/]+)/vfilm', r'\1', url)


def cda_alternatives(url: str) -> Sequence[tuple[str, str]]:
    """
    Return sequence of (variant, url).
    Find CDA movie alternatives (lector, subtitles) and return all link, original too.
    """
    try:
        resp = _session.get(url, timeout=5)
    except OSError:
        # cda down, return original only
        return (('', url),)
    if resp.status_code == 200:
        if mch := re.search(r'<meta\s+name="description"\s+content="[^"]*Wersja z (?P<type>\w+):\s*(?P<url>http[^"\s]+)[^"]*"', resp.text):
            alt_type, alt_url = mch.group('type', 'url')
            # if alt is lektor (voice-over), we are subtitles
            if alt_type == 'lektorem':
                return (('napisy', url), ('lektor', alt_url))
            # if alt is napisy (subtitles), we are lektor (voice-over)
            if alt_type == 'napisami':
                return (('lektor', url), ('napisy', alt_url))
            # if alt is unknown, we are not-language (https://www.czu.pl/jezyk/)
            return (('', url), ('', alt_url))
    # no alternative
    return (('', url),)


def _pbg_session_token(sess_id: str, sess_key_exp: str, sess_key: str, group: str, method: str) -> str:
    """Generate Polsat Box Go session token (HMAC-SHA256) — same logic as addon.py:get_sessionToken."""
    r = f'{sess_id}|{sess_key_exp}|{group}|{method}'
    key = base64.b64decode(sess_key.replace('-', '+').replace('_', '/'))
    sig = base64.b64encode(_hmac.new(key, r.encode(), hashlib.sha256).digest()).decode()
    sig = sig.replace('+', '-').replace('/', '_')
    return f'{r}|{sig}'


def _pbg_find_episode_mediaid(show_cid: str, season: int, episode: int) -> tuple[str, str] | None:
    """
    Return (mediaid, cpid) for a specific Polsat Box Go episode, or None on failure.
    Replicates the seasonList → contentList navigation from addon.py.
    """
    try:
        pbg = xbmcaddon.Addon('plugin.video.polsatbox_go')
        sess_id = pbg.getSetting('sessId')
        sess_key = pbg.getSetting('sessKey')
        sess_key_exp = pbg.getSetting('sessKeyExp')
        clientid = pbg.getSetting('clientid')
        deviceid = pbg.getSetting('deviceid')
        client_ctx_token = pbg.getSetting('ClientContextToken')

        if not all([sess_id, sess_key, sess_key_exp, clientid, deviceid]):
            fflog('pbg: no session data in plugin settings')
            return None

        UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/110.0'
        UA_post = 'pbg_pc_windows_firefox_html/2 (Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/110.0)'
        base_hea = {'User-Agent': UA, 'Referer': 'https://polsatboxgo.pl/'}
        userAgentData = {
            'application': 'firefox', 'build': 2, 'deviceType': 'pc',
            'os': 'windows', 'osInfo': UA, 'player': 'html', 'portal': 'pbg',
        }
        rpc = 'https://b2c-www.redefine.pl/rpc/navigation/'

        # Step 1: getSubCategories — list of seasons for the tvshow
        tok = _pbg_session_token(sess_id, sess_key_exp, sess_key, 'navigation', 'getSubCategories')
        resp: PbgResponse = _session.post(rpc, json={
            'id': 1, 'jsonrpc': '2.0', 'method': 'getSubCategories',
            'params': {
                'authData': {'sessionToken': tok},
                'catid': int(show_cid),
                'clientId': clientid,
                'deviceId': {'type': 'other', 'value': deviceid},
                'ua': UA_post,
                'userAgentData': userAgentData,
            },
        }, headers=base_hea, timeout=10).json()

        if 'errors' in resp or 'result' not in resp:
            fflog(f'pbg: getSubCategories error {resp}')
            return None

        seasons = resp['result']

        # When there is only 1 season (or 0), the plugin queries by show_cid directly
        if len(seasons) <= 1:
            season_catid = show_cid
        else:
            s = next((s for s in seasons if s.get('seasonNumber') == season), None)
            if not s:
                fflog(f'pbg: season {season} not found')
                return None
            season_catid = str(s['id'])

        # Step 2: getCategoryContentWithFlatNavigation — list of episodes
        tok = _pbg_session_token(sess_id, sess_key_exp, sess_key, 'navigation', 'getCategoryContentWithFlatNavigation')
        ep_data = {
            'catid': int(season_catid),
            'collection': {'default': False, 'name': 'Data dodania', 'type': 'sortedby', 'value': '12'},
            'filters': [],
            'limit': 200,
            'offset': 0,
        }
        ep_hea = {**base_hea, **{
            'X-Auth-Data-Session-Id': sess_id,
            'X-Auth-Data-Session-Token': tok,
            'X-Client-Context-Token': client_ctx_token,
            'X-Client-Id': clientid,
            'X-Device-Id-Type': 'other',
            'X-Device-Id-Value': deviceid,
            'X-User-Agent-Data-Application': 'firefox',
            'X-User-Agent-Data-Build': '2',
            'X-User-Agent-Data-Device-Type': 'pc',
            'X-User-Agent-Data-Os': 'windows',
            'X-User-Agent-Data-Os-Info': UA,
            'X-User-Agent-Data-Player': 'html',
            'X-User-Agent-Data-Portal': 'pbg',
        }}
        url_params = {
            'id': '1',
            'method': 'getCategoryContentWithFlatNavigation',
            'params': base64.b64encode(json.dumps(ep_data).encode()).decode(),
        }
        resp: PbgResponse = _session.get(rpc, json=ep_data, headers=ep_hea, params=url_params, timeout=10).json()

        if 'errors' in resp or 'result' not in resp:
            fflog(f'pbg: getCategoryContent error {resp}')
            return None

        ep = next((e for e in resp['result']['results'] if e.get('episodeNumber') == episode), None)
        if not ep:
            fflog(f'pbg: episode {episode} not found in season {season}')
            return None

        return str(ep['id']), str(ep['cpid'])

    except Exception:
        fflog_exc()
        return None
