> 历史过程记录：保留原文供追溯；其中旧配置、旧接口和阶段状态不代表当前版本。唯一当前交接与接口说明见 [DELIVERY.md](DELIVERY.md)。

# V3 证据的可复现入口

本文件记录 **V3 抽象证据如何用 `tools/gradlew.sh test` 复现**。这是交接文档
`docs/V3-HANDOVER.md` §5 的第 1 步，也是后续任何改动 `road/core/V3*` 的回归基线。

## 1. 为什么需要这一步

在本次改动之前，V3 的抽象证据只存在于两个 **`public static void main` 校验器**里：

| 类 | 位置 | 改前状态 |
| --- | --- | --- |
| `V3AbstractCases` | `src/test/java/.../road/core/` | `verify`/`extreme`/`rare`/`sweep` 四个模式，`@Test` = 0 |
| `V3TopologyComparison` | `src/test/java/.../road/core/` | TLC/V2/V3 参数扫描对照，`@Test` = 0 |

两者都能编译、也能手工跑，但 `gradlew test` **完全不覆盖 `road/core/V3*`**。也就是说
交接记录里"纯 Java `V3AbstractCases verify` 覆盖……"是**手工 CLI 结果**，不受回归保护：
任何一次改动都可能静默破坏 V3 而测试仍然全绿。

## 2. 现在怎么跑

```bash
# 回归基线：17 个新增用例随全量套件一起跑（无需额外步骤）
tools/gradlew.sh test --offline

# 只跑 V3 的两个新测试类
tools/gradlew.sh test --offline --tests '*V3AbstractCasesTest*' --tests '*V3TopologyComparisonTest*'

# 手工 CLI（保留原命令形态，用于快速探查，不替代 test）
tools/run-v3-probe.sh com.scarasol.citylines.road.core.V3AbstractCases verify
tools/run-v3-probe.sh com.scarasol.citylines.road.core.V3AbstractCases extreme
tools/run-v3-probe.sh com.scarasol.citylines.road.core.V3TopologyComparison
```

`tools/run-v3-probe.sh` 是本项目新增的探针运行器：它用一次 Gradle 调用导出 **test runtime
classpath**，再交给 Windows JDK 运行。**为什么需要它**——该 classpath 约 9.5 KB，超过
`cmd.exe` 约 8191 字符的命令行上限；批处理里写 `set CP=<长值>` 会被静默截断，`-cp` 内联
同样失败（本次实测：`ClassNotFoundException` 与"命令行太长"两种表现）。Java 的 `@argfile`
没有该上限，因此 classpath 写进 argfile；`@` 引用用**相对 Windows 路径**传递，因为 WSL 会
破坏内联反斜杠参数。

## 3. 各测试类分别在守什么

### `V3AbstractCasesTest`（12 个用例）

每个用例驱动 `V3AbstractCases` 里对应的一个 `verify*` 分组。**校验逻辑一行未改**，只是把
原先"一个 `verify()` 塞全部"改成分组方法，并给断言补上坐标与世界名（失败信息形如
`render transition changed the logical edge class at 11,0 S: edge=TERTIARY`）。
`main` 的 `verify` 模式按同一顺序调用这些分组，因此 CLI 与 JUnit **不会各自漂移**。

| 用例 | 守的语义 |
| --- | --- |
| `defaultParametersKeepTheReviewedScale` | 默认参数仍是 `M=10 / K=3 / S=30`、单集散轴、带当前版本盐 |
| `stationEntrancesPreferDetourThenRoadPriority` | 轴内站口先试有界绕行；绕行被堵才 `roadWinsStation` |
| `axisGapsRecordReasonAndFinalConnectivity` | 预定义建筑造成的轴缺口记录原因；`connectedAround` 与最终显式图一致 |
| `transitionPortsStayLocalAndMirrored` | 过渡臂不出超单元、与邻格互认、**只用于渲染且为 TERTIARY 逻辑边** |
| `worldInvariantsHoldInEveryFactWorld` | 负坐标、跨界边互认、单边路必有终止原因、本地路度 ≤2、缓存逐出不改图（4 个事实世界） |
| `openMapKeepsEveryRoadClassAndLargeParcels` | 开阔图三级齐全、无 `lostParcelAreas`、每超单元有 3×3/7×7/9×9 候选 |
| `conditionalFallbackNeverLosesAFeasibleParcel` | 条件兜底：主次骨架仍允许 7×7/9×9 时不得丢失该候选区（6 个事实世界 × 4 seed） |
| `conditionalFallbackFiresAndCountsWithdrawnCells` | 兜底确实被触发，且计入撤销的本地格数 |
| `nonNestedRareFootprintsSurviveEviction` | 非嵌套长方形足迹（9×9、10×4）不丢；逐出后救援结果不变 |
| `largeOnlyAssetListKeepsAParcelAndLocalRoads` | 只有大足迹的资产清单不会删掉最后一个地块或所有本地路 |
| `fingerprintCoversInputsButNotPerChunkFacts` | 指纹含 seed/维度/参数版本/足迹尺寸，**不含逐格事实内容**（接入方必须自行冻结事实） |
| `nullFactsAreTreatedAsEmptyFacts` | `null` 事实按 `V3Facts.EMPTY` 处理，不抛异常 |

