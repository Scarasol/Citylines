> 历史过程记录：保留原文供追溯；其中旧配置、旧接口和阶段状态不代表当前版本。唯一当前交接与接口说明见 [DELIVERY.md](DELIVERY.md)。

# Citylines → V3 接入交接文档

写给接手 V3 接入工作的新会话。**先读本文件，再读第 4 节的必读清单**；不要从零开始重新调研 TLC。

> **2026-09-25 更新（四问题修复批次）**：决策 D1–D16 与逐项实现/未验证状态见
> `docs/V3-四问题决策记录.md`、`docs/V3-四问题改动交付.md`、问题描述见 `docs/V3-四问题描述.md`。
> 该批次已落地：水判定误报修复、10×10 地块平衡、本地路跨层连边修复、足迹清单读实际资产、
> 三级断面重做（`1/1/12 + 中线`、`2/1/10`、`4/1/6` 浅灰）。

## 1. 当前状态（工作区实测，非转述）

| 项 | 状态 |
| --- | --- |
| V2（`CONNECTED_DISTRICT_V1`） | **已完成并实测**：契约套件全绿、真实生成日志有 V2 激活 / 样式自动切换 / `V2 part` 铺设、`ERROR`/`FATAL`=0 |
| V2 审后修复 | **7 项已修**（集散锚度量、路由边、`hardBlocked`、`stationEntrance`、`highwayConflict`、桥面喂入+部件、海平面优先级），每项有回归测试 |
| V2 残余 | **4 条**：游戏内桥面渲染未证、集散路交叉环（15→4）、同层高速抑制桥、盐 2 批次范围 |
| V3 纯算法 | **已由 GPT 落地在本仓库** `src/main/java/com/scarasol/citylines/road/core/V3*.java`（10 个类，`V3Planner` 约 58KB），`V3Params.CURRENT_VERSION = 3` |
| V3 证据接入（§5 第 1 步） | **已完成**：`V3AbstractCasesTest`（12）+ `V3TopologyComparisonTest`（5）= **+17 用例**，校验逻辑未改、无用例被放宽。细节与边界见 `docs/v3-testing.md` |
| V3 面积语义（§5 第 2 步） | **已完成**：`areasize` 是多建筑分块的区块边长，实测恒为 **10**（`V3AreaSemanticsTest` 3 个用例）；运行时仍须读活值并 fail-closed，见 §5 第 2 步 |
| V3 事实源适配器（§5 第 3 步） | **映射与适配器已完成**（`TlcV3FactSource`，11 个用例）；**尚未接入维度构造**——还没有任何世界会用它规划，见下 |
| V3 接入（§5 第 4 步） | **已完成并实测**：`V3RoadGraph`（有效图/路权）+ 消费者全部接线（部件选择、有效路类、整足迹路权、诊断命令）；**真实服务端冒烟 V3 已激活**（`V3 planner enabled … S=30, areaSize=10`），`Done (27.8s)`、模组 `ERROR`/`FATAL`=0 |
| V3 三级部件（§5 第 6 步） | **已完成**，按《V3本地路部件族-审阅共识》落地：**32 件 / 106 键**（V2 的 29/96 + 本地 3 件/10 键）；**2026-09-25 按决策 D4 重做断面并重新生成资产**（主干 `1/1/12` + 两格中央线、次级 `2/1/10`、本地 `4/1/6` 浅灰），生成器自检 **20,596 断言**、全套 **192 tests 0 失败**。**退役 V2 与 fail-closed 预检仍未做**，见下 |
| 测试 | `cleanTest test --offline` → **BUILD SUCCESSFUL，173 tests / 0 failures / 0 skipped**（本次接手前为 128）；**2026-09-25 四问题批次后为 195 tests / 0 failures / 0 skipped** |
| **V2 退役** | **已完成（移入测试源码集）**：`ConnectedDistrictPlanner`/`DistrictPlan`/`DistrictFacts`/`FactCache`/`GateSelector`/`PathTree`/`PlannerStats`/`BridgeScanner` 已移到 `src/test/java`，**打包验证 jar 里 0 个 V2 planner 条目**，而 14 个测试类约 80 条断言全部保住（套件仍 173 tests）。说明见 `src/test/java/com/scarasol/citylines/road/core/package-info.java` |
| 水面桥 | **已裁定维持首版无桥**（《V3水面桥-审阅共识》选项 A）：水=硬障碍、不发布跨水边、活动维度继续阻止 V1 桥规划器。**两岸不连通是明确的现实感折让**，须写进交付限制；带再入条件、不得写成"已完成" |
| 代表性耗时对照 | **已完成（2+2 次固定种子）**：V1 均值 31.675s vs V3 均值 32.404s，差 +0.730s（+2.3%）——**但 V1 组内极差 1.342s 大于该差值且两组区间重叠，故不能证明"无明显开销"也不证明退化**。详见 `docs/DELIVERY.md` §7 |
| LC2H 快速路径 | **已改为策划案要求的局部钩子**：从"HEAD 取消整条快速路径"改为在 `LazyPlan.occupied(I)Z` 内**唯一一处** `City.isChunkOccupied` 用 `@WrapOperation` 合入路权，快速路径保持启用。字节码已 `javap` 核对（LC2H `8928853`）。**本机无法运行 LC2H，故无运行期证据**。详见 `docs/DELIVERY.md` §8 |
| 未接线/未做 | ⚠️ 观感与可玩性由用户验收；⚠️ LC2H 注入需用户环境验证 |

