#!/usr/bin/env python3
"""Generate launcher-icon candidates from a pattern by wave function collapse.

The input is a pattern drawing: the PNG the editor saves (its JSON `PixelCollapsePattern` chunk
carries the drawing itself) or any small pixel-art PNG.  The script takes every 3x3 window of that
drawing as a pattern, offers its mirrored and quarter-turned variants as extra patterns (on by
default; disable with `--no-mirror` / `--no-quarter-turn`), solves a wave function collapse over a
`--size` x `--size` grid with *relaxed edges* (the drawing's border is not imposed on the output
border), and writes the best `--n` distinct results as SVGs.

When the PNG carries a pattern document, its saved seed is the base seed and its wrap setting is
used; the first candidate is that exact seed.  A raw drawing gets wrapped windows by default.
`--seed` and `--wrap` override both.

Every candidate fills the canvas with the generated cells, with no safe-zone inset and no invented
shapes; every colour comes from the pattern.  White cells are not written at all, so the icon is
transparent wherever the generated picture is white (or near-white).

Examples
--------
    python design/generate-icons.py
    python design/generate-icons.py --size 9 --n 12
    python design/generate-icons.py --pattern path/to/pattern.png --size 16 --n 6

Requires Pillow (`python -m pip install Pillow`).
"""

from __future__ import annotations

import argparse
import base64
import json
import math
import random
import struct
import sys
from collections import Counter, deque
from pathlib import Path
from xml.sax.saxutils import escape

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover - the message is the useful part
    sys.exit("Pillow is required: python -m pip install Pillow")

HERE = Path(__file__).resolve().parent
DEFAULT_PATTERN = HERE / "pattern.png"
DEFAULT_OUT = HERE / "candidates"
DEFAULT_CANVAS = 108.0
FALLBACK_SEED = 1
PATTERN_METADATA_KEY = "PixelCollapsePattern"

# Cells at or above this on every channel are treated as the pattern's blank and are not written.
# The app's palettes use #ffffff and #fcfcfc for their lightest colour, so "exactly white" would
# leave a near-white square behind on many patterns.
WHITE_LEVEL = 250

# A dense drawing can produce an unreasonable number of distinct 3x3 patterns; fail with guidance
# rather than spending minutes building compatibility tables.
MAX_TILES = 4096
MAX_UNWRAPPED_PIXELS = 100_000


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate icon candidates by wave function collapse from a pattern PNG.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--pattern",
        type=Path,
        default=DEFAULT_PATTERN,
        help="the pattern drawing PNG to read",
    )
    parser.add_argument(
        "--size",
        type=int,
        default=24,
        help="icon resolution in cells (24 means a 24x24-cell icon)",
    )
    parser.add_argument(
        "--n",
        type=int,
        default=32,
        help="number of candidate icons to write",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=DEFAULT_OUT,
        help="directory for the candidate icons",
    )
    parser.add_argument(
        "--canvas",
        type=float,
        default=DEFAULT_CANVAS,
        help="SVG canvas side in dp; the cell grid fills it edge to edge",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=None,
        help="base random seed; defaults to the pattern's saved seed, or 1",
    )
    parser.add_argument(
        "--mirror",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="offer horizontally and vertically mirrored windows",
    )
    parser.add_argument(
        "--quarter-turn",
        action=argparse.BooleanOptionalAction,
        default=True,
        dest="quarter_turn",
        help="offer quarter-turned windows",
    )
    parser.add_argument(
        "--wrap",
        action=argparse.BooleanOptionalAction,
        default=None,
        help="wrap windows across the drawing border; defaults to the pattern setting, or on",
    )
    args = parser.parse_args()

    if args.size < 2:
        parser.error("--size must be at least 2 cells")
    if args.n < 1:
        parser.error("--n must be at least 1")
    if args.canvas <= 0:
        parser.error("--canvas must be positive")
    return args


