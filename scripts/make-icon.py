#!/usr/bin/env python3
"""Generate AutoRoot's launcher icon: a crowned Android (root = king) on a
deep purple-to-cyan gradient. Draws once at high resolution and downsamples so
every density is crisp."""
import math
import os

from PIL import Image, ImageDraw, ImageFilter

RES = r"<repo>\app\app\src\main\res"
S = 1024  # master size


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def gradient(size, top, mid, bottom):
    img = Image.new("RGB", (size, size))
    d = ImageDraw.Draw(img)
    for y in range(size):
        t = y / (size - 1)
        c = lerp(top, mid, t / 0.55) if t < 0.55 else lerp(mid, bottom, (t - 0.55) / 0.45)
        d.line([(0, y), (size, y)], fill=c)
    return img


def rounded_mask(size, radius):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return m


def draw_art(size, with_bg=True, scale=1.0):
    """with_bg=True -> legacy icon (square, rounded). False -> adaptive foreground."""
    base = gradient(size, (27, 16, 53), (58, 28, 122), (12, 74, 110)) if with_bg else Image.new("RGBA", (size, size), (0, 0, 0, 0))
    img = base.convert("RGBA")
    d = ImageDraw.Draw(img)

    cx, cy = size / 2, size / 2
    k = size / 1024.0 * scale

    # ---- glow behind the robot ----
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gr = 300 * k
    gd.ellipse([cx - gr, cy - gr * 0.75, cx + gr, cy + gr * 0.95], fill=(120, 220, 255, 60))
    glow = glow.filter(ImageFilter.GaussianBlur(60 * k))
    img = Image.alpha_composite(img, glow)
    d = ImageDraw.Draw(img)

    WHITE = (238, 241, 245, 255)
    GOLD = (255, 206, 74, 255)
    GOLD_D = (222, 160, 30, 255)
    EYE = (34, 26, 56, 255)

    # ---- ANDROID HEAD (dome: rounded top, flat bottom) ----
    hw, hh = 210 * k, 175 * k
    head = [cx - hw, cy - hh * 0.15, cx + hw, cy + hh * 1.15]
    d.rounded_rectangle(head, radius=int(150 * k), fill=WHITE)
    # flatten the very bottom
    d.rectangle([cx - hw, cy + hh * 0.75, cx + hw, cy + hh * 1.15], fill=WHITE)

    # ---- ANTENNAE ----
    for sx in (-1, 1):
        x0 = cx + sx * 120 * k
        y0 = cy - hh * 0.12
        x1 = cx + sx * 165 * k
        y1 = y0 - 95 * k
        d.line([(x0, y0), (x1, y1)], fill=WHITE, width=int(26 * k))
        r = 24 * k
        d.ellipse([x1 - r, y1 - r, x1 + r, y1 + r], fill=WHITE)

    # ---- EYES ----
    for sx in (-1, 1):
        ex = cx + sx * 88 * k
        ey = cy + 62 * k
        r = 30 * k
        d.ellipse([ex - r, ey - r, ex + r, ey + r], fill=EYE)
        r2 = 10 * k
        d.ellipse([ex - r2 - 8 * k, ey - r2 - 10 * k, ex + r2 - 8 * k, ey - r2 + 10 * k], fill=(255, 255, 255, 200))

    # ---- CROWN (sits on top of the head, slightly overlapping) ----
    cw = 205 * k          # half width
    cyb = cy - hh * 0.05  # crown base y
    ct = cyb - 210 * k    # crown tip y
    base_h = 58 * k
    pts = [
        (cx - cw, cyb - base_h),
        (cx - cw, ct),
        (cx - cw * 0.52, cyb - base_h - 58 * k),
        (cx, ct - 34 * k),
        (cx + cw * 0.52, cyb - base_h - 58 * k),
        (cx + cw, ct),
        (cx + cw, cyb - base_h),
    ]
    d.polygon(pts, fill=GOLD)
    # crown band
    d.rounded_rectangle([cx - cw - 10 * k, cyb - base_h, cx + cw + 10 * k, cyb + 20 * k],
                        radius=int(18 * k), fill=GOLD)
    d.rectangle([cx - cw - 10 * k, cyb - base_h + 34 * k, cx + cw + 10 * k, cyb + 20 * k],
                fill=GOLD_D)
    # jewels on the tips
    for jx, jy in ((cx - cw, ct), (cx + cw, ct), (cx, ct - 34 * k)):
        r = 30 * k
        d.ellipse([jx - r, jy - r, jx + r, jy + r], fill=(120, 235, 255, 255))
        d.ellipse([jx - r * 0.45, jy - r * 0.55, jx + r * 0.15, jy + r * 0.05], fill=(255, 255, 255, 225))
    # centre jewel on the band
    r = 26 * k
    d.ellipse([cx - r, cyb - base_h + 22 * k - r, cx + r, cyb - base_h + 22 * k + r], fill=(255, 90, 140, 255))

    # ---- "#" root symbol, bottom-right ----
    hs = 92 * k
    hx, hy = cx + hw * 0.72, cy + hh * 1.02
    w = int(20 * k)
    for off in (-1, 1):
        x = hx + off * hs * 0.42
        d.line([(x - hs * 0.30, hy - hs), (x + hs * 0.30, hy + hs)], fill=(140, 245, 200, 255), width=w)
    for off in (-1, 1):
        y = hy + off * hs * 0.42
        d.line([(hx - hs, y - hs * 0.28), (hx + hs, y + hs * 0.28)], fill=(140, 245, 200, 255), width=w)

    if with_bg:
        img.putalpha(rounded_mask(size, int(size * 0.22)))
    return img


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print(f"  {path}  {img.size[0]}x{img.size[1]}")


# legacy launcher icons (include background, full art)
for name, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    art = draw_art(S, with_bg=True, scale=1.0).resize((px, px), Image.LANCZOS)
    save(art, os.path.join(RES, f"mipmap-{name}", "ic_launcher.png"))
    save(art, os.path.join(RES, f"mipmap-{name}", "ic_launcher_round.png"))

# adaptive foreground: art must sit inside the middle ~66% safe zone
for name, px in (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)):
    art = draw_art(S, with_bg=False, scale=0.60).resize((px, px), Image.LANCZOS)
    save(art, os.path.join(RES, f"mipmap-{name}", "ic_launcher_foreground.png"))

print("done")