### ✅ 第 1 步已完成：V3 的证据已可从 `gradlew test` 复现

原先 `V3AbstractCases.java` 与 `V3TopologyComparison.java` 都是 **`public static void main` 校验器、`@Test` 数量为 0**，
V3 的抽象结论（站口、缺口、负坐标、跨界互认、三级过渡、缓存逐出、7×7/9×9 大足迹等）**无法用
`tools/gradlew.sh test` 复现**，也不受回归保护；交接记录里"纯 Java `V3AbstractCases verify` 覆盖……"
只是**手工 CLI 结果**。现在：

- 两个校验器的校验逻辑被分组为 `verify*` 方法（**判定一行未改**，仅补了失败时的坐标/世界名），
  `main` 与 JUnit 调用同一批方法，不会各自漂移；
- 失败可定位到格（例：`render transition changed the logical edge class at 11,0 S: edge=TERTIARY`）；
- 已实测"能失败"：临时把过渡臂断言改成 `SECONDARY` 后对应用例确实 FAILED，随后还原；
- 原 CLI 命令仍可用（`tools/run-v3-probe.sh`，输出逐字不变）。

**仍未验证的部分**：本次只覆盖**纯算法**。Mixin 注入、真实 `V3FactSource` 适配、路权消费者、
部件铺设、游戏内表现**都不在其中**，仍属 §5 的第 3–6 步。详见 `docs/v3-testing.md` §4。

### ✅ 第 2 步已完成：`areasize` 语义已核实

**语义**：`MultiSettings.areasize()` 是 TLC 多建筑分块的**区块边长**——`MultiChunk` 按
`floorDiv(区块坐标, areasize)` 把世界切成 `areasize × areasize` 的方格，并用该值决定自己的
`buildingGrid` 尺寸（`new MB[areasize][areasize]`，`MultiChunk.java:47`）。所以**一个多建筑
永远不可能跨超过 `areasize` 个区块**。V3 的地块保护正是以 `V3Params.areaSize()` 为步长在超单元里
走同样的方格；两者不一致时，V3 会保护 TLC 永远填不进去的矩形（或漏掉能填的），因此**必须核对而不是假定**。

**取值核实**（`V3AreaSemanticsTest` 3 个用例，随 `gradlew test` 运行）：

| 来源 | `areasize` |
| --- | --- |
| TLC `MultiSettings.DEFAULT`（世界样式缺省该块时的回落） | **10** |
| Citylines `citylines:v2_standard` 世界样式（V2 维度实际解析到的那个） | **10** |
| `V3Params.selectedForDefaultArea().areaSize()` | **10** |

另有**手工核对、未被测试覆盖**的部分：TLC 依赖 jar 里的两个世界样式
`lostcities:standard` 与 `lostcities:standard_everywhere` 都声明 `areasize: 10`；
`LostCityProfile.worldStyle` 默认为 `"standard"`；`config/lostcities/profiles/` 下
**没有任何** profile 声明 `multisettings` 覆盖。

**结论**：本环境（TLC 1.20-7.5.5 + 内置 profile + Citylines V2 样式）下 `M = 10`，
`V3Params.selectedForDefaultArea()` 可直接复用。

**仍未闭环的风险（必须带进接入层）**：用户**数据包**可以自带一个 `areasize != 10` 的世界样式，
这是唯一能打破上表的途径。因此第 3 步接入时**必须读取活值**
（`provider.getWorldStyle().getMultiSettings().areasize()`，注意**不要**走
`info.getProfile()`——它在 LC2H 下是被 `@Overwrite` 的未初始化 ThreadLocal）并与
`params.areaSize()` 比对，**不一致就 fail-closed 拒绝启用 V3**，不得静默套用默认参数。
本测试只覆盖"发布的资产不会漂移"，**不能**替代运行时那道检查。

### ✅ 第 3 步（映射部分）已完成：`V3FactSource` 适配器

`TlcV3FactSource`（`road/tlc/`）把 V2 已修好的冻结事实层 `TlcRoadFacts` 映射到 `V3Facts`。
**没有第二条采样路径、没有自己的缓存**（`V3Planner` 自带 LRU；再叠 V2 的 `FactCache` 会让同一份数据
有两个失效语义不同的缓存）。适配器是**无状态纯函数**，因此天然满足"绝对区块坐标 + 可查 `S+2` 以外"：

