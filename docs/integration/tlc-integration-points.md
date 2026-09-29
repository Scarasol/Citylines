> 历史过程记录：保留原文供追溯；其中旧配置、旧接口和阶段状态不代表当前版本。唯一当前交接与接口说明见 [DELIVERY.md](../DELIVERY.md)。

# TLC 集成点清单（CONNECTED_DISTRICT_V1 / V2 路网）

只读分析产物。基线：Lost Cities `1.20-7.5.5` / `61ed3004a8ad3e862a3bd7d0465839afa82f415f`（快照 `reference/lostcities-src/`）。
所有签名、行号、方法体均从该快照逐字抄录；行号对应该快照文件。

设计依据：`方案.md`（V2 = CONNECTED_DISTRICT_V1）、`道路形制与方块.md`、`抽象用例与调参.md`、本仓库 `docs/WORKFLOW.md`。

**硬约束（用户指定，本报告所有建议均受其约束）**

- 只允许：`@Inject`、`@WrapOperation`(mixinextras)、`@Unique`、`@Shadow`、`@Accessor`、`@Invoker`、`@Implements`。
- 禁止：`@Redirect`、`@Overwrite`、一切反射（`Class.forName`、`MethodHandles`、`setAccessible`）。
- 不可 fork Lost Cities；`StreetGenerationMode` 枚举不可新增常量；V2 状态必须存放在附属模组内。
- V2 未启用时 V1 行为必须逐位一致。

**关于"额外验证"的说明**：本报告除源码快照外，还解析了编译后的 LC2H `4.2.4-LTS`（CurseMaven `curse.maven:lc2h-1325431:8928853`，即 `reference/lc2h-src.tar.gz` 对应的构建；该 tarball 本身在第 3 个 jar 处截断，无法读取 Java 源码，因此改用 Gradle 缓存中的 deobf jar 解析常量池与注解）。凡属 LC2H 的结论都标注为**已逐字节验证的 jar 事实**，并给出类名与方法名；未能从字节码唯一确定的部分明确标注为推断。

---

## 0. 集成面总览

TLC 里"路网"只有一条数据出口：`IDimensionInfo.getStreetPlanner()`（`worldgen/IDimensionInfo.java:41`）。
共 6 处真实查询（4 个文件、6 行），全部走这一个 getter：

| # | 位置 | 查询 | 用途 |
| --- | --- | --- | --- |
| Q1 | `worldgen/lost/BuildingInfo.java:408` | `getStreetInfo(chunkX, chunkZ)` | 每区块路网特征（唯一进入渲染的通道） |
| Q2 | `worldgen/lost/MultiChunk.java:189` | `getRoadType(chunkX, chunkZ)` | 多区块建筑选址路权否决 |
| Q3 | `worldgen/street/HierarchicalBridgePlanner.java:83` | `getRoadType(...) == PRIMARY` | 桥端点资格 |
| Q4 | `worldgen/street/HierarchicalBridgePlanner.java:93` | `planner.isHorizontalPrimary/isVerticalPrimary` | 水隙格沿轴资格 |
| Q5 | `worldgen/street/HierarchicalBridgePlanner.java:106` | `isHorizontalPrimary/isVerticalPrimary` | 轴资格 |
| Q6 | `commands/CommandDebug.java:63` | `getStreetInfo(chunkX, chunkZ)` | 诊断输出 |

另有接口实现而非查询的点：`worldgen/DefaultDimensionInfo.java:138`（实现）、`worldgen/IDimensionInfo.java:41`（声明）、`gui/NullDimensionInfo.java:185`（GUI/预览实现，**也返回 planner，必须容忍 V2 状态缺失**）。

结论：**在 `HierarchicalStreetPlanner` 的 `getStreetInfo`/`getRoadType` 上做单点委派，可以让 Q1–Q6 同时看到 V2**（与 `docs/WORKFLOW.md` §0.2 的既定决策一致）。但单点委派**不足以完成 V2**，原因见 §1.3：`PlannedStreetInfo` 无法表达 V2 的"中心等级 + 四向边等级"，而且 Q1 之后 TLC 还会用 V1 规则二次加工（§3.3）。因此最小实现需要 **1 个委派点 + 1 个 BuildingInfo 特征覆写点 + 1 个街道部件替换点 + 若干抑制点**，见 §11。

---

## 1. `HierarchicalStreetPlanner`

文件：`reference/lostcities-src/src/main/java/mcjty/lostcities/worldgen/street/HierarchicalStreetPlanner.java`（共 354 行）

### 1.1 声明、字段、构造器

```java
public final class HierarchicalStreetPlanner {                       // L14

    private static final long VERSION_SALT = 0x4847524944563101L;    // L16  "HGRIDV1" + version byte
    ...
    private final long seed;                                          // L31
    private final long dimensionSalt;                                 // L32
    private final StreetPlannerSettings settings;                     // L33
    private final int primaryOffsetX;                                 // L34
    private final int primaryOffsetZ;                                 // L35

    public HierarchicalStreetPlanner(long seed, String dimensionId, StreetPlannerSettings settings) {  // L37
        this.seed = seed;
        this.dimensionSalt = stableStringHash(dimensionId);
        this.settings = settings;
        this.primaryOffsetX = floorModHash(hash(PRIMARY_X_SALT, 0, 0, 0), settings.primarySpacingX());
        this.primaryOffsetZ = floorModHash(hash(PRIMARY_Z_SALT, 0, 0, 0), settings.primarySpacingZ());
    }
```

构造器描述符：`(JLjava/lang/String;Lmcjty/lostcities/worldgen/street/StreetPlannerSettings;)V`

### 1.2 两个查询方法（逐字）

```java
    public PlannedRoadType getRoadType(int chunkX, int chunkZ) {      // L57
        return rawAt(chunkX, chunkZ).roadType;
    }

    public PlannedStreetInfo getStreetInfo(int chunkX, int chunkZ) {  // L61
        RawRoad raw = rawAt(chunkX, chunkZ);
        BlockLayout block = raw.block;
        boolean north = raw.roadType != PlannedRoadType.NONE && getRoadType(chunkX, chunkZ - 1) != PlannedRoadType.NONE;
        boolean south = raw.roadType != PlannedRoadType.NONE && getRoadType(chunkX, chunkZ + 1) != PlannedRoadType.NONE;
        boolean west = raw.roadType != PlannedRoadType.NONE && getRoadType(chunkX - 1, chunkZ) != PlannedRoadType.NONE;
        boolean east = raw.roadType != PlannedRoadType.NONE && getRoadType(chunkX + 1, chunkZ) != PlannedRoadType.NONE;
        return new PlannedStreetInfo(raw.roadType, north, south, west, east,
                block.blockX, block.blockZ, block.westX, block.northZ, block.eastX, block.southZ, block.density,
                block.secondaryX, block.secondaryZ, raw.tertiary);
    }
```

`rawAt`（L73–88，private）、`getBlockLayout`（L90，**public**）、`isVerticalPrimary`（L119，public）、`isHorizontalPrimary`（L128，public）是同类内部/公开辅助；末行 `public record BlockLayout(...)`（L343）与 `private record RawRoad(...)`（L341）。

### 1.3 内部缓存/状态与"能否 HEAD 委派"

**没有缓存，也没有可变状态。** 5 个实例字段全部 `private final`，`settings` 是不可变 record，`hash`/`mix64`/`unitDouble` 是纯静态函数。因此：

- `getRoadType`/`getStreetInfo` 是**纯函数、无副作用、线程安全**；同一个 `(chunkX, chunkZ)` 每次重算结果相同。
- 代价：`getStreetInfo` 会调用 5 次 `rawAt`（自身 1 次 + 4 个方向各 1 次），每次 `rawAt` 都重跑 `getBlockLayout`（含 `selectSecondaryPositions` 的列表分配与排序）。V2 若在 HEAD 委派，**不要**在 V2 路径里再以同样方式递归调用 5 次；应一次解析整分区的四向 mask。

**`@Inject(at = HEAD, cancellable = true)` 委派评估：可行，但有三个必须处理的问题。**

1. **可委派性 OK。** 方法是 `public` 非 final（类 final 但方法不是），描述符简单：
   - `getRoadType(II)Lmcjty/lostcities/worldgen/street/PlannedRoadType;`
   - `getStreetInfo(II)Lmcjty/lostcities/worldgen/street/PlannedStreetInfo;`
   V2 未启用时不得调用 `ci.cancel()`，V1 字节码路径不变（`@Inject` 本身不改变语义，只增加一次空判断）。LC2H 4.2.4-LTS **没有** mixin `HierarchicalStreetPlanner`（已解析 `mixins.lc2h.json` 与全部 1476 个 jar 条目确认：只有 `MixinMultiChunk`、`MixinNativeBuildingInfoCharacteristicsCache`、`MixinDefaultDimensionInfoThreadLocal` 等触及 planner 的下游对象）。所以"S 类被 LC2H 也碰"这一担忧在**当前锁定构建上不成立**，但见 §10 的其它 LC2H 冲突。

2. **实例级 V2 状态挂载 OK，但要注意"实例唯一性"。** planner 由 `DefaultDimensionInfo` 构造器 L62 建立，每个 `DefaultDimensionInfo` 实例恰好一个，而 `IDimensionInfo` 实例由 `LostCityFeature.getOrCreateDimensionInfo` 的 `ConcurrentHashMap.computeIfAbsent`（`LostCityFeature.java:198`）保证**每维度唯一**；`LostCityFeature.cleanUpInternal()`（L217–223）会 `dimensionInfo.clear()`，之后新维度会得到**新 planner 实例**。所以把 V2 planner 以 `@Unique` 字段挂在 planner 或 `DefaultDimensionInfo` 上都能天然随生命周期失效——但**绝不能**用 `static Map<dimension, V2State>` 而不接 `cleanUp`。

3. **对象标识/线程安全**：`LostCityFeature.runWithDimensionInfo`（L84–99）只对 3×3 邻域加**分片锁**（4096 条 stripe，L38/L106–135），**不同区块可并发生成**；而 Q1 的调用发生在 `BuildingInfo.getChunkCharacteristics` 的 `synchronized (getDimensionLock(dimension))` 内（`BuildingInfo.java:369`）。因此：
   - V2 planner 必须是线程安全的，且**不得在锁内做阻塞计算**（方案 §7 的"锁外构造 + 原子发布"正是为此）。
   - 若 V2 在 `getStreetInfo` 内回调 `BuildingInfo.getChunkCharacteristics`/`MultiChunk.getOrCreate`，会重入同一 `getDimensionLock`（`ReentrantLock` 语义的 `synchronized` 可重入，不会死锁），但会触发"缓存构建中重入"，方案 §3 明确禁止。

**`PlannedStreetInfo` 的表达力不足（关键发现）。** record 组件只有 `roadType` + 4 个 `boolean` 连接位；`道路形制与方块.md` §3 要求部件键是 `(本格 roadType, 四向边等级)`，而 4 个 boolean **不携带边等级**。把 V2 的 `PRIMARY/SECONDARY` 边塞进 boolean 后，`LostCityTerrainFeature` 无法恢复"邻格是主干还是集散路"。因此 V2 **必须**另开一条数据通道（§3.4 的 duck interface / `@Unique` 侧表），不能只靠委派 `getStreetInfo`。

### 1.4 `StreetPlannerSettings`

```java
public record StreetPlannerSettings(                                  // L5
        int primarySpacingX, int primarySpacingZ, float primaryOptionalChance, int primaryForceEvery,
        int secondaryMinCountX, int secondaryMaxCountX, int secondaryMinCountZ, int secondaryMaxCountZ,
        int minimumRoadSeparation, int minimumEdgeDistance,
        float tertiaryChance, int tertiaryMinLength, int tertiaryMaxLength) {
    public StreetPlannerSettings { /* L20 校验：spacing>=8；chance∈[0,1]；forceEvery∈[1,16]；
                                       min<=max；separation>=2 且 edgeDistance>=2；tertiary 合法 */ }
    public static StreetPlannerSettings fromProfile(LostCityProfile profile) {  // L40
        return new StreetPlannerSettings(
                profile.PRIMARY_ROAD_SPACING_X, profile.PRIMARY_ROAD_SPACING_Z,
                profile.PRIMARY_ROAD_OPTIONAL_CHANCE, profile.PRIMARY_ROAD_FORCE_EVERY,
                profile.SECONDARY_ROAD_MIN_COUNT_X, profile.SECONDARY_ROAD_MAX_COUNT_X,
                profile.SECONDARY_ROAD_MIN_COUNT_Z, profile.SECONDARY_ROAD_MAX_COUNT_Z,
                profile.MINIMUM_ROAD_SEPARATION, profile.MINIMUM_ROAD_EDGE_DISTANCE,
                profile.TERTIARY_ROAD_CHANCE, profile.TERTIARY_ROAD_MIN_LENGTH, profile.TERTIARY_ROAD_MAX_LENGTH);
    }
}
```

V2 相关性：`StreetPlannerSettings` 是 V1 的（间距/次要路数量/三级路）参数，**V2 不需要它，只需要 `seed`+维度 ID 做版本化哈希**。`fromProfile` 是 `public static`，附属模组可直接调用（在 `DefaultDimensionInfo` 的 `@Inject` 里通过 `@Shadow` 的 `streetPlanner` 或直接 `provider.getProfile()` 取得）。V2 严禁把 `TERTIARY` 纳入部件枚举（`道路形制与方块.md` §2 末段）。

### 1.5 `PlannedRoadType`（enum，值/序）

文件 `street/PlannedRoadType.java`：

```java
public enum PlannedRoadType {
    NONE, TERTIARY, SECONDARY, PRIMARY;                              // L4-7  （序数 0..3，等级递增）
    public static PlannedRoadType strongest(PlannedRoadType first, PlannedRoadType second) {  // L9
        return first.ordinal() >= second.ordinal() ? first : second;
    }
}
```

V2 用法：`ordinal()` 即等级，`min(边两侧)` 可直接用 `ordinal` 比较（方案 §2 的"端口等级由两侧路类按同一规则求 min"）。**不能在枚举里加 `V2_PRIMARY`**；V2 的 PRIMARY/SECONDARY 复用这两个既有常量，但语义由附属模组自己的 `roadReservation` 侧表定义（例如 `TERTIARY` 在 V2 维度恒为 `NONE`）。

### 1.6 `PlannedStreetInfo`（record 组件）

`street/PlannedStreetInfo.java:5`：15 个组件

```java
PlannedRoadType roadType, boolean north, boolean south, boolean west, boolean east,
int primaryBlockX, int primaryBlockZ, int primaryWestX, int primaryNorthZ, int primaryEastX, int primarySouthZ,
double density, List<Integer> secondaryRoadsX, List<Integer> secondaryRoadsZ, TertiaryRoadSegment tertiarySegment
```
加 `isRoad()`（`roadType != NONE`）与 `connects(RoadDirection)`（switch 四向）。是 `public record`，附属模组**可以直接 `new`**（V2 委派时构造返回值），但见 §1.3 的表达力问题。

### 1.7 `TertiaryRoadSegment`

`street/TertiaryRoadSegment.java:4`：`public record TertiaryRoadSegment(long id, int originX, int originZ, RoadDirection direction, int length)`，含 `contains(int chunkX, int chunkZ)`（L5）。V2 委派时该组件可传 `null`（V1 在 PRIMARY/SECONDARY 时同样为 `null`）。

`RoadDirection`（`street/RoadDirection.java:3`）：`NORTH(0,-1), SOUTH(0,1), WEST(-1,0), EAST(1,0)`，`stepX()/stepZ()`。

### 1.8 §1 推荐策略

| 目标 | 注解 | 位置 | 说明 |
| --- | --- | --- | --- |
| V2 路网委派 | `@Inject(method={"getStreetInfo","getRoadType"}, at=@At("HEAD"), cancellable=true)` | `HierarchicalStreetPlanner` | 未启用 V2 时直接 return，不 cancel。用 `@Unique` 字段或 duck interface 取 V2 sink；**不要**用 `@Accessor` 读 V1 私有字段做判定，用 `@Shadow` 更直观 |
| V2 状态挂载 | `@Unique` 字段 + `implements CitylinesRoadSink` | 同上 或 `DefaultDimensionInfo` | 见 §2 |
| V2 四向边等级 | 独立 duck interface（如 `CitylinesRoadInfo { byte centre(); byte edge(RoadDirection); BridgeSpanV2 span(); }`） | `BuildingInfo`（§3.4） | 不能靠 `PlannedStreetInfo` |

---

## 2. `DefaultDimensionInfo` 与 `IDimensionInfo`

文件：`worldgen/DefaultDimensionInfo.java`（202 行）、`worldgen/IDimensionInfo.java`（57 行）

### 2.1 字段集（L38–52，逐字）

```java
    private volatile WorldGenLevel world;
    private final long seed;
    private final ResourceKey<Level> type;
    private final LostCityProfile profile;
    private final LostCityProfile profileOutside;
    private final WorldStyle style;
    private final StreetGenerationMode streetGenerationMode;
    private final HierarchicalStreetPlanner streetPlanner;
    private final HighwayGenerationMode highwayGenerationMode;
    private final IntercityHighwayPlanner highwayPlanner;

    private final ThreadLocal<Random> random;

    private final Registry<Biome> biomeRegistry;
    private final LostCityTerrainFeature feature;
```

