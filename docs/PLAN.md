# 满箱 · 技术方案与判据

## 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 语言 | Kotlin | — |
| UI | Jetpack Compose + Material 3 | 单 Activity，Navigation Compose 三个目的地 |
| 持久化 | Room (SQLite) | 本地单库，无迁移压力时用 `fallbackToDestructiveMigration` 也需要显式导出 CSV 兜底 |
| 图表 | 自绘 Canvas 或 Vico | 第一版可以只做简单折线，不引入重库 |
| 网络 | OkHttp 单例 + kotlinx.serialization | **唯一**网络客户端，拦截器强制 host 白名单。不引入 Retrofit（省一层，也不给别的库复用连接的机会） |
| 定位 | `FusedLocationProvider` 或系统 `LocationManager` | 仅 `ACCESS_COARSE_LOCATION`，一次性取点后立即释放 |
| 最低版本 | minSdk 26 / targetSdk 35 | — |
| 依赖 | AndroidX + Compose + Room + OkHttp | 无广告、无分析、无推送、无崩溃上报 |

构建：Android Studio 新建 Empty Compose Activity 模板 → 落业务代码。不手搓 Gradle 配置。

## 网络边界

```
res/xml/network_security_config.xml
  <base-config cleartextTrafficPermitted="false"/>
  <domain-config>
    <domain>油价源</domain>
    <domain>气象源</domain>
    <domain>地图源</domain>
  </domain-config>
```

```kotlin
// 全 App 唯一实例；host 不在白名单 → 直接抛异常，不静默放行
object Net {
    private val allowed = setOf("...", "...", "...")
    val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            require(chain.request().url.host in allowed) { "host not allowed" }
            chain.proceed(chain.request())
        }
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()
}
```

超时设 3 秒而不是默认 10 秒，是因为这三个请求全部发生在用户已经打开界面的时刻，等 10 秒等于卡住。宁可失败回落到缓存。

## 数据模型

```kotlin
/* 多车：不再恒为 1。车牌唯一（导入时按车牌归车），允许空串但空串不参与匹配 */
@Entity(tableName = "vehicle", indices = [Index(value = ["plate"], unique = false)])
data class Vehicle(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val plate: String = "",             // 选填；导入按此字段认车
    val tankCapacity: Double = 45.0,    // 升
    val fuelGrade: String = "92",       // 默认标号 92 / 95 / 98，跟着车走
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "fuel_price")
data class FuelPrice(
    @PrimaryKey val grade: String,      // "92" / "95" / "98"
    val price: Double,                  // 元/升，省级最高零售价
    val priceDay: LocalDate,            // 该价格的执行日（调价日）
    val syncedAt: Long,                 // 上次成功同步的时间戳，UI 必须显示
    val source: String = "manual"       // api / manual / fallback
)

/* 历史气温不失效，拉到就存，用于离线也能做归因 */
@Entity(tableName = "daily_temp")
data class DailyTemp(
    @PrimaryKey val date: LocalDate,
    val tempAvg: Double,                // ℃，日平均
    val fetchedAt: Long
)
@Entity(
    tableName = "fuel_record",
    foreignKeys = [ForeignKey(
        entity = Vehicle::class,
        parentColumns = ["id"],
        childColumns = ["vehicleId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("vehicleId"), Index(value = ["vehicleId", "odometer"])]
)
data class FuelRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long,                // 必填：一条记录必须属于某辆车
    val date: LocalDate,
    val odometer: Int,                  // 绝对里程，不是单次里程
    val liters: Double,
    val amount: Double,                 // 元
    val price: Double,                  // 元/升
    val isFull: Boolean = true,
    val station: String? = null,
    val note: String? = null,
    val estimated: Boolean = false      // 升数由默认价推算，非用户实填
)
```

**关键决定一：存绝对里程，不存单次里程。** 单次里程是派生量，一旦某次录错，后面整条链全错且无法定位。绝对里程可以独立校验（必须单调递增）。

**关键决定二：所有按里程的运算必须先按 `vehicleId` 分组。** 两辆车的里程表读数通常高度重叠（都在 1~5 万公里区间），一旦混在一起排序，加满点会跨车配对，算出的油耗是荒谬值且看起来「像个数」。这是多车版最容易写错、也最难靠肉眼发现的地方。

```kotlin
// 正确：先分组，再计算
vehicles.map { v -> compute(records.filter { it.vehicleId == v.id }) }
// 错误：compute(records) —— 两车里程交叠时区间会串
```

## 油耗算法

不复用「相邻两条记录相除」的做法。按**加满事件切分区间**，且**每辆车独立计算**：

