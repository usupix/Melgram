#!/usr/bin/env python3
"""
Builds the built-in Melgram launcher icons (switchable in the app).

Usage: python3 make_icon_set.py <res-dir> <picture>:<mode>[:<bias>] ...
  mode "cover" - fill the whole icon, cropping the picture (bias 0..1 = which part
                 of a tall/wide picture stays: 0 top/left, 0.5 centre, 1 bottom/right)
  mode "fit"   - show the whole picture (good for memes with text) on a blurred copy
Icon N gets resources melgram_icon_N_bg / melgram_icon_N / melgram_icon_N_round.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

DENS = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
CANVAS = 864  # working size of the 108dp canvas (8x xxxhdpi/4)


def crop_square(img, bias):
    w, h = img.size
    s = min(w, h)
    if h > w:
        top = int(round((h - s) * bias))
        return img.crop((0, top, s, top + s))
    left = int(round((w - s) * bias))
    return img.crop((left, 0, left + s, s))


def compose(img, mode, bias):
    img = img.convert("RGB")
    if mode == "cover":
        return crop_square(img, bias).resize((CANVAS, CANVAS), Image.LANCZOS)
    # fit: blurred cover as background, whole picture inside the visible 72dp circle-safe area
    bg = crop_square(img, 0.5).resize((CANVAS, CANVAS), Image.LANCZOS).filter(ImageFilter.GaussianBlur(CANVAS // 24))
    safe = int(CANVAS * 70 / 108)
    w, h = img.size
    k = safe / max(w, h)
    fg = img.resize((max(1, int(w * k)), max(1, int(h * k))), Image.LANCZOS)
    bg.paste(fg, ((CANVAS - fg.size[0]) // 2, (CANVAS - fg.size[1]) // 2))
    return bg


def mask(size, round_):
    m = Image.new("L", (size * 4, size * 4), 0)
    d = ImageDraw.Draw(m)
    if round_:
        d.ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    else:
        d.rounded_rectangle((0, 0, size * 4 - 1, size * 4 - 1), radius=int(size * 4 * 0.22), fill=255)
    return m.resize((size, size), Image.LANCZOS)


def write_icon(res, n, canvas):
    vis = int(round(CANVAS * 72 / 108))
    off = (CANVAS - vis) // 2
    visible = canvas.crop((off, off, off + vis, off + vis))
    for d, k in DENS.items():
        out = os.path.join(res, "mipmap-" + d)
        os.makedirs(out, exist_ok=True)
        bg = int(108 * k)
        canvas.resize((bg, bg), Image.LANCZOS).save(os.path.join(out, "melgram_icon_%d_bg.webp" % n), quality=90, method=6)
        size = int(48 * k)
        inner = int(round(size * 44 / 48))
        pad = (size - inner) // 2
        pic = visible.resize((inner, inner), Image.LANCZOS)
        for suffix, rnd in (("", False), ("_round", True)):
            ic = Image.new("RGBA", (size, size), (0, 0, 0, 0))
            ic.paste(pic, (pad, pad), mask(inner, rnd))
            ic.save(os.path.join(out, "melgram_icon_%d%s.webp" % (n, suffix)), quality=90, method=6)
    anydpi = os.path.join(res, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    for suffix in ("", "_round"):
        with open(os.path.join(anydpi, "melgram_icon_%d%s.xml" % (n, suffix)), "w") as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                    '    <background android:drawable="@mipmap/melgram_icon_%d_bg" />\n'
                    '    <foreground android:drawable="@drawable/melgram_transparent" />\n'
                    '</adaptive-icon>\n' % n)


def main():
    res = sys.argv[1]
    for n, spec in enumerate(sys.argv[2:], start=1):
        parts = spec.split(":")
        path, mode = parts[0], parts[1]
        bias = float(parts[2]) if len(parts) > 2 else 0.5
        write_icon(res, n, compose(Image.open(path), mode, bias))
        print("icon %d <- %s (%s, %.2f)" % (n, os.path.basename(path), mode, bias))


if __name__ == "__main__":
    main()