### 2.2 构造器全文（L54–81，逐字）

```java
    public DefaultDimensionInfo(WorldGenLevel world, LostCityProfile profile, LostCityProfile profileOutside) {
        this.world = world;
        this.seed = world.getSeed();
        this.type = world.getLevel().dimension();
        this.profile = profile;
        this.profileOutside = profileOutside;
        style = AssetRegistries.WORLDSTYLES.get(world, profile.getWorldStyle());
        streetGenerationMode = LostCityWorldGenData.get(world.getLevel()).getStreetMode(world.getLevel().dimension(), profile.STREET_GENERATION_MODE);
        streetPlanner = new HierarchicalStreetPlanner(world.getSeed(), world.getLevel().dimension().location().toString(), StreetPlannerSettings.fromProfile(profile));
        random = ThreadLocal.withInitial(() -> new Random(seed));
        RandomSource randomSource = new LegacyRandomSource(world.getSeed());
        feature = new LostCityTerrainFeature(this, profile, randomSource);
        feature.setupStates(profile);
        highwayGenerationMode = LostCityWorldGenData.get(world.getLevel()).getHighwayMode(world.getLevel().dimension(), profile.HIGHWAY_GENERATION_MODE);
        if (highwayGenerationMode == HighwayGenerationMode.INTERCITY_NETWORK_V1) {
            HighwayPlannerSettings highwaySettings = HighwayPlannerSettings.fromProfile(profile);
            long cacheSignature = LostCityHighwayData.createCacheSignature(world.getSeed(), profile, profileOutside,
                    highwaySettings, style.getId());
            highwayPlanner = new IntercityHighwayPlanner(world.getSeed(), world.getLevel().dimension().location().toString(),
                    highwaySettings,
                    new ApproximateCityPotential(world.getSeed(), profile, this::applyHighwayCityConstraints),
                    (chunkX, chunkZ) -> BuildingInfo.getCityLevel(new ChunkCoord(type, chunkX, chunkZ), this),
                    LostCityHighwayData.get(world.getLevel()).forDimension(world.getLevel().dimension(), cacheSignature));
        } else {
            highwayPlanner = null;
        }
        biomeRegistry = world.registryAccess().registryOrThrow(Registries.BIOME);
    }
```

**精确构造器描述符**：
`(Lnet/minecraft/world/level/WorldGenLevel;Lmcjty/lostcities/config/LostCityProfile;Lmcjty/lostcities/config/LostCityProfile;)V`

附属模组 Mixin 的 handler 签名（`DefaultDimensionInfoMixin` 已存在，可沿用）：
`private void hook(WorldGenLevel world, LostCityProfile profile, LostCityProfile profileOutside, CallbackInfo ci)`

### 2.3 `IDimensionInfo` 接口方法（L20–57）

```
void setWorld(WorldGenLevel)            long getSeed()                WorldGenLevel getWorld()
ResourceKey<Level> getType()            LostCityProfile getProfile()  LostCityProfile getOutsideProfile()
WorldStyle getWorldStyle()              StreetGenerationMode getStreetGenerationMode()
HighwayGenerationMode getHighwayGenerationMode()   IntercityHighwayPlanner getHighwayPlanner()
HierarchicalStreetPlanner getStreetPlanner()       Random getRandom()
LostCityTerrainFeature getFeature()     ChunkHeightmap getHeightmap(int,int)
ChunkHeightmap getHeightmap(ChunkCoord) Holder<Biome> getBiome(BlockPos)
@Nullable ResourceKey<Level> dimension()
```

`DefaultDimensionInfo` 实现全部（L96–201）。**注意 `gui/NullDimensionInfo.java` 也实现该接口**（L185 返回 `streetPlanner`，L180 返回 `profile.STREET_GENERATION_MODE`），并且是 `public class NullDimensionInfo implements IDimensionInfo`（`gui/NullDimensionInfo.java:34`）。任何"从 provider 拿 V2 状态"的辅助函数都必须对 `NullDimensionInfo` 返回 null 而不是抛异常。

### 2.4 生命周期与锁

- 创建：`LostCityFeature.getOrCreateDimensionInfo`（`LostCityFeature.java:190–206`）：

```java
            IDimensionInfo info = dimensionInfo.computeIfAbsent(type, key -> {
                LostCityProfile outsideProfile = profile.CITYSPHERE_OUTSIDE_PROFILE == null ? null : ProfileSetup.STANDARD_PROFILES.get(profile.CITYSPHERE_OUTSIDE_PROFILE);
                return new DefaultDimensionInfo(world, profile, outsideProfile);
            });
            info.setWorld(world);
```
  → 构造器在 `ConcurrentHashMap.computeIfAbsent` 的映射函数内执行（持有 bin 锁），**TAIL 钩子里做磁盘 I/O 是危险设计**（见 §2.6）。
- 使用：`LostCityFeature.runWithDimensionInfo`（L84–99）取 `lifecycleLock.readLock()`，再按 3×3 邻域取分片锁（L106–135）。
- 清理：`LostCityFeature.cleanUpInternal`（L217–223）：`LostCities.lostCitiesImp.cleanUp(); ForgeEventHandlers.cleanUp(); AssetRegistries.reset(); dimensionInfo.clear();`
- 脏计数：`public static volatile int globalDimensionInfoDirtyCounter`（`LostCityFeature.java:41`），由 `gui/GuiLCConfig.java:65,548`、`setup/ClientEventHandlers.java:78`、`setup/ForgeEventHandlers.java:142` 自增。**这是附属模组可用的公共生命周期信号**（非反射）。
- 世界/维度适配器：`LostCityFeature.getDimensionInfo(WorldGenLevel)` 是 `public`（L157），`setup/Registration.java:26` 的 `LOSTCITY_FEATURE` 是 `public static final RegistryObject<LostCityFeature>`。→ 附属模组无需反射即可拿到与 worldgen 完全相同的 `IDimensionInfo` 实例。

### 2.5 哪个是"每维度 V2 planner 的最安全挂载点"

**推荐：`@Inject(method="<init>", at=@At("TAIL"))` + 混入类上的 `@Unique` 字段 + 混入类 `implements` 一个附属模组自己的接口。**

理由与细节：

1. 实例唯一性由 `computeIfAbsent` 保证；TAIL 恰好一次/实例。
2. TAIL 时 `streetPlanner`（L62）、`feature`（L65）、`highwayPlanner`（L72/78）、`biomeRegistry`（L80）都已赋值，`@Shadow` 读它们安全。
3. `DefaultDimensionInfo` 是普通 `public class`（非 final），Mixin 追加接口是标准 Mixin 行为（混入类 `implements` 的接口会被加到目标类上），**不需要 `@Implements`**；`@Implements` 只在需要给"接口"加软实现（default 方法）时才用。
4. **不要**把 V2 planner 挂到 `HierarchicalStreetPlanner` 上作首选：虽然可行，但 `getStreetPlanner()` 只暴露 V1 类型，附属模组拿到的静态类型不含 duck interface，需要额外 `instanceof` 转换；挂 `DefaultDimensionInfo` 后，凡是有 `IDimensionInfo` 的地方都能 `instanceof CitylinesDimensionRoadState` 取到。两个都挂会带来"哪个是真源"的歧义——**只挂一处**，建议挂 `DefaultDimensionInfo`，V2 委派时从 planner 反查是不可能的（planner 不知道自己的 provider），所以委派钩子的 handler 需要 `provider`：见下一条。
5. **委派钩子拿 `provider` 的办法**：`HierarchicalStreetPlanner` 不持有 provider。可行做法有二：
   (a) 在 `DefaultDimensionInfoMixin` 的 TAIL 里把 `(planner 实例 → V2 sink)` 登记进附属模组的 `WeakHashMap`/`Map<HierarchicalStreetPlanner, V2Sink>`，并在 `cleanUp` 时清理（用 `globalDimensionInfoDirtyCounter` 变化触发）。**这是静态注册表，但符合 WORKFLOW.md §0.3 的"实例级回链"折中**；弱键可避免泄漏。
   (b) 反过来：`@Inject` 到 `DefaultDimensionInfo.getStreetPlanner()`（`at=@At("RETURN")`, cancellable）把返回值换成附属模组的 `HierarchicalStreetPlanner` **子类**——**不可行**，类是 `final`。
   → 采纳 (a)，并且**必须**实现失效（见 §7.5）。

### 2.6 风险 / 顺序陷阱

1. **磁盘 I/O 在 `computeIfAbsent` 锁内**：TAIL 钩子若调用 `SavedData`（`DimensionDataStorage.computeIfAbsent` 会触发文件加载）或 `level.getChunk(...)`，会在 CHM bin 锁内做 I/O，可能拖慢首次维度访问，甚至与区块加载形成锁序问题。**建议**：TAIL 只放一个"携带 `world`/`profile`/`profileOutside`/`seed`/维度 ID 的惰性 holder"，真正解析 V2 模式推迟到第一次 V2 查询（那时在 `runWithDimensionInfo` 的分片锁下，但仍应避免磁盘 I/O —— 更好的做法是在 `LevelEvent.Load` / `ServerStartedEvent` 里预读 SavedData 进内存缓存）。
2. **LC2H 覆写面**：LC2H 4.2.4-LTS 的 `org.admany.lc2h.mixin.lostcities.dimension.MixinDefaultDimensionInfoThreadLocal`（`@Mixin(value = DefaultDimensionInfo.class, remap = false)`）用 `@Overwrite` 替换了 `setWorld`、`getWorld`、`getSeed`、`getType`、`getRandom`、`getProfile`、`getOutsideProfile`、`getWorldStyle`、`getStreetGenerationMode`、`getFeature`、`getHeightmap(int,int)`、`getHeightmap(ChunkCoord)`、`dimension()`（已从 jar 注解解析逐条确认）。**它没有覆写 `getStreetPlanner()`、也没有覆写 `<init>`**，所以我们的 TAIL 钩子安全，但：
   - 不要在钩子里用 `this.getProfile()`/`getStreetGenerationMode()`（会走 LC2H 的可变覆写路径）；**直接用构造器参数**，或从 `@Shadow` 的 `private final LostCityProfile profile` 读。
   - LC2H 还 `@Shadow`s 了 `profile`、`profileOutside`、`style`、`streetGenerationMode`——字段名不要与 `lc2h$` 前缀冲突；附属模组统一用 `citylines$`。
3. `setWorld` 每次 `getOrCreateDimensionInfo` 都会被调（`LostCityFeature.java:202`），**V2 状态不要在 `setWorld` 里重建**（LC2H 已 `@Overwrite` 该方法，注入其中不可靠）。
4. `NullDimensionInfo`（GUI）没有 V2 状态 → 所有取用点必须 null-safe。

---

## 3. `BuildingInfo`

文件：`worldgen/lost/BuildingInfo.java`（2295 行）

### 3.1 `getChunkCharacteristicsLocked` 全部步骤（顺序，L374–497）

`public static LostChunkCharacteristics getChunkCharacteristics(ChunkCoord, IDimensionInfo)`（L368）先 `synchronized (getDimensionLock(coord.dimension()))` 再进 `private static LostChunkCharacteristics getChunkCharacteristicsLocked`（L374）。步骤：

1. **L375–378** 读 `CITY_INFO_MAP`（`TimedCache<ChunkCoord, LostChunkCharacteristics>`，L161）命中即返回。
2. **L381** `LostCityProfile profile = getProfile(coord, provider);`（L729，考虑 Space/Spheres 的球内外 profile）。
3. **L382** `LostChunkCharacteristics characteristics = new LostChunkCharacteristics();`
4. **L385** `characteristics.isCity = isCityRaw(coord, provider, profile);`
5. **L387–392** 非城市 → `multiPos = MultiPos.SINGLE; multiBuilding = null;`；否则 `initMultiBuildingSection(...)`（L600，内部调用 `City.isChunkOccupied`、`City.getPredefinedBuilding`、**`MultiChunk.getOrCreate(provider, coord)`**（L618）与 `multiChunk.getMultiBuilding(coord)`（L619））。
6. **L394–399** `StructureAvoidance.check(...)`；`avoidCity` 时 `isCity = false`。
7. **L401–405** `cityLevel`：单格用 `getCityLevel(coord, provider)`（L1230）；多格用 `MULTI_USE_CORNER ? getTopLeftCityLevel : getAverageCityLevel`。
8. **L406** `Random rand = getBuildingRandom(chunkX, chunkZ, provider.getSeed());`（`QualityRandom`，L1819）。
9. **L407–411 路网**：
   ```java
   if (provider.getStreetGenerationMode() == StreetGenerationMode.HIERARCHICAL_GRID_V1) {
       PlannedStreetInfo rawStreet = provider.getStreetPlanner().getStreetInfo(chunkX, chunkZ);
       characteristics.rawPlannedRoadType = rawStreet.roadType();
       characteristics.plannedRoadType = getEffectivePlannedRoadType(coord, provider, profile, characteristics, rawStreet);
   }
   ```
   **注意**：只有 `HIERARCHICAL_GRID_V1` 才填路网字段；`LEGACY` 下两个字段保持 `LostChunkCharacteristics` 的初值 `PlannedRoadType.NONE`（`api/LostChunkCharacteristics.java:17-18`）。
10. **L412–413** `characteristics.couldHaveBuilding = isCity && checkBuildingPossibility(coord, provider, profile, multiPos, cityLevel, plannedRoadType, rand);`
11. **L414–420** Space/Spheres 球边缘抑制（`dist > .7f → couldHaveBuilding = false`，仅单格）。
12. **L422–442** cityStyle 决议：`isCity && !couldHaveBuilding`（即街道/空地格）时对 3×3 邻域做 `Counter<String>` 多数投票并给自己加权（`counter.add` 两次）；否则 `City.getCityStyle(coord, ...)`。
13. **L444–478** 多区块建筑类型：非 top-left 走 `getTopLeftCityInfo`（L661）→ `getChunkCharacteristics`（**递归、同锁可重入**）；top-left/单格走 `MultiBuilding.getBuilding(...)` 或 `cityStyle.getRandomBuilding(rand, coord)`；`predefinedBuilding` 覆盖名字。
14. **L480–488** `MinecraftForge.EVENT_BUS.post(new LostCityEvent.CharacteristicsEvent(world, LostCities.lostCitiesImp, chunkX, chunkZ, characteristics));`——**外部可改特征**；随后 `avoidCity` 再次强制 `isCity=false; couldHaveBuilding=false;`。
15. **L493–495** 仅当 `structureAvoidance.isKnown()` 才 `CITY_INFO_MAP.put(coord, characteristics)`。

### 3.2 `LostChunkCharacteristics` 全部字段（`api/LostChunkCharacteristics.java`，逐字）

```java
public class LostChunkCharacteristics {
    public boolean isCity;                                   // L7
    public boolean couldHaveBuilding;                        // L8  True if this chunk could contain a building
    public MultiPos multiPos;                                // L9  Equal to SINGLE if a single building
    public int cityLevel;                                    // L10 0 is lowest city level
    public ResourceLocation cityStyleId;                     // L11  （全仓无写入点，仅声明）
    public ILostCityCityStyle cityStyle;                     // L12
    public ResourceLocation multiBuildingId;                 // L13  （全仓无写入点，仅声明）
    public ILostCityMultiBuilding multiBuilding;             // L14
    public ResourceLocation buildingTypeId;                  // L15  （全仓无写入点，仅声明）
    public ILostCityBuilding buildingType;                   // L16
    public PlannedRoadType rawPlannedRoadType = PlannedRoadType.NONE;   // L17
    public PlannedRoadType plannedRoadType = PlannedRoadType.NONE;      // L18
}
```
（`cityStyleId`/`multiBuildingId`/`buildingTypeId` 在 `reference/lostcities-src` 内**只有声明，无任何读写**——已全树 grep 确认。可安全忽略。）

### 3.3 `getEffectivePlannedRoadType` 全文（L572–595，逐字）

```java
    private static PlannedRoadType getEffectivePlannedRoadType(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile,
                                                                LostChunkCharacteristics characteristics, PlannedStreetInfo rawStreet) {
        if (!characteristics.isCity || !rawStreet.isRoad()) {
            return EffectiveStreetResolver.resolve(rawStreet.roadType(), characteristics.isCity, false, false);
        }
        // Explicit content and accepted multi-buildings have already been resolved
        // and always take precedence over the automatic street field.
        boolean overridden = City.getPredefinedBuilding(provider, coord) != null || City.getPredefinedStreet(provider, coord) != null
                || characteristics.multiPos.isMulti();
        // Avoid one-chunk fragments at tiny city-mask protrusions. This only asks
        // lower-level raw city membership and never final BuildingInfo state.
        boolean connectedCityNeighbor = false;
        for (RoadDirection direction : RoadDirection.values()) {
            if (rawStreet.connects(direction)) {
                ChunkCoord adjacent = coord.offset(direction.stepX(), direction.stepZ());
                LostCityProfile adjacentProfile = getProfile(adjacent, provider);
                if (adjacentProfile == profile && isCityRaw(adjacent, provider, adjacentProfile)) {
                    connectedCityNeighbor = true;
                    break;
                }
            }
        }
        return EffectiveStreetResolver.resolve(rawStreet.roadType(), characteristics.isCity, connectedCityNeighbor, overridden);
    }
```

