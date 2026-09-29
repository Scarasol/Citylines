> 历史过程记录：保留原文供追溯；其中旧配置、旧接口和阶段状态不代表当前版本。唯一当前交接与接口说明见 [DELIVERY.md](../DELIVERY.md)。

# LC2H 4.2.4-LTS 对 Lost Cities 路网/街道/特征/高速代码的实际改动 — Citylines V2（CONNECTED_DISTRICT_V1）兼容分析

只读分析产物（未编译、未运行 gradle、未修改任何被分析对象）。

## 0. 证据来源与方法

| 项 | 值 |
| --- | --- |
| LC2H 二进制（唯一权威） | `/mnt/d/Library/.gradle/caches/modules-2/files-2.1/curse.maven/lc2h-1325431/8928853/20845fe885725f0e69a56ef5bbbd5b3fda51b295/lc2h-1325431-8928853.jar` |
| 版本（jar 内 `META-INF/mods.toml`） | `version="4.2.4-LTS"`，`modId="lc2h"`，`[[mixins]] config = "mixins.lc2h.json"`，依赖 `lostcities versionRange="[1.20-7.5.4,)"`、`quantified [2.2.2,)` |
| Lost Cities 源码基线 | 本仓库 `reference/lostcities-src/`（`gradle.properties: version=1.20-7.5.5`，全树无 `citylines`/`CONNECTED_DISTRICT` 字样，即未改动的上游快照） |
| 设计依据 | `docs/WORKFLOW.md`（不 fork TLC、枚举不新增常量、V2 全部由 Mixin 实现）、`docs/integration/tlc-integration-points.md` |
| 方法 | `python3 -m zipfile` 读 jar 条目；Windows JDK `javap -v -p -c -constants` 读注解与字节码；对全部非 NeoForge 类做常量池字节扫描做"是否触及某 API"的否定性证明 |

**结论所依据的两个方法论要点**

1. `org.admany.lc2h.*` 未混淆：类名、方法名、字段名均为源码名。LC2H 编译目标里 `mcjty.lostcities.*` 本身不需要 remap，因此 `@Inject/@Redirect/@Overwrite` 注解里写的就是上游真名与真描述符（`mixins.lc2h.refmap.json` 只映射原版 `m_xxx_` 成员，共 22 个类，全部是 Minecraft/Forge 目标）。注解值由 `javap -v` 的 `RuntimeVisibleAnnotations` 段逐条打印得到。
2. "LC2H 未触及 X" 类结论是**否定性证明**：对 jar 中每个非 `org/admany/lc2h/neoforge` 类做常量池 UTF-8 字节搜索。被搜索且**零命中**的字符串：`HierarchicalStreetPlanner`、`getStreetInfo`、`getRoadType`、`getStreetPlanner`、`StreetPart`、`getStreetParts`、`hasStreetPartConnection`、`StreetSettings`、`generatePart`、`MultiBuildingStreetConflict`、`roadBlocks`。

**未确定项**（不猜）：文中所有"未验证"均已显式标注，见 §6。

---

## 1. 清单：LC2H 4.2.4-LTS 命中 `mcjty.lostcities.*` 的全部 mixin / config 条目

### 1.1 mixin 配置文件（从 jar 内原文提取）