```
对每辆车 v：
  记录集 = records.filter { vehicleId == v.id }
  把记录按 odometer 升序排列 → 取所有 is_full = true 的记录
对相邻两个加满点 A、B：
  distance = B.odometer - A.odometer
  fuel     = A、B 之间（含 B，不含 A）所有记录的 liters 之和
  consumption = fuel / distance * 100      // L/100km
```

即：中间未加满的记录，油量累加进区间，但自己不产生油耗值。

## 派生指标

首页除油耗外还有四个推算值，全部由上面两张表算出，**不落库**。以下所有公式**作用范围都是当前车**（`records.filter { vehicleId == current }`）。

| 指标 | 公式 | 边界 |
|---|---|---|
| 累计行驶 | `max(odometer) - min(odometer)` | 只有 1 条记录时为 0 |
| 累计油费 | `sum(amount)` | 含首条 |
| 累计平均油耗 | `sum(liters where odo > minOdo) / 累计行驶 × 100` | 首条的油量属于上次加油，不计入 |
| 续航估算 | 标了油箱：`tankCapacity / 当前油耗 × 100`；没标：`lastRecord.liters / 当前油耗 × 100` | 当前油耗为空时隐藏。两种来源要在界面上写出来（TANK / LAST_FILL） |
| 预计加油里程 | 标了油箱：`最后一个加满点里程 + 续航`；没标：`max(odometer) + 续航` | 同上。起点不同：满箱是从注满那一刻开始烧的，中间可能夹未加满的补油 |
| 区间均温 | `avg(daily_temp between A.date and B.date)` | 按天加权，缺数据的日期跳过；覆盖率 < 80% 该区间不参与归因 |
| 每 10℃ 油耗变化 | 回归斜率 `k` × 10 | 有效区间 < 4 时不出归因 |
| 最大残差 | `max(abs(y − (k·T + b)))` | 判定车况的唯一依据 |

## 温度归因算法

```
输入：有效区间的 (均温 T, 油耗 y) 列表，长度 n ≥ 4
k = Σ(T-T̄)(y-ȳ) / Σ(T-T̄)²
b = ȳ - k·T̄
r = Σ(T-T̄)(y-ȳ) / sqrt(Σ(T-T̄)² · Σ(y-ȳ)²)
residual_i = y_i - (k·T_i + b)
```

展示与判定：

| 输出 | 规则 |
|---|---|
| 相关系数 `r` | 保留 2 位；同时给出 `r²` 解释率百分比 |
| 每 10℃ | `k × 10`，保留 2 位 |
| 夏冬差 | 均温最高区间与最低区间的**实测**油耗差，不是回归预测差 —— 用户看的是真数 |
| 最大残差 | `max abs(residual)` |
| 结论 | 残差 < 0.35 L → 「车况正常」；否则点名残差最大的那一段 |

**0.35 这个阈值不是拍的**：示例数据残差标准差约 0.19，2 倍即 0.38，取 0.35 作为「超出正常抖动」的界。

**已知局限，必须写进代码注释：** 气温只能解释约一半波动，剩余部分与驾驶工况、胎压、机油状态、载重混在一起，本 App 无法分离。所以结论措辞是「没有跳出去的异常」，不是「车况良好」—— 后者是过度断言。

## 判据表

每条都要能实际验证，不接受「看起来对」。

