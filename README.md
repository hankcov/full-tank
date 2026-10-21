# 满箱 · full-tank

个人自用的安卓加油记账 App（full-tank fuel log），按满箱法算油耗。数据只存本机，无账号、无广告、无统计。

- 需求：`docs/REQUIREMENTS.md`
- 技术方案与判据：`docs/PLAN.md`
- 交互原型：`demo/prototype.html`（浏览器直接打开）

## 现状

Android 工程可编译出可安装的 APK（包名 `app.manxiang`，release 12 MB）。

已经落地：

| 模块 | 状态 |
|---|---|
| 满箱法油耗算法 + 异常区间隔离 | 完成，单测覆盖判据 A1–A9 |
| 首页指标（当前油耗 / 累计 / 续航） | 完成，单测覆盖 B1–B5 |
| 续航双口径（油箱容积优先，缺省退上次加油量） | 完成 |
| 多车：新建 / 切换 / 编辑 / 按车隔离 | 完成 |
| 录入页（金额 / 升数 / 单价 三者联动） | 完成，含「估」标记 |
| 历史列表 + 异常行标注 + 删除 | 完成 |
| CSV 导出 / 导入（按车牌归车、去重） | 完成，单测覆盖 G1–G6 |
| 趋势折线 | 完成（Canvas 自绘，首页折叠区） |
| 油价联网同步 | **未做** |
| 气温归因 | **未做** |
| 定位带出加油站 | **未做**（需要地图 POI 接口和密钥） |

## 构建

本机环境不在 PATH 里，都在 `D:\android-dev`：

```bash
cd android
export JAVA_HOME=/d/android-dev/jdk-21.0.2
./gradlew assembleRelease         # 出 APK（app/build/outputs/apk/release/）
./gradlew assembleDebug           # debug 版
./gradlew testDebugUnitTest       # 跑 49 个单元测试
```

装到手机（开 USB 调试后）：

```bash
/d/android-dev/sdk/platform-tools/adb.exe install -r app/build/outputs/apk/release/app-release.apk
```

`local.properties` 里的 `sdk.dir` 指向本机 SDK，不要提交。

## 三条不能破的线

1. 一次录入 ≤ 2 个数字 + ≤ 3 次点击。
2. 油耗按「加满事件切分区间」算，未加满的记录只累加油量、不单独出点。
3. 任何按里程的计算必须先按车分组 —— 两辆车里程表读数几乎必然交叠，混在一起会让加满点跨车配对，算出「看起来像个数」的荒谬油耗。

## 关于联网

需要 `INTERNET` 权限（normal 权限，不弹窗）。但网络层只有白名单内的 host 能出站，
拦截器里不在白名单的直接抛异常，不做静默放行。没有广告 SDK、没有统计、没有崩溃上报。
