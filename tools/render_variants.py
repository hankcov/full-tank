"""
渲染几套图标候选，供挑选用。纯标准库，输出 108×108 PNG。

每个方案一个 paint(x, y) -> (r,g,b) | None，统一 4× 超采样后降采样。
几何与最终写进 ic_launcher_foreground.xml 的 pathData 一致。
"""
import math
import struct
import zlib
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "design" / "options"
SS = 4
N = 108 * SS
BG = (0x16, 0x21, 0x1D)


# ---------- 基础工具 ----------

def mix(base, alpha):
    """白色按 alpha 叠在背景上"""
    return tuple(b / 255.0 + (1.0 - b / 255.0) * alpha for b in base)


def seg_dist(px, py, x1, y1, x2, y2):
    dx, dy = x2 - x1, y2 - y1
    l2 = dx * dx + dy * dy
    if l2 == 0:
        return math.hypot(px - x1, py - y1)
    t = max(0.0, min(1.0, ((px - x1) * dx + (py - y1) * dy) / l2))
    return math.hypot(px - (x1 + t * dx), py - (y1 + t * dy))


def ang(px, py, cx=54.0, cy=54.0):
    return (math.degrees(math.atan2(py - cy, px - cx)) + 360.0) % 360.0


# ---------- 方案 A：双弧油表 ----------

def paint_a(x, y):
    d = math.hypot(x - 54, y - 54)
    on_ring = abs(d - 30) <= 1.7
    if not on_ring:
        # 圆头端点
        for deg in (120, 60, 330):
            r = math.radians(deg)
            if math.hypot(x - (54 + 30 * math.cos(r)), y - (54 + 30 * math.sin(r))) <= 1.7:
                on_ring = True
                break
    if not on_ring:
        return None
    a = ang(x, y)
    c = mix(BG, 0.24) if (a >= 120 or a <= 60) else None
    if 120 <= a <= 330:
        c = mix(BG, 1.0)
    return c or mix(BG, 0.0)


# ---------- 方案 B：竖尺 + 液位 ----------

TICKS = (34, 46, 58, 70)


def paint_b(x, y):
    # 主轴
    if seg_dist(x, y, 54, 26, 54, 82) <= 1.7:
        return mix(BG, 1.0)
    # 左侧刻度
    for ty in TICKS:
        if seg_dist(x, y, 42, ty, 54, ty) <= 1.2:
            return mix(BG, 0.55)
    # 液位指针（右侧长一些，最亮）
    if seg_dist(x, y, 54, 46, 66, 46) <= 1.2:
        return mix(BG, 1.0)
    return None


# ---------- 方案 C：环形刻度 ----------

def paint_c(x, y):
    d = math.hypot(x - 54, y - 54)
    a = ang(x, y)
    # 长刻度：0/90/180/270，从 r=21 到 30
    for deg in (0, 90, 180, 270):
        if abs(((a - deg + 180) % 360) - 180) <= 2.4:
            if 21 <= d <= 31:
                return mix(BG, 1.0)
    # 短刻度：每 30°，从 r=25 到 30
    if a % 30 <= 2.4 or a % 30 >= 27.6:
        if 25 <= d <= 31:
            return mix(BG, 0.5)
    return None


# ---------- 方案 D：U 形液位 ----------

def paint_d(x, y):
    # 两侧竖壁
    if seg_dist(x, y, 29, 36, 29, 54) <= 1.7:
        return mix(BG, 0.55)
    if seg_dist(x, y, 79, 36, 79, 54) <= 1.7:
        return mix(BG, 0.55)
    # 底部半圆
    d = math.hypot(x - 54, y - 54)
    if abs(d - 25) <= 1.7 and y >= 54:
        return mix(BG, 0.55)
    # 液面
    if seg_dist(x, y, 29, 41, 79, 41) <= 1.7:
        return mix(BG, 1.0)
    return None


# ---------- 光栅化 ----------

def raster(paint):
    buf = [[(0.0, 0.0, 0.0)] * N for _ in range(N)]
    base = tuple(c / 255.0 for c in BG)
    for py in range(N):
        y = (py + 0.5) / SS
        for px in range(N):
            x = (px + 0.5) / SS
            c = paint(x, y)
            buf[py][px] = c if c else base
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


def write_png(path, pixels, mask=36.0):
    raw = bytearray()
    for y in range(108):
        raw.append(0)
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
    for name, fn in (("a", paint_a), ("b", paint_b), ("c", paint_c), ("d", paint_d)):
        write_png(OUT / f"{name}.png", raster(fn))
    print("wrote", OUT)
