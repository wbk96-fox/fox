# -*- coding: utf-8 -*-
"""
FanFilm - źródło: webstreamr.hayd.uk
Copyright (C) 2026 :)

Dystrybuowane na licencji MIT <https://mit-license.org>

And if you absolutely must borrow the scraper, remember that it cost us over a month of work...
It would be a good idea to mention this in your version where you are borrowing from,
in accordance with the license.
"""

from __future__ import annotations

import re
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import TYPE_CHECKING, Dict, List, Optional, ClassVar
from urllib.parse import urlencode, parse_qs

from lib.ff import requests, source_utils

if TYPE_CHECKING:
    from lib.sources import SourceItem, SourceTitleAlias
from lib.ff.item import FFItem
from lib.ff.log_utils import fflog, fflog_exc
from lib.ff.source_utils import DEFAULT_UA, append_headers, convert_size, get_host, get_quality
from lib.api import kitsu as kitsu_api


# Public webstreamr instances — add new ones here as they appear.
# 'main' beamup deployment (webstreamrmbg.baby-beamup.club) returns 0 streams for
# every title (stale deploy); '-dev' branch build works, so we use that instead.
# hdhub.thevolecitor.qzz.io is a separate Stremio addon (HdHub), kept because it
# speaks the same stream/{type}/{id}.json protocol and actually returns results.
_WEBSTREAMR_INSTANCES = [
    'https://87d6a6ef6b58-webstreamrmbg-dev.baby-beamup.club',
    'https://hdhub.thevolecitor.qzz.io',
]

_HEADERS = {'User-Agent': DEFAULT_UA, 'Accept': 'application/json'}

# Shared so repeated calls to the same instance reuse one TCP/TLS connection
# instead of each opening and tearing down its own (lib.ff.requests.get() does
# that per call otherwise). Session objects are thread-safe for concurrent use.
_session = requests.Session()

_RE_VIDEO = re.compile(r'\.(mkv|mp4|avi|ts|m4v)$', re.IGNORECASE)
_RE_HOSTER = re.compile(r'🔗\s*([^\n\r(]+)')

# Storage/CDN backendy z losowymi subdomenami → krótka, czytelna nazwa hostera
_HOST_ALIASES = (
    ('.r2.dev', 'R2'),
    ('.workers.dev', 'Workers'),
    ('.b-cdn.net', 'Bunny'),
)
# losowo wyglądająca etykieta subdomeny, np. 'pub-67769310809f40c4ba5a959a9b56152d'
_RE_RANDOM_LABEL = re.compile(r'[0-9a-f]{12,}', re.IGNORECASE)

# ─── source ──────────────────────────────────────────────────────────────────────

