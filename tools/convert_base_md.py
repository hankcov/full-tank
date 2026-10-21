#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 base.md（AI 从截图提取的摩托车加油表）转成本 App 可导入的 CSV。

为什么要写脚本而不是手填：55 行 × 8 列，手抄一定错，而且里程要重算。
脚本的每一步都留了校验输出，跑完能直接看到哪一段对不上。

数据问题（已核对）：
1. 2026/05/10 起「仪表盘总里程」列被读断了（05/10 只有 758，而 04/21 是 8330）。
   这一段的里程按「油耗 × 里程 = 加油量」反推，终点强制落在表里真实的 10665。
2. 2026/03/03 的总里程少读一位（7698 应为 7898），否则该段 37 km 烧 5.05 L。
3. 2024/03/21 是初始记录，没有里程，按 0 处理。
"""
import csv
import re
import sys
from datetime import date
from pathlib import Path

SRC = Path(r"D:\code\github\tmp\base.md")
OUT = Path(r"D:\code\github\carassistant\data\motorcycle-history.csv")

VEHICLE_NAME = "摩托车"
ASSUME_YEAR = 2026  # 只有 MM/DD 的行属于哪一年


def num(s):
    m = re.search(r"(-?[\d.]+)", s)
    return float(m.group(1)) if m else None


def parse_date(s):
    m = re.fullmatch(r"(\d{4})/(\d{1,2})/(\d{1,2})", s)
    if m:
        return date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
    m = re.fullmatch(r"(\d{1,2})/(\d{1,2})", s)
    if m:
        return date(ASSUME_YEAR, int(m.group(1)), int(m.group(2)))
    raise ValueError(f"看不懂的日期: {s}")


def main():
    rows = []
    for line in SRC.read_text(encoding="utf-8").splitlines():
        if not line.startswith("|"):
            continue
        c = [x.strip() for x in line.strip().strip("|").split("|")]
        if len(c) != 8 or c[0] in ("日期", "") or set(c[0]) <= set(":- "):
            continue
        rows.append({
            "date": parse_date(c[0]),
            "l100": num(c[1]),
            "price_marked": num(c[2]),
            "seg_km": num(c[3]),
            "liters": num(c[4]),
            "amount": num(c[5]),
            "odo_raw": num(c[7]),
            "odo_text": c[7],
        })

    if not rows:
        sys.exit("没解析出任何行")

    rows.sort(key=lambda r: r["date"])  # 表里是倒序，改成时间正序

    # ---- 里程修复 ----
    # 04/21 之前的里程是同一张截图里读出来的，内部自洽（偏差多在 1% 内），原样保留，
    # 只补两个已知坏点。
    fix = {date(2026, 3, 3): 7898}
    for r in rows:
        if r["date"] == date(2024, 3, 21):
            r["odo"] = 0
        elif r["date"] in fix:
            r["odo"] = fix[r["date"]]
        else:
            r["odo"] = int(round(r["odo_raw"]))

    # 05/10 起的里程列不可信：从 04/21 的真实读数出发，按每段油耗反推
    last_good = date(2026, 4, 21)
    base_odo = next(r["odo"] for r in rows if r["date"] == last_good)
    repaired = []
    for r in rows:
        if r["date"] <= last_good:
            continue
        if r["l100"] and r["liters"]:
            base_odo = base_odo + r["liters"] / r["l100"] * 100
        r["odo"] = int(round(base_odo))
        r["repaired"] = True
        repaired.append(r)

    # 最后一行是真实读数，以它为准（反推值只能到 10664，差 1 km 是油耗四舍五入的累积）
    rows[-1]["odo"] = int(round(rows[-1]["odo_raw"]))

    # ---- 单调性校验 ----
    for prev, cur in zip(rows, rows[1:]):
        if cur["odo"] <= prev["odo"]:
            sys.exit(f"里程不是递增的: {prev['date']} {prev['odo']} -> {cur['date']} {cur['odo']}")

    # ---- 写 CSV ----
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, lineterminator="\r\n")
        w.writerow(["vehicle", "vehicle_name", "date", "odometer", "liters",
                    "amount", "price", "is_full", "station", "estimated"])
        for r in rows:
            price = round(r["amount"] / r["liters"], 2) if r["liters"] else 0
            w.writerow([
                "", VEHICLE_NAME, r["date"].isoformat(), r["odo"],
                f"{r['liters']:.2f}", f"{r['amount']:.2f}", f"{price:.2f}",
                1, "", 0,
            ])

    # ---- 校验报告 ----
    print(f"写出 {len(rows)} 条 -> {OUT}")
    print(f"里程 {rows[0]['odo']} → {rows[-1]['odo']} km，共 {rows[-1]['odo'] - rows[0]['odo']} km")
    print(f"累计加油 {sum(r['liters'] for r in rows):.2f} L，累计花费 {sum(r['amount'] for r in rows):.2f} 元")
    print(f"其中 {len(repaired)} 条里程是按油耗反推的（2026/05/10 起）")
    print("\n偏差 > 10% 的区间（这些行本身的油耗/升数可能有误）：")
    bad = 0
    for prev, cur in zip(rows, rows[1:]):
        seg = cur["odo"] - prev["odo"]
        if not cur["l100"]:
            continue
        expect = seg * cur["l100"] / 100
        dev = abs(expect - cur["liters"]) / cur["liters"] * 100
        if dev > 10:
            bad += 1
            print(f"  {cur['date']}  区间 {seg} km  加 {cur['liters']:.2f} L  "
                  f"表里写 {cur['l100']:.2f}  应为 {expect:.2f} L  偏差 {dev:.0f}%")
    if not bad:
        print("  无")


if __name__ == "__main__":
    main()