- `V3Planner.sampleFacts` 采样 `origin-1 .. origin+size`，而 `fact(x,z)` 对窗口外**回落**到
  `facts.factsAt(...)`（边界站口会查第二格外侧）。固定一格 halo 恰好会在最需要正确的边界上答错，
  所以适配器不持有任何数组。已有测试记录规划器真实请求的坐标范围（`29..60` / `-31..0` @ S=30）。
- 只读 `ChunkFacts`；**不**回读最终 `BuildingInfo`、`MultiChunk`、生成后铁路状态。

为拿到 V3 需要的站口语义，给 `ChunkFacts` **加了两个原始字段** `rail`（`RailChunkType`）与
`railHeight`，并由记录自身派生两个判定（判定的**唯一实现**，`TlcRoadFacts.railBlocksStreet` 现在委托给它）：

| 条件 | `railBlocksStreet` | `stationEntrance` | V3 语义 |
| --- | --- | --- | --- |
| `STATION_SURFACE` | **true** | false | 硬阻断（TLC 不铺面） |
| `STATION_EXTENSION_SURFACE` 且 `railHeight < cityLevel` | false | **true** | 可裁决站口：地表可铺、且是地下入口 |
| `STATION_EXTENSION_SURFACE` 且 `railHeight >= cityLevel` | **true** | false | 硬阻断 |
| `*_UNDERGROUND` / 其它 | false | false | 普通可铺地表，**不是**站口 |

映射另有两处刻意的判断：

- `buildingEligible` ← `ChunkFacts.pavableLand()`（不是 `!hardBlocked`）。否则"荒野"会被标成可建，
  与 `V3Facts.EMPTY` 不一致。它只决定建筑候选矩形，**不阻拦道路**，所以不由站口收窄。
- **同层高速冲突折进 `hardBlocked`**：V3 只有一个冻结硬阻断概念、没有独立高速标志，而 TLC 自己
  拒绝在该格铺面；当作可铺会规划出"有计划无铺面"的路。它**不**被记成 `predefinedBuilding`（归因不能撒谎）。
  代价：**跨高速的桥暂时不在范围内**，直到 V3 自身有高速概念；这一条已写进 `docs/DELIVERY.md`。

**本步未做、也未验证的部分（不得当成已接入）**：
- ❌ **没有任何世界会使用这个适配器**——`CitylinesRoadState`/维度构造尚未接线，`V3Planner` 在真实世界里
  一次都没跑过。本步交付的是**适配器 + 映射证据**，不是"V3 已生效"。
- ❌ **冷建锁序尚未确认**：规划器在自己同步锁内读事实源，是否与 TLC 维度锁构成倒置，本会话**没有验证**。
- ❌ 同层高速 → 硬阻断这条映射**只有单测**，没有真实生成证据。
### ✅ 第 4 步（有效图部分）已完成：`V3RoadGraph`

`V3RoadGraph`（`road/tlc/`）是 V3 侧的有效图层：**所有答案都只来自拥有该坐标的那份已发布 `V3Plan`**，
不重规划、不重排序、不反向修改计划。10 个用例覆盖：

| 用例 | 守的语义 |
| --- | --- |
| `levelMappingIsExact` / `allThreeLevelsSurviveTheMapping` | V3 三个等级与 TLC `PlannedRoadType` **同名同序**，映射是序数上的恒等（不是近似）；开阔超单元确实产出三级 |
| `streetInfoBooleansAreNotTransposed` | `PlannedStreetInfo` 的四个 bool 是 **north, south, west, east**（TLC 记录次序），**不是** `N/E/S/W` 掩码次序——按掩码次序传会把 north 和 east 转置，而本项目其余测试**抓不到**这个错 |
| `adjacencyAloneNeverProducesAConnection` | 邻格也是路**不等于**连通；只认显式 `edge(Direction)`（V2 当初要引入 `RouteEdge` 才修掉的缺陷） |
| `everyLevelReservesWithAnEdge` / `reservationAgreesWithStreetInfo` | 三级**都**占路权；有等级无显式边的是"幽灵路"，不占路权；路权与街情对"是不是路"必须一致 |
| `footprintTouchesOnlyOverlappingRoads` / `footprintChecksEveryChunkNotJustTheCorner` | 整足迹逐格检查；**仅相邻**的路不拦；只有真正重叠才拦；右下角有路也要拦 |

**为什么这一层可测且已测**：`footprintTouchesReservedRoad` 收的是 `(topLeftChunkX, topLeftChunkZ, dimX, dimZ)`
而不是 `ChunkCoord`——这不只是风格：`ChunkCoord` 需要 `ResourceKey<Level>`，而 `Level.OVERWORLD` 会触发
MC 注册表静态初始化，在纯单测里直接 `ExceptionInInitializerError`。用裸 `int` 让这一层**完全不依赖 MC 运行时**，
所以它是真单测，不是"只能靠实机"的代码。

