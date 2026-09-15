"""FanFilm settings: definition parser, reader and writer.

Kodi addons declare their settings in ``resources/settings.xml`` (schema v1
here: ``section > category > group > setting``) and store values in
``userdata/addon_data/<id>/settings.xml``. FOX.TV renders its own Compose screen
from the declaration, so the whole chain is real:

    resources/settings.xml → parse_definitions() → Compose UI → write_setting()
      → userdata/.../settings.xml → Addon().getSetting() → FanFilm consumer

Two upstream gaps in FanFilm's shim are corrected here, at runtime, without
touching the vendored addon:

``Settings.setBool`` / ``setInt`` / ``setNumber`` are ``pass``
    Only ``setString`` writes anything, so a boolean written through the modern
    K20 API was silently dropped. They are re-pointed at ``setString`` with the
    textual encoding Kodi uses (``true``/``false``, decimal integers).

``Addon.setSetting`` raises ``DeprecatedError``
    FanFilm's own ``SettingsManager.set_string`` calls it, which would abort any
    K19-style write. It is re-pointed at ``getSettings().setString``.

Persistence is also made eager. Upstream only writes ``settings.xml`` from an
``atexit`` handler; an Android process can be killed without ``atexit`` ever
running, which would lose every change made in the session.
"""

from __future__ import annotations

import json
import re
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional
from xml.etree import ElementTree

from . import bridge, paths

ADDON_ID = "plugin.video.fanfilm"

_lock = threading.RLock()
_patched = False

# Settings whose value is a credential; FOX.TV masks them in the UI and never
# logs them. Matched case-insensitively against the setting id.
_SECRET_PATTERN = re.compile(
    r"(password|passwd|api[._-]?key|token|secret|client[._-]?secret|cookie|auth)",
    re.IGNORECASE,
)

# `action` settings run addon code; they are surfaced as buttons, not values.
_ACTION_TYPE = "action"


# ---------------------------------------------------------------------------
# shim corrections
# ---------------------------------------------------------------------------

def install() -> None:
    """Make the shim's settings writers actually write. Idempotent."""
    global _patched
    with _lock:
        if _patched:
            return
        import xbmcaddon

        settings_cls = xbmcaddon.Settings
        addon_cls = xbmcaddon.Addon

        def set_bool(self, id: str, value: bool) -> None:  # noqa: A002
            settings_cls.setString(self, id, "true" if value else "false")

        def set_int(self, id: str, value: int) -> None:  # noqa: A002
            settings_cls.setString(self, id, str(int(value)))

        def set_number(self, id: str, value: float) -> None:  # noqa: A002
            settings_cls.setString(self, id, repr(float(value)))

        def set_setting(self, id: str, value: str) -> None:  # noqa: A002
            self.getSettings().setString(id, "" if value is None else str(value))

        def get_setting_bool(self, id: str) -> bool:  # noqa: A002
            return self.getSettings().getBool(id)

        def get_setting_int(self, id: str) -> int:  # noqa: A002
            try:
                return self.getSettings().getInt(id)
            except (TypeError, ValueError):
                return 0

        def get_setting_number(self, id: str) -> float:  # noqa: A002
            try:
                return self.getSettings().getNumber(id)
            except (TypeError, ValueError):
                return 0.0

        def get_setting_string(self, id: str) -> str:  # noqa: A002
            return self.getSettings().getString(id)

        settings_cls.setBool = set_bool
        settings_cls.setInt = set_int
        settings_cls.setNumber = set_number

        addon_cls.setSetting = set_setting
        # The shim raises DeprecatedError from these getters too, which is fine
        # for FanFilm itself but breaks ResolveURL, whose common.py uses the K19
        # names. Point them at the working implementations.
        addon_cls.getSettingBool = get_setting_bool
        addon_cls.getSettingInt = get_setting_int
        addon_cls.getSettingNumber = get_setting_number
        addon_cls.getSettingString = get_setting_string

        _patched = True
        bridge.log("settings writers patched (bool/int/number + setSetting)", bridge.LOG_INFO)


def _addon(addon_id: str = ADDON_ID):
    import xbmcaddon

    return xbmcaddon.Addon(addon_id)


def values_path(addon_id: str = ADDON_ID) -> Path:
    return paths.addon_data_dir(addon_id) / "settings.xml"


