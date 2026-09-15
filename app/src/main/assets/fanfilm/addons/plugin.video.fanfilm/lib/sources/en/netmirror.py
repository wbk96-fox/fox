# -*- coding: utf-8 -*-
"""
FanFilm – source: netmirror (net52.cc)
Copyright (C) 2026 :)

Dystrybuowane na licencji MIT <https://mit-license.org>

Search/metadata via the same newtv API as playback (search.php, post.php,
episodes.php) - no auth, self-heals via checknewtv.php. search.php has no
year/kind though, so candidates get confirmed via post.php - see _fetch_post_meta.

Playback via tv.imgcdn.kim/newtv/player.php now requires a usertoken header -
without it the site answers status "otp" and hands back a decoy clip (a
thumbnail-sprite m3u8 dressed up with real-looking audio tracks/resolution)
instead of the real stream. A usertoken is minted via newtv/otp.php using a
bypass OTP code that NetMirror itself publishes at netmirror.gg/tv (rendered
client-side as `const otp = [...]`) - this used to be the fixed "111111" test
code baked into the official app, but that stopped being accepted server-side;
the netmirror.gg/tv page is the current live source of the working code, so it
is scraped fresh rather than hardcoded. Cloudflare sometimes challenges that
page outright (no bypass short of running real JS), in which case the code
falls back to whatever the user pasted into the "netmirror.otp_code" setting.
See _fetch_otp_code / _otp_bypass_token.

NEW (faster, no auth): net27.cc/api/embed-tmdb/{tmdbId} returns ready signed
CDN mp4 links with a plain unauthenticated GET, no verify.php/OTP needed. Tried
in parallel with the flow above and merged into its results - useful both as a
speed-up and as a fallback for when the OTP flow is unavailable (e.g. netmirror.gg/tv
itself is Cloudflare-blocked and no manual code is set). Its catalogue is
younger/less complete, and it silently ignores season/episode query params
(always answers with S1E1), so a TV response is only trusted when it self-reports
the season/episode actually requested. See _nm27_sources.
"""

from __future__ import annotations

import base64
import hashlib
import json
import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from html import unescape
from typing import TYPE_CHECKING, Callable, ClassVar, Literal, cast
from typing_extensions import NotRequired, TypeAlias, TypedDict
from urllib.parse import parse_qs, urljoin

from const import const
from lib.ff import cache, cleantitle, control, requests, source_utils
from lib.ff.settings import settings
from lib.ff.source_utils import CHROME_UA
from lib.ff.item import FFItem
from lib.ff.resolve_utils import build_isa_url
from lib.ff.log_utils import fflog, fflog_exc
from lib.kolang import L
from lib.service.client import service_client

if TYPE_CHECKING:
    from lib.sources import SourceItem, SourceTitleAlias


# ─── Types ───────────────────────────────────────────────────────────────────────

Ott: TypeAlias = "Literal['pv', 'nf', 'hs']"
Quality: TypeAlias = "Literal['4K', '1080p', '720p', 'SD']"


class Caption(TypedDict):
    file: str
    label: str
    code: str


class SearchHit(TypedDict):          # search.php → searchResult[] (no year/kind - see _fetch_post_meta)
    id: str
    t: str


class SeasonEntry(TypedDict):        # post.php → season[], e.g. "Season 1 (8 EP)"
    s: str
    id: str


class EpisodeData(TypedDict):        # episodes.php → episodes[]
    id: str
    t: str
    ep: str                          # bare episode number, e.g. "1"


class ShowResponse(TypedDict):       # post.php
    status: str                      # "ok" on success
    year: NotRequired[str]
    type: NotRequired[str]           # "m" movie, "t" series
    season: NotRequired[list[SeasonEntry]]


class EpisodesResponse(TypedDict):   # episodes.php
    episodes: NotRequired[list[EpisodeData]]
    nextPageShow: NotRequired[int]


# ─── Constants ───────────────────────────────────────────────────────────────────

_BASE = 'https://net52.cc'
_NEWTV_BASE = 'https://tv.imgcdn.kim'
_NEWTV_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0'
# NetMirror itself publishes a fresh bypass OTP on this page (rendered client-side
# as `const otp = [d,d,d,d,d,d]`) - see _fetch_otp_code. The old fixed "111111"
# test code stopped being accepted server-side; this page is the current source
# of truth, so the code is fetched live instead of hardcoded again.
_OTP_CODE_URL = 'https://netmirror.gg/tv'

# TMDB-keyed REST API, see module docstring. CDN links 429 without this Referer.
_NM27_BASE = 'https://net27.cc'
_NM27_REFERER = 'https://videodownloader.site/'

# Multi-platform OTT endpoints - PV first (FanFilm = Prime Video addon).
# Disney+ content is already reachable via 'hs' (Disney+ Hotstar), so a separate
# 'dp' ott is intentionally omitted - it would only add per-query requests.
_OT_PLATFORMS: list[tuple[Ott, str]] = [
    ('pv', 'pv/'),
    ('nf', ''),
    ('hs', 'hs/'),
]

# Mobiledetect domains for dynamic NewTV API base discovery (checknewtv.php).
# tv.imgcdn.kim rotates; the live base is whatever checknewtv.php hands back.
# Only the live mirrors are listed - the other ~20 documented domains all
# time out, and probing them would stall discovery for minutes on failure.
_NEWTV_DOMAINS: list[str] = [
    'mobiledetects.com', 'mobiledetect.app', 'mobidetect.art', 'mobidetect.cc',
]

# Best → worst, for ordering emitted sources. Quality is bucketed from the
# actual stream dimensions (pv serves non-standard crops like 1920x800), not a
# fixed resolution lookup — see source._quality_label.
_QUALITY_ORDER: list[Quality] = ['4K', '1080p', '720p', 'SD']

_AUDIO_NOISE = frozenset({'', 'und'})