Forge 1.20.1 实际加载的是 `mixins.lc2h.json`（`mods.toml` 的 `[[mixins]] config`）。原文关键段：

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "org.admany.lc2h.mixin",
  "plugin": "org.admany.lc2h.mixin.Lc2hMixinConfigPlugin",
  "compatibilityLevel": "JAVA_13",
  "mixins": [
    ...
    "lostcities.building.MixinBuildingInfo",
    "lostcities.building.MixinNativeBuildingInfoCharacteristicsCache",
    ...
    "lostcities.dimension.MixinDefaultDimensionInfoThreadLocal",
    "lostcities.highway.MixinHighwayThreadSafety",
    "lostcities.highway.MixinIntercityHighwayBoundedCache",
    "lostcities.highway.MixinIntercityHighwayHeightmapPrefetch",
    "lostcities.highway.MixinDefaultDimensionInfoHighwayHeightGate",
    "lostcities.highway.MixinApproximateCityPotentialCache",
    "lostcities.highway.MixinHighwayNullGuard",
    "lostcities.highway.MixinHighwaySupportDepth",
    ...
  ],
  "client": [ "lostcities.gui.MixinGuiLCConfigPreviewBudget", ... ],
  "injectors": { "defaultRequire": 1 },
  "refmap": "mixins.lc2h.refmap.json"
}
```

计：`mixins.lc2h.json` 中 `lostcities.*` / `accessor.lostcities.*` 条目 **48** 个（`mixins` 47 + 客户端 1，客户端另有 `forge.client.MixinForgeHooksClient` 不计）。另有 `mixins.lc2h.neoforge.json`（package `org.admany.lc2h.neoforge.mixin`，47 条）——Forge 端不加载，本报告只在需要时提及。

### 1.2 运行时门控（`org.admany.lc2h.mixin.Lc2hMixinConfigPlugin`）

`javap -p -c` 反编译 `shouldApplyMixin(String, String)` 的逐条逻辑（`ldc` 常量原文）：

```java
if (NEOFORGE) return false;                                     // NEOFORGE = classPresent("net.neoforged.fml.loading.FMLLoader")
switch (mixinClass) {
  case "org.admany.lc2h.mixin.minecraft.worldgen.MixinWorldGenRegionPreCaptureTrace",
       "org.admany.lc2h.mixin.minecraft.worldgen.MixinPlacedFeaturePreCaptureTrace":
      return Boolean.parseBoolean(System.getProperty("lc2h.precaptureTrace.enabled", "false"));
  case "org.admany.lc2h.mixin.lostcities.building.MixinBuildingInfo":
      return Boolean.parseBoolean(System.getProperty("lc2h.concurrentBuildingInfo", "false"));
  case "org.admany.lc2h.mixin.lostcities.building.MixinNativeBuildingInfoCharacteristicsCache":
      return !Boolean.parseBoolean(System.getProperty("lc2h.concurrentBuildingInfo", "false"));
  default:
      if (!Lc2hRuntimeModes.baselineMode()) return true;
      return ALWAYS_ALLOWED.contains(mixinClass);               // { MixinConfig, MixinDefaultDimensionInfoThreadLocal, ServerChunkCacheInvoker }
}
```

`onLoad` 在 baseline 模式下打印：`"[LC2H] Baseline mode active. LC2H gameplay mixins are disabled for A/B world parity export, except profile-resolution support mixins."`。
`Lc2hRuntimeModes` 的系统属性（`<clinit>`）：`lc2h.baselineMode`（默认 false）、`lc2h.worldparity.auto`（false）、`lc2h.parity.auto`（false）。

**默认配置下的实际生效集**：除 `MixinBuildingInfo`（需 `lc2h.concurrentBuildingInfo=true`）与两个 PreCaptureTrace mixin（默认关）外，§1.1 中其余 lostcities 条目**全部生效**，其中 `MixinNativeBuildingInfoCharacteristicsCache` 默认生效。

### 1.3 A 组 — 建筑特征（characteristics）与缓存

| # | Mixin 类 | `@Mixin` 目标 | 注入 |
| --- | --- | --- | --- |
| 1 | `org.admany.lc2h.mixin.lostcities.building.MixinNativeBuildingInfoCharacteristicsCache`（默认生效） | `@Mixin(value=[class Lmcjty/lostcities/worldgen/lost/BuildingInfo;] remap=false)` | `@Overwrite`：`getChunkCharacteristics(ChunkCoord,IDimensionInfo)` 描述符 `(Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/IDimensionInfo;)Lmcjty/lostcities/api/LostChunkCharacteristics;`；`getCityLevel(ChunkCoord,IDimensionInfo)` `(...)I`；`getBuildingInfo(ChunkCoord,IDimensionInfo)` `(...)Lmcjty/lostcities/worldgen/lost/BuildingInfo;`。`@Shadow`：`getChunkCharacteristicsLocked(ChunkCoord,IDimensionInfo)`、`getCityLevelLocked(...)`、`getDimensionLock(ResourceKey)`；`@Invoker("<init>")` |
| 2 | `org.admany.lc2h.mixin.lostcities.building.MixinBuildingInfo`（仅 `-Dlc2h.concurrentBuildingInfo=true`） | 同上 `BuildingInfo` | `@Overwrite` ×8：`getChunkCharacteristics`、`getChunkCharacteristicsGui`、`getBuildingInfo`、`getCityLevel`、`cleanCache()V`、`getMaxcellars(EffectiveCitySettings)I`、`getMinfloors(EffectiveCitySettings)I`、`getMaxfloors(EffectiveCitySettings)I`；`@Redirect` ×7（`<init>` 内 `cellars/floors` PUTFIELD、`ILostCityBuilding.getMinCellars()`、`BuildingInfo.getDimensionLock`、`WorldGenLevel.getBiome`、`LevelReader.getBiome`、`Building.getRandomPart`）；`@Inject` ×4（`isCityRaw` HEAD/RETURN、`hasHighway` HEAD/RETURN，前两个 cancellable）；`@Invoker("<init>")`；`@Shadow` 含 `getChunkCharacteristicsLocked`、`checkBuildingPossibility(ChunkCoord,IDimensionInfo,LostCityProfile,MultiPos,int,PlannedRoadType,Random)` |
| 3 | `...building.MixinBuildingInfoSafeGetOrThrow` | 同上 `BuildingInfo` | `@Redirect` `<init>` 内 `RegistryAssetRegistry.getOrThrow(CommonLevelAccessor,String)` |
| 4 | `...building.MixinBuildingPartSliceCompat` | `@Mixin(value=[class Lmcjty/lostcities/worldgen/lost/cityassets/BuildingPart;] remap=false)` | `@Inject` HEAD cancellable `getPaletteChar(III)`、`getC(III)`、`get(BuildingInfo,int,int,int)`；`@Redirect` `String.charAt(I)C` ×2 |
| 5 | `...building.MixinBuildingRandomPartFallback` | `@Mixin(value=[class Lmcjty/lostcities/worldgen/lost/cityassets/Building;] remap=false)` | `@Inject` RETURN cancellable `getRandomPart(Random,ConditionContext)` |
| 6 | `...accessor.lostcities.BuildingInfoAccessor` | `BuildingInfo` | `@Accessor`：`coord`、`profile`、`buildingType`、`floors`(get/set)、`cellars`、`cityLevel` |

**默认路径（#1）的关键语义（`javap -c` 逐指令）**：`getChunkCharacteristics(coord, provider)`
`scope = BuildingInfoCacheRegistry.scope(provider)` → `scope.nativeCharacteristics.get(coord)` 命中即返回；未命中则做 per-coord flight 去重后调用 `lc2h$nativeResolve(coord, provider)`；`lc2h$nativeResolve` 在 `getChunkCharacteristicsLocked(coord, provider)` 外包一层 `scope.buildingLocks` 的 `ReentrantLock`（`tryLock` 失败也直接调用）。即**默认路径最终仍执行上游 `getChunkCharacteristicsLocked`**（V2 的 `getStreetInfo/getRoadType` 就在其中被调用）。

`BuildingInfoCacheRegistry.scope(IDimensionInfo)` → `LostCitiesGuiPreviewGuard.cacheScope(provider)`；非 GUI 预览时 **cacheScope 就是 `provider` 实例本身**（GUI 预览时为 `PreviewScope(profile, seed, type, PREVIEW_REVISION)`）。缓存字段（`BuildingInfoCacheScope`）：`cityInfo`、`nativeCharacteristics`、`nativeCharacteristicFlights`、`characteristicFlights`、`buildingInfo`、`cityLevel`、`cityRaw`、`highway`、`multiHeightStats`、`multiBoundary`、`buildingLocks`，键均为 `ChunkCoord`。

### 1.4 B 组 — 多区块建筑选址（MultiChunk）

| # | Mixin 类 | `@Mixin` 目标 | 注入 |
| --- | --- | --- | --- |
| 7 | `...worldgen.MixinMultiChunk` | `@Mixin(value=[class Lmcjty/lostcities/worldgen/lost/MultiChunk;] remap=false)` | `@Inject` HEAD cancellable `getOrCreate`（`lc2h$asyncGetOrCreate`）、RETURN `getOrCreate`；`@Inject` HEAD cancellable **`calculateBuildings`**（`lc2h$beginPlanningCache`）、RETURN `calculateBuildings`；`@Inject` HEAD `cleanCache`；`@Redirect` ×6 个处理器（覆盖 7 个注入点，其中一个处理器同时挂 `calculateBuildings` 与 `canPlaceBuilding`）：`calculateBuildings` 内 `City.getCityStyle(ChunkCoord,IDimensionInfo,LostCityProfile)`、`RegistryAssetRegistry.get(CommonLevelAccessor,String)`；`canPlaceBuilding` 内 `City.getCityStyle`、`City.isChunkOccupied(IDimensionInfo,ChunkCoord)`、`Railway.getRailChunkType(ChunkCoord,IDimensionInfo,LostCityProfile)`、`BuildingInfo.isCityRaw(...)`、`BuildingInfo.hasHighway(...)`（全部 `require=0 expect=0`） |
| 8 | `...worldgen.MixinMultiChunkTimings` | 同上 `MultiChunk` | `@Redirect` `calculateBuildings` 内 `Logger.info(String,Object[])`、`Logger.debug(String,Object[])` |
| 9 | `...accessor.lostcities.MultiChunkAccessor` | `MultiChunk` | `@Accessor`：`areasize`、`topleft`、`mc`、`MULTICHUNKS` |
| 10 | `...accessor.lostcities.MultiChunkInvoker` | `MultiChunk` | `@Invoker`：`calculateBuildings(IDimensionInfo)`、`placeBuilding(MultiBuilding,int,int)` |
| 11 | `...city.MixinCity` | `@Mixin(value=[class Lmcjty/lostcities/worldgen/lost/City;] remap=false)` | `@Overwrite` ×6：`getCityStyle(ChunkCoord,IDimensionInfo,LostCityProfile)`、`isCityCenter(ChunkCoord,IDimensionInfo)`、`getCityRadius(...)F`、`getCityFactor(...)F`、`getCityRarityMap(ResourceKey,long,double,double,double)`、`getPredefinedCity(CommonLevelAccessor,ChunkCoord)`；`@Inject` HEAD：`isChunkOccupied`、`getPredefinedBuilding`、`getPredefinedStreet`、`getPredefinedBuildingAtTopLeft`、`cleanCache`；`@Invoker`：`calculateOccupied(IDimensionInfo)`、`calculateMap(CommonLevelAccessor)` |
| 12 | `...railway.MixinRailway` | `@Mixin(value=[class Lmcjty/lostcities/worldgen/lost/Railway;] remap=false)` | `@Overwrite` ×3：`getRailChunkType(ChunkCoord,IDimensionInfo,LostCityProfile)`、`cleanCache()V`、`removeRailChunkType(ChunkCoord)`；`@Redirect` `getRailChunkTypeInternal` 内 `BuildingInfo.isCityRaw(...)` |

**`calculateBuildings` 的 HEAD 行为（`javap -c` 原文）**：

```java
MultiChunk self = (MultiChunk) this;
if (FastMultiChunkPlanner.tryPlan(self, provider)) {   // tryPlan 内部先判 ENABLED
    cir.setReturnValue(self); cir.cancel(); return;     // ← 只有这里才跳过 canPlaceBuilding
}
MultiChunkPlanningCache.begin();                        // 线程内查询记忆化，不产出 MultiChunk
```

**快速多区块路径（不在 mixin 包内，但由 #7 触发）**：`org.admany.lc2h.worldgen.lostcities.FastMultiChunkPlanner`
`<clinit>`：`ENABLED = Boolean.parseBoolean(System.getProperty("lc2h.fast_multichunk.enabled", "false"))`（**默认 false**）。
`tryPlan(MultiChunk, IDimensionInfo)Z` 开头即 `if (!ENABLED || isBypassed() || ...) { FALLBACK_DISABLED.increment(); return false; }`。
该类常量池**不含** `PlannedRoadType`/`getRoadType`/`getStreetPlanner`/`MULTI_BUILDING_STREET_CONFLICT`/`roadBlocks`；其拒绝计数器只有 `REJECT_GRID / REJECT_OCCUPIED / REJECT_RAIL / REJECT_HIGHWAY / REJECT_STYLE / REJECT_INVALID`，上游调用只有 `City.isChunkOccupied`、`BuildingInfo.isCityRaw/hasHighway/getCityLevel`、`Railway.getRailChunkType`、`City.getCityStyle`、`MultiChunk.<init>`、`MultiBuilding.getDimX/getDimZ`。
→ **快速路径自己构造 `MultiChunk` 并填格，完全不走 `MultiChunk.canPlaceBuilding`，也完全不看任何路类。**

`org.admany.lc2h.worldgen.async.planner.AsyncMultiChunkPlanner`（`ASYNC_PLANNER_ENABLED = System.getProperty("lc2h.concurrentBuildingInfo","false")`）仍通过 `MultiChunkInvoker.lc2h$calculateBuildings(provider)` 构造（即仍会经过 #7 的 HEAD 钩子）；只有 `FastMultiChunkPlanner.isEnabled()` 为 true 时该钩子才会改成快速选址。

### 1.5 C 组 — 维度信息与街道模式策略

| # | Mixin 类 | `@Mixin` 目标 | 注入 |
| --- | --- | --- | --- |
| 13 | `...dimension.MixinDefaultDimensionInfoThreadLocal` | `@Mixin(value=[class Lmcjty/lostcities/worldgen/DefaultDimensionInfo;] remap=false)` | `@Overwrite` ×13：`setWorld(WorldGenLevel)`、`getWorld()`、`getSeed()J`、`getType()`、`getRandom()`、`getProfile()`、`getOutsideProfile()`、`getWorldStyle()`、**`getStreetGenerationMode()`**、`getFeature()`、`getHeightmap(int,int)`、`getHeightmap(ChunkCoord)`、`dimension()`。**没有** `getStreetPlanner()`、**没有** `getHighwayGenerationMode()`、**没有** `getHighwayPlanner()`、**没有** `<init>` |
| 14 | `...config.MixinConfig` | `@Mixin(value=[class Lmcjty/lostcities/setup/Config;] remap=false)` | `@Inject` HEAD/RETURN cancellable `getProfileForDimension(ResourceKey)`；`@Redirect` 其内 `LostCityProfile.GENERATE_NETHER` 字段、`Map.put`(ordinal=1)、`Map.get`(ordinal=2) |
| 15 | `...biome.MixinBiomeInfo` | `@Mixin(value=[class Lmcjty/lostcities/worldgen/lost/BiomeInfo;] remap=false)` | `@Inject` HEAD cancellable `getBiomeInfo(IDimensionInfo,ChunkCoord)`；HEAD `cleanCache` |
| 16 | `...accessor.lostcities.BiomeInfoAccessor` | `BiomeInfo` | `@Accessor` `mainBiome`(set) |
| 17 | `...accessor.lostcities.WorldStyleAccessor` | `WorldStyle` | `@Accessor` `cityStyleSelector` |

`getStreetGenerationMode()` 的字节码（`javap -c`）：

```java
LostCityProfile p = getProfile();                                   // ← 已被 #13 覆写为 LostCityProfileOverrideManager.resolveProfile(world, profile)
String name = (p == null) ? null : p.getName();
return LostCitiesStreetModePolicy.resolve(this.streetGenerationMode, name);   // streetGenerationMode 是 @Shadow 的上游 final 字段
```

`org.admany.lc2h.worldgen.lostcities.LostCitiesStreetModePolicy`（`javap -c`）：

```java
static final StreetGenerationMode DEFAULT_MODE = StreetGenerationMode.HIERARCHICAL_GRID_V1;
static volatile StreetGenerationMode configuredMode = DEFAULT_MODE;  // setConfiguredMode(...) 由 ConfigManager.apply() 调用