配合 `street/EffectiveStreetResolver.java:9`：

```java
    public static PlannedRoadType resolve(PlannedRoadType rawRoadType, boolean currentChunkIsCity,
                                          boolean hasConnectedCityNeighbor, boolean overriddenByHigherPrecedenceContent) {
        if (!currentChunkIsCity || !hasConnectedCityNeighbor || overriddenByHigherPrecedenceContent) {
            return PlannedRoadType.NONE;
        }
        return rawRoadType;
    }
```

**"city + 一个相邻原始城市格且 profile 相同"规则与全部覆盖项（按优先级）**：

1. `isCity == false` → `NONE`（非城市格不铺路）。
2. `rawStreet.isRoad() == false` → `NONE`。
3. `overridden`：本格有**预定义建筑**（`City.getPredefinedBuilding(provider, coord)`，L80）**或预定义街道**（`City.getPredefinedStreet`，L85）**或** `characteristics.multiPos.isMulti()`（L9）→ `NONE`。即显式内容与已接受的多区块建筑压掉自动路。
4. `connectedCityNeighbor`：在本格 `rawStreet` 声明的每个连接方向上取邻格，要求 `getProfile(邻格, provider) == profile`（**同一 profile 实例**，不是同名）且 `isCityRaw(邻格)`（**原始**城市掩码，不是最终 `BuildingInfo`）。任意一个方向满足即通过。
5. 通过则取 `rawRoadType`，否则 `NONE`。

**这是 V2 必须绕开的核心规则。** V2 的路是"分量内连通树 + 已规划边"，它**不要求**相邻格是原始城市格（跨水桥头、跨城边界的门、以及城市掩码被水/断崖切碎的分量都需要非城市或不同 profile 的端点）。若让 V2 的 `getStreetInfo` 返回值走这条规则，V2 的主干会被大面积裁成 `NONE`，且裁法是 V1 的"单格邻接"启发式——正是方案 §1 要修的缺陷。

**多区块建筑抑制**：`overridden` 的第 3 项使 `rawPlannedRoadType != plannedRoadType`；`CommandDebug.java:77` 就是用这个不等式判断"多建筑压路"。V2 下 `PRIMARY/SECONDARY` 都应阻断多建筑（方案 §4.5），因此这个不等式在 V2 维度应当**恒为 false**（若为 true 说明漏了 §4 的强制点）。

### 3.4 其余被点名的成员

`checkBuildingPossibility`（L522，签名逐字）：

```java
    private static boolean checkBuildingPossibility(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile,
                                                     MultiPos section, int cityLevel, PlannedRoadType plannedRoadType, Random rand) {
        boolean b;
        float bc = rand.nextFloat();                                   // L525  ← 必须保留的随机消耗

        PredefinedBuilding predefinedBuilding = City.getPredefinedBuildingAtTopLeft(provider.getWorld(), coord);
        if (predefinedBuilding != null) return true;                   // L528-530
        PredefinedStreet predefinedStreet = City.getPredefinedStreet(provider.getWorld(), coord);
        if (predefinedStreet != null) return false;                    // L531-534

        CityStyle style = City.getCityStyle(coord, provider, profile);
        float buildingChance = EffectiveCitySettings.resolve(profile, style).buildingChance();   // L536-537

        if (section.isMulti()) {
            b = true;                                                  // L539-541  多区块：上方一切检查已过
        } else if (plannedRoadType != PlannedRoadType.NONE) {
            b = false;                                                 // L542-543  ← plannedRoadType 的唯一用途
        } else if (bc >= buildingChance) {
            b = false;                                                 // L544-546
        } else if (hasHighway(coord, provider, profile)) { ... }        // L547-552
        else if (hasRailway(coord, provider, profile)) { ... }          // L553-564
        else { b = true; }                                             // L565-568
        return b;
    }
```

描述符：`(Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/IDimensionInfo;Lmcjty/lostcities/config/LostCityProfile;Lmcjty/lostcities/api/MultiPos;ILmcjty/lostcities/worldgen/street/PlannedRoadType;Ljava/util/Random;)Z`

`plannedRoadType` 参数**只在 L542 用一次**：非 `NONE` 就否决单格建筑。→ **只要 `characteristics.plannedRoadType` 被 V2 正确写入，本方法自动 V2 生效，无需改动**（方案 §4.5 的"普通单格建筑在已规划路权上按现有 plannedRoadType 入口拒绝"）。**但**：`section.isMulti()` 优先级更高（L539 在 L542 之前），所以多区块建筑**不会**被 `plannedRoadType` 拦住 → 多区块路权必须靠 §4。

`doesRoadExtendTo`（L1791，逐字）：

```java
    public boolean doesRoadExtendTo() {
        if (provider.getStreetGenerationMode() == StreetGenerationMode.HIERARCHICAL_GRID_V1) {
            return isCity && !hasBuilding && (isPlannedRoad() || predefinedStreet);
        }
        boolean b = isCity && !hasBuilding;
        if (b) {
            return !isElevatedParkSection();
        }
        return false;
    }
```
→ V2 下 `isPlannedRoad()` 来自 `plannedRoadType`，已自动生效；**但 `isCity` 是必要条件**：V2 若允许"非城市格的桥头/门端点"参与渲染连接，此法会返回 false。首版 V2 桥只在两端城市格渲染，水面格不是城市格但也不铺街面（走 `doNormalChunk`），因此可接受。

`hasRoadConnection`（L1803，逐字）：

```java
    public static boolean hasRoadConnection(BuildingInfo i1, BuildingInfo i2) {
        if (!i1.doesRoadExtendTo()) return false;
        if (!i2.doesRoadExtendTo()) return false;
        if (i1.cityLevel == i2.cityLevel) return true;
        Direction slope1 = i1.getStreetSlopeDirection();
        Direction slope2 = i2.getStreetSlopeDirection();
        return slope1 != null && slope1.get(i1).coord.equals(i2.coord)
                || slope2 != null && slope2.get(i2).coord.equals(i1.coord);
    }
```
→ **只比较"两格是否都是路"，不比较边等级**。V2 契约要求"双边 mask 均有边才接路，边等级 = min(两侧)"（方案 §2.4）。所以 V2 下 `hasRoadConnection` 必须被"双边 mask"版本取代，否则会在"一格是路、邻格不是路"或"两侧等级不同"时给出错误连接。

`getStreetSlopeDirection`（L1406–1450）与 `isSameSlopeRoadClass`（L1452）：

```java
    public Direction getStreetSlopeDirection() {
        if (provider.getStreetGenerationMode() != StreetGenerationMode.HIERARCHICAL_GRID_V1
                || !doesRoadExtendTo()) {
            return null;
        }
        Direction slopeDirection = null;
        for (Direction direction : Direction.VALUES) {
            BuildingInfo adjacent = direction.get(this);
            if (isSameSlopeRoadClass(adjacent) && adjacent.cityLevel == cityLevel + 1) {
                if (slopeDirection != null) return null;      // 多于一个上坡方向 → 不铺坡
                slopeDirection = direction;
            }
        }
        if (slopeDirection == null) return null;
        BuildingInfo upper = slopeDirection.get(this);
        BuildingInfo approach = slopeDirection.getOpposite().get(this);
        if (!isSameSlopeRoadClass(approach) || approach.cityLevel != cityLevel) return null;
        BuildingInfo departure = slopeDirection.get(upper);
        if (!isSameSlopeRoadClass(departure) || departure.cityLevel != upper.cityLevel) return null;
        for (Direction direction : Direction.VALUES) {
            if (direction != slopeDirection && direction != slopeDirection.getOpposite()) {
                BuildingInfo side = direction.get(this);
                if (isSameSlopeRoadClass(side) && side.cityLevel == cityLevel) return null;
                BuildingInfo upperSide = direction.get(upper);
                if (isSameSlopeRoadClass(upperSide) && upperSide.cityLevel == upper.cityLevel) return null;
            }
        }
        return slopeDirection;
    }
    private boolean isSameSlopeRoadClass(BuildingInfo other) {
        return other.doesRoadExtendTo() && isPrimaryRoad() == other.isPrimaryRoad();
    }
```
V2 规则（方案 §2.1）：首版**不规划跨高度边**，坡道是后续独立扩展。但 `getStreetSlopeDirection` 只看 `isPrimaryRoad()` 与 `cityLevel`，**V2 主干碰到相邻 +1 层城市格就会生成坡道部件**——这会与 V2 的平面端口契约冲突。→ V2 必须抑制坡道（`getStreetSlopeDirection` 返回 null），否则 `generateStreetSlopeSection` 会用 V1 `parts.stair()` 覆盖 V2 路面。V2 的首版跨高度处理应为：高度不同的边**不进入 V2 图**（方案 §2.1），渲染上视作无连接边。

`hasXBridge`/`hasZBridge`：

```java
    public BuildingPart hasXBridge(IDimensionInfo provider) {          // L1552  public
        synchronized (memoizationLock) { return calculateXBridge(provider); }
    }
    private BuildingPart calculateXBridge(IDimensionInfo provider) {   // L1558
        ...
        if (provider.getStreetGenerationMode() == StreetGenerationMode.HIERARCHICAL_GRID_V1) {
            PlannedBridgeInfo planned = HierarchicalBridgePlanner.getBridgeInfo(this, Orientation.X);
            if (planned != null) {
                BuildingInfo endpoint = getBuildingInfo(planned.minimumEndpoint(), provider);
                xBridgeType = endpoint.largeBridgeType != null ? endpoint.largeBridgeType : endpoint.bridgeType;
            }
            return xBridgeType;
        }
        ...  // L1574+ LEGACY 逐格扫描
    }
```
`hasZBridge`（L1624）/`calculateZBridge`（L1630）同构，多了 `hasXBridge(provider) != null → null` 的 X/Z 互斥。**注意**：`hasXBridge` 在 V1 分支下会**递归调用 `BuildingInfo.getBuildingInfo`**（L1568，端点），因此桥判定不是纯函数。V2 桥必须换掉这一支，且不能让 V2 桥判定回调 `BuildingInfo`（方案 §3 禁止）。`hasXBridge`/`hasZBridge` 均为 **public 非 final**，可 `@Inject(HEAD, cancellable)`。

`CityLevel` / `cityLevel` 访问器：

```java
    public static int getCityLevel(ChunkCoord key, IDimensionInfo provider) {   // L1230
        synchronized (getDimensionLock(key.dimension())) { return getCityLevelLocked(key, provider); }
    }
    private static int getCityLevelLocked(ChunkCoord key, IDimensionInfo provider) { ... }   // L1236，带 CITY_LEVEL_CACHE（L162）
    public static int getCityLevelGui(ChunkCoord key, IDimensionInfo provider) { ... }        // L1259，无缓存
```
实例侧：`public final int cityLevel;`（L90，注释 `The first floor of buildings starts at groundLevel + cityLevel * 6`）、`public int getCityLevel()`（L2188，`ILostChunkInfo` 实现）、`public int getCityGroundLevel()`（L298）、`public int localToGlobal(int)`（L1824）、`public int globalToLocal(int)`（L1828）。
`getCityLevel*` 会触发真实高度/biome/城市概率查询（L1300 `getCityLevelNormal` → `provider.getHeightmap` + `isCityRaw`），方案 §9 已把它列为必须做性能对照的项。

### 3.5 构造器里与 V2 有关的行（L799–1123）

```java
    private BuildingInfo(ChunkCoord key, IDimensionInfo provider) {     // L799  private
        ...
        LostChunkCharacteristics characteristics = getChunkCharacteristics(key, provider);   // L807
        ...
        rawPlannedRoadType = characteristics.rawPlannedRoadType;        // L815
        ...
        isCity = c;                                                     // L860
        hasBuilding = b;                                                // L861
        plannedRoadType = isCity && !hasBuilding ? characteristics.plannedRoadType : PlannedRoadType.NONE;   // L864
        hierarchicalOpen = provider.getStreetGenerationMode() == StreetGenerationMode.HIERARCHICAL_GRID_V1
                && isCity && !hasBuilding && plannedRoadType == PlannedRoadType.NONE && !predefinedStreet;    // L865
        ...
            } else if (hierarchicalOpen) {                              // L921
                streetType = StreetType.PARK;                           // L925
                fountainType = null;
                parkType = rand.nextDouble() < citySettings.openLotParkChance() ? ... : null;
            } else {                                                    // L930
                streetType = StreetType.NORMAL;                         // L934
                parkType = null;
                BuildingPart selectedFountain = rand.nextFloat() < citySettings.fountainChance() ? ... : null;
                fountainType = isPlannedRoad() ? null : selectedFountain;      // L939  ← 读 plannedRoadType
            }
            ...
            if (provider.getStreetGenerationMode() == StreetGenerationMode.HIERARCHICAL_GRID_V1) {   // L998
                Random bridgeRandom = new QualityRandom(provider.getSeed() ^ 0x4c41524745425247L ^ ...);
                String largeBridge = cs.getRandomLargeBridge(bridgeRandom, this.coord);
                largeBridgeType = largeBridge == null ? null : AssetRegistries.PARTS.getOrWarn(provider.getWorld(), largeBridge);
            } else { largeBridgeType = null; }                           // L1004-1006
```

**随机消耗顺序敏感性**：`hierarchicalOpen` 决定 L921/L930 哪个分支，而**两支消耗的 `rand` 次数不同**（open 支：1×`nextDouble` + 条件 `getRandomPark`；planned 支：1×`nextFloat` + 条件 `getRandomFountain`；legacy 支：L910/L913/L915/L920 四次）。因此**改变 `plannedRoadType`/`hierarchicalOpen` 会改变整条 `rand` 流**，从而改变楼层数、cellars、门、palette、ruin、front 等一切下游结果。这是 V2 最难避免的"必要间接影响"（方案 §1 已承认"新的道路保留区会改变可放建筑和多区块建筑的位置"），但**必须**在 V1 维度保持为 0 变化：V2 未启用时 `plannedRoadType`/`hierarchicalOpen` 不变 → 分支不变 → 随机流不变。

### 3.6 哪些必须 V2-aware，`@WrapOperation` 够不够

| 成员 | V2 要求 | 最小手段 | 够不够 |
| --- | --- | --- | --- |
| `getChunkCharacteristicsLocked` L407-411 | 用 V2 结果填 `rawPlannedRoadType`/`plannedRoadType`，**跳过** `getEffectivePlannedRoadType` | `@WrapOperation` on `provider.getStreetPlanner().getStreetInfo(...)`（L408）+ `@WrapOperation` on `EffectiveStreetResolver.resolve(...)`（L575 与 L594 两处 INVOKE） | **够**。两处 WrapOperation 都可按 `method="getChunkCharacteristicsLocked"` / `"getEffectivePlannedRoadType"` 限定；V2 未启用时 `operation.callOriginal()` |
| `checkBuildingPossibility` L542 | 无需改；`plannedRoadType != NONE` 自动否决单格 | 无 | **够** |
| 多区块路权 | PRIMARY/SECONDARY 都阻断，整 footprint 检查 | 见 §4 | 需另一处 |
| `getEffectivePlannedRoadType` L572-595 | V2 下不应执行（其单格邻接启发式会裁掉 V2 路） | 同上 WrapOperation；或 `@Inject(at=HEAD, cancellable=true)` 在其内部按 V2 直接返回 | **够** |
| `BuildingInfo` 构造器 L864 | `plannedRoadType = ... characteristics.plannedRoadType`（已可用） | 无需改 | **够** |
| `BuildingInfo` 构造器 L865 `hierarchicalOpen` | V2 维度下语义要重定义：V2 无路的城市格应为 open lot（等价于现在的 `hierarchicalOpen==true`），V2 有路的格应为 `false` | 现状**恰好**可用（`plannedRoadType != NONE` ⇒ false；`== NONE` ⇒ true）。**但**语义绑在 `getStreetGenerationMode()==HIERARCHICAL_GRID_V1` 上：若 V2 维度的持久模式不是 HGV1，`hierarchicalOpen` 恒为 false，空地将走 L930 分支（`streetType=NORMAL` + 消耗 `nextFloat` 的 fountain），随机流与 V1-HGV1 不同 | **风险点**：这是"V2 必须让持久模式保持 HGV1"的硬依赖，见 §7 |
| `doesRoadExtendTo` L1791 | 差不多够，但 `isCity` 必要条件限制桥头/边界门 | 首版可不改；若要跨非城市端点的门，需 `@Inject(HEAD, cancellable)` | 视首版范围 |
| `hasRoadConnection` L1803 | **必须**换成"双边 mask 均有边 + 边等级一致" | 静态方法、`public static`，`@Inject(at=HEAD, cancellable=true)` 或 `@WrapOperation` on its call sites（`LostCityTerrainFeature:1663,1680`） | **必须深改**：仅靠 planner 委派不够 |
| `getStreetSlopeDirection` L1406 | V2 首版必须返回 null（无跨高度边） | `@Inject(HEAD, cancellable=true)`，条件按 V2 维度 | **必须** |
| `hasXBridge`/`hasZBridge` L1552/L1624 | V2 span 决定桥面部件 | `@Inject(HEAD, cancellable=true)` 返回 V2 deck part | **必须**（但见 §6） |
| `getCityLevel*` | 只读输入，不改 | 无 | — |

