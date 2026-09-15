"""Unit tests for the FanFilm bridge's pure-Python logic.

These cover the two pieces that are easy to get subtly wrong and impossible to see
from Kotlin: the ``special://`` translation table and the normalisation of FanFilm's
playback strings.

They run on the host interpreter, not on a device, so they import the bridge modules
directly without Chaquopy. ``foxtv_fanfilm.bridge`` already degrades to stdout when the
Java side is absent, which is what makes that possible.

    python3.11 -m unittest discover -s app/src/test/python -t app/src/test/python

The same command runs from Gradle via the ``foxtvPythonTest`` task.
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(REPO / "app" / "src" / "main" / "python"))

from foxtv_fanfilm import media, paths  # noqa: E402
from foxtv_fanfilm.version import BRIDGE_VERSION  # noqa: E402


class SpecialPathTest(unittest.TestCase):
    """``special://`` must resolve every root the vendored addons actually use."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.home = Path(self._tmp.name) / "fanfilm"
        self.addons = self.home / "addons"
        self.addons.mkdir(parents=True)
        paths.prepare(self.home, self.addons)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_bare_root_resolves_to_home(self) -> None:
        self.assertEqual(paths.translate("special://"), str(self.home))

    def test_home_and_subpath(self) -> None:
        self.assertEqual(paths.translate("special://home"), str(self.home))
        self.assertEqual(
            paths.translate("special://home/addons/plugin.video.fanfilm"),
            str(self.home / "addons" / "plugin.video.fanfilm"),
        )

    def test_userdata_masterprofile_and_profile_share_a_root(self) -> None:
        userdata = str(self.home / "userdata")
        self.assertEqual(paths.translate("special://userdata"), userdata)
        self.assertEqual(paths.translate("special://masterprofile"), userdata)
        self.assertEqual(paths.translate("special://profile"), userdata)

    def test_addon_data_subpath(self) -> None:
        # Upstream's translatePath only matches the exact string, so this is precisely
        # the case that used to fall through untranslated.
        self.assertEqual(
            paths.translate("special://profile/addon_data/plugin.video.fanfilm/settings.xml"),
            str(self.home / "userdata/addon_data/plugin.video.fanfilm/settings.xml"),
        )
        self.assertEqual(
            paths.translate("special://userdata/addon_data/plugin.video.fanfilm/"),
            str(self.home / "userdata/addon_data/plugin.video.fanfilm"),
        )

    def test_database_thumbnails_temp_and_logpath(self) -> None:
        self.assertEqual(
            paths.translate("special://database"),
            str(self.home / "userdata/Database"),
        )
        self.assertEqual(
            paths.translate("special://thumbnails"),
            str(self.home / "userdata/Thumbnails"),
        )
        self.assertEqual(paths.translate("special://temp"), str(self.home / "temp"))
        self.assertEqual(paths.translate("special://logpath"), str(self.home / "temp"))
        self.assertEqual(
            paths.translate("special://temp/qr_img.png"),
            str(self.home / "temp/qr_img.png"),
        )

    def test_skin_root(self) -> None:
        self.assertTrue(paths.translate("special://skin/").startswith(str(self.home / "addons")))

    def test_xbmcbin_is_a_real_but_empty_directory(self) -> None:
        # inputstreamhelper looks here for Kodi binary addons. Android has none, so the
        # directory exists and stays empty rather than the path being unresolvable.
        resolved = Path(paths.translate("special://xbmcbin/"))
        self.assertTrue(resolved.is_dir())
        self.assertEqual([], list(resolved.iterdir()))

    def test_ca_bundle_is_installed_where_resolveurl_expects_it(self) -> None:
        bundle = Path(paths.translate("special://xbmc/system/certs/cacert.pem"))
        self.assertEqual(bundle, self.home / "system/certs/cacert.pem")
        if paths.ca_bundle() is not None:
            # certifi is available on the host; the file must be real, not a stub.
            self.assertTrue(bundle.is_file())
            self.assertGreater(bundle.stat().st_size, 1000)

    def test_traversal_is_refused(self) -> None:
        resolved = paths.translate("special://home/../../../etc/passwd")
        self.assertEqual(resolved, str(self.home))

    def test_unknown_root_is_contained_not_passed_through(self) -> None:
        resolved = Path(paths.translate("special://somethingnew/file.txt"))
        self.assertTrue(str(resolved).startswith(str(self.home / "temp" / "unmapped")))

    def test_non_special_paths_are_untouched(self) -> None:
        self.assertEqual(paths.translate("/data/local/x"), "/data/local/x")
        self.assertEqual(paths.translate("https://example.invalid/a"), "https://example.invalid/a")

    def test_describe_lists_every_root(self) -> None:
        table = paths.describe()
        for root in (
            "home", "xbmc", "xbmcbin", "userdata", "masterprofile", "profile",
            "database", "thumbnails", "temp", "logpath", "skin", "frameworks",
        ):
            self.assertIn(f"special://{root}", table)


