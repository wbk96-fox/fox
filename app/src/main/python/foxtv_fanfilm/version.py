"""Bridge revision.

Bumped whenever the Python↔Kotlin contract in this package changes. Kotlin
asserts the value it was compiled against so a stale installed asset tree fails
loudly at startup instead of misbehaving at playback time.
"""

BRIDGE_VERSION = "1.0.0"