def flush(addon_id: str = ADDON_ID) -> bool:
    """Write pending changes to ``settings.xml`` immediately.

    Returns ``True`` when a file was written. Uses a temp file + replace so a
    process death mid-write cannot leave a truncated settings file.
    """
    with _lock:
        try:
            addon = _addon(addon_id)
            store = addon.getSettings()
        except Exception as exc:  # noqa: BLE001
            bridge.error(f"settings flush failed to open store: {exc}")
            return False

        tree = getattr(store, "_tree", None)
        path = getattr(store, "_path", None)
        if tree is None or path is None:
            bridge.warn("settings store has no backing file; nothing to flush")
            return False

        target = Path(path)
        temp = target.with_name(target.name + ".tmp")
        try:
            target.parent.mkdir(parents=True, exist_ok=True)
            try:
                tree.write(str(temp), pretty_print=True)  # lxml
            except TypeError:
                tree.write(str(temp))  # ElementTree
            temp.replace(target)
            store._dirty = False  # noqa: SLF001 - shim attribute, intentional
            return True
        except OSError as exc:
            bridge.error(f"settings flush failed: {exc}")
            temp.unlink(missing_ok=True)
            return False


# ---------------------------------------------------------------------------
# definitions
# ---------------------------------------------------------------------------

def _localize(addon, raw: Optional[str]) -> str:
    """Resolve a Kodi label, which is either a numeric string id or literal text."""
    text = (raw or "").strip()
    if not text:
        return ""
    if text.isdigit():
        try:
            resolved = addon.getLocalizedString(int(text))
        except Exception:  # noqa: BLE001
            return ""
        # The shim returns "#<id>" when a translation is missing.
        return "" if resolved.startswith("#") else resolved
    return text


def _condition(node: ElementTree.Element) -> Dict[str, Any]:
    """Convert one ``<dependency>`` subtree into a JSON-friendly expression."""
    tag = node.tag.lower()
    if tag in ("and", "or"):
        return {"op": tag, "operands": [_condition(child) for child in node]}
    if tag == "not":
        operands = [_condition(child) for child in node]
        return {"op": "not", "operands": operands}
    if tag == "condition":
        return {
            "op": node.attrib.get("operator", "is").lower(),
            "setting": node.attrib.get("setting", ""),
            "value": (node.text or "").strip(),
        }
    return {"op": "unknown", "tag": tag}


def _dependencies(setting: ElementTree.Element) -> Dict[str, List[Dict[str, Any]]]:
    result: Dict[str, List[Dict[str, Any]]] = {}
    for dependencies in setting.findall("dependencies"):
        for dependency in dependencies.findall("dependency"):
            kind = dependency.attrib.get("type", "enable").lower()
            expressions = [_condition(child) for child in dependency]
            if not expressions:
                # <dependency type="enable" setting="x">true</dependency>
                expressions = [
                    {
                        "op": "is",
                        "setting": dependency.attrib.get("setting", ""),
                        "value": (dependency.text or "").strip(),
                    }
                ]
            result.setdefault(kind, []).extend(expressions)
    return result


def parse_definitions(addon_id: str = ADDON_ID) -> Dict[str, Any]:
    """Parse ``resources/settings.xml`` into the structure the FOX.TV UI renders."""
    addon = _addon(addon_id)
    definition_file = Path(addon.getAddonInfo("path")) / "resources" / "settings.xml"
    if not definition_file.is_file():
        raise FileNotFoundError(f"settings definition missing: {definition_file}")

    root = ElementTree.parse(str(definition_file)).getroot()
    categories: List[Dict[str, Any]] = []

    for section in root.iter("section"):
        for category in section.findall("category"):
            groups: List[Dict[str, Any]] = []
            for group in category.findall("group"):
                items: List[Dict[str, Any]] = []
                for setting in group.findall("setting"):
                    items.append(_parse_setting(addon, setting))
                if items:
                    groups.append(
                        {
                            "id": group.attrib.get("id", ""),
                            "label": _localize(addon, group.attrib.get("label")),
                            "settings": items,
                        }
                    )
            if groups:
                categories.append(
                    {
                        "id": category.attrib.get("id", ""),
                        "label": _localize(addon, category.attrib.get("label")),
                        "help": _localize(addon, category.attrib.get("help")),
                        "groups": groups,
                    }
                )

    return {
        "addonId": addon_id,
        "addonVersion": addon.getAddonInfo("version"),
        "categories": categories,
    }


