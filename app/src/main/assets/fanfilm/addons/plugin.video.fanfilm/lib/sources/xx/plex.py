# -*- coding: utf-8 -*-
"""
FanFilm - źródło: plex
Copyright (C) 2026 :)

Dystrybuowane na licencji MIT <https://mit-license.org>
Requires the composite plugin from the Kodi repository
"""

from __future__ import annotations

import pickle
import uuid
import xml.etree.ElementTree as ET
from typing import TYPE_CHECKING, Any, ClassVar
from urllib.parse import parse_qs, quote, urlencode

from lib.ff import requests
from lib.sources import single_call
import xbmcaddon
import xbmcvfs

from lib.ff import control, source_utils
from lib.ff.item import FFItem
from lib.ff.log_utils import fflog

if TYPE_CHECKING:
    from lib.sources import SourceItem, SourceTitleAlias

composite_plugin = 'plugin.video.composite_for_plex'
composite_enabled = control.condVisibility('System.AddonIsEnabled(%s)' % composite_plugin)
if composite_enabled:
    COMPOSITE_ADDON = xbmcaddon.Addon(id=composite_plugin)
    icon = xbmcaddon.Addon(composite_plugin).getAddonInfo('icon')
COMPOSITE_PATH = f"special://profile/addon_data/{composite_plugin}/cache/servers/"
CACHE_NAME = 'plexhome_user.pcache'

_LANG_ISO = {'pol': 'pl', 'pla': 'pl', 'pl': 'pl', 'eng': 'en', 'en': 'en'}


def _norm_lang(code: str) -> str:
    code = (code or '').lower()
    return _LANG_ISO.get(code, code[:2] if len(code) >= 2 else code)


def get_composite_cache(cache=''):
    cache_path = xbmcvfs.translatePath(COMPOSITE_PATH)
    try:
        cache = xbmcvfs.File(cache_path + CACHE_NAME)
    except FileNotFoundError:
        return False, None

    try:
        cache_data = cache.readBytes()
    except Exception as error:
        print(f"CACHE [{cache}]: read error [{error}]")
        cache_data = False
    finally:
        cache.close()
    if cache_data:
        if isinstance(cache_data, str):
            cache_data = cache_data.encode('utf-8')
        print(f"CACHE [{cache}]: read")
        try:
            cache_object = pickle.loads(cache_data)
        except (ValueError, TypeError):
            return False, None
        return True, cache_object

# ─── source ──────────────────────────────────────────────────────────────────────

