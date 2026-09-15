"""Plugin invocation.

Kodi runs one plugin call at a time per addon (``reuselanguageinvoker`` reuses
the interpreter but serialises the calls), and FanFilm relies on that:
``sys.argv`` and ``xbmcplugin._directory`` are module-level state. FOX.TV keeps
the same guarantee with :data:`_execution_lock`.

Two properties matter for the UI:

*Cancellation is real.* Every run carries an id. Kotlin can retire an id, and
the Python side both refuses to publish results for a retired run and reports
the retirement through the progress/dialog paths that FanFilm itself polls.

*The lock is released while a user is being asked something.* Dialogs block the
worker thread; holding the execution lock across them would deadlock a
subsequent run (and, before that, make cancellation take effect only after the
user answered). :func:`released_lock` is the single supported way to do that.
"""

from __future__ import annotations

import contextlib
import json
import threading
import traceback
from typing import Any, Dict, Iterator, Optional

from . import bridge, environment, listing, ui

#: Serialises plugin invocations, mirroring Kodi's per-addon behaviour.
_execution_lock = threading.RLock()

#: How long a run waits for a previous run to finish before giving up.
_LOCK_TIMEOUT_SECONDS = 90.0

#: Run ids Kotlin has retired.
_cancelled: set[int] = set()
_cancelled_lock = threading.Lock()

_depth = threading.local()


def cancel_run(run_id: int) -> None:
    """Retire *run_id*. Called from Kotlin; also observed inside Python."""
    with _cancelled_lock:
        _cancelled.add(int(run_id))
        # Keep the set from growing without bound over a long session.
        if len(_cancelled) > 256:
            for stale in sorted(_cancelled)[:128]:
                _cancelled.discard(stale)


def is_cancelled(run_id: int) -> bool:
    if run_id <= 0:
        return False
    with _cancelled_lock:
        if int(run_id) in _cancelled:
            return True
    return bridge.is_cancelled(run_id)


@contextlib.contextmanager
def released_lock() -> Iterator[None]:
    """Temporarily release the execution lock held by this thread.

    ``RLock`` tracks its owner, so the count is unwound fully and restored
    afterwards. When the current thread does not hold the lock (for example a
    dialog raised from the service thread) this is a no-op.
    """
    count = 0
    while True:
        try:
            _execution_lock.release()
        except RuntimeError:
            break
        count += 1
    try:
        yield
    finally:
        for _ in range(count):
            _execution_lock.acquire()


@contextlib.contextmanager
def plugin_run(run_id: int, description: str) -> Iterator[None]:
    """Acquire the execution lock and set up per-run state."""
    if not _execution_lock.acquire(timeout=_LOCK_TIMEOUT_SECONDS):
        raise TimeoutError(
            f"another FanFilm call is still running; {description} was not started"
        )
    previous_run = ui.current_run()
    try:
        ui.set_current_run(run_id)
        listing.clear_terminal()
        yield
    finally:
        listing.clear_terminal()
        if previous_run:
            ui.set_current_run(previous_run)
        else:
            ui.clear_current_run()
        _execution_lock.release()
        _collect()


def _collect() -> None:
    """Reclaim cycles left by a plugin run.

    FanFilm builds large object graphs (metadata, parsed HTML, cattrs
    structures). Kodi drops the whole interpreter between invocations when
    ``reuselanguageinvoker`` is off; FOX.TV keeps it, so an explicit collection
    after each run keeps the resident set from creeping up over a session. Only
    the youngest generation is swept unless the heap is actually under pressure.
    """
    import gc

    try:
        gc.collect(0)
    except Exception:  # noqa: BLE001
        pass


def _error_payload(run_id: int, kind: str, message: str, *, detail: str = "") -> str:
    return json.dumps(
        {
            "runId": int(run_id),
            "kind": kind,
            "message": message,
            "detail": detail,
        },
        ensure_ascii=False,
    )


def _report_error(run_id: int, kind: str, message: str, detail: str = "") -> None:
    host = bridge.host()
    payload = _error_payload(run_id, kind, message, detail=detail)
    if host is None:
        bridge.error(payload)
        return
    try:
        host.onRunError(int(run_id), payload)
    except Exception as exc:  # noqa: BLE001
        bridge.error(f"failed to report error ({exc}): {payload}")


def _report_finished(run_id: int) -> None:
    host = bridge.host()
    if host is None:
        return
    try:
        host.onRunFinished(int(run_id))
    except Exception as exc:  # noqa: BLE001
        bridge.error(f"failed to report completion: {exc}")