def _parse_setting(addon, setting: ElementTree.Element) -> Dict[str, Any]:
    setting_id = setting.attrib.get("id", "")
    setting_type = setting.attrib.get("type", "string")
    control = setting.find("control")
    control_type = control.attrib.get("type", "") if control is not None else ""
    control_format = control.attrib.get("format", "") if control is not None else ""

    default_node = setting.find("default")
    default = (default_node.text or "") if default_node is not None else ""

    options: List[Dict[str, str]] = []
    constraints = setting.find("constraints")
    minimum: Optional[float] = None
    maximum: Optional[float] = None
    step: Optional[float] = None
    if constraints is not None:
        for option in constraints.iterfind("options/option"):
            options.append(
                {
                    "label": _localize(addon, option.attrib.get("label")),
                    "value": (option.text or "").strip(),
                }
            )
        minimum = _number(constraints.findtext("minimum"))
        maximum = _number(constraints.findtext("maximum"))
        step = _number(constraints.findtext("step"))

    level_text = setting.findtext("level")
    try:
        level = int(level_text) if level_text is not None else 0
    except ValueError:
        level = 0

    return {
        "id": setting_id,
        "type": setting_type,
        "label": _localize(addon, setting.attrib.get("label")),
        "help": _localize(addon, setting.attrib.get("help")),
        "default": default,
        "level": level,
        "control": {"type": control_type, "format": control_format},
        "options": options,
        "minimum": minimum,
        "maximum": maximum,
        "step": step,
        "dependencies": _dependencies(setting),
        "secret": bool(_SECRET_PATTERN.search(setting_id)),
        "action": setting_type == _ACTION_TYPE,
    }


def _number(text: Optional[str]) -> Optional[float]:
    if text is None:
        return None
    try:
        return float(text.strip())
    except (AttributeError, ValueError):
        return None


# ---------------------------------------------------------------------------
# public API (called from Kotlin)
# ---------------------------------------------------------------------------

def read_settings(addon_id: str = ADDON_ID) -> str:
    """Return definitions plus current values as JSON."""
    with _lock:
        install()
        definitions = parse_definitions(addon_id)
        addon = _addon(addon_id)
        store = addon.getSettings()

        values: Dict[str, str] = {}
        for category in definitions["categories"]:
            for group in category["groups"]:
                for setting in group["settings"]:
                    if setting["action"]:
                        continue
                    setting_id = setting["id"]
                    try:
                        values[setting_id] = store.getString(setting_id)
                    except Exception:  # noqa: BLE001
                        values[setting_id] = setting["default"]

        definitions["values"] = values
        definitions["valuesPath"] = str(values_path(addon_id))
        return json.dumps(definitions, ensure_ascii=False)


def write_setting(setting_id: str, value: str, addon_id: str = ADDON_ID) -> str:
    """Persist one setting and report the value that is now in effect.

    The write goes through the shim so a running plugin instance sees it in
    ``fake_api.SETTINGS`` immediately, and is then flushed to disk so it survives
    a process kill.
    """
    with _lock:
        install()
        try:
            addon = _addon(addon_id)
            store = addon.getSettings()
            store.setString(str(setting_id), "" if value is None else str(value))
            written = flush(addon_id)
            effective = store.getString(str(setting_id))
        except Exception as exc:  # noqa: BLE001
            bridge.error(f"write_setting({setting_id!r}) failed: {exc}")
            return json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False)

        if not _SECRET_PATTERN.search(setting_id):
            bridge.log(f"setting {setting_id}={effective!r} (flushed={written})", bridge.LOG_INFO)
        else:
            bridge.log(f"setting {setting_id}=<redacted> (flushed={written})", bridge.LOG_INFO)

        _invalidate_caches()
        return json.dumps(
            {"ok": True, "id": setting_id, "value": effective, "flushed": written},
            ensure_ascii=False,
        )


def _invalidate_caches() -> None:
    """Drop FanFilm's cached settings object so the next read sees the change.

    ``SettingsManager`` memoises the ``Settings`` instance and derived state
    (log verbosity, provider toggles). ``reset()`` is upstream's own supported
    way of invalidating it.
    """
    try:
        from lib.ff.settings import settings as fanfilm_settings

        fanfilm_settings.reset()
    except Exception as exc:  # noqa: BLE001 - plugin may not be imported yet
        bridge.log(f"settings cache not reset ({exc})", bridge.LOG_DEBUG)