static StreetGenerationMode resolve(StreetGenerationMode upstream) { return configuredMode != null ? configuredMode : (upstream != null ? upstream : DEFAULT_MODE); }
static StreetGenerationMode resolve(StreetGenerationMode upstream, String profileName) {
    if (requiresLegacyMode(profileName)) return StreetGenerationMode.LEGACY;   // profileName.equalsIgnoreCase("aaaaaaaaz15Flat") || profileName.regionMatches(true,0,"Azzz",0,4)
    return resolve(upstream);
}
```

→ **LC2H 存在时，`IDimensionInfo.getStreetGenerationMode()` 返回的是 LC2H 的全局配置值（默认 `HIERARCHICAL_GRID_V1`），不是 TLC 的持久值**；ChaosZPack 系 profile 名一律强制 `LEGACY`。`configuredMode` 是 `volatile` 且运行时可改（配置说明原文：`"Lost Cities street planner. The 7.5.x grid is the default. ChaosZPack profiles automatically use LEGACY. Applies to chunks planned after saving without a restart."`）。

`getProfile()` 被覆写为 `LostCityProfileOverrideManager.resolveProfile(world, profile)`（LC2H 命令可运行时切换 profile）；`getFeature()` 会在 profile token 变化时**新建** `LostCityTerrainFeature` 并 `setupStates(profile)`。

### 1.6 D 组 — 城际高速

| # | Mixin 类 | `@Mixin` 目标 | 注入 |
| --- | --- | --- | --- |
| 18 | `...highway.MixinHighwayThreadSafety` | `@Mixin(value=[class Lmcjty/lostcities/worldgen/lost/Highway;] remap=false)` | `@Inject` TAIL `<clinit>`（把 `X_HIGHWAY_LEVEL_CACHE`/`Z_HIGHWAY_LEVEL_CACHE` 换成并发 map）；`@Inject` HEAD cancellable `getHighwayLevel(...)`（快路径命中）；`@Redirect` 其中 `Map.put`；HEAD `cleanCache` |
| 19 | `...highway.MixinHighwayNullGuard` | 同上 `Highway` | `@Redirect` `getHighwayLevel` 内 `Map.get(Object)` |
| 20 | `...highway.MixinHighwaySupportDepth` | `@Mixin(value=[class Lmcjty/lostcities/worldgen/gen/Highways;] remap=false)` | `@ModifyConstant` `generateHighwayPart` 常量 `40` |
| 21 | `...highway.MixinIntercityHighwayBoundedCache` | `@Pseudo @Mixin(targets=["mcjty.lostcities.worldgen.highway.IntercityHighwayPlanner$BoundedCache"] remap=false)` | `@Overwrite` `computeIfAbsent(K,Function)`；`@Shadow` `values`；`@Unique` `lc2h$inFlightKeys` |
| 22 | `...highway.MixinIntercityHighwayHeightmapPrefetch` | `@Mixin(value=[class Lmcjty/lostcities/worldgen/highway/IntercityHighwayPlanner;] remap=false)` | `@Inject` HEAD/RETURN `calculateHub(HubKey)`、`calculateOwnedRoutes(HubKey)` |
| 23 | `...highway.MixinDefaultDimensionInfoHighwayHeightGate` | 同上 `DefaultDimensionInfo` | `@Redirect` `applyHighwayCityConstraints` 内 `DefaultDimensionInfo.getHeightmap(ChunkCoord)`（冷缓存时用 profile `GROUNDLEVEL` stub，避免冷高度图构建） |
| 24 | `...highway.MixinApproximateCityPotentialCache` | `@Mixin(value=[class Lmcjty/lostcities/worldgen/highway/ApproximateCityPotential;] remap=false)` | `@Overwrite` `getPotential(int,int)F`（`@Shadow` 全部字段；`lc2h.highway.potentialCacheSize`） |

补充（非 mixin，LC2H 自有的高速索引）：`org.admany.lc2h.worldgen.terrain.IntercityHighwayIndex`（按 `IDimensionInfo` 分片，缓存 `HighwayHub`/`HighwayRoute`/`HighwaySegment`），被 `LC2H`、`ChunkRoleProbe`、`CityShiftField`、`MountainCityReservationPlanner`、`FastMultiChunkPlanner$LazyPlan` 使用。

### 1.7 E 组 — 其余 24 条 lostcities 条目（与路网无交集，列出以求完整）

| Mixin | 目标 | 注入（方法名） |
| --- | --- | --- |
| `worldgen.feature.MixinLostCityFeature` | `LostCityFeature` | `@Inject` `<init>` RETURN、`cleanUp` HEAD、`place(FeaturePlaceContext)` HEAD/RETURN；`@Redirect` `IDimensionInfo.getFeature()` ×2、`LostCityTerrainFeature.generate(WorldGenRegion,ChunkAccess)` ×2 |
| `worldgen.feature.MixinLostCityTerrainFeature` | `LostCityTerrainFeature` | `@Inject` `correctTerrainShape` HEAD(cancel)、`generate` HEAD/RETURN、`breakBlocksForDamageNew` HEAD/RETURN；`@Redirect` `getHeightmap(ChunkCoord,WorldGenLevel)`、`DamageArea.hasExplosions(I)`、`DamageArea.getDamage(III)`、`DamageArea.damageBlock(...)` |
| `worldgen.feature.MixinLostCitySphereFeature` | `LostCitySphereFeature` | `@Inject` `place` HEAD |
| `worldgen.MixinNoiseChunkOpt` | `NoiseChunkOpt` | `@Inject` `optimizeNoise` HEAD、`<init>` RETURN；`@Redirect` `Map.computeIfAbsent` |
| `worldgen.MixinChunkFixerVineCleanup` | `ChunkFixer` | `@Redirect` `removeUnsupportedVines`、`removeUnsupportedBoundaryVines` |
| `util.MixinToolsGrassFix` | `varia.Tools` | `@Inject` `stringToState` HEAD(cancel) |
| `cache.MixinCitySphereCacheBudget` | `CitySphere` | `@Inject` `<clinit>` TAIL、`cleanCache` HEAD；`@Redirect` `Map.get`/`Map.put` |
| `cache.MixinLostCityTerrainFeatureHeightmapCache` | `LostCityTerrainFeature` | `@Overwrite` `getHeightmap(ChunkCoord,WorldGenLevel)` |
| `terrain.MixinLostCityTerrainFeatureTodoTreeSafety` | `LostCityTerrainFeature` | `@Inject` `lambda$handleTodo$10` HEAD(cancel) |
| `terrain.MixinLostCityTerrainFeaturePostTodoSafety` | `LostCityTerrainFeature` | `@Inject` `lambda$updateNeeded$22` HEAD(cancel) |
| `terrain.MixinForgeEventHandlersTodoPrimeGate` | `ForgeEventHandlers` | `@Redirect` `onWorldTick` 内 `GlobalTodo.executeAndClearTodo(ServerLevel)` |
| `terrain.MixinLostCityTerrainFeatureLootSafety` | `LostCityTerrainFeature` | `@Inject` `generateLoot` HEAD(cancel)；`@Redirect` `lambda$handleLoot$9` 内 `IDimensionInfo.getWorld()`、`generateLoot` 内 `createLoot(...)` |
| `debris.MixinLostCityDebrisFix` | `LostCityTerrainFeature` | `@Overwrite` `generateDebrisFromChunk(BuildingInfo,BuildingInfo,BiFunction)` |
| `debris.MixinLostCityRubble` | `LostCityTerrainFeature` | `@Redirect` `generateRuins` 内 `ChunkDriver.add(BlockState)` |
| `damage.MixinDamageAreaDisable` | `DamageArea` | `@Inject` HEAD(cancel) ×7：`hasExplosions()`、`hasExplosions(I)`、`isCompletelyDestroyed(I)`、`getDamage(III)`、`getDamageFactor()`、`getExplosions()`、`damageBlock(...)` |
| `city.MixinCityEdgeBlend` | `LostCityTerrainFeature` | `@Inject` `getMinHeightAt` HEAD(cancel) |
| `city.MixinCityEdgeBlendSurfaceFix` | `LostCityTerrainFeature` | `@Inject` TAIL `generateBorder` |
| `safety.MixinChunkDriverNullGuard` | `ChunkDriver` | `@Inject` `correct` HEAD(cancel)、`actuallyGenerate` RETURN |
| `safety.MixinPaletteUnknownBlockGuard` | `Palette` | `@Redirect` `parsePaletteArray` 内 `Tools.stringToState(String)` |
| `registry.MixinRegistryAssetRegistryWorldStyleFallback` | `RegistryAssetRegistry` | `@Inject` HEAD(cancel) `get(CommonLevelAccessor,ResourceLocation)` |
| `spawn.MixinForgeEventHandlersSpawnPartsFix` | `ForgeEventHandlers` | `@Overwrite` `findSafeSpawnPointAtColumn(...)` |
| `spawn.MixinForgeEventHandlersBedFix` | `ForgeEventHandlers` | `@Overwrite` `findLocation(BlockPos,ServerLevel)`、`onPlayerSleepInBedEvent(PlayerSleepInBedEvent)` |
| `accessor.lostcities.ForgeEventHandlersAccessor` | `ForgeEventHandlers` | `@Accessor` `spawnPositions` |
| `gui.MixinGuiLCConfigPreviewBudget`（client） | `gui.GuiLCConfig` | `@Inject` `m_88315_` HEAD/RETURN、`refreshPreview` HEAD；`@Redirect` `refreshPreview` 内 `BuildingInfo.cleanCache()`、`City.cleanCache()` |

### 1.8 注册表里没有的类（死代码，明确记录）

以下 mixin 类存在于 jar 但**不在任何 mixin 配置中**，因此永不应用：`lostcities.palette.MixinCompiledPaletteThreadSafeRandoms`、`lostcities.terrain.MixinLostCityTerrainFeatureThreadSafeRandoms`、`lostcities.worldgen.feature.MixinLostCityTerrainFeatureTallBlocks`、`minecraft.worldgen.MixinChunkGeneratorSkipCarvers`。

---

## 2. 冲突分析：V2 的结果会被谁观察、缓存或覆盖

### 2.1 判定总表

"V2 结果" = Citylines 在 `HierarchicalStreetPlanner#getStreetInfo/getRoadType` HEAD 委派后返回的 `PlannedStreetInfo`/`PlannedRoadType`，以及 V2 在 `BuildingInfo`/`MultiChunk`/街道渲染各注入点产生的副作用。