def load_pattern(path: Path) -> tuple[list[list[tuple[int, int, int, int]]], dict | None]:
    """The drawing's cells and the pattern document, if the PNG carries one."""
    if not path.is_file():
        sys.exit(
            f"No pattern at {path}.\n"
            f"Save a pattern from the app and pass it with --pattern, or put it at {DEFAULT_PATTERN}."
        )
    source = Image.open(path)
    source.load()
    metadata = read_pattern_metadata(source.info)

    if metadata and isinstance(metadata.get("pixelsBase64"), str) and metadata["pixelsBase64"]:
        drawing = decode_drawing(metadata)
    else:
        image = source.convert("RGBA")
        if image.width * image.height > MAX_UNWRAPPED_PIXELS:
            sys.exit(
                f"{path} is {image.width}x{image.height} and has no pattern metadata. "
                f"Use a small drawing, or a pattern saved by the app (which carries its drawing)."
            )
        drawing = [
            [image.getpixel((x, y)) for x in range(image.width)]
            for y in range(image.height)
        ]
    if len(drawing) < 3 or len(drawing[0]) < 3:
        sys.exit(f"The pattern is {len(drawing[0])}x{len(drawing)}; a 3x3 tile needs at least 3x3.")
    return drawing, metadata


def read_pattern_metadata(info: dict) -> dict | None:
    raw = info.get(PATTERN_METADATA_KEY)
    if not raw:
        return None
    try:
        return json.loads(base64.b64decode(raw))
    except (ValueError, TypeError, json.JSONDecodeError):
        return None


