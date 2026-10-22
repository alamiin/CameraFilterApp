#!/usr/bin/env python3
"""Writes PNG lookup images (LUTs) in the layout LutCache.fromLookupImage reads.

Layout: a 512x512 image of 8 x 8 tiles, 64 x 64 pixels each. Tile index (left to right, top to
bottom) is the blue level; inside a tile red increases to the right and green increases downwards.

    python3 tools/lut_png.py

writes tools/lut_template.png (the neutral, do-nothing LUT a designer grades in a photo editor)
and regenerates the sample LUTs in app/src/main/assets/luts/. Standard library only.
"""

import colorsys
import os
import struct
import zlib

LEVELS = 64
TILES = 8
SIDE = LEVELS * TILES

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def write_png(path, transform):
    """Renders the lookup image with every colour passed through transform(r, g, b) -> (r, g, b)."""
    rows = bytearray()
    step = 1.0 / (LEVELS - 1)
    for y in range(SIDE):
        rows.append(0)  # PNG filter type: none
        tile_y, g = divmod(y, LEVELS)
        for x in range(SIDE):
            tile_x, r = divmod(x, LEVELS)
            b = tile_y * TILES + tile_x
            out = transform(r * step, g * step, b * step)
            rows.extend(round(min(max(c, 0.0), 1.0) * 255) for c in out)

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", SIDE, SIDE, 8, 2, 0, 0, 0)  # 8-bit RGB
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", header))
        f.write(chunk(b"IDAT", zlib.compress(bytes(rows), 9)))
        f.write(chunk(b"IEND", b""))
    print("wrote", os.path.relpath(path, ROOT))


def hue_window(h, centre, width):
    """1 at centre, falling smoothly to 0 at +/- width (hues in degrees, wrapping)."""
    d = abs((h - centre + 180) % 360 - 180)
    t = max(0.0, 1 - d / width)
    return t * t * (3 - 2 * t)


def shift_hue(r, g, b, centre, width, target, sat_scale, other_sat):
    """Pulls hues near centre towards target; scales saturation inside and outside the window."""
    h, s, v = colorsys.rgb_to_hsv(r, g, b)
    deg = h * 360
    w = hue_window(deg, centre, width) * min(1.0, s * 4)  # leave near-greys alone
    delta = (target - deg + 180) % 360 - 180
    deg = (deg + delta * w * 0.8) % 360
    s = min(1.0, s * (sat_scale * w + other_sat * (1 - w)))
    return colorsys.hsv_to_rgb(deg / 360, s, v)


def autumn(r, g, b):
    # Foliage greens turn amber; everything else gets slightly warmer.
    r, g, b = shift_hue(r, g, b, centre=105, width=65, target=38, sat_scale=1.15, other_sat=1.0)
    return r * 1.05, g, b * 0.9


def emerald(r, g, b):
    # Blues and cyans become teal-green, other colours are muted a little.
    return shift_hue(r, g, b, centre=210, width=60, target=165, sat_scale=1.15, other_sat=0.8)


def velvet(r, g, b):
    # Skin hues lean peach and everything else is muted; a gentle curve lifts the shadows and rolls
    # off the highlights so the softness effect has smooth tones to work with.
    r, g, b = shift_hue(r, g, b, centre=25, width=35, target=18, sat_scale=0.95, other_sat=0.75)

    def tone(x):
        return 0.07 + 0.88 * max(x, 0.0) ** 0.9

    return tone(r) * 1.03, tone(g), tone(b) * 0.97


if __name__ == "__main__":
    write_png(os.path.join(ROOT, "tools", "lut_template.png"), lambda r, g, b: (r, g, b))
    assets = os.path.join(ROOT, "app", "src", "main", "assets", "luts")
    os.makedirs(assets, exist_ok=True)
    write_png(os.path.join(assets, "autumn.png"), autumn)
    write_png(os.path.join(assets, "emerald.png"), emerald)
    write_png(os.path.join(assets, "velvet.png"), velvet)