---

## 4. `MultiChunk`

文件：`worldgen/lost/MultiChunk.java`（230 行）

### 4.1 关键成员

```java
    record MB(String name, int offsetX, int offsetZ) {}                  // L30  ← 包级私有！
    private static final TimedCache<ChunkCoord, MultiChunk> MULTICHUNKS = new TimedCache<>(Config.CACHE_CLEANUP_SECONDS::get);   // L33
    public static void cleanCache() { MULTICHUNKS.clear(); }              // L34
    private final ChunkCoord mc;      // 除以 areasize 的坐标          // L38
    private final ChunkCoord topleft;                                     // L39
    private final int areasize;                                           // L40
    private final MB[][] buildingGrid;                                    // L41
```

```java
    public static MultiChunk getOrCreate(IDimensionInfo provider, ChunkCoord coord) {     // L56
        int areasize = provider.getWorldStyle().getMultiSettings().areasize();
        ChunkCoord mc = getMultiCoord(coord, areasize);
        return MULTICHUNKS.computeIfAbsent(mc, k -> new MultiChunk(mc, areasize).calculateBuildings(provider));
    }
```
描述符 `(Lmcjty/lostcities/worldgen/IDimensionInfo;Lmcjty/lostcities/varia/ChunkCoord;)Lmcjty/lostcities/worldgen/lost/MultiChunk;`

```java
    public MB getMultiBuilding(ChunkCoord coord) {                        // L62
        return buildingGrid[coord.chunkX() - topleft.chunkX()][coord.chunkZ() - topleft.chunkZ()];
    }
```
描述符 `(Lmcjty/lostcities/varia/ChunkCoord;)Lmcjty/lostcities/worldgen/lost/MultiChunk$MB;` —— **`MB` 是包级私有的嵌套 record**，附属模组在别的包里**无法声明这个类型**。这直接限制了可用手段（见 §4.4）。

`calculateBuildings`（L72–148，private）流程：`new Random(mc.chunkX()*797013493L + mc.chunkZ()*295085213L)` → 数量 `min + rand.nextInt(max-min+1)` → `BuildingInfo.getCityLevel(topleft, provider)` → 统计区域内 `CityStyle` → 按 style 权重抽 `CityStyle.getRandomMultiBuilding` → 按 `dimX+dimZ` 降序排序 → 每个建筑最多 `settings.attempts()` 次随机 `(x,z)` 试放，命中即 `placeBuilding`。

### 4.2 `canPlaceBuilding` 的路权冲突部分（L175–221，逐字）

```java
    private boolean canPlaceBuilding(ChunkCoord topleft, IDimensionInfo provider, LostCityProfile profile, CityStyle buildingCityStyle, MultiBuilding building,
                                     int cityLevel, int maxCellars, int x, int z) {
        int partlevel = provider.getWorldStyle().getWorldSettings().railPartHeight6();
        int correctStyle = 0;
        for (int xx = 0 ; xx < building.getDimX() ; xx++) {
            for (int zz = 0 ; zz < building.getDimZ() ; zz++) {
                if (buildingGrid[x+xx][z+zz] != null) {
                    return false;
                }
                ChunkCoord coord = topleft.offset(x + xx, z + zz);
                if (City.isChunkOccupied(provider, coord)) {
                    return false;
                }
                if (provider.getStreetGenerationMode() == StreetGenerationMode.HIERARCHICAL_GRID_V1) {
                    PlannedRoadType roadType = provider.getStreetPlanner().getRoadType(coord.chunkX(), coord.chunkZ());
                    if (profile.MULTI_BUILDING_STREET_CONFLICT.roadBlocks(roadType)) {
                        return false;
                    }
                }
                Railway.RailChunkInfo railChunkInfo = Railway.getRailChunkType(coord, provider, profile);
                ...
            }
        }
        float correctStyleFactor = provider.getWorldStyle().getMultiSettings().correctStyleFactor();
        if (correctStyle < building.getDimX() * building.getDimZ() * correctStyleFactor) {
            return false;
        }
        return true;
    }
```
描述符：`(Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/IDimensionInfo;Lmcjty/lostcities/config/LostCityProfile;Lmcjty/lostcities/worldgen/lost/cityassets/CityStyle;Lmcjty/lostcities/worldgen/lost/cityassets/MultiBuilding;IIII)Z`

**关键点**：路权检查已经在**整个 footprint 的逐格循环内**（`for xx / for zz`）执行，也就是说"整 footprint 检查"这个形状**已经存在**——只是谓词 `MULTI_BUILDING_STREET_CONFLICT.roadBlocks(roadType)` 太宽。

### 4.3 `MultiBuildingStreetConflict` 语义（`config/MultiBuildingStreetConflict.java`，逐字）

```java
public enum MultiBuildingStreetConflict {
    BLOCK_ALL,          // roadBlocks: roadType != NONE            → 任何路都阻断
    OVERRIDE_MINOR,     // roadBlocks: roadType == PRIMARY         → 只阻断主干（默认值）
    OVERRIDE_ALL;       // roadBlocks: false                       → 从不阻断

    public boolean roadBlocks(PlannedRoadType roadType) {
        return switch (this) {
            case BLOCK_ALL -> roadType != PlannedRoadType.NONE;
            case OVERRIDE_MINOR -> roadType == PlannedRoadType.PRIMARY;
            case OVERRIDE_ALL -> false;
        };
    }
    public static MultiBuildingStreetConflict byName(String name) { ... }   // L20
}
```
默认值：`config/LostCityProfile.java:221` `public MultiBuildingStreetConflict MULTI_BUILDING_STREET_CONFLICT = MultiBuildingStreetConflict.OVERRIDE_MINOR;`

→ V2 想要的规则（PRIMARY 与 SECONDARY 都阻断）**等价于 `BLOCK_ALL`**。区别只在于 V2 下 `getRoadType` 必须返回 V2 的 TERTIARY≡NONE 语义（V2 无三级路），此时 `BLOCK_ALL` 与 V2 语义完全一致。

### 4.4 用允许的注解强制"V2 footprint 路权"——三种方案与取舍

**方案 A（推荐，双保险）**

1. 前置否决（native 路径）：`@Inject(method="canPlaceBuilding", at=@At("HEAD"), cancellable=true)`。在 handler 内用 `@Shadow` 不到的东西（参数已给 `topleft`、`provider`、`building`、`x`、`z`）遍历 `building.getDimX()/getDimZ()` 的 footprint，向 V2 plan 查询每格 `centre ∈ {PRIMARY, SECONDARY}`；命中则 `cir.setReturnValue(false)`。
   - 优点：不碰 `MB`、不碰 `buildingGrid`、语义与 V1 完全同形（V1 也是在 footprint 内返回 false）。
   - 缺点：**LC2H 快速路径下此方法根本不会被调用**（§10.1 已验证）。
2. 后置兜底（覆盖 LC2H 快速路径）：`@Inject(method="initMultiBuildingSection", at=@At("RETURN"))`（`BuildingInfo.java:600`，`private static void initMultiBuildingSection(LostChunkCharacteristics, ChunkCoord, IDimensionInfo, LostCityProfile)`，描述符 `(Lmcjty/lostcities/api/LostChunkCharacteristics;Lmcjty/lostcities/varia/ChunkCoord;Lmcjty/lostcities/worldgen/IDimensionInfo;Lmcjty/lostcities/config/LostCityProfile;)V`）。RETURN 时读 `characteristics.multiPos`（`public`，`mcjty.lostcities.api.MultiPos`，record，可 `isMulti()/x()/z()/w()/h()`），若 footprint 与 V2 冲突则 `characteristics.multiPos = MultiPos.SINGLE; characteristics.multiBuilding = null;`
   - 优点：纯 `@Inject` + 公共 API（`LostChunkCharacteristics` 与 `MultiPos` 都是 public），**在 native 与 LC2H 快速路径下都会执行**（LC2H 只 shadow/覆写 `getChunkCharacteristics`、`getCityLevel`、`getBuildingInfo`，`getChunkCharacteristicsLocked` 与被它调用的 `initMultiBuildingSection` 仍是上游实现；jar 常量池证实 `MixinNativeBuildingInfoCharacteristicsCache` 里有 `@Shadow getChunkCharacteristicsLocked`）。
   - 缺点：**不是逐位等价**——MultiChunk 的 `buildingGrid` 仍记录了该建筑，会挤掉同一 multichunk 内后续建筑的试放位置，因此"被否决的建筑"之后的放置序列与 native 路径不同。必须作为已知限制声明（见 §11）。

**方案 B（不推荐）**：`@Accessor("buildingGrid")` 直接清格。
- 不可行/高风险：`MB` 包级私有，跨包混入类无法写出返回类型；尝试用 `Object[][]` 之类会因描述符不匹配而 Mixin 应用失败。**除非**把辅助类放进 `mcjty.lostcities.worldgen.lost` 包（跨 jar 分裂包，脆弱，且 Mixin 要求混入类位于配置声明的 `package` 下）。**不要采用**。

**方案 C（不推荐）**：`@Inject(at=HEAD, cancellable=true)` 到 `calculateBuildings` 并自行实现选址。
- 无法调用原方法体（`CallbackInfo` 没有 `callOriginal`），等于重写上游 148 行；且会与 LC2H 的 `MixinMultiChunk.lc2h$beginPlanningCache`（同样 HEAD/cancellable）争抢取消权，优先级顺序决定谁赢 → 未定义行为。**不要采用**。

**方案 D（必要时）**：`@WrapOperation` on `MultiChunk.calculateBuildings(IDimensionInfo)` 的**调用点**（`getOrCreate` 内的 lambda `k -> new MultiChunk(...).calculateBuildings(provider)`）。
- 描述符是 `(Lmcjty/lostcities/worldgen/IDimensionInfo;)Lmcjty/lostcities/worldgen/lost/MultiChunk;`，返回类型是 `MultiChunk`（public）→ **可以**写 `Operation<MultiChunk>`。但 `operation.callOriginal()` 之后要改 `buildingGrid` 仍然卡在方案 B 的 `MB` 问题上。→ 只在需要"记录/统计"时使用。

**配置开关的注意**：不要试图通过改 `profile.MULTI_BUILDING_STREET_CONFLICT` 来实现 V2（例如强行设成 `BLOCK_ALL`）。`LostCityProfile` 是共享可变对象（`config/` 下 `getProfileForDimension` 返回的是 `ProfileSetup.STANDARD_PROFILES` 里的共享实例），就地修改会污染同 profile 的其它维度，也违反"V1 逐位一致"。V2 的路权判定必须是**独立的谓词**，不借用 profile 枚举。

---

## 5. `LostCityTerrainFeature` 街道渲染全路径