| 编号 | 判据 | 验证方式 |
|---|---|---|
| A1 | 首条记录不产生油耗值 | 插入 1 条 `is_full=true` 记录，区间计算结果为空 |
| A2 | 两次连续加满直接计算 | A 里程 1000 加 40L，B 里程 1500 加 35L → 7.00 L/100km |
| A3 | 中间未加满时油量累加 | A(1000, 加满) → X(1300, 20L, 未满) → B(1500, 25L, 加满) → (20+25)/500×100 = 9.00 |
| A4 | 未加满记录不单独出点 | X 不出现在趋势数据里 |
| A5 | 里程回退不参与计算 | C 里程 1400（< B 的 1500）→ 该区间标记异常、不出油耗、UI 提示核对 |
| A6 | 里程重复不参与计算 | 两次相同里程 → 标记异常，不产生除零 |
| A7 | 金额与升数不一致时提示 | 7.85×40=314 ≠ 300（差 4.5%）→ 允许保存但标记「单价存疑」；差 > 5% 弹提示 |
| A8 | 单次加油量超过油箱容量提示 | 45L > 40L 容量 → 保存前提示，不阻止 |
| A9 | 计算结果精度 | 与手工核算一致到小数点后 2 位，不做浮点累积误差 |
| B1 | 累计行驶 = 最大里程 − 最小里程 | 10 条示例数据 → 4690 km |
| B2 | 累计平均排除首条油量 | 371.0 L / 4690 km → 7.91 L/100km。实现上按**可信区间**累计 fuel/dist，无异常数据时与本公式等价，有异常时自动剔除 |
| B3 | 续航 = 油箱容积 ÷ 当前油耗（未标油箱则用上次加油量） | 45 / 7.52 × 100 → 598 km；未标油箱：44.0 / 7.52 × 100 → 585 km |
| B4 | 预计加油里程 = 起点里程 + 续航 | 12890 + 598 → 13488 km（未标油箱：12890 + 585 → 13475） |
| B5 | 首页四层顺序固定 | 核心 / 事实 / 建议 / 趋势，不因数据缺失重排 |
| C1 | 切换标号自动带出对应油价 | 92 → 95，单价从 8.49 变 8.97 |
| C2 | 油价只预填，不覆盖手填值 | 手填 8.29 保存后重开，单价仍取上次记录 8.29，不被 8.49 覆盖 |
| C3 | 无油价数据时不显示 0 或 NaN | `fuel_price` 为空 → 单价回落上次记录或留空 |
| D1 | 只填里程与金额即可保存 | 两个字段有值 → 保存按钮可点，升数自动反推 |
| D2 | 未手动改单价/升数时标记为估算 | 只填金额保存 → `estimated = true`，历史升数后显示「估」 |
| D3 | 手动改过任一项则不算估算 | 手填升数 45.11 保存 → `estimated = false`，无「估」标 |
| D4 | 切换标号后重置估算状态 | 92 → 95 后只填金额 → 仍标「估」，但单价用 8.97 |
| E1 | 加油站列表按次数降序 | 中石化(8) → 中石油(2) → 延长壳牌(1) |
| E2 | 列表不含从未加过的站 | 空库时列表为空并提示，不预置任何站名 |
| E3 | 定位只在点按钮时请求一次 | 打开录入页不触发定位；权限拒绝后历史列表照常可用，保存不受影响 |
| E4 | 不申请精确位置 | `aapt dump permissions` 输出只有 `ACCESS_COARSE_LOCATION`，无 `ACCESS_FINE_LOCATION` |
| F1 | 7 天内的缓存不发请求 | 断网打开录入页，油价正常显示，抓包无请求 |
| F2 | 请求失败不阻断保存 | 飞行模式下填两个数 → 保存成功，油价回落旧值，无错误弹窗 |
| F3 | 非白名单 host 被拒 | 构造请求到 `example.com` → 拦截器抛异常，无出站流量 |
| F4 | 归因需要 ≥ 4 个有效区间 | 3 个区间时只显示提示文案，不出现 r 值或 NaN |
| F5 | 归因数值可复算 | 示例 8 个区间 → r = 0.71、每 10℃ = 0.46 L、最大残差 = 0.27 L |
| F6 | 无气温数据时归因块整体隐藏 | 清空 `daily_temp` → 折线正常显示，归因区无 0 / NaN / 空框 |
| F7 | 气温覆盖率不足的区间被排除 | 某区间只有 5 天有效气温 → 该区间不参与回归，n 减 1 |
| G1 | 导出内容可原样回读 | 导出（含两辆车）→ 清空库 → 导入 → 车辆数、记录数、各车油耗与车名全部复原 |
| G2 | 导入按 vehicle + date + odometer 去重 | 同车同日期同里程才跳过；两辆车同一天同一里程不冲突 |
| G3 | 含逗号的字段用引号包裹 | 站名「某站, 带逗号」导出再导入后原样恢复；内嵌双引号转义为 `""` |
| G4 | 异常区间不污染首页 | 导入 150 km / 40.5 L 的记录 → 首页油耗仍是上一有效值，历史该行标「异常 · 核对里程」 |
| G5 | 全部区间异常时首页降级 | 只导入 2 条异常记录 → 油耗「—」、续航「还没有可信的油耗区间」，不显示 27.00 之类的假值 |
| G6 | 导入失败不破坏现有数据 | 选一个非 CSV 文件导入 → 现有记录一条不少，只提示无法识别 |
| H1 | 切车后所有视图跟着切 | 车 A 10 条 / 油耗 7.52，车 B 4 条 / 油耗 6.61 → 切换后首页、历史、趋势点数、加油站列表全部换掉 |
| H2 | 两车里程交叠时区间不串 | 车 A 8000~12890、车 B 42310~44380；再把 B 的里程改成 9000~12000（与 A 交叠）→ 两车油耗仍各自正确 |
| H3 | 录入中途切车清空里程 | 录入页填里程 12950 → 切到车 B → 里程输入为空、单价换成 B 的上次值、保存按钮禁用 |
| H4 | 新车第一条记录不产生油耗 | 新建车辆记 1 条 → 油耗「—」、次数 1、行程 0 km，不报错不显示 NaN |
| H5 | 零车辆时不允许记账 | 清空车辆 → 首页显示「添加车辆」，点 + 弹出添加面板而不是录入页 |
| H6 | 新车卡片不显示 0 或 NaN | 空车首页：油耗「—」、续航「—」、摘要「这辆车还没有记录」 |
| H7 | 导入按车牌归车并恢复车名 | 导出两辆车 → 清空 → 导入 → 车名仍为「我的车 / 家里的SUV」，不退化成车牌号 |