| 上游接触点 | LC2H 的干预 | V2 结果的去向 | 判定 |
| --- | --- | --- | --- |
| `HierarchicalStreetPlanner.getStreetInfo/getRoadType` | **零引用**（常量池否定性证明） | 原样流经 | ✅ 未被观察/未被缓存/未被绕过 |
| `BuildingInfo.getChunkCharacteristicsLocked`（写 `rawPlannedRoadType`、`plannedRoadType`） | 默认路径：不被覆写，只被 `@Shadow` 调用（在 `lc2h$nativeResolve` 里加锁） | V2 的两个字段原样写入 | ✅ 原样 |
| `BuildingInfo.getChunkCharacteristics(coord,provider)` | 默认：`@Overwrite` 包装（查 `scope.nativeCharacteristics` → 未命中则调上游 locked 方法并缓存） | V2 结果**被缓存**：键 = `(IDimensionInfo 实例, ChunkCoord)` | ⚠️ 值正确，但**在缓存生命周期内被冻结**：`BuildingInfo.cleanCache()` 清不掉这一层 |
| 同上，`-Dlc2h.concurrentBuildingInfo=true` | `@Overwrite` 全量重实现；`couldHaveBuilding` 用 `rawPlannedRoadType` 计算 | V2 的 raw/effective 区分被抹平：`plannedRoadType` 在 LC2H 常量池中**只作为 `rawPlannedRoadType` 的子串出现，独立标识符零命中**；字节码 offset 515→517→522：`getfield rawPlannedRoadType` → `checkBuildingPossibility(..., PlannedRoadType, ...)` | ❌ **绕过/语义破坏**（详见 §2.2.2） |
| `BuildingInfo.getBuildingInfo` / `getCityLevel`（两种路径） | `@Overwrite` + 自有键为 `ChunkCoord` 的缓存 | V2 输入原样流经；同样不受 `cleanCache()` 清理 | ✅ 值正确 ⚠️ 缓存不可清 |
| `MultiChunk.canPlaceBuilding`（内含 `getStreetPlanner().getRoadType(...)` + `MULTI_BUILDING_STREET_CONFLICT.roadBlocks`） | 默认：`canPlaceBuilding` 内只 `@Redirect` 了 5 个**别的**调用（`City.getCityStyle`/`isChunkOccupied`、`Railway.getRailChunkType`、`BuildingInfo.isCityRaw`/`hasHighway`），`require=0`；V2 想插入的路权判定不受影响 | V2 的路权强制**按设计执行** | ✅ 默认下是可靠强制点 |
| `MultiChunk.calculateBuildings` | `@Inject` HEAD → 若 `FastMultiChunkPlanner.tryPlan` 成功则**取消**整个方法 | `lc2h.fast_multichunk.enabled=true` 时 `canPlaceBuilding` **完全不执行** | ❌ **绕过**（仅在该属性为 true 时） |
| `City.getCityFactor/getCityStyle/isCityCenter/getCityRadius/getCityRarityMap/getPredefinedCity` | `@Overwrite`（缓存/并行变体） | V2 规划器是纯函数（不读这些）；其它 V2 输入（`isCityRaw` 等）不受影响。若 V2 未来调用它们，只会拿到 LC2H 的值 | ✅（当前设计）/ ⚠️（若改设计） |
| `DefaultDimensionInfo.getStreetGenerationMode()` | `@Overwrite` → `LostCitiesStreetModePolicy.resolve(...)`，默认强制 `HIERARCHICAL_GRID_V1` | **所有 TLC 侧 `== HIERARCHICAL_GRID_V1` 分支的判定源**；V2 若依赖"模式必须仍是 HGV1"这一前提，则前提由 LC2H 的配置决定 | ❌ **危险**（详见 §2.2.1） |
| `DefaultDimensionInfo.getStreetPlanner()` | **未被覆写**（也不在 `@Shadow` 列表） | V2 的 HEAD 委派正常工作；实例级回链（`@Inject` TAIL 构造器）安全 | ✅ |
| `DefaultDimensionInfo.getProfile()` | `@Overwrite` → `LostCityProfileOverrideManager`（运行时可切换） | V2 若每次查询都用 `provider.getProfile()` 重新推导设置，会与规划器构造时的快照不一致 | ⚠️ 语义漂移风险 |
| `DefaultDimensionInfo.getHeightmap(ChunkCoord/int,int)`、`LostCityTerrainFeature.getHeightmap` | `@Overwrite`（LC2H 缓存；`getFeature()` 可因 profile 变化而换实例） | V2 的桥/水判定读高度时走 LC2H 缓存 | ✅ 值等价 ⚠️ 不得依赖上游内部缓存/线程假设 |
| `LostCityTerrainFeature` 街道渲染路径（`hasStreetPartConnection`、`getStreetParts`、`generatePart`、`generateStreet*`） | **零引用**（常量池否定性证明） | V2 自选街道部件不受干扰 | ✅ |
| `Highway.*` / `IntercityHighwayPlanner` / `ApproximateCityPotential` | 多个 `@Overwrite`/缓存/预取 | V2 不触碰高速，二者无交集；唯一共享输入是 `getHeightmap` 与 `BuildingInfo.getCityLevel` | ✅ 无冲突 |

