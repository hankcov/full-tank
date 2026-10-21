"""
把 adaptive icon 光栅化出来，纯标准库，不依赖 PIL。
用途：改图标时先看效果，别拿 XML 的想象当结果。

几何必须和 res/drawable/ic_launcher_foreground.xml 保持一致：
  液位杯：容器 M29,36 L29,54 A25,25 0 0 0 79,54 L79,36（55% 白）
          液面   M29,41 L79,41（纯白）
  线宽 3.4，圆头
"""
import math
import struct
import zlib
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "design"

BG = (0x16, 0x21, 0x1D)
SS = 4
N = 108 * SS
HALF = 1.7


def seg_dist(px, py, x1, y1, x2, y2):
    """点到线段的距离；线段端点自然形成圆头线帽"""
    dx, dy = x2 - x1, y2 - y1
    l2 = dx * dx + dy * dy
    if l2 == 0:
        return math.hypot(px - x1, py - y1)
    t = max(0.0, min(1.0, ((px - x1) * dx + (py - y1) * dy) / l2))
    return math.hypot(px - (x1 + t * dx), py - (y1 + t * dy))


def render():
    base = tuple(c / 255.0 for c in BG)

    def blend(c, alpha):
        return tuple(v + (1.0 - v) * alpha for v in c)

    def paint(x, y):
        c = base
        # 容器：两壁 + 底部半圆。半圆用距离场：圆心(54,54) r=25，只取 y>=54
        wall = (seg_dist(x, y, 29, 36, 29, 54) <= HALF
                or seg_dist(x, y, 79, 36, 79, 54) <= HALF)
        d = math.hypot(x - 54, y - 54)
        bowl = abs(d - 25) <= HALF and y >= 54
        if wall or bowl:
            c = blend(c, 0.55)
        # 液面：悬在杯口下方 5 个单位，读作「快满」
        if seg_dist(x, y, 29, 41, 79, 41) <= HALF:
            c = blend(c, 1.0)
        return c

    buf = [[base] * N for _ in range(N)]
    for py in range(N):
        y = (py + 0.5) / SS
        for px in range(N):
            buf[py][px] = paint((px + 0.5) / SS, y)

    out = []
    for oy in range(108):
        row = []
        for ox in range(108):
            rs = gs = bs = 0.0
            for sy in range(SS):
                for sx in range(SS):
                    r, g, b = buf[oy * SS + sy][ox * SS + sx]
                    rs += r
                    gs += g
                    bs += b
            k = SS * SS
            row.append((rs / k, gs / k, bs / k))
        out.append(row)
    return out


def write_png(path, pixels, mask=None):
    """mask=36 → 圆形裁剪（launcher 实际显示区域）；None → 方形全图"""
    raw = bytearray()
    for y in range(108):
        raw.append(0)  # filter type 0
        for x in range(108):
            r, g, b = pixels[y][x]
            a = 255
            if mask is not None:
                d = math.hypot(x + 0.5 - 54, y + 0.5 - 54)
                a = int(max(0.0, min(1.0, mask - d + 0.5)) * 255)
            raw += bytes((int(r * 255), int(g * 255), int(b * 255), a))

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", 108, 108, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))
    path.write_bytes(png)


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    px = render()
    write_png(OUT / "icon-square.png", px, mask=None)
    write_png(OUT / "icon-circle.png", px, mask=36.0)
    print("wrote design/icon-square.png, design/icon-circle.png")
