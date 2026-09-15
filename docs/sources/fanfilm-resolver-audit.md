# FOX.TV — FanFilm Resolver Audit (ETAP 3A.1 / Checkpoint E)

- DATA: 2026-09-14 · ZASADA: **per-resolver, zero globalnego QUARANTINE/REMOVE** · ŹRÓDŁO: `app/src/main/assets/fanfilm/addons/script.module.resolveurl/lib/resolveurl/plugins/` (230 plików .py, sha256 w `MANIFEST.json`)

## 1. Inwentarz resolverów (230)

Pełna lista w pliku tekstowym wygenerowanym z assets (`230 pluginów`, przykłady: abstream, abyss, alldebrid, amazon, archiveorg, **cda**, cloudmailru, dailymotion, dailymotion-list, filemoon, streamtape-family, vidmoly-family, ok-ru, google-drive, …).

## 2. Klasy techniczne resolverów (z kodu resolverów)

| Klasa | Mechanizm (fakty z pluginów) | Infrastruktura FOX | Status wzorca | Przykłady |
|---|---|---|---|---|
| OPEN/LEGAL media | archive.org, lbry/tv, bitchute, brighteon, youtube/dailymotion/ok.ru/sibnet/mail.ru (publiczne wideo) | transport OkHttp | KEEP_AND_EXTEND | archiveorg.py, dailymotion.py, ok-ru.py, sibnet, mail.ru, lbry |
| Płatne konta (AUTHENTICATED_SERVICE) | alldebrid.py, realdebrid (api.real-debrid.com), premium.rpnet.biz, cocoleech, debrid-download | klucze API użytkownika (własne konta) | KEEP | alldebrid, rpnet, cocoleech |
| CDA (POLSKA!) | cda.py — **polski hoster** | CdnHeaderResolver (brak reguły cda.pl — DODAĆ) | KEEP_AND_EXTEND (PL priorytet!) | cda.py |
| Hosterzy plików (user-up) | anonymfile, bigshare, bowfile, cloudfile, send.now, … | direct/MP4 | KEEP / UNKNOWN | liczne |
| Embed-resolvery dla agregatorów | vidmoly/streamtape/moon/dood-family… | HTML→HLS | KEEP / UNKNOWN | liczne |
| Goo.gl/mega/google-drive | drive.google.com, yadi.sk, cloudmailru | API | KEEP | — |

## 3. Fakty ekstrakcji (głowa resolverów)

- Wyodrębnione domeny z pluginów: gnu.org×229 (nagłówki licencji GPL — pomijalne), dailymotion×3, rpnet×3, cda.py (PL), drive.google, real-debrid, amazon, ok.ru, mail.ru, sibnet, lbry, megogo, send.now, vinovo, vtbe, vkspeed, …
- Wspólne helpery ResolveURL: `jsunpack` (packed JS), `get_redirect`, `cf` — pełny chain dostępny per resolver.

## 4. Decyzje NO-LOSS

1. **Żaden resolver nie jest globalnie usunięty** — wcześniejsze „QUARANTINE per resolver” z 3A UCHYLONE; zamiast tego: audyt per resolver w kolejnych przebiegach (Checkpoint E pozostaje IN PROGRESS dla pełnej klasyfikacji 230 — zewnętrzne badanie domen).
2. **`cda.py` = polski priorytet** (CDA jest też w kandydatach screenshotów) → repair/extension + reguła CDN.
3. Resolvery OPEN/LEGAL (archive.org, lbry, bitchute, brighteon, publiczne wideo) → kandydaci `OPEN_LICENSED`/`VERIFIED_IN_APP_MEDIA` przez istniejący most.
4. Resolvery debrid → `AUTHENTICATED_SERVICE` (własne konta).
5. Runtime status: **NOT VERIFIED** per resolver (Checkpoint L).

## 5. Naprawy/rozszerzenia (plan)

- [ ] Integracja `CloudflareKiller` dla resolverów z CF (per resolver, po runtime verify)
- [ ] Reguły CDN w `CdnHeaderResolver` dla: cda.pl (+bezpośrednie hosts), vidmoly-family, streamtape-family (nagłówki Referer/Origin)
- [ ] Sesja cookie dla resolverów wymagających sesji — reuse wzorca `AniyomiCookieJar`
- [ ] Testy: per-resolver kontrakt (MockWebServer) przed włączeniem do runtime