def run_plugin(plugin_url: str, run_id: int) -> str:
    """Execute ``default.py`` for *plugin_url*.

    Results are delivered through the callbacks in :mod:`.listing`; the return
    value is a JSON status so Kotlin can distinguish "ran and produced nothing"
    from "could not run".
    """
    run_id = int(run_id)
    url = str(plugin_url or "")
    if not environment.is_configured():
        _report_error(run_id, "configuration", "FanFilm runtime is not configured")
        return json.dumps({"ok": False, "kind": "configuration"})

    if is_cancelled(run_id):
        return json.dumps({"ok": False, "kind": "cancelled"})

    default_py = environment.plugin_root() / "default.py"
    if not default_py.is_file():
        _report_error(run_id, "plugin", f"default.py missing at {default_py}")
        return json.dumps({"ok": False, "kind": "plugin"})

    # Kodi passes (plugin_url, handle, query) in sys.argv.
    base, _, query = url.partition("?")
    argv = [url, "0", f"?{query}" if query else ""]

    try:
        with plugin_run(run_id, f"run_plugin({url!r})"):
            import sys

            listing.reset_directory()
            environment.prepare_embedded_lifecycle()
            saved_argv = sys.argv
            sys.argv = argv
            try:
                namespace = {"__name__": "__main__", "__file__": str(default_py)}
                source = default_py.read_text(encoding="utf-8")
                exec(compile(source, str(default_py), "exec"), namespace)  # noqa: S102
            except SystemExit as exc:
                # Kodi uses SystemExit to terminate a plugin invocation. In a
                # long-lived Chaquopy host, navigation with no directory/playback
                # output is NOT a successful generic action: it is an observable
                # lifecycle outcome so the UI can recover instead of showing a
                # misleading empty directory.
                if not listing.had_terminal() and url.startswith("plugin://"):
                    _report_error(run_id, "system_exit_no_output",
                                  "FanFilm navigation exited without publishing a directory or playback result",
                                  detail=f"SystemExit code={getattr(exc, 'code', None)!r}")
            finally:
                sys.argv = saved_argv

            if is_cancelled(run_id):
                return json.dumps({"ok": False, "kind": "cancelled"})

            if listing.had_terminal():
                return json.dumps({"ok": True, "kind": "delivered"})

            # Pure actions may legitimately finish without output. Browser
            # navigation URLs are different: no output is a lifecycle failure,
            # not an empty directory. SystemExit has already reported the
            # specific failure above; this branch covers silent no-output exits.
            if url.startswith("plugin://"):
                _report_error(run_id, "no_directory_published",
                              "FanFilm navigation completed without publishing a directory or playback result")
                return json.dumps({"ok": False, "kind": "no_directory_published"})
            _report_finished(run_id)
            return json.dumps({"ok": True, "kind": "action"})
    except TimeoutError as exc:
        _report_error(run_id, "busy", str(exc))
        return json.dumps({"ok": False, "kind": "busy"})
    except Exception as exc:  # noqa: BLE001
        detail = traceback.format_exc(limit=12)
        bridge.error(f"run_plugin({url!r}) failed: {exc}\n{detail}")
        _report_error(run_id, "plugin", str(exc) or type(exc).__name__, detail)
        return json.dumps({"ok": False, "kind": "plugin"})


def run_locked(run_id: int, description: str, action: Any) -> Dict[str, Any]:
    """Run *action* under the execution lock, returning a status dict.

    Shared by :mod:`.provider` so source discovery and resolution obey the same
    serialisation and cancellation rules as a plugin invocation.
    """
    run_id = int(run_id)
    if not environment.is_configured():
        return {"ok": False, "kind": "configuration", "message": "FanFilm runtime is not configured"}
    if is_cancelled(run_id):
        return {"ok": False, "kind": "cancelled", "message": "cancelled"}
    try:
        with plugin_run(run_id, description):
            if is_cancelled(run_id):
                return {"ok": False, "kind": "cancelled", "message": "cancelled"}
            return {"ok": True, "value": action()}
    except TimeoutError as exc:
        return {"ok": False, "kind": "busy", "message": str(exc)}
    except Exception as exc:  # noqa: BLE001
        detail = traceback.format_exc(limit=12)
        bridge.error(f"{description} failed: {exc}\n{detail}")
        return {
            "ok": False,
            "kind": "plugin",
            "message": str(exc) or type(exc).__name__,
            "detail": detail,
        }


def current_run_id() -> int:
    return ui.current_run()


def lock_depth() -> int:
    return int(getattr(_depth, "value", 0))


def diagnostics() -> Dict[str, Optional[int]]:
    with _cancelled_lock:
        cancelled = len(_cancelled)
    return {"cancelledRuns": cancelled, "currentRun": ui.current_run()}
