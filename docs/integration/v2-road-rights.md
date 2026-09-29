> 历史过程记录：保留原文供追溯；其中旧配置、旧接口和阶段状态不代表当前版本。唯一当前交接与接口说明见 [DELIVERY.md](../DELIVERY.md)。

# V2 路权集成（阶段 2：有效路类 + 多区块路权）

本文记录阶段 2 中"路权"部分的**实现事实**：改了什么、为什么这是允许注解集合下的最小正确缝、
V2 未启用时如何保证逐位一致，以及**明确不保证**的部分。
基线：Lost Cities `1.20-7.5.5`（快照 `reference/lostcities-src/`），MixinExtras `0.3.6`。

相关设计依据：`docs/integration/tlc-integration-points.md` §3.3（有效路类）、§4（多区块路权）、§11.1（M3/M4/M13）。

---

## 1. 范围

| 项 | 状态 |
| --- | --- |
| V2 有效路类（`BuildingInfo.getEffectivePlannedRoadType` 的邻格启发式） | **已实现**（M3） |
| 多区块建筑整 footprint 路权（原生路径） | **已实现**（M13，写入 `buildingGrid` 之前拒绝） |
| 多区块建筑整 footprint 路权（LC2H 快速路径兜底） | **已实现**（M4，消费端拒绝） |
| V2 街道部件渲染、坡道、V2 桥面 | 未实现（阶段 3；见 WORKFLOW §5） |
| 单格建筑路权 | 无需新代码：`characteristics.plannedRoadType != NONE` 已由 `checkBuildingPossibility` 否决 |

新增文件：

| 文件 | 作用 |
| --- | --- |
| `road/core/RoadRights.java` | 纯函数路权谓词（无 MC/TLC 类型，可 JUnit 直测） |
| `road/tlc/V2RoadRights.java` | TLC 适配层：有效路类、footprint 查询、消费端拒绝 |
| `road/tlc/V2RoadDiagnostics.java` | 仅 `debugLogging=true` 时输出的有界诊断 |
| `mixin/tlc/BuildingInfoMixin.java` | M3（两处 `@WrapOperation`）+ M4（`initMultiBuildingSection` RETURN） |
| `mixin/tlc/MultiChunkMixin.java` | M13（`canPlaceBuilding` HEAD 取消） |
| `src/test/java/.../road/core/RoadRightsTest.java` | 掩码规则与 footprint 重叠的单测 |

---

## 2. 有效路类：只在 `EffectiveStreetResolver.resolve` 处包裹

`BuildingInfo.getEffectivePlannedRoadType`（快照 L572–595）的判定分成两半：

1. **优先级否决**：`!isCity`、`City.getPredefinedBuilding`、`City.getPredefinedStreet`、
   `characteristics.multiPos.isMulti()`；
2. **邻格启发式**：本格 raw street 声明连接的每个方向上，要求邻格 `getProfile(邻格) == profile`
   且 `isCityRaw(邻格)`，否则整条路被裁成 `NONE`。

第 2 条正是 V2 不能继承的规则（跨水面桥头、城市掩码边缘、profile 交界都是合法端点），
而它唯一的出口是两次 `EffectiveStreetResolver.resolve(...)` 调用（L575 早退分支、L594 主返回）。
因此 M3 用两个 `@WrapOperation`（`ordinal = 0/1` 分别对应这两个调用点）只替换**邻格输入**：

- V2 维度：`hasConnectedCityNeighbor` ← `RoadRights.isReservedRoad(state.planInfo(chunk))`，
  即"本格是路类 **且** 四向共享边中至少一条是已规划 V2 边"（`RoadPlanInfo.hasEdge`，
  边等级 = 两侧 `min`，本身就是双侧判定的结果）。
- 其余参数（`rawRoadType`、`isCity`、`overridden`）**原样传入**，并且仍然调用 Lost Cities 自己的
  `EffectiveStreetResolver.resolve`，所以"优先级否决"逐条保留，也不复制它的实现。

早退调用点（ordinal 0）在 V2 下结果与原来相同：进入该分支时必有 `!isCity` 或 `rawStreet.roadType() == NONE`，
两者都会让 `resolve` 返回 `NONE`；包裹它只是为了不留下"V2 维度里还有一处走 V1 邻格规则"的暗缝。

**为什么这是最小正确缝（而不是 HEAD-cancel 整个方法）**：

- 方法体其余部分（profile / `isCityRaw` 查询）继续执行，Lost Cities 的缓存副作用与 V1 完全一致；
- 不需要复制 `overridden` 的三项判定，也就不会在 TLC 升级时悄悄改变优先级语义；
- `expect = 1` 是 `@WrapOperation` 的默认值，两个调用点必须用 `ordinal` 分开，
  任一调用点消失都会让 Mixin 在加载期**直接失败**而不是静默失效。

`@WrapOperation` 的 handler **不能**带 `CallbackInfoReturnable`（MixinExtras 0.3.6 会在 APPLY 阶段报
`invalid signature ... unexpected additional method arguments`）：返回值即结果，原调用用
`original.call(原参数...)`。

