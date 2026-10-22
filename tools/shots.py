"""README 界面预览截图。

用法：python tools/shots.py
依赖：pip install playwright && playwright install chromium
输出：docs/img/*.png（2x，430x900 视口）

截图动作全部走真实点击（点 tab、点 FAB、点车名），不直接改 DOM ——
预览必须和用户实际看到的一致，绕过交互就等于验了个假页面。
"""
import pathlib
import sys

from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "img"
URL = (ROOT / "demo" / "prototype.html").resolve().as_uri()


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(viewport={"width": 430, "height": 900}, device_scale_factor=2)
        page.goto(URL)
        page.wait_for_timeout(700)

        # 1. 首页
        page.screenshot(path=OUT / "home.png")

        # 2. 录入页（FAB 滑入覆盖层，等动画走完）
        page.click("#openRecord")
        page.wait_for_timeout(600)
        page.screenshot(path=OUT / "record.png")
        page.keyboard.press("Escape")  # 覆盖层没有关闭按钮？稳妥起见重载页面再继续
        page.goto(URL)
        page.wait_for_timeout(700)

        # 3. 历史（右下角更多菜单展开，展示导入导出入口）
        page.click('.tab[data-target="page-history"]')
        page.wait_for_timeout(400)
        page.click("#moreBtn")
        page.wait_for_timeout(300)
        page.screenshot(path=OUT / "history.png")

        # 4. 车辆面板（含每行「编辑」铅笔）
        page.goto(URL)
        page.wait_for_timeout(700)
        page.click("#carSwitch")
        page.wait_for_timeout(600)
        page.screenshot(path=OUT / "vehicles.png")

        browser.close()
    for f in sorted(OUT.glob("*.png")):
        print(f"{f.name}: {f.stat().st_size // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