**本步未做（下一步的前置条件）**：
- ❌ **消费者尚未接线**：`CitylinesRoadState` 仍在用 `ConnectedDistrictPlanner`；`V3RoadGraph` 还没有被任何生产代码调用。
- ⛔ **必须先做第 6 步**：见 §3 的 fail-closed 硬约束——三级图配两级部件会硬崩。
- ❌ **`EXTERNAL_VETO` 未做**：目前 V3 侧**没有**外部否决登记；第 3 步勘察已确认 TLC 自身的优先级否决
  （预定义建筑/街道、已接受多建筑、非城市格）在 `BuildingInfo.getEffectivePlannedRoadType` 里，
  接线时应继续复用 V2 的 `@WrapOperation` 缝、只替换邻格输入，**不得**改 `V3Planner`。
- ❌ **冷建锁序**仍未验证。


### ✅ 第 6 步已完成：三级部件按审阅共识落地（32 件 / 106 键）

依据 `/mnt/d/Workspace/Knowledge Base/Lost Cities/路网优化方案/V3-拟真优先/V3本地路部件族-审阅共识.md`
（Codex 与 DeepSeek 共识）。**我送审稿的旧计数（5 件 / 14 键、110 键 / 34 件）已作废。**

**共识推翻了我的一处判断，我按实测确认共识是对的**：我一度认为"中宽入口臂 + 纯本地臂"的混合格不可达，
因为我用 `plan.infoAt(x±1,z±1)` 读邻居（可能读到非本计划坐标，需走 owner 计划）。改用 owning plan 后，
10 seed × 7×7 超单元的样本一致证明该形状存在，且 `transitionMask` 精确标出入口臂：

```
(-53,-59) transitionMask=1
  N: logicalEdge=TERTIARY  neighbourCentre=PRIMARY    transBit=true   <- 入口臂
  S: logicalEdge=TERTIARY  neighbourCentre=TERTIARY   transBit=false  <- 纯本地臂
```

**逻辑边在入口侧仍是 TERTIARY**（`min` 两端），`transitionMask` 只做几何入口判定——与知识库"几何别名不得回写逻辑级"一致。

| 本地形状 | 有向键 | 规范件 |
| --- | --- | --- |
| 纯本地直路 | `0t0t`、`t0t0` | `v2r_t_0t0t` |
| 中宽入口 + 本地直路 | `0t0s`、`s0t0`、`0s0t`、`t0s0` | `v2r_t_0t0s` |
| 单臂本地尽端 | `000t`、`t000`、`0t00`、`00t0` | `v2r_t_000t` |

`0s0s`、`000s`、`000p` **不可由规划器产生，未制造防御部件**。

**按共识修掉的三个缺陷**（均已验证）：
1. `portClass` 把 `NONE` 臂与本地中心的臂都别名成 `SECONDARY` → 改为：`NONE` 臂保持 `NONE`；
   本地中心自身的臂是 `TERTIARY`，**入口由 `transitionMask` 决定**，不由边等级猜。
2. `selectionKey` 把别名写进了表键 → 改回**逻辑夹取 `min(edge, centre)`**，
   故 V2 的 96 键与 29 件规范名**逐字不变**；本地族用**独立的** `localPortKey(transitionMask, ports…)`（按端口打包）。
3. Java 规范旋转比较用 `ordinal()` → 改用 **`rank()`**，与生成器的 `canonical_key` 对齐
   （否则同一形状 Java 给 `0s0t`、Python 给 `0t0s`，件名对不上——已实测复现并修好）。
4. `RoadSurfaceLayout.grid` 把本地入口的 `s` 夹成 `t` → 本地中心**不夹**（入口臂故意宽于中心），
   并加了注释说明为何这是唯一例外。

**已补：fail-closed 覆盖门（共识第 4 条的"缺件预检"）**
`RoadPartTable.unresolvedReachableKeys()` 枚举**规划器真正可达**的键（V2 集散/主干 + V3 的 10 个本地端口键），
报告任何解析不到的键；`RoadAssetPrecheck.validate` 已接入，**任何不可达键即拒绝该维度**。
`RoadPartTableCoverageTest`（4 用例）含共识要求的**负例**：从真实键表副本里删掉一个可达键必须被报出来；
不可达的孤立集散键（`0000`）删掉**不得**被报（它不是缺口）。
另有一个用例断言**真实 V3 计划里每一个路格**都能投影到可解析的键——覆盖声明靠生成计划验证，不靠手写清单。

**仍未做的（不得当成已完成）**：
- ❌ **退役 V2**：`ConnectedDistrictPlanner` 与 V2 的 29 件仍在；
- ❌ **`areasize` 活值比对**：共识/第 2 步要求的运行时检查尚未接进预检；
- ⚠️ **运行时尚未消费 `transitionMask`**：`V3RoadGraph.partEntry(cell)` 已实现（把 V3 格投影到部件，
  含本地中心的入口解析），但 `RoadSurfaceHooks.placeV2Section` 仍在用 V2 的 `RoadPartTable.entry(plan)`，
  且 V3 **尚未接进维度构造**——所以**三级路面目前在真实生成中尚未生效**。
  本步交付的是**资产 + 表 + 布局 + 覆盖门 + 投影函数**，不是"V3 已在世界里铺出三级路面"。
