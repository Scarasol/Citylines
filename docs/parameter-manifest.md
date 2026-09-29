> 历史过程记录：保留原文供追溯；其中旧配置、旧接口和阶段状态不代表当前版本。唯一当前交接与接口说明见 [DELIVERY.md](DELIVERY.md)。

# 参数冻结清单（CONNECTED_DISTRICT_V1）

> 阶段：方案 §1 阶段 1（抽象用例与调参）的**参数冻结**产物。
> 依据：`docs/WORKFLOW.md`、`/mnt/d/Workspace/Knowledge Base/Lost Cities/路网优化方案/抽象用例与调参.md` §2–4。
> 本清单只覆盖**零 MC 依赖的纯算法原型**；未接 Minecraft，未做真实性能测量。

## 1. 原型版本与代码位置

| 项 | 值 |
| --- | --- |
| 算法 | `CONNECTED_DISTRICT_V1`，`PlannerParams.CURRENT_VERSION = 2`（v2 = 冻结后修复批次：锚点距离 §6.3 + 路线边集 §6.5；v1 的形态数据已作废） |
| 主代码 | `src/main/java/com/scarasol/citylines/road/core/`（`ConnectedDistrictPlanner`、`GateSelector`、`BridgeScanner`、`PathTree`、`PlannerParams`…） |
| 契约测试 | `src/test/java/com/scarasol/citylines/road/core/`（`PlanInvariants`、`SyntheticWorld`、`TestWorlds` + 各用例测试类） |
| 扫描器 | `ParameterSweepTest`（JUnit，表输出到 stdout，同时存于 `build/test-results/test/*.xml`） |
| 固定盐 | `seed=0x5EED`、`dimensionId="citylines:test"`（仅用于确定性破同分，不作为选参样本） |

## 2. 被测候选网格（实际跑满，共 135 个候选）

- `districtSize S ∈ {8, 16, 24}`
- `straightCost=1` 固定；`turnPenalty ∈ {0,2,4}` × `hugPenalty ∈ {0,1,2}`（9 组）
- 集散锚：`enableCollectors=true` × `collectorMinArea ∈ {16,24,32}`、`enableCollectors=false` × `minArea=24`（4 组，`maxCollectorsPerComponent=2` 固定）
- 桥走廊：`bridgeBandSpacing ∈ {8,16}`（`band=16` 仅与 `minA=24` 组合，未跑全交叉）
- 每候选在 8 个抽象用例上评估（dense / snake / blocked / lakeRing / channel3 / staircase / checkerboard / mixedLevel），每用例最多 3 个分区（S=8/16/24 分别共 24/17/16 张 plan）。

## 3. 硬约束全部为 0 的证据

`ParameterSweepTest` 对**每个候选的每一张 plan** 跑 `PlanInvariants.assertAll`：135/135 候选、共 2565 张 plan
（S=8: 1080、S=16: 765、S=24: 720），**hard violations = 0、孤立单格路 = 0**（版本盐 2 下重跑，与 v1 相同）。
逐条对应（其中 C 的判定在版本盐 2 中按设计契约修正，见 §6.3）：

| 不变量 | 内容 | 覆盖用例 |
| --- | --- | --- |
| A 端点连通 | 同 `Terminal.component()` 的终端在路网中互相连通、且都是路格 | 全部 8 用例（含 snake 瓶颈/分叉、blocked 双侧锚、桥头） |
| B 边双向 | 界内边双向；越界边必须有门伙伴；门两侧 plan 互相发布同一 `Gate` 且两侧门格都是路 | 全部（dense 3×3 实心城、双分量边界、comb 8 分量对） |
| C 共享边等级 | **已规划边**满足 `edgeClass(A→B)=min(roadTypeA,roadTypeB)=edgeClass(B→A)`；相邻但未被任何路由连通的双方互不赋边等级（不变量 C 曾把"相邻即相连"写死，那正是 DEFECT-2 本身） | 全部，含跨区门两侧、mixedLevel 两高度分量贴边 |
| D 禁铺面 | 水格/硬障碍/非城市/异 profile/公路格永不成路、永不被边穿过；边永不跨分量 | blocked、highway、lakeRing、bridgeChannel、checkerboard、mixedLevel |
| E1 无孤立路格 | 不存在零度（无任何边）的路格 | 全部；门格 / 桥头 / 锚点退化情形单测（`tinyAnchor`、3 格桥头小岛、comb 8 齿） |
| E2 无未解释断头 | 度为 1 的叶子必须是终端、SECONDARY 集散端或重算出的分量核心 | 全部 |
| 终点分类 | 度为 ≤1 的路格数与 `terminations()` 计数一致，且不含 `BUDGET_EXCEEDED` | 全部 |
| 单分量单连通块 | 每个可铺设分量的所有路格属于**同一个**连通块（不发布局部残段） | 全部 |
| 结构上界 | `gates ≤ maxGates`、`dijkstraStates ≤ maxStatesPerComponent`、`relaxations ≤ maxRelaxationsPerComponent`、`budgetExceeded=false` | 全部（含 checkerboard 128 分量、comb 8 分量对） |