# Non-Western dubs — for a PL/EN audience an English track plus these is
# still just an English source, not a meaningful MULTI.
_NON_WESTERN_LANGS = frozenset({
    'hin', 'hindi', 'tam', 'tamil', 'tel', 'telugu', 'kan', 'kannada',
    'mal', 'malayalam', 'ben', 'bengali', 'mar', 'marathi', 'pan', 'punjabi',
    'guj', 'gujarati', 'urd', 'urdu', 'ori', 'odia', 'asm', 'assamese', 'bho',
    'ind', 'tha', 'vie', 'fil', 'msa', 'mya', 'khm', 'lao',
    'jpn', 'kor', 'zho', 'chi',
    'ara', 'tur', 'fas', 'heb',
})

# One audio track exposes its language up to three ways (LANGUAGE, NAME, URI
# path) - collapse the synonyms to a single canonical code so a lone track is
# never mistaken for MULTI.
_AUDIO_LANG_MAP = {
    'pol': 'pl', 'pl': 'pl', 'polski': 'pl', 'polish': 'pl',
    'eng': 'en', 'en': 'en', 'english': 'en',
}


# ─── Caches ──────────────────────────────────────────────────────────────────────

# Shared so repeat calls reuse one connection instead of a fresh TCP+TLS handshake
# each time (lib.ff.requests.get() does that per call otherwise); thread-safe.
_session = requests.Session()

# content_id:newtv:ott -> (cached_at, master_url, master_text, captions, referer)
_master_cache: dict[str, tuple[float, str, str, list[Caption], str]] = {}
_MASTER_TTL = 300  # 5 min — signed master URLs expire fast


def _store_master(key: str, entry: tuple[float, str, str, list[Caption], str]) -> None:
    """Cache a master, evicting whatever already outlived the TTL."""
    _master_cache[key] = entry
    expired = [cached_key for cached_key, cached_entry in _master_cache.items()
               if entry[0] - cached_entry[0] >= _MASTER_TTL]
    for stale_key in expired:
        _master_cache.pop(stale_key, None)  # pop() so parallel writers can't KeyError


# Resolved NewTV API base (tv.imgcdn.kim rotates); also disk-cached (see
# _resolve_newtv_base) so it survives a fresh Kodi process.
_newtv_base = ''
_newtv_base_ts = 0.0
_NEWTV_BASE_TTL = 43200  # 12h

# Cached usertoken (name -> (value, fetched_at)) for player.php - see _otp_bypass_token.
_token_cache: dict[str, tuple[str, float]] = {}
_token_cache_lock = threading.Lock()
# Official app re-mints every 24h (per its own source) - match that, not 30d.
_OTP_TOKEN_TTL = 20 * 3600  # 20h

# Spaces out requests to the resolved newtv base so a burst on the OTP retry
# path (up to 3 ott threads x 2 calls each - otp.php + player.php - within a
# split second) doesn't trip the backend's anti-abuse rate limit.
_NEWTV_MIN_INTERVAL = 1.2
_newtv_throttle_lock = threading.Lock()
_newtv_last_call = 0.0


def _throttle_newtv() -> None:
    global _newtv_last_call
    with _newtv_throttle_lock:
        wait = _NEWTV_MIN_INTERVAL - (time.time() - _newtv_last_call)
        if wait > 0:
            time.sleep(wait)
        _newtv_last_call = time.time()


# fetch() runs once per ott (pv/nf/hs) on a force-refresh, all serialized under
# _token_cache_lock - dedup so a single dead code doesn't pop the same "get a
# fresh code" notification 2-3x back to back (which some skins render badly).
def _notify_otp_failure() -> None:
    control.groupedInfoDialog(
        'netmirror_otp',
        L(32850, "Refresh the OTP code in settings."),
        heading=L(30603, "NetMirror OTP code"),
        icon="ERROR",
    )


def _cached_token(name: str, ttl: float, fetch: Callable[[], str | None], force: bool = False) -> str | None:
    """Return the cached token for ``name`` if still within ``ttl``, else refetch.

    Locked so concurrent callers (sources() fans out pv/nf/hs in parallel) don't
    each redo the same scrape/API round trip - the first one in fetches, the rest
    just read its result. Also persisted to the on-disk provider cache so a
    hard-won token (see _OTP_TOKEN_TTL) survives a Kodi/service restart instead of
    being thrown away and re-minted from scratch.

    ``force`` skips the cache read (a caller found the cached value rejected
    server-side despite being within ttl) but still only overwrites it on success.
    """
    if not force:
        cached = _token_cache.get(name)
        if cached and (time.time() - cached[1]) < ttl:
            return cached[0]
    with _token_cache_lock:
        if not force:
            cached = _token_cache.get(name)
            if cached and (time.time() - cached[1]) < ttl:
                return cached[0]
            row = cache.cache_get(f'netmirror_{name}', control.providercacheFile)
            if row and row.get('value') and (time.time() - row.get('date', 0)) < ttl:
                _token_cache[name] = (row['value'], row['date'])
                return row['value']
        value = fetch()
        if value:
            now = time.time()
            _token_cache[name] = (value, now)
            cache.cache_insert(f'netmirror_{name}', value, control.providercacheFile)
        return value


# ─── source ──────────────────────────────────────────────────────────────────────