def decode_drawing(metadata: dict) -> list[list[tuple[int, int, int, int]]]:
    raw = base64.b64decode(metadata["pixelsBase64"])
    if len(raw) % 4 != 0:
        sys.exit("The pattern's pixel data is not a whole number of ARGB values.")
    values = struct.unpack(f">{len(raw) // 4}i", raw)
    width = int(metadata.get("columns") or 0)
    height = int(metadata.get("rows") or 0)
    if width <= 0 or height <= 0 or width * height != len(values):
        sys.exit("The pattern's pixel data does not match its stated size.")
    drawing: list[list[tuple[int, int, int, int]]] = []
    for y in range(height):
        row: list[tuple[int, int, int, int]] = []
        for x in range(width):
            value = values[y * width + x] & 0xFFFFFFFF
            row.append(
                ((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF, (value >> 24) & 0xFF)
            )
        drawing.append(row)
    return drawing


def settings_from(
    metadata: dict | None,
    mirror: bool,
    quarter_turn: bool,
    wrap_override: bool | None,
) -> tuple[bool, bool, bool, bool]:
    """wrapEdges, mirrorHorizontal, mirrorVertical, quarterTurn.

    Mirrors and quarter turns default to on: the extra tiles make the collapse much less likely to
    run into a dead end, and an icon is better for the variety.  They can be switched off per run.
    Wrapping follows the pattern's own setting when it has one -- a wrapped drawing has no border for
    the edge rule to keep -- and defaults to on for a raw drawing, matching how icons are generated
    from the app's patterns.
    """
    if wrap_override is not None:
        wrap = wrap_override
    elif metadata is not None:
        wrap = bool(metadata.get("wrapEdges", True))
    else:
        wrap = True
    return wrap, mirror, mirror, quarter_turn


def mirror_horizontal(window: tuple) -> tuple:
    return tuple(window[row * 3 + (2 - column)] for row in range(3) for column in range(3))


def mirror_vertical(window: tuple) -> tuple:
    return tuple(window[(2 - row) * 3 + column] for row in range(3) for column in range(3))


def rotate_quarter(window: tuple) -> tuple:
    # 90 degrees clockwise: new[row][column] = old[2 - column][row].
    return tuple(window[(2 - column) * 3 + row] for row in range(3) for column in range(3))


def transformed_windows(
    window: tuple,
    mirror_h: bool,
    mirror_v: bool,
    quarter: bool,
) -> set[tuple]:
    """Every variant the pattern's settings allow (identity when all are off)."""
    variants = {window}
    if not (mirror_h or mirror_v or quarter):
        return variants

    changed = True
    while changed:
        changed = False
        for variant in list(variants):
            if mirror_h:
                mirrored = mirror_horizontal(variant)
                if mirrored not in variants:
                    variants.add(mirrored)
                    changed = True
            if mirror_v:
                mirrored = mirror_vertical(variant)
                if mirrored not in variants:
                    variants.add(mirrored)
                    changed = True
            if quarter:
                turned = rotate_quarter(variant)
                if turned not in variants:
                    variants.add(turned)
                    changed = True
    return variants


def extract_tiles(
    drawing: list[list[tuple[int, int, int, int]]],
    wrap: bool,
    mirror_h: bool,
    mirror_v: bool,
    quarter: bool,
) -> list[tuple]:
    """Every distinct 3x3 window, after the pattern's symmetry settings."""
    height = len(drawing)
    width = len(drawing[0])
    windows: set[tuple] = set()
    for y in (range(height) if wrap else range(height - 2)):
        for x in (range(width) if wrap else range(width - 2)):
            window = tuple(
                drawing[(y + dy) % height][(x + dx) % width] if wrap else drawing[y + dy][x + dx]
                for dy in range(3)
                for dx in range(3)
            )
            windows.update(transformed_windows(window, mirror_h, mirror_v, quarter))
    tiles = sorted(windows)
    if len(tiles) > MAX_TILES:
        sys.exit(
            f"The pattern yields {len(tiles)} distinct 3x3 patterns; this generator is meant for "
            f"a small drawing. Try a simpler pattern."
        )
    return tiles


def build_compatibility(tiles: list[tuple]) -> tuple[list[int], list[int], list[int], list[int]]:
    """Bitmasks of the tiles allowed on each side, for every tile."""
    left_map: dict[tuple, int] = {}
    right_map: dict[tuple, int] = {}
    top_map: dict[tuple, int] = {}
    bottom_map: dict[tuple, int] = {}
    for index, tile in enumerate(tiles):
        bit = 1 << index
        for edge, table in (
            (left_edge(tile), left_map),
            (right_edge(tile), right_map),
            (top_edge(tile), top_map),
            (bottom_edge(tile), bottom_map),
        ):
            table[edge] = table.get(edge, 0) | bit

    left: list[int] = []
    right: list[int] = []
    up: list[int] = []
    down: list[int] = []
    for tile in tiles:
        # A is left of B when A's right edge equals B's left edge, and so on.
        right.append(left_map.get(right_edge(tile), 0))
        left.append(right_map.get(left_edge(tile), 0))
        down.append(top_map.get(bottom_edge(tile), 0))
        up.append(bottom_map.get(top_edge(tile), 0))
    return left, right, up, down


def left_edge(tile: tuple) -> tuple:
    return tuple(tile[row * 3 + column] for row in range(3) for column in (0, 1))


def right_edge(tile: tuple) -> tuple:
    return tuple(tile[row * 3 + column] for row in range(3) for column in (1, 2))


def top_edge(tile: tuple) -> tuple:
    return tuple(tile[row * 3 + column] for row in (0, 1) for column in range(3))


def bottom_edge(tile: tuple) -> tuple:
    return tuple(tile[row * 3 + column] for row in (1, 2) for column in range(3))


def collapse(
    size: int,
    tile_count: int,
    left: list[int],
    right: list[int],
    up: list[int],
    down: list[int],
    seed: int,
) -> list[int] | None:
    """One wave function collapse attempt: a flat N*N list of chosen tile indices, or None."""
    rng = random.Random(seed)
    full = (1 << tile_count) - 1
    wave = [full] * (size * size)
    queue: deque[int] = deque()

    def propagate() -> bool:
        while queue:
            index = queue.popleft()
            domain = wave[index]
            y, x = divmod(index, size)

            allowed = 0
            bits = domain
            while bits:
                low = bits & -bits
                allowed |= right[low.bit_length() - 1]
                bits ^= low
            if x + 1 < size:
                new = wave[index + 1] & allowed
                if new != wave[index + 1]:
                    if not new:
                        return False
                    wave[index + 1] = new
                    queue.append(index + 1)

            allowed = 0
            bits = domain
            while bits:
                low = bits & -bits
                allowed |= left[low.bit_length() - 1]
                bits ^= low
            if x > 0:
                new = wave[index - 1] & allowed
                if new != wave[index - 1]:
                    if not new:
                        return False
                    wave[index - 1] = new
                    queue.append(index - 1)

            allowed = 0
            bits = domain
            while bits:
                low = bits & -bits
                allowed |= down[low.bit_length() - 1]
                bits ^= low
            if y + 1 < size:
                new = wave[index + size] & allowed
                if new != wave[index + size]:
                    if not new:
                        return False
                    wave[index + size] = new
                    queue.append(index + size)

            allowed = 0
            bits = domain
            while bits:
                low = bits & -bits
                allowed |= up[low.bit_length() - 1]
                bits ^= low
            if y > 0:
                new = wave[index - size] & allowed
                if new != wave[index - size]:
                    if not new:
                        return False
                    wave[index - size] = new
                    queue.append(index - size)
        return True

    while True:
        best_count = tile_count + 1
        choices: list[int] = []
        for index, domain in enumerate(wave):
            count = domain.bit_count()
            if count <= 1:
                continue
            if count < best_count:
                best_count = count
                choices = [index]
            elif count == best_count:
                choices.append(index)
        if not choices:
            break

        index = rng.choice(choices)
        domain = wave[index]
        wanted = rng.randrange(domain.bit_count())
        chosen = domain
        for _ in range(wanted):
            chosen &= chosen - 1
        wave[index] = chosen & -chosen
        queue.append(index)
        if not propagate():
            return None

    return [domain.bit_length() - 1 for domain in wave]


def grid_stats(
    grid: list[list[tuple[int, int, int, int]]],
    size: int,
    background: tuple[int, int, int, int],
) -> dict | None:
    flat = [colour for row in grid for colour in row]
    total = size * size
    visible = [colour for colour in flat if colour[3] > 0]
    if not visible:
        return None

    ink = sum(1 for colour in flat if colour[3] > 0 and colour != background) / total
    distinct = len(set(visible))

    def effective(colour: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
        return background if colour[3] == 0 else colour

    differences: list[float] = []
    for y in range(size):
        for x in range(size):
            colour = effective(grid[y][x])
            if x + 1 < size:
                differences.append(colour_distance(colour, effective(grid[y][x + 1])))
            if y + 1 < size:
                differences.append(colour_distance(colour, effective(grid[y + 1][x])))
    contrast = sum(differences) / len(differences) if differences else 0.0

    half = size // 2
    quadrants: list[float] = []
    for quadrant_y in range(2):
        for quadrant_x in range(2):
            cells = [
                grid[y][x]
                for y in range(quadrant_y * half, (quadrant_y + 1) * half)
                for x in range(quadrant_x * half, (quadrant_x + 1) * half)
            ]
            if cells:
                quadrants.append(
                    sum(1 for colour in cells if colour != background and colour[3] > 0) / len(cells)
                )
    quadrant_min = min(quadrants) if quadrants else 0.0

    border = (
        list(grid[0])
        + list(grid[size - 1])
        + [grid[y][0] for y in range(1, size - 1)]
        + [grid[y][size - 1] for y in range(1, size - 1)]
    )
    edge = sum(1 for colour in border if colour != background and colour[3] > 0) / len(border)

    windows: set[tuple] = set()
    for y in range(size - 2):
        for x in range(size - 2):
            windows.add(tuple(grid[y + dy][x + dx] for dy in range(3) for dx in range(3)))

    # Reject blank, solid, single-colour and edgeless results; a collapsed output can otherwise be
    # the all-background tiling, which is valid but not an icon.
    if ink < 0.04 or ink > 0.94 or distinct < 2 or contrast < 3 or edge < 0.04:
        return None

    score = (
        1.4 * (1.0 - abs(ink - 0.42) * 2.0)
        + 0.9 * min(distinct, 6) / 6.0
        + 1.3 * min(contrast / 55.0, 1.0)
        + 0.8 * min(quadrant_min * 6.0, 1.0)
        + 0.5 * min(len(windows) / 20.0, 1.0)
        + 0.6 * min(edge / 0.45, 1.0)
    )
    return {
        "score": score,
        "ink": ink,
        "colours": distinct,
        "contrast": contrast,
        "quadrantMin": quadrant_min,
        "edge": edge,
        "windows": len(windows),
    }


def colour_distance(a: tuple[int, int, int, int], b: tuple[int, int, int, int]) -> float:
    return (abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])) / 3.0