- ⚠️ 观感、性能、游戏内验收**均未做**；共识第 42 行明示本轮未实现整链计时或游戏验收。

## 2. 环境与运行纪律（都是本会话踩出来的）

### 关键路径（绝对路径，先记下来）

| 用途 | 路径 |
| --- | --- |
| 工作区（代码） | `/mnt/d/Workspace/Citylines-forge-1.20.1` |
| **Gradle 仓库（依赖缓存）** | `/mnt/d/Library/.gradle`（Windows 侧 `D:\Library\.gradle`） |
| 知识库 | `/mnt/d/Workspace/Knowledge Base/Lost Cities` |
| 知识库 · V3 目录 | `/mnt/d/Workspace/Knowledge Base/Lost Cities/路网优化方案/V3-拟真优先` |
| 上游只读源码（TLC 7.5.5，MIT） | `/mnt/d/Workspace/Citylines-forge-1.20.1/reference/lostcities-src` |
| Windows JDK（wrapper 实际调用的） | `/mnt/c/Program Files/Java/jdk-17.0.18+8` |

`tools/gradlew.sh` 内部用 `cmd.exe` 调 Windows 侧 `gradlew.bat`，并设置 `GRADLE_USER_HOME=D:\Library\.gradle`（可用环境变量 `CITYLINES_GRADLE_HOME` 覆盖）。**这就是为什么依赖已经下好、且必须 `--offline`**：容器里的 Linux 侧没有 JDK，代理 `localhost:10809` 又被拒。

- **Gradle 必须带 `--offline`**：配置的代理 `localhost:10809` 在本容器被拒；`D:\Library\.gradle` 缓存完整，`compileJava`/`build`/`test` 离线均可。
- 用 `tools/gradlew.sh <tasks>`（走上述 Windows JDK 17 + 上述 Gradle home）。wsl→Windows 互操作下**不能用内联引号**，脚本已用无引号形式封装；路径不能含空格。
- **服务端只允许单实例**：先确认端口 25565 空闲；用工具的 **background 模式**启动 `runServer`，**不要内联 `&`**（本会话因此失败过一次）；跑完 `taskkill /PID <pid> /T /F`。曾出现两个实例争抢 `run/world` 的 `session.lock` 导致 `Failed to start the minecraft server`——那是环境问题，不是模组缺陷。
- **真实生成验证技巧**：
  - 未加载的坐标用 RCON 探测会返回 "That position is not loaded"；`forceload add` 后区块会生成，可用于逐格探测地形事实；
  - 需要预定义城市/街道时，可往 `run/world/datapacks/` 放**临时**数据包（schema 参考 `PredefinedCityRE`），**绝不放进 `src/main/resources`**，用完删除并报告是否真的加载；
  - **TLC 自己的地形修正会填掉自建城市旁的水**——"造夹具"式验证会与地形对抗，优先寻找**既有自然城市/水边界**。
- **LC2H 无法在本机运行**：其 4.2.4-LTS 要求 Forge ≥47.4.0（本环境 47.3.0）且依赖 `quantified` 模组。因此任何 LC2H 相关注入只能做**字节码核对 + 单测**，游戏内验证需用户环境。
- 运行配置：IntelliJ 的 `.idea/runConfigurations/*.xml` 曾残留模板参数 `--mixin.config rummage.mixins.json` 导致启动崩溃（已修）。若再遇启动即崩，先查这里。

## 3. 用户硬性约束（不可协商）

- Mixin 只允许 `@Inject` / `@WrapOperation` / `@Unique` / `@Shadow` / `@Accessor` / `@Invoker` / `@Implements`；**禁止 `@Redirect`、`@Overwrite`、以及一切反射**（`Class.forName`/`MethodHandles`/`setAccessible`）。
- **单一 `citylines.mixins.json`** + `CitylinesMixinPlugin`（可选模组探测用 `ModList.get()` → 回落 `LoadingModList.get()`，**无反射**）。不要新增第二个 mixin 配置。
- 不得削弱既有测试来换取绿灯；放宽断言必须给出设计依据并留注释。
- 参数冻结后任何几何/策略改动**必须升版本盐**（V2 是 `PlannerParams.CURRENT_VERSION`，V3 是 `V3Params.CURRENT_VERSION`）。
- **V3 阶段盐冻结在 3，不再升盐**（用户 2026-09-24 明确指示）。这条把上一条变成一条**纪律**而不是选项：
  盐 3 是 V3 唯一会用到的盐，因此**任何会改变"同种子 + 同维度 + 同足迹清单 ⇒ 同计划"的改动都被禁止**，
  包括改 `V3Planner` 的选线/裁决/顺序、改 `V3Params` 的任何参与哈希的字段、改 `Direction` 位序或 `V3RoadType` 次序。
  接入工作只能**喂事实**（`ChunkFacts` → `V3Facts`）和**包外面一层**（有效图/否决/渲染），**不得改算法本身**。
  若某一步非改算法不可，**先停下来报告**，不要把盐 3 下的计划语义悄悄换掉。
  理由：盐的作用是避免混用不该混用的计划；V3 从未接线、无任何已生成世界或已存计划，
  升盐只会作废已发布的版本 3 抽象证据（`docs/v3-testing.md` §3 钉住的那些数字），换不到任何保护。
