"""FanFilm's addon service (``xbmc.service`` extension point).

``plugin.video.fanfilm``'s ``addon.xml`` declares::

    <extension point="xbmc.service" library="service.py" start="startup"/>

Kodi runs that script for the lifetime of the application, and the plugin's
client side waits for it (ping/pong through ``xbmc.Monitor``). Without it, plugin
invocations block. FOX.TV therefore starts it on a daemon thread during
:func:`foxtv_fanfilm.environment.configure`.

Shutdown uses the shim's own ``xbmc.Monitor.abortRequested`` contract instead of
killing the thread, so the service unwinds the way it does on a Kodi quit.
"""

from __future__ import annotations

import threading
import traceback
from pathlib import Path
from typing import Optional

from . import bridge

_lock = threading.RLock()
_thread: Optional[threading.Thread] = None
_started = False
_abort = threading.Event()


def is_running() -> bool:
    thread = _thread
    return bool(thread and thread.is_alive())


def start(plugin_root: Path) -> bool:
    """Start ``service.py`` once. Returns whether a thread is running."""
    global _thread, _started
    with _lock:
        if _started and is_running():
            return True
        script = Path(plugin_root) / "service.py"
        if not script.is_file():
            bridge.error(f"FanFilm service.py missing at {script}")
            return False

        _abort.clear()
        _install_abort_hook()

        def run() -> None:
            bridge.log("FanFilm service starting", bridge.LOG_INFO)
            namespace = {"__name__": "__main__", "__file__": str(script)}
            try:
                source = script.read_text(encoding="utf-8")
                exec(compile(source, str(script), "exec"), namespace)  # noqa: S102
                # Reaching this point means the service returned. In Kodi it runs
                # until shutdown, so an early return is worth reporting: plugin
                # calls that wait for the service would otherwise just time out.
                if not _abort.is_set():
                    bridge.warn("FanFilm service returned before shutdown was requested")
            except SystemExit as exc:
                bridge.log(f"FanFilm service exited: {exc}", bridge.LOG_INFO)
            except BaseException:  # noqa: BLE001 - report, never crash the app
                bridge.error("FanFilm service crashed:\n" + traceback.format_exc(limit=12))

        _thread = threading.Thread(target=run, name="foxtv-fanfilm-service", daemon=True)
        _thread.start()
        _started = True
        return True


def stop(timeout: float = 5.0) -> bool:
    """Ask the service to unwind and wait briefly for it.

    Returns ``True`` when no service thread is alive afterwards.
    """
    global _started
    with _lock:
        _abort.set()
        thread = _thread
        if thread is None:
            _started = False
            return True
        try:
            _wake_monitors()
        except Exception as exc:  # noqa: BLE001
            bridge.warn(f"could not wake FanFilm monitors: {exc}")
        thread.join(timeout)
        alive = thread.is_alive()
        if alive:
            # Daemon thread: the process can still exit. Report it rather than
            # pretending the shutdown was clean.
            bridge.warn("FanFilm service did not stop within the grace period")
        else:
            _started = False
        return not alive


def _install_abort_hook() -> None:
    """Make ``xbmc.Monitor.abortRequested``/``waitForAbort`` follow :data:`_abort`.

    The shim's ``Monitor`` never aborts on its own, so the service would loop
    forever after the app asked it to stop.
    """
    import xbmc

    monitor = xbmc.Monitor

    def abort_requested(self) -> bool:  # noqa: ANN001
        return _abort.is_set()

    def wait_for_abort(self, timeout: float = -1) -> bool:  # noqa: ANN001
        if timeout is None or timeout < 0:
            _abort.wait()
            return True
        return _abort.wait(timeout)

    monitor.abortRequested = abort_requested
    monitor.waitForAbort = wait_for_abort


def _wake_monitors() -> None:
    """Nudge anything sleeping in ``xbmc.sleep`` so the abort is observed promptly."""
    import xbmc

    original_sleep = getattr(xbmc, "_foxtv_original_sleep", None)
    if original_sleep is None:
        original_sleep = xbmc.sleep
        xbmc._foxtv_original_sleep = original_sleep  # noqa: SLF001

        def sleep(msec: int) -> None:
            # Interruptible sleep: the service polls in 100–1000 ms steps, so a
            # pending abort is picked up immediately instead of after the sleep.
            if _abort.wait(max(0.0, float(msec) / 1000.0)):
                return

        xbmc.sleep = sleep