def grid_distance(a: list[list[tuple[int, int, int, int]]], b: list[list[tuple[int, int, int, int]]]) -> float:
    size = len(a)
    different = sum(
        1 for y in range(size) for x in range(size) if a[y][x] != b[y][x]
    )
    return different / (size * size)


def generate_candidates(
    tiles: list[tuple],
    compatibility: tuple[list[int], list[int], list[int], list[int]],
    size: int,
    background: tuple[int, int, int, int],
    wanted: int,
    base_seed: int,
) -> list[dict]:
    left, right, up, down = compatibility
    pool: list[dict] = []
    wanted_pool = max(wanted * 3, wanted + 6)
    max_attempts = max(60, wanted * 20)
    attempt = 0
    while len(pool) < wanted_pool and attempt < max_attempts:
        seed = base_seed + attempt * 104729
        attempt += 1
        indices = collapse(size, len(tiles), left, right, up, down, seed)
        if indices is None:
            continue
        grid = [
            [tiles[indices[y * size + x]][0] for x in range(size)]
            for y in range(size)
        ]
        stats = grid_stats(grid, size, background)
        if stats is None:
            continue
        pool.append({"grid": grid, "seed": seed, **stats})

    if not pool:
        return []

    pool.sort(key=lambda candidate: candidate["score"], reverse=True)
    picked: list[dict] = []
    for threshold in (0.18, 0.10, 0.0):
        for candidate in pool:
            if len(picked) >= wanted:
                break
            if any(existing["seed"] == candidate["seed"] for existing in picked):
                continue
            if threshold > 0 and any(
                grid_distance(candidate["grid"], existing["grid"]) < threshold
                for existing in picked
            ):
                continue
            picked.append(candidate)
        if len(picked) >= wanted:
            break

    if 0 < len(picked) < wanted:
        print(
            f"Only {len(picked)} distinct collapse(s) were available for --n {wanted}; "
            f"try a smaller --size or another --seed."
        )

    # The pattern's own seed is the generation the phone made, so keep that exact collapse first
    # even when a later seed happens to score a little better.
    base = next((candidate for candidate in pool if candidate["seed"] == base_seed), None)
    if base is not None:
        picked = [base] + [candidate for candidate in picked if candidate["seed"] != base["seed"]]
        picked = picked[:wanted]
    return picked