### 2.2 三个必须处理的冲突

#### 2.2.1 模式覆写：LC2H 会把"持久模式"换成它自己的全局配置

TLC 的三个消费点全部以 `provider.getStreetGenerationMode() == StreetGenerationMode.HIERARCHICAL_GRID_V1` 为条件调用规划器：

- `worldgen/lost/BuildingInfo.java:407`（`getChunkCharacteristicsLocked` 内取 `rawStreet`）
- `worldgen/lost/MultiChunk.java:188`（`canPlaceBuilding` 内取 `roadType`）
- `worldgen/street/HierarchicalBridgePlanner.java:25`（桥规划入口）
- 另有 `worldgen/gen/Bridges.java:41`（桥面高度 `GROUNDLEVEL` vs `GROUNDLEVEL+1`）与 `BuildingInfo` 内 6 处同模式分支

LC2H 默认把该 getter 变成"配置值优先"：`configuredMode` 的初值就是 `HIERARCHICAL_GRID_V1`，且 `resolve(upstream, profileName)` 只在 ChaosZPack 名下才退回 `LEGACY`。
后果分两类：

- **默认/显式设为 `HIERARCHICAL_GRID_V1`**：分支照常进入，V2 的 HEAD 委派生效 —— 这是 V2 在 LC2H 下唯一能工作的组合。
- **用户在 `config/lc2h/lc2h_config.json` 里设 `LEGACY`，或使用 `Azzz*`/`aaaaaaaaz15Flat` profile**：所有消费点走 LEGACY 分支，**V2 的 `getStreetInfo/getRoadType` 根本不会被调用**，而 Citylines 自己的维度开关仍然是"V2 已启用" → 生成结果既不是 V1 也不是 V2，属于静默半成品。方案 §6 要求的"旧 LC2H 在 V2 维度必须 fail-fast 拒载"正是指这一条。

注意：由于本项目不可 fork TLC、不可新增枚举常量（`docs/WORKFLOW.md` §0），**不能靠"新增 `CONNECTED_DISTRICT_V1` 常量并让 LC2H 配置它"来解决**：`LostCitiesStreetModePolicy.parse` 用 `StreetGenerationMode.valueOf(...)`，若枚举里没有该常量会抛 `IllegalArgumentException` 并静默退回 `DEFAULT_MODE`（=HGV1）。因此 V2 只能在"运行时可见模式 == HGV1"时工作。

#### 2.2.2 可选并发特征路径（`lc2h.concurrentBuildingInfo=true`）

已从字节码逐条确认：

- 类常量池中**独立的 `plannedRoadType` 标识符零命中**，只有 `rawPlannedRoadType`（`plannedRoadType` 仅作为其子串出现）。
- `MixinBuildingInfo#lc2h$computeChunkCharacteristics` 在 offset 501–522 处：`getfield LostChunkCharacteristics.rawPlannedRoadType : Lmcjty/lostcities/worldgen/street/PlannedRoadType;` → `invokestatic checkBuildingPossibility(ChunkCoord, IDimensionInfo, LostCityProfile, MultiPos, int, PlannedRoadType, Random)Z` → 写回 `LostChunkCharacteristics.couldHaveBuilding`。
- 上游 `BuildingInfo:410-413` 传的是 `characteristics.plannedRoadType`（由 `getEffectivePlannedRoadType(...)` 精算，`EffectiveStreetResolver.resolve(raw, isCity, hasConnectedCityNeighbor, overridden)`）。

即：**LC2H 的并发路径用"原始保留类"代替"最终有效类"来判定能否放建筑**。V2 的设计恰恰要求 `rawPlannedRoadType`（保留、可为后续预留而置为路）与 `plannedRoadType`（最终有效）分离；在该路径下，被 V2 预留但最终不铺路的格子会被当作道路而拒绝建筑（或反之产生与 native 路径不同的接受集），城市内容与 native/V1 都不一致。同一路径还 `@Overwrite` 了 `getChunkCharacteristics`、`getChunkCharacteristicsGui`、`getBuildingInfo`、`getCityLevel`、`cleanCache`。

#### 2.2.3 快速多区块选址（`lc2h.fast_multichunk.enabled=true`）

`FastMultiChunkPlanner` 完全自行构造 `MultiChunk` 并填 `buildingGrid`，只判定 GRID/OCCUPIED/RAIL/HIGHWAY/STYLE/INVALID，**不读任何路类，也不读 `MULTI_BUILDING_STREET_CONFLICT`**。此时 `MixinMultiChunk.lc2h$beginPlanningCache` 在 `calculateBuildings` HEAD 取消上游方法，`canPlaceBuilding`（以及其中 V2 的整 footprint 路权判定）**一行都不执行**。

对既有文档的更正：`docs/integration/tlc-integration-points.md` §10 表格第一行写"`MixinMultiChunk` 会在 HEAD 取消上游选址并改由 `FastMultiChunkPlanner.tryPlan` 规划，`canPlaceBuilding` 因此完全不执行"。**机制正确，但默认不成立**：该取消只发生在 `lc2h.fast_multichunk.enabled=true`（默认 false）时；且此调用点不受 `lc2h.concurrentBuildingInfo` 门控（`MixinMultiChunk` 默认应用，`tryPlan` 自己判 `ENABLED`）。默认配置下 `canPlaceBuilding` 会执行，仍是 V2 的可靠强制点。

### 2.3 `@Overwrite` 清单（V2 侧任何"改这些方法体"的方案都会被 LC2H 覆盖）

| 被覆写的方法 | LC2H 类 | 生效条件 |
| --- | --- | --- |
| `BuildingInfo.getChunkCharacteristics` / `getCityLevel` / `getBuildingInfo` | `MixinNativeBuildingInfoCharacteristicsCache` | 默认 |
| 同上 + `getChunkCharacteristicsGui` / `cleanCache` / `getMaxcellars` / `getMinfloors` / `getMaxfloors` | `MixinBuildingInfo` | concurrent |
| `DefaultDimensionInfo`：`setWorld`、`getWorld`、`getSeed`、`getType`、`getRandom`、`getProfile`、`getOutsideProfile`、`getWorldStyle`、**`getStreetGenerationMode`**、`getFeature`、`getHeightmap(int,int)`、`getHeightmap(ChunkCoord)`、`dimension` | `MixinDefaultDimensionInfoThreadLocal` | 默认（连 baseline 模式也生效，见 §1.2 ALWAYS_ALLOWED） |
| `City.getCityStyle` / `isCityCenter` / `getCityRadius` / `getCityFactor` / `getCityRarityMap` / `getPredefinedCity` | `MixinCity` | 默认 |
| `Railway.getRailChunkType` / `cleanCache` / `removeRailChunkType` | `MixinRailway` | 默认 |
| `LostCityTerrainFeature.getHeightmap(ChunkCoord,WorldGenLevel)` / `generateDebrisFromChunk` | `MixinLostCityTerrainFeatureHeightmapCache` / `MixinLostCityDebrisFix` | 默认 |
| `IntercityHighwayPlanner$BoundedCache.computeIfAbsent`；`ApproximateCityPotential.getPotential` | 高速公路组 | 默认 |
| `ForgeEventHandlers.findSafeSpawnPointAtColumn` / `findLocation` / `onPlayerSleepInBedEvent` | spawn 组 | 默认 |
| `MultiChunk` 无 `@Overwrite`；`canPlaceBuilding`、`getOrCreate` 只被 `@Inject`/`@Redirect` | `MixinMultiChunk` | — |

**V2 计划使用的注入点是否落在上表？** 全部不在：`HierarchicalStreetPlanner.getStreetInfo/getRoadType`、`MultiChunk.canPlaceBuilding`、`BuildingInfo.initMultiBuildingSection`、`BuildingInfo.getChunkCharacteristicsLocked`、`LostCityTerrainFeature` 街道渲染方法、`IDimensionInfo` 新增 getter，均既未被 `@Overwrite` 也未被 `@Shadow`-then-use 之外的方式改动。唯一冲突是 `DefaultDimensionInfo.getStreetGenerationMode`（见 §2.2.1）。

