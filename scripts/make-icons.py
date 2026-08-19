#!/usr/bin/env python3
"""Regenerate the Android launcher icons from one square source image.

    python3 scripts/make-icons.py ~/Pictures/kefi-player.png

Produces, for every density:
  - ic_launcher.png          legacy square icon
  - ic_launcher_round.png    legacy round icon
  - ic_launcher_foreground.png  adaptive foreground layer

and rewrites values/ic_launcher_background.xml with a colour sampled from the
source, so the adaptive background matches the artwork wherever a launcher's
mask crops a corner.

Requires Pillow (`pip install --user Pillow`).
"""
import statistics
import sys
from pathlib import Path

from PIL import Image, ImageDraw

RES = Path(__file__).resolve().parent.parent / "android/app/src/main/res"

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

# An adaptive layer is 108dp but only the central 72dp is guaranteed visible.
# Pre-shaped artwork is inset to this fraction so the mask can never clip it.
FOREGROUND_SCALE = 0.76
ROUND_SCALE = 0.92

# The launch screen paints this behind the logo. Keep it equal to --bg in
# src/style.css, or the app flashes a different colour before the WebView paints.
BRAND_BG = (0x14, 0x10, 0x0F)

# Fraction of the shorter splash edge taken up by the logo.
SPLASH_LOGO_SCALE = 0.28

# Points that should land on the artwork's own background rather than on any
# foreground detail.
BACKGROUND_PROBES = [
    (0.12, 0.12), (0.09, 0.42), (0.13, 0.88),
    (0.88, 0.13), (0.91, 0.30), (0.50, 0.07),
]


def sample_background(art: Image.Image) -> tuple[int, int, int]:
    w, h = art.size
    seen = []
    for fx, fy in BACKGROUND_PROBES:
        pixel = art.getpixel((int(fx * w), int(fy * h)))
        if pixel[3] > 200:
            seen.append(pixel[:3])
    if not seen:
        return (0, 0, 0)
    return tuple(int(statistics.median(c[i] for c in seen)) for i in range(3))


def circle_mask(size: int) -> Image.Image:
    # Drawn oversized then downsampled, because PIL does not antialias ellipses.
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    return mask.resize((size, size), Image.LANCZOS)


def centred(art: Image.Image, canvas: int, scale: float, background=None) -> Image.Image:
    out = Image.new("RGBA", (canvas, canvas), background or (0, 0, 0, 0))
    inner = int(round(canvas * scale))
    offset = (canvas - inner) // 2
    out.alpha_composite(art.resize((inner, inner), Image.LANCZOS), (offset, offset))
    return out


def write_splashes(art: Image.Image) -> int:
    """Repaints every splash density: brand background with a centred logo."""
    written = 0
    for path in sorted(RES.glob("drawable*/splash.png")):
        w, h = Image.open(path).size
        canvas = Image.new("RGBA", (w, h), BRAND_BG + (255,))
        side = int(round(min(w, h) * SPLASH_LOGO_SCALE))
        logo = art.resize((side, side), Image.LANCZOS)
        canvas.alpha_composite(logo, ((w - side) // 2, (h - side) // 2))
        canvas.convert("RGB").save(path)
        written += 1
    return written


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2

    source = Path(sys.argv[1]).expanduser()
    art = Image.open(source).convert("RGBA")
    if art.width != art.height:
        print(f"warning: {source.name} is {art.width}x{art.height}, not square", file=sys.stderr)

    bg = sample_background(art)
    print("background:", "#%02X%02X%02X" % bg)

    for density, size in LEGACY.items():
        out = RES / f"mipmap-{density}"
        # The source is already icon-shaped, so the square icon is a plain resize.
        art.resize((size, size), Image.LANCZOS).save(out / "ic_launcher.png")

        round_icon = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        round_icon.paste(centred(art, size, ROUND_SCALE, bg + (255,)), (0, 0), circle_mask(size))
        round_icon.save(out / "ic_launcher_round.png")

    for density, size in ADAPTIVE.items():
        centred(art, size, FOREGROUND_SCALE).save(
            RES / f"mipmap-{density}" / "ic_launcher_foreground.png"
        )

    (RES / "values/ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        '    <color name="ic_launcher_background">#%02X%02X%02X</color>\n</resources>\n' % bg
    )
    print(f"wrote icons for {len(LEGACY)} densities")
    print(f"wrote {write_splashes(art)} splash screens")
    return 0


if __name__ == "__main__":
    sys.exit(main())