## 3. 多区块路权

V1 的检查在 `MultiChunk.canPlaceBuilding`（L175–221）内部逐格执行，谓词是 profile 的
`MULTI_BUILDING_STREET_CONFLICT`（默认 `OVERRIDE_MINOR`：只挡 `PRIMARY`，且结果是**路被建筑压掉**）。
V2 要求 `PRIMARY` 与 `SECONDARY` 都在**整个 footprint** 上胜出，且失败模式必须是"**不放建筑**"。

`MultiChunk.MB` 与 `buildingGrid` 是**包级私有**（`record MB(...)` 无修饰符），跨包混入类既无法声明其类型，
也无法用 `@Accessor` 表达，因此无法在 `MultiChunk` 内部改写已记录的选择。可用的两个缝：

| # | 注入 | 路径 | 语义 |
| --- | --- | --- | --- |
| M13 | `MultiChunk.canPlaceBuilding` `@Inject(HEAD, cancellable)` | TLC 原生路径 | 试放**之前**拒绝，`buildingGrid` 不被写入；把 SECONDARY 纳入原生路径的前置否决 |
| M4 | `BuildingInfo.initMultiBuildingSection` `@Inject(RETURN)` | 原生 + LC2H 快速路径 | 已接受的 multi section 被改回 `MultiPos.SINGLE` / `multiBuilding = null` → 该 footprint 上不生成多区块建筑 |

**为什么两条都要**（源码事实，别把它当成冗余）：

- 原生路径上 V1 **自己**就会拒绝与 PRIMARY 重叠的候选（`canPlaceBuilding` 内 `roadBlocks`，
  `OVERRIDE_MINOR` 挡 PRIMARY；`getRoadType` 在 V2 维度经单点委派返回 V2 路类）。
  所以 M13 在原生路径上新增的是 **SECONDARY 也挡**（V1 只挡 PRIMARY），并且保持"写在 `buildingGrid` 之前"。
- LC2H 的 `calculateBuildings` 快速路径**完全不执行** `canPlaceBuilding`，V1 与 M13 都不生效，
  此时 M4 是唯一强制点，且代价是"被拒绝的建筑仍占格位"（见下）。
- 因此运行期观察到的形态是：`multiSections`（被接受的多区块 section）> 0 而 `multiRefusals`（M4 兜底次数）
  可以为 0 —— 这是 M13 提前把重叠候选拒掉的**预期结果**，不是 M4 失效；
  被 M13 拒绝的候选由 `multiCandidatesRefused` 计数。

M4 的细节：

- footprint 由 `multiPos` 反推：`topLeft = coord.offset(-multiPos.x(), -multiPos.z())`，
  尺寸 `multiPos.w() x multiPos.h()`——这正是 `getTopLeftCityInfo` / `getAverageCityLevel` 使用的恒等式，
  因此对 multichunk 内任意一格都会得到同一个 footprint；
- **预定义内容优先**：`initMultiBuildingSection` 的两个 multi 来源用 `City.isChunkOccupied(provider, coord)`
  区分（预定义分支必然 occupied，自动分支必然不 occupied）。预定义多区块建筑**不会**被否决——
  V1 的 `overridden` 一直让显式内容压过自动路，删掉设计者的建筑是回归而不是路权；
- 拒绝后该 chunk 走单格路径：路格由 `plannedRoadType != NONE` 否决建筑，footprint 内的非路格可能生成普通单格建筑。

### 明确不保证（诚实清单）

1. **LC2H 快速路径不是逐位等价的**：`MixinMultiChunk` 在 HEAD 取消 `calculateBuildings` 并交给
   `FastMultiChunkPlanner`，`canPlaceBuilding` 根本不执行。此时被 M4 拒绝的建筑**仍占据**该 multichunk 的
   试放格位，同一 multichunk 内后续建筑可用的位置因此少于"理想 V2"。原生路径没有这个问题（M13 提前拒绝）。
   **该路径在工作区当前依赖集下无法运行期验证**（LC2H 为 `compileOnly`，服务端日志显示 `lc2h absent`）；
   它是按 `reference/lc2h-src` 的字节码分析设计的兜底，属已声明限制。
2. **M4 不改写 `MultiChunk` 内部状态**，也不尝试重排其它 multichunk 的建筑；V2 只保证"路格上不出现多区块建筑"，
   不保证"多区块建筑数量与 V1 相同"。
3. **不借用 profile 开关**：即使服务器把 `MULTI_BUILDING_STREET_CONFLICT` 设为 `OVERRIDE_ALL`，
   V2 维度仍然按 PRIMARY+SECONDARY 全挡（V2 的路权是独立谓词，不改共享的 `LostCityProfile` 实例）。
4. **同一 chunk 的分类规则与 M3 一致**：只有 `roadType != NONE` **且** 至少一条共享边被规划才视为"保留路"。
   中心有路类但没有规划边的格子会被 M3 裁成 `NONE`，因此也不占多区块路权——两条规则必须一致，
   否则会出现"为一条不会渲染的路拒绝建筑"。

