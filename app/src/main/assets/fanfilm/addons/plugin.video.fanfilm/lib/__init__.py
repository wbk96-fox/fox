import sys
from pathlib import Path
from typing import List

#: Debug leftover objects in memory on exit (for memory leak detection).
#: 0 - debug code removed,
#: 1 - dump code left in
#: 2 - dump code left in + dump traceback of Kodi objects (now: Addons)
DEBUG_LEFTOVER_OBJECTS: int = 0
#: Modules to remove on exit to free Kodi objects (Addons, ListItems, etc.) from memory.
MODULES_TO_REMOVE_ON_EXIT: 'set[str]' = set()  # {'resolveurl', 'inputstreamhelper'}


if DEBUG_LEFTOVER_OBJECTS >= 2:
    import traceback
    import xbmcaddon, xbmcgui
    class Addon(xbmcaddon.Addon):
        def __new__(cls, *args, **kwargs):
            obj = super().__new__(cls, *args, **kwargs)
            obj._traceback = ''.join(traceback.format_stack())
            return obj
    xbmcaddon.Addon = Addon
    # class ListItem(xbmcgui.ListItem):
    #     def __new__(cls, *args, **kwargs):
    #         obj = super().__new__(cls, *args, **kwargs)
    #         obj._traceback = ''.join(traceback.format_stack())
    #         return obj
    # xbmcgui.ListItem = ListItem


def is_subinterpreter() -> bool:
    """Detect if module is called in subinterpreter (Kodi) or not (command line)."""
    from traceback import format_stack
    st = format_stack()
    return bool(st and 'fanfilm' in st[0] and '/_dev_' not in st[0])


SUBINTERPRETER: bool = is_subinterpreter()
cmdline_argv: List[str] = []
FAKE: bool = not SUBINTERPRETER
MOCK: bool = False

# Path to the top-level fanfilm folder.
top_ff_path = Path(__file__).parent
# Add paths to 3rd-party libs.
sys.path.insert(0, str(top_ff_path / '3rd'))
sys.path.insert(0, str(top_ff_path / '3rd' / 'jwgraph'))  # contains a few modules
# Add fake xmbc modules (DEBUG & TESTS).
if FAKE:
    import os
    sys.path.insert(0, str(top_ff_path / 'fake'))
    if os.environ.get('XBMC_MOCK') == '1':
        sys.path.insert(0, str(top_ff_path / 'fake' / 'raw'))
        MOCK = True
    # Fake sys.argv for DEBUG & TESTS from command line
    cmdline_argv, sys.argv = sys.argv, ['plugin://fanfilm/', '0', '']
    from lib.fake.fake_api import auto
    auto(cmdline_argv)
    del auto


# Monkey-patching datetime.strptime
# see: https://forum.kodi.tv/showthread.php?tid=112916&pid=2953239
# see: https://bugs.python.org/issue27400
import datetime as datetime_module            # noqa: E402
from datetime import datetime as _datetime    # noqa: E402

# Checked in real code, fixed in py3.13.
if sys.version_info >= (3, 11):
    datetime = _datetime
elif not getattr(datetime_module, '_datetime_is_patched', False):

    class datetime(_datetime):

        @classmethod
        def strptime(cls, date_string: str, format: str) -> _datetime:
            try:
                return _dt_strptime(date_string, format)
            except TypeError:
                import time
                return datetime(*(time.strptime(date_string, format)[0:6]))

    _dt_strptime = _datetime.strptime
    datetime_module.datetime = datetime
    datetime_module._datetime = _datetime
    datetime_module._datetime_is_patched = True
