"""ANSI presentation colors matching the legacy PowerShell ConsoleColor names."""
import os
import sys

CODES = dict(zip(
    ("black", "darkred", "darkgreen", "darkyellow", "darkblue", "darkmagenta",
     "darkcyan", "gray", "darkgray", "red", "green", "yellow", "blue",
     "magenta", "cyan", "white"),
    (30, 31, 32, 33, 34, 35, 36, 37, 90, 91, 92, 93, 94, 95, 96, 97),
))


def colorize(text, color, stream=None):
    stream = sys.stdout if stream is None else stream
    enabled = "NO_COLOR" not in os.environ and (
        os.environ.get("FORCE_COLOR", "0") != "0"
        or (stream.isatty() and os.environ.get("TERM") != "dumb")
    )
    if enabled:
        return f"\033[{CODES.get(color.lower(), 0)}m{text}\033[0m"
    return str(text)


def color_print(color, *values, **kwargs):
    stream = kwargs.get("file", sys.stdout)
    sep = kwargs.pop("sep", " ")
    print(colorize(sep.join(str(value) for value in values), color, stream), **kwargs)
