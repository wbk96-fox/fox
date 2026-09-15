# -*- coding: utf-8 -*-
"""
FanFilm - źródło: jellyfin
Copyright (C) 2026 :)

Dystrybuowane na licencji MIT <https://mit-license.org>
Requires plugin.video.jellycon or plugin.video.jellyfin
from the repository https://kodi.jellyfin.org
"""

from __future__ import annotations

import json
import xml.etree.ElementTree as ET
from typing import TYPE_CHECKING, ClassVar
from typing_extensions import NotRequired, TypedDict
from urllib.parse import urlencode, parse_qs

from lib.ff import requests
from lib.sources import single_call
import xbmcaddon
import xbmcvfs

from lib.ff import control, source_utils
from lib.ff.item import FFItem
from lib.ff.settings import settings
from lib.ff.log_utils import fflog, log_exc
from lib.ff.source_utils import convert_size

if TYPE_CHECKING:
    from lib.sources import SourceItem, SourceTitleAlias


# ─── Types ─────────────────────────────────────────────────────────────────────────

class SearchHint(TypedDict):            # /Search/Hints → SearchHints[]
    ItemId: str
    Name: NotRequired[str]
    ProductionYear: NotRequired[int]
    Type: NotRequired[str]


class SearchResponse(TypedDict):
    SearchHints: NotRequired[list[SearchHint]]


class EpisodeItem(TypedDict):           # /Shows/{id}/Episodes → Items[]
    Id: str
    IndexNumber: NotRequired[int]


class EpisodesResponse(TypedDict):
    Items: list[EpisodeItem]


class MediaStream(TypedDict):           # PlaybackInfo → MediaSources[].MediaStreams[]
    Type: str
    Codec: NotRequired[str]
    Width: NotRequired[int]
    Height: NotRequired[int]
    Language: NotRequired[str]
    ChannelLayout: NotRequired[str]
    Profile: NotRequired[str]
    Title: NotRequired[str]
    VideoRange: NotRequired[str]
    VideoRangeType: NotRequired[str]
    BitDepth: NotRequired[int]
    DisplayTitle: NotRequired[str]


class MediaSource(TypedDict):
    Name: NotRequired[str]
    Size: NotRequired[int]
    MediaStreams: NotRequired[list[MediaStream]]


class PlaybackInfo(TypedDict):          # /Items/{id}/PlaybackInfo
    MediaSources: NotRequired[list[MediaSource]]


class JellyServer(TypedDict):
    server_address: str
    token: str
    user_id: str
    server_name: str


jellycon_plugin = 'plugin.video.jellycon'
jellycon_enabled = control.condVisibility(f"System.AddonIsEnabled({jellycon_plugin})")
jellyfin_plugin = 'plugin.video.jellyfin'
jellyfin_enabled = control.condVisibility(f"System.AddonIsEnabled({jellyfin_plugin})")
if jellycon_enabled:
    CACHE_NAME = 'auth.json'
    jelly_pattern = f"plugin://{jellycon_plugin}/?item_id={{}}&mode=PLAY"
    jelly_plugin = jellycon_plugin
    plugin_active = True
    icon = xbmcaddon.Addon(jellycon_plugin).getAddonInfo('icon')

elif jellyfin_enabled:
    CACHE_NAME = 'data.json'
    jelly_pattern = f'plugin://{jellyfin_plugin}/?id={{}}&mode=play&server=None'
    jelly_plugin = jellyfin_plugin
    plugin_active = True
    icon = xbmcaddon.Addon(jellyfin_plugin).getAddonInfo('icon')
else:
    CACHE_NAME = '---'
    jelly_pattern = '---'
    jelly_plugin = '---'
    plugin_active = False

JELLYFIN_PATH = f"special://profile/addon_data/{jelly_plugin}/"

# ISO 639-2 (pol/eng) → 2-literowy kod FanFilm
LANG_ISO = {'pol': 'pl', 'pl': 'pl', 'eng': 'en', 'en': 'en'}

# ─── source ──────────────────────────────────────────────────────────────────────