### 2.4 加载兼容性核对（LC2H 4.2.4 是照 7.5.4 编译的）

对 `MixinBuildingInfo` / `MixinNativeBuildingInfoCharacteristicsCache` / `MixinCity` / `MixinRailway` / `MixinDefaultDimensionInfoThreadLocal` 的 `@Shadow`/`@Overwrite` 成员逐一在 7.5.5 快照里查声明，全部存在且描述符一致：`getDimensionLock`、`getChunkCharacteristicsLocked(ChunkCoord,IDimensionInfo)`、`getCityLevelLocked`、`initMultiBuildingSection(LostChunkCharacteristics,ChunkCoord,IDimensionInfo,LostCityProfile)`、`getAverageCityLevel`、`getTopLeftCityLevel`、`getTopLeftCityInfo`、`checkBuildingPossibility(...,PlannedRoadType,Random)`、`getCityLevelSpace/Floating/Cavern/Normal`、`getBuildingRandom(IIJ)`、`isCityRaw`、`hasHighway`、`getMaxcellars/getMinfloors/getMaxfloors(EffectiveCitySettings)`、`calculateOccupied`、`calculateMap`、`getCityStyleInt`、`getPredefinedCity`、`getCityRarityMap`、`getCityRadius`、`isCityCenter`、`getCityFactor`、`getRailChunkType`、`findSafeSpawnPointAtColumn`、`findLocation`；`BuildingInfo` 字段 `coord/provider/profile/buildingType/floors/cellars/cityLevel`、`MultiChunk` 字段 `mc/topleft/areasize/MULTICHUNKS`、`Highway` 字段 `X/Z_HIGHWAY_LEVEL_CACHE` 亦均在。
附带发现：7.5.5 里 `X/Z_HIGHWAY_LEVEL_CACHE` **本来就是 `ConcurrentHashMap`**，`MixinHighwayThreadSafety` 的 `<clinit>` 替换在此版本上是冗余但无害。
结论：**从签名匹配角度 LC2H 4.2.4-LTS 能在 7.5.5 上应用**；这只证明"能应用"，不等于"语义逐位一致"（并发/快速路径的语义差异见 §2.2）。

---

## 3. 配置面（可从 jar 确定的全部开关）

### 3.1 JSON 配置文件

`org.admany.lc2h.config.ConfigManager`：路径常量 `"config/lc2h/lc2h_config.json"`，Gson 读写；键即 `ConfigManager$Config` 的字段名；新增键会补齐默认值，解析失败会备份并重建。
`ConfigManager.apply()` 里与街道直接相关的一句（`javap -c` 原文）：

```
getstatic CONFIG.lostCitiesStreetGenerationMode : Ljava/lang/String;
invokestatic LostCitiesStreetModePolicy.normalizeValue:(Ljava/lang/String;)Ljava/lang/String;
putstatic LOSTCITIES_STREET_GENERATION_MODE
...
invokestatic LostCitiesStreetModePolicy.setConfiguredMode:(Ljava/lang/String;)V
```

`Config` 全字段与默认值（`<init>` 字节码）：`enableAsyncDoubleBlockBatcher=true`、`enableAutomaticChunkScans=false`、`rejectStructuresInCityChunks=false`、`cityStructureRejectionBufferChunks=0`、`cityVerticalTerrainClearance=false`、`enableLostCitiesGenerationLock=true`、`enableLostCitiesPartSliceCompat=true`、**`lostCitiesStreetGenerationMode="HIERARCHICAL_GRID_V1"`**、`enableCacheStatsLogging=true`、`enableFloatingVegetationRemoval=true`、`floatingVegetationAdditionalBlocks=["minecraft:glow_lichen"]`、`enableExplosionDebris=false`、`hideExperimentalWarning=true`、`enableDebugLogging=false`、`enableLc2hLogging=false`、`uiAccentColor="3A86FF"`、`uiLocale="en_us"`、`cacheMaxMB=384`、`cacheLostCitiesMaxMB=128`、`cacheCombinedMaxMB=256`、`cacheEnforceCombinedMax=true`、`cacheSplitEqual=false`、`cacheLostCitiesTtlMinutes=10`、`cacheLostCitiesDiskTtlHours=2`、`cityBlendEnabled=true`、`cityBlendWidth=36`、`cityBlendSoftness=1.4`、`cityBlendClearTrees=true`、`cityBlendTreeSeamFix=true`、`cityBlendTreeSeamBuffer=3`、`treeSeamRadiusMultiplier=1.0`、`seamOwnershipEnabled=false`、`seamOwnershipMaxIntentsPerChunk=8192`、`seamOwnershipIntentTtlMs=600000`、`highwaySupportMaxDepth=192`。

与路网/多区块有关的键只有 `lostCitiesStreetGenerationMode` 一项；**多区块快速路径不在此文件里**（见 §3.2）。

### 3.2 系统属性（`-D`，默认值取自各 `<clinit>` 的 `System.getProperty(key, default)` 常量）

**路网/共存关键 5 项**

| 属性 | 默认 | 作用点 | 对 V2 的意义 |
| --- | --- | --- | --- |
| `lc2h.concurrentBuildingInfo` | `false` | `Lc2hMixinConfigPlugin.shouldApplyMixin`（开关 `MixinBuildingInfo` ↔ `MixinNativeBuildingInfoCharacteristicsCache`）；`MixinMultiChunk`/`AsyncMultiChunkPlanner` 的 `LC2H_CONCURRENT_BUILDING_INFO`/`ASYNC_PLANNER_ENABLED` | `true` = 并发特征路径，`raw` 当 `effective` 用 → V2 不可用 |
| `lc2h.fast_multichunk.enabled` | `false` | `FastMultiChunkPlanner.ENABLED`；被 `MixinMultiChunk` 的 `calculateBuildings` HEAD 钩子调用 | `true` = 跳过 `canPlaceBuilding` → V2 路权失效 |
| `lc2h.baselineMode` | `false` | `Lc2hMixinConfigPlugin`：只保留 `MixinConfig`、`MixinDefaultDimensionInfoThreadLocal`、`ServerChunkCacheInvoker` | 可用于隔离测试；注意模式覆写仍在 |
| `lc2h.worldparity.auto` | `false` | `Lc2hRuntimeModes.WORLD_PARITY_AUTO` + `WorldParityAutoRunner`（读 `getStreetGenerationMode`、`HIERARCHICAL_GRID_V1`） | 开发用 A/B 对照，假定 V1 语义 → V2 下结果无意义 |
| `lc2h.parity.auto` | `false` | `MULTICHUNK_PARITY_AUTO` + `MultiChunkParityAutoRunner`（用 `FastMultiChunkPlanner` 的 `calculateFastForAudit`/`calculateOriginalForAudit`） | 同上 |

**其余（与路网无直接关系，供排障）**：`lc2h.precaptureTrace.enabled=false`（还决定两个 PreCaptureTrace mixin 是否应用）、`lc2h.multichunk.cacheMinRetain=192`、`lc2h.multichunk.boundaryStitch`、`lc2h.multichunk.boundaryTtlMs`、`lc2h.highway.potentialCacheSize`、`lc2h.highwaySupportMaxDepth`（配置默认 192）、`lc2h.lostcities.cache.maxBytes`、`lc2h.lostcities.genLock*`、`lc2h.cache.maxBytes`、`lc2h.terrain.floorBridge`、`lc2h.biome.scopedCache`、`lc2h.damage.cacheTtlMs`、`lc2h.damage.minCityOffset`、`lc2h.treeSafety.surfaceRailOnly`、`lc2h.treeReplay.*`、`lc2h.terrain.shift.*`、`lc2h.gpu.*`、`lc2h.kernel.*`、`lc2h.warmup.*`、`lc2h.startupWatchdog*` 等（共 200+ 个，完整名单可由常量池扫描复现；本报告只对被 V2 决策使用的键负责）。

### 3.3 运行期门控小结（默认配置）

生效：`MixinNativeBuildingInfoCharacteristicsCache`（特征缓存）、`MixinMultiChunk`（HEAD 钩子因 `ENABLED=false` 直接返回 false，但 `MultiChunkPlanningCache.begin/end` 的线程内记忆化 + 7 个 `@Redirect` 生效）、`MixinCity`、`MixinRailway`、`MixinDefaultDimensionInfoThreadLocal`、全部 highway/terrain/render/spawn mixin。
不生效：`MixinBuildingInfo`、两个 PreCaptureTrace。

---

## 4. 门控方案（只用允许的注解）

### 4.1 (a) 探测 LC2H