文件：`worldgen/LostCityTerrainFeature.java`（2355 行）。字段：`public final IDimensionInfo provider;`（L84）、`public final LostCityProfile profile;`（L85）、`public final BlockState air;`（L63）、`public final BlockState liquid;`（L67）。
`public int generatePart(BuildingInfo, IBuildingPart, Transform, int, int, int, HardAirSetting)`（L1747）与 `public BlockState transformBlockState(Transform, BlockState)`（L1957）都是 **public**，`public ChunkDriver getDriver()`（L378）/**public**，`public CompiledPalette computePalette(BuildingInfo, IBuildingPart)`（L1845）**public**。

### 5.1 调用图（顶层）

```
generate(WorldGenRegion, ChunkAccess)                                  L276
 └─ doCity (info.isCity || (outsideChunk && hasBuilding))              L296
     ├─ true  → doCityChunk(info, heightmap, chunk)                    L307 / L908
     │            ├─ generateBuilding  (if info.hasBuilding)           L949
     │            ├─ generateStreet(info, heightmap)  (else)           L951 / L1162
     │            ├─ generateRuins                                     L958
     │            ├─ generateStreetDecorations   (仅当 !building && 无高速/铁路坡道)  L967 / L983
     │            ├─ Highways.generateHighways                         L971
     │            ├─ generateRubble                                    L976
     │            └─ Stuff.generateStuff                               L980
     └─ false → doNormalChunk(info, heightmap)                         L310 / L417
                  ├─ correctTerrainShape                               L420
                  ├─ Bridges.generateBridges(this, info)               L429   ← 桥面在此
                  ├─ Highways.generateHighways                         L430
                  └─ Scattered.generateScattered                       L435
```

### 5.2 逐个方法：现有行为、V2 需要什么、精确注入点

| 方法（行号） | 现在做什么 | V2 需求 | 推荐注解与目标 |
| --- | --- | --- | --- |
| `generateStreet(BuildingInfo, ChunkHeightmap)` L1162，`private void` | 走廊→`canDoStreetOrPark` 判定→取 `height=cityGroundLevel`→`getStreetSlopeDirection()`→`streetType` 分支（L1202 `switch`）→`height++`→park/fountain 部件→`generateRandomVegetation` + 4×`generateFrontPart`→最后 `generateBorders`（L1237） | **(a) 替换选择**（选 V2 部件族）**(b) 抑制**（fountain/vegetation/connector/坡道）**(c) 保留**（`generateBorders`） | 首选**不** HEAD-cancel `generateStreet`（要重写 76 行且必须 `@Invoker` 调私有 `generateBorders`）；改为逐个下游方法注入（见下） |
| L1196–1200 `else if (!info.isHierarchicalOpen())` | 用**独立** `new Random(chunkZ*155557723L + chunkX*45555558379L)` 把 `info.streetType` 重写为 `NORMAL` 或 `FULL`（`values()[rnd.nextInt(0, len-2)]`），并**回写 `info.streetType`** | V2 不能用 V1 的 NORMAL/FULL 语义（FULL 会走 `parts.full()` = 单幅 16×16 旧宽街） | V2 路格必须让该分支不生效。最小手段：在 `generateStreet` 上 `@Inject(at=@At(value="INVOKE", target="...BuildingInfo.isHierarchicalOpen()Z"), cancellable=true)` **不可行**（`@Inject` 的 cancellable 只能取消整个方法，不能取消单个调用）。→ 结论：**必须在 `generateNormalStreetSection`/`generateFullStreetSection` 处拦截**，让 `streetType` 的取值无关紧要（见下行） |
| `generateFullStreetSection(BuildingInfo, int)` L1568，`private` | `getStreetParts(info)` → `parts.full()` → `generatePart(..., ROTATE_NONE, ..., HardAirSetting.VOID)` | **(a) 替换**：V2 不使用 `parts.full()` | `@Inject(method="generateFullStreetSection", at=@At("HEAD"), cancellable=true)`：V2 维度下 `ci.cancel()` 并渲染 V2 部件 |
| `generateStreetSlopeSection(BuildingInfo, int, Direction)` L1576，`private` | `parts.stair()` + `slopeDirection.getRotation()` | **(b) 抑制**：V2 首版无坡道 | 更好的做法是在 `BuildingInfo.getStreetSlopeDirection` 返回 null（一处搞定，同时让 `hasRoadConnection`/`generateStreet` 的坡道分支全部失效） |
| `generateNormalStreetSection(BuildingInfo, int)` L1584，`private` | 由 4×`hasStreetPartConnection` 得 `cnt`，按 0/1/2(直/弯)/3(T)/4(十字) 选 `parts.none()/end()/straight()/bend()/t()/all()` 并给 `Transform`；成功后 `generatePart(..., HardAirSetting.VOID)` + `generateMinorStreetConnectors` | **(a) 替换**：部件键必须是 `(本格 roadType, 四向边等级)` 而非 `cnt`；**这是 V2 形制的核心替换点** | `@Inject(method="generateNormalStreetSection", at=@At("HEAD"), cancellable=true)`：V2 维度下 `ci.cancel()`，用 `this.generatePart(info, v2Part, v2Transform, 0, height, 0, HardAirSetting.VOID)`（public 方法，无需 @Invoker；`getDriver()`/`computePalette` 也 public）。**这是最干净、最必要的注入点** |
| `getStreetParts(BuildingInfo)` L1671，`private static` | `switch (info.plannedRoadType) { PRIMARY→getLargeStreetParts(); TERTIARY→getTertiaryStreetParts(); default→getStreetParts(); }` | V2 需要**版本化的 V2 部件入口**，不复用 `StreetParts` | 若采用上一条 HEAD-cancel，则本方法在 V2 下不被调用，**可不改**。否则需 `@Inject(HEAD, cancellable)` 伪造一个 `StreetParts`（`StreetParts` 是 public record，可 `new`），但语义扭曲，不推荐 |
| `hasStreetPartConnection(BuildingInfo, BuildingInfo, boolean)` L1679，`private static` | `BuildingInfo.hasRoadConnection(info, adjacent)`；若本格是 PRIMARY，还要求 `adjacent.isPrimaryRoad()`（或 `bridgeConnection`） | **必须**换成"双侧 V2 mask 均有边 + 边等级一致" | `@Inject(at=HEAD, cancellable=true)`；或在 `BuildingInfo.hasRoadConnection` 里统一改（§3.6） |
| `generateMinorStreetConnectors(BuildingInfo, StreetParts, int)` L1651 / `generateMinorStreetConnector(...)` L1661 | 若 `info.isPrimaryRoad() && !parts.connector().isEmpty()`，对四邻中 `hasRoadConnection && !adjacent.isPrimaryRoad()` 的方向叠一条单列 `connector` 部件 | **(b) 抑制**：`道路形制与方块.md` §3 明确"不能沿用现有宽街只叠一条单列 connector" | `@Inject(method="generateMinorStreetConnectors", at=@At("HEAD"), cancellable=true)`，V2 维度直接 `ci.cancel()`（一处即可，避免四次调用各判一次） |
| `generateRandomVegetation(BuildingInfo, int)` L1420，`private` | 若四邻中有建筑，则在**贴邻建筑的内侧条带**（宽 `THICKNESS_OF_RANDOM_LEAFBLOCKS`）铺随机树叶；用 `ThreadLocal<Random>`，种子 `seed*377 + chunkZ*341873128712L + chunkX*132897987541L` | **(b) 抑制/收窄**：V2 PRIMARY 的 2 格步行带会被树叶侵占 | `@Inject(method="generateRandomVegetation", at=@At("HEAD"), cancellable=true)`，V2 路格 `ci.cancel()`（首版最简）；后续若保留绿化，需改成"仅非车行/非路缘列"的自定义实现 |
| `generateFrontPart(BuildingInfo, int, BuildingInfo, Transform)` L1411，`private` | `info.hasFrontPartFrom(adj)` 时把 `adj.frontType` 在街道格上生成（`adj` 相邻建筑的前饰） | **(c) 首版保留，但必须审计**：`道路形制与方块.md` §3 要求"front part 不得变成车道或堵车行口" | 首版不改；V2 上线前需按 CityStyle 预检 `frontType` 的 16×16 占用是否落在 V2 车行面/端口列。若冲突再 `@Inject(HEAD, cancellable)` 抑制 |
| `generateBorders(BuildingInfo, boolean, ChunkHeightmap)` L1240，`private` | 先按 `LANDSCAPE_TYPE` 填基座（`fillToBedrockStreetBlock`/`fillMainStreetBlock`/`fillToGroundStreetBlock`），再对四边按 `doBorder` 决定是否 `generateBorder`（L1329）；`generateBorder` 用 `borderNeedsConnectionToAdjacentChunk`（L1691）决定是否在 `cityGroundLevel+1` 放墙（`connectsToAdjacentChunk ? 0 : 1`，L1336） | **(c) 基本保留**；但 V2 必须验证"计划开放端口的 16 列没有补墙" | 不改。风险点：`doBorder`（L2109）只在 `isHigherThenNearbyStreetChunk` 或"邻格非城市且层级更低"时补边。**相同 cityLevel 的两个城市街区之间 `doBorder` 返回 false → 不补墙**（正确）；但 V2 若让道路接到更高 `cityLevel` 的邻格，`doBorder` 会返回 true → 在 V2 端口列上补墙。→ V2 首版禁止跨层级边正好回避此问题 |
| `generateStreetDecorations(BuildingInfo)` L983，`private` | 若 `info.getActualStairDirection() != null`，在 `cityGroundLevel+1` 生成 `stairType`（`Transform` 按方向） | **(b) 抑制**（V2 路格上不该出现通往 +1 层的楼梯）；`calculateStairDirection`（L1479）只看 `cityLevel` 差与 `isCity/!hasBuilding`，**V2 不改 cityLevel，所以只要邻格高一层就会触发** | `@Inject(method="generateStreetDecorations", at=@At("HEAD"), cancellable=true)`；或在 `BuildingInfo.getActualStairDirection` 上按 V2 返回 null（影响面更大，谨慎） |
| `generateParkSection(BuildingInfo, int, boolean)` L1498，`private` | 用 `GenerationContext.current().street()`（L1515，由 `generate()` L294 `setStreet(cityStyle.getStreetBlock())` 设置）与 `getGrassBlock()`/`getParkElevationBlock()` 铺公园；边缘按 `parkBorder` | **(c) 保留** | V2 无路格走 `hierarchicalOpen==true` → `streetType == PARK`（L925）→ 本方法。**必须保留**，否则 V2 空地块会露出裸地 |
| `generatePart(BuildingInfo, IBuildingPart, Transform, int, int, int, HardAirSetting)` L1747，**public** | 遍历 `part.getXSize()/getZSize()`，`rx = ox + transform.rotateX(x, z)`、`rz = oz + transform.rotateZ(x, z)`；取 `compiledPalette.get(c)`；**L1775–1777：`if (transform != Transform.ROTATE_NONE) b = transformBlockState(transform, b);`**；处理 `hardAir`（`AIR`/`WATERLEVEL`/`VOID`）、torch、loot、spawner、blockentity、Poi/lighting todo；返回 `oy + part.getSliceCount()` | **(c) 保留**（V2 直接复用） | 不改。**答案：是的，它旋转 `BlockState`，但有条件** → 见 §5.3 |
| `transformBlockState(Transform, BlockState)` L1957，**public** | `if (Tools.hasTag(b.getBlock(), LostTags.ROTATABLE_TAG)) b = b.rotate(transform.getMcRotation()); else if (getRailStates().contains(b)) { ...RailShape... }` | V2 若使用 stairs/doors 之外的朝向方块（墙、栅栏、活板门、告示牌、藤蔓、glazed 等）会**不被旋转** | `LostTags.ROTATABLE_TAG` 的定义（`datagen/LCBlockTags.java:47`）**只有** `BlockTags.STAIRS` + `BlockTags.DOORS`。→ 附属模组应 `@Inject(method="transformBlockState", at=@At("HEAD"), cancellable=true)` 追加 V2 相关方块的旋转（public 方法，签名 `(Lmcjty/lostcities/worldgen/lost/Transform;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;`） |
| `borderNeedsConnectionToAdjacentChunk(BuildingInfo, int, int)` L1691，`private` | 对位于该边的每个方向：邻格坡道部件 `stair()` 的 `META_Z_1/Z_2` 列范围、邻格 `getActualStairDirection()` 的 `stairType` 列范围、`adjacent.hasBridge(provider, orientation) != null` 三者之一命中 → 该列不需要墙 | **(c) 基本保留**；V2 桥头若走 `hasXBridge/hasZBridge`，此方法自动放行桥头列 | 不改（前提是 §3.6 的 `hasXBridge` 返回 V2 deck part） |
| 桥面高度选择 | `gen/Bridges.generateBridge` L36，`private static`：`int bridgeLevel = info.provider.getStreetGenerationMode() == HIERARCHICAL_GRID_V1 ? info.profile.GROUNDLEVEL : info.profile.GROUNDLEVEL + 1;`（L41–43） | V2 必须走 `GROUNDLEVEL`（与 V2 主干端口同层） | 见 §6.3 |

### 5.3 `generatePart` 与旋转：精确结论

- `generatePart` **确实**对每个非空方块调用 `transformBlockState`，但**仅当 `transform != ROTATE_NONE`**（L1775）且**仅当方块在 `lostcities:rotatable` 内或是轨道**（L1958–1971）。
- `lostcities:rotatable` = `#minecraft:stairs` + `#minecraft:doors`（`datagen/LCBlockTags.java:47-49`）。`minecraft:walls`、`fences`、`fence_gates`、`trapdoors`、`iron_bars`、`ladders`、`glazed_terracotta`、`signs`、`banners`、`vines`、`observer`、`piston`、`stairs` 之外的台阶类**都不旋转**。
- 对 V2 的后果：`道路形制与方块.md` §2 的候选材料（`gray_concrete`/`light_gray_concrete`/`polished_andesite`/`smooth_stone`/`cut_sandstone`）**都是无朝向方块，不受影响**；但**路缘台阶/栏杆/栅栏/墙**一律不旋转。方案 §6 里"不能说 `generatePart` 已旋转 BlockState 就以为所有材料都安全"的要求，在源码层得到确认：**只有 stairs/doors 会转**。
- 两个可行对策（都不需要 `@Redirect`）：
  1. 只用无朝向材料 + 每朝向单独 JSON 部件（`道路形制与方块.md` §3 的"为不同朝向单独制作部件"）。
  2. `@Inject(method="transformBlockState", at=@At("HEAD"), cancellable=true)` 追加旋转（推荐，因为它是 public 且可以拿到 `transform`）。

### 5.4 桥段选择（街道部件侧）

`generateNormalStreetSection` L1586–1589：

```java
        boolean xmin = hasStreetPartConnection(info, info.getXmin(), info.getXmin().hasXBridge(provider) != null);
        boolean xmax = hasStreetPartConnection(info, info.getXmax(), info.getXmax().hasXBridge(provider) != null);
        boolean zmin = hasStreetPartConnection(info, info.getZmin(), info.getZmin().hasZBridge(provider) != null);
        boolean zmax = hasStreetPartConnection(info, info.getZmax(), info.getZmax().hasZBridge(provider) != null);
```
配合 `hasStreetPartConnection`（L1679）：当本格是 PRIMARY 时，只有 `adjacent.isPrimaryRoad()` 或 `bridgeConnection` 才算连接。→ **V2 桥头要维持"直线主干断面"，必须让 `info.getXmin().hasXBridge(provider)` 在 V2 span 上非 null**（这样 `bridgeConnection=true`，即使对岸不是 `isPrimaryRoad` 也能给出连接边）。而 `hasXBridge` 的 V1 实现会回头调用 `BuildingInfo.getBuildingInfo(planned.minimumEndpoint())`——V2 版必须不这样做。

---

## 6. `HierarchicalBridgePlanner` 与 `gen/Bridges`

### 6.1 `HierarchicalBridgePlanner` 全类要点（`worldgen/street/HierarchicalBridgePlanner.java`，146 行）

```java
public final class HierarchicalBridgePlanner {                              // L16
    private static final long BRIDGE_SPAN_SALT = 0x31d6a7f04c82be59L;       // L18
    private static final long BRIDGE_INTERSECTION_SALT = 0x67a14ce3b9052df8L; // L19
    private HierarchicalBridgePlanner() { }                                 // L21 私有构造，不可实例化

    public static PlannedBridgeInfo getBridgeInfo(BuildingInfo source, Orientation orientation) {   // L24
        if (source.provider.getStreetGenerationMode() != StreetGenerationMode.HIERARCHICAL_GRID_V1
                || source.isCity || !isPrimaryForOrientation(source.provider, source.coord, orientation)) {
            return null;                                                    // L26-28 候选：非城市格 + 沿轴 PRIMARY
        }
        LostCityProfile profile = source.profile;
        if (!isGapChunk(source, source.coord, orientation)) return null;     // L30-32 本格必须是"水隙"

        int maximumLength = profile.PLANNED_PRIMARY_BRIDGE_MAX_LENGTH;      // L34 默认 12
        ChunkCoord minimumEndpoint = findEndpoint(source, source.coord, orientation, false, maximumLength);  // L35
        if (minimumEndpoint == null) return null;
        ChunkCoord maximumEndpoint = findEndpoint(source, source.coord, orientation, true, maximumLength);   // L39
        if (maximumEndpoint == null) return null;

        int gapLength = maximumEndpoint.getCoord(orientation) - minimumEndpoint.getCoord(orientation) - 1;   // L44
        if (gapLength < 1 || gapLength > maximumLength) return null;

        BuildingInfo minimumInfo = BuildingInfo.getBuildingInfo(minimumEndpoint, source.provider);   // L49  ← 递归进 BuildingInfo！
        BuildingInfo maximumInfo = BuildingInfo.getBuildingInfo(maximumEndpoint, source.provider);   // L50
        if (!minimumInfo.isPrimaryRoad() || !maximumInfo.isPrimaryRoad()
                || minimumInfo.cityLevel != 0 || maximumInfo.cityLevel != 0) {                        // L53-54 两端必须零层有效主干
            return null;
        }
        long id = spanHash(source.provider, orientation, minimumEndpoint, maximumEndpoint, BRIDGE_SPAN_SALT);  // L58
        if (unitDouble(id) >= profile.PLANNED_PRIMARY_BRIDGE_CHANCE) return null;                    // L59 默认 1.0 → 必过
        return new PlannedBridgeInfo(id, orientation, minimumEndpoint, maximumEndpoint, gapLength);  // L62
    }
```

辅助（全部 `private static`）：`findEndpoint`（L65，沿 `Orientation` 正/负方向最多 `maximumLength+1` 步，要求途中每格 `isGapChunk`、终点 `isRawPrimaryCityEndpoint`）、`isRawPrimaryCityEndpoint`（L80，要求 `isCityRaw` **且** `getStreetPlanner().getRoadType(...) == PRIMARY` → **Q3**）、`isGapChunk`（L86，要求 `!isCityRaw`、沿轴 primary、交点方向裁决、`isWaterBiome || heightmap.getHeight() < source.waterLevel`）、`isPrimaryForOrientation`（L105 → **Q5**）、`preferredIntersectionOrientation`（L112）、`spanHash`（L117）、`unitDouble`/`stableStringHash`/`mix64`。

**轴选择规则**：`Orientation.X` 走 `isHorizontalPrimary(chunkZ)`，`Orientation.Z` 走 `isVerticalPrimary(chunkX)`（L107–109）；X/Z 交点（`isHorizontalPrimary(z) && isVerticalPrimary(x)`）只允许 `preferredIntersectionOrientation`（seed ⊕ 维度 ID ⊕ salt 的 bit0，L113–114）那条轴（L93–97）。

`PLANNED_PRIMARY_BRIDGE_*` 定义在 `config/LostCityProfile.java`：
```java
    public float PLANNED_PRIMARY_BRIDGE_CHANCE = 1.0f;      // L196
    public int PLANNED_PRIMARY_BRIDGE_MAX_LENGTH = 12;      // L197
```
配置读入 L415/L417，校验 L477（`chance ∈ [0,1]`、`length ∈ [1,64]`）。
`PlannedBridgeInfo`（`street/PlannedBridgeInfo.java:7`）：`public record PlannedBridgeInfo(long id, Orientation orientation, ChunkCoord minimumEndpoint, ChunkCoord maximumEndpoint, int gapLength)`。

**对 V2 的含义**：
- V1 桥的全部候选逻辑都绑在"`getStreetPlanner()` 的 PRIMARY 数学网格"上（L83/L106）。V2 若在 planner 上做 HEAD 委派，`isVerticalPrimary`/`isHorizontalPrimary` 这两个 **V1 专有**方法**不会**被委派（方案 §5 要求候选在选线之前独立确定，且"候选函数只在确定性稀疏桥廊扫描"，即**每个 X/Z 固定坐标的 8 格带**，与 V1 的 PRIMARY 网格不同）。因此 V2 桥不能用 `HierarchicalBridgePlanner`。
- `getBridgeInfo` 自身会 `BuildingInfo.getBuildingInfo(...)`（L49/L50）→ 违反方案 §3 的"规划函数里不能调用最终 `BuildingInfo`"。V2 span 候选必须是纯函数。
- V2 若仍走本类，需要 `@Inject(method="getBridgeInfo", at=@At("HEAD"), cancellable=true)` 提前返回 V2 的 `PlannedBridgeInfo`——但 `PlannedBridgeInfo` 的语义是"V1 主干延长"，且下游 `BuildingInfo.calculateXBridge` 只把它当"取哪个 endpoint 的 `bridgeType`"，对 V2 无意义。

### 6.2 `gen/Bridges`：桥面放置与 GROUNDLEVEL vs GROUNDLEVEL+1

```java
public class Bridges {
    public static void generateBridges(LostCityTerrainFeature feature, BuildingInfo info) {   // L19
        if (info.getHighwayXLevel() == 0 || info.getHighwayZLevel() == 0) return;              // L20-24
        BuildingPart bt = info.hasXBridge(info.provider);                                      // L25
        if (bt != null) { generateBridge(feature, info, bt, Orientation.X); }                  // L26-27
        else { bt = info.hasZBridge(info.provider); if (bt != null) generateBridge(feature, info, bt, Orientation.Z); }  // L28-33
    }

    private static void generateBridge(LostCityTerrainFeature feature, BuildingInfo info, BuildingPart bt, Orientation orientation) {  // L36
        CompiledPalette compiledPalette = feature.computePalette(info, bt);
        ChunkDriver driver = feature.getDriver();
        // Legacy bridge parts were authored one block above the old street
        // surface. Wide planned bridges share the large-street surface level.
        int bridgeLevel = info.provider.getStreetGenerationMode() == StreetGenerationMode.HIERARCHICAL_GRID_V1
                ? info.profile.GROUNDLEVEL
                : info.profile.GROUNDLEVEL + 1;                                                 // L41-43
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                driver.current(x, bridgeLevel, z);
                int l = 0;
                while (l < bt.getSliceCount()) {
                    Character c = orientation == Orientation.X ? bt.getPaletteChar(x, l, z) : bt.getPaletteChar(z, l, x);  // L49  @todo general rotation system?
                    ...
                    driver.add(b); l++;
                }
            }
        }
        // 支撑：bt 的 META_SUPPORT，否则 CityStyle.getBridgeSupport / WorldStyle.getBridgeSupport
        // supportPartName 路径走 Supports.generatePart(feature, info, supportPart, transform, bridgeLevel - 1)   L93
        // 纯方块支撑则从 waterLevel-10 到 groundLevel 在 (7,7)(7,8)(8,7)(8,8) 四列填充          L95-100
        // 若 minDir/maxDir 没有桥（即桥的端点侧），在 GROUNDLEVEL 层铺连接带                   L103-134
    }
}
```

**GROUNDLEVEL vs GROUNDLEVEL+1 的差别（精确）**：唯一的差别就是 `bridgeLevel` 这一个局部变量（L41–43）。`GROUNDLEVEL` 用于 `HIERARCHICAL_GRID_V1`（"宽计划桥与宽街表面同层"），`GROUNDLEVEL+1` 用于 LEGACY（"旧桥部件按旧街面上方一格绘制"）。**注意**：桥体从 `bridgeLevel` 起向上 `bt.getSliceCount()` 层；支撑件从 `bridgeLevel - 1` 向下；而 L107/L113/L123/L129 的"侧面连接带"**硬编码**使用 `info.profile.GROUNDLEVEL`（不是 `bridgeLevel`），所以 V1 在 `GROUNDLEVEL` 时两者一致。

**V2 span 必须喂入的东西**（据此才能让 `gen/Bridges` 复用而不改）：
1. `BuildingInfo.hasXBridge/hasZBridge` 在 V2 span 覆盖的格上返回 **V2 deck `BuildingPart`**（非 null 才会进 `generateBridge`）。
2. 该格必须是"非城市格"（`generateBridges` 只在 `doNormalChunk` 被调用，L429；城市格走 `doCityChunk` 不会铺桥面）。
3. 高度：`bridgeLevel` 必须等于 V2 主干端口所在层。V2 首版只允许 `cityLevel == 0` 的端点（同 `HierarchicalBridgePlanner` L53–54 的限制），此时 `profile.GROUNDLEVEL == info.getCityGroundLevel()`，而 L41–43 在 `HIERARCHICAL_GRID_V1` 下正好给 `GROUNDLEVEL`。**结论：只要 V2 维度的持久模式仍是 `HIERARCHICAL_GRID_V1`（见 §7），`gen/Bridges` 的高度分支天然正确，无需注入。** 这是"V2 模式必须复用 HGV1 持久值"的第二个硬依赖。
   若要显式加固（防止未来 TLC 改动或模式值变化），可用 `@WrapOperation` 包装 `generateBridge` 内的 `info.provider.getStreetGenerationMode()` **调用点**（`At(value="INVOKE", target="Lmcjty/lostcities/worldgen/IDimensionInfo;getStreetGenerationMode()Lmcjty/lostcities/config/StreetGenerationMode;")`，限定 `method="generateBridge"`），V2 维度下返回 `HIERARCHICAL_GRID_V1`。**注意**：这是 `@WrapOperation` 而不是 `@Redirect`，且不改任何局部变量，符合约束。
   不能用 `@ModifyVariable` 改 `bridgeLevel`（不在允许列表内）；也不能 `@Inject(HEAD, cancellable)` 重写整个 `generateBridge`（约 100 行重复上游）。
4. `Orientation` 的旋转：L49 **没有**走 `transformBlockState`，只是"X 轴用 `(x,l,z)`、Z 轴用 `(z,l,x)`"的朴素转置（源码自带 `// @todo general rotation system?`）。→ V2 的桥面部件必须**为 X/Z 两个朝向各备一份（或保证转置等价）**，不能依赖 `generatePart` 的旋转。
5. 支撑语义：`bt.getMetaChar(ILostCities.META_SUPPORT)` → CityStyle/WorldStyle `getBridgeSupport`/`getBridgeSupportPart`；V2 只要沿用同一 meta 约定即可复用。

---

## 7. `LostCityWorldGenData` 与模式持久化

文件：`worldgen/LostCityWorldGenData.java`（157 行）

### 7.1 存储位置与读写

```java
public class LostCityWorldGenData extends SavedData {                              // L25
    public static final String NAME = "LostCityWorldGenData";                      // L27
    private static final String NEW_WORLD_KEY = "newWorldStreetModes";             // L28
    private static final String NEW_WORLD_HIGHWAY_KEY = "newWorldHighwayModes";    // L29
    private static final String STREET_MODES_KEY = "streetModes";                  // L30
    private static final String HIGHWAY_MODES_KEY = "highwayModes";                // L31

    private boolean newWorldStreetModes;                                           // L33
    private boolean newWorldHighwayModes;                                          // L34
    private final Map<String, StreetGenerationMode> streetModes = new HashMap<>(); // L35
    private final Map<String, HighwayGenerationMode> highwayModes = new HashMap<>();// L36
```

- `public static LostCityWorldGenData get(ServerLevel level)`（L75）：**总是取 overworld 的 `DimensionDataStorage`**，`storage.computeIfAbsent(LostCityWorldGenData::new, LostCityWorldGenData::new, NAME)`（L81）→ 不存在时返回"默认构造实例"，即 `newWorldStreetModes == false`（L38–43 注释明确：默认构造 = 磁盘上无此 SavedData = 旧世界）。
- 反序列化（L45–72）：`streetModes` 是 `CompoundTag` 的 `Map<String /*dimension.location().toString()*/, String /*enum name*/>`；未知名字记 error 并回落 `LEGACY`（L54–57）。
- 序列化（L146–156）：写两个 bool 与两个 string map。

### 7.2 模式解析

```java
    public synchronized StreetGenerationMode getStreetMode(ResourceKey<Level> dimension,
                                                             StreetGenerationMode requestedMode) {   // L97
        return getStreetMode(dimension.location().toString(), requestedMode);
    }

    synchronized StreetGenerationMode getStreetMode(String dimensionId, StreetGenerationMode requestedMode) {  // L102 包级
        StreetGenerationMode persisted = streetModes.get(dimensionId);
        if (persisted != null) return persisted;                                 // L104-106
        StreetGenerationMode selected = resolveUnpersistedMode(newWorldStreetModes, requestedMode);   // L107
        if (newWorldStreetModes) {                                               // L108
            streetModes.put(dimensionId, selected);                              // L109  ← 首次调用即落盘
            setDirty();                                                          // L110
        }
        return selected;
    }

    /** Pure compatibility rule, exposed for focused tests. */
    public static StreetGenerationMode resolveUnpersistedMode(boolean initializedAsNewWorld,
                                                               StreetGenerationMode requestedMode) {   // L116
        return initializedAsNewWorld ? requestedMode : StreetGenerationMode.LEGACY;   // L118
    }
