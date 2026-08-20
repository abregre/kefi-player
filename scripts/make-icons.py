#!/usr/bin/env python3
"""Regenerate the Android launcher icons from one square source image.

    python3 scripts/make-icons.py ~/Pictures/kefi-player.png

Produces, for every density:
  - ic_launcher.png          legacy square icon
  - ic_launcher_round.png    legacy round icon
  - ic_launcher_foreground.png  adaptive foreground layer

plus:
  - drawable-nodpi/splash_icon.png   the Android 12+ splash icon
  - drawable*/splash.png             the legacy (<= Android 11) splash bitmaps

and rewrites values/ic_launcher_background.xml with a colour sampled from the
source, so the adaptive background matches the artwork wherever a launcher's
mask crops a corner.

Requires Pillow (`pip install --user Pillow`).
"""
import shutil
import statistics
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw

RES = Path(__file__).resolve().parent.parent / "android/app/src/main/res"

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

# An adaptive layer is 108dp but only the central 72dp is guaranteed visible under
# every launcher mask, so the artwork is inset to exactly that: 72/108. Anything
# larger is a bet on the mask being generous, which a circular logo loses — its
# outer ring is the first thing a tight circular mask eats.
FOREGROUND_SCALE = 72 / 108
ROUND_SCALE = 0.92

# The launch screen paints this behind the logo. Keep it equal to --bg in
# src/style.css, or the app flashes a different colour before the WebView paints.
BRAND_BG = (0x08, 0x0A, 0x0A)

# Fraction of the shorter splash edge taken up by the logo (legacy splash bitmaps).
SPLASH_LOGO_SCALE = 0.28

# Android 12+ ignores the splash bitmaps entirely and composes its own splash from
# windowSplashScreenAnimatedIcon over windowSplashScreenBackground (see
# res/values/styles.xml). For an icon WITHOUT its own background the platform asks for
# a 288dp canvas whose artwork lives in the inner 192dp — the same 2/3 as an adaptive
# icon. 1152px is that canvas at 4x, which covers every density from one nodpi asset.
SPLASH_ICON_CANVAS = 1152
SPLASH_ICON_SCALE = 2 / 3

# Points that should land on the artwork's own background rather than on any
# foreground detail.
BACKGROUND_PROBES = [
    (0.12, 0.12), (0.09, 0.42), (0.13, 0.88),
    (0.88, 0.13), (0.91, 0.30), (0.50, 0.07),
]

# Corner squares used to decide whether the artwork is pre-cut (see is_pre_cut).
CORNER_FRACTION = 0.14


def is_pre_cut(art: Image.Image) -> bool:
    """True when the artwork has no background of its own, i.e. its corners are transparent.

    A round logo is the common case, and its corners being empty is the whole point. Asking
    this FIRST matters: `sample_background`'s probes sit close to the edge, so on a circle
    that nearly fills its canvas some of them land on the artwork's own outline instead of on
    empty space. That is how `kefi-4.png` — a green/yellow/red ring — produced a saturated
    #F41A12 adaptive background, which would have shown as bright red corners behind the icon
    under any mask wider than the logo.
    """
    w, h = art.size
    box = (int(w * CORNER_FRACTION), int(h * CORNER_FRACTION))
    corners = [
        (0, 0, box[0], box[1]),
        (w - box[0], 0, w, box[1]),
        (0, h - box[1], box[0], h),
        (w - box[0], h - box[1], w, h),
    ]
    alpha = art.getchannel("A")
    means = [statistics.mean(alpha.crop(c).getdata()) for c in corners]
    # Averaged over a real area rather than single pixels, so one stray antialiased pixel
    # cannot swing the verdict.
    return statistics.mean(means) < 32


def sample_background(art: Image.Image) -> tuple[int, int, int]:
    if is_pre_cut(art):
        # There is no artwork background to match, so the brand background is the only
        # non-arbitrary choice — and it is what the rest of the app paints anyway.
        return BRAND_BG
    w, h = art.size
    seen = []
    for fx, fy in BACKGROUND_PROBES:
        pixel = art.getpixel((int(fx * w), int(fy * h)))
        if pixel[3] > 200:
            seen.append(pixel[:3])
    if not seen:
        return BRAND_BG
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


def compress(paths: list[Path]) -> str:
    """Quantises the generated PNGs in place with pngquant, if it is installed.

    Worth doing: this artwork is a neon gradient, so the splash bitmaps and the 1152px
    splash icon land at ~180KB and ~730KB straight out of Pillow — about 1.5MB of the APK
    for images shown for a few hundred milliseconds. pngquant cuts that by ~70% with no
    difference visible at display size. Optional on purpose: a missing tool must not fail
    an icon regeneration, it just leaves bigger files behind.
    """
    tool = shutil.which("pngquant")
    if tool is None:
        return "pngquant not installed — PNGs left unoptimised"
    before = after = 0
    for path in paths:
        size = path.stat().st_size
        before += size
        done = subprocess.run(
            [tool, "--quality=60-95", "--speed", "1", "--force", "--output", str(path), str(path)],
            capture_output=True,
        )
        # pngquant exits 98/99 when it cannot hit the quality floor; the file is then
        # untouched, which is the right outcome — keep the lossless original.
        after += path.stat().st_size
        if done.returncode not in (0, 98, 99):
            return f"pngquant failed on {path.name}: {done.stderr.decode().strip()}"
    return f"compressed {len(paths)} PNGs: {before // 1024}KB -> {after // 1024}KB"


def write_splash_icon(art: Image.Image) -> Path:
    """Writes the Android 12+ splash icon: logo centred in the inner 2/3, transparent."""
    out = RES / "drawable-nodpi"
    out.mkdir(exist_ok=True)
    path = out / "splash_icon.png"
    centred(art, SPLASH_ICON_CANVAS, SPLASH_ICON_SCALE).save(path)
    return path


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

    # Both colours live in one generated file so a re-run can never leave the launcher
    # and the splash disagreeing about the brand background.
    (RES / "values/ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        '    <color name="ic_launcher_background">#%02X%02X%02X</color>\n'
        '    <color name="splash_background">#%02X%02X%02X</color>\n</resources>\n'
        % (bg + BRAND_BG)
    )
    print(f"wrote icons for {len(LEGACY)} densities")
    print(f"wrote {write_splashes(art)} legacy splash screens")
    splash_icon = write_splash_icon(art)
    print(f"wrote {splash_icon.relative_to(RES)} for the Android 12+ splash")
    print(
        compress(
            [splash_icon, *sorted(RES.glob("drawable*/splash.png")), *sorted(RES.glob("mipmap-*/*.png"))]
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