```java
// 1) 模组存在性与版本：唯一允许的可选模组探测方式
boolean lc2hPresent = ModList.get().isLoaded("lc2h");                 // mods.toml: modId="lc2h"
String  lc2hVersion = ModList.get().getModContainerById("lc2h")
        .map(c -> c.getModInfo().getVersion().toString()).orElse("");

// 2) 危险开关：普通 JDK 调用，不是反射
boolean concurrent = Boolean.parseBoolean(System.getProperty("lc2h.concurrentBuildingInfo", "false"));
boolean fastMulti  = Boolean.parseBoolean(System.getProperty("lc2h.fast_multichunk.enabled", "false"));
boolean baseline   = Boolean.parseBoolean(System.getProperty("lc2h.baselineMode", "false"));
boolean parity     = Boolean.parseBoolean(System.getProperty("lc2h.worldparity.auto", "false"))
                  || Boolean.parseBoolean(System.getProperty("lc2h.parity.auto", "false"));

// 3) 模式策略：只读解析 config/lc2h/lc2h_config.json 的 lostCitiesStreetGenerationMode
//    （Gson 已在 Minecraft 依赖里；只读、不写别人的配置）
String lc2hStreetMode = readLc2hConfigString("lostCitiesStreetGenerationMode", "HIERARCHICAL_GRID_V1");
//    并用 LostCitiesStreetModePolicy 的同款规则自查：profile 名 equalsIgnoreCase("aaaaaaaaz15Flat") 或以 "Azzz" 开头 → 实际为 LEGACY
```

版本比较只针对 `4.2.4-LTS`：本报告的全部结论只对该构建逐字节验证过；其他版本必须重跑同一套解析（`javap` 注解 + 常量池扫描）。

### 4.2 (b) 每个配置下是否运行 V2

V2 的启用条件（全部满足才启用，任一不满足即按 §4.4 报错并禁用 V2）：

| 条件 | 依据 |
| --- | --- |
| Citylines 自己的维度开关为开（SavedData，独立于 TLC 枚举） | `docs/WORKFLOW.md` §0.1 |
| 该维度无既有 V1 区块（或按本项目自己的兼容规则初始化） | 方案 §6 |
| `!lc2hPresent`，**或**同时满足下列全部：`!concurrent`、`!fastMulti`、`!parity`，且运行期可见模式为 `HIERARCHICAL_GRID_V1` | §2.2.1 / §2.2.2 / §2.2.3 |
| 模式校验以"LC2H 存在时 `resolve` 的结果"为准（即 `lc2hStreetMode` 归一化后 == `HIERARCHICAL_GRID_V1`，且当前 profile 名不触发 ChaosZPack 规则） | §1.5 字节码 |
| `baseline=true` 时：可以启用（LC2H 的特征缓存/多区块钩子都被关掉），但仍需上面的模式校验 | §1.2 |

推荐把"LC2H 存在 + 默认参数 + `lostCitiesStreetGenerationMode` 为 HGV1"定义为**受支持组合**；其余组合一律拒绝 V2 并给出可执行的修复提示。

### 4.3 (c) 中和危险路径

三个可选的中和手段，全部放在 `citylines.lc2h.mixins.json`（`required: false`，目标只用字符串 `targets=`；不存在 LC2H 时该配置整体不加载，我们的类里没有任何 LC2H 类型引用）：

1. **中和快速多区块（推荐，风险低）**

```java
@Mixin(targets = "org.admany.lc2h.worldgen.lostcities.FastMultiChunkPlanner", remap = false)
public class Lc2hFastMultiChunkGate {
    @Inject(method = "tryPlan", at = @At("HEAD"), cancellable = true, require = 1)
    private static void citylines$disableFastPlan(MultiChunk multiChunk, IDimensionInfo provider,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (CitylinesRoads.v2ActiveFor(provider)) cir.setReturnValue(Boolean.FALSE);  // false = 官方回退语义
    }
}
```

`tryPlan` 是普通 `public static` 方法，返回 `false` 正是 LC2H 自己的 fallback 信号（原实现里 `FALLBACK_DISABLED` 计数并 `return false`），因此取消它只会让 `calculateBuildings` 走上游 `canPlaceBuilding`，不破坏 LC2H 的一致性设计。**允许的注解**：只用 `@Inject` + `CallbackInfoReturnable`。
实现细节（已从 `mixin-0.8.5-sources.jar` 核实）：`CallbackInfoReturnable.setReturnValue(R)` 内部就调用 `super.cancel()`（javadoc 原文 "Sets a return value for this callback and cancels the callback (required in order to return the new value)"），所以 `cancellable = true` 时**只需** `cir.setReturnValue(...)`，不必再写 `cir.cancel()`（重复 cancel 无害，但缺少 `cancellable = true` 会抛 `CancellationException`）。现有 `HierarchicalStreetPlannerMixin` 的写法正确。
注意：`MixinMultiChunk` 的 HEAD 钩子会先调用 `tryPlan`；我们在 `tryPlan` HEAD 返回 false 即可，不需要碰 `MultiChunk`。

2. **中和模式覆写（推荐，但需实测注入顺序）**

在**基础** mixin 配置里（目标是 TLC，不引用 LC2H 类型）：

```java
@Mixin(DefaultDimensionInfo.class)
public abstract class DimensionModeGuard {
    @Inject(method = "getStreetGenerationMode", at = @At("HEAD"), cancellable = true, require = 1)
    private void citylines$authoritativeMode(CallbackInfoReturnable<StreetGenerationMode> cir) {
        // 仅当 Citylines 已为当前维度接管路网时，返回 TLC 持久模式（HGV1）
        // 从 @Shadow 的 final 字段 streetGenerationMode 或 SavedData 读取，不用 provider.getProfile()
    }
}
```

理由：LC2H 用 `@Overwrite` 替换了该方法；Mixin 的常规行为是"先合并 `@Overwrite` 方法体，再应用注入器"，因此 HEAD 注入应当仍会执行并可用 `cancel()` 抢先返回。**这一点无法从 jar 证明（属于 Mixin 运行时应用顺序），必须在游戏内实测**（见 T4）。若实测不成立，就退化为"配置必须对齐 + 否则拒绝"（§4.2）。
副作用（有意）：被接管的 V2 维度上，LC2H 的"ChaosZPack → LEGACY"规则对该维度失效；应在日志中说明。

3. **不推荐**：注入 `MixinBuildingInfo` 的并发特征路径去"修补" `raw`→`effective`。LC2H 用 `@Overwrite` 提供了整个方法体，我们用允许的注解只能在 HEAD 取消并自行调用 `@Shadow` 的 `getChunkCharacteristicsLocked`——这等于整段废弃 LC2H 的并发实现，且注入器与 `@Overwrite` 的顺序同样未定义。**首版不实现，直接按 §4.4 拒绝。**

### 4.4 (d) 大声失败（不允许静默降级）

在维度初始化（首次为某维度接管路网）之前做一次前置校验，任一条件不满足即：

1. 让该维度**保持 V1**（不接管、不写 Citylines 接管标记、不缓存任何 V2 计划）；
2. 在服务端日志与服务端命令输出中打印**结构化、可执行**的告警（前缀 `[Citylines/LC2H]`），内容至少包含：
   - 命中的检测结果（modid/版本/具体属性值/配置文件键与值/实际生效模式）；
   - 明确后果一句话（例如："V2 已在该维度禁用：LC2H 快速多区块选址会跳过 `MultiChunk.canPlaceBuilding`，V2 的整 footprint 路权无法生效。"）；
   - 修复动作（例如："删除 `-Dlc2h.fast_multichunk.enabled=true`，或把 `config/lc2h/lc2h_config.json` 的 `lostCitiesStreetGenerationMode` 设为 `HIERARCHICAL_GRID_V1`，然后重载世界。"）；
3. `/citylines roads` 输出同样的状态行（`MODE=V1`，`LC2H=present(4.2.4-LTS)`，`REASON=...`），避免"看起来是 V2 其实是 V1"。

不要做：把 V2 降级成 V1 后继续生成并声称是 V2；不要在网络/客户端静默隐藏。

### 4.5 允许注解做不到、必须写成"已记录不兼容"的部分