class source:
    ffitem: FFItem

    priority: ClassVar[int] = 1
    language: ClassVar[list[str]] = ['pl', 'en']

    has_sort_order: bool = False
    has_color_identify2: bool = True
    use_premium_color: bool = True

    def __init__(self):
        self.domains = ['jellyfin']
        self.search_url = '{server}/Search/Hints?searchTerm={query}'
        self.item_info_url = '{server}/Items/{ItemID}/PlaybackInfo?userId={User_ID}'
        self.jellyfin_pattern = jelly_pattern
        self.session = requests.session()
        self.cache_status = False
        self.servers = []

    @single_call
    def init(self):
        if not (jellycon_enabled or jellyfin_enabled):
            return
        self.servers = self.get_jellyfin_cache()
        if self.servers:
            fflog('Jellyfin enabled')

    # ── public api ─────────────────────────────────────────────────────────

    def movie(self, imdb: str, title: str, localtitle: str, aliases: list[SourceTitleAlias], year: str) -> str | None:
        self.init()
        try:
            if not self.cache_status:
                return
            url = {'imdb': imdb, 'title': title, 'localtitle': localtitle, 'year': year}
            url = urlencode(url)
            return url
        except Exception:
            return

    def tvshow(self, imdb: str, tvdb: str, tvshowtitle: str, localtvshowtitle: str, aliases: list[SourceTitleAlias], year: str) -> str | None:
        self.init()
        try:
            if not self.cache_status:
                return
            tmdb = self.ffitem.show_item.tmdb_id
            url = {'imdb': imdb, 'tmdb': tmdb, 'tvshowtitle': tvshowtitle, 'localtvshowtitle': localtvshowtitle,
                'year': year, }
            url = urlencode(url)
            return url
        except Exception:
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
            return

    def sources(self, url: str | None, hostDict: list[str], hostprDict: list[str]) -> list[SourceItem]:
        self.init()
        try:
            sources = []
            if url is None:
                return sources
            url = parse_qs(url)
            url = dict([(i, url[i][0]) if url[i] else (i, '') for i in url])

            if not plugin_active:
                return sources

            if not self.cache_status:
                return sources
            for server in self.servers:
                headers = {'X-Emby-Token': server['token'],
                           'content-type': 'application/json'
                           }
                if 'tvshowtitle' in url:
                    titles = [url['tvshowtitle'], url['localtvshowtitle']]
                    try:
                        for title in titles:
                            fflog(f'query: {title!r}')
                            search_url = self.search_url.format(server=server['server_address'],
                                                                query=title)
                            search: SearchResponse = self.session.get(search_url, headers=headers).json()
                            fflog(f'search: {len(search.get("SearchHints", []))} results')
                            hints = search.get('SearchHints')
                            match_id = [hint.get('ItemId') for hint in hints if
                                        (hint.get('ProductionYear') == int(url['year'])
                                        and hint.get('Type') == 'Series'
                                        and hint.get('Name') == title)]
                            if not match_id:
                                continue
                            for series_id in match_id:
                                tvshow_url = f'''{server['server_address']}/Shows/{{}}/Episodes?season={url['season']}'''
                                episodes: EpisodesResponse = self.session.get(tvshow_url.format(series_id), headers=headers).json()
                                if not episodes['Items']:
                                    continue
                                episode_id = [item.get('Id')
                                                 for item in episodes['Items'] if item['IndexNumber'] == int(url['episode'])][0]
                                info_url = self.item_info_url.format(server=server['server_address'],
                                                                     ItemID=episode_id,
                                                                     User_ID=server['user_id'])
                                playback: PlaybackInfo = self.session.get(info_url, headers=headers).json()

                                media_source = playback.get('MediaSources')[0]
                                sources.append(self.build_source(server, media_source, episode_id))
                    except Exception as error:
                        fflog(f'Jellyfin series parse error: {error}')
                        log_exc()
                else:
                    titles = [url['title'], url['localtitle']]
                    for title in titles:
                        fflog(f'query: {title!r}')
                        search_url = self.search_url.format(server=server['server_address'],
                                                            query=title)
                        try:
                            search: SearchResponse = self.session.get(search_url, headers=headers).json()
                            hints = search.get('SearchHints')
                            fflog(f'search: {len(hints or [])} results')
                            match_id = [hint.get('ItemId') for hint in hints if
                                        (hint.get('ProductionYear') == int(url['year'])
                                        and hint.get('Type') == 'Movie'
                                        and hint.get('Name') == title)]
                            if not match_id:
                                continue
                            for movie_id in match_id:
                                info_url = self.item_info_url.format(server=server['server_address'],
                                                                     ItemID=movie_id,
                                                                     User_ID=server['user_id'])
                                playback: PlaybackInfo = self.session.get(info_url, headers=headers).json()
                                media_source = playback.get('MediaSources')[0]
                                sources.append(self.build_source(server, media_source, movie_id))

                        except requests.Timeout:
                            fflog('Jellyfin connection timeout')

        except Exception:
            fflog('Jellyfin source: failed')
            log_exc()
            return

        fflog(f'sources: {len(sources)}')
        return sources

    def resolve(self, url: str) -> str | None:
        return url

    # ── helpers ────────────────────────────────────────────────────────────

    @staticmethod
    def _norm_lang(code: str) -> str:
        code = (code or '').lower()
        return LANG_ISO.get(code, code[:2])

    @staticmethod
    def _video_info(video: MediaStream) -> str:
        if not video:
            return ''
        parts = [(video.get('Codec') or '').upper()]
        video_range = (video.get('VideoRangeType') or video.get('VideoRange') or '').upper()
        if video_range and video_range != 'SDR':
            parts.append('DV' if video_range in ('DOVI', 'DOLBYVISION') else video_range)
        if str(video.get('BitDepth') or '') == '10':
            parts.append('10BIT')
        return ' '.join(part for part in parts if part)

    @staticmethod
    def _audio_info(audio: MediaStream) -> str:
        if not audio:
            return ''
        parts = [(audio.get('Codec') or '').upper()]
        if audio.get('ChannelLayout'):
            parts.append(audio['ChannelLayout'])
        profile = f" {audio.get('Profile') or ''} {audio.get('Title') or ''} ".upper()
        if 'ATMOS' in profile:
            parts.append('ATMOS')
        elif 'DTS-HD MA' in profile or ' MA ' in profile:
            parts.append('HD-MA')
        elif 'DTS-HD' in profile:
            parts.append('HD')
        return ' '.join(part for part in parts if part)

    @staticmethod
    def _subs_info(subs: list[MediaStream]) -> str:
        return 'Napisy' if subs else ''

    def build_source(self, server: JellyServer, media_source: MediaSource, item_id: str) -> SourceItem:
        streams = media_source.get('MediaStreams') or []
        videos = [stream for stream in streams if stream.get('Type') == 'Video']
        audios = [stream for stream in streams if stream.get('Type') == 'Audio']
        subs = [stream for stream in streams if stream.get('Type') == 'Subtitle']
        video = videos[0] if videos else {}
        audio = audios[0] if audios else {}

        # (1) jakość z realnej rozdzielczości, fallback do DisplayTitle
        quality = source_utils.quality_from_resolution(
            int(video.get('Width') or 0), int(video.get('Height') or 0))
        if quality == 'SD':
            quality = source_utils.get_release_quality(video.get('DisplayTitle'))[0] or quality

        # (2) typ tłumaczenia (Dubbing/Lektor) z nazwy; (4) język z audio streams — PL ma priorytet
        lang, lang_type = source_utils.get_lang_by_type(media_source.get('Name') or '')
        audio_langs = [self._norm_lang(stream.get('Language')) for stream in audios if stream.get('Language')]
        if not lang:
            if 'pl' in audio_langs:
                lang = 'pl'
            elif audio_langs:
                lang = audio_langs[0]
        multi_part = 'Multi' if len(set(audio_langs)) > 1 else ''
        info = ' | '.join(dict.fromkeys(part for part in [lang_type, multi_part] if part))

        # (3)+(5)+(6) technikalia, HDR/DV, Atmos/DTS-HD, napisy → info2
        info2 = ' | '.join(part for part in (self._video_info(video), self._audio_info(audio), self._subs_info(subs)) if part)

        return {
            'source': server['server_name'],
            'quality': quality,
            'language': lang,
            'url': self.jellyfin_pattern.format(item_id),
            'info': info,
            'info2': info2,
            'size': convert_size(media_source.get('Size')),
            'direct': True,
            'debridonly': False,
            'icon': icon,
            'premium': True,
        }

    def get_jellyfin_cache(self) -> list[JellyServer] | None:

        cache_path = xbmcvfs.translatePath(JELLYFIN_PATH) or JELLYFIN_PATH
        self.cache_status = False

        try:
            cache = xbmcvfs.File(cache_path + CACHE_NAME)
        except FileNotFoundError:
            fflog('not logged in to Jellycon plugin')
            return None
        cached_data = cache.read()
        cached_data = json.loads(cached_data)
        cache.close()
        if jellycon_enabled:
            try:
                settings = ET.parse(cache_path + 'settings.xml')
                user = settings.find('./setting[@id="username"]').text
                server = settings.find('./setting[@id="server_address"]').text
            except Exception:
                fflog('missing user/server data — check Jellycon settings')
                return None
            cache_data = cached_data.get(user)
            server_name = self.session.get(f'{server}/System/Info',
                                           headers={'X-Emby-Token': cache_data['token']}).json()
            cache_data.update({'server_address': server,
                               'server_name': server_name['ServerName']})
            cache_data = [cache_data]
        elif jellyfin_enabled:
            try:
                cache_data = [{'server_address': i['address'],
                               'token': i['AccessToken'],
                               'user_id': i['UserId'],
                               'server_name': i['Name']}
                              for i in cached_data['Servers'] if 'AccessToken' in i]
            except IndexError:
                print('missing user/server data — check Jellyfin settings')
                return None
        self.cache_status = True
        return cache_data