class MediaDescriptorTest(unittest.TestCase):
    """FanFilm's playback strings must survive as structured data."""

    def test_direct_url(self) -> None:
        descriptor = media.describe("https://cdn.example/movie.mp4")
        self.assertEqual(descriptor["url"], "https://cdn.example/movie.mp4")
        self.assertEqual(descriptor["streamType"], media.STREAM_DIRECT)
        self.assertEqual(descriptor["headers"], {})
        self.assertIsNone(descriptor["drm"])
        self.assertEqual(descriptor["host"], "cdn.example")

    def test_pipe_headers_are_parsed_and_url_decoded(self) -> None:
        raw = (
            "https://cdn.example/movie.mp4"
            "|User-Agent=Mozilla%2F5.0&Referer=https%3A%2F%2Fcda.pl%2F&Cookie=sid%3Dabc%3B+lang%3Dpl"
        )
        descriptor = media.describe(raw)

        self.assertEqual(descriptor["url"], "https://cdn.example/movie.mp4")
        self.assertEqual(descriptor["headers"]["User-Agent"], "Mozilla/5.0")
        self.assertEqual(descriptor["referer"], "https://cda.pl/")
        self.assertEqual(descriptor["cookies"], {"sid": "abc", "lang": "pl"})

    def test_hls_and_dash_are_classified(self) -> None:
        self.assertEqual(media.classify("https://x/y.m3u8"), media.STREAM_HLS)
        self.assertEqual(media.classify("https://x/y.mpd"), media.STREAM_DASH)
        self.assertEqual(media.classify("https://x/y.m3u8?token=1"), media.STREAM_HLS)
        self.assertEqual(media.classify("https://x/y.mkv"), media.STREAM_DIRECT)

    def test_dash_always_requests_the_adaptive_pipeline(self) -> None:
        descriptor = media.describe("https://cdn.example/manifest.mpd")
        self.assertTrue(descriptor["adaptive"])
        self.assertEqual(descriptor["mimeType"], "application/dash+xml")

    def test_plugin_url_is_reported_with_its_target(self) -> None:
        descriptor = media.describe(
            "plugin://plugin.video.youtube/play/?video_id=dQw4w9WgXcQ"
        )
        self.assertEqual(descriptor["streamType"], media.STREAM_PLUGIN)
        self.assertEqual(descriptor["pluginTarget"]["addonId"], "plugin.video.youtube")
        self.assertEqual(descriptor["pluginTarget"]["videoId"], "dQw4w9WgXcQ")

    def test_stack_url_exposes_its_parts(self) -> None:
        descriptor = media.describe("stack://https://a/1.mkv , https://a/2.mkv")
        self.assertEqual(descriptor["streamType"], media.STREAM_STACK)
        self.assertEqual(descriptor["parts"], ["https://a/1.mkv", "https://a/2.mkv"])

    def test_drm_payload_is_parsed(self) -> None:
        raw = (
            "DRM|{'protocol': 'mpd', 'manifest': 'https://cdn.example/m.mpd', "
            "'mimetype': 'application/dash+xml', 'licence_type': 'com.widevine.alpha', "
            "'licence_url': 'https://lic.example/wv', "
            "'licence_header': 'User-Agent=UA&Referer=https%3A%2F%2Fx%2F', "
            "'post_data': 'R{SSM}', 'response_data': ''}"
        )
        descriptor = media.describe(raw)

        self.assertEqual(descriptor["url"], "https://cdn.example/m.mpd")
        self.assertEqual(descriptor["streamType"], media.STREAM_DASH)
        self.assertTrue(descriptor["adaptive"])
        self.assertEqual(descriptor["drm"]["scheme"], "com.widevine.alpha")
        self.assertEqual(descriptor["drm"]["licenseUrl"], "https://lic.example/wv")
        self.assertEqual(descriptor["drm"]["licenseHeaders"]["User-Agent"], "UA")
        self.assertEqual(descriptor["drm"]["postData"], "R{SSM}")

    def test_drm_without_a_licence_server_is_rejected(self) -> None:
        raw = (
            "DRM|{'protocol': 'mpd', 'manifest': 'https://cdn.example/m.mpd', "
            "'mimetype': 'application/dash+xml', 'licence_type': 'com.widevine.alpha'}"
        )
        with self.assertRaises(media.UnplayableSource):
            media.describe(raw)

    def test_malformed_drm_payload_is_rejected_not_evaluated(self) -> None:
        # literal_eval, never eval: a provider response must not be executable.
        with self.assertRaises(media.UnplayableSource):
            media.describe("DRM|__import__('os').system('true')")

    def test_empty_and_header_only_values_are_rejected(self) -> None:
        with self.assertRaises(media.UnplayableSource):
            media.describe("")
        with self.assertRaises(media.UnplayableSource):
            media.describe("|User-Agent=UA")

    def test_non_header_pipe_pairs_are_kept_as_properties(self) -> None:
        descriptor = media.describe(
            "https://x/y.m3u8|Referer=https%3A%2F%2Fz%2F&mimetype=application/x-mpegURL"
        )
        self.assertEqual(descriptor["properties"]["mimetype"], "application/x-mpegURL")
        self.assertNotIn("mimetype", descriptor["headers"])


class ContractTest(unittest.TestCase):
    def test_bridge_version_matches_the_kotlin_constant(self) -> None:
        source = (
            REPO / "app/src/main/java/com/foxtv/app/core/fanfilm/FanFilmRuntime.kt"
        ).read_text(encoding="utf-8")
        expected = f'EXPECTED_BRIDGE_VERSION = "{BRIDGE_VERSION}"'
        self.assertIn(
            expected,
            source,
            "FanFilmRuntime.EXPECTED_BRIDGE_VERSION must track foxtv_fanfilm.version",
        )

    def test_bridge_class_name_matches_the_kotlin_object(self) -> None:
        from foxtv_fanfilm import bridge

        self.assertEqual(bridge._BRIDGE_CLASS, "com.foxtv.app.core.fanfilm.FanFilmBridge")
        source = (
            REPO / "app/src/main/java/com/foxtv/app/core/fanfilm/FanFilmBridge.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("object FanFilmBridge", source)
        self.assertIn("package com.foxtv.app.core.fanfilm", source)


if __name__ == "__main__":
    unittest.main()