1. **`lc2h.concurrentBuildingInfo=true` 下的特征语义**：`MixinBuildingInfo` 用 `@Overwrite` 全量替换 `BuildingInfo.getChunkCharacteristics/getBuildingInfo/getCityLevel/cleanCache`，且 `couldHaveBuilding` 由 `rawPlannedRoadType` 决定。我们无法在不使用 `@Overwrite`/`@Redirect`、且不引用 LC2H 类型的前提下**部分**修正它；HEAD 取消等于整段禁用 LC2H 的并发路径，且注入器 vs `@Overwrite` 的顺序无契约。→ **文档化为不兼容，检测到即拒绝 V2。**
2. **任何"必须替换方法体"的 V2 需求若落在 §2.3 的 `@Overwrite` 清单上**：LC2H 的方法体优先，我们用 `@Inject` 只能在其前后附加行为，无法替换。特别提醒：`DefaultDimensionInfo.getStreetGenerationMode()`（§4.3 方案 2 是"抢占"而非"替换"，若实测无效即不可解）；`BuildingInfo.getChunkCharacteristics` 本身（我们的 V2 接线点在它调用的 `getChunkCharacteristicsLocked`，不在它自己，所以当前设计安全）；`LostCityTerrainFeature.getHeightmap(ChunkCoord,WorldGenLevel)`（V2 若想自定义高度图缓存则不可解）。
3. **LC2H 内部缓存不可失效**：`BuildingInfo.cleanCache()`（上游）清不掉 `BuildingInfoCacheScope`（LC2H）。当 LC2H 存在时，"改 V2 计划版本 / 改参数后靠 `cleanCache()` 就地重算"这条路不可用；必须走"该 `IDimensionInfo` 实例作废 + 维度重载/重启"。这是**已记录限制**，不是可以通过注解解决的问题（除非在 `citylines.lc2h.mixins.json` 里 mixin LC2H 的 `BuildingInfoCacheRegistry` 并在我们的重载流程里调用它，属于可选增强）。
4. **LC2H 的 `@Overwrite` 会连带替换 V2 依赖的上游新鲜度语义**：`getProfile()` 运行时可换、`getFeature()` 可换实例、`getHeightmap` 走 LC2H 缓存。V2 若把"设置快照"重新绑定到 `provider.getProfile()`，就会出现"规划器设置与当前 profile 不一致"。这只能通过 V2 自己的设计纪律避免（构造期快照 + `@Shadow` 字段），不是注解能修的。

---

## 5. 最小游戏内验证（TLC-only vs TLC+LC2H）

前置：同一世界种子、同一 profile、同一批区块、同一访问顺序；每例只跑到"能对比若干区块"的量级，不做全矩阵。

| # | 场景 | 做什么 | 期望 / 看什么日志 |
| --- | --- | --- | --- |
| T1 | TLC-only，V2 关 | 新世界，生成 ~5×5 区块，执行 `/lostcities debug` | 记录基准：`streetGenerationMode = HIERARCHICAL_GRID_V1`、raw/effective 路类、四边连接、`finalCityContent`。作为 V1 语义基线 |
| T2 | TLC-only，V2 开 | 同种子同区块 | V2 路类/mask 与 T1 不同但自洽；`/citylines roads` 打印 `MODE=V2`、图摘要、终点分类、缓存统计 |
| T3 | TLC + LC2H **默认参数**，V2 开 | 同种子同区块，比对 T2 | **应逐格等于 T2**（V2 在 `getStreetInfo/getRoadType`、`canPlaceBuilding`、`initMultiBuildingSection`、街道渲染四条路径上都不被 LC2H 触碰）。日志：无 `Mixin apply failed`/`MixinApplyError`（LC2H 的配置是 `required: true`，应用失败是启动期致命错误）；LC2H 侧出现 `[LC2H]` 前缀行；`/lostcities debug` 仍打印 V2 的 `PlannedStreetInfo` |
| T4 | TLC + LC2H，`lc2h.concurrentBuildingInfo=true` | 启动 | Citylines 必须**拒绝**该维度的 V2，并打印属性名与修复建议；断言拒绝发生在任何区块生成之前。反例（若发现仍有 V2 区块生成）即为门控缺陷 |
| T5 | TLC + LC2H，`lc2h.fast_multichunk.enabled=true` | 启动 + 生成含多区块建筑的城区 | 二选一必须成立：**(a)** Citylines 拒绝 V2（首版）；或 **(b)** §4.3 方案 1 生效并在日志打印"已中和 LC2H 快速多区块"。然后 `/lostcities debug` 检查多区块建筑 footprint 与 V2 保留路不重叠 |
| T6 | TLC + LC2H，`lostCitiesStreetGenerationMode=LEGACY`（或换用 `Azzz*` profile） | 启动 | 必须触发 §2.2.1 的拒绝路径（因为此时 TLC 消费点不会调用规划器）；日志给出该配置键与当前值 |
| T7 | TLC + LC2H，`lc2h.baselineMode=true`，其余默认，V2 开 | 同 T3 | 特征缓存/多区块钩子被关，V2 结果应仍等于 T2；`[LC2H] Baseline mode active...` 是预期行 |
| T8 | 高速抽查（TLC vs TLC+LC2H） | 同种子生成到出现 intercity 高速，`/lostcities debug` 比对 highway 模式/路线 | 期望：V2 不改变高速结果；若 hub 高度/位置有差异，归因于 `MixinDefaultDimensionInfoHighwayHeightGate`（冷缓存用 `GROUNDLEVEL`）而非 V2 —— 这是 LC2H 固有近似，需在交付说明里写明 |
| T9 | 重载世界（同种子） | 退出重进同一世界，重新访问 T2/T3 的区块 | LC2H 的每维度缓存挂在 `IDimensionInfo` 实例上（`LostCityFeature.cleanUpInternal()` 会 `dimensionInfo.clear()`），重载后应重算且结果与首次一致；`/citylines roads` 的缓存计数应从 0 重新增长 |

**日志关键字清单**

- 原版/TLC：`/lostcities debug` 输出到服务端 stdout，含 `streetGenerationMode = ...`、`rawPlannedRoadType`/`plannedRoadType`、street part/桥计划（`mcjty.lostcities.commands.CommandDebug`，其内部就是 `dimInfo.getStreetPlanner().getStreetInfo(...)`，因此 V2 接管后打印的就是 V2 的 `PlannedStreetInfo`）。
- LC2H：logger 名 `LC2H-MixinPlugin`，业务日志统一前缀 `[LC2H]`（`org.admany.lc2h.log.LCLogger`）；配置问题前缀 `[LC2H] [Config] ...`；baseline 提示见 §1.2。命令根字面量 `lc2h`（`org.admany.lc2h.LC2H` 的 `RegisterCommandsEvent` 处理器 `ldc "lc2h"` → `DebugCommands.appendTo`），其子命令字面量包含 `cache`、`multichunk`、`diagnostics`、`lifecycle`、`hooks`、`lostcities`、`kernelbench`、`multichunkparity` 等（完整命令树未逐条验证，见 §6）。
- 混入失败：`Mixin apply failed` / 崩溃报告 `MixinApplyError`（LC2H 侧 `required: true` 会使失败变成启动崩溃，可作为"7.5.5 与 LC2H 不兼容"的最早信号）。
- Citylines：建议每条门控决策打印一行机器可读状态（`CITYLINES_MODE`/`CITYLINES_LC2H`/`CITYLINES_REASON`），T4–T6 直接断言该行。

---

## 6. 未确定 / 不猜测的部分

1. **Mixin 注入器与 `@Overwrite` 的应用顺序**：`DefaultDimensionInfo.getStreetGenerationMode` 上"我们的 `@Inject(HEAD, cancellable)` 能否抢在 LC2H 的 `@Overwrite` 方法体之前"无法从 jar 判定（取决于 Mixin 运行时把 injector 应用于合并后方法体的顺序）。§4.3 方案 2 必须实测；不成立则只能配置对齐 + 拒绝。
2. **LC2H 自有缓存的生命周期边界**：`BuildingInfoCacheRegistry.SCOPES` 以 `IDimensionInfo` 实例为键且未见主动移除（除 GUI 预览与 `evict*` API）；不同世界/维度不会串用（`LostCityFeature.cleanUpInternal()` 会新建实例），但旧实例条目是否泄漏、以及 LC2H 自身在何处调用 `evict*` 未逐一追踪。
3. **LC2H 命令树的完整语法**：只验证了根字面量 `lc2h` 与若干子命令字面量，未逐条还原 brigadier 树（不影响本报告的兼容结论）。
4. **`lc2h.worldparity.auto` / `lc2h.parity.auto` 的具体比对口径**：只验证了开关、调用类与它们引用 `getStreetGenerationMode`/`HIERARCHICAL_GRID_V1`/`FastMultiChunkPlanner` 的事实，未逐行分析其 A/B 判定是否会把 V2 结果判为失败；本报告将其列为"V2 下应拒绝/仅用于排障"。
5. **7.5.5 快照的保真度**：本报告对上游的引用均来自 `reference/lostcities-src`（`version=1.20-7.5.5`、无 Citylines 痕迹）。若该快照与 CurseForge 发布的 7.5.5 二进制有差异，涉及行号与私有方法的结论需以二进制复核（签名清单见 §2.4，可用 `javap` 对 TLC jar 重跑）。
6. **NeoForge 侧未分析**：`mixins.lc2h.neoforge.json` 与其 `Lc2hMixinConfigPlugin` 未纳入；本项目面向 Forge 1.20.1。
7. **LC2H 其他版本未验证**：全部结论仅对 `lc2h-1325431-8928853`（`4.2.4-LTS`）成立。