- **V3 维度在 fail-closed 预检落地前必须仍不启用**（第 6 步的部件已补齐，所以不再是"三级图配两级件"的硬崩风险，而是"还没有任何代码检查过部件与 `areasize` 是否匹配这个维度"）。`RoadAssetPrecheck` **尚未**加入：①三级件齐全性；②第 2 步要求的 `areasize` 活值比对。两者都在接线前必须先做，缺件在 TLC 里依旧是硬崩（`RegistryAssetRegistry.get` 抛 `RuntimeException`）。
  `RoadSection` 只有两级且**明确冻结**（加第三级会改代表数与整套资产），V2 的两处 `selectionKey`
  还把等级按 **2 bit** 打包（`ordinal() << (2 + 2*d)`）。给一个三级图配两级部件，
  产出的选择键在 `RoadPartTable` 里查不到，而 **TLC 部件缺失是硬崩**
  （`RegistryAssetRegistry.get` 抛 `RuntimeException`）。因此**不得**在第 6 步之前把 V3 接进维度构造，
  更不得用"只发两级、把本地路并进次级"来换取能跑——那会静默改变几何语义且违反本条。
- **严格执行策划案，不得私自变更方案**（用户 2026-09-24 追加）。只有在**有确实的数据或证据**证明
  策划案存在问题、或证明存在更好方案时才可偏离，并且**必须把证据和偏离一起写进文档**。
  本文件 §1 与 `docs/DELIVERY.md` 里凡是标注"待验收的候选值/未验证"的项，都属于**尚未取得证据**，
  不得当成已批准的方案改动。
- 未验证的事必须如实标注，不得用"应该可以"顶替。

## 4. 必读文件

| 文件 | 作用 |
| --- | --- |
| `docs/STATUS.md` | 工作区状态总览 |
| `docs/DELIVERY.md` | 交付说明：7 行已修缺陷表 + 4 条残余 + 复核入口 |
| `docs/WORKFLOW.md` | 阶段/门槛/架构决策 |
| `docs/integration/tlc-integration-points.md` | TLC 7.5.5 注入点与最小 Mixin 清单（含"做不到"清单） |
| `docs/integration/tlc-asset-pipeline.md` | 资产 JSON schema、注册表规则、部件选择 |
| `docs/integration/lc2h-compat.md` | LC2H jar 级冲突清单（C1–C5）与门控建议 |
| `docs/integration/v2-road-rights.md` | V2 路权实现与不保证项 |
| `docs/parameter-manifest.md` | V2 冻结参数与扫描结果 |
| `docs/v3-testing.md` | **V3 证据的可复现入口**：两个测试类各守什么、仍不覆盖什么、为什么需要 `tools/run-v3-probe.sh` |
| `reference/lostcities-src/` | 上游 7.5.5 只读源码（MIT），核对签名用 |
| KB：`/mnt/d/Workspace/Knowledge Base/Lost Cities/路网优化方案/V3-拟真优先/算法完成与接入移交.md` | **V3 唯一当前移交文档**（算法状态、接口、接入边界） |
| KB：`/mnt/d/Workspace/Knowledge Base/Lost Cities/路网优化方案/V3-拟真优先/方案.md` | V3 设计规则（三级变尺度闭合街区） |
| KB：`/mnt/d/Workspace/Knowledge Base/Lost Cities/路网优化方案/V3-拟真优先/实现偏离再协商共识.md` | 决策依据（含条件兜底等偏离项） |
| KB：`/mnt/d/Workspace/Knowledge Base/Lost Cities/路网优化方案/V3-拟真优先/核验记录.md` | 双模型核验结论 |

## 5. V3 接入步骤（按序，前一步过闸再进下一步）

1. ~~**补 V3 证据接入**（见 §1 ⚠️）。~~ **已完成**（见 §1）：17 个用例随 `gradlew test` 运行；入口、覆盖范围与边界见 `docs/v3-testing.md`。
2. ~~**核实面积语义**：`V3Params.selectedForDefaultArea()` 只在 `MultiSettings.areasize() == 10` 时有效（默认 `M=10, K=3, S=30`）。读 `MultiSettings.areasize()` 的真实语义并确认目标整合包取值；`M` 不匹配时不得直接复用默认参数。~~ **已核实**（见下）。
3. ~~**实现 `V3FactSource` 适配器**~~ **（映射部分已完成，见 §1）**：`TlcV3FactSource` + `ChunkFacts` 的
   `rail`/`railHeight` 字段已落地，11 个用例（`TlcV3FactSourceTest`）覆盖映射、绝对坐标（含窗口外）与纯净性。
   **仍未做**：①把适配器接进维度构造（目前**没有任何世界会用它规划**）；②确认冷建锁序是否与 TLC 维度锁倒置。
   下面保留原始要求与勘察结论，供接线时使用：
   直接复用 V2 已修好的事实层（`TlcRoadFacts` 的 `hardBlocked`、TLC 铁路条件、`highwayConflict`、海平面优先级都已修对），映射到 `V3Facts`。注意：接**绝对区块坐标**、规划器常规读取 `S+2` 见方、边界站口可能查第二格外侧，**不能只实现固定数组的一格 halo**；不得回读最终 `BuildingInfo`/`MultiChunk`/生成后铁路状态；确认冷建锁序（规划器在自身同步锁内读事实源，不得与 TLC 维度锁倒置）。