class source:
    ffitem: FFItem

    priority: ClassVar[int] = 1
    language: ClassVar[List[str]] = ['en']

    def __init__(self):
        self.domains = ['1corncastle.vercel.app']

    # ── public api ─────────────────────────────────────────────────────────

    def movie(self, imdb: str, title: str, localtitle: str, aliases: list[SourceTitleAlias], year: str) -> Optional[str]:
        return urlencode({'imdb': imdb, 'type': 'movie'}) if imdb else None

    def tvshow(self, imdb: str, tvdb: str, tvshowtitle: str, localtvshowtitle: str, aliases: list[SourceTitleAlias], year: str) -> Optional[str]:
        return urlencode({'imdb': imdb, 'type': 'tv'}) if imdb else None

    def episode(self, url: Optional[str], imdb: str, tvdb: str, title: str, premiered: str, season: str, episode: str) -> Optional[str]:
        try:
            if url is None:
                return None
            data = {k: v[0] for k, v in parse_qs(url).items()}

            if source_utils.is_anime(self.ffitem):
                tmdb = self.ffitem.show_item.tmdb_id if self.ffitem.show_item else None
                cours = kitsu_api.get_cour_structure(tmdb) if tmdb else None
                if cours:
                    cour, local_ep = kitsu_api.resolve_cour(cours, self.ffitem)
                    if cour and local_ep:
                        cour_season = cours.index(cour) + 1
                        fflog(
                            f"corncastle: anime {season}x{episode} → kitsu cour={cour['title']} (season={cour_season}) local_ep={local_ep}")
                        season = str(cour_season)
                        episode = str(local_ep)
                    else:
                        fflog(f'corncastle: anime {season}x{episode} – kitsu: no cour, using as-is')
                else:
                    fflog(f'corncastle: anime {season}x{episode} – no kitsu (tmdb={tmdb}), using as-is')

            data.update({'season': season, 'episode': episode})
            return urlencode(data)
        except Exception:
            fflog_exc()
            return None

    def sources(self, url: Optional[str], hostDict: List[str], hostprDict: List[str]) -> List[SourceItem]:
        result = []
        try:
            if not url:
                return result

            data = {k: v[0] for k, v in parse_qs(url).items()}
            imdb = data.get('imdb', '')
            if not imdb:
                return result

            if data.get('type') == 'tv':
                path = f"stream/series/{imdb}:{data.get('season', '1')}:{data.get('episode', '1')}.json"
            else:
                path = f"stream/movie/{imdb}.json"

            fflog(f'query: {imdb!r}')
            streams = []
            executor = ThreadPoolExecutor(max_workers=len(_WEBSTREAMR_INSTANCES))
            try:
                futures = [executor.submit(self._fetch_from_instance, inst, path, _HEADERS)
                           for inst in _WEBSTREAMR_INSTANCES]
                for future in as_completed(futures, timeout=30):
                    try:
                        instance_streams = future.result()
                        streams.extend(instance_streams)
                    except Exception:
                        fflog_exc()
            except TimeoutError:
                fflog('parallel fetch timeout')
            finally:
                executor.shutdown(wait=False)

            result = [src for s, inst in streams if (src := self._stream_to_source(s, inst))]

            # Deduplicate by URL
            seen_urls = set()
            unique_result = []
            for src in result:
                url = src.get('url', '')
                if url not in seen_urls:
                    seen_urls.add(url)
                    unique_result.append(src)
            result = unique_result

        except Exception:
            fflog_exc()

        fflog(f'sources: {len(result)}')
        return result

    def resolve(self, url: str) -> Optional[str]:
        return url

    # ── helpers ────────────────────────────────────────────────────────────

    @staticmethod
    def _fetch_from_instance(instance: str, path: str, headers: dict) -> List[tuple]:
        try:
            api_url = f"{instance}/{path}"
            resp = _session.get(api_url, headers=headers, timeout=15)
            if resp.status_code != 200:
                fflog(f"HTTP {resp.status_code}, skipping {instance}")
                return []
            instance_streams = resp.json().get('streams', [])
            fflog(f'search: {len(instance_streams)} results from {instance}')
            return [(s, instance) for s in instance_streams]
        except Exception:
            fflog(f"instance {instance} failed, continuing")
            return []

    @staticmethod
    def _parse_language(binge_group: str) -> tuple[str, str]:
        # bingeGroup format: "webstreamr-{source}-{hoster}-{lang1}_{lang2}_..."
        # languages are after the last '-', separated by '_'
        try:
            langs = binge_group.rsplit('-', 1)[-1].split('_')
            if 'pl' in langs:
                return 'pl', ''
            if 'ja' in langs:
                return 'en', 'JP'
        except Exception:
            pass
        return 'en', ''

    @staticmethod
    def _parse_hoster(info_line: str, url: str) -> str:
        if 'pixeldrain' in url.lower():
            return 'PixelDrain'
        if '.m3u8' in url:
            return 'VixSrc'
        m = _RE_HOSTER.search(info_line)
        if m:
            return m.group(1).strip().split(' from ')[0].strip()
        return source._pretty_host(url)

    @staticmethod
    def _pretty_host(url: str) -> str:
        host = get_host(url)
        for suffix, name in _HOST_ALIASES:
            if host.endswith(suffix):
                return name
        # generyczny fallback: wytnij losowo wyglądającą subdomenę (np. 'pub-<hex>')
        labels = host.split('.')
        if len(labels) > 2 and _RE_RANDOM_LABEL.search(labels[0]):
            host = '.'.join(labels[1:])
        return host

    @staticmethod
    def _stream_to_source(stream: dict, instance: str = None) -> dict | None:
        url = stream.get('url', '')
        if not re.match(r'https?://', url.lower()):
            return None

        # 4KHDHub "Server" variants are .mkv.zip archives — Kodi can't unpack in-flight
        if url.lower().split('?', 1)[0].endswith('.zip'):
            return None

        # HubCloud (via /extract/) never plays: the extractor lands on gamerxyt.com's
        # "Link Generator" HTML gateway (real download URL is only revealed client-side
        # by JS, in a `link=` query param, after a fake countdown) instead of the final
        # file — and even that final URL (video-downloads.googleusercontent.com) ignores
        # Range requests (always 200, never 206), so it wouldn't be seekable anyway.
        if 'hubcloud' in url.lower():
            return None

        # R2 signed URLs (r2.cloudflarestorage.com) from this webstreamr instance come
        # back 403 Forbidden by the time Kodi opens them (expired/invalid signature) —
        # unlike the public r2.dev bucket links, which work fine.
        if 'r2.cloudflarestorage.com' in url.lower():
            return None

        # WebStreamrMBG bug: extract URLs have truncated hostname without .baby-beamup.club
        if instance and 'baby-beamup' in instance:
            _host = instance.split('://', 1)[1]
            _short = _host.split('.', 1)[0]
            if f'://{_short}/' in url and f'://{_host}/' not in url:
                url = url.replace(f'://{_short}/', f'://{_host}/', 1)

        name = stream.get('name', '')
        title = stream.get('title', '')
        description = stream.get('description', '')
        hints = stream.get('behaviorHints', {})

        if 'download only' in description.lower():
            return None

        # WebStreamrMBG: title = "Filename.mkv\n💾 14.69 GB 🔗 HubCloud (FSL) from 4KHDHub"
        # HDHub: description = "[FSL] [...] \nRemux HDR DV | Hindi\nFSL | 4KHDHub"
        info_text = title or description
        filename_part, _, info_line = info_text.partition('\n')

        filename_part = re.sub(r'\[💾[^\]]*\]\s*', '', filename_part)
        filename_part = re.sub(r'^\s*(?:\[[^\]]*\]\s*)+', '', filename_part)

        # For HDHub: enhance filename parsing from description
        if not title and description:
            # Try to extract video quality/format from description
            # e.g. "4KHDHub 4K" or "[Castle] 1080p"
            if not filename_part.endswith(('.mkv', '.mp4', '.avi', '.ts', '.m4v')):
                # Use name as filename hint for HDHub
                filename_part = name if name else filename_part

        binge_group = hints.get('bingeGroup', '')
        language, lang_info = source._parse_language(binge_group)
        if language != 'pl':
            filename_part = re.sub(r'\.(?:Multi|MULTI|multi)\.', '.', filename_part)
        # Extract filename up to video extension. Tolerate trailing markers WebStreamrMBG
        # appends like "⚠️ no seek" — original anchored _RE_VIDEO ($) would miss those.
        m = re.search(r'(.+?\.(?:mkv|mp4|avi|ts|m4v))\b', filename_part.strip(), re.IGNORECASE)
        filename = m.group(1) if m else ''

        proxy_headers = hints.get('proxyHeaders', {}).get('request', {})
        referer = proxy_headers.get('Referer')
        user_agent = proxy_headers.get('User-Agent', DEFAULT_UA)

        # Only append headers for direct file URLs, not for webstreamr extractor URLs
        # (extractor URLs use referer server-side, not in the player)
        is_direct = '/extract/' not in url
        headers = {'User-Agent': user_agent}
        if referer:
            headers['Referer'] = referer
        play_url = url + append_headers(headers) if is_direct else url

        video_size = hints.get('videoSize')
        if not video_size:
            return None

        # Use description as filename (contains all metadata: title, quality, format, audio)
        if not filename and description:
            lines = description.split('\n')
            # An EN track bundled with Hindi (or any other non-Western dub) is still
            # just EN for a PL/EN audience - drop both markers so sources.py's shared
            # "MULTI" auto-detection (which scans this text) doesn't misfire and
            # relabel the source as multi-language.
            if language == 'en':
                lines = [re.sub(r'\bHindi\b|\bMulti\b', '', line, flags=re.IGNORECASE).strip() for line in lines]
            # Remove trailing pipes and clean up empty lines
            lines = [re.sub(r'[|\s]+$', '', line).strip() for line in lines]
            filename = ' | '.join([line for line in lines if line])
            filename = re.sub(r'\[💾[^\]]*\]\s*', '', filename)
            filename = re.sub(r'^\s*(?:\[[^\]]*\]\s*)+', '', filename)
            # Fix incomplete brackets (e.g., "[ DDP 2.0" without closing bracket)
            filename = re.sub(r'\[\s+(?!.*\])', '', filename)  # Remove [ that don't have closing ]
            filename = re.sub(r'\s+', ' ', filename)  # Normalize multiple spaces
            filename = filename[:200]

        # Quality from name's last token or full filename. The last token of the name field
        # is the resolution marker (e.g. "4KHDHub 720p" → "720p", "WebStreamrMBG\n…\n2160p"
        # → "2160p"). Using the full name would cause "4KHDHub 720p" to match RES_4K via the
        # "4k" substring in "4khdhub" before reaching "720p".
        _name_clean = (name or '').replace('\n', ' ').strip()
        _name_last = _name_clean.split()[-1] if _name_clean else ''
        quality = get_quality(_name_last or _name_clean or filename or '')

        # Filter out low quality sources (360p, 480p, SD) — they don't work reliably
        if quality == 'SD':
            return None

        # Hoster: use parsed, fallback to 'Unknown' if empty
        hoster = source._parse_hoster(info_line, url)
        if not hoster:
            hoster = 'Unknown'

        return {
            'source': hoster,
            'url': play_url,
            'quality': quality,
            'language': language,
            'info': lang_info,
            'size': convert_size(video_size),
            'filename': filename,
            'direct': True,
            'debridonly': False,
            'premium': False,
        }