## 边界处理规则

| 场景 | 处理 |
|---|---|
| 中间某次没加满 | 该条 `is_full=false`，油量并入下游区间，自身不出油耗 |
| 首次记录 | 作为基准点，不出油耗 |
| 里程表回退（换表/换车） | 该区间不计算，记录标记 `suspect`，首页提示核对 |
| 同一箱油分两次加 | 两次都 `is_full=false`（第二次其实加满则标 true），按加满点切分自动正确 |
| 金额与升数对不上（优惠/抹零） | 保留实际金额，`price` 存实付单价，差额不修正 —— 用户看到的是真花的钱 |
| 记录删除后 | 重新计算整条区间链，不做增量修补 |
| 录入中途切车 | 清空里程与金额，重新带出新车的上次单价与加油站；已填的里程属于上一辆车，不能留 |
| 当前车 id 悬空 | 回落到列表第一辆；一辆都没有则进入零车辆状态，不崩溃 |
| 删除车辆 | 第一版不做界面。若将来加：外键 `CASCADE` 连带删除该车全部记录，删除前必须二次确认并提示条数 |
| 新建车辆 | 无记录、无油价历史：单价回落到该车默认标号的价格，加油站列表为空并提示 |
| 导入遇到未知车牌 | 新建一辆车，名字取 `vehicle_name`，缺失则用车牌 |
| 油价请求超时 / 失败 | 用缓存值；面板只显示上次成功同步时间，不弹错误、不置空 |
| 气温缺日数据 | 覆盖率不足的区间跳过；有效区间 < 4 时归因块整体隐藏 |
| 定位失败或被拒 | 静默回落到历史列表，不弹对话框，不重复请求 |
| 首次使用且无缓存 | 油价用内置默认值并标记 `source=fallback`；归因块隐藏 |
| 导入的记录 | 不走录入校验；按 `date + odometer` 去重，重复即跳过 |
| 异常区间（里程回退 / 重复，或区间油耗 > 25 或 < 1 L/100km） | 不参与首页当前油耗、趋势、归因、累计平均；仅在历史中标「异常 · 核对里程」。计算本身没错，但数据大概率录错，不能让它冒充「当前油耗」 |

## 界面结构

```
MainActivity (单 Activity)
└─ NavHost
   ├─ home      首页：车名(可点切车) / 当前油耗 / 上次加油 / 累计 / 建议 / 趋势(折叠)
   ├─ record    录入页：记到哪辆车(可切) / 里程 / [金额|升数|单价] / 加满 / 加油站(含定位)
   └─ history   历史列表 ──> detail(可选)
```

首页右下角一个大的「+ 记一笔」浮动按钮，直达 `record`。

**车辆切换用 ModalBottomSheet**，不单开一页：切车是高频小动作，开新页会打断当前的录入或浏览。列表项即车辆，底部是添加表单（名称 + 车牌）。与油价面板、加油站面板共用同一个 `ModalBottomSheet` 容器，`screen` 用一个 sealed class 状态区分，避免三层 sheet 互相叠。

## 实现顺序

**先把离线部分做完、做对，再联网。** 这样网络层无论出什么问题，核心功能都已经可用。

1. 工程骨架 + Room schema（含 `vehicleId` 外键）+ 算法单元测试（A1–A9 全绿）
2. **车辆层：列表 / 添加 / 切换 / 当前车持久化**（H1–H6 全绿）—— 必须排在录入之前，否则录入页没有归属对象
3. 录入页 + 首页（B1–B5 全绿）
4. 历史列表 + 编辑删除
5. 油价：本地缓存 + 面板（C1–C3 全绿，先不做网络）
6. 联网层：OkHttp 单例 + 白名单 + 油价同步（F1–F3）
7. 气温拉取 + 归因（F4–F7）
8. 定位带出加油站（E1–E4）
9. CSV 导出导入（G1–G6、H7）
10. 趋势图视觉打磨

**当前车的持久化**：`currentVehicleId` 存 DataStore（不是 Room，它是一条配置）。启动时读；值失效（车被删）就回落第一辆；一辆都没有则进入零车辆状态。