```

**`resolveUnpersistedMode` 语义**：旧世界（`initializedAsNewWorld == false`）**一律 LEGACY**，与 profile 请求值无关，也**不写入** map；新世界才采用请求值并持久化。新世界标记由 `LevelEvent.CreateSpawnPosition`（`setup/ForgeEventHandlers.java:132-139`）里的 `LostCityWorldGenData.initializeNewWorld(serverLevel)` 落定，并紧接 `LostCityFeature.globalDimensionInfoDirtyCounter++`（L142）强制重建 `IDimensionInfo`。

### 7.3 附属模组如何在不碰 TLC 枚举的前提下持久化 V2 opt-in

**结论：完全可行，且不需要改 TLC 的任何存档协议。**

1. **自己的 SavedData**：`ServerLevel.getDataStorage()` 是 public；`DimensionDataStorage` 提供 `computeIfAbsent(...)` 重载（1.20.1 上是 `Function<CompoundTag,T> loader, Supplier<T> factory, String name` 三参形式；TLC 自己在 `LostCityWorldGenData.get` 的 L81 就用的是 `computeIfAbsent(LostCityWorldGenData::new, LostCityWorldGenData::new, NAME)`，可直接照抄该调用形状）。建议放在 overworld（与 TLC 同处）或按维度分别放；键用 `dimension.location().toString()`（与 TLC 一致的写法，注意 `ResourceKey.location()` 对 `minecraft:overworld` 给出 `"minecraft:overworld"`）。
   `CitylinesRoadData` 应存：维度 ID → `{ v2Enabled, plannedAlgorithmVersion, planSalt, profileFingerprint, assetFingerprint, bridgeEnabled, createdAtWorldVersion }`（方案 §3 的"seed、维度 ID、V2 算法版本"与 §6 的"计划版本、配置/资产指纹"）。
2. **"这个维度已经有生成过的区块"的检测**（方案 §6：V2 只允许在"确认无旧区块"的维度 opt-in）。可用的**公共**入口，按可靠性排序：
   - **TLC 自己的新世界标记**：`LostCityWorldGenData.get(serverLevel).getStreetMode(dimension, requestedMode)`。旧世界返回 `LEGACY`；新世界返回请求值。**注意副作用**：新世界 + 该维度尚未持久化时，这次调用会**写入并 `setDirty()`**（L108–110）。若 `requestedMode` 传的是 `profile.STREET_GENERATION_MODE`（即 `DefaultDimensionInfo` 构造器 L61 会传的同一个值），则结果与上游首次生成时完全一致 → **幂等、无行为分叉**，可以安全用作探测。**绝不要**传一个"我们希望持久化的别的值"做探测。
   - **区块是否已生成**：`ServerLevel` 继承的 `Level.hasChunkAt(BlockPos)`（public）与 `ServerLevel.getChunkSource().getChunkNow(int,int)`（`ChunkSource` API，public，返回 `@Nullable ChunkAccess`）可以判定"某坐标是否已有区块"。对"整个维度是否已有区块"没有 O(1) 公共 API；务实做法是探测出生点/若干代表性坐标，或**直接以'本模组 SavedData 首次创建时该维度尚无区块'为充分条件，并在后续加载时若发现既有区块则拒绝启用**（fail-fast）。
   - **不要**尝试枚举 region 文件目录（需要反射或平台相关路径）。
3. **运行时状态不要落在静态表里**：把 V2 planner 挂在 `DefaultDimensionInfo` 的 `@Unique` 字段上，`LostCityFeature.cleanUpInternal()` → `dimensionInfo.clear()` 后自然失效。若必须用静态注册表（§2.5 的 planner→sink 映射），则用 `WeakHashMap` 并监听 `LostCityFeature.globalDimensionInfoDirtyCounter`（public static volatile）变化清理。
4. **`StreetGenerationMode` 不能扩展**：附属模组不得调用 `StreetGenerationMode.byName("CONNECTED_DISTRICT_V1")`（会抛 `IllegalArgumentException`，`config/StreetGenerationMode.java:13-19`），也不得把 V2 名字写进 `streetModes` map（下次加载会在 L54–57 记 error）。V2 维度在 TLC 眼中**必须仍然是 `HIERARCHICAL_GRID_V1`**。
5. **为什么"必须仍是 HGV1"是硬依赖**（两处已确认）：
   - `BuildingInfo` 构造器 L865 的 `hierarchicalOpen` 与 L998 的 `largeBridgeType`、L1407 的坡道、L1565/L1637 的桥判定全部 `== HIERARCHICAL_GRID_V1`；
   - `gen/Bridges` L41–43 的桥面高度。
   如果 V2 维度落到 `LEGACY`，`hierarchicalOpen` 恒 false → 空地走 L930 分支，**随机消耗与 V1-HGV1 不同**，且桥面会抬高 1 格。
6. **LC2H 的覆盖路径**：LC2H 4.2.4-LTS 的 `MixinDefaultDimensionInfoThreadLocal` 用 `@Overwrite` 重写 `getStreetGenerationMode()`，内部调用 `org.admany.lc2h.worldgen.lostcities.LostCitiesStreetModePolicy.resolve(upstreamMode, profileName)`（已从 jar 常量池确认方法签名与串 `DEFAULT_MODE`/`CHAOS_Z_PACK_*`；其 `DEFAULT_MODE` 常量值为 `HIERARCHICAL_GRID_V1`）。这意味着**附属模组不能假设 `provider.getStreetGenerationMode()` 一定等于 TLC 持久值**；判断"TLC 持久模式"应优先用 `LostCityWorldGenData.get(level).getStreetMode(dim, profile.STREET_GENERATION_MODE)`（不经过 `IDimensionInfo`），或用构造器参数里传进来的值。

---

## 8. 诊断：`CommandDebug` 与 `ModCommands`

### 8.1 注册方式（`commands/ModCommands.java`）

```java
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {      // L21
        LiteralCommandNode<CommandSourceStack> commands = dispatcher.register(
                Commands.literal(LostCities.MODID)                                          // L23  "lostcities"
                        .then(CommandCreateBuilding.register(dispatcher))
                        .then(CommandDebug.register(dispatcher))
                        ... (共 13 个子命令)
        );
        dispatcher.register(Commands.literal("lost").redirect(commands));                   // L39  别名 /lost
        ResetChunksCommand.register(dispatcher);                                            // L40
    }
```
`CommandDebug.register`（L34–38）：`Commands.literal("debug").requires(cs -> cs.hasPermission(0)).executes(CMD)` → 即 `/lostcities debug` 与 `/lost debug`。

### 8.2 输出如何产生（`commands/CommandDebug.java:41-130`）

```java
    public int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();                  // L44
        BlockPos position = player.blockPosition();
        IDimensionInfo dimInfo = Registration.LOSTCITY_FEATURE.get().getDimensionInfo((WorldGenLevel) player.level());  // L46
        if (dimInfo != null) {
            ChunkCoord coord = new ChunkCoord(dimInfo.getType(), position.getX() >> 4, position.getZ() >> 4);
            BuildingInfo info = BuildingInfo.getBuildingInfo(coord, dimInfo);               // L49
            System.out.println("profile = " + info.profile.getName());
            ... buildingType / multiBuilding / floors / floorsBelowGround / cityLevel / cityGroundLevel / isCity ...
            System.out.println("getCityStyle() = " + BuildingInfo.getChunkCharacteristics(info.coord, info.provider).cityStyle.getName());
            System.out.println("streetType = " + info.streetType);
            PlannedStreetInfo planned = dimInfo.getStreetPlanner().getStreetInfo(info.coord.chunkX(), info.coord.chunkZ());  // L63  ← Q6
            System.out.println("streetGenerationMode = " + dimInfo.getStreetGenerationMode());
            System.out.println("rawPlannedRoadType = " + planned.roadType());               // L65
            System.out.println("effectivePlannedRoadType = " + info.plannedRoadType);       // L66
            System.out.println("plannedRoadConnections = N:" + planned.north() + ...);       // L67-68
            System.out.println("primaryBlock = " + planned.primaryBlockX() + "," + planned.primaryBlockZ());        // L69
            System.out.println("primaryBlockBounds = " + planned.primaryWestX() + "," + planned.primaryNorthZ()
                    + " -> " + planned.primaryEastX() + "," + planned.primarySouthZ());     // L70-71
            System.out.println("secondaryRoadsX = " + planned.secondaryRoadsX());
            System.out.println("secondaryRoadsZ = " + planned.secondaryRoadsZ());
            System.out.println("streetDensity = " + planned.density());
            System.out.println("tertiarySegment = " + planned.tertiarySegment());
            System.out.println("multiBuildingStreetConflict = " + info.profile.MULTI_BUILDING_STREET_CONFLICT);
            System.out.println("multiBuildingSuppressedRoad = " + (info.rawPlannedRoadType != info.plannedRoadType
                    && info.multiBuildingPos.isMulti()));                                   // L77-78
            PlannedBridgeInfo plannedBridgeX = HierarchicalBridgePlanner.getBridgeInfo(info, Orientation.X);   // L79
            PlannedBridgeInfo plannedBridgeZ = HierarchicalBridgePlanner.getBridgeInfo(info, Orientation.Z);   // L80
            System.out.println("plannedPrimaryBridgeX = " + plannedBridgeX);
            System.out.println("plannedPrimaryBridgeZ = " + plannedBridgeZ);
            String finalContent = !info.isCity ? "NORMAL_TERRAIN"
                    : info.hasBuilding ? (info.multiBuildingPos.isMulti() ? "MULTI_BUILDING" : "BUILDING")
                    : info.isPlannedRoad() ? "PLANNED_ROAD"
                    : info.isPredefinedStreet() ? "PREDEFINED_STREET"
                    : info.isHierarchicalOpen() ? (info.parkType == null ? "OPEN_LOT" : "PARK")
                    : "LEGACY_STREET_OR_PARK";                                             // L83-88
            System.out.println("finalCityContent = " + finalContent);
            ... ruinHeight / tunnel0 / tunnel1 / highway levels / highway mode / highway network dump /
                reldist / railInfo / sphere / explosions / heightmap / 若干 profile 常量 / isOcean
        }
        return 0;
    }