### `V3TopologyComparisonTest`（5 个用例）

把原先只打印不判定的对照表变成**钉住实测值**的回归：

- 默认块（`M=10 K=3 C=1..1 merge=0.7 through=6`）：主/次/本地 `3776/3068/1822`、
  道路占比 `15.0%`、闭合面 `257`、面 P50/P90 `150/306`、3×3 地块 `576/576`；
- TLC：`8320/6304/872`、`26.9%`、面 `999`、P50/P90 `27/100`、地块 `564/576`（**不完整**）；
- V2：`8506/4125/0`、`21.9%`、面 `226`、P50/P90 `170/358`、地块 `576/576`；
- V3 有本地等级而 V2 完全为 0；V3 道路占比低于 TLC、街区 P50/P90 大于 TLC、
  且补回 TLC 漏掉的 3×3 地块；
- 扫描行的相对序：8 组配对里 K=2 的主干格数恒多于 K=3、道路占比恒高于 K=3，每组都保住全部 576 个地块；
- 三套图（TLC/V2/V3）在采样窗口内**只发布"路到路"的边**，且该检查非空跑（统计了受检边数）。

上述数字与知识库《算法完成与接入移交》记录一致，本次已逐项复算通过。

**刻意没有钉的两项**（实测非单调，钉住等于把偶然当性质）：
- *本地路格数*：8 组配对里有 4 组是 K=3 多于 K=2（`through=6` 侧），不存在干净序；
- *闭合面数量*：路更稀疏会闭合出**更少**可区分面，所以 K=2 在部分配对上面数反而多于 K=3。

### `TlcV3FactSourceTest`（11 个用例，第 3 步）

覆盖 `ChunkFacts` → `V3Facts` 的映射，以及适配器的坐标契约。
**它不加载 Minecraft 运行时**，所以只覆盖映射与坐标，不覆盖真实维度接线。

| 用例 | 守的语义 |
| --- | --- |
| `stationEntranceMappingMatchesTheRailRule` | 只有"地表可铺且是地下入口"才是可裁决站口；`STATION_SURFACE` 是硬阻断，**绝不能被发布成站口** |
| `undergroundStationsAreOrdinaryPavement` | `*_UNDERGROUND`/`NONE` 不占地表 → 普通可铺，不是站口 |
| `buildingEligibleNeverNarrowsRoadRights` | `buildingEligible` 不阻拦道路；预定义建筑与车站阻断可区分归因 |
| `predefinedStreetMappingKeepsTheAnchor` | 预定义街道是土地与锚点，不是硬障碍 |
| `predefinedBuildingIsRecoveredWithoutANewField` | 预定义建筑标志 = "硬阻断且非铁路所致" |
| `highwayConflictIsFoldedIntoHardBlocked` | 同层高速折进 `hardBlocked`，但**不**记成 `predefinedBuilding` |
| `emptyAndNullFactsAreHandled` | 荒野事实 → `V3Facts.EMPTY`；`null` 也在边界处映射为空 |
| `adapterForwardsAbsoluteCoordinatesWithoutAHaloArray` | 规划器真实请求的坐标范围被记录（`29..60`/`-31..0` @ S=30），证明不是固定 halo |
| `adapterIsAPureFunctionOfTheCoordinate` | 同坐标同结果，且 x/z 不转置 |
| `directionBitOrderIsStable` | `N/E/S/W` 位序 `1/2/4/8`（整个资产/计划 ABI 依赖它） |