def label_for(index: int, candidate: dict) -> str:
    return f"{index:02d}  seed {candidate['seed']}  {candidate['score']:.2f}"


def hex_colour(colour: tuple[int, int, int, int]) -> str:
    return "#%02x%02x%02x" % colour[:3]


def is_white(colour: tuple[int, int, int, int]) -> bool:
    """True for the pattern's blank colour: white, or the near-white most palettes use."""
    return colour[3] == 0 or min(colour[0], colour[1], colour[2]) >= WHITE_LEVEL


def cell_hex(colour: tuple[int, int, int, int]) -> str:
    if colour[3] == 255:
        return hex_colour(colour)
    return "#%02x%02x%02x%02x" % colour


def svg_rects(grid: list[list[tuple[int, int, int, int]]], size: int, indent: str = "  ") -> list[str]:
    """Horizontal runs as rects, in cell coordinates (viewBox is 0 0 size size)."""
    lines: list[str] = []
    for y, row in enumerate(grid):
        x = 0
        while x < size:
            colour = row[x]
            run = 1
            while x + run < size and row[x + run] == colour:
                run += 1
            if not is_white(colour):
                opacity = "" if colour[3] == 255 else f' fill-opacity="{colour[3] / 255:.3f}"'
                lines.append(
                    f'{indent}<rect x="{x}" y="{y}" width="{run}" height="1" '
                    f'fill="{hex_colour(colour)}"{opacity}/>'
                )
            x += run
    return lines


def write_icon_svg(
    path: Path,
    grid: list[list[tuple[int, int, int, int]]],
    size: int,
    canvas: float,
    title: str,
    description: str,
) -> None:
    body = "\n".join(svg_rects(grid, size))
    path.write_text(
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{canvas:g}" height="{canvas:g}" '
        f'viewBox="0 0 {size} {size}" role="img" aria-label="Pixel Collapse">\n'
        f"  <title>{escape(title)}</title>\n"
        f"  <desc>{escape(description)}</desc>\n"
        f"  <!-- No safe-zone inset; white cells are left transparent. -->\n"
        f"  <g>\n{body}\n  </g>\n"
        f"</svg>\n",
        encoding="utf-8",
    )