## 4. V2 未启用时的逐位一致性

所有新增注入的第一行都是 `CitylinesRoadState.of(provider)`（`null` 或 `!isActive()` 即返回）：

- M3：`original.call(四个原参数)` → 与原调用完全相同的字节码语义；
- M13：直接返回，不调用 `cir.setReturnValue`；
- M4：直接返回，不改 `characteristics`。

`isV2` 只做一次 `provider.getStreetPlanner()` 与接口 `instanceof` 判定（编译期接口、无反射），
`NullDimensionInfo`（GUI/预览）因此安全返回 `null`。

## 5. 诊断

`CitylinesConfig.debugLogging = true` 时，服务端日志会给出（默认 false，完全静默）：

```
[citylines] V2 road kept: dim=minecraft:overworld chunk=(x,z) class=PRIMARY signature=PsPs
[citylines] V2 road stripped by mask rule: ...            # 有路类但无规划边（应接近 0）
[citylines] V2 road stripped by a Lost Cities veto: ...   # 预定义内容/多建筑/非城市
[citylines] V2 rule differs from V1: ... v1=NONE v2=PRIMARY v1ConnectedCityNeighbour=false v2PlannedEdge=true
[citylines] V2 multi-building candidate refused: dim=... footprint=(x,z) 2x2     # M13
[citylines] V2 multi-building refused: dim=... chunk=(x,z) footprint=(x,z) 2x2   # M4 兜底
[citylines] V2 road rights so far: dim=... keptPrimary=... keptSecondary=... strippedByMask=... strippedByVeto=...
            differsFromV1=... multiSections=... multiCandidatesRefused=... multiRefusals=...
```

计数只用于诊断，绝不参与生成（`V2RoadDiagnostics` 的注释里写明了这一点）。
其中 `differsFromV1` 是对**未注入时会得到什么**的反事实计数：它用 TLC 自己算出的
`hasConnectedCityNeighbor` 再调用一次 `EffectiveStreetResolver.resolve`，因此
`differsFromV1 > 0` 才说明这次注入真的改变了有效路类。

## 6. 验证记录（2026-09-23，TLC-only 工作区）

`tools/gradlew.sh build --console=plain`：**BUILD SUCCESSFUL**，94 个测试 0 失败。
`tools/gradlew.sh runServer --console=plain`（删除 `run/world` 后新生成，`debugLogging=true`，
LC2H 未安装）关键行：

```
[citylines] V2 connected-district planner enabled for minecraft:overworld (S=16, fingerprint=bb5c8bb94aec419d)
[citylines] V2 road kept: dim=minecraft:overworld chunk=(9,-10) class=PRIMARY   signature=P.P..
[citylines] V2 road kept: dim=minecraft:overworld chunk=(-6,-13) class=SECONDARY signature=s.sss
[citylines] V2 multi-building candidate refused: dim=minecraft:overworld footprint=(0,-15) 2x2
[citylines] V2 road rights so far: dim=minecraft:overworld keptPrimary=1731 keptSecondary=61
            strippedByMask=0 strippedByVeto=0 differsFromV1=0 multiSections=74
            multiCandidatesRefused=139 multiRefusals=0
Done (32.381s)! For help, type "help"
```

`latest.log` 中 `Exception` / `ERROR` / `FATAL` 计数均为 0，无 crash-report；完整日志留档于
`build/tmp/verify/evidence/run5-final.log`。

**诚实解读（不要过度宣称）**：

1. `keptPrimary/keptSecondary > 0` 证明 M3 包裹后的返回值就是 TLC 最终采用的有效路类
   （这些行只可能由 `@WrapOperation` 的 handler 打印），且 `strippedByMask=0` 说明 V2 路没有被掩码规则裁掉。
2. `multiCandidatesRefused=139` 是 M13 在写入 `buildingGrid` 之前拒绝的真实候选数，
   同时 `multiSections=74` 说明多区块建筑仍然存在——既没有漏挡也没有全挡。
3. `multiRefusals=0` 是**预期**结果：原生路径上 M13 已经先行拒绝，M4 无事可做。
   **LC2H 快速路径没有被运行期验证**（工作区未安装 LC2H，日志为 `lc2h absent`），
   M4 的兜底语义来自 `reference/lc2h-src` 的字节码分析，属已声明限制。
4. `differsFromV1=0`：本次生成中 TLC 的 V1 邻格启发式与 V2 双侧 mask 在**每一个**被解析的路格上给出相同答案——
   因为当前 V2 规划器只在 `pavableLand`（原始城市格）上铺路、且边只连到其它已规划路格，
   邻格天然是原始城市格。这**不**说明包裹多余：规则正确性不应依赖这一巧合
   （跨水桥头、被水/断崖切碎的城市掩码分量、未来的非城市端点都会让 V1 规则裁掉合法路）。
   该计数保留在诊断里，一旦未来的规划器产生这类端点，日志会立刻显示 `differsFromV1 > 0`。