### `V3RoadGraphTest`（10 个用例，第 4 步）

覆盖 V3 的**有效图层**：等级映射、街情布尔次序、显式边（非邻格推断）、三级路权与整足迹检查。
**不依赖 MC 运行时**——这正是 `footprintTouchesReservedRoad` 收裸 `int` 而非 `ChunkCoord` 的原因
（`ChunkCoord` 需要 `ResourceKey<Level>`，而 `Level.OVERWORLD` 会触发 MC 注册表静态初始化，
在纯单测里直接 `ExceptionInInitializerError`）。

| 用例 | 守的语义 |
| --- | --- |
| `levelMappingIsExact` | V3 三级与 TLC `PlannedRoadType` 同名同序（含序数逐一核对） |
| `allThreeLevelsSurviveTheMapping` | 开阔超单元真的同时产出三级（断言集合，不断言扫描序） |
| `streetInfoBooleansAreNotTransposed` | 街情四 bool 是 TLC 的 `north/south/west/east` 次序；按掩码次序传会把 north 与 east 转置 |
| `nonRoadChunkHasNoEdges` | 非路格一律 `NONE` 且无任何边 |
| `adjacencyAloneNeverProducesAConnection` | **直接构造**证实：邻格是路但无显式边 ⇒ 不连通；发布显式边才连通 |
| `everyLevelReservesWithAnEdge` | 三级都能占路权；有等级无显式边的是幽灵路，不占；`NONE` 中心不占 |
| `reservationAgreesWithStreetInfo` | 路权与街情对"是不是路"必须一致（否则会为不会渲染的路拒绝建筑） |
| `footprintTouchesOnlyOverlappingRoads` | 仅重叠才拦，仅相邻不拦 |
| `footprintChecksEveryChunkNotJustTheCorner` | 足迹右下角有路也拦（不是只看左上角） |
| `accessInterfaceAnswersFromTheOwningPlan` | 含负坐标在内，一律由拥有该坐标的计划作答 |

## 4. 这不是什么（边界，必须随证据一起引用）

- **不是拟真度阈值**。这套抽象对照不含地形、不含资产、不含生成耗时；知识库明确说没有约定的
  现实阈值就不能称 `K=3` 过稀，因此这些数字**不能**用来支持"改 K=2"的结论。
- **不是 TLC/LC2H 接入验证**。它只覆盖纯算法（零 Minecraft/TLC 依赖），**不覆盖** Mixin 注入、
  `V3FactSource` 真实适配、路权消费者、部件铺设或游戏内表现。这些仍属交接文档 §5 的第 3–6 步。
- **不改算法**。本次只搬运校验逻辑与补文档，`road/core/V3*` 的规划行为一字未动；
  参数冻结后的几何/策略改动**必须升 `V3Params.CURRENT_VERSION`**。

## 5. 本次实测

| 项 | 结果 |
| --- | --- |
| 改前基线 | `cleanTest test --offline` → 128 tests / 0 failures / 0 skipped，**V3 测试类 0 个** |
| 改后 | `cleanTest test --offline` → **169 tests / 0 failures / 0 skipped**，23 个测试类 |
| 增量 | **+41**（`V3AbstractCasesTest` 12 + `V3TopologyComparisonTest` 5 + `V3AreaSemanticsTest` 3 + `TlcV3FactSourceTest` 11 + `V3RoadGraphTest` 10），无用例被删除或放宽 |
| `build --offline` | BUILD SUCCESSFUL |
| 失败检测能力 | 已实测：把"过渡臂必须是 TERTIARY"临时改成 `SECONDARY` 后，对应用例 FAILED 并给出坐标级信息；随后已还原 |
| V3 套件耗时 | `V3AbstractCasesTest` 0.78s、`V3TopologyComparisonTest` 2.11s（不构成构建负担） |
| 面积语义（§5 第 2 步） | `V3AreaSemanticsTest` 3 个用例：`MultiSettings.DEFAULT` 与 `citylines:v2_standard` 的 `areasize` 都是 10，且等于 `V3Params.selectedForDefaultArea().areaSize()` |
| CLI 兼容 | `V3AbstractCases verify` 与 `V3TopologyComparison` 的输出与改前逐字一致 |
