"""Import guard for Kodi's ``xbmcdrm`` module.

FOX.TV handles playback/DRM through the native Android player path. This
compatibility module exists only so Kodi-oriented imports resolve cleanly.
"""

class CryptoSession:  # pragma: no cover - import compatibility surface
    pass
