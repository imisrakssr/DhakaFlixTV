import sys, math, os
from PIL import Image, ImageDraw, ImageFilter, ImageFont
RES = sys.argv[1]
FONTS = os.environ.get("FONT_DIR", "/usr/share/fonts/opentype/inter")
for d in ("mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi", "drawable-xhdpi"):
    os.makedirs(f"{RES}/{d}", exist_ok=True)
SS = 4  # supersample

def grad(w, h, c1, c2, diag=True):
    img = Image.new("RGBA", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = ((x / w) * 0.6 + (y / h) * 0.4) if diag else y / h
            px[x, y] = tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3)) + (255,)
    return img

BLUE = (30, 110, 255); CYAN = (34, 222, 238)

def mark(size, c1=None, c2=None):
    """D-shaped badge with play triangle cut out (DhakaFlix mark)."""
    S = size * SS
    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    l, t, r, b = S * .10, S * .17, S * .92, S * .83
    h = b - t
    # D: rectangle left half + half circle right
    d.rectangle([l, t, l + (r - l) - h / 2, b], fill=255)
    d.ellipse([r - h, t, r, b], fill=255)
    d.rounded_rectangle([l, t, l + h * .35, b], radius=int(h * .08), fill=255)
    # film perforations on left edge
    for i in range(5):
        y = t + h * (.12 + i * .19)
        d.rounded_rectangle([l + h * .06, y, l + h * .14, y + h * .1], radius=int(h * .02), fill=0)
    # play triangle cutout
    cx, cy = l + (r - l) * .56, (t + b) / 2
    s = h * .26
    d.polygon([(cx - s * .75, cy - s), (cx - s * .75, cy + s), (cx + s * 1.05, cy)], fill=0)
    g = grad(S, S, c1 or BLUE, c2 or CYAN)
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(g, (0, 0), m)
    return out.resize((size, size), Image.LANCZOS)

def bg(w, h, radius_frac=None, b1=(16, 36, 82), b2=(6, 9, 18)):
    S = (w * SS, h * SS)
    g = Image.new("RGBA", S)
    px = g.load()
    for y in range(S[1]):
        for x in range(S[0]):
            dx = (x - S[0] * .5) / S[0]; dy = (y - S[1] * .35) / S[1]
            t = min(1, math.sqrt(dx * dx + dy * dy) * 1.6)
            c1 = b1; c2 = b2
            px[x, y] = tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3)) + (255,)
    if radius_frac:
        mask = Image.new("L", S, 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, S[0] - 1, S[1] - 1], radius=int(S[0] * radius_frac), fill=255)
        o = Image.new("RGBA", S, (0, 0, 0, 0)); o.paste(g, (0, 0), mask); g = o
    return g.resize((w, h), Image.LANCZOS)

def glow(img, r, col=(34, 160, 255)):
    a = img.split()[3].filter(ImageFilter.GaussianBlur(r))
    gl = Image.new("RGBA", img.size, col + (0,)); gl.putalpha(a.point(lambda v: int(v * .55)))
    return gl

# name: (mark gradient start, mark gradient end, background centre, background edge)
PALETTES = {
    "classic": (BLUE, CYAN, (16, 36, 82), (6, 9, 18)),
    "sunset": ((255, 122, 40), (255, 64, 150), (70, 20, 34), (12, 6, 10)),
    "emerald": ((16, 200, 120), (170, 255, 120), (8, 56, 40), (4, 10, 8)),
    "royal": ((140, 80, 255), (255, 96, 210), (44, 18, 88), (8, 6, 18)),
    "crimson": ((229, 9, 20), (255, 110, 70), (64, 8, 10), (10, 4, 4)),
    "gold": ((255, 186, 40), (255, 236, 150), (40, 32, 12), (6, 6, 6)),
    "mono": ((255, 255, 255), (190, 200, 220), (40, 44, 54), (8, 9, 12)),
}

def icon(size, path, round_=False, pal=None):
    pal = pal or PALETTES["classic"]
    base = bg(size, size, .22 if not round_ else .5, pal[2], pal[3])
    mk = mark(int(size * .78), pal[0], pal[1])
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    off = ((size - mk.width) // 2, (size - mk.height) // 2)
    layer.paste(mk, off, mk)
    base = Image.alpha_composite(base, glow(layer, size * .04, pal[1]))
    base = Image.alpha_composite(base, layer)
    if round_:
        mask = Image.new("L", (size, size), 0); ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
        o = Image.new("RGBA", (size, size), (0, 0, 0, 0)); o.paste(base, (0, 0), mask); base = o
    base.save(path)

for d, s in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    icon(s, f"{RES}/mipmap-{d}/ic_launcher.png")
    icon(s, f"{RES}/mipmap-{d}/ic_launcher_round.png", True)
    for name, pal in PALETTES.items():
        icon(s, f"{RES}/mipmap-{d}/ic_launcher_{name}.png", pal=pal)
        icon(s, f"{RES}/mipmap-{d}/ic_launcher_{name}_round.png", True, pal=pal)

FB = f"{FONTS}/Inter-ExtraBold.otf"
def wordmark_img(h, c1=None, c2=None):
    f = ImageFont.truetype(FB, int(h))
    w1 = f.getlength("Dhaka"); w2 = f.getlength("Flix")
    W = int(w1 + w2 + h * .1); H = int(h * 1.3)
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.text((0, 0), "Dhaka", font=f, fill=(255, 255, 255, 255))
    m = Image.new("L", (W, H), 0); ImageDraw.Draw(m).text((w1, 0), "Flix", font=f, fill=255)
    g = grad(W, H, c1 or BLUE, c2 or CYAN); img.paste(g, (0, 0), m)
    return img.crop(img.getbbox())

# TV banner 320x180dp -> xhdpi 640x360
W, H = 640, 360
def banner(pal, path):
    ban = bg(W, H, None, pal[2], pal[3])
    mk = mark(210, pal[0], pal[1]); lay = Image.new("RGBA", (W, H), (0, 0, 0, 0)); lay.paste(mk, (36, (H - 210) // 2), mk)
    wm = wordmark_img(78, pal[0], pal[1]); lay.paste(wm, (250, H // 2 - wm.height // 2 - 22), wm)
    ft = ImageFont.truetype(f"{FONTS}/Inter-SemiBold.otf", 34)
    ImageDraw.Draw(lay).text((254, H // 2 + 34), "T V", font=ft, fill=(210, 215, 230, 255))
    ban = Image.alpha_composite(ban, glow(lay, 10, pal[1])); ban = Image.alpha_composite(ban, lay)
    ban.convert("RGB").save(path)

banner(PALETTES["classic"], f"{RES}/drawable-xhdpi/tv_banner.png")
for name, pal in PALETTES.items():
    banner(pal, f"{RES}/drawable-xhdpi/tv_banner_{name}.png")

# in-app assets
mark(256).save(f"{RES}/drawable-xhdpi/logo_mark.png")
wordmark_img(96).save(f"{RES}/drawable-xhdpi/logo_wordmark.png")
print("done")