4. **（有效图已完成；消费者接线与部件选择待第 6 步之后）** 四个消费者共用同一有效图适配层：TLC 道路类型与街道信息查询、`MultiChunk` 整足迹选址路权、路面部件选择、地铁地表入口。晚期结构否决只登记 `EXTERNAL_VETO` 并同步所有消费者，**不得只在其中一个消费者处裁剪**、不得反向修改已发布的 `V3Plan`。

   **已完成的勘察：一个决定顺序的硬约束（2026-09-24，勿重复推导）**

   V3 是**三级**路网，而 V2 的共享表示 `RoadPlanInfo`/`RoadType` 只有**两级**
   （`NONE < SECONDARY < PRIMARY`；`RoadRights` 的类注释明说"V2 规划器不需要 TERTIARY 概念"）。
   更关键的是 `RoadSection` 的类注释把这件事**冻结**了：

   > "Only the two V2 classes exist; **TERTIARY and the intercity highway deliberately stay outside this
   > signature (adding a third class would change the representative count and the whole asset set).**"

   而 `RoadPartTable.selectionKey` 把等级按 **2 bit/格**打包（`ordinal() << (2 + 2*d)`），
   `RoadPlanInfo.selectionKey` 同构。所以给 `RoadType` 加第三个等级**不是加一个枚举值**，它会：
   ① 把 `SECONDARY`/`PRIMARY` 的序数从 1/2 顶到 2/3，**同时破坏两处打包键**；
   ② 让 V2 的 29 个部件 / 96 个有向键的资产集合失效（`RoadSection.of` 对第三个等级无断面）；
   ③ 连带 `RoadAssetPrecheck` 的 fail-closed 预检与 `generate_v2_road_parts.py` 的冻结表。

   **结论（已由第 6 步落实，2026-09-24）**：
   - `RoadType` 加 **`TERTIARY` 且追加在末尾**（序数 3），因此 **V2 的两个等级保住冻结序数 1/2，
     部件键不发生序数位移**；层级顺序由新的 `rank()` 承载（`NONE<TERTIARY<SECONDARY<PRIMARY`）。
     两处 `selectionKey` 由 2 bit/格 加宽到 **3 bit/格**（`RoadPlanInfo.KEY_BITS`）。
   - **关键：部件表不扩张，且几何别名不进表**。知识库《方案.md》第 63 行定死了规则：
     *"高等级中心遇本地路时，**仅在渲染几何层**把该臂口映射为现有 `SECONDARY` 端口截面；
     第一个 `TERTIARY` 格以固定入口过渡件从中宽端口收至本地窄断面……逻辑等级、路权及拓扑统计
     绝不被这个几何别名改写。"*
     所以别名的落点是**有效图层**（查找前把该臂解析成几何端口类），**不是** `RoadPartTable` 的键空间。
     第一版我把别名写进了表，结果造出 "SECONDARY 中心带 PRIMARY 臂" 这种**不可能**的键；
     契约测试当场用 `s000p` 抓出来了。**不要重犯**：表的每个中心只枚举自己的逻辑字母表，
     而"高等级中心 + 本地臂"复用该中心**已有的中宽臂**，因此**不新增资产**。
   - 字母表（与生成器 `logical_classes` 一致）：`PRIMARY={0,s,p}`、`SECONDARY={0,s}`、`TERTIARY={0,t}`。
   - 孤立格规则：`SECONDARY {0,0,0,0}` 与 `TERTIARY {0,0,0,0}` 都**无件**（规划器从不产出，
     且端口全为步行带）。实测 TERTIARY 度分布 `{1:385, 2:8937}`——**从不为 0**。
     `PRIMARY {0,0,0,0}` 有件（封口）。