补充证据：`GateBlockerTest.gateSelectorAcceptsCandidatePairs`（实心边界 16 个候选格对、每分量对恰 1 门）、
`MultiComponentBorderTest`（同一边界 2 个分量对 → 恰 2 门，双侧一致）、`DenseDistrictTest`（9 分区并集为同一路网）。

## 4. 候选对比（版本盐 2 实测；关键列，均为每分区均值）

> 下表由 `ParameterSweepTest` 在 `CURRENT_VERSION = 2` 下重跑得到（同一 135 候选网格、同一 8 个抽象用例），
> 与冻结时的 v1 数字不同：集散锚改为"距已有主干最远"、主干并集改为顺序挂接（§6.3/§6.5/§6.6）后，
> 主次干里程重新分布，其余成本量不变。

| 候选 | roads | primary | secondary | gates | spans | states | relaxations | factSamples |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| S=8, 1/2/1, coll on/24, band 8 | 16.2 | 9.7 | 6.5 | 1.30 | 0.4 | 145.3 | 516.5 | 733.3 |
| S=16, 1/0/0, coll on/24, band 8 | 26.8 | 14.6 | 12.1 | 0.90 | 0.5 | 413.8 | 1531.6 | 1460.7 |
| S=16, 1/2/1, coll on/24, band 8 | 24.8 | 14.5 | 10.3 | 0.90 | 0.5 | 413.8 | 1531.6 | 1460.7 |
| S=16, 1/2/1, coll **off**, band 8 | 14.5 | 14.5 | 0.0 | 0.90 | 0.5 | 413.8 | 1531.6 | 1460.7 |
| S=16, 1/2/1, coll on/24, band **16** | 24.4 | 13.6 | 10.8 | 0.90 | 0.4 | 413.8 | 1531.6 | 1460.7 |
| S=24, 1/2/1, coll on/**16**, band 8 | 41.3 | 25.4 | 15.9 | 1.40 | 0.8 | 705.4 | 2642.8 | 3456.0 |
| S=24, 1/2/1, coll on/**32**, band 8 | 41.3 | 25.4 | 15.9 | 1.40 | 0.8 | 705.4 | 2642.8 | 3456.0 |
| S=24, 1/2/1, coll **off**, band 8 | 25.4 | 25.4 | 0.0 | 1.40 | 0.8 | 705.4 | 2642.8 | 3456.0 |

敏感性（有差异的因子才列出，均为 S=16 / coll on/24 / band 8 的行内比较）：**S** 影响全部代理量
（states 145/414/705，gates 1.30/0.90/1.40）；**turn/hug** 在 v2 下不可忽略：九组权重把 roads 拉到
24.4–26.8（极差约 9%）、secondary 9.8–12.1（极差约 19%），而 states/relaxations/factSamples 仍**完全相同**；
冻结值 1/2/1 保留（协议已冻结，本清单不重新选参），它落在 roads 偏低、secondary 偏低的一端，0/0 会多约 8% 道路；
**集散锚**使 secondary 0→10.3（S=16）、0→15.9（S=24），states 不变（复用同一棵 Dijkstra 树）；
**minArea** 在 16/24/32 之间仍无差别；**band** 8→16 使 spans 0.5→0.4、roads 24.8→24.4、secondary 10.3→10.8，
states/relaxations/factSamples 同值。

## 5. 最终冻结参数

| 参数 | 冻结值 | 选择理由 |
| --- | --- | --- |
| `districtSize` | **16** | 方案 §4/§7 的静态上界（`MAX_GATES=64`、states ≤1024、relaxations ≤4096、冷建 5×256=1280 次采样）全部按 S=16 推导；S=8 使分区/边界数翻倍且每区块 states 更高（145/64=2.27 格 vs 414/256=1.62），S=24 使单分区状态与采样 ×1.7 而上界须重算。改动 S 会同时改动所有已冻结常量，收益不足以匹配风险。 |
| `straightCost / turnPenalty / hugPenalty` | **1 / 2 / 1** | 九组权重候选的代理量差异 ≤1.2%，成本量（states/relaxations/factSamples）完全相同。按「差异不明显取成本更低者」本可退化为 0/0，但 0/0 会让转弯/贴边偏好完全失效（形状意图无从表达）；2/1 与 `defaults()` 一致、成本相同且 secondary 略少（2.2 vs 2.5）。后续真实形态评估若仍无差异，可无成本简化为 0/0。 |
| `collectorMinArea / maxCollectorsPerComponent / enableCollectors` | **24 / 2 / true** | 集散锚以 0 额外搜索成本（states 不变）增加 secondary 0→2.2；minArea 16/24/32 在所有 S 上均无差别，取中间值 24。 |
| `enableLoops / maxLoopDetour` | **false / 6** | 原型中没有回路代码路径，保留开关与设计默认值，不启用。 |
| `bridgeBandSpacing / bridgeMaxLength / bridgeChance` | **8 / 12 / 1.0** | band=8 比 16 多 25% 跨水 span（0.5 vs 0.4/分区）、roads 26.1 vs 25.6，而 states/采样**完全相同**，符合「稀疏走廊、每 8 格一条」的设计意图；`bridgeMaxLength` 取 `BridgeScanner.HARD_MAX_LENGTH=12`；`chance=1.0` 使每条合法 span 必建，桥分布完全确定、不随种子随机。 |
| `markingEnabled` | **false** | 标线属于渲染层（方块/材质阶段），不进入规划输出与指纹语义。 |
| 断面 PRIMARY / SECONDARY / TERTIARY | **1/1/12 + 两格中央线**（走 1+缘 1+车 12，中间 7/8 两格为白色混凝土）/ **2/1/10** / **4/1/6（本地车行道浅灰 `smooth_stone`）** | 已由 `RoadSection` 固定，三档总宽均为 16。**2026-09-25 由决策 D4 变更**（原 `2/1/10` 与 `3/1/8`），见 `docs/V3-四问题决策记录.md` 与 `docs/V3-四问题改动交付.md`。 |
| `algorithmVersion` | **2** | `CURRENT_VERSION`；v2 是冻结后修复批次的统一盐（锚点距离 §6.3 + 路线边集 §6.5 + 主干顺序挂接 §6.6 + TLC 事实层/水位 §6.7 + 桥馈送），几何因此改变，按 §8 的冻结后规则必须升版本盐；批次内无盐 2 世界生成、水位修正对默认 profile 恒等，故不逐项升盐（理由与打破条件见 §6.7）。 |

## 6. 冻结前修复的实现缺陷（本清单的证据依赖它们）

1. **门选择恒空**（`GateSelector`）：高侧内陆余量曾用 `forward.opposite()` 测量，方向指向分区外，`marginB` 恒为 0，
   所有候选被跳过（`gatesExamined=0`）。已按方案 §4.2「本侧向内法线方向」改为 `forward`；
   重算后实心边界有 16 个候选、每分量对恰 1 门，9 分区并集为同一路网。
2. **孤立单格路**（`ConnectedDistrictPlanner`）：当分量的核心格恰是其唯一终端时，主干并集只有一个零度路格。
   现按设计「短主路」语义扩展一步到分量内**最远邻居**（N/E/S/W 顺序 + 深度严格更大，确定性、不用 hash），
   并把该端记为 `CORE` 终端；同时 (a) 一段式分量（面积 1）不再接受硬终端、`BridgeScanner.predicate` 要求
   两岸分量面积 ≥ 2，(b) 零度路格今后**一律**记 `BUDGET_EXCEEDED`（不再因“deliberate 终端”而静默豁免）。
   回归用例：`ObstacleAnchorTest.isolatedRoadCellAtTheOnlyAnchor`、`AdversarialTest.bridgeHeadIslandsPublishIsolatedStubs`、
   `AdversarialTest.oneCellComponentStaysUnpaved`、`AdversarialTest.combBorderProducesValidPlans`。
3. **锚点距离按"距主干/核心最远"，不再用边缘深度**（版本盐 1 → 2，`CURRENT_VERSION = 2`）：
   集散锚曾按 `DistrictFacts.depth`（到分量**外缘**的距离）降序选取，短主路的"最远格"也曾取边缘深度最大者。
   两者都可能选中紧贴已有主干/核心的格子，产出 1–2 格残桩（`方案.md` §4.3 要求"距已有主干最远的内陆格"，
   无门分量要求"距核心最远"）。现在 `ConnectedDistrictPlanner` 对分量做多源 BFS：
   - 集散锚：以本分量全部 `arterial[]` 格为源，按 `arterialDistance` 降序，再以原边缘深度、hash、局部序号破同分；
   - 短主路：`maybeShortCore` 以 root 为源做 BFS，取 `rootDistance` 最大者（同距离用 hash、序号破同分）。
   同时把边掩码的邻格判定限制在**同一分量**内（跨分量贴边不得相连，本就不变量 D 的要求）。
   回归用例：`AnchorDistanceTest`（16×3 走廊：旧规则选中的 (9,1) 距主干仅 1 格 = 残桩，新规则必须选距主干 8 格的
   (15,0)；无终端分量必须到达距 root 最远的格）；把旧排序临时放回时这两个用例实测失败。
   几何改变后已按 §8 重跑 `ParameterSweepTest`（135 候选、2565 张 plan、硬违规 0、孤立路格 0），
   本清单 §1/§3/§4/§5 已同步为版本盐 2 的实测数字。
4. **契约助手 C 的旧假设**（`PlanInvariants.assertEdgeClasses`）：它曾由两侧中心等级直接推导
   `expected = min(own, neighbour)`，即隐含"任意两个相邻路格必然相连"——这正是"按相邻格补边"的缺陷本身
   （`抽象用例与调参.md` L13：只有双方都认可的边才赋边等级）。现改为只对**已规划边**断言
   `min(own, neighbour)`（双向），相邻但未路由的双方断言**无边等级**。
5. **边必须来自被选中的路线**（`ConnectedDistrictPlanner`，与 3 同属版本盐 2 的冻结后修复批次）：
   `edgeMask` 曾按"邻格也是路"直接生成，于是任何两个相邻路格都被自动连边，凭空造出未规划的路口与小回路
   （`抽象用例与调参.md` L13 禁止"按相邻格等级自行补边"）。现在 `compute` 只记录真正走过的边——
   每个终端路径回溯到 root 的每一步、每条集散路径到首个主干格的每一步、短核心的确定性延伸一步、
   以及每条被接受的门（越界半边）——并由该集合唯一推导 `edgeMask`；新公开类型 `RouteEdge`
   与 `DistrictPlan.routeEdges()` 使该集合可被独立核对。回归用例：`RouteEdgeTest`
   （掩码 ⇔ 记录边双向一致；16×4 走廊里 (14,2)-(15,2)、(14,3)-(15,3) 这类**相邻但未路由**的路格必须不连通）。
   把旧的几何掩码临时放回时该用例实测失败。
6. **主干并集改为顺序挂接**（`ConnectedDistrictPlanner`，同属版本盐 2 的批次）：
   每个终端原先各自用 `pathFromRoot` 重建自己到 root 的路径；由于 Dijkstra 树建在
   `(格, 进入方向)` 状态上，两条 root 路径投影到格级可能成环（实测 dense D[0,0] 一个 16 格环、
   channel3 D[1,1] 一个 18 格环）。现与集散路径一致改用 `pathToExisting(..., arterial)`：
   后一个终端挂到**已在主干集合中的第一个格**，并集因此是父树的子树、按构造无环；
   终端连通性不变（挂接点本身已在树上），只是后一个终端的发布路径可能短于它自己的 root 路径。
   实测 72 张 plan 的环数 15 → 4（单张最多 2 → 1），S=8 抽样 22 → 14、S=16 抽样 13 → 4；
   几何相应变化：冻结行 roads 26.1 → 24.8、primary 15.1 → 14.5、secondary 10.9 → 10.3，
   硬违规与孤立路格仍为 0，determinism/order-independence 仍全绿。
   集散支路之间的交叉环未消除（见 §7.4），未继续扩大改动。
7. **TLC 事实层修正**（`road/tlc/TlcRoadFacts`，同属版本盐 2 的批次；本清单的"S 门 桥"上界结论依赖事实层的正确性）：
   - `hardBlocked` 不再用 `City.isChunkOccupied`（它把预定义**街道**也算占用，导致 `ASSET_ANCHOR` 在真实生成中是死代码），改为只由预定义**建筑**与"TLC 不铺面的车站表面"产生；预定义街道是可通行土地与硬终端。实机证据：`predefinedcities registry=1 streets=576`、`V2 plan … assetAnchors=94`、8 条 `V2 predefined-street anchor:`。
   - 车站条件由 `stationEntrance`（把 `STATION_UNDERGROUND` 也当障碍、又漏判非栅格 `STATION_SURFACE`）换成 TLC `LostCityTerrainFeature.generateStreet` L1171-1172 的铁路条件（查 `Railway.getRailChunkType`）。
   - `highwayConflict` 由恒 `false` 改为 TLC 真实条件（同源 L1170-1171）。
   - `waterLevel` 复刻 `BuildingInfo.waterLevel` 的优先级：`profile.SEALEVEL == -1 ? 维度海平面 : profile.SEALEVEL`；`belowWaterLine(height, level)` 抽成纯谓词。**只影响显式 `sealevel` 的 profile（如 `atlantis`，`seaLevel=89`）**，默认 profile 为 `-1`、行为与 v1/v2 完全一致。
   - **盐的选择与理由**：以上四项与 §6.3/§6.5/§6.6 同属一个未发布的冻结后修复批次，批次内没有任何盐 2 构建生成过世界；水位修正对默认 profile 是严格恒等、对显式 `sealevel` 的 profile 只改变事实取值（且这些 profile 从未在本批次被用于生成）。因此**不单独升盐**，统一用 2；**一旦用盐 2 构建生成过世界（或某维度采用显式 `sealevel` 的 profile 生成）就必须改为 3**（§8）。
   - 纯谓词测试：`TlcRoadFactsRulesTest`（预定义街道/建筑、地下与地面车站、高速两侧、水位优先级、`canDoStreetOrPark` 穷举等价）。

## 7. 已知限制

1. 事实层仍以 `SyntheticWorld` 抽象几何为选参依据；真实 Lost Cities 输入已在冒烟中部分验证（预定义街道锚点实际出现、V2 激活与铺设、0 异常），
   但**桥面方块在实机从未被观测到**（八次专用服务端尝试中没有任何 plan 出现 `spans >= 1`；根因与复现指引见 `docs/DELIVERY.md` §6.1），
   且真实水面/高度对桥扫描的影响仍只由契约测试覆盖。
2. 未做真实性能：只记录 states/relaxations/factSamples 等抽象成本代理，无 p95/p99、无内存曲线；不代表运行时开销。
3. 固定世界种子只用于**冻结后的冒烟**与破同分，不作为选参依据；本清单结论来自手工抽象用例。
4. 原型中仍可能出现设计未声明的小回路，但来源与数量都已改变：几何贴边不再连边（§6.5），
   主干并集改为顺序挂接后不再投影出环（§6.6）。**残余回路只来自集散支路互相交叉**：后加的集散路径
   以"首个主干格"为停止条件，可能横穿先加的集散路径并各自汇入主干，形成环。
   实测（8 个抽象用例 × 3×3 分区 = 72 张 plan）：顺序挂接前 15 个环、单张最多 2 个；挂接后 4 个环、单张最多 1 个；
   同批 S=8/16 抽样 27/23 张：S=8 由 22 → 14、S=16 由 13 → 4。所有环的边**都被路线走过**，
   不属于"按相邻格补边"；`enableLoops=false` 只表示不主动造回路。未在本轮修复中消除
   （消除需要让集散路径也停在"首个已有路格"而非"首个主干格"，会偏离 §4.3 的措辞）。
   `debugLogging=true` 时 `V2 plan ... routeCycles=N` 会把该计数写进日志。
5. `DistrictPlan.roadTypeAtWorld/infoAtWorld` 不校验分区归属（按 `floorMod` 取本区局部格）：调用方必须用
   `ConnectedDistrictPlanner.infoAt` 或先按 `DistrictKey` 路由到 owner plan，否则会读到相邻分区的格子。
6. S/门/桥的真实质量（跨区连通后的观感）仍未做游戏内验收；本阶段只保证硬约束与抽象成本代理。

## 8. 冻结后的变更规则

已冻结的参数共同决定 `PlannerParams.fingerprint(seed, dimensionId)`，并随 `DistrictPlan.fingerprint()` 持久化。
**冻结后若要修改任何一项（含权重、S、集散锚、桥走廊、断面、markingEnabled），必须递增
`PlannerParams.algorithmVersion`（版本盐）**，否则已生成区块会静默沿用旧布局、新旧图混排；
新版本需重跑 `ParameterSweepTest` 与全部契约测试，并更新本清单与 `docs/WORKFLOW.md` 的门槛记录。
