"""Reads the one-time code the app sends as DTMF tones after the line answers."""

from __future__ import annotations

import asyncio
import time
from collections.abc import Callable

CODE_LENGTH = 8


class CodeReader:
    """
    Collects digits from SIP DTMF events. The app sends `<code>#`: at `#` the last [CODE_LENGTH] digits are the code
    (a stray tone before it is ignored); fewer digits start over. `*` starts over too. If the `#` gets lost, the
    digits collected when the time runs out count as the code.
    """

    def __init__(self, clock: Callable[[], float] = time.monotonic) -> None:
        self._digits: asyncio.Queue[str] = asyncio.Queue()
        self._clock = clock

    def feed(self, digit: str) -> None:
        """Called for each `sip_dtmf_received` event."""
        if len(digit) == 1 and digit in "0123456789*#":
            self._digits.put_nowait(digit)

    async def read(self, timeout_s: float) -> str | None:
        """The next code, or None when [timeout_s] passes without one."""
        deadline = self._clock() + timeout_s
        code = ""
        while True:
            remaining = deadline - self._clock()
            digit = None
            if remaining > 0:
                try:
                    digit = await asyncio.wait_for(self._digits.get(), remaining)
                except TimeoutError:
                    digit = None
            if digit is None:
                return code[-CODE_LENGTH:] if len(code) >= CODE_LENGTH else None
            if digit == "#" and len(code) >= CODE_LENGTH:
                return code[-CODE_LENGTH:]
            code = "" if digit in "*#" else code + digit