5. **LC2H 局部钩子**：只在 `FastMultiChunkPlanner$LazyPlan.occupied` 内那**唯一一处** `City.isChunkOccupied` 调用点做 `original || v3RoadRight(coord)`（`@WrapOperation` 形态）；**不包外层审计调用、不整维关闭快速路径**。实施前用 `javap` 核对描述符。`concurrentBuildingInfo` 若绕开冻结道路语义，适配完成前拒绝该模式。
6. **三级 + 过渡部件**（**部分完成，见 §1 的 ⚠️ 段**）：三级**表示层**已完成（`RoadType.TERTIARY` 追加在末尾保住 V2 序数、3 bit 键、`RoadSection.TERTIARY`、`rank()` 层级）；**本地部件按计划内的几何别名复用中宽族**，故仍是 29 件 / 96 键。**未做**：本地窄断面落地、入口过渡件、fail-closed 预检、退役 V2。
   原始要求（保留）：沿用 V2 的生成器（`tools/generate_v2_road_parts.py`）与端口契约测试，按**可达逐臂签名**枚举补齐；缺件时在新维度启用前 fail-closed 拒绝。**不预报部件数量**。完成后按交接文档**退役 V2**，不保留永久双算法开关。

## 6. V3 接口语义摘要（易错点，来自交接文档）

- `V3RoadType` 顺序 `NONE < TERTIARY < SECONDARY < PRIMARY`；连接边取两端**较低**等级。
- `V3CellInfo.edge(Direction)` 是**显式逻辑共享边**；邻格都为路**不代表**自动连边。位序 `N/E/S/W = 1/2/4/8` **固定**。
- `axisTypeAt` **只是全局几何候选轴**，不能用作建筑路权或实际铺面；实际图用 `infoAt`/`roadTypeAt`。
- `V3Plan.infoAt` **只接受该计划拥有的坐标**，越界抛 `IllegalArgumentException`。
- `transitionMask` 只用于**渲染端口**（高等级接本地路的几何收窄），**不得提高逻辑边等级**。
- `roadWinsStation` 为真时必须**整组**跳过地表入口、竖井与楼梯，保留地下线路；不能在渲染期另做裁决。只拦地表一层而留竖井属不完整实现，应拒绝启用该例外。
- `fingerprint()` **不含事实源的逐格内容**——接入方必须冻结事实并自行管理缓存/持久化失效。
- `V3Plan.roadTypeAt` 是**规划路权**，不等于 TLC 实际可铺的有效图；结构否决须走 `EXTERNAL_VETO`。
- 大足迹采用**条件兜底**：只在本地路压掉最后一个可容纳候选区时撤销整条本地路；不撤主次路、不虚构地形、不保证资产一定被选中。完整 footprint 路权拒绝属接入层。

## 7. V2 期间踩过、V3 不要重踩的坑

1. **`PlannedStreetInfo` 表达力上限**：只有 `roadType` + 四向 bool，装不下"中心等级 + 四边边等级 + 过渡位"。V3 语义必须走自有 API（duck interface / `V3Plan`），不要试图塞进它。
2. **TLC 有效路类会二次加工**：`getEffectivePlannedRoadType` 的"相邻原始城市格"规则会裁掉规划路，需 `@WrapOperation` 包 `EffectiveStreetResolver.resolve`，**保留 TLC 全部否决权**。
3. **V2 维度的 TLC 持久模式必须保持 `HIERARCHICAL_GRID_V1`**：否则 `hierarchicalOpen`、坡道、桥高分支与整条随机流都会变。
4. **LC2H 会把 `DefaultDimensionInfo` 与 `BuildingInfo` 的多个 getter `@Overwrite`**：构造期 TAIL 读 `getStreetGenerationMode()`/`getProfile()` 会拿到未初始化的 ThreadLocal 实现 → 静默降级。判定必须只用构造器实参 + 公开未覆写的 `LostCityWorldGenData`。
5. **样式决定与激活判定必须同源**：V2 用"一次性计算 `ConstructorDecision` 并存进 `@Unique` 字段"避免"半切换维度"（套上 V2 样式却在 TAIL 拒绝启用）。
6. **`City.isChunkOccupied` 同时覆盖建筑与街道**，两种语义不要混用（道路可铺性 vs 设计者显式内容不可被自动否决覆盖）。
7. **部件缺失在 TLC 中是硬崩**（`RegistryAssetRegistry.get` 抛 `RuntimeException`），整维度预检必须 fail-closed。
8. **`citystyle` 的 `largebridges` 经 `addAll` 继承、子风格无法移除**——只加显式条目不够，必须在消费点替换。
9. **`Bridges.generateBridge` 是逐区块 16×16 部件**（Z 方向靠坐标转置），不是整跨模板；桥面高度取 `GROUNDLEVEL`（持久模式为 HGV1 时）。
10. 施工纪律：**同一时刻只允许一个写者**。本会话多次因并发编辑红树、因核验快照滞后误判"零产出"——派活要串行，核验要看工作区而不是只看回报。

## 8. 完成标准

- V3 抽象证据**可从 `tools/gradlew.sh test --offline` 复现**；
- 契约/回归套件只增不减，全绿；
- 一次 TLC-only 与（若环境允许）TLC+LC2H 的启动冒烟，`ERROR`/`FATAL`=0，且能看到三级路面与过渡件的铺设证据；
- 一次代表性耗时冒烟；**最终游戏内视觉与可玩性由用户验收**；
- 未做到的事写进 `docs/DELIVERY.md`，不用"应该可以"顶替。