def write_contact_svg(path: Path, candidates: list[dict], size: int) -> None:
    columns = min(4, len(candidates))
    tile_width = 180
    tile_height = 210
    padding = 20
    rows = math.ceil(len(candidates) / columns)
    width = padding * 2 + columns * tile_width
    height = padding * 2 + rows * tile_height
    icon_size = 128

    lines = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
        f'viewBox="0 0 {width} {height}">',
        "  <title>Pixel Collapse - icon candidates</title>",
        f'  <rect width="{width}" height="{height}" fill="#201d2b"/>',
    ]
    for index, candidate in enumerate(candidates):
        column = index % columns
        row = index // columns
        x = padding + column * tile_width
        y = padding + row * tile_height
        lines.append(
            f'  <rect x="{x}" y="{y}" width="{tile_width - 16}" height="{tile_height - 16}" '
            f'rx="14" fill="#f6f2e9"/>'
        )
        scale = icon_size / size
        icon_x = x + (tile_width - 16 - icon_size) / 2
        icon_y = y + 14
        lines.append(f'  <g transform="translate({icon_x:.2f} {icon_y:.2f}) scale({scale:.4f})">')
        lines.extend(svg_rects(candidate["grid"], size, indent="    "))
        lines.append("  </g>")
        label = label_for(index + 1, candidate)
        lines.append(
            f'  <text x="{x + (tile_width - 16) / 2:.1f}" y="{y + tile_height - 30}" '
            f'font-family="sans-serif" font-size="11" fill="#201d2b" text-anchor="middle">'
            f"{escape(label)}</text>"
        )
    lines.append("</svg>")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def render_icon_png(grid: list[list[tuple[int, int, int, int]]], size: int, pixels: int) -> Image.Image:
    image = Image.new("RGBA", (pixels, pixels), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    for y in range(size):
        top = round(y * pixels / size)
        bottom = max(top + 1, round((y + 1) * pixels / size))
        for x in range(size):
            colour = grid[y][x]
            if is_white(colour):
                continue
            left = round(x * pixels / size)
            right = max(left + 1, round((x + 1) * pixels / size))
            draw.rectangle((left, top, right - 1, bottom - 1), fill=colour)
    return image


def write_contact_png(path: Path, candidates: list[dict], size: int) -> None:
    scale = 2
    columns = min(4, len(candidates))
    tile_width = 180 * scale
    tile_height = 210 * scale
    padding = 20 * scale
    rows = math.ceil(len(candidates) / columns)
    width = padding * 2 + columns * tile_width
    height = padding * 2 + rows * tile_height
    sheet = Image.new("RGB", (width, height), "#201d2b")
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("arial.ttf", 12 * scale)
    except OSError:
        font = ImageFont.load_default()

    icon_pixels = 128 * scale
    for index, candidate in enumerate(candidates):
        column = index % columns
        row = index // columns
        x = padding + column * tile_width
        y = padding + row * tile_height
        draw.rounded_rectangle(
            (x, y, x + tile_width - 16 * scale, y + tile_height - 16 * scale),
            radius=14 * scale,
            fill="#f6f2e9",
        )
        icon = render_icon_png(candidate["grid"], size, icon_pixels)
        icon_x = x + (tile_width - 16 * scale - icon_pixels) // 2
        icon_y = y + 14 * scale
        sheet.paste(icon, (icon_x, icon_y), icon)
        label = label_for(index + 1, candidate)
        label_width = draw.textlength(label, font=font)
        draw.text(
            (x + (tile_width - 16 * scale - label_width) / 2, y + tile_height - 38 * scale),
            label,
            font=font,
            fill="#201d2b",
        )
    sheet.save(path)


def write_manifest(
    path: Path,
    pattern: Path,
    drawing_size: tuple[int, int],
    metadata: dict | None,
    settings: tuple[bool, bool, bool, bool],
    base_seed: int,
    size: int,
    canvas: float,
    tile_count: int,
    candidates: list[dict],
    files: list[str],
) -> None:
    wrap, mirror_h, mirror_v, quarter = settings
    manifest = {
        "pattern": str(pattern),
        "drawingSize": list(drawing_size),
        "patternName": metadata.get("name") if metadata else None,
        "method": "wave-function-collapse",
        "edges": "relaxed",
        "wrapEdges": wrap,
        "mirrorHorizontal": mirror_h,
        "mirrorVertical": mirror_v,
        "quarterTurn": quarter,
        "tiles": tile_count,
        "baseSeed": base_seed,
        "size": size,
        "canvas": canvas,
        "whiteOmitted": True,
        "candidates": [],
    }
    for index, (candidate, filename) in enumerate(zip(candidates, files)):
        manifest["candidates"].append(
            {
                "file": filename,
                "label": label_for(index + 1, candidate),
                "seed": candidate["seed"],
                "score": round(candidate["score"], 3),
                "ink": round(candidate["ink"], 3),
                "colours": candidate["colours"],
                "contrast": round(candidate["contrast"], 2),
                "edge": round(candidate["edge"], 3),
                "cells": [[cell_hex(colour) for colour in row] for row in candidate["grid"]],
            }
        )
    path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def clear_previous_icons(out: Path) -> None:
    for old in out.glob("icon-*.svg"):
        old.unlink()
    for generated in ("contact-sheet.svg", "contact-sheet.png", "manifest.json"):
        path = out / generated
        if path.is_file():
            path.unlink()


def main() -> None:
    args = parse_args()
    drawing, metadata = load_pattern(args.pattern)
    height = len(drawing)
    width = len(drawing[0])
    settings = settings_from(metadata, args.mirror, args.quarter_turn, args.wrap)
    wrap, mirror_h, mirror_v, quarter = settings
    base_seed = args.seed
    if base_seed is None:
        base_seed = int(metadata.get("seed", FALLBACK_SEED)) if metadata else FALLBACK_SEED
    tiles = extract_tiles(drawing, wrap, mirror_h, mirror_v, quarter)
    compatibility = build_compatibility(tiles)
    background = Counter(
        colour for row in drawing for colour in row
    ).most_common(1)[0][0]

    candidates = generate_candidates(
        tiles,
        compatibility,
        args.size,
        background,
        args.n,
        base_seed,
    )
    if not candidates:
        sys.exit(
            f"The wave function collapse found no non-blank {args.size}x{args.size} result. "
            f"Try another --seed, a smaller --size, or a pattern with more structure."
        )

    args.out.mkdir(parents=True, exist_ok=True)
    clear_previous_icons(args.out)

    pattern_name = metadata.get("name") if metadata and metadata.get("name") else args.pattern.stem
    files: list[str] = []
    for index, candidate in enumerate(candidates, start=1):
        filename = f"icon-{index:02d}-s{candidate['seed']}.svg"
        files.append(filename)
        description = (
            f"Wave function collapse with relaxed edges from the pattern '{pattern_name}': "
            f"{args.size}x{args.size} cells, seed {candidate['seed']}, {len(tiles)} 3x3 patterns. "
            f"{candidate['colours']} colours, {candidate['ink'] * 100:.0f}% ink. "
            f"White cells are left transparent."
        )
        write_icon_svg(
            args.out / filename,
            candidate["grid"],
            args.size,
            args.canvas,
            f"Pixel Collapse - icon candidate {index:02d}",
            description,
        )

    write_contact_svg(args.out / "contact-sheet.svg", candidates, args.size)
    write_contact_png(args.out / "contact-sheet.png", candidates, args.size)
    write_manifest(
        args.out / "manifest.json",
        args.pattern,
        (width, height),
        metadata,
        settings,
        base_seed,
        args.size,
        args.canvas,
        len(tiles),
        candidates,
        files,
    )

    print(
        f"Pattern: {args.pattern} (drawing {width}x{height}"
        + (f", '{pattern_name}'" if metadata and metadata.get("name") else "")
        + ")"
    )
    print(
        f"Tiles: {len(tiles)} distinct 3x3 patterns "
        f"(wrap {'on' if wrap else 'off'}, mirrors "
        f"{'on' if mirror_h or mirror_v else 'off'}, quarter turns "
        f"{'on' if quarter else 'off'}, edges relaxed)"
    )
    print(f"Seed: {base_seed} (the pattern's saved seed)" if args.seed is None else f"Seed: {base_seed}")
    for index, candidate in enumerate(candidates, start=1):
        print(f"  {label_for(index, candidate)}")
    print(
        f"Wrote {len(candidates)} candidate icons, contact-sheet.svg, contact-sheet.png and "
        f"manifest.json to {args.out}"
    )


if __name__ == "__main__":
    main()
