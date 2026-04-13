"""Generate mipmap launcher assets from tools/branding/launcher_source.png."""
from __future__ import annotations

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "tools" / "branding" / "launcher_source.png"
RES = ROOT / "app" / "src" / "main" / "res"

FOREGROUND = {
    "mipmap-mdpi": (108, 108),
    "mipmap-hdpi": (162, 162),
    "mipmap-xhdpi": (216, 216),
    "mipmap-xxhdpi": (324, 324),
    "mipmap-xxxhdpi": (432, 432),
}

LEGACY = {
    "mipmap-mdpi": (48, 48),
    "mipmap-hdpi": (72, 72),
    "mipmap-xhdpi": (96, 96),
    "mipmap-xxhdpi": (144, 144),
    "mipmap-xxxhdpi": (192, 192),
}

# Android TV banner (xhdpi baseline in this project)
BANNER_SIZE = (320, 180)


def cover_crop(src: Image.Image, tw: int, th: int) -> Image.Image:
    """Scale to cover (tw, th), center-crop."""
    sw, sh = src.size
    scale = max(tw / sw, th / sh)
    nw = int(sw * scale)
    nh = int(sh * scale)
    scaled = src.resize((nw, nh), Image.Resampling.LANCZOS)
    left = (nw - tw) // 2
    top = (nh - th) // 2
    return scaled.crop((left, top, left + tw, top + th))


def main() -> None:
    if not SRC.is_file():
        raise SystemExit(f"Missing source image: {SRC}")

    im = Image.open(SRC).convert("RGBA")
    w, h = im.size
    side = min(w, h)
    icon = im.crop((0, 0, side, side))

    r, g, b, _a = icon.getpixel((max(4, side // 120), max(4, side // 120)))
    bg = (r, g, b, 255)

    for folder, (tw, th) in FOREGROUND.items():
        out = RES / folder / "ic_launcher_foreground.png"
        icon.resize((tw, th), Image.Resampling.LANCZOS).save(out, format="PNG", optimize=True)
        print("wrote", out.relative_to(ROOT))

    scale = 0.88
    for folder, (tw, th) in LEGACY.items():
        out = RES / folder / "ic_launcher.png"
        canvas = Image.new("RGBA", (tw, th), bg)
        small_w = int(tw * scale)
        small_h = int(th * scale)
        sm = icon.resize((small_w, small_h), Image.Resampling.LANCZOS)
        ox = (tw - small_w) // 2
        oy = (th - small_h) // 2
        canvas.paste(sm, (ox, oy), sm)
        canvas.save(out, format="PNG", optimize=True)
        print("wrote", out.relative_to(ROOT))

    tw, th = BANNER_SIZE
    banner = cover_crop(im, tw, th)
    for name in ("ic_banner.png", "ic_banner_foreground.png"):
        out = RES / "mipmap-xhdpi" / name
        banner.convert("RGBA").save(out, format="PNG", optimize=True)
        print("wrote", out.relative_to(ROOT))

    print("ic_launcher_background / ic_banner_background: #%02x%02x%02x" % (r, g, b))


if __name__ == "__main__":
    main()