class source:
    ffitem: FFItem
    priority: ClassVar[int] = 1
    language: ClassVar[list[str]] = ['en']

    def __init__(self):
        self.domains = [_BASE.split('://', 1)[-1]]
        self.debug = const.sources.netmirror.debug

    # ── public api ────────────────────────────────────────────────────────────

    def movie(self, imdb: str, title: str, localtitle: str, aliases: list[SourceTitleAlias], year: str) -> str | None:
        tmdb = self.ffitem.tmdb_id
        return self._encode_locator(self._search(title, want_series=False, year=year), str(tmdb or ''))

    def tvshow(self, imdb: str, tvdb: str, tvshowtitle: str, localtvshowtitle: str, aliases: list[SourceTitleAlias], year: str) -> str | None:
        # self.ffitem is the episode here, not the show - tmdb_id lives on show_item.
        show_item = self.ffitem.show_item
        tmdb = (show_item.tmdb_id if show_item else self.ffitem.tmdb_id) or ''
        return self._encode_locator(self._search(tvshowtitle, want_series=True, year=year), str(tmdb))

    def episode(self, url: str | None, imdb: str, tvdb: str, title: str, premiered: str, season: str, episode: str) -> str | None:
        pairs = self._decode_locator(url) if url else []
        tmdb, _, _ = self._decode_tmdb(url) if url else ('', '', '')
        if not pairs and not tmdb:
            return url
        per_ott_ep: dict[Ott, str] = {}
        if pairs:
            # NetMirror labels episodes with English titles.
            titles = [candidate for candidate in (self.ffitem.vtag.getEnglishTitle(), self.ffitem.vtag.getOriginalTitle()) if candidate] or [title]
            clean_targets = {cleantitle.get(candidate) for candidate in titles if candidate}
            clean_targets.discard(None)
            target_season_label, target_episode_label = f'S{season}', f'E{episode}'

            # Resolve the episode id on each ott concurrently, using that ott's show id.
            with ThreadPoolExecutor(max_workers=len(pairs)) as pool:
                done = list(pool.map(
                    lambda pair: (pair[0], self._episode_id_for_ott(
                        pair[0], pair[1], season, episode,
                        clean_targets, target_season_label, target_episode_label, titles)),
                    pairs,
                ))
            per_ott_ep = {ott: str(epid) for ott, epid in done if epid}
        # No real episode found on any ott, and no tmdb fallback either (e.g.
        # search matched a same-titled movie) → emit nothing rather than
        # letting sources() play the show/movie id.
        if not per_ott_ep and not tmdb:
            if self.debug:
                fflog(f'S{season}E{episode}: no episode id on any ott, dropping {url!r}')
            return None
        if self.debug:
            fflog(f'S{season}E{episode} → {per_ott_ep}' + (f' (+ tmdb={tmdb})' if tmdb else ''))
        return self._encode_locator(per_ott_ep, tmdb, season, episode)

    def sources(self, url: str | None, hostDict: list[str], hostprDict: list[str]) -> list[SourceItem]:
        results: list[SourceItem] = []
        try:
            if not url:
                return results
            tmdb, season, episode = self._decode_tmdb(url)
            pairs = self._decode_locator(url)
            if not tmdb and not pairs:
                return results

            # Combined, not either/or - net27.cc has only one unlabelled audio
            # track, so skipping the ott/OTP path would hide its PL/MULTI finds.
            # It's also the only one that still works when OTP is unavailable.
            with ThreadPoolExecutor(max_workers=2) as pool:
                nm27_future = pool.submit(self._nm27_sources, tmdb, season, episode, self.debug) if tmdb else None
                ott_future = pool.submit(self._ott_sources, pairs) if pairs else None
                if nm27_future:
                    results += nm27_future.result()
                if ott_future:
                    results += ott_future.result()
        except Exception:
            fflog_exc()
        if self.debug:
            fflog(f'sources: {len(results)}')
        return results

    def _ott_sources(self, pairs: list[tuple[Ott, str]]) -> list[SourceItem]:
        results: list[SourceItem] = []
        user_agent = CHROME_UA
        # Fetch every ott's master concurrently (each is an independent
        # player.php + m3u8 round-trip); parse/emit in pair order afterwards.
        with ThreadPoolExecutor(max_workers=len(pairs)) as pool:
            masters = list(pool.map(
                lambda pair: (pair[0], pair[1], self._cached_master(pair[1], pair[0], user_agent)),
                pairs,
            ))

        for ott, content_id, master_text in masters:
            if not master_text:
                continue
            resolutions = self._video_resolutions(master_text)
            if not resolutions:
                if self.debug:
                    fflog(f'ott={ott}: no video streams, skip')
                continue
            audios = self._audio_langs(master_text)
            captions = self._cached_captions(content_id, ott)
            language, info = self._classify_audio(audios, captions)
            qualities = {self._quality_label(res) for res in resolutions}
            if self.debug:
                fflog(f'ott={ott}: qualities={sorted(qualities)} audio={sorted(audios)}')
            for quality in _QUALITY_ORDER:
                if quality not in qualities:
                    continue
                results.append({
                    'source':     'netmirror',
                    'quality':    quality,
                    'language':   language,
                    'url':        f'nm:{ott}:{content_id}?q={quality}',
                    'info':       info,
                    'filename':   str(content_id),
                    'direct':     False,
                    'debridonly': False,
                })
        return results

    def resolve(self, url: str) -> str | None:
        try:
            if not url:
                return None
            if url.startswith('nm27:'):
                return self._resolve_nm27(url)
            if not url.startswith('nm:'):
                return None
            body, _, query = url[3:].partition('?')
            quality = parse_qs(query).get('q', ['1080p'])[0]
            content_ott, _, content_id = body.partition(':')
            if not content_id:  # legacy single-id safety
                content_ott, content_id = 'pv', content_ott

            user_agent = CHROME_UA
            # master_text is non-empty only for a FRESH entry (cache hit within TTL
            # or a successful refetch) — guard on it so we never reuse a stale entry's
            # expired signed master_url after a failed refetch.
            master_text = self._cached_master(content_id, content_ott, user_agent)
            if not master_text:
                if self.debug:
                    fflog(f'no fresh master for id={content_id} (ott={content_ott})')
                return None
            cached = _master_cache.get(f'{content_id}:newtv:{content_ott}')
            master_url = cached[1] if cached else None
            if not master_url:
                return None
            referer = self._cached_referer(content_id, content_ott)

            captions = self._cached_captions(content_id, content_ott)
            if captions:
                try:
                    subtitle_urls = [caption['file'] for caption in captions if caption.get('file')]
                    control.window().setProperty('source.subtitles', json.dumps(subtitle_urls))
                    if self.debug:
                        fflog(f'{len(subtitle_urls)} subtitle(s) passed to player')
                except Exception:
                    fflog_exc()

            # Serve a master trimmed to the chosen quality; ISA pulls the variant,
            # audio, subs and segments straight from the CDN (absolute URLs, children
            # carry ENDLIST → VOD, seeking works). Quality not isolatable → hand over
            # the full master and let ISA adapt.
            trimmed = self._trim_master(master_text, quality)
            if trimmed:
                master_path = f'/nm_{hashlib.md5((master_url + quality).encode()).hexdigest()[:12]}.m3u8'
                service_client.set_media_files({master_path: trimmed})
                proxy_url = urljoin(service_client.url, '/media') + master_path
                if self.debug:
                    fflog(f'{quality} → trimmed master {master_path}')
                return build_isa_url(proxy_url, referer, ua=user_agent)

            if self.debug:
                fflog(f'{quality} not isolated → full master (ABR)')
            return build_isa_url(master_url, referer, ua=user_agent)
        except Exception:
            fflog_exc()
            return None

    def _resolve_nm27(self, url: str) -> str | None:
        """Already a ready-to-play signed CDN mp4 - see _nm27_sources."""
        try:
            tmdb, season, episode, stream_url = url[len('nm27:'):].split(':', 3)
        except ValueError:
            return None
        cached = _master_cache.get(f'nm27:{tmdb}:{season}:{episode}')
        captions = cached[3] if cached else []
        if captions:
            try:
                subtitle_urls = [caption['file'] for caption in captions if caption.get('file')]
                control.window().setProperty('source.subtitles', json.dumps(subtitle_urls))
                if self.debug:
                    fflog(f'{len(subtitle_urls)} subtitle(s) passed to player (net27.cc)')
            except Exception:
                fflog_exc()
        return f'{stream_url}|Referer={_NM27_REFERER}&User-Agent={CHROME_UA}'

    @staticmethod
    def _fetch_otp_code(debug: bool = False, prefer_manual: bool = False) -> tuple[str | None, bool]:
        """Scrape the current bypass OTP from netmirror.gg/tv (see _OTP_CODE_URL).

        The page renders it client-side as a JS array literal
        (``const otp = [2, 3, 0, 4, 5, 0]``); no need to run JS, the digits are
        already sitting in the served HTML. The page also ships a commented-out
        placeholder line with the same pattern (all zeros), so the last match
        (not the first) is the real one.

        Cloudflare occasionally challenges this page outright (no HTML served at
        all); when that happens fall back to the code the user pasted into the
        ``netmirror.otp_code`` setting. Returns ``(code, is_manual)``.

        ``prefer_manual`` skips the scrape and uses the manual setting directly -
        for prefetch_otp_token(), called right after that setting was just set
        (manually or via the tampermonkey script), where the scrape could return
        some unrelated/stale code instead of the one we actually just got.
        """
        if prefer_manual:
            manual = settings.getString('netmirror.otp_code').strip()
            if manual:
                return manual, True
        try:
            resp = _session.get(_OTP_CODE_URL, headers={'User-Agent': CHROME_UA}, timeout=8)
            if resp and resp.status_code == 200:
                matches = re.findall(r'const\s+otp\s*=\s*\[([\d,\s]+)\]', resp.text)
                if matches:
                    code = ''.join(re.findall(r'\d', matches[-1]))
                    if code:
                        if debug:
                            fflog(f'netmirror.gg/tv OTP code: {code}')
                        return code, False
        except Exception:
            if debug:
                fflog_exc()
        manual = settings.getString('netmirror.otp_code').strip()
        return (manual, True) if manual else (None, False)

    @classmethod
    def _otp_bypass_token(cls, debug: bool = False, force: bool = False, prefer_manual: bool = False) -> str | None:
        """usertoken via newtv/otp.php, using the live bypass OTP (see _fetch_otp_code)."""
        def fetch() -> str | None:
            code, is_manual = cls._fetch_otp_code(debug, prefer_manual=prefer_manual)
            if not code:
                _notify_otp_failure()
                return None
            try:
                base = cls._resolve_newtv_base()
                _throttle_newtv()
                resp = _session.get(
                    f'{base}/newtv/otp.php',
                    headers={
                        'User-Agent': _NEWTV_UA,
                        'Otp': code,
                        'Cache-Control': 'no-cache, no-store, must-revalidate',
                        'Pragma': 'no-cache',
                        'Expires': '0',
                    },
                    timeout=8,
                )
                if resp and resp.status_code == 200:
                    data = resp.json()
                    if data.get('status') == 'ok' and data.get('usertoken'):
                        if debug:
                            fflog(f'otp.php returned usertoken from {base}')
                        control.infoDialog(
                            L(30642, 'Pairing successful.'),
                            heading=L(30603, 'NetMirror OTP code'),
                            icon="INFO",
                        )
                        return data['usertoken']
                if is_manual:
                    _notify_otp_failure()
            except Exception:
                if debug:
                    fflog_exc()
            return None
        return _cached_token('otp', _OTP_TOKEN_TTL, fetch, force=force)

    @classmethod
    def prefetch_otp_token(cls) -> None:
        """Redeem a freshly-set OTP code in the background right away.

        The code (whether typed into settings or pushed by the tampermonkey
        script) is only valid ~2 minutes - waiting for the user's next search
        to trigger _otp_bypass_token can easily miss that window.
        """
        def run() -> None:
            try:
                cls._otp_bypass_token(debug=const.sources.netmirror.debug, force=True, prefer_manual=True)
            except Exception:
                fflog_exc()
        threading.Thread(target=run, daemon=True).start()

    # ── net27.cc fast path (no auth, younger/incomplete catalogue) ───────────

    @classmethod
    def _nm27_sources(cls, tmdb: str, season: str, episode: str, debug: bool = False) -> list[SourceItem]:
        """Streams straight from net27.cc/api/embed-tmdb - see module docstring.

        Returns [] on any failure, "noSource" (title not ripped yet there), or a
        TV response that doesn't confirm the requested season/episode - so the
        caller's ott/OTP results (merged, not replaced - see sources()) are all
        that's shown in those cases.
        """
        results: list[SourceItem] = []
        try:
            params = {'type': 'tv', 's': season, 'e': episode} if season and episode else {}
            resp = _session.get(
                f'{_NM27_BASE}/api/embed-tmdb/{tmdb}',
                params=params,
                headers={'User-Agent': CHROME_UA},
                timeout=8,
            )
            if not resp or resp.status_code != 200:
                return results
            data = resp.json()
            if not data.get('ok') or data.get('noSource'):
                return results
            if season and episode and (str(data.get('currentSeason')) != str(season) or str(data.get('currentEpisode')) != str(episode)):
                # As of 2026-08 net27.cc ignores s=/e= and always answers with S1E1 -
                # drop any response that doesn't confirm the requested episode.
                if debug:
                    fflog(f'net27.cc: wanted S{season}E{episode}, got S{data.get("currentSeason")}E{data.get("currentEpisode")} - ignoring')
                return results

            # net27.cc gives no audio-track info at all (single unlabelled track
            # per title) - reuse _classify_audio with an empty audio set so it
            # falls back to its own "unknown audio" default and still tags NAPISY
            # from whatever caption languages are actually available.
            captions = cls._nm27_captions(data.get('captions') or [])
            language, info = cls._classify_audio(set(), captions)
            key = f'nm27:{tmdb}:{season}:{episode}'
            _store_master(key, (time.time(), '', '', captions, _NM27_REFERER))

            for stream in data.get('streams') or []:
                stream_url = stream.get('url')
                if not stream_url:
                    continue
                results.append({
                    'source':     'netmirror',
                    'quality':    source_utils.quality_from_resolution(height=stream.get('resolution', 0)),
                    'language':   language,
                    'info':       info,
                    'size':       source_utils.convert_size(stream.get('size')),
                    'url':        f'nm27:{tmdb}:{season}:{episode}:{stream_url}',
                    'filename':   str(tmdb),
                    'direct':     True,
                    'debridonly': False,
                })
            if debug:
                fflog(f'net27.cc: {len(results)} stream(s) for tmdb={tmdb} lang={language} info={info!r}')
        except Exception:
            if debug:
                fflog_exc()
        return results

    @staticmethod
    def _nm27_captions(raw_captions: list[dict]) -> list[Caption]:
        captions: list[Caption] = []
        for entry in raw_captions:
            file_url = entry.get('url')
            if not file_url:
                continue
            if file_url.startswith('/'):
                file_url = _NM27_BASE + file_url
            captions.append({'file': file_url, 'label': entry.get('name', ''), 'code': entry.get('lang', '')})
        return captions

    # ── search ────────────────────────────────────────────────────────────────

    def _search(self, title: str, want_series: bool, year: str = '') -> dict[Ott, str]:
        """Find the title's content id on every ott. Returns {ott: id} (may be empty).

        NetMirror is an EN/Indian catalogue, so we query the single English title
        only. search.php exposes no year/kind, so every title-matched candidate is
        confirmed via post.php (_fetch_post_meta) before picking - this is what
        keeps a same-titled movie from standing in for a show (and vice versa).
        """
        if not title:
            return {}
        fflog(f'query: {title!r}')
        base = self._resolve_newtv_base()
        target = cleantitle.get(title)

        with ThreadPoolExecutor(max_workers=len(_OT_PLATFORMS)) as pool:
            results = list(pool.map(
                lambda platform: self._search_once(base, platform[0], title),
                _OT_PLATFORMS,
            ))

        # Pass 1 (no network): title-matched candidates per ott.
        candidates_by_ott: dict[Ott, list[SearchHit]] = {}
        for (ott, _subpath), search_result in zip(_OT_PLATFORMS, results):
            if not search_result:
                continue
            candidates = [item for item in search_result
                          if cleantitle.get(unescape(item['t'])) == target]
            if not candidates:
                # No exact hit - fall back to a substring match (title differs by a
                # subtitle/punctuation cleantitle doesn't strip).
                candidates = [item for item in search_result
                              if (cleaned := cleantitle.get(unescape(item['t'])))
                              and (cleaned in target or target in cleaned)]
            if candidates:
                candidates_by_ott[ott] = candidates

        # Pass 2: confirm every candidate's year/kind via post.php, concurrently.
        to_check = [(ott, hit) for ott, hits in candidates_by_ott.items() for hit in hits]
        metas: dict[tuple[Ott, str], tuple[str, str]] = {}  # (ott, id) -> (year, type)
        if to_check:
            with ThreadPoolExecutor(max_workers=len(to_check)) as pool:
                fetched = pool.map(lambda item: self._fetch_post_meta(base, item[1]['id'], item[0]), to_check)
                metas = {(ott, hit['id']): meta for (ott, hit), meta in zip(to_check, fetched)}

        # Pass 3: pick the best candidate per ott, honouring kind then year.
        found: dict[Ott, str] = {}
        want_kind = 't' if want_series else 'm'
        for ott, hits in candidates_by_ott.items():
            pool_hits = [hit for hit in hits if metas.get((ott, hit['id']), ('', ''))[1] == want_kind]
            if not pool_hits:
                continue
            if year:
                by_year = [hit for hit in pool_hits if metas.get((ott, hit['id']), ('', ''))[0] == str(year)]
                if not by_year:
                    if self.debug:
                        fflog(f"year mismatch for {pool_hits[0]['t']!r} ({ott}): wanted={year!r}")
                    continue
                pool_hits = by_year
            matched = pool_hits[0]
            found[ott] = matched['id']
            if self.debug:
                match_year = metas.get((ott, matched['id']), ('', ''))[0]
                fflog(f"matched {matched['t']!r} ({match_year or '?'}) → id={matched['id']} ({ott})")
        if not found and self.debug:
            fflog(f'no {"series" if want_series else "movie"} hits for {title!r}')
        return found

    @staticmethod
    def _fetch_post_meta(base: str, content_id: str, ott: str) -> tuple[str, str]:
        """post.php for a candidate → (year, type: 'm' movie / 't' series). ('', '') on failure (fail-open)."""
        try:
            resp = _session.get(
                f'{base}/newtv/post.php',
                params={'id': content_id},
                headers=source._newtv_headers(ott),
                timeout=8,
            )
            if resp and resp.status_code == 200:
                data = resp.json()
                return data.get('year', ''), data.get('type', '')
        except Exception:
            pass
        return '', ''

    def _search_once(self, base: str, ott: str, query: str) -> list[SearchHit] | None:
        """One newtv search.php request for one ott. Returns its searchResult list.

        Transient network/JSON errors are expected (flaky backend) and swallowed
        quietly — None means "nothing here".
        """
        try:
            resp = _session.get(
                f'{base}/newtv/search.php',
                params={'s': query},
                headers=self._newtv_headers(ott),
                timeout=10,
            )
            if resp and resp.status_code == 200:
                return resp.json().get('searchResult') or []
        except Exception:
            pass
        return None

    # ── episode lookup ────────────────────────────────────────────────────────

    def _episode_id_for_ott(self, ott: str, show_id: str, season: str, episode: str,
                            clean_targets: set[str | None],
                            target_season_label: str, target_episode_label: str, titles: list[str]) -> str | None:
        """Find the episode id for S/E within one ott's show id."""
        base = self._resolve_newtv_base()

        # Netflix mapping – single-request fast path (nf ids are numeric).
        netflix_map = source_utils.get_netflix_ep_id(show_id, season, episode, titles) if ott == 'nf' and show_id.isdigit() else None
        if netflix_map:
            try:
                resp = _session.get(
                    f'{base}/newtv/episodes.php',
                    params={'id': netflix_map[1]},
                    headers=self._newtv_headers('nf'),
                    timeout=10,
                )
                if resp and resp.status_code == 200:
                    eps_data: EpisodesResponse = resp.json()
                    for episode_data in eps_data.get('episodes') or []:
                        if str(episode_data['id']) == netflix_map[0]:
                            if self.debug:
                                fflog(f'MATCH BY NETFLIX ID (fast): {netflix_map[0]}')
                            return netflix_map[0]
            except Exception:
                fflog_exc()

        return self._episode_scan(base, ott, show_id, season,
                                  target_season_label, target_episode_label, clean_targets, netflix_map)

    @staticmethod
    def _season_number(label: str) -> str:
        """post.php season labels look like "Season 1 (8 EP)" - pull out the number."""
        match = re.search(r'\d+', label)
        return match.group(0) if match else ''

    def _episode_scan(self, base: str, ott: str, show_id: str, season: str,
                      target_season_label: str, target_episode_label: str,
                      clean_targets: set[str | None], netflix_map: tuple[str, str] | None) -> str | None:
        """post.php + paginated episodes.php scan via the newtv API.

        Returns on a netflix-id or episode-title match immediately; otherwise the
        episode-number match found in the target season (after scanning its pages).
        """
        headers = self._newtv_headers(ott)
        try:
            resp = _session.get(
                f'{base}/newtv/post.php',
                params={'id': show_id},
                headers=headers,
                timeout=10,
            )
            if not resp or resp.status_code != 200:
                return None
            post_data: ShowResponse = resp.json()
            if post_data.get('status') != 'ok':
                return None
            seasons_list: list[SeasonEntry] = post_data.get('season') or []
            if not seasons_list:
                return None

            ordered_seasons = sorted(seasons_list, key=lambda season_entry: 0 if self._season_number(season_entry['s']) == str(season) else 1)
            fallback_id = None
            for season_entry in ordered_seasons:
                season_id = season_entry['id']
                if not season_id:
                    continue
                in_target_season = self._season_number(season_entry['s']) == str(season)
                page = 1
                while True:
                    resp = _session.get(
                        f'{base}/newtv/episodes.php',
                        params={'id': season_id, 'page': page},
                        headers=headers,
                        timeout=10,
                    )
                    if not resp or resp.status_code != 200:
                        break
                    eps_data: EpisodesResponse = resp.json()
                    for episode_data in eps_data.get('episodes') or []:
                        episode_id_str = str(episode_data['id'])
                        if netflix_map and episode_id_str == netflix_map[0]:
                            if self.debug:
                                fflog(f'MATCH BY NETFLIX ID: {episode_id_str}')
                            return episode_id_str
                        episode_title = episode_data['t']
                        if clean_targets and cleantitle.get(episode_title) in clean_targets:
                            if self.debug:
                                fflog(f'MATCH BY TITLE: {episode_title!r} → id={episode_id_str} (s_id={season_id})')
                            return episode_id_str
                        if not fallback_id and in_target_season and str(episode_data.get('ep')) == str(episode):
                            fallback_id = episode_id_str
                    if not eps_data.get('nextPageShow') or page > 5:
                        break
                    page += 1

            if fallback_id and self.debug:
                fflog(f'MATCH BY NUMBER (fallback): {target_season_label}{target_episode_label} → id={fallback_id}')
            return fallback_id
        except Exception:
            fflog_exc()
            return None

    # ── master playlist (NewTV fetch + cache) ─────────────────────────────────

    def _cached_master(self, content_id: str, content_ott: str, user_agent: str) -> str | None:
        now = time.time()
        key = f'{content_id}:newtv:{content_ott}'
        cached = _master_cache.get(key)
        if cached and (now - cached[0]) < _MASTER_TTL:
            return cached[2]
        playback = self._newtv_playback(content_id, content_ott)
        if not playback:
            return None
        master_url, referer = playback
        try:
            resp = _session.get(
                master_url,
                headers={
                    'User-Agent': user_agent,
                    'Referer': referer,
                    'Accept': '*/*',
                },
                timeout=12,
            )
            if resp and resp.status_code == 200 and resp.text.startswith('#EXTM3U'):
                master_text = resp.text
                captions = self._extract_captions_from_master(master_text)
                _store_master(key, (now, master_url, master_text, captions, referer))
                return master_text
            if self.debug:
                fflog(f'master fetch failed: status {getattr(resp,"status_code",None)}')
        except Exception:
            fflog_exc()
        return None

    def _cached_captions(self, content_id: str, content_ott: str = 'pv') -> list[Caption]:
        """Read captions from the master cache (populated by _cached_master)."""
        key = f'{content_id}:newtv:{content_ott}'
        cached = _master_cache.get(key)
        return cached[3] if cached else []

    def _cached_referer(self, content_id: str, content_ott: str = 'pv') -> str:
        """Read the per-response Referer from the master cache (populated by _cached_master)."""
        key = f'{content_id}:newtv:{content_ott}'
        cached = _master_cache.get(key)
        return cached[4] if cached else (_BASE + '/')

    @staticmethod
    def _newtv_headers(content_ott: str, usertoken: str | None = None) -> dict[str, str]:
        headers = {
            'User-Agent': _NEWTV_UA,
            'X-Requested-With': 'NetmirrorNewTV v1.0',
            'Ott': content_ott,
            'Accept': 'application/json, text/plain, */*',
        }
        if usertoken:
            headers['Usertoken'] = usertoken
        return headers

    @classmethod
    def _resolve_newtv_base(cls, refresh: bool = False) -> str:
        """Discover the live NewTV API base via checknewtv.php.

        tv.imgcdn.kim rotates; the mobidetect domains hand back the current base
        as a base64 token_hash. All candidates are probed concurrently and the
        first valid base wins (a dead first domain no longer stalls the chain).
        Falls back to the hardcoded default.
        """
        global _newtv_base, _newtv_base_ts
        if not refresh and _newtv_base and (time.time() - _newtv_base_ts) < _NEWTV_BASE_TTL:
            return _newtv_base
        if not refresh:
            row = cache.cache_get('netmirror_base', control.providercacheFile)
            if row and row.get('value') and (time.time() - row.get('date', 0)) < _NEWTV_BASE_TTL:
                _newtv_base, _newtv_base_ts = row['value'], row['date']
                return _newtv_base
        pool = ThreadPoolExecutor(max_workers=len(_NEWTV_DOMAINS))
        try:
            futures = [pool.submit(cls._probe_newtv_domain, domain) for domain in _NEWTV_DOMAINS]
            for future in as_completed(futures):
                base = future.result()
                if base:
                    _newtv_base = base
                    _newtv_base_ts = time.time()
                    cache.cache_insert('netmirror_base', base, control.providercacheFile)
                    return base
            return _newtv_base or _NEWTV_BASE
        finally:
            pool.shutdown(wait=False)  # don't block on slower domains once we have one

    @classmethod
    def _probe_newtv_domain(cls, domain: str) -> str | None:
        try:
            resp = _session.get(
                f'https://{domain}/checknewtv.php',
                headers=cls._newtv_headers('pv'),
                timeout=8,
            )
            if not resp or resp.status_code != 200:
                return None
            token_hash = resp.json().get('token_hash')
            if not token_hash:
                return None
            base = base64.b64decode(token_hash).decode().rstrip('/')
            return base if base.startswith('http') else None
        except Exception:
            return None

    def _newtv_playback(self, content_id: str, content_ott: str = 'pv') -> tuple[str, str] | None:
        def _try(base: str, usertoken: str | None) -> tuple[str, str] | None:
            try:
                _throttle_newtv()
                resp = _session.get(
                    f'{base}/newtv/player.php',
                    params={'id': content_id},
                    headers=self._newtv_headers(content_ott, usertoken),
                    timeout=12,
                )
                if resp and resp.status_code == 200:
                    data = resp.json()
                    # status != 'ok' (e.g. 'otp') means the usertoken was rejected -
                    # the site still hands back a video_link, but it resolves to a
                    # fixed decoy clip (same file for every id) instead of real content.
                    if data.get('status') == 'ok' and data.get('video_link'):
                        # Some responses omit `referer` and only carry `img_referer`
                        # (seen in NetMirror's own app source) - fall back to that,
                        # then to the site itself.
                        referer = data.get('referer') or data.get('img_referer') or (_BASE + '/')
                        return data['video_link'], referer
            except Exception:
                fflog_exc()
            return None

        usertoken = self._otp_bypass_token(self.debug)
        base = self._resolve_newtv_base()
        result = _try(base, usertoken)
        if result:
            return result
        # The cached usertoken can start getting rejected well before its 30d TTL
        # (despite normally not expiring) - force a fresh mint and retry once
        # before assuming it's the host that rotated instead.
        fresh_token = self._otp_bypass_token(self.debug, force=True)
        if fresh_token and fresh_token != usertoken:
            result = _try(base, fresh_token)
            if result:
                return result
            usertoken = fresh_token
        fresh_base = self._resolve_newtv_base(refresh=True)
        return _try(fresh_base, usertoken) if fresh_base != base else None

    # ── stream parsing (master m3u8 → quality / audio / captions) ─────────────

    @staticmethod
    def _video_resolutions(master_text: str) -> set[str]:
        return set(re.findall(r'#EXT-X-STREAM-INF[^\n]*RESOLUTION=(\d+x\d+)', master_text))

    @staticmethod
    def _quality_label(resolution: str) -> str:
        """Bucket an arbitrary ``WxH`` into a quality label (pv serves odd crops)."""
        try:
            width, height = (int(dim) for dim in resolution.split('x'))
        except (ValueError, TypeError):
            return 'SD'
        return source_utils.quality_from_resolution(width, height)

    @staticmethod
    def _audio_langs(master_text: str) -> set[str]:
        """One canonical language code per audio track.

        Picks a single label per ``TYPE=AUDIO`` line (LANGUAGE, else NAME, else
        the ``/a/<lang>/`` URI segment) so a single track contributes a single
        token - distinct tokens then genuinely mean distinct audio tracks.
        """
        langs: set[str] = set()
        for line in master_text.splitlines():
            if '#EXT-X-MEDIA:' not in line or 'TYPE=AUDIO' not in line:
                continue
            raw = ''
            for attr in ('LANGUAGE', 'NAME'):
                attr_match = re.search(attr + r'="([^"]*)"', line)
                if attr_match and attr_match.group(1):
                    raw = attr_match.group(1)
                    break
            if not raw:
                path_match = re.search(r'URI="[^"]*?/a/([^/]+)/', line)
                raw = path_match.group(1) if path_match else ''
            raw = raw.strip().lower()
            if raw:
                langs.add(_AUDIO_LANG_MAP.get(raw, raw))
        return langs

    @staticmethod
    def _classify_audio(audios: set[str], captions: list[Caption] | None = None) -> tuple[str, str]:
        meaningful = audios - _AUDIO_NOISE
        if not meaningful:
            language, info_parts = 'en', []
        else:
            # Common variants in attributes or URI paths
            has_pol = any(variant in meaningful for variant in ('pol', 'pl', 'polski', 'polish'))
            has_eng = any(variant in meaningful for variant in ('eng', 'en', 'english'))

            if has_pol:
                language = 'pl'
                info_parts = ['LEKTOR']
                if len(meaningful) > 1:
                    info_parts.append('MULTI')
            elif has_eng:
                language = 'en'
                non_western = meaningful - {'en'} - _NON_WESTERN_LANGS
                info_parts = ['MULTI'] if non_western else []
            else:
                language = 'en'
                info_parts = ['MULTI']
        if captions:
            caption_codes = set()
            for caption in captions:
                code = caption.get('code', '').strip().lower()
                if code:
                    caption_codes.add(_AUDIO_LANG_MAP.get(code, code))
            if caption_codes - {language} - _NON_WESTERN_LANGS:
                info_parts.append('NAPISY')
        return (language, ' '.join(info_parts))

    @staticmethod
    def _extract_captions_from_master(master_text: str) -> list[Caption]:
        captions: list[Caption] = []
        for line in master_text.splitlines():
            if '#EXT-X-MEDIA:TYPE=SUBTITLES' not in line:
                continue
            name_match = re.search(r'NAME="([^"]*)"', line)
            lang_match = re.search(r'LANGUAGE="([^"]*)"', line)
            uri_match = re.search(r'URI="([^"]*)"', line)
            if not uri_match:
                continue
            file_url = uri_match.group(1)
            label = name_match.group(1) if name_match else ''
            code = lang_match.group(1).split('-')[0].lower() if lang_match else ''
            if file_url.startswith('//'):
                file_url = 'https:' + file_url
            captions.append({'file': file_url, 'label': label, 'code': code})
        return captions

    # ── master trimming (force a single quality) ─────────────────────────────

    def _trim_master(self, master_text: str, target_quality: str) -> str | None:
        """Master containing only the chosen-quality variant; audio/subs kept as-is.

        Variant, audio, subtitle and segment URLs are already absolute and the child
        playlists carry #EXT-X-ENDLIST (ISA plays them as VOD → seeking works), so we
        only drop the other STREAM-INF variants to pin the picked quality. Returns
        None when the quality isn't present (caller serves the full master).
        """
        if not master_text.startswith('#EXTM3U'):
            return None
        lines = master_text.splitlines()
        kept: list[str] = []
        kept_variant = False
        index = 0
        while index < len(lines):
            line = lines[index]
            if line.startswith('#EXT-X-STREAM-INF'):
                resolution_match = re.search(r'RESOLUTION=(\d+x\d+)', line)
                variant_url = lines[index + 1] if index + 1 < len(lines) else ''
                if (resolution_match and variant_url.startswith('http')
                        and self._quality_label(resolution_match.group(1)) == target_quality):
                    kept.append(line)
                    kept.append(variant_url)
                    kept_variant = True
                index += 2
                continue
            kept.append(line)
            index += 1
        return '\n'.join(kept) if kept_variant else None

    # ── helpers ───────────────────────────────────────────────────────────────

    @staticmethod
    def _encode_locator(per_ott: dict[Ott, str], tmdb: str = '', season: str = '', episode: str = '') -> str | None:
        """Pack the per-ott ids and/or tmdb id found for a title into one locator string.

        Nothing found on either side → None, so callers don't repeat the empty check.
        """
        if not per_ott and not tmdb:
            return None
        body = ';'.join(f'{ott}:{content_id}' for ott, content_id in per_ott.items())
        suffix = f'?tmdb={tmdb}' + (f'&s={season}&e={episode}' if season and episode else '') if tmdb else ''
        return f'nm:{body}{suffix}'

    @staticmethod
    def _decode_locator(url: str) -> list[tuple[Ott, str]]:
        """Unpack a locator into (ott, id) pairs. Tolerates the single legacy form."""
        body = url[3:].split('?', 1)[0] if url.startswith('nm:') else ''
        pairs: list[tuple[Ott, str]] = []
        for chunk in body.split(';'):
            ott, _, content_id = chunk.partition(':')
            if ott and content_id:
                pairs.append((cast('Ott', ott), content_id))
        return pairs

    @staticmethod
    def _decode_tmdb(url: str) -> tuple[str, str, str]:
        """Unpack the ``?tmdb=..&s=..&e=..`` suffix (net27.cc fast path) from a locator."""
        if '?' not in url:
            return '', '', ''
        query = parse_qs(url.split('?', 1)[1])
        return query.get('tmdb', [''])[0], query.get('s', [''])[0], query.get('e', [''])[0]