class source:
    ffitem: FFItem

    priority: ClassVar[int] = 1
    language: ClassVar[list[str]] = ['pl', 'en']

    has_color_identify2: bool = True
    use_premium_color: bool = True

    def __init__(self):
        self.domains = ['plex.tv']
        self.base_link = 'https://plex.tv'
        self.server_url = '{scheme}://{IP}:{port}{path}'
        self.search_link = '{scheme}://{IP}:{port}/{path}?query={query}'
        self.plex_API = self.base_link + '//api/resources?includeHttps=1'
        self.composite_pattern = (
            'plugin://plugin.video.composite_for_plex/?url={uri}{key}&mode=5'
        )
        self.session = requests.session()
        self.UUID = str(uuid.uuid4())
        self.cache_status = False
        self.servers = []

    @single_call
    def init(self):
        if not composite_enabled:
            return

        cache_data = get_composite_cache(CACHE_NAME)
        if cache_data is None:
            return

        self.cache_status, self.cache_token = cache_data
        if not self.cache_status:
            return
        self.token = self.cache_token['myplex_user_cache'].split('|')[1]
        self.headers = {
            'X-Plex-Client-Identifier': self.UUID,
            'X-Plex-Product': COMPOSITE_ADDON.getAddonInfo('name'),
            'X-Plex-Token': self.token,
        }

        r = self.session.get(self.plex_API, headers=self.headers)
        server_list = ET.fromstring(r.text)
        devices = server_list.iter('Device')
        for device in devices:
            try:
                server = {}
                server['name'] = device.get('name')
                server['accessToken'] = device.get('accessToken')
                # zachowaj wszystkie połączenia, lokalne (LAN) najpierw
                conns = [c.attrib for c in device.findall('./Connection')]
                conns.sort(key=lambda c: c.get('local') != '1')
                server['connections'] = conns
                self.servers.append(server)
            except Exception:
                continue

        fflog(f"Plex enabled - found: {len(self.servers)} servers")

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
            url = {
                'imdb': imdb,
                'tmdb': tmdb,
                'tvshowtitle': tvshowtitle,
                'localtvshowtitle': localtvshowtitle,
                'year': year,
            }
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
            url['title'], url['premiered'], url['season'], url['episode'] = (
                title,
                premiered,
                season,
                episode,
            )
            url = urlencode(url)
            return url
        except Exception:
            return

    def sources(self, url: str | None, hostDict: list[str], hostprDict: list[str]) -> list[SourceItem]:
        self.init()
        sources = []
        try:
            if url is None:
                return sources
            url = parse_qs(url)
            url = dict([(i, url[i][0]) if url[i] else (i, '') for i in url])

            if not composite_enabled or not self.cache_status:
                return sources

            for server in self.servers:
                if not self.select_connection(server):
                    continue
                self.headers['X-Plex-Token'] = server['accessToken']
                try:
                    if 'tvshowtitle' in url:
                        sources.extend(self._sources_episode(server, url))
                    else:
                        sources.extend(self._sources_movie(server, url))
                except Exception as error:
                    fflog(f'Plex parse error: {error}')
        except Exception as error:
            fflog(f'Plex source error: {error}')
            return sources

        # deduplikacja - ten sam plik bywa zwracany kilka razy (różne biblioteki/huby)
        seen = set()
        sources = [source for source in sources if not (source['url'] in seen or seen.add(source['url']))]

        fflog(f'sources: {len(sources)}')
        return sources

    def resolve(self, url: str) -> str | None:
        return url

    # ── wyszukiwanie ───────────────────────────────────────────────────────

    def _sources_movie(self, server, url) -> list[SourceItem]:
        results = []
        keys = []
        for title in self._title_variants(url['title'], url['localtitle']):
            root = self._search(server, title)
            if root is None:
                continue
            movies = root.findall(f'./Video[@type="movie"][@year="{url["year"]}"]')
            fflog(f'search: {len(movies)} results')
            keys.extend(movie.get('key') for movie in movies)

        for metadata_key in dict.fromkeys(keys):
            video = self._fetch_metadata(server, metadata_key)
            if video is None:
                continue
            results.append(self._build_source(server, video))
        return results

    def _sources_episode(self, server, url) -> list[SourceItem]:
        results = []
        shows = []
        for title in self._title_variants(url['tvshowtitle'], url['localtvshowtitle']):
            root = self._search(server, title)
            if root is None:
                continue
            shows = [directory.get('key') for directory in root.findall('./Directory')]
            fflog(f'search: {len(shows)} results')
            if shows:
                break

        episodes_path = None
        for show in shows:
            seasons = self._get(server, show)
            if seasons is None:
                continue
            season = seasons.find(f'./Directory[@title="Season {url["season"]}"]')
            if season is None:
                fflog(f"season {url['season']} not found")
                continue
            episodes_path = season.get('key')

        if episodes_path is None:
            return results

        episode_list = self._get(server, episodes_path)
        if episode_list is None:
            return results

        episodes = episode_list.findall(
            f'./Video[@type="episode"][@index="{url["episode"]}"]'
            f'[@parentTitle="Season {url["season"]}"]'
        )
        for episode in episodes:
            video = self._fetch_metadata(server, episode.get('key'))
            if video is None:
                continue
            results.append(self._build_source(server, video))
        return results

    # ── helpers ────────────────────────────────────────────────────────────

    def _title_variants(self, primary: str, secondary: str) -> list[str]:
        variants = [primary, secondary]
        return list(dict.fromkeys(variant for variant in variants if variant))

    def select_connection(self, server) -> bool:
        """Wstaw do server pierwsze osiągalne połączenie (lokalne najpierw),
        żeby reszta sources() działała bez zmian. Zwraca False, gdy żadne nie odpowiada."""
        for conn in server['connections']:
            try:
                test = '{protocol}://{address}:{port}/'.format(**conn)
                self.session.get(test, headers={'X-Plex-Token': server['accessToken']},
                                 verify=False, timeout=3)
                server.update(conn)
                return True
            except Exception:
                continue
        return False

    def _search(self, server, title: str) -> ET.Element | None:
        fflog(f'query: {title!r}')
        build_url = self.search_link.format(
            scheme=server['protocol'], IP=server['address'],
            port=server['port'], path='search', query=quote(title))
        try:
            response = self.session.get(build_url, headers=self.headers, verify=False, timeout=3)
            return ET.fromstring(response.text)
        except requests.Timeout:
            fflog(f"Plex connection timeout for server: {server['name']}")
        except Exception as error:
            fflog(f'Plex search error: {error}')
        return None

    def _get(self, server, path) -> ET.Element | None:
        detail_url = self.server_url.format(
            scheme=server['protocol'], IP=server['address'],
            port=server['port'], path=path)
        try:
            response = self.session.get(detail_url, headers=self.headers, verify=False, timeout=3)
            return ET.fromstring(response.text)
        except Exception as error:
            fflog(f'Plex GET error [{path}]: {error}')
            return None

    def _fetch_metadata(self, server, metadata_key) -> ET.Element | None:
        """Pobierz /library/metadata/<id> - dokładnie ten zasób, którego używa
        Composite przy odtwarzaniu - i zwróć <Video>, gdy item jest grywalny.
        Composite wywala się (IndexError, playback.py:661: parts[media_index]),
        gdy któraś <Media> nie ma <Part>; takie itemy odrzucamy (None), żeby nie
        trafiły na listę źródeł."""
        detail_url = self.server_url.format(
            scheme=server['protocol'], IP=server['address'],
            port=server['port'], path=metadata_key)
        try:
            response = self.session.get(detail_url, headers=self.headers, verify=False, timeout=3)
        except Exception as error:
            fflog(f'metadata fetch failed [{metadata_key}]: {error}')
            return None

        try:
            video = ET.fromstring(response.text).find('./Video')
        except Exception as error:
            fflog(f'metadata parse failed [{metadata_key}]: {error}')
            return None
        if video is None:
            fflog(f'metadata [{metadata_key}]: brak <Video> w odpowiedzi - pomijam')
            return None

        medias = video.findall('./Media')

        # >>> TEMP DEBUG XML — USUNĄĆ przed wydaniem (zrzut samych <Media> do logu) >>>
        media_xml = ''.join(ET.tostring(media, encoding='unicode') for media in medias)
        fflog('\n===== PLEX XML DUMP [{key}] {title!r} =====\n{xml}===== /PLEX XML DUMP ====='.format(
            key=metadata_key, title=video.get('title'), xml=media_xml))
        # <<< TEMP DEBUG XML — USUNĄĆ przed wydaniem <<<

        # struktura każdego itemu - żeby wyłapać też kształty inne niż "brak <Part>"
        media_summary = [
            {
                'height': media.get('height'),
                'part': part is not None,
                'size': (part.get('size') if part is not None else None),
                'file': (part.get('file') if part is not None else None),
            }
            for media in medias
            for part in (media.find('./Part'),)
        ]
        fflog(f'metadata [{metadata_key}]: media={media_summary}')

        if not medias:
            fflog(f'metadata [{metadata_key}]: brak <Media> - pomijam')
            return None
        if not all(media.find('./Part') is not None for media in medias):
            fflog(f'metadata [{metadata_key}]: <Media> bez <Part> (wywala Composite) - pomijam')
            return None
        return video

    def _build_source(self, server, video) -> SourceItem:
        src = self.parse_source_data(server, video)
        return {
            'source': src['name'],
            'quality': src['quality'],
            'language': src['language'],
            'url': self.composite_pattern.format(uri=src['uri'], key=src['key']),
            'info': src['lang_type'],
            'info2': src['videoinfo'] + ' | ' + src['audioinfo'],
            'size': src['size'],
            'filename': src['file'],
            'direct': True,
            'debridonly': False,
            'icon': icon,
            'premium': True,
        }

    def parse_source_data(self, server, xml) -> dict[str, Any]:
        src = server
        src['key'] = xml.get('key')
        # przy wielu wersjach (<Media>) bierz najwyższą rozdzielczość
        media = max(xml.findall('./Media'), key=lambda m: int(m.get('height') or 0))
        src.update(media.attrib)
        part = media.find('./Part')
        file = (part.get('file') if part is not None else None) or ''
        src['file'] = file.split('/')[-1]
        size = part.get('size') if part is not None else None
        src['size'] = source_utils.convert_size(int(size)) if size else ''
        # jakość z realnej rozdzielczości - videoResolution Plexa bywa zawyżony (np. "4k" dla 1080p)
        src['quality'] = source_utils.quality_from_resolution(
            int(src.get('width') or 0), int(src.get('height') or 0))
        src['videoinfo'] = (
            src['videoCodec'] + ' ' + src['videoFrameRate'] + ' ' + src['container']
        )
        # layout audio (np. "5.1") z wybranego strumienia jest czytelniejszy niż surowe audioChannels ("6CH")
        audio_streams = part.findall('./Stream[@streamType="2"]') if part is not None else []
        selected_streams = [stream for stream in audio_streams if stream.get('selected') == '1']
        audio_stream = (selected_streams or audio_streams or [None])[0]
        layout = (audio_stream.get('audioChannelLayout') if audio_stream is not None else '') or ''
        layout = layout.split('(')[0].strip()
        channels = layout if layout else (src.get('audioChannels', '') + 'CH')
        src['audioinfo'] = src['audioCodec'] + ' ' + channels + ' '

        # Język z audio streams — PL ma priorytet; fallback do nazwy pliku
        _, lang_type = source_utils.get_lang_by_type(src['file'])
        audio_lang_codes = [
            _norm_lang(s.get('languageCode') or '')
            for s in audio_streams
            if s.get('languageCode')
        ]
        if audio_lang_codes:
            lang = 'pl' if 'pl' in audio_lang_codes else audio_lang_codes[0]
            multi_part = 'Multi' if len(set(audio_lang_codes)) > 1 else ''
        else:
            lang, lang_type = source_utils.get_lang_by_type(src['file'])
            multi_part = ''
        src['language'] = lang
        src['lang_type'] = ' | '.join(part for part in [lang_type, multi_part] if part)

        return src