```
高速公路子诊断在 `dumpHighwayNetworkDebug`（L133–155），模式为 `INTERCITY_NETWORK_V1` 时额外打印 planning cell、hub、connections、routes、cache stats。

### 8.3 附属模组加 `/citylines roads` 的可行做法

- **注册**：监听 Forge 的 `net.minecraftforge.event.RegisterCommandsEvent`（forge bus），`event.getDispatcher().register(...)`；不要尝试往 TLC 的 `ModCommands` 里插命令（那是 `dispatcher.register` 的静态流程，且会与 LC2H 争用）。
- **取数据**：`Registration.LOSTCITY_FEATURE.get().getDimensionInfo((WorldGenLevel) player.level())` → 与 worldgen 同实例；`BuildingInfo.getBuildingInfo(coord, dimInfo)` → 与该区块生成时相同的实例（`BUILDING_INFO_MAP` 缓存，`BuildingInfo.java:160`）。**注意**：这些调用会**触发区块特征计算**（`getCityLevel`/`isCityRaw` 有真实高度/biome 查询），诊断命令里调用是可接受的，但不要在生成热路径里这样做。
- **应当对齐的字段**（V1 等价项）：`streetGenerationMode`、`rawPlannedRoadType`、`effectivePlannedRoadType`、`plannedRoadConnections`、`primaryBlock/Bounds`、`secondaryRoads*`、`streetDensity`、`tertiarySegment`、`multiBuildingStreetConflict`、`multiBuildingSuppressedRoad`、`plannedPrimaryBridgeX/Z`、`finalCityContent`。
- **V2 必须新增的字段**（方案 §8.2 与"调试信息应能区分不可行输入与算法错误"）：`v2PlanVersion`、`v2OptIn`（是否启用）、`districtId (floorDiv)`、`centreClass`、四向 `edgeClass`、四向 `portSignature`、`gateList`（规范化门）、`bridgeSpans`（含 `P_A`/`P_B` 双侧裁决结果）、`terminalClassification` 计数（`CITY_EDGE/OBSTACLE/HEIGHT_UNREACHABLE/WATER_NO_BRIDGE/ASSET_UNSUPPORTED/EXTERNAL_VETO/OVERLAY_VETO/BUDGET_EXCEEDED/OLD_NEW_BOUNDARY`）、缓存命中/构建计数。
- **禁止**：`CommandDebug` 直接打印到 `System.out`（见 `@SuppressWarnings("UseOfSystemOutOrSystemErr")` L41）；附属模组应发到命令源（`context.getSource().sendSuccess(...)`）或 logger，避免污染日志与专用服务器控制台。

---

## 9. 全部相关读取点（全树搜索）

搜索范围：`reference/lostcities-src/src/main/java/mcjty/lostcities/**/*.java`（含 `api/`、`gui/`、`commands/`、`setter/` 等全部包）。

### 9.1 `getStreetPlanner()`

| file:line | 代码 |
| --- | --- |
| `worldgen/IDimensionInfo.java:41` | `HierarchicalStreetPlanner getStreetPlanner();`（声明） |
| `worldgen/DefaultDimensionInfo.java:138` | `public HierarchicalStreetPlanner getStreetPlanner() { return streetPlanner; }` |
| `gui/NullDimensionInfo.java:185` | 同上（GUI/预览实现） |
| `worldgen/lost/BuildingInfo.java:408` | `PlannedStreetInfo rawStreet = provider.getStreetPlanner().getStreetInfo(chunkX, chunkZ);` |
| `worldgen/lost/MultiChunk.java:189` | `PlannedRoadType roadType = provider.getStreetPlanner().getRoadType(coord.chunkX(), coord.chunkZ());` |
| `worldgen/street/HierarchicalBridgePlanner.java:83` | `&& provider.getStreetPlanner().getRoadType(coord.chunkX(), coord.chunkZ()) == PlannedRoadType.PRIMARY;` |
| `worldgen/street/HierarchicalBridgePlanner.java:93` | `HierarchicalStreetPlanner planner = provider.getStreetPlanner();`（随后 `isHorizontalPrimary` + `isVerticalPrimary`） |
| `worldgen/street/HierarchicalBridgePlanner.java:106` | `HierarchicalStreetPlanner planner = provider.getStreetPlanner();`（随后 `isHorizontalPrimary`/`isVerticalPrimary`） |
| `commands/CommandDebug.java:63` | `PlannedStreetInfo planned = dimInfo.getStreetPlanner().getStreetInfo(info.coord.chunkX(), info.coord.chunkZ());` |

### 9.2 `PlannedRoadType` / `plannedRoadType` / `rawPlannedRoadType`

| file:line | 说明 |
| --- | --- |
| `worldgen/street/PlannedRoadType.java:3-11` | 枚举定义 + `strongest` |
| `api/LostChunkCharacteristics.java:17` | `public PlannedRoadType rawPlannedRoadType = PlannedRoadType.NONE;` |
| `api/LostChunkCharacteristics.java:18` | `public PlannedRoadType plannedRoadType = PlannedRoadType.NONE;` |
| `worldgen/lost/BuildingInfo.java:60` | `public final PlannedRoadType rawPlannedRoadType;` |
| `worldgen/lost/BuildingInfo.java:61` | `public final PlannedRoadType plannedRoadType;` |
| `worldgen/lost/BuildingInfo.java:409` | `characteristics.rawPlannedRoadType = rawStreet.roadType();` |
| `worldgen/lost/BuildingInfo.java:410` | `characteristics.plannedRoadType = getEffectivePlannedRoadType(...);` |
| `worldgen/lost/BuildingInfo.java:413` | 传入 `checkBuildingPossibility(..., characteristics.plannedRoadType, rand)` |
| `worldgen/lost/BuildingInfo.java:523` | `checkBuildingPossibility` 形参 `PlannedRoadType plannedRoadType` |
| `worldgen/lost/BuildingInfo.java:542` | `} else if (plannedRoadType != PlannedRoadType.NONE) { b = false; }` |
| `worldgen/lost/BuildingInfo.java:572-575,594` | `getEffectivePlannedRoadType` 签名 + 两次 `EffectiveStreetResolver.resolve(...)` |
| `worldgen/lost/BuildingInfo.java:815` | `rawPlannedRoadType = characteristics.rawPlannedRoadType;` |
| `worldgen/lost/BuildingInfo.java:864` | `plannedRoadType = isCity && !hasBuilding ? characteristics.plannedRoadType : PlannedRoadType.NONE;` |
| `worldgen/lost/BuildingInfo.java:865-866` | `hierarchicalOpen = ... && plannedRoadType == PlannedRoadType.NONE && !predefinedStreet;` |
| `worldgen/lost/BuildingInfo.java:939` | `fountainType = isPlannedRoad() ? null : selectedFountain;` |
| `worldgen/lost/BuildingInfo.java:1385-1391` | `isPlannedRoad()` / `isPrimaryRoad()` |
| `worldgen/lost/BuildingInfo.java:1453` | `isSameSlopeRoadClass`：`other.doesRoadExtendTo() && isPrimaryRoad() == other.isPrimaryRoad()` |
| `worldgen/lost/BuildingInfo.java:1793` | `doesRoadExtendTo`：`(isPlannedRoad() \|\| predefinedStreet)` |
| `worldgen/lost/MultiChunk.java:190` | `if (profile.MULTI_BUILDING_STREET_CONFLICT.roadBlocks(roadType)) return false;` |
| `worldgen/LostCityTerrainFeature.java:1652` | `if (!info.isPrimaryRoad() \|\| parts.connector().isEmpty()) return;` |
| `worldgen/LostCityTerrainFeature.java:1663` | `if (BuildingInfo.hasRoadConnection(info, adjacent) && !adjacent.isPrimaryRoad())` |
| `worldgen/LostCityTerrainFeature.java:1672-1675` | `getStreetParts`：`switch (info.plannedRoadType)` |
| `worldgen/LostCityTerrainFeature.java:1681,1686` | `hasStreetPartConnection` 的 PRIMARY 特判 |
| `worldgen/street/EffectiveStreetResolver.java:9` | 规则本体 |
| `config/LostCityProfile.java:221` | `MULTI_BUILDING_STREET_CONFLICT` 字段与默认值 |
| `config/MultiBuildingStreetConflict.java:7-18` | 枚举与 `roadBlocks` |
| `config/LostCityProfile.java:458,461` | 该配置项的读入 |
| `commands/CommandDebug.java:65,66,77,85` | 诊断读取 `planned.roadType()`、`info.plannedRoadType`、两者差、`isPlannedRoad()` |

### 9.3 `PlannedStreetInfo` / `PlannedBridgeInfo` / `TertiaryRoadSegment`

| file:line | 说明 |
| --- | --- |
| `worldgen/street/PlannedStreetInfo.java:5-34` | record 定义 + `isRoad()` + `connects()` |
| `worldgen/lost/BuildingInfo.java:22,408,573` | 导入 / 查询 / 形参 |
| `worldgen/street/HierarchicalStreetPlanner.java:61,68` | 构造 |
| `commands/CommandDebug.java:23,63,65,67,69,70,72,73,74,75` | 全部字段打印 |
| `worldgen/street/PlannedBridgeInfo.java:7-13` | record 定义 |
| `worldgen/lost/BuildingInfo.java:1566,1638` | `calculateXBridge`/`calculateZBridge` 读取 |
| `commands/CommandDebug.java:79-82` | 诊断打印 |
| `worldgen/street/TertiaryRoadSegment.java:4-11` | record + `contains` |
| `worldgen/street/HierarchicalStreetPlanner.java:83,277` | 计算/返回 |

### 9.4 `getStreetGenerationMode()`（每个都是"V2 感知/不感知"的判定点）

| file:line | 分支含义 |
| --- | --- |
| `worldgen/IDimensionInfo.java:35` / `DefaultDimensionInfo.java:133` / `NullDimensionInfo.java:180` | 声明/实现 |
| `worldgen/lost/BuildingInfo.java:407` | 是否填路网特征 |
| `worldgen/lost/BuildingInfo.java:865` | `hierarchicalOpen` |
| `worldgen/lost/BuildingInfo.java:907` | LEGACY 分支（街型/喷泉/公园的**历史随机消耗**） |
| `worldgen/lost/BuildingInfo.java:998` | `largeBridgeType` 解析 |
| `worldgen/lost/BuildingInfo.java:1407` | 坡道是否可用 |
| `worldgen/lost/BuildingInfo.java:1565` / `1637` | `calculateXBridge`/`calculateZBridge` 的 HGV1 分支 |
| `worldgen/lost/BuildingInfo.java:1792` | `doesRoadExtendTo` |
| `worldgen/lost/MultiChunk.java:188` | 多建筑路权检查是否执行 |
| `worldgen/street/HierarchicalBridgePlanner.java:25` | 桥候选是否启用 |
| `worldgen/gen/Bridges.java:41` | 桥面高度 `GROUNDLEVEL` vs `GROUNDLEVEL+1` |
| `commands/CommandDebug.java:64,99` | 诊断 |

`StreetGenerationMode` 定义：`config/StreetGenerationMode.java:9-19`（`LEGACY, HIERARCHICAL_GRID_V1` + `byName`）。该枚举**不可扩展**。
写入点（唯一）：`worldgen/DefaultDimensionInfo.java:61`。
请求值来源：`config/LostCityProfile.java:207`（默认 `HIERARCHICAL_GRID_V1`）与 L428/L431（配置读入）。

### 9.5 `hasXBridge` / `hasZBridge` / `hasBridge`

| file:line | 说明 |
| --- | --- |
| `worldgen/lost/BuildingInfo.java:1534-1549` | `hasBridge(provider, orientation)` / `hasBridge(provider)` |
| `worldgen/lost/BuildingInfo.java:1552,1558` | `hasXBridge` + `calculateXBridge`（HGV1 分支 L1565–1572，递归 `getBuildingInfo` L1568） |
| `worldgen/lost/BuildingInfo.java:1624,1630` | `hasZBridge` + `calculateZBridge`（HGV1 分支 L1637–1644，X/Z 互斥 L1652/L1663/L1681） |
| `worldgen/gen/Bridges.java:25,29` | 桥面部件选择 |
| `worldgen/LostCityTerrainFeature.java:1586-1589` | 街道部件的四向连接（`bridgeConnection`） |
| `worldgen/LostCityTerrainFeature.java:1731` | `borderNeedsConnectionToAdjacentChunk` 放行桥头列 |

### 9.6 街道坡道 / 楼梯读取点（V2 必须抑制的旁路）

| file:line | 说明 |
| --- | --- |
| `worldgen/lost/BuildingInfo.java:1406,1452,1479,1511` | `getStreetSlopeDirection` / `isSameSlopeRoadClass` / `calculateStairDirection` / `calculateActualStairDirection` |
| `worldgen/gen/Doors.java:141` | `if (info2.getStreetSlopeDirection() != null) return false;`（门不与坡道格连通） |
| `worldgen/LostCityTerrainFeature.java:966` | 坡道格不生成街道装饰 |
| `worldgen/LostCityTerrainFeature.java:1176,1204-1208` | `generateStreet` 的坡道分支 |
| `worldgen/LostCityTerrainFeature.java:1695` | `borderNeedsConnectionToAdjacentChunk` 读邻格坡道 |
| `worldgen/lost/BuildingInfo.java:1803-1817` | `hasRoadConnection` 用坡道判定跨层连接 |

### 9.7 其它消费者

| file:line | 说明 |
| --- | --- |
| `worldgen/lost/BuildingInfo.java:368,374` | `getChunkCharacteristics` / `...Locked`（路网特征的唯一写入点） |
| `worldgen/lost/BuildingInfo.java:600,618,619` | `initMultiBuildingSection` 与 `MultiChunk.getOrCreate` / `getMultiBuilding` |
| `worldgen/lost/BuildingInfo.java:701,708,722` | `cleanCache()`（清 `BUILDING_INFO_MAP`/`CITY_INFO_MAP`/`CITY_LEVEL_CACHE`）、`getBuildingInfo`、`getDimensionLock` |
| `worldgen/lost/MultiChunk.java:33,34,56,62` | `MULTICHUNKS` 缓存、`cleanCache`、`getOrCreate`、`getMultiBuilding` |
| `worldgen/LostCityTerrainFeature.java:429` | `Bridges.generateBridges(this, info)` 唯一调用点 |
| `worldgen/LostCityTerrainFeature.java:1747,1957` | `generatePart` / `transformBlockState`（public，V2 复用与扩展旋转的入口） |
| `worldgen/LostCityFeature.java:41,84,157,190,198,217` | 生命周期、`getDimensionInfo`、`computeIfAbsent`、`cleanUpInternal` |
| `setup/Registration.java:26` | `LOSTCITY_FEATURE`（附属模组取 `IDimensionInfo` 的公共入口） |
| `setup/ForgeEventHandlers.java:132-143` | 新世界标记 + `globalDimensionInfoDirtyCounter++` |

---

## 10. LC2H 4.2.4-LTS 干扰面（从 jar 逐条解析，已验证）

来源：`curse.maven:lc2h-1325431:8928853`（Gradle 缓存中的 deobf jar；`mixins.lc2h.json` + 类文件注解解析）。

### 10.1 直接冲突

| LC2H 混入类 | 目标/方法 | 注解 | 对 V2 的影响 |
| --- | --- | --- | --- |
| `org.admany.lc2h.mixin.lostcities.worldgen.MixinMultiChunk` | `MultiChunk.calculateBuildings(IDimensionInfo)` | `@Inject(at=HEAD, cancellable=true)`（`lc2h$beginPlanningCache`）+ `@Inject(at=RETURN)` | **会在 HEAD 取消上游选址并改由 `FastMultiChunkPlanner.tryPlan(MultiChunk, IDimensionInfo)` 规划。**`canPlaceBuilding` 因此**完全不执行** → 任何放进 `canPlaceBuilding` 的 V2 路权强制在 LC2H 快速路径下失效 |
| 同上 | `MultiChunk.getOrCreate(IDimensionInfo, ChunkCoord)` | `@Inject(at=HEAD, cancellable=true)` + `@Inject(at=RETURN)` | 异步 MultiChunk 规划（`AsyncMultiChunkPlanner`）会**绕过上游 `getOrCreate` 主体**；但 `BuildingInfo.initMultiBuildingSection` 仍在路径上（它只是消费 `getOrCreate` 的返回值） |
| 同上 | `canPlaceBuilding` 内的 `City.getCityStyle`、`City.isChunkOccupied`、`Railway.getRailChunkType`、`BuildingInfo.isCityRaw`、`BuildingInfo.hasHighway`，以及 `calculateBuildings` 内的 `RegistryAssetRegistry.get` | `@Redirect`（`require=0`） | **没有** redirect `getStreetPlanner().getRoadType(...)`；但 `@Redirect` 与我们的 `@Inject`/`@WrapOperation` 在同一方法内共存时的顺序依赖 Mixin 优先级 |
| 同上 | `MultiChunk.cleanCache()` | `@Inject(at=HEAD)` | 无冲突，但注意清理时机 |
| `MixinMultiChunkTimings` | `calculateBuildings` | `@Redirect`（debug 计时） | 同上，顺序依赖 |
| `org.admany.lc2h.mixin.lostcities.building.MixinNativeBuildingInfoCharacteristicsCache` | `BuildingInfo.getChunkCharacteristics(ChunkCoord, IDimensionInfo)`、`getCityLevel(...)`、`getBuildingInfo(...)` | `@Overwrite` | **覆写了特征解析的三个入口。**它 `@Shadow`s `getChunkCharacteristicsLocked` 与 `getCityLevelLocked`（即仍调用上游实现），因此**注入 `getChunkCharacteristicsLocked` 仍然在路径上**；但不能假设 `getChunkCharacteristics` 是上游实现 |
| 同上 | `BuildingInfo.<init>` 内的 `getDimensionLock(...)` 调用点 | `@Redirect`（`lc2h$perInstanceMemoizationLock`） | `BuildingInfo` 的 `memoizationLock` 被换成 LC2H 的实例级锁；V2 若依赖"维度级锁内单线程"的假设会失效 |
| `MixinBuildingInfo`（`lostcities.building`） | `BuildingInfo.getMaxcellars/getMinfloors/getMaxfloors(EffectiveCitySettings)`；`<init>` 内写 `cellars`/`floors` 字段（`@Redirect` `PUTFIELD`，opcode 181）与 `ILostCityBuilding.getMinCellars()` 调用 | `@Overwrite` ×3、`@Redirect` ×3、`@Invoker("<init>")`（`lc2h$create`） | 直接重写楼层/地下室计算并挂钩 `BuildingInfo` 构造器 → **`<init>` 已被 LC2H 修改**，V2 不能假设构造器里只有上游逻辑 |
| 同上 | `isCityRaw`、`getDimensionLock`、**`getChunkCharacteristicsLocked`**、**`initMultiBuildingSection`**、`getAverageCityLevel`、`getTopLeftCityLevel`、`getTopLeftCityInfo`、**`checkBuildingPossibility`**、`getProfile`、`getCityLevelSpace/Floating/Cavern/Normal`、`getBuildingRandom` | 全部 `@Shadow` | **关键**：LC2H 只是 `@Shadow`（调用）而不是覆写 `getChunkCharacteristicsLocked` 与 `initMultiBuildingSection`，且 `checkBuildingPossibility` 的描述符与 §3.4 完全一致 → **§11.1 的 M4（`initMultiBuildingSection` RETURN）与 M3（`getChunkCharacteristicsLocked` 内 `getStreetInfo`）在 LC2H 下仍在执行路径上**，这是 V2 在 LC2H 下唯一可靠的强制点 |
| 同上 | 大量 `lc2h$*` `@Unique` 缓存/城市层级算法（`LC2H_CITY_REGION_LEVEL_CACHE`、`LC2H_CITY_RAW_COMPUTE_FLAG`、`LC2H_CHARACTERISTIC_OWNERS`、`LC2H_EXACT_NATIVE_CHARACTERISTICS`、`lc2h$rememberCharacteristics` 等） | `@Unique` | 读取 `rawPlannedRoadType`（常量池中出现）→ **LC2H 已在消费 V2 也要写的同一字段**；V2 必须保证该字段语义不变（"raw 保留类"） |
| `org.admany.lc2h.mixin.lostcities.dimension.MixinDefaultDimensionInfoThreadLocal` | `DefaultDimensionInfo` 的 `setWorld`/`getWorld`/`getSeed`/`getType`/`getRandom`/`getProfile`/`getOutsideProfile`/`getWorldStyle`/`getStreetGenerationMode`/`getFeature`/`getHeightmap`×2/`dimension` | 全部 `@Overwrite` | **`getStreetPlanner()` 与 `<init>` 未被覆写** → §2.5 的挂载点安全；但不要在 V2 代码里用被覆写的 getter 取"冻结输入" |
| `org.admany.lc2h.mixin.lostcities.worldgen.feature.MixinLostCityTerrainFeature` | 仅 `correctTerrainShape`、`generate(WorldGenRegion, ChunkAccess)`、`breakBlocksForDamageNew` | `@Inject` + `@Redirect` | **没有触碰街道渲染路径**（`generateStreet`/`generate*StreetSection`/`getStreetParts`/`generatePart`/`generateBorders`/`generateMinorStreetConnectors` 全部干净）→ §5 的注入点与 LC2H 无直接冲突 |
| `MixinBuildingsFallback` 等 | — | — | 已检查 `FastMultiChunkPlanner` 与 `AsyncMultiChunkPlanner` 的常量池：**没有任何 `PlannedRoadType`/`getStreetPlanner`/`getRoadType`/`MULTI_BUILDING_STREET_CONFLICT`/`roadBlocks` 字符串** → 证实方案 §1 的判断："LC2H 快速选址当前跳过这项检查" |

### 10.2 结论（对 V2 的影响）

1. **`MultiChunk.canPlaceBuilding` 不是可靠的 V2 强制点**（LC2H 快速路径下不执行）。→ 必须有 §4.4 方案 A-2 的 `initMultiBuildingSection` RETURN 兜底。
2. **不能靠"V2 会顺带修好 LC2H"**：`FastMultiChunkPlanner` 连路权类型都不认识。
3. 按方案 §6 的要求，交付时**要么**在检测到 LC2H 且其快速多区块路径启用时**拒绝启用 V2（fail-fast）**，**要么**实现等价谓词后再开放。前者是首版唯一诚实的选择；后者需要 §4.4 A-2 并接受"非逐位等价"（见 §11）。
4. `LostCitiesStreetModePolicy.resolve(upstreamMode, profileName)` 会覆写模式 → V2 的"维度模式必须仍是 HGV1"这一前提在 LC2H 下需要显式校验（读取 TLC 持久值而非 `IDimensionInfo.getStreetGenerationMode()`，见 §7.3.6）。

---

## 11. 集成面结论

### 11.1 最小有序混入清单（按依赖顺序）

> 编号后括注：优先级建议（Mixin `priority`，数值小者先应用）。全部使用 `remap = false`（TLC 类型不是 Mojang 映射名）。

| # | 目标类 | 注解 / 方法 | 作用 | 前置依赖 |
| --- | --- | --- | --- | --- |
| M1 | `DefaultDimensionInfo` | `@Inject(method="<init>", at=@At("TAIL"))` + `@Unique` 字段 + `implements CitylinesDimensionRoadState` | 每维度绑定 V2 运行时状态（**惰性**，不在钩子里做 I/O）。**已存在骨架** `src/main/java/com/scarasol/citylines/mixin/tlc/DefaultDimensionInfoMixin.java` + `road/RoadHooks.java` | 无 |
| M2 | `HierarchicalStreetPlanner` | `@Inject(method={"getStreetInfo","getRoadType"}, at=@At("HEAD"), cancellable=true)` | 单点委派，覆盖 Q1–Q6；V2 未启用时不动 | M1（取 sink） |
| M3 | `BuildingInfo` | `@WrapOperation` on `provider.getStreetPlanner().getStreetInfo(int,int)`（`getChunkCharacteristicsLocked` 内 L408）+ `@WrapOperation` on `EffectiveStreetResolver.resolve(...)`（`getEffectivePlannedRoadType` 内 L575/L594） | 让 V2 的原始路类**不经**"单格邻接原始城市格"启发式；写入 `rawPlannedRoadType` 与 `plannedRoadType` | M2 |
| M4 | `BuildingInfo` | `@Inject(method="initMultiBuildingSection", at=@At("RETURN"))` | V2 footprint 路权兜底（覆盖 LC2H 快速路径）：冲突则 `multiPos = MultiPos.SINGLE; multiBuilding = null`。**已由 LC2H 的 `@Shadow initMultiBuildingSection`（同描述符）证实该方法在 LC2H 路径上仍被调用** | M3 |
| M5 | `BuildingInfo` | `@Inject(method="getStreetSlopeDirection", at=@At("HEAD"), cancellable=true)` | V2 首版无坡道 → 返回 `null`，同时关掉 `hasRoadConnection` 的跨层分支、`generateStreetSlopeSection`、`Doors` 的坡道特判、`borderNeedsConnection…` 的坡道列 | M3 |
| M6 | `BuildingInfo` | `@Inject(method="hasXBridge", at=@At("HEAD"), cancellable=true)` 与 `hasZBridge` 同上 | V2 span → V2 桥面部件；不经 `HierarchicalBridgePlanner`，不回调 `BuildingInfo.getBuildingInfo` | M1 |
| M7 | `BuildingInfo` | `@Inject(method="hasRoadConnection", at=@At("HEAD"), cancellable=true)` | V2 版"双侧 mask 均有边 + 边等级一致" | M2/M3（需 V2 侧表） |
| M8 | `LostCityTerrainFeature` | `@Inject(method="generateNormalStreetSection", at=@At("HEAD"), cancellable=true)` 与 `generateFullStreetSection` 同上 | 用 `(中心等级, 四向边等级)` 选 V2 部件；复用 public 的 `generatePart`/`computePalette`/`getDriver` | M7 |
| M9 | `LostCityTerrainFeature` | `@Inject(method="generateMinorStreetConnectors", at=@At("HEAD"), cancellable=true)` | 抑制 V1 单列 connector | M8 |
| M10 | `LostCityTerrainFeature` | `@Inject(method="generateRandomVegetation", at=@At("HEAD"), cancellable=true)`（首版抑制）与 `generateStreetDecorations` 同上 | 防树叶/楼梯侵占 V2 车行面与步行带 | M8 |
| M11 | `LostCityTerrainFeature` | `@Inject(method="transformBlockState", at=@At("HEAD"), cancellable=true)` | 为 V2 额外朝向方块补旋转（默认 tag 只含 stairs/doors） | M8（仅当 V2 用了额外朝向方块） |
| M12 | `gen/Bridges`（可选加固） | `@WrapOperation` on `info.provider.getStreetGenerationMode()` 调用点（`generateBridge` 内） | V2 维度强制走 `GROUNDLEVEL` 桥面高度 | 无（但见 §6.2：在 HGV1 持久值下本来就正确） |
| M13 | `MultiChunk`（仅 native 路径优化，可选） | `@Inject(method="canPlaceBuilding", at=@At("HEAD"), cancellable=true)` | 在 native 路径上**提前**否决，避免浪费已否决建筑的试放位置（使 native V2 结果更接近"理想 V2"） | M3 |
| M14 | 附属模组自有命令（**不是 mixin**） | `RegisterCommandsEvent` → `/citylines roads` | §8.3 | M1–M7 |
| M15 | 附属模组自有 `SavedData` + opt-in（**不是 mixin**） | overworld `DimensionDataStorage` | §7.3 | M1 |

**最小可用子集**（能跑通"V2 路权 + V2 断面"而暂不做桥/形制）：M1, M2, M3, M4, M5, M7, M8, M9, M10, M15。

### 11.2 在"无 `@Redirect` / 无反射 / 不 fork"约束下**做不到或高风险**的方案要求

诚实清单（按严重度排序）：

1. **"换掉整个 `MultiChunk` 选址算法以让 V2 路权成为原生前置约束"——做不到。**
   `calculateBuildings` 是 private 且无 `callOriginal` 机制；`MB`/`buildingGrid` 是包级私有，跨包混入类无法声明其类型（`@Accessor` 也不行）。因此 V2 只能在 `initMultiBuildingSection`（消费端）兜底否决，代价是**同一 multichunk 内后续建筑的试放位置与 native 路径不同**——即"V2 + LC2H 快速路径"下 `MultiChunk` 内容不是"理想 V2"。**必须声明为已知限制**，不能宣称逐位等价。
2. **`PlannedStreetInfo` 无法承载 V2 的"中心等级 + 四向边等级 + 桥 span"——做不到无副作用地复用 V1 通道。**
   record 只有 4 个 boolean 连接位。V2 必须另建一条侧表/duck interface（§1.3）。这意味着**任何第三方（Extraction Cities 等）通过 `getStreetInfo()` 读 V2 路网都会得到信息丢失的结果**，只能读到"是不是路 + 四个是否相邻路"，读不到边等级。方案 §6 说"下游只通过固定版本的 API 读数据"——在当前 TLC API 形状下，V2 的完整语义**无法**通过 TLC 的公共 API 表达。这是接口层的硬限制，不是实现偷懒。
3. **V2 的桥面高度这一项，只能"碰巧正确"。** `bridgeLevel` 是 `gen/Bridges.generateBridge` 的局部变量（L41），`@ModifyVariable` 不在允许列表内，`@Inject(HEAD, cancellable)` 要重写约 100 行。可行解只有两个：依赖"V2 维度持久模式 = HGV1"（当前成立，但脆弱），或 `@WrapOperation` 掉 `getStreetGenerationMode()` 调用点（可行，但语义上是"对一个判定调用做包装"，需在文档里写清）。
4. **V2 无法避免改变随机流与建筑放置——"必要间接影响"是硬事实。**
   `BuildingInfo` 构造器的分支（L921/L930/L907）消耗 `rand` 的次数不同，`hierarchicalOpen`/`plannedRoadType` 一变，整条 `QualityRandom` 流与 floors/cellars/door/palette/ruin/front 全部改变。V2 只能保证"未启用 V2 的维度逐位一致"，**不能**保证"启用 V2 的维度里建筑与 V1 相同"。方案 §1 已经承认这一点，但任何"V2 只改路不动建筑"的说法都是错的。
5. **坡道（跨高度道路）在首版无法安全实现。** `getStreetSlopeDirection` 的整套谓词（L1406–1454）只认 `isPrimaryRoad()` 与相邻 `cityLevel+1`，并会连带影响 `hasRoadConnection`、`Doors`、`borderNeedsConnectionToAdjacentChunk`、`generateStreetDecorations` 四处。V2 要么全局禁掉坡道（M5），要么就得同步重写这四处——后者已超出"最小集成面"，且方案 §2.1 本身就把坡道列为后续独立扩展。**首版必须诚实地把跨高度边排除在连通承诺之外。**
6. **`transformBlockState` 只旋转 stairs/doors。** `道路形制与方块.md` §3 要求"每个部件的 N/E/S/W 端口签名旋转后逐格吻合"，但引擎只对 `lostcities:rotatable`（= stairs + doors）与轨道生效。**任何超出这两类的朝向方块必须按朝向单独出件，或由附属模组扩展旋转（M11）。** 不能指望"`generatePart` 已经旋转了 BlockState"。
7. **`NullDimensionInfo`（GUI/预览）永远没有 V2 状态。** 所有从 `IDimensionInfo` 取 V2 的辅助函数必须 null-safe，否则 LC 配置 GUI / 预览/编辑器路径会 NPE。这也是"V2 状态挂 `DefaultDimensionInfo` 而不挂静态表"的代价之一（但静态表同样覆盖不到 `NullDimensionInfo`）。
8. **LC2H 共存无法做到"逐位等价"。** 已验证 LC2H 覆写 `DefaultDimensionInfo` 的全部 getter、`BuildingInfo.getChunkCharacteristics/getCityLevel/getBuildingInfo`、以及 `MultiChunk` 的选址入口。按方案 §6，**首版必须 fail-fast：检测到 LC2H 且其快速/异步多区块路径启用时拒绝启用 V2**，或至少声明"V2 在 LC2H 下的多区块路权只在消费端兜底"。不得静默降级、也不得宣称已验证。
9. **V2 的"整维度预检（缺件即拒绝初始化）"（`道路形制与方块.md` §3）在 Mixin 形态下只能在初始化点做。** `DefaultDimensionInfo` 构造器的 TAIL 钩子（M1）是最自然的落点，但它在 `ConcurrentHashMap.computeIfAbsent` 的锁内执行，**不能**在那里扫描全部 CityStyle/资产（会做大量注册表查询）。可行解：把预检挪到 `LevelEvent.Load`/`ServerAboutToStartEvent`，在第一次 `getOrCreateDimensionInfo` 之前完成；若预检失败则把该维度标记为"拒绝 V2"，让 M2 的委派直接不 cancel（退化为 V1）——**但方案 §4 明确禁止"在一个维度内按风格回退 V1"**。因此必须把"预检失败"处理为**该维度拒绝生成并报错**（fail-fast），而不是回退。这一点需要用户在设计层确认，因为它会让"缺件"的世界直接不可玩，而不是退化成 V1。
10. **`@Inject` 与 `@WrapOperation` 与 LC2H 的 `@Overwrite`/`@Redirect` 共存需要显式优先级约定。** Mixin 对同一方法的多个注入器按 `priority` 决定应用顺序，但 `@Overwrite` 与注入器的交互（注入器是否作用于被覆写后的方法体）在跨模组场景下没有稳定契约。当前已验证 LC2H **没有**覆写我们计划注入的 `getStreetPlanner`、`initMultiBuildingSection`、`getStreetSlopeDirection`、`hasXBridge`、`hasRoadConnection`、街道渲染各方法，也没有覆写 `MultiChunk.canPlaceBuilding`（只用 `@Redirect` 改其内部调用）——所以可行；但**任何 TLC 或 LC2H 版本升级都必须重跑这套注解解析验证**，并保持 `require = 1` 让其尽早失败。

### 11.3 一句话总结

TLC 只有 6 个 planner 查询点，看似"单点委派即可"；实际最小集成面是 **1 个委派点（M2）+ 1 个特征覆写对（M3）+ 1 个多建筑兜底（M4）+ 1 个坡道抑制（M5）+ 1 个连接谓词替换（M7）+ 1 个街道部件替换点（M8）+ 3 个抑制点（M9/M10）+ 1 个状态挂载（M1）**，并且 V2 必须**另建一条数据通道**（`PlannedStreetInfo` 表达力不足）、**把桥梁高度托付给"持久模式仍是 HGV1"这一脆弱前提**、**在 LC2H 下接受多区块路权只能消费端兜底**。
