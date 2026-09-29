> 历史过程记录：保留原文供追溯；其中旧配置、旧接口和阶段状态不代表当前版本。唯一当前交接与接口说明见 [DELIVERY.md](../DELIVERY.md)。

# TLC 1.20-7.5.5 资产管线与 V2 街道部件接入报告

| 项 | 值 |
| --- | --- |
| 上游基线 | Lost Cities `1.20-7.5.5`，git `61ed3004a8ad3e862a3bd7d0465839afa82f415f`，只读快照 `reference/lostcities-src/` |
| Forge / MC | `1.20.1-47.3.0` / 1.20.1 |
| 任务范围 | 只读分析 + 本文件；未修改任何 V1 资产、未运行 gradle |
| 相关设计 | `道路形制与方块.md`（V2 断面/28+1 键）、`方案.md` 第 6 节（接入点） |
| 本仓库约束 | 附属模组 + Mixin；只允许 `@Inject`/`@WrapOperation`/`@Unique`/`@Shadow`/`@Accessor`/`@Invoker`/`@Implements`；禁止 `@Redirect`/`@Overwrite`/反射（见 `docs/WORKFLOW.md`） |

本文件回答 8 个问题，所有结论都附 `文件:行` 证据。凡引用 JSON 一律逐字摘录；凡引用 Java 一律给出类名 + 行号。**未在本文件中出现的字段名一律不存在**。

---

## 0. 结论速览

| # | 问题 | 结论 |
| --- | --- | --- |
| 1 | 第三方命名空间是否被扫描 | **是，会被加载。** 目录规则是 `data/<任意命名空间>/lostcities/<registryPath>/<name>.json`；ID 的命名空间 = 目录命名空间。ExtractionCities 的 `data/extractioncities/lostcities/citystyles/single_building_test.json` 实际注册为 `extractioncities:single_building_test` |
| 2 | parts JSON | 6 个字段：`xsize`/`zsize`/`slices`（必填）+ `refpalette`/`palette`/`meta`（可选）。**没有旋转字段**；旋转由放置代码选定并在运行时对 BlockState 调用 `rotate()` |
| 3 | damaged 规则键 | **以最终 `BlockState` 为 key**，不是以模板字符为 key。无映射时 `DamageArea.damageBlock` 直接把方块变空气（或水线下变液体） |
| 4 | 选择机制 | V1 只有 9 个槽位（`full/straight/end/bend/t/none/all/connector/stair`），按邻居连接数选变体；JSON **没有** `(roadType, edgeClass)` 键。V2 必须在代码层新增选择入口（`HierarchicalStreetPlanner.getStreetInfo` 已提供逐方向 mask，可直接复用） |
| 5 | 放置 | `generatePart` 对任何 namespace 的部件一视同仁；地面 `y=0` 切片写在 `getCityGroundLevel()`；`GROUNDLEVEL` 是 cityLevel 0 的街道基准高度 |
| 6 | WorldStyle | `worldstyles/*.json` 只有 `citystyles[]`（CityStyleSelector）决定 CityStyle；高速部件在 `"parts"."highways"`（`PartSelector`），WorldStyle **没有** 街道部件字段 |
| 7 | 先例 | ExtractionCities 已用 `"citystyle": "extractioncities:single_building_test"` 完成同样的事，证明跨命名空间引用可用 |
| 8 | V2 最小交付 | 28（+1 可选）部件 JSON + 1 份 V2 palette + V2 Style/CityStyle/WorldStyle + 一份 profile 绑定 + 代码层 `(roadType, edgeClass)` 表 |

---

## 1. 加载：数据包如何被发现与解析（Q1）

### 1.1 注册表登记（全部是 Forge 数据包注册表）

`reference/lostcities-src/src/main/java/mcjty/lostcities/setup/CustomRegistries.java:15-52` 定义了 13 个 `ResourceKey<Registry<…>>`，其中与本报告相关的：

```java
15:    public static final ResourceKey<Registry<BuildingRE>> BUILDING_REGISTRY_KEY = ResourceKey.createRegistryKey(new ResourceLocation(LostCities.MODID, "buildings"));
18:    public static final ResourceKey<Registry<PaletteRE>> PALETTE_REGISTRY_KEY = ResourceKey.createRegistryKey(new ResourceLocation(LostCities.MODID, "palettes"));
21:    public static final ResourceKey<Registry<BuildingPartRE>> PART_REGISTRY_KEY = ResourceKey.createRegistryKey(new ResourceLocation(LostCities.MODID, "parts"));
24:    public static final ResourceKey<Registry<StyleRE>> STYLE_REGISTRY_KEY = ResourceKey.createRegistryKey(new ResourceLocation(LostCities.MODID, "styles"));
30:    public static final ResourceKey<Registry<CityStyleRE>> CITYSTYLES_REGISTRY_KEY = ResourceKey.createRegistryKey(new ResourceLocation(LostCities.MODID, "citystyles"));
36:    public static final ResourceKey<Registry<VariantRE>> VARIANTS_REGISTRY_KEY = ResourceKey.createRegistryKey(new ResourceLocation(LostCities.MODID, "variants"));
39:    public static final ResourceKey<Registry<WorldStyleRE>> WORLDSTYLES_REGISTRY_KEY = ResourceKey.createRegistryKey(new ResourceLocation(LostCities.MODID, "worldstyles"));
48:    public static final ResourceKey<Registry<ScatteredRE>> SCATTERED_REGISTRY_KEY = ResourceKey.createRegistryKey(new ResourceLocation(LostCities.MODID, "scattered"));
51:    public static final ResourceKey<Registry<StuffSettingsRE>> STUFF_REGISTRY_KEY = ResourceKey.createRegistryKey(new ResourceLocation(LostCities.MODID, "stuff"));
```

注册（`CustomRegistries.java:70-84`，由 `LostCities.java:56` 的 `bus.addListener(CustomRegistries::onDataPackRegistry)` 挂到 mod 事件总线）：

```java
70:    public static void onDataPackRegistry(DataPackRegistryEvent.NewRegistry event) {
71:        event.dataPackRegistry(BUILDING_REGISTRY_KEY, BuildingRE.CODEC);
72:        event.dataPackRegistry(PALETTE_REGISTRY_KEY, PaletteRE.CODEC);
73:        event.dataPackRegistry(PART_REGISTRY_KEY, BuildingPartRE.CODEC);
74:        event.dataPackRegistry(STYLE_REGISTRY_KEY, StyleRE.CODEC);
76:        event.dataPackRegistry(CITYSTYLES_REGISTRY_KEY, CityStyleRE.CODEC);
77:        event.dataPackRegistry(MULTIBUILDINGS_REGISTRY_KEY, MultiBuildingRE.CODEC);
78:        event.dataPackRegistry(VARIANTS_REGISTRY_KEY, VariantRE.CODEC);
79:        event.dataPackRegistry(WORLDSTYLES_REGISTRY_KEY, WorldStyleRE.CODEC);
80:        event.dataPackRegistry(PREDEFINEDCITIES_REGISTRY_KEY, PredefinedCityRE.CODEC);
82:        event.dataPackRegistry(SCATTERED_REGISTRY_KEY, ScatteredRE.CODEC);
83:        event.dataPackRegistry(STUFF_REGISTRY_KEY, StuffSettingsRE.CODEC);
```

`conditions`（`CONDITIONS_REGISTRY_KEY`）也在其中（`CustomRegistries.java:27-28, 75`）。**没有任何一处过滤命名空间。**

### 1.2 资源路径规则：由 Forge 的 `prefixNamespace` 决定（关键证据）

TLC 自己不扫描文件——它只遍历动态注册表。扫描由 vanilla `RegistryDataLoader` 完成，而 Forge 给它打了一个补丁。补丁后的源码在 ForgeFG 缓存里可读：

`forge-1.20.1-47.3.0-patched.jar!/net/minecraft/resources/RegistryDataLoader.java:125-131`

```java
125:   private static String m_246502_(ResourceLocation p_252033_) {
126:      return net.minecraftforge.common.ForgeHooks.prefixNamespace(p_252033_); // FORGE: add non-vanilla registry namespace to loader directory, same format as tag directory (see net.minecraft.tags.TagManager#getTagDir(ResourceKey))
127:   }
...
130:      String s = m_246502_(p_255792_.m_135782_());
131:      FileToIdConverter filetoidconverter = FileToIdConverter.m_246568_(s);
```

`forge-1.20.1-47.3.0-patched.jar!/net/minecraftforge/common/ForgeHooks.java:1533-1536`

```java
1533:    public static String prefixNamespace(ResourceLocation registryKey)
1534:    {
1535:        return registryKey.m_135827_().equals("minecraft") ? registryKey.m_135815_() : registryKey.m_135827_() +  "/"  + registryKey.m_135815_();
1536:    }
```

即：`getNamespace().equals("minecraft") ? getPath() : getNamespace() + "/" + getPath()`。

于是目录前缀 = **`lostcities/<registryPath>`**（`lostcities` 是注册表 key 的命名空间，不是数据包命名空间）：

| 注册表 key | 目录前缀 | 实际目录 |
| --- | --- | --- |
| `lostcities:parts` | `lostcities/parts` | `data/<ns>/lostcities/parts/*.json` |
| `lostcities:palettes` | `lostcities/palettes` | `data/<ns>/lostcities/palettes/*.json` |
| `lostcities:citystyles` | `lostcities/citystyles` | `data/<ns>/lostcities/citystyles/*.json` |
| `lostcities:worldstyles` | `lostcities/worldstyles` | `data/<ns>/lostcities/worldstyles/*.json` |
| `lostcities:styles` | `lostcities/styles` | `data/<ns>/lostcities/styles/*.json` |
| `lostcities:variants` | `lostcities/variants` | `data/<ns>/lostcities/variants/*.json` |
| `lostcities:stuff` | `lostcities/stuff` | `data/<ns>/lostcities/stuff/*.json` |
| `lostcities:scattered` | `lostcities/scattered` | `data/<ns>/lostcities/scattered/*.json` |

### 1.3 文件 → ResourceLocation：命名空间被保留

vanilla `net/minecraft/resources/FileToIdConverter.java`（同 jar，官方映射）：

```java
public ResourceLocation m_245273_(ResourceLocation p_249595_) {      // fileToId
   String s = p_249595_.m_135815_();                                 // getPath()
   return p_249595_.m_247449_(s.substring(this.f_244233_.length() + 1, s.length() - this.f_244199_.length()));
}
```

`m_247449_` 是 `ResourceLocation.withPath(String)`（`ResourceLocation.java:102-104`），**只替换 path，保留 namespace**。列表来源是 `FileToIdConverter.m_247457_(resourceManager)` → `ResourceManager.listResources(prefix, p -> p.getPath().endsWith(".json"))`，其实现按命名空间逐包遍历（`MultiPackResourceManager.java:86-95` 遍历 `this.f_203794_.values()`，即每个命名空间一个 `FallbackResourceManager`），最终由 `PathPackResources.m_246914_` 第 82 行构造：

```java
82:            ResourceLocation resourcelocation = ResourceLocation.m_214293_(p_249455_, s);   // tryBuild(namespace, path)
```

其中 `p_249455_` 就是当前遍历到的命名空间。

**端到端示例**：文件 `data/citylines/lostcities/parts/v2r_p_0sp0.json`
→ pack 内路径 `lostcities/parts/v2r_p_0sp0.json`（namespace = `citylines`）
→ `fileToId` 去掉 `lostcities/parts/` 与 `.json`
→ `ResourceLocation citylines:v2r_p_0sp0`，落进注册表 `lostcities:parts`。

### 1.4 TLC 侧：`loadAll` 全量遍历 + 惰性 `get`

`reference/lostcities-src/src/main/java/mcjty/lostcities/worldgen/lost/cityassets/RegistryAssetRegistry.java:97-114`

```java
97:    public void loadAll(CommonLevelAccessor level) {
101:        Registry<R> registry = level.registryAccess().registryOrThrow(registryKey);
102:        for (R r : registry) {                       // ← 全量，无命名空间判断
103:            ResourceLocation name = registry.getKey(r);
104:            if (!assets.containsKey(name)) {
105:                if (r instanceof IAsset asset) {
106:                    asset.setRegistryName(name);
107:                }
108:                T t = assetConstructor.apply(r);
109:                if (t != null) {
110:                    assets.putIfAbsent(name, t);
111:                }
112:            }
113:        }
114:    }
```

`AssetRegistries.java:53-72` 决定哪些注册表"预加载"：

```java
61:            PARTS.loadAll(level);
62:            BUILDINGS.loadAll(level);
63:            STUFF.loadAll(level);
...
70:            loaded = true;
```

以及 `loadPredefinedStuff`（`AssetRegistries.java:74-86`）加载 `PREDEFINED_CITIES` / `PREDEFINED_SPHERES`。
**`PALETTES` / `CITYSTYLES` / `WORLDSTYLES` / `STYLES` / `VARIANTS` / `SCATTERED` / `CONDITIONS` / `MULTI_BUILDINGS` 不在 `loadAll` 列表里，是首次 `get` 时惰性构造**（`RegistryAssetRegistry.java:68-95`）：

```java
72:        T t = assets.get(name);
73:        if (t == null) {
75:                Registry<R> registry = level.registryAccess().registryOrThrow(registryKey);
76:                R value = registry.get(ResourceKey.create(registryKey, name));
77:                if (value instanceof IAsset asset) {
78:                    asset.setRegistryName(name);
79:                }
80:                t = assetConstructor.apply(value);
...
92:        if (t != null) {
93:            t.init(level);
94:        }
```

触发点：`AssetRegistries.load(serverLevel)` 在 `setup/ForgeEventHandlers.java:104`（`onWorldTick`）被调用；`ModSetup.java:42` 与 `LostCityFeature.java:220` 调用 `AssetRegistries.reset()`。

### 1.5 名字的写法：`DataTools.fromName` / `toName`

`reference/lostcities-src/src/main/java/mcjty/lostcities/worldgen/lost/regassets/data/DataTools.java:22-36`

```java
22:    public static String toName(ResourceLocation rl) {
23:        if (rl.getNamespace().equals(LostCities.MODID)) {
24:            return rl.getPath();
25:        } else {
26:            return rl.toString();
27:        }
28:    }
29:
30:    public static ResourceLocation fromName(String name) {
31:        if (name.contains(":")) {
32:            return new ResourceLocation(name);
33:        } else {
34:            return new ResourceLocation(LostCities.MODID, name);
35:        }
36:    }
```

推论（对 V2 直接适用）：

* 不带冒号的字符串（如 `street_straight`）**永远**解析到 `lostcities:`。
* 附属模组的资产必须被写成 `citylines:<name>` 才能被引用（`getOrThrow`/`getOrWarn`/`get` 都走 `fromName`，见 `RegistryAssetRegistry.java:35-65`）。
* 唯一的例外是注册表内部的 key 本身（遍历时来自注册表，天然带命名空间）。

### 1.6 第三方命名空间的判定结论

**结论：第三方命名空间会被完整加载，不存在"被静默忽略"的过滤器。** 三条独立证据：

1. `RegistryDataLoader` 的目录前缀只取注册表 key 的 **path**，扫描经过 `ResourceManager.listResources`，该 API 按 `PackResources.getNamespaces(PackType)` 枚举所有命名空间（`MultiPackResourceManager.java:24-59, 86-95`）。
2. `FileToIdConverter.fileToId` 保留文件所在命名空间。
3. `RegistryAssetRegistry.loadAll` / `get` 只按 `ResourceLocation` 取用，代码里除 `DataTools` 的显示名处理外没有一次 `getNamespace()` 比较（全仓库仅 `gui/LostCitySetup.java:132` 与 `DataTools.java:23` 两处，都是显示用途）：

```
reference/lostcities-src/src/main/java/mcjty/lostcities/gui/LostCitySetup.java:132:        if (!LostCities.MODID.equals(rl.getNamespace())) {
reference/lostcities-src/src/main/java/mcjty/lostcities/worldgen/lost/regassets/data/DataTools.java:23:        if (rl.getNamespace().equals(LostCities.MODID)) {
```

`gui/LostCitySetup.java:122-142` 甚至是**主动支持**附属模组的：它用 `resourceManager.listResources("lostcities/worldstyles", …)` 枚举世界样式，并在 `worldStyleToName` 里给非 `lostcities` 命名空间补回前缀：

```java
141:        Map<ResourceLocation, Resource> map = resourceManager.listResources("lostcities/worldstyles", s -> s.toString().endsWith(".json"));
122:    private static String worldStyleToName(ResourceLocation rl) {
...
131:        if (!LostCities.MODID.equals(rl.getNamespace())) {
132:            path = rl.getNamespace() + ":" + path;
133:        }
```

**必须知道的两个限制（不是 bug，但会造成"看起来被忽略"）**：

* 注册表遍历只覆盖**当前数据包集合**；`data/citylines/...` 只在这个模组/数据包被启用时存在。
* `Palette` 是**惰性解析**的：`Palette.parsePaletteArray`（`cityassets/Palette.java:65-114`）在 `Palette` 构造时执行，而 `Palette` 只在首次 `PALETTES.get(...)` 时构造。因此一个从未被引用的 palette 里的非法方块名不会立刻报错。
* 反过来，`parts` 是**预加载**的（`AssetRegistries.load` 第 61 行），构造 `BuildingPart` 时不解析 palette（`BuildingPart.java:42-47` 只记录 `refPaletteName`），所以 part 引用了不存在的 palette 也要等到放置时 `getLocalPalette` → `getOrThrow` 才抛异常。

**更正（2026-09 逐行复核，替换早先"只打一条 warning"的说法）**：`getOrWarn` **不会**在缺件时返回 `null`。`RegistryAssetRegistry.get`（`RegistryAssetRegistry.java:68-95`）把 `registry.get(...)` 与 `assetConstructor.apply(...)` 包在 `try { … } catch (Exception e) { throw new RuntimeException("Error getting resource " + name + "!", e); }` 里，而 `new BuildingPart(null)` / `new Palette(null)` / `new Style(null)` 会在构造器第一行解引用 `null` 的 RE 立刻 NPE。因此：

* 缺失/无法解析的 part、palette、style、citystyle（含 `inherit`，走 `CityStyle.init` 的 `getOrThrow`）或 worldstyle，都会让**区块生成直接抛 `RuntimeException` 崩溃**，不是 warning-and-skip；
* 唯一"静默"的只是**引用存在但内容非法**（例如 part 引用了存在的 palette、但该 palette 里有非法方块名，只有真正取用该字符时才抛）；
* 推论：**整维度预检是强制项而不是优化**（见 §8.6 第 6 条），且预检失败必须在**任何 V2 区块生成之前**就拒绝启用 V2——设计中"不能在一个维度内静默回退 V1"正是为此。

---

## 2. `parts/*.json` 完整模式（Q2）

### 2.1 codec 字段全表

`reference/lostcities-src/src/main/java/mcjty/lostcities/worldgen/lost/regassets/BuildingPartRE.java:18-26`

```java
18:    public static final Codec<BuildingPartRE> CODEC = RecordCodecBuilder.create(instance ->
19:            instance.group(
20:                    Codec.INT.fieldOf("xsize").forGetter(l -> l.xSize),
21:                    Codec.INT.fieldOf("zsize").forGetter(l -> l.zSize),
22:                    Codec.list(Codec.list(Codec.STRING)).fieldOf("slices").forGetter(BuildingPartRE::createSlices),
23:                    Codec.STRING.optionalFieldOf("refpalette").forGetter(l -> Optional.ofNullable(l.refPaletteName)),
24:                    PaletteRE.CODEC.optionalFieldOf("palette").forGetter(l -> Optional.ofNullable(l.localPalette)),
25:                    Codec.list(PartMeta.CODEC).optionalFieldOf("meta").forGetter(l -> Optional.ofNullable(l.metadata))
26:            ).apply(instance, BuildingPartRE::new));
```

| 字段 | 类型 | 必填 | 含义 |
| --- | --- | --- | --- |
| `xsize` | int | ✔ | 部件 X 尺寸（街道部件为 16） |
| `zsize` | int | ✔ | 部件 Z 尺寸（街道部件为 16） |
| `slices` | `list<list<string>>` | ✔ | 外层 = Y 层（索引 0 为最底）；内层 = 该层的 zsize 个字符串，每个长 xsize |
| `refpalette` | string | ✘ | 引用 `lostcities:palettes` 中的 palette（`DataTools.fromName` 规则） |
| `palette` | `PaletteRE`（内联） | ✘ | 内联 palette，等价于 `{"palette":[ … ]}`；**与 `refpalette` 互斥（`else if`）**，见 `BuildingPart.java:42-47` |
| `meta` | `list<PartMeta>` | ✘ | 任意键值元数据 |

`PartMeta`（`regassets/data/PartMeta.java:14-22`）字段：

```java
16:                    Codec.STRING.fieldOf("key").forGetter(l -> l.key),
17:                    Codec.BOOL.optionalFieldOf("boolean").forGetter(l -> Optional.ofNullable(l.bool)),
18:                    Codec.STRING.optionalFieldOf("char").forGetter(l -> Optional.ofNullable(l.chr)),
19:                    Codec.STRING.optionalFieldOf("string").forGetter(l -> Optional.ofNullable(l.str)),
20:                    Codec.INT.optionalFieldOf("integer").forGetter(l -> Optional.ofNullable(l.i)),
21:                    Codec.FLOAT.optionalFieldOf("float").forGetter(l -> Optional.ofNullable(l.f))
```

已知 meta key 常量（`api/ILostCities.java:24-29`）：`dontconnect`、`support`、`z1`、`z2`、`nowater`、`forcedair`。街道坡道只用 `z1`/`z2`（`LostCityTerrainFeature.java:1700-1701`）。

### 2.2 逐字引用：六个代表性部件

`reference/lostcities-src/src/main/resources/data/lostcities/lostcities/parts/street_straight.json`（全文）

```json
{
  "xsize": 16,
  "zsize": 16,
  "slices": [
    [
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb",
      "SSSSSSSSSSSSSSSS",
      "SSSSSSSSSSSSSSSS",
      "SSSSSSSSSSSSSSSS",
      "SSSSSSSSSSSSSSSS",
      "SSSSSSSSSSSSSSSS",
      "SSSSSSSSSSSSSSSS",
      "SSSSSSSSSSSSSSSS",
      "SSSSSSSSSSSSSSSS",
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb"
    ]
  ]
}
```

`parts/street_large_straight.json`（全文）

```json
{
  "xsize": 16,
  "zsize": 16,
  "refpalette": "street_large",
  "slices": [
    [
      "bbbbbbbbbbbbbbbb",
      "llllllllllllllll",
      "llllllllllllllll",
      "llllllllllllllll",
      "llllllllllllllll",
      "llllllllllllllll",
      "llllllllllllllll",
      "qqqqqqqqqqqqqqqq",
      "qqqqqqqqqqqqqqqq",
      "llllllllllllllll",
      "llllllllllllllll",
      "llllllllllllllll",
      "llllllllllllllll",
      "llllllllllllllll",
      "llllllllllllllll",
      "bbbbbbbbbbbbbbbb"
    ]
  ]
}
```

`parts/street_bend.json`（全文）

```json
{
  "xsize": 16,
  "zsize": 16,
  "slices": [
    [
      "bbbbSSSSSSSSbbbb",
      "bbbbSSSSSSSSbbbb",
      "bbbbSSSSSSSSbbbb",
      "bbbbSSSSSSSSbbbb",
      "SSSSSSSSSSSSbbbb",
      "SSSSSSSSSSSSbbbb",
      "SSSSSSSSSSSSbbbb",
      "SSSSSSSSSSSSbbbb",
      "SSSSSSSSSSSSbbbb",
      "SSSSSSSSSSSSbbbb",
      "SSSSSSSSSSSSbbbb",
      "SSSSSSSSSSSSbbbb",
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb"
    ]
  ]
}
```

`parts/street_large_connector.json`（全文；注意空字符串用于"不写"）

```json
{
  "xsize": 16,
  "zsize": 16,
  "refpalette": "street_large",
  "slices": [
    [
      "                ",
      "                ",
      "                ",
      "                ",
      "l               ",
      "l               ",
      "l               ",
      "l               ",
      "l               ",
      "l               ",
      "l               ",
      "l               ",
      "                ",
      "                ",
      "                ",
      "                "
    ]
  ]
}
```

`parts/street_none.json`（全文）

```json
{
  "xsize": 16,
  "zsize": 16,
  "slices": [
    [
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb",
      "bbbbSSSSSSSSbbbb",
      "bbbbSSSSSSSSbbbb",
      "bbbbSSSSSSSSbbbb",
      "bbbbSSSSSSSSbbbb",
      "bbbbSSSSSSSSbbbb",
      "bbbbSSSSSSSSbbbb",
      "bbbbSSSSSSSSbbbb",
      "bbbbSSSSSSSSbbbb",
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb"
    ]
  ]
}
```

`parts/street_end.json`（全文；`_t` 以 `parts/street_t.json` 为代表）

```json
{
  "xsize": 16,
  "zsize": 16,
  "slices": [
    [
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb",
      "SSSSSSSSSSSSbbbb",
      "SSSSSSSSSSSSbbbb",
      "SSSSSSSSSSSSbbbb",
      "SSSSSSSSSSSSbbbb",
      "SSSSSSSSSSSSbbbb",
      "SSSSSSSSSSSSbbbb",
      "SSSSSSSSSSSSbbbb",
      "SSSSSSSSSSSSbbbb",
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb"
    ]
  ]
}
```

```json
// parts/street_t.json（全文）
{
  "xsize": 16,
  "zsize": 16,
  "slices": [
    [
      "bbbbSSSSSSSSbbbb",
      "bbbbSSSSSSSSbbbb",
      "bbbbSSSSSSSSbbbb",
      "bbbbSSSSSSSSbbbb",
      "SSSSSSSSSSSSSSSS",
      "SSSSSSSSSSSSSSSS",
      "SSSSSSSSSSSSSSSS",
      "SSSSSSSSSSSSSSSS",
      "SSSSSSSSSSSSSSSS",
      "SSSSSSSSSSSSSSSS",
      "SSSSSSSSSSSSSSSS",
      "SSSSSSSSSSSSSSSS",
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb",
      "bbbbbbbbbbbbbbbb"
    ]
  ]
}
```

对照参考（V2 要取代的对象，全文见文件）：`parts/street_large_none.json` 的 `l` 覆盖 `x=1..14, z=1..14`（14×14 满铺）+ 中央 `q` 2×2；`parts/street_large_stair.json` 有 `meta`：

```json
// parts/street_large_stair.json:2-11
  "meta": [
    {
      "integer": 1,
      "key": "z1"
    },
    {
      "integer": 14,
      "key": "z2"
    }
  ],
```

`parts/street_stair.json:2-11` 用 `z1=4, z2=11`。**`z1/z2` 是坡道模板两侧起坡行的 z 索引，被 `borderNeedsConnectionToAdjacentChunk` 用来决定边界列是否需要开口（`LostCityTerrainFeature.java:1691-1737`）。**

### 2.3 `slices` 的 16×16 布局与坐标映射

`BuildingPartRE.java:42-58` 把内层字符串**拼接**成一整条：

```java
44:        this.slices = new String[slices.size()];
45:        int idx = 0;
46:        for (List<String> slice : slices) {
47:            StringBuilder builder = new StringBuilder();
48:            for (String s : slice) {
49:                builder.append(s);
50:            }
51:            this.slices[idx++] = builder.toString();
52:        }
```

`createSlices()`（`BuildingPartRE.java:60-71`）按 `xsize` 切回 z 行：

```java
64:            for (int z = 0; z < zSize; z++) {
65:                String sub = slice.substring(z * xSize, z * xSize + xSize);
```

索引公式（`BuildingPart.java:181-192`）：

```java
181:    public Character getPaletteChar(int x, int y, int z) {
182:        return slices[y].charAt(z * xSize + x);
183:    }
...
189:    public Character getC(int x, int y, int z) {
190:        return slices[y].charAt(z * xSize + x);
191:    }
```

因此：

* `slices[y]` = 第 y 层，`y=0` 是最底层，**写在世界 `getCityGroundLevel()` 的高度**。
* 层内行优先：`charAt(z*16 + x)`；`x` 向右（东）增大 `0→15`，`z` 向正 Z（南）增大 `0→15`。
* 街道部件都只有 1 层（`getSliceCount()==1`）；坡道部件有多层（`street_stair.json` 有 7 层），逐层形成台阶。
* `BuildingPart.getVslices()`（`BuildingPart.java:108-136`）把三维转成"每列一条竖字符串"，若某列**全为空格**则 `vslices[z*16+x] = null`，放置时整列跳过（`LostCityTerrainFeature.java:1760-1761`）：
  ```java
  1760:                char[] vs = part.getVSlice(x, z);
  1761:                if (vs != null) {
  ```

由此得出**街道部件边缘连接的可判定定义**：某条边被"打通"= 该边 16 个格中，至少有一格在 y=0 层写入了非空、非 `structure_void` 的方块。V1 部件用 `b`（structure_void）作为一个"不连接"的显式占位，用 `' '` 作为"整列不触碰"。

### 2.4 字符的含义

字符**不是方块**，是 palette 的 key。解析链：`generatePart` → `computePalette(info, part)` → `info.getCompiledPalette()`（由 CityStyle 的 `style` 决定的 chunk palette）**再叠加 part 自己的 palette**。

`LostCityTerrainFeature.java:1845-1853`

```java
1845:    public CompiledPalette computePalette(BuildingInfo info, IBuildingPart part) {
1846:        CompiledPalette compiledPalette = info.getCompiledPalette();
1847:        // Cache the combined palette?
1848:        Palette partPalette = part.getLocalPalette(provider.getWorld());
1849:        if (partPalette != null) {
1850:            compiledPalette = new CompiledPalette(compiledPalette, partPalette);
1851:        }
1852:        return compiledPalette;
1853:    }
```

所以：

| 字符 | 在默认链中的含义 | 证据 |
| --- | --- | --- |
| `' '`（空格） | **该格不写**（整列全空格时整列跳过；`b == air` 时也跳过） | `BuildingPart.java:122-128`；`LostCityTerrainFeature.java:1780` |
| `b` | `minecraft:structure_void` → `hardAir`；放置时**不覆盖已有方块**（`HardAirSetting.VOID`） | `palettes/default.json:37-40`；`LostCityTerrainFeature.java:104, 1785, 1800-1802` |
| `S` | `minecraft:smooth_stone_slab[type=double]`，`damaged: minecraft:iron_bars` | `palettes/default.json:32-36` |
| `_` / `=` | `minecraft:smooth_stone_slab[type=bottom]`（坡道踏步） | `palettes/default.json:61-68` |
| `B` | `minecraft:bricks`（`streetvariant`） | `palettes/default.json:41-45`；`citystyles/citystyle_common.json:9` |
| `w` | `minecraft:cobblestone_wall`（`wall`，边界墙） | `palettes/default.json:69-72`；`citystyle_common.json:6` |
| `y` | `variant: stonebrick`（`border`） | `palettes/default.json:51-55`；`citystyle_common.json:5` |
| `A t z c d ( ) /` | 各种楼梯朝向 | `palettes/default.json:77-108` |
| `l` | `minecraft:smooth_stone_slab[type=double]`（`street_large` 局部 palette） | `palettes/street_large.json:3-6` |
| `q` | `minecraft:smooth_quartz`（中央带） | `palettes/street_large.json:7-10` |
| `#` | 砖/陶瓦类，随风格变化（`bricks_*`），多数带 `damaged: iron_bars` | `palettes/bricks_cyan.json:14-17` 等 |
| `a` | 玻璃类（`glass_*`），无 `damaged` | `palettes/glass_full.json:12-14` 等 |

注意 `b` 的语义取决于放置时的 `HardAirSetting` 参数（见 §5.1）。街道部件统一用 `HardAirSetting.VOID`（`LostCityTerrainFeature.java:1572/1580/1646/1666`），所以 `b` = "保持世界原样"。

### 2.5 旋转如何存储

**部件 JSON 不存旋转。** 旋转是放置时的参数：

* 变体选择决定一个 `Transform`（`LostCityTerrainFeature.java:1591-1644`）。
* `generatePart` 用 `Transform.rotateX/rotateZ` 把局部 `(x,z)` 映射到区块内 `(rx,rz)`（`Transform.java:46-68`，常量 `15 - x` / `15 - z` 说明坐标系是 0..15）。
* 同时对角色的 `BlockState` 调用 `transformBlockState`（`LostCityTerrainFeature.java:1775-1777`）。

`Transform` 与方向的对应（`worldgen/lost/Direction.java:18-25`）：

```java
18:    public Transform getRotation() {
19:        return switch (this) {
20:            case XMIN -> Transform.ROTATE_NONE;
21:            case XMAX -> Transform.ROTATE_180;
22:            case ZMIN -> Transform.ROTATE_90;
23:            case ZMAX -> Transform.ROTATE_270;
24:        };
25:    }
```

方向本身（`lost/Direction.java:6-9, 36-44`；`BuildingInfo.java:249-282`）：`XMIN = coord.west() = x-1`，`XMAX = coord.east() = x+1`，`ZMIN = coord.north() = z-1`，`ZMAX = coord.south() = z+1`。

由 `street_bend.json` 反推的**未旋转约定**：`ROTATE_NONE` 时部件连接 `XMIN`（西，x=0 列）与 `ZMIN`（北，z=0 行）；`street_end.json` 的 `ROTATE_NONE` 连 `XMIN`；`street_t.json` 的 `ROTATE_NONE` 连 `XMIN+XMAX+ZMIN`。这与 `generateNormalStreetSection` 的分支完全一致（见 §4.4）。

### 2.6 `Slice` / `Part` 类型

7.5.5 **不存在** `Slice` 或 `Part` 类。相关类型只有：

* `IBuildingPart`（接口，`cityassets/IBuildingPart.java`）
* `BuildingPart`（实现，`cityassets/BuildingPart.java`）
* `BuildingPartRE`（codec 载体，`regassets/BuildingPartRE.java`）

"Slice" 概念对应 `BuildingPart.getSlice(int)` / `getSlices()`（`BuildingPart.java:163-169`）与 `getSliceCount()`（第 159-161 行）。

---

## 3. `palettes/*.json` 与损坏表（Q3）

### 3.1 字段全表

`reference/lostcities-src/src/main/java/mcjty/lostcities/worldgen/lost/regassets/PaletteRE.java:17-20`

```java
17:    public static final Codec<PaletteRE> CODEC = RecordCodecBuilder.create(instance ->
18:            instance.group(
19:                    Codec.list(PaletteEntry.CODEC).fieldOf("palette").forGetter(l -> l.paletteEntries)
20:            ).apply(instance, PaletteRE::new));
```

即 palette 文件只有**一个**顶层字段 `palette`（数组）。数组元素见 `regassets/data/PaletteEntry.java:18-30`：

```java
18:    public static final Codec<PaletteEntry> CODEC = RecordCodecBuilder.create(instance ->
19:            instance.group(
20:                    Codec.STRING.fieldOf("char").forGetter(PaletteEntry::getChr),
21:                    Codec.STRING.optionalFieldOf("block").forGetter(l -> Optional.ofNullable(l.getBlock())),
22:                    Codec.STRING.optionalFieldOf("variant").forGetter(l -> Optional.ofNullable(l.getVariant())),
23:                    Codec.STRING.optionalFieldOf("frompalette").forGetter(l -> Optional.ofNullable(l.getFrompalette())),
24:                    Codec.list(BlockEntry.CODEC).optionalFieldOf("blocks").forGetter(l -> Optional.ofNullable(l.getBlocks())),
25:                    Codec.STRING.optionalFieldOf("damaged").forGetter(l -> Optional.ofNullable(l.getDamaged())),
26:                    Codec.STRING.optionalFieldOf("mob").forGetter(l -> Optional.ofNullable(l.getMob())),
27:                    Codec.STRING.optionalFieldOf("loot").forGetter(l -> Optional.ofNullable(l.getLoot())),
28:                    Codec.BOOL.optionalFieldOf("torch").forGetter(l -> Optional.ofNullable(l.getTorch())),
29:                    CompoundTag.CODEC.optionalFieldOf("tag").forGetter(l -> Optional.ofNullable(l.getTag()))
30:            ).apply(instance, PaletteEntry::new));
```

`BlockEntry`（`regassets/data/BlockEntry.java:11-15`）：`{"random": int, "block": string}`，`random` 是**相对 128 的权重**（`CompiledPalette.java:52-62` 要求权重之和恰好铺满 128，否则抛 `Invalid palette entry for '…'! Not enough blocks in the random list`）。

`char` 只取**首字符**（`Palette.java:67`：`Character c = entry.getChr().charAt(0);`）。

### 3.2 逐字引用

`palettes/default.json`（全文）

```json
{
  "palette": [
    {
      "char": "R",
      "block": "minecraft:cyan_terracotta",
      "damaged": "minecraft:iron_bars"
    },
    {
      "char": ";",
      "block": "minecraft:furnace",
      "damaged": "minecraft:iron_bars",
      "tag": {
        "Items": [
          {
            "Slot": 0,
            "id": "minecraft:coal",
            "Count": 10
          }
        ]
      }
    },
    {
      "char": "Q",
      "block": "minecraft:quartz_block",
      "damaged": "minecraft:iron_bars"
    },
    {
      "char": "u",
      "block": "minecraft:smooth_stone_slab[type=double]",
      "damaged": "minecraft:iron_bars"
    },
    {
      "char": "S",
      "block": "minecraft:smooth_stone_slab[type=double]",
      "damaged": "minecraft:iron_bars"
    },
    {
      "char": "b",
      "block": "minecraft:structure_void"
    },
    {
      "char": "B",
      "block": "minecraft:bricks",
      "damaged": "minecraft:iron_bars"
    },
    {
      "char": "v",
      "variant": "stonebrick",
      "damaged": "minecraft:iron_bars"
    },
    {
      "char": "y",
      "variant": "stonebrick",
      "damaged": "minecraft:iron_bars"
    },
    {
      "char": "x",
      "variant": "stonebrick",
      "damaged": "minecraft:iron_bars"
    },
    {
      "char": "_",
      "block": "minecraft:smooth_stone_slab[type=bottom]"
    },
    {
      "char": "=",
      "block": "minecraft:smooth_stone_slab[type=bottom]"
    },
    {
      "char": "w",
      "block": "minecraft:cobblestone_wall"
    },
    {
      "char": ":",
      "block": "minecraft:iron_bars"
    },
    {
      "char": "A",
      "block": "minecraft:stone_brick_stairs[facing=west,half=bottom,shape=straight]"
    },
    {
      "char": "t",
      "block": "minecraft:stone_brick_stairs[facing=east,half=bottom,shape=straight]"
    },
    {
      "char": "z",
      "block": "minecraft:stone_brick_stairs[facing=north,half=bottom,shape=straight]"
    },
    {
      "char": "c",
      "block": "minecraft:stone_brick_stairs[facing=south,half=bottom,shape=straight]"
    },
    {
      "char": "d",
      "block": "minecraft:stone_brick_stairs[facing=north,half=top,shape=straight]"
    },
    {
      "char": "(",
      "block": "minecraft:quartz_stairs[facing=north,half=bottom,shape=straight]"
    },
    {
      "char": ")",
      "block": "minecraft:quartz_stairs[facing=south,half=bottom,shape=straight]"
    },
    {
      "char": "/",
      "block": "minecraft:stone_stairs[facing=west,half=bottom,shape=straight]"
    }
  ]
}
```

`palettes/street_large.json`（全文）

```json
{
  "palette": [
    {
      "char": "l",
      "block": "minecraft:smooth_stone_slab[type=double]"
    },
    {
      "char": "q",
      "block": "minecraft:smooth_quartz"
    }
  ]
}
```

`street_large.json` **没有 `damaged` 字段**——但 `l` 的方块状态与 `default.json` 的 `S` 完全相同（`minecraft:smooth_stone_slab[type=double]`），损坏表按状态合并后会命中 `S` 的规则（见 3.4）。

### 3.3 `Palette` → `CompiledPalette` 的构建

`cityassets/Palette.java:65-114` 解析每条 entry：

```java
75:            if (entry.getBlock() != null) {
76:                String block = entry.getBlock();
77:                BlockState state = Tools.stringToState(block);
78:                palette.put(c, new PE(state, info));
79:                if (dmg != null) {
80:                    damaged.put(state, dmg);
81:                }
82:            } else if (entry.getVariant() != null) {
...
87:                List<Pair<Integer, BlockState>> blocks = variant.getBlocks();
88:                if (dmg != null) {
89:                    for (Pair<Integer, BlockState> pair : blocks) {
90:                        damaged.put(pair.getRight(), dmg);
91:                    }
92:                }
...
94:            } else if (entry.getFrompalette() != null) {
95:                String value = entry.getFrompalette();
96:                palette.put(c, new PE(value, info));
97:            } else if (entry.getBlocks() != null) {
...
105:                    if (dmg != null) {
106:                        damaged.put(state, dmg);
107:                    }
...
110:            } else {
111:                throw new RuntimeException("Illegal palette " + name + "!");
112:            }
```

`frompalette` 是"字符别名"，在 `CompiledPalette.addPalettes` 里用不动点迭代解析（`CompiledPalette.java:76-96`）：

```java
85:                        if (pe.blocks() instanceof String blocks) {
86:                            char c = blocks.charAt(0);
87:                            if (palette.containsKey(c) && !palette.containsKey(entry.getKey())) {
88:                                palette.put(entry.getKey(), palette.get(c));
```

`CompiledPalette` 的 `palette` 映射值是三种之一（`CompiledPalette.java:42-74`）：`BlockState`、`BlockState[]`（长度 128 的随机表），或尚未解析的 `String`（别名）。
取用：`get(char)`（第 168-182 行）用 `GenerationContext.nextPaletteIndex()` 索引随机表；`get(char, Random)`（第 134-150 行）用给定随机源。

**随机表的 128 上限**：`CompiledPalette.java:52-62`

```java
52:                        BlockState[] randomBlocks = new BlockState[128];
...
61:                        if (idx < randomBlocks.length) {
62:                            throw new RuntimeException("Invalid palette entry for '" + entry.getKey() + "'! Not enough blocks in the random list (factor should go up to 128)");
```

### 3.4 `damaged` 的键：**最终 `BlockState`，不是模板字符**（确认）

三层证据：

1. 写入：`Palette.java:80`（`block` 分支）、`:90`（`variant` 分支）、`:106`（`blocks` 分支）都是 `damaged.put(state, dmg)`，key 是 `Tools.stringToState(...)` 的产物。
2. 合并：`CompiledPalette.java:98-103`
   ```java
   98:        for (Palette p : palettes) {
   99:            if (p != null) {
   100:                for (Map.Entry<BlockState, BlockState> entry : p.getDamaged().entrySet()) {
   101:                    BlockState c = entry.getKey();
   102:                    damagedToBlock.put(c, entry.getValue());
   103:                }
   ```
   这是一个 `Map<BlockState, BlockState>`（第 18 行声明），按 palette 顺序 `put`，**同名状态后写覆盖先写**。
3. 查询：`CompiledPalette.java:184-186`
   ```java
   184:    public BlockState canBeDamagedToIronBars(BlockState b) {
   185:        return damagedToBlock.get(b);
   186:    }
   ```
   `BlockState` 是单例（`Block.BLOCK_STATE_REGISTRY`），所以 key 是状态对象身份，`getBlock()`/属性全等才算命中。**两个不同字符指向同一方块状态 → 共享同一条 damaged 规则。**

**重要推论（对 V2 关键）**：`DamageArea.damageBlock` 与 `generateRuins` 用的都是 **chunk 级** `info.getCompiledPalette()`，而 `computePalette` 产生的"chunk + part 局部 palette"合并体**不缓存、只在 `generatePart` 内部使用**（`LostCityTerrainFeature.java:1845-1853` 的注释 `// Cache the combined palette?`），因此：

* **写在 part `palette`（内联或 `refpalette`）里的 `damaged` 规则对爆炸/废墟阶段无效。**
* 想让 V2 路面材料拥有受控的 damaged 行为，必须把该状态通过 CityStyle 的 `style` → `styles/*.json` → `palettes/*.json` 引入 **chunk palette**。

调用点：`LostCityTerrainFeature.java:484`

```java
484:                                    BlockState newd = damageArea.damageBlock(d, provider, getRandom(), cury, damage, info.getCompiledPalette(), liquid);
```

`LostCityTerrainFeature.java:1107`（`generateRuins`）同样用 chunk palette：

```java
1107:        CompiledPalette palette = info.getCompiledPalette();
...
1134:                    BlockState damage = palette.canBeDamagedToIronBars(getDriver().getBlock());
...
1140:                    } else if ((damage != null || checkIronbars.test(c)) && c != air && c != liquid && random.nextFloat() < .2f) {
1141:                        getDriver().add(ironbars.get());
```

即只要有 damaged 映射，废墟阶段就有 20%/列的概率把路面换成 `iron_bars`——这正是设计文档所说"会让道路表面突起"的机制。

### 3.5 没有损坏映射时的代码路径（逐字）

`worldgen/lost/DamageArea.java:73-95`

```java
73:    public BlockState damageBlock(BlockState b, IDimensionInfo provider, RandomSource random, int y, float damage, CompiledPalette palette, BlockState liquidChar) {
74:        if (Tools.hasTag(b.getBlock(), LostTags.NOT_BREAKABLE_TAG)) {
75:            return b;
76:        }
77:
78:        if (Tools.hasTag(b.getBlock(), LostTags.EASY_BREAKABLE_TAG)) {
79:            damage *= 2.5f;    // As if this block gets double the damage
80:        }
81:        if (random.nextFloat() <= damage) {
82:            BlockState damaged = palette.canBeDamagedToIronBars(b);
83:            int waterlevel = Tools.getSeaLevel(provider.getWorld());//profile.GROUNDLEVEL - profile.WATERLEVEL_OFFSET;
84:            if (damage < BLOCK_DAMAGE_CHANCE && damaged != null) {
85:                if (random.nextFloat() < .7f) {
86:                    b = damaged;
87:                } else {
88:                    b = y <= waterlevel ? liquidChar : air;
89:                }
90:            } else {
91:                b = y <= waterlevel ? liquidChar : air;
92:            }
93:        }
94:        return b;
95:    }
```

`BLOCK_DAMAGE_CHANCE = .7f`（`DamageArea.java:22`）。**`damaged == null` 时走第 90-91 行：方块直接变空气（或水线以下变液体）——它不是"保留原方块"，而是"被摧毁"。**

### 3.6 V2 候选材料的实测推论

对内置 palette 全量 grep（`data/lostcities/lostcities/palettes/`）：

* `minecraft:gray_concrete` **只**出现在 `palettes/common.json:43`，位于字符 `9` 的 `blocks` 随机表内，且该条目**没有** `damaged` 字段 → `gray_concrete` 在损坏表中**无映射**。若 V2 车行面用它，爆炸/废墟流程会把它变成空气（`DamageArea.java:91`）而不是铁栏杆；设计文档要求"不承诺爆炸后无断洞"正是这种情况。
* `minecraft:light_gray_concrete`、`minecraft:smooth_stone`（非 slab）、`minecraft:polished_andesite` 在内置 palette 中**完全没有出现** → 同样无映射。
* `minecraft:smooth_stone_slab[type=double]`（V1 的 `S`/`l`）带 `damaged: iron_bars`（`default.json:30`、`bricks_border.json:11`、`bricks_standard.json:11`）。

结论：V2 若要保持"不突起"，两条路可选——(a) 接受"被打成空气/洞"（与设计文档的说明一致，不需要额外工作）；(b) 在 V2 专用 Style 的 chunk palette 里为每个 V2 状态显式声明 `damaged`（例如同高度的 `minecraft:gray_concrete_powder`? 不，非重力要求 → 只能用同为满高度、无随机 tick 的方块，如 `minecraft:andesite`），并注意**同一状态只能有一条规则，后写覆盖**。

---

## 4. 选择机制（Q4）

### 4.1 `CityStyle` 字段全表

JSON 侧，`regassets/CityStyleRE.java:14-31`：

```java
14:    public static final Codec<CityStyleRE> CODEC = RecordCodecBuilder.create(instance ->
15:            instance.group(
16:                    Codec.FLOAT.optionalFieldOf("explosionchance").forGetter(l -> Optional.ofNullable(l.explosionChance)),
17:                    Codec.STRING.optionalFieldOf("style").forGetter(l -> Optional.ofNullable(l.style)),
18:                    Codec.STRING.optionalFieldOf("inherit").forGetter(l -> Optional.ofNullable(l.inherit)),
19:                    Codec.STRING.listOf().optionalFieldOf("stuff_tags").forGetter(l -> Optional.ofNullable(l.stuffTags)),
20:                    Codec.STRING.optionalFieldOf("bridgesupport").forGetter(l -> DataTools.toNullable(l.bridgeSupport)),
21:                    Codec.STRING.optionalFieldOf("bridgesupportpart").forGetter(l -> Optional.ofNullable(l.bridgeSupportPart)),
22:                    CityProfileOverrides.CODEC.optionalFieldOf("profile_overrides").forGetter(l -> Optional.ofNullable(l.profileOverrides)),
23:                    GeneralSettings.CODEC.optionalFieldOf("generalblocks").forGetter(l -> Optional.ofNullable(l.generalSettings)),
24:                    BuildingSettings.CODEC.optionalFieldOf("buildingsettings").forGetter(l -> Optional.ofNullable(l.buildingSettings)),
25:                    CorridorSettings.CODEC.optionalFieldOf("corridorblocks").forGetter(l -> Optional.ofNullable(l.corridorSettings)),
26:                    ParkSettings.CODEC.optionalFieldOf("parkblocks").forGetter(l -> Optional.ofNullable(l.parkSettings)),
27:                    RailSettings.CODEC.optionalFieldOf("railblocks").forGetter(l -> Optional.ofNullable(l.railSettings)),
28:                    SphereSettings.CODEC.optionalFieldOf("sphereblocks").forGetter(l -> Optional.ofNullable(l.sphereSettings)),
29:                    StreetSettings.CODEC.optionalFieldOf("streetblocks").forGetter(l -> Optional.ofNullable(l.streetSettings)),
30:                    Selectors.CODEC.optionalFieldOf("selectors").forGetter(l -> Optional.ofNullable(l.selectors))
31:            ).apply(instance, CityStyleRE::new));
```

| 顶层字段 | 内容 |
| --- | --- |
| `explosionchance` | float，覆盖 `DamageArea` 里爆炸生效概率（`DamageArea.java:52`） |
| `style` | 字符串，指向 `lostcities:styles`（决定 chunk palette；`BuildingInfo.createPalette` `:238-247`） |
| `inherit` | 字符串，父 CityStyle；`CityStyle.init` 做字段级回填（`:348-474`） |
| `stuff_tags` | 字符串数组，追加到默认的 `"all"`（`CityStyle.java:95-98`） |
| `bridgesupport` / `bridgesupportpart` | 桥墩字符 / 桥墩部件名（`Bridges.java:71-79`） |
| `profile_overrides` | `CityProfileOverrides` |
| `generalblocks` | `GeneralSettings` |
| `buildingsettings` | `BuildingSettings` |
| `corridorblocks` | `CorridorSettings` |
| `parkblocks` | `ParkSettings` |
| `railblocks` | `RailSettings` |
| `sphereblocks` | `SphereSettings` |
| `streetblocks` | **`StreetSettings`**（街道，见 4.2） |
| `selectors` | `Selectors`：`buildings`/`bridges`/`largebridges`/`parks`/`fountains`/`stairs`/`fronts`/`raildungeons`/`multibuildings`（`regassets/data/Selectors.java:23-34`） |

Java 侧 `CityStyle` 的街道字段与 getter（`cityassets/CityStyle.java`）：

```java
31:    private StreetParts streetParts = StreetParts.DEFAULT;
32:    private StreetParts largeStreetParts = StreetParts.DEFAULT;
33:    private StreetParts tertiaryStreetParts = StreetParts.DEFAULT;
...
45:    private Integer streetWidth;
...
189:    public StreetParts getStreetParts() {
190:        return streetParts;
191:    }
192:
193:    public StreetParts getLargeStreetParts() {
194:        return largeStreetParts;
195:    }
196:
197:    public StreetParts getTertiaryStreetParts() {
198:        return tertiaryStreetParts == StreetParts.DEFAULT ? streetParts : tertiaryStreetParts;
199:    }
```

`getStreetWidth()` 返回 `int`（`CityStyle.java:184-187`，`ILostCityCityStyle` 第 9 行声明 `int getStreetWidth()`）——若 JSON 未写 `width`，这里会 NPE 拆箱，**但街道铺设路径并不读它**（设计文档已指出 `width=8` 不参与几何）。

`inherit` 的回填包含街道部件（`CityStyle.java:375-386`）：

```java
375:                if (streetWidth == null) {
376:                    streetWidth = inheritFrom.streetWidth;
377:                }
378:                if (streetParts == StreetParts.DEFAULT) {
379:                    streetParts = inheritFrom.streetParts;
380:                }
381:                if (largeStreetParts == StreetParts.DEFAULT) {
382:                    largeStreetParts = inheritFrom.largeStreetParts;
383:                }
384:                if (tertiaryStreetParts == StreetParts.DEFAULT) {
385:                    tertiaryStreetParts = inheritFrom.tertiaryStreetParts;
386:                }
```

即：**"等于 DEFAULT"被当作"未设置"**。这是继承语义的关键，也是不能用一个"恰好等于 DEFAULT 的自定义列表"表达 V1 默认行为的原因。

### 4.2 `StreetSettings` 与 `StreetParts`

`regassets/data/StreetSettings.java:11-37`

```java
11:public class StreetSettings {
12:    private final Float fountainChance;
13:    private final Float frontChance;
14:    private final Integer streetWidth;
15:    private final Character streetBlock;
16:    private final Character streetBaseBlock;
17:    private final Character streetVariantBlock;
18:    private final Character borderBlock;
19:    private final Character wallBlock;
20:    private final StreetParts parts;
21:    private final StreetParts largeParts;
22:    private final StreetParts tertiaryParts;
23:
24:    public static final Codec<StreetSettings> CODEC = RecordCodecBuilder.create(instance ->
25:            instance.group(
26:                    Codec.FLOAT.optionalFieldOf("fountainchance").forGetter(l -> Optional.ofNullable(l.fountainChance)),
27:                    Codec.FLOAT.optionalFieldOf("frontchance").forGetter(l -> Optional.ofNullable(l.frontChance)),
28:                    Codec.INT.optionalFieldOf("width").forGetter(l -> Optional.ofNullable(l.streetWidth)),
29:                    Codec.STRING.optionalFieldOf("street").forGetter(l -> DataTools.toNullable(l.streetBlock)),
30:                    Codec.STRING.optionalFieldOf("streetbase").forGetter(l -> DataTools.toNullable(l.streetBaseBlock)),
31:                    Codec.STRING.optionalFieldOf("streetvariant").forGetter(l -> DataTools.toNullable(l.streetVariantBlock)),
32:                    Codec.STRING.optionalFieldOf("border").forGetter(l -> DataTools.toNullable(l.borderBlock)),
33:                    Codec.STRING.optionalFieldOf("wall").forGetter(l -> DataTools.toNullable(l.wallBlock)),
34:                    StreetParts.CODEC.optionalFieldOf("parts").forGetter(l -> l.parts.get()),
35:                    StreetParts.CODEC.optionalFieldOf("largeparts").forGetter(l -> l.largeParts.get()),
36:                    StreetParts.CODEC.optionalFieldOf("tertiaryparts").forGetter(l -> l.tertiaryParts.get())
37:            ).apply(instance, StreetSettings::new));
```

缺省（`StreetSettings.java:94-104`）：

```java
102:        this.parts = parts.orElse(StreetParts.DEFAULT);
103:        this.largeParts = largeParts.orElse(StreetParts.DEFAULT);
104:        this.tertiaryParts = tertiaryParts.orElse(StreetParts.DEFAULT);
```

`StreetParts`（`regassets/data/StreetParts.java:10-44`，全文）

```java
10:public record StreetParts(List<String> full, List<String> straight, List<String> end, List<String> bend,
11:                          List<String> t, List<String> none, List<String> all, List<String> connector,
12:                          List<String> stair) {
13:
14:    public static final Codec<StreetParts> CODEC = RecordCodecBuilder.create(instance -> instance.group(
15:            Tools.listOrStringList("full", "street_full", StreetParts::full),
16:            Tools.listOrStringList("straight", "street_straight", StreetParts::straight),
17:            Tools.listOrStringList("end", "street_end", StreetParts::end),
18:            Tools.listOrStringList("bend", "street_bend", StreetParts::bend),
19:            Tools.listOrStringList("t", "street_t", StreetParts::t),
20:            Tools.listOrStringList("none", "street_none", StreetParts::none),
21:            Tools.listOrStringList("all", "street_all", StreetParts::all),
22:            Tools.listOrStringList("connector", "street_large_connector", StreetParts::connector),
23:            Tools.listOrStringList("stair", "street_stair", StreetParts::stair))
24:            .apply(instance, StreetParts::new)
25:    );
26:
27:    public static final StreetParts DEFAULT = new StreetParts(
28:            List.of("street_full"),
29:            List.of("street_straight"),
30:            List.of("street_end"),
31:            List.of("street_bend"),
32:            List.of("street_t"),
33:            List.of("street_none"),
34:            List.of("street_all"),
35:            List.of("street_large_connector"),
36:            List.of("street_stair"));
37:
38:    public Optional<StreetParts> get() {
39:        if (this == DEFAULT) {
40:            return Optional.empty();
41:        } else {
42:            return Optional.of(this);
43:        }
44:    }
```

9 个槽位 = `full/straight/end/bend/t/none/all/connector/stair`。每个槽位是**字符串或字符串数组**（`Tools.listOrStringList`，`varia/Tools.java:140-145`）：

```java
140:    public static <T> RecordCodecBuilder<T, List<String>> listOrStringList(String fieldName, String defaultVal, Function<T, List<String>> getter) {
141:        return Codec.either(Codec.STRING, Codec.STRING.listOf())
142:                .optionalFieldOf(fieldName, Either.left(defaultVal))
143:                .xmap(either -> either.map(List::of, Function.identity()), list -> list.size() == 1 ? Either.left(list.get(0)) : Either.right(list))
144:                .forGetter(getter);
145:    }
```

数组 = 候选池，放置时**随机抽一个**（`LostCityTerrainFeature.java:519-525`）：

```java
519:    public String getRandomPart(List<String> parts) {
520:        if (parts.size() == 1) {
521:            return parts.get(0);
522:        } else {
523:            return parts.get(GenerationContext.current().random().nextInt(parts.size()));
524:        }
525:    }
```

内置实例（`citystyles/citystyle_common.json:4-21`，逐字）：

```json
  "streetblocks": {
    "border": "y",
    "wall": "w",
    "street": "S",
    "streetbase": "b",
    "streetvariant": "B",
    "largeparts": {
      "full": "street_large_full",
      "straight": "street_large_straight",
      "end": "street_large_end",
      "bend": "street_large_bend",
      "t": "street_large_t",
      "none": "street_large_none",
      "all": "street_large_all",
      "connector": "street_large_connector",
      "stair": "street_large_stair"
    }
  },
```

（`citystyle_config.json:2-4` 只放 `"width": 8`；`parts` 与 `tertiaryparts` 在整个内置资产集中**从未出现**。）

### 4.3 `getStreetParts`：large/tertiary/parts 的选取

`LostCityTerrainFeature.java:1671-1677`（全文）

```java
1671:    private static StreetParts getStreetParts(BuildingInfo info) {
1672:        return switch (info.plannedRoadType) {
1673:            case PRIMARY -> info.getCityStyle().getLargeStreetParts();
1674:            case TERTIARY -> info.getCityStyle().getTertiaryStreetParts();
1675:            default -> info.getCityStyle().getStreetParts();
1676:        };
1677:    }
```

**注意三点**：

1. `SECONDARY` 落进 `default`，与 `NONE` **共用同一个 `parts` 列表**——V1 没有二级路的独立资产槽。
2. `getLargeStreetParts()` 不做 DEFAULT 回退；若某 CityStyle 没写 `largeparts` 也没有 `inherit`，PRIMARY 会去用**普通小街**部件（`StreetParts.DEFAULT`）。ExtractionCities 的 `single_building_test` 正是这种情况。
3. `getTertiaryStreetParts()` 在未设置时回退到 `streetParts`（`CityStyle.java:197-199`）。

### 4.4 变体如何从邻居连接性选出（`generateNormalStreetSection` 逐分支）

`LostCityTerrainFeature.java:1584-1649`（全文）

```java
1584:    private void generateNormalStreetSection(BuildingInfo info, int height) {
1585:        StreetParts parts = getStreetParts(info);
1586:        boolean xmin = hasStreetPartConnection(info, info.getXmin(), info.getXmin().hasXBridge(provider) != null);
1587:        boolean xmax = hasStreetPartConnection(info, info.getXmax(), info.getXmax().hasXBridge(provider) != null);
1588:        boolean zmin = hasStreetPartConnection(info, info.getZmin(), info.getZmin().hasZBridge(provider) != null);
1589:        boolean zmax = hasStreetPartConnection(info, info.getZmax(), info.getZmax().hasZBridge(provider) != null);
1590:        int cnt = (xmin ? 1 : 0) + (xmax ? 1 : 0) + (zmin ? 1 : 0) + (zmax ? 1 : 0);
1591:        Transform transform = Transform.ROTATE_NONE;
1592:        BuildingPart part = switch (cnt) {
1593:            case 0 -> AssetRegistries.PARTS.getOrWarn(provider.getWorld(), getRandomPart(parts.none()));
1594:            case 1 -> {
1595:                BuildingPart p = AssetRegistries.PARTS.getOrWarn(provider.getWorld(), getRandomPart(parts.end()));
1596:                if (xmin) {
1597:                } else if (xmax) {
1598:                    transform = Transform.ROTATE_180;
1599:                } else if (zmin) {
1600:                    transform = Transform.ROTATE_90;
1601:                } else {
1602:                    transform = Transform.ROTATE_270;
1603:                }
1604:                yield p;
1605:            }
1606:            case 2 -> {
1607:                if (xmin == xmax || zmin == zmax) {
1608:                    BuildingPart p = AssetRegistries.PARTS.getOrWarn(provider.getWorld(), getRandomPart(parts.straight()));
1609:                    if (xmin) {
1610:                    } else if (xmax) {
1611:                        transform = Transform.ROTATE_180;
1612:                    } else if (zmin) {
1613:                        transform = Transform.ROTATE_90;
1614:                    } else {
1615:                        transform = Transform.ROTATE_270;
1616:                    }
1617:                    yield p;
1618:                } else {
1619:                    BuildingPart p = AssetRegistries.PARTS.getOrWarn(provider.getWorld(), getRandomPart(parts.bend()));
1620:                    if (xmin && zmin) {
1621:                    } else if (xmin && zmax) {
1622:                        transform = Transform.ROTATE_270;
1623:                    } else if (xmax && zmin) {
1624:                        transform = Transform.ROTATE_90;
1625:                    } else {
1626:                        transform = Transform.ROTATE_180;
1627:                    }
1628:                    yield p;
1629:                }
1630:            }
1631:            case 3 -> {
1632:                BuildingPart p = AssetRegistries.PARTS.getOrWarn(provider.getWorld(), getRandomPart(parts.t()));
1633:                if (!xmin) {
1634:                    transform = Transform.ROTATE_90;
1635:                } else if (!xmax) {
1636:                    transform = Transform.ROTATE_270;
1637:                } else if (!zmin) {
1638:                    transform = Transform.ROTATE_180;
1639:                }
1640:                yield p;
1641:            }
1642:            case 4 -> AssetRegistries.PARTS.getOrWarn(provider.getWorld(), getRandomPart(parts.all()));
1643:            default -> throw new RuntimeException("Not possible!");
1644:        };
1645:        if (part != null) {
1646:            generatePart(info, part, transform, 0, height, 0, HardAirSetting.VOID);
1647:            generateMinorStreetConnectors(info, parts, height);
1648:        }
1649:    }
```

连接性判定（`LostCityTerrainFeature.java:1679-1689`）：

```java
1679:    private static boolean hasStreetPartConnection(BuildingInfo info, BuildingInfo adjacent, boolean bridgeConnection) {
1680:        boolean roadConnection = BuildingInfo.hasRoadConnection(info, adjacent);
1681:        if (info.isPrimaryRoad()) {
1682:            // Minor streets still meet the primary road surface, but they must
1683:            // not turn its quartz center line into a bend or junction.
1684:            // A planned primary bridge is also a continuation of the primary
1685:            // road, so its endpoint keeps the straight large-road part.
1686:            return (roadConnection && adjacent.isPrimaryRoad()) || bridgeConnection;
1687:        }
1688:        return roadConnection || bridgeConnection;
1689:    }
```

`BuildingInfo.hasRoadConnection`（`BuildingInfo.java:1803-1817`）：

```java
1803:    public static boolean hasRoadConnection(BuildingInfo i1, BuildingInfo i2) {
1804:        if (!i1.doesRoadExtendTo()) {
1805:            return false;
1806:        }
1807:        if (!i2.doesRoadExtendTo()) {
1808:            return false;
1809:        }
1810:        if (i1.cityLevel == i2.cityLevel) {
1811:            return true;
1812:        }
1813:        Direction slope1 = i1.getStreetSlopeDirection();
1814:        Direction slope2 = i2.getStreetSlopeDirection();
1815:        return slope1 != null && slope1.get(i1).coord.equals(i2.coord)
1816:                || slope2 != null && slope2.get(i2).coord.equals(i1.coord);
1817:    }
```

`doesRoadExtendTo`（`BuildingInfo.java:1791-1800`）在 `HIERARCHICAL_GRID_V1` 下 = `isCity && !hasBuilding && (isPlannedRoad() || predefinedStreet)`。

**变体判定汇总（V1 语义）**

| `cnt` | 条件 | 变体槽 | `Transform` |
| --- | --- | --- | --- |
| 0 | 无任何连接 | `none` | `ROTATE_NONE` |
| 1 | 仅 XMIN | `end` | `ROTATE_NONE` |
| 1 | 仅 XMAX | `end` | `ROTATE_180` |
| 1 | 仅 ZMIN | `end` | `ROTATE_90` |
| 1 | 仅 ZMAX | `end` | `ROTATE_270` |
| 2 | XMIN+XMAX 或 ZMIN+ZMAX | `straight` | `ROTATE_NONE` / `180`（X 轴）· `90` / `270`（Z 轴） |
| 2 | 任意相邻一对 | `bend` | `XMIN+ZMIN→NONE`；`XMIN+ZMAX→270`；`XMAX+ZMIN→90`；`XMAX+ZMAX→180` |
| 3 | 缺 XMIN | `t` | `ROTATE_90` |
| 3 | 缺 XMAX | `t` | `ROTATE_270` |
| 3 | 缺 ZMIN | `t` | `ROTATE_180` |
| 3 | 缺 ZMAX | `t` | `ROTATE_NONE` |
| 4 | 全连 | `all` | `ROTATE_NONE` |

**主干（PRIMARY）的额外规则**：只有"邻居也是 PRIMARY"的连接才算数（`hasStreetPartConnection` 第 1686 行）。所以一个 PRIMARY 格被四个 SECONDARY 包围时 `cnt = 0` → `street_large_none`，而该部件是 14×14 满铺（`parts/street_large_none.json`），并不会封口——这正是 V2 要补的 29 号资产。

**次要连接子的后处理**（`LostCityTerrainFeature.java:1651-1669`）：

```java
1651:    private void generateMinorStreetConnectors(BuildingInfo info, StreetParts parts, int height) {
1652:        if (!info.isPrimaryRoad() || parts.connector().isEmpty()) {
1653:            return;
1654:        }
1655:        generateMinorStreetConnector(info, info.getXmin(), parts, height, Transform.ROTATE_NONE);
1656:        generateMinorStreetConnector(info, info.getXmax(), parts, height, Transform.ROTATE_180);
1657:        generateMinorStreetConnector(info, info.getZmin(), parts, height, Transform.ROTATE_90);
1658:        generateMinorStreetConnector(info, info.getZmax(), parts, height, Transform.ROTATE_270);
1659:    }
1660:
1661:    private void generateMinorStreetConnector(BuildingInfo info, BuildingInfo adjacent, StreetParts parts,
1662:                                              int height, Transform transform) {
1663:        if (BuildingInfo.hasRoadConnection(info, adjacent) && !adjacent.isPrimaryRoad()) {
1664:            BuildingPart connector = AssetRegistries.PARTS.getOrWarn(provider.getWorld(), getRandomPart(parts.connector()));
1665:            if (connector != null) {
1666:                generatePart(info, connector, transform, 0, height, 0, HardAirSetting.VOID);
1667:            }
1668:        }
1669:    }
```

`street_large_connector.json` 只在 `x=0, z=4..11` 写一列 `l`——就是设计文档批评的"单列 connector"。

**变体路径的入口条件**（`LostCityTerrainFeature.java:1196-1212`）：

```java
1196:            } else if (!info.isHierarchicalOpen()) {
1197:                Random rnd = new Random(info.coord.chunkZ() * 155557723L + info.coord.chunkX() * 45555558379L);
1198:                info.streetType = BuildingInfo.StreetType.values()[rnd.nextInt(0, BuildingInfo.StreetType.values().length - 2)];
1199:                streetType = info.streetType;
1200:            }
1201:
1202:            switch (streetType) {
1203:                case NORMAL -> {
1204:                    if (streetSlopeDirection == null) {
1205:                        generateNormalStreetSection(info, height);
1206:                    } else {
1207:                        generateStreetSlopeSection(info, height, streetSlopeDirection);
1208:                    }
1209:                }
1210:                case FULL -> generateFullStreetSection(info, height);
1211:                case PARK -> generateParkSection(info, height, elevated);
1212:            }
```

配合 `BuildingInfo.java:921-940`：V1 模式下 `streetType` 只可能是 `NORMAL`（规划路）或 `PARK`（未规划的空地，`hierarchicalOpen`）。`StreetType` 定义在 `BuildingInfo.java:2156-2160`（`NORMAL, FULL, PARK`）。

### 4.5 V1 JSON 的表达能力边界

把 §4.1–4.4 合起来看，V1 的 JSON 选择面只有：

* `CityStyle.streetblocks.parts` / `largeparts` / `tertiaryparts` → 各 9 个字符串槽（可为候选数组，随机抽取）；
* `WorldStyle.citystyles[]` → CityStyle 的选择；
* 没有任何字段能表达 `(本格 roadType, 四向 edgeClass)`。

而且 `StreetParts` 的槽位语义被硬编码在 `generateNormalStreetSection` 的 `switch (cnt)` 里（只有 `cnt` 与"哪条边"参与），**没有任何扩展点**。因此：

> **用纯 JSON 无法交付 V2 的 28 个 `(roadType, edgeClass[N/E/S/W])` 键。** 必须新增代码层选择入口。V1 JSON（`parts`/`largeparts`/`tertiaryparts` 的既有内容）不需要、也不应该被改写。

### 4.6 附属模组如何自带 `(roadType, edgeClass[N/E/S/W])` 键

**可用输入全是 public**：

| 输入 | 位置 |
| --- | --- |
| 本格路类 | `BuildingInfo.plannedRoadType`（`public final PlannedRoadType`，`BuildingInfo.java:61`）；`rawPlannedRoadType`（第 60 行） |
| 是否主干 | `BuildingInfo.isPrimaryRoad()`（`:1389-1391`） |
| 四邻 | `getXmin()/getXmax()/getZmin()/getZmax()`（`:249-282`）→ 西/东/北/南 |
| 双向边判定 | `BuildingInfo.hasRoadConnection(info, adjacent)`（`public static`，`:1803`） |
| 桥 | `hasXBridge/hasZBridge/hasBridge`（`:1534-1643`） |
| 街道面高度 | `getCityGroundLevel()`（`:298-300`） |

**唯一需要 Mixin 的步骤是"拦截变体选择"**，因为 `getStreetParts`/`generateNormalStreetSection` 都是 `private`。在本仓库的约束（只允许 `@Inject`/`@WrapOperation`/`@Unique`/`@Shadow`/`@Accessor`/`@Invoker`/`@Implements`）下，唯一干净的做法是：

```java
@Mixin(value = LostCityTerrainFeature.class, remap = false)
public abstract class MixinStreetSection {
    @Shadow public abstract int generatePart(BuildingInfo info, IBuildingPart part, Transform transform,
                                             int ox, int oy, int oz, LostCityTerrainFeature.HardAirSetting air);

    @Inject(method = "generateNormalStreetSection", at = @At("HEAD"), cancellable = true)
    private void citylines$v2Section(BuildingInfo info, int height, CallbackInfo ci) {
        if (RoadHooks.tryGenerateV2Section(info, height, this::generatePart)) {
            ci.cancel();     // 跳过 V1 部件选择 + generateMinorStreetConnectors
        }
    }
}
```

要点：

1. `generatePart` 是 `public`（`LostCityTerrainFeature.java:1747`），可以直接 `@Shadow`；`HardAirSetting` 是 `public enum`（`:1739-1741`）。
2. `ci.cancel()` 同时跳过第 1647 行的 `generateMinorStreetConnectors`——**这正是 V2 想要的**（不再需要单列 connector）。
3. V2 的键→(部件名, `Transform`) 表放在**附属模组自己的数据**里，最自然的两种载体：
   * 自建数据包注册表：在 mod 事件总线监听 `DataPackRegistryEvent.NewRegistry`，`event.dataPackRegistry(new ResourceKey<>(…, new ResourceLocation("citylines","roadparts")), CODEC)`；目录会是 `data/<ns>/citylines/roadparts/*.json`（同一套 `prefixNamespace` 规则）。**注意：这条注册表是 Citylines 自己的，TLC 不需要知道。**
   * 或普通 JSON reload listener（`AddReloadListenerEvent` + `SimpleJsonResourceReloadListener`），但世界生成线程需要能拿到，必须缓存到静态只读快照。
4. `edgeClass(方向) = roadType.isRoad() && neighbour.isRoad() ? min(...) : NONE`；判定"双方都有计划边"必须用**双侧 mask**（`hasRoadConnection` 已经是双向的；再叠加本格 mask 为空则一律 `NONE`）。中心类用 `plannedRoadType`，`TERTIARY` 不属于 V2 签名（方案第 3 节）。
5. V2 必须自己决定"是否要走 V2 路径"：`provider.getStreetGenerationMode() == HIERARCHICAL_GRID_V1` 且本维度 V2 开关为真，且 `info.isPlannedRoad()`；否则**必须放行给 V1**（不 cancel），保证 V1 维度逐位一致。
6. 若不想改 `generateNormalStreetSection`，唯一等效点是 `generateStreet`（`private`）或 `doCityChunk`；但前者的 HEAD 早于 `streetType` 解析与 `height++`，需要复制更多逻辑，不如前者干净。
7. **更权威的四向 mask 来源**：`IDimensionInfo.getStreetPlanner()`（`worldgen/IDimensionInfo.java:41`）→ `HierarchicalStreetPlanner`（`public final`）已经暴露逐方向的规划连通性：
   ```java
   // worldgen/street/HierarchicalStreetPlanner.java:57-71
   57:    public PlannedRoadType getRoadType(int chunkX, int chunkZ) {
   58:        return rawAt(chunkX, chunkZ).roadType;
   59:    }
   61:    public PlannedStreetInfo getStreetInfo(int chunkX, int chunkZ) {
   62:        RawRoad raw = rawAt(chunkX, chunkZ);
   63:        BlockLayout block = raw.block;
   64:        boolean north = raw.roadType != PlannedRoadType.NONE && getRoadType(chunkX, chunkZ - 1) != PlannedRoadType.NONE;
   65:        boolean south = raw.roadType != PlannedRoadType.NONE && getRoadType(chunkX, chunkZ + 1) != PlannedRoadType.NONE;
   66:        boolean west = raw.roadType != PlannedRoadType.NONE && getRoadType(chunkX - 1, chunkZ) != PlannedRoadType.NONE;
   67:        boolean east = raw.roadType != PlannedRoadType.NONE && getRoadType(chunkX + 1, chunkZ) != PlannedRoadType.NONE;
   68:        return new PlannedStreetInfo(raw.roadType, north, south, west, east, …);
   71:    }
   ```
   `north/south/west/east` 的定义（两侧 roadType 都非 NONE）天然对称：`A.north == B.south`。`BuildingInfo.getChunkCharacteristicsLocked` 正是通过 `provider.getStreetPlanner().getStreetInfo(chunkX, chunkZ)` 写入 `rawPlannedRoadType`（`BuildingInfo.java:407-411`）。按 `docs/WORKFLOW.md` 的"单点委派"决策，Citylines 会在 `HierarchicalStreetPlanner#getStreetInfo/getRoadType` 的 HEAD 委派给 V2 规划器，因此**附属模组在自己的 V2 选择代码里读这两个方法，拿到的就是 V2 自己的 plan mask**——这比用 `hasRoadConnection` 反推更直接，也天然满足"双侧 mask 都有边"的定义。`PlannedStreetInfo.isRoad()`（`:22-24`）与 `BuildingInfo.isPlannedRoad()`（`:1385-1387`）是判断"本格是否为路"的现成方法（注意：`PlannedRoadType` 枚举本身**没有** `isRoad()`）。

**不需要碰 V1 JSON**：V2 部件是同注册表里的新条目（`citylines:*`），V2 的 CityStyle/WorldStyle 是新文件，V1 的 `citystyle_standard`/`citystyle_common`/`street_*` 不被引用也不被改写。

---

## 5. 放置（Q5）

### 5.1 `generatePart` 全文

`LostCityTerrainFeature.java:1739-1843`

```java
1739:    public enum HardAirSetting {
1740:        AIR, WATERLEVEL, VOID
1741:    }
1742:
1743:    /**
1744:     * Generate a part. If 'airWaterLevel' is true then 'hard air' blocks are replaced with water below the waterLevel.
1745:     * Otherwise they are replaced with air.
1746:     */
1747:    public int generatePart(BuildingInfo info, IBuildingPart part,
1748:                            Transform transform,
1749:                            int ox, int oy, int oz, HardAirSetting airWaterLevel) {
1750:        if (profile.EDITMODE) {
1751:            EditModeData.getData().addPartData(info.coord, oy, part.getName());
1752:        }
1753:        CompiledPalette compiledPalette = computePalette(info, part);
1754:
1755:        boolean nowater = part.getMetaBoolean(ILostCities.META_NOWATER);
1756:        boolean forcedAir = part.getMetaBoolean(ILostCities.META_FORCEDAIR);
1757:
1758:        for (int x = 0; x < part.getXSize(); x++) {
1759:            for (int z = 0; z < part.getZSize(); z++) {
1760:                char[] vs = part.getVSlice(x, z);
1761:                if (vs != null) {
1762:                    int rx = ox + transform.rotateX(x, z);
1763:                    int rz = oz + transform.rotateZ(x, z);
1764:                    getDriver().current(rx, oy, rz);
1765:                    int len = vs.length;
1766:                    for (int y = 0; y < len; y++) {
1767:                        char c = vs[y];
1768:                        BlockState b = compiledPalette.get(c);
1769:                        if (b == null) {
1770:                            throw new RuntimeException("Could not find entry '" + c + "' in the palette for part '" + part.getName() + "'!");
1771:                        }
1772:
1773:                        Palette.Info inf = compiledPalette.getInfo(c);
1774:
1775:                        if (transform != Transform.ROTATE_NONE) {
1776:                            b = transformBlockState(transform, b);
1777:                        }
1778:
1779:                        // We don't replace the world where the part is empty (air)
1780:                        if (b != air) {
1781:                            if (b == liquid) {
1782:                                if (info.profile.AVOID_WATER) {
1783:                                    b = air;
1784:                                }
1785:                            } else if (b == hardAir) {
1786:                                if (forcedAir) {
1787:                                    b = air;
1788:                                } else {
1789:                                    switch (airWaterLevel) {
1790:                                        case AIR:
1791:                                            b = air;
1792:                                            break;
1793:                                        case WATERLEVEL:
1794:                                            if (!info.profile.AVOID_FOLIAGE && !nowater && oy + y < info.waterLevel) {
1795:                                                b = liquid;
1796:                                            } else {
1797:                                                b = air;
1798:                                            }
1799:                                            break;
1800:                                        case VOID:
1801:                                            // hardAir (STRUCTURE_VOID) is replaced by whatever was already there
1802:                                            break;
1803:                                    }
1804:                                }
1805:                            } else if (inf != null) {
1806:                                if (inf.isTorch()) {
1807:                                    if (info.profile.GENERATE_LIGHTING) {
1808:                                        info.addTorchTodo(getDriver().getCurrentCopy());
1809:                                    } else {
1810:                                        b = air;        // No torches
1811:                                    }
1812:                                } else if (inf.loot() != null && !inf.loot().isEmpty()) {
1813:                                    handleLoot(info, part, provider.getWorld(), b, inf);
1814:                                } else if (inf.mobId() != null && !inf.mobId().isEmpty()) {
1815:                                    b = handleSpawner(info, part, oy, provider.getWorld(), rx, rz, y, b, inf);
1816:                                } else if (inf.tag() != null) {
1817:                                    b = handleBlockEntity(info, oy, provider.getWorld(), rx, rz, y, b, inf);
1818:                                }
1819:                            } else if (getStatesNeedingPoiUpdate().contains(b)) {
1820:                                // If this block has POI data we need to delay setting it
1821:                                BlockState finalB = b;
1822:                                BlockPos p = getDriver().getCurrentCopy();
1823:                                info.addPostTodo(p, () -> {
1824:                                    if (provider.getWorld().getBlockState(p).getBlock() == Blocks.DIRT) {
1825:                                        provider.getWorld().setBlock(p, finalB, Block.UPDATE_NONE);
1826:                                    }
1827:                                });
1828:                                b = Blocks.DIRT.defaultBlockState();
1829:                            } else if (getStatesNeedingLightingUpdate().contains(b)) {
1830:                                updateNeeded(info, getDriver().getCurrentCopy(), Block.UPDATE_CLIENTS);
1831:                            } else if (getStatesNeedingTodo().contains(b)) {
1832:                                b = handleTodo(info, oy, provider.getWorld(), rx, rz, y, b);
1833:                            }
1834:                            getDriver().add(b);
1835:                        } else {
1836:                            getDriver().incY();
1837:                        }
1838:                    }
1839:                }
1840:            }
1841:        }
1842:        return oy + part.getSliceCount();
1843:    }
```

关键点：

* **偏移**：`ox/oy/oz` 是**区块内局部**偏移；`transform.rotateX/rotateZ` 把部件坐标旋转到 0..15 空间后再加偏移（第 1762-1763 行）。街道部件恒为 `ox=0, oz=0`（第 1572/1580/1646/1666 行）。
* **垂直**：`getDriver().current(rx, oy, rz)` 定位到 `(区块原点 + rx, oy, rz)`（`ChunkDriver.java:91-94`），`add(b)` 写入并 `incY()`（`ChunkDriver.java:287-292`），`b == air` 时只 `incY()`（第 1835-1836 行）。
* **返回值** = `oy + getSliceCount()`，即部件顶端之上。
* **`b == null` 抛异常**（第 1769-1771）——字符不在合并 palette 里是**硬错误**，会中断区块生成。
* **`b == hardAir` + `HardAirSetting.VOID`**：不写（第 1800-1802 行）。街道部件用 `VOID`。
* 若部件的字符解析出 `info`（torch/loot/mob/tag）或特殊状态，会走特殊分支；V2 的混凝土/石头不会命中。

### 5.2 旋转辅助 `transformBlockState`

`LostCityTerrainFeature.java:1957-1973`

```java
1957:    public BlockState transformBlockState(Transform transform, BlockState b) {
1958:        if (Tools.hasTag(b.getBlock(), LostTags.ROTATABLE_TAG)) {
1959:            b = b.rotate(transform.getMcRotation());
1960:        } else if (getRailStates().contains(b)) {
1961:            EnumProperty<RailShape> shapeProperty;
1962:            if (b.getBlock() == Blocks.RAIL) {
1963:                shapeProperty = RailBlock.SHAPE;
1964:            } else if (b.getBlock() == Blocks.POWERED_RAIL) {
1965:                shapeProperty = PoweredRailBlock.SHAPE;
1966:            } else {
1967:                throw new RuntimeException("Error with rail!");
1968:            }
1969:            RailShape shape = b.getValue(shapeProperty);
1970:            b = b.setValue(shapeProperty, transform.transform(shape));
1971:        }
1972:        return b;
1973:    }
```

`ROTATABLE_TAG = lostcities:rotatable`（`LostTags.java:14`），数据在 `src/generated/resources/data/lostcities/tags/blocks/rotatable.json`（由 `datagen/LCBlockTags.java` 生成）。**楼梯/墙等有朝向的方块只有在这个 tag 里才会被旋转**；`gray_concrete` 等无属性方块旋转是恒等操作，所以 V2 的平面部件不需要关心它。`Transform.getMcRotation()`（`Transform.java:20-22`）把 `ROTATE_90` 映射到 `Rotation.CLOCKWISE_90`。

坐标旋转（`Transform.java:46-68`）：

```java
46:    public int rotateX(int x, int z) {
47:        return switch (this) {
48:            case ROTATE_NONE -> x;
49:            case ROTATE_90 -> 15 - z;
50:            case ROTATE_180 -> 15 - x;
51:            case ROTATE_270 -> z;
...
58:    public int rotateZ(int x, int z) {
59:        return switch (this) {
60:            case ROTATE_NONE -> z;
61:            case ROTATE_90 -> x;
62:            case ROTATE_180 -> 15 - z;
63:            case ROTATE_270 -> 15 - x;
```

### 5.3 `GROUNDLEVEL` 的含义

* `LostCityProfile.GROUNDLEVEL`（默认 71，`config/LostCityProfile.java:60`）是**城市 level 0 的街道基准世界高度**。
* 实际使用高度是 `BuildingInfo.getCityGroundLevel()`（`BuildingInfo.java:298-300`）：
  ```java
  298:    public int getCityGroundLevel() {
  299:        return groundLevel + cityLevel * FLOORHEIGHT;
  300:    }
  ```
  其中 `FLOORHEIGHT = 6`（`LostCityTerrainFeature.java:61`），`cityLevel` 来自 `LostChunkCharacteristics.cityLevel`。
* 街道部件写在 `getCityGroundLevel()`（`generateStreet` 第 1175 行 `int height = info.getCityGroundLevel();` → 传给 `generateNormalStreetSection`）。
* `height++`（第 1213 行）之后，`front part` 与 `generateRandomVegetation` 用的是 **ground + 1**。
* `fillToBedrockStreetBlock`（`:1281-1295`）把 `ground - 1` 往下填 `profile.getBaseBlock()`。
* 城市桥在 V1 模式下用 `profile.GROUNDLEVEL`（`gen/Bridges.java:41-43`），V2 桥也必须落在同一高度（方案第 6 节已指出）。

### 5.4 `Slice` / `Part` 到世界的映射

不存在 `Slice`/`Part` 类型。映射由 `generatePart` 的循环直接给出：

```
part 坐标 (x, y, z)  →  世界 (chunkX*16 + ox + rotateX(x,z), oy + y, chunkZ*16 + oz + rotateZ(x,z))
```

`oy = info.getCityGroundLevel()`（街道），`ox = oz = 0`。
部件是"以区块为单位整块贴上去"的，因此 V2 的 16×16 端口对齐要求天然落到"相邻两格各自 16 格边缘列逐格一致"。

### 5.5 附属模组部件是否同等待遇

**是，完全相同。** 依据：

* `generatePart` 只通过 `IBuildingPart` 接口使用部件，接口没有任何命名空间/来源概念（`cityassets/IBuildingPart.java`）。
* 部件获取只有 `AssetRegistries.PARTS.getOrWarn/getOrThrow/get`，最终都是 `DataTools.fromName` 的 `ResourceLocation` 查询（`RegistryAssetRegistry.java:35-95`）。
* 唯一与命名空间有关的差异在编辑模式日志：`BuildingPart.getName()` 对非 `lostcities` 部件返回带命名空间的全名（`BuildingPart.java:93-96` + `DataTools.java:22-28`），仅影响 `EditModeData` 记录与错误信息文本。

结论：V2 部件与 V1 部件走同一条放置、旋转、调色板合并、损坏、植被/前饰/边界覆写路径。风险不在"能不能放"，而在"放在别人之后"（见 5.6）。

### 5.6 渲染次序与被谁覆写

`generateStreet`（`LostCityTerrainFeature.java:1162-1238`）内的固定顺序：

1. `Corridors.generateCorridors`（若有走廊，第 1165-1167 行）
2. 街道部件：`generateNormalStreetSection` / `generateStreetSlopeSection` / `generateFullStreetSection` / `generateParkSection`（第 1202-1212 行）
3. `height++`（第 1213 行）
4. 公园/喷泉部件（第 1215-1225 行）
5. `generateRandomVegetation(info, height)`（第 1228 行）
6. `generateFrontPart` ×4（第 1230-1233 行）
7. `generateBorders(info, canDoStreetOrPark, heightmap)`（第 1237 行）

之后在 `doCityChunk`（`:908-981`）：`generateRuins`（第 957-959 行）、`generateStreetDecorations`（楼梯，第 983-1000 行）、`Highways.generateHighways`、`generateRubble`、`Stuff.generateStuff`（第 974-980 行）。

对 V2 有影响的三处：

* **植被**（`:1420-1500`）：当某侧邻居 `hasBuilding` 时，在 `x = 0..THICKNESS_OF_RANDOM_LEAFBLOCKS-1`（默认 `THICKNESS_OF_RANDOM_LEAFBLOCKS = 2`，`CHANCE_OF_RANDOM_LEAFBLOCKS = .1f`，`LostCityProfile.java:46-47`）逐格以 `getDriver().current(x, height, z)`（注意 y = ground + 1）+ `add(leaf)` 写作。**它正好压在 V2 的步行边带（PRIMARY x=0..1，SECONDARY x=0..2）上**，与设计文档"植被不得占车行面/路缘/必要步行通道"冲突，必须由 V2 分支抑制或收窄。
* **前饰**（`generateFrontPart` `:1411-1416`，调用点 `:1230-1233`）：把**邻接建筑**的 `frontType` 部件以 `(0, height=ground+1, 0)` 为原点写进街道格。内置 `building_front1.json` 是 `xsize=2`（占 x=0..1，z 向 16），`building_front2/3.json` 是 `xsize=3`（占 x=0..2），第 0 层就有 `##`（`building_front2.json:9`）——即**落在 V2 步行边带上（ground+1 高度）**。V1 的 4 格 `b` 缓冲带被取消后，这层装饰会直接站在步行边带里；必须按设计文档做加载期审计或抑制。
* **边界**（`generateBorders` `:1240-1276` → `generateBorder` `:1329-1389`）：只在 `doBorder(...)`（`:2109-2129`）为真时执行，即邻居**不是同级城市街道**（更低 level 或非城市）时。此时在第 0/15 列写 `border` 字符（从地形到 `ground+1`）并在 `ground+1` 写 `wall`，除非 `borderNeedsConnectionToAdjacentChunk` 为真（`:1691-1737`，只在坡道/楼梯/桥端口成立）。**同级相邻街道不写边界**——这正是 V2 "同级端口必须逐格对齐"的硬约束来源。

---

## 6. `WorldStyle` 与每区块 CityStyle 选择（Q6）

### 6.1 `WorldStyleRE` 字段全表

`regassets/WorldStyleRE.java:15-29`

```java
15:    public static final Codec<WorldStyleRE> CODEC = RecordCodecBuilder.create(instance ->
16:            instance.group(
17:                    Codec.STRING.fieldOf("outsidestyle").forGetter(l -> l.outsideStyle),
18:                    MultiSettings.CODEC.optionalFieldOf("multisettings").forGetter(l -> l.multiSettings.get()),
19:                    WorldSettings.CODEC.optionalFieldOf("settings").forGetter(l -> l.worldSettings.get()),
20:                    CitySphereSettings.CODEC.optionalFieldOf("cityspheres").forGetter(l -> Optional.ofNullable(l.citysphereSettings)),
21:                    ScatteredSettings.CODEC.optionalFieldOf("scattered").forGetter(l -> Optional.ofNullable(l.scatteredSettings)),
22:                    PartSelector.CODEC.optionalFieldOf("parts").forGetter(l -> l.partSelector.get()),
23:                    Codec.STRING.optionalFieldOf("bridgesupport").forGetter(l -> DataTools.toNullable(l.bridgeSupport)),
24:                    Codec.STRING.optionalFieldOf("highwaysupport").forGetter(l -> DataTools.toNullable(l.highwaySupport)),
25:                    Codec.STRING.optionalFieldOf("bridgesupportpart").forGetter(l -> Optional.ofNullable(l.bridgeSupportPart)),
26:                    Codec.STRING.optionalFieldOf("highwaysupportpart").forGetter(l -> Optional.ofNullable(l.highwaySupportPart)),
27:                    Codec.list(CityStyleSelector.CODEC).fieldOf("citystyles").forGetter(l -> l.cityStyleSelectors),
28:                    Codec.list(CityBiomeMultiplier.CODEC).optionalFieldOf("citybiomultipliers").forGetter(l -> Optional.ofNullable(l.cityBiomeMultipliers))
29:            ).apply(instance, WorldStyleRE::new));
```

**回答"`highwayParts` / `streetParts` 在哪"**：

* WorldStyle **没有** `streetParts`/`largeparts` 等字段——街道部件只在 CityStyle 的 `streetblocks`。
* 高速部件在 `"parts"."highways"`：`regassets/data/PartSelector.java:11-21`
  ```java
  11:public record PartSelector(MonorailParts monoRailParts, HighwayParts highwayParts, RailwayParts railwayParts) {
  13:    public static final Codec<PartSelector> CODEC = RecordCodecBuilder.create(instance ->
  14:            instance.group(
  15:                    MonorailParts.CODEC.optionalFieldOf("monorails").forGetter(l -> l.monoRailParts.get()),
  16:                    HighwayParts.CODEC.optionalFieldOf("highways").forGetter(l -> l.highwayParts.get()),
  17:                    RailwayParts.CODEC.optionalFieldOf("railways").forGetter(l -> l.railwayParts.get())
  ```
  使用点：`gen/Highways.java:59` `info.provider.getWorldStyle().getPartSelector().highwayParts()`。
* WorldStyle 的 `bridgesupport`/`bridgesupportpart` 只是**桥墩**（字符或部件名），桥面部件在 CityStyle 的 `selectors.bridges`/`largebridges`。

### 6.2 `worldstyles/standard.json`（全文）

`reference/lostcities-src/src/main/resources/data/lostcities/lostcities/worldstyles/standard.json`

```json
{
  "outsidestyle": "outside",
  "bridgesupport": "v",
  "highwaysupport": "v",
  "settings": {
    "railwayavoidance": "ignore",
    "railpartheight6": 1
  },
  "multisettings": {
    "areasize": 10,
    "minimum": 1,
    "maximum": 5,
    "correctstylefactor": 0.8,
    "attempts": 50
  },
  "scattered": {
    "areasize": 8,
    "chance": 0.7,
    "weightnone": 30,
    "list": [
      {
        "name": "radiotower",
        "weight": 15,
        "maxheightdiff": 3,
        "biomes": {
          "excluding": [
            "#minecraft:is_ocean",
            "#minecraft:is_river",
            "#minecraft:is_beach"
          ]
        }
      },
      {
        "name": "oilrig",
        "weight": 4,
        "maxheightdiff": 100,
        "biomes": {
          "if_any": [
            "#minecraft:is_deep_ocean"
          ]
        }
      },
      {
        "name": "cabin",
        "weight": 10,
        "maxheightdiff": 2,
        "biomes": {
          "excluding": [
            "#minecraft:is_ocean",
            "#minecraft:is_river",
            "#minecraft:is_beach"
          ]
        }
      },
      {
        "name": "highway_gas_station",
        "weight": 12,
        "nearhighway": true,
        "maxheightdiff": 12,
        "biomes": {
          "excluding": [
            "#minecraft:is_ocean",
            "#minecraft:is_river",
            "#minecraft:is_beach"
          ]
        }
      },
      {
        "name": "highway_restaurant",
        "weight": 9,
        "nearhighway": true,
        "maxheightdiff": 12,
        "biomes": {
          "excluding": [
            "#minecraft:is_ocean",
            "#minecraft:is_river",
            "#minecraft:is_beach"
          ]
        }
      }
    ]
  },
  "citybiomemultipliers": [
    {
      "multiplier": 0.1,
      "biomes": {
        "if_any": [
          "#minecraft:is_ocean"
        ]
      }
    },
    {
      "multiplier": 0.3,
      "biomes": {
        "if_any": [
          "#minecraft:is_river"
        ]
      }
    }
  ],
  "citystyles": [
    {
      "factor": 0.5,
      "citystyle": "citystyle_standard"
    },
    {
      "factor": 9.0,
      "biomes": {
        "if_any": [
          "minecraft:desert",
          "minecraft:badlands"
        ]
      },
      "citystyle": "citystyle_desert"
    }
  ]
}
```

### 6.3 `WorldStyle` 类

`cityassets/WorldStyle.java:22-63, 123-152`（摘）

```java
34:    private final List<Pair<Predicate<Holder<Biome>>, Pair<Float, String>>> cityStyleSelector = new ArrayList<>();
35:    private final List<Pair<Predicate<Holder<Biome>>, Float>> cityBiomeMultiplier = new ArrayList<>();
...
51:        for (CityStyleSelector selector : object.getCityStyleSelectors()) {
52:            Predicate<Holder<Biome>> predicate = biomeHolder -> true;
53:            if (selector.biomeMatcher() != null) {
54:                predicate = selector.biomeMatcher();
55:            }
56:            cityStyleSelector.add(Pair.of(predicate, Pair.of(selector.factor(), selector.citystyle())));
57:        }
...
137:    public String getRandomCityStyle(IDimensionInfo provider, ChunkCoord coord, Random random) {
138:        Holder<Biome> biome = BiomeInfo.getBiomeInfo(provider, coord).getMainBiome();
139:        List<Pair<Float, String>> ct = new ArrayList<>();
140:        for (Pair<Predicate<Holder<Biome>>, Pair<Float, String>> pair : cityStyleSelector) {
141:            if (pair.getKey().test(biome)) {
142:                ct.add(pair.getValue());
143:            }
144:        }
145:
146:        Pair<Float, String> randomFromList = Tools.getRandomFromList(random, ct, Pair::getLeft);
147:        if (randomFromList == null) {
148:            return null;
149:        } else {
150:            return randomFromList.getRight();
151:        }
152:    }
```

`CityStyleSelector`（`regassets/data/CityStyleSelector.java:11-18`）：`{"factor": float, "citystyle": string, "biomes": BiomeMatcher?}`。**`citystyle` 字符串经 `fromName` 解析**，所以附属模组必须写 `citylines:xxx`（ExtractionCities 就是这么做的，见 §7）。

WorldStyle 的绑定：`LostCityProfile.worldStyle`（默认 `"standard"`，`config/LostCityProfile.java:33`；TOML 键 `worldStyle`，`:333`），在 `worldgen/DefaultDimensionInfo.java:60` 解析：

```java
60:        style = AssetRegistries.WORLDSTYLES.get(world, profile.getWorldStyle());
```

维度→profile 由 `setup/Config.java:24-28, 158-160` 的 `dimensionsWithProfiles`（COMMON 配置，格式 `<dimensionid>=<profilename>`）决定，或由附属模组调用 `ILostCities.registerDimension`（`api/ILostCities.java:42`）。

### 6.4 每区块 CityStyle 的选择路径

`worldgen/lost/City.java:232-302`

```java
232:    // Call this on a city center to get the style of that city
233:    public static String getCityStyleForCityCenter(ChunkCoord coord, IDimensionInfo provider) {
234:        PredefinedCity city = getPredefinedCity(provider.getWorld(), coord);
235:        if (city != null) {
236:            if (city.getCityStyle() != null) {
237:                return city.getCityStyle();
238:            }
239:            // Otherwise we chose a random city style
240:        }
241:        int chunkX = coord.chunkX();
242:        int chunkZ = coord.chunkZ();
243:        Random cityStyleForCenterRandom = new Random(chunkZ * 899809363L + chunkX * 256203221L);
244:        return provider.getWorldStyle().getRandomCityStyle(provider, coord, cityStyleForCenterRandom);
245:    }
246:
247:    // Calculate the citystyle based on all surrounding cities
248:    public static CityStyle getCityStyle(ChunkCoord coord, IDimensionInfo provider, LostCityProfile profile) {
249:        return CITY_STYLE_CACHE.computeIfAbsent(coord, k -> getCityStyleInt(coord, provider, profile));
250:    }
```

`getCityStyleInt`（`:252-302`）对每个城市中心按"距离/半径"加权收集 `Pair<Float,String>`（低于 `CITY_STYLE_THRESHOLD` 时使用 `profile.CITY_STYLE_ALTERNATIVE`），最后：

```java
290:        String cityStyleName;
291:        if (styles.isEmpty()) {
292:            cityStyleName = provider.getWorldStyle().getRandomCityStyle(provider, coord, cityStyleRandom);
293:        } else {
294:            Pair<Float, String> fromList = Tools.getRandomFromList(cityStyleRandom, styles, Pair::getLeft);
...
301:        return AssetRegistries.CITYSTYLES.get(provider.getWorld(), cityStyleName);
```

落到 `BuildingInfo.getChunkCharacteristicsLocked`（`BuildingInfo.java:422-442`）：街道格（`isCity && !couldHaveBuilding`）会在 3×3 邻域里做多数投票（防止两个风格交界的街道随机跳变），否则直接取本格风格；结果写进 `LostChunkCharacteristics.cityStyle`（`api/LostChunkCharacteristics.java:12`，类型为 `ILostCityCityStyle`，实际实现是 `CityStyle`）。

**注意**：`CityStyle` 里的 `selectors`（`Selectors`）选的是**建筑/桥/公园/前饰/楼梯等对象**，不是 CityStyle 本身；CityStyle 的选择完全由 WorldStyle 的 `citystyles[]` + `City.getCityStyleInt` 决定。两者不要混淆。

---

## 7. 第三方先例：ExtractionCities（Q7）

仓库 `ExtractionCities-forge-1.20.1/src/main/resources/data/` 只有 3 个文件：

```
./extractioncities/dimension_type/extraction_city.json
./extractioncities/lostcities/citystyles/single_building_test.json
./extractioncities/lostcities/worldstyles/single_building_test.json
```

### 7.1 逐字引用

`data/extractioncities/lostcities/citystyles/single_building_test.json:1-28`（逐字摘录前 28 行；第 29-137 行是 `selectors`，其中 `buildings` 只有 `building1`、`multibuildings` 为 `[]`，其余与内置 `citystyle_common` 相同。**下面的 `...` 是摘录标记，不代表文件里有该字符**）

```json
{
  "style": "standard",
  "inherit": "citystyle_config",
  "stuff_tags": [
    "rubble"
  ],
  "streetblocks": {
    "border": "y",
    "wall": "w",
    "street": "S",
    "streetbase": "b",
    "streetvariant": "B"
  },
  "parkblocks": {
    "elevation": "x"
  },
  "corridorblocks": {
    "roof": "x",
    "glass": "+"
  },
  "railblocks": {
    "railmain": "y"
  },
  "sphereblocks": {
    "glass": "Z",
    "border": "9",
    "inner": "b"
  },
  "selectors": {
    ...（buildings: 仅 building1；multibuildings: []；bridges/fronts/stairs/fountains/parks/raildungeons 与内置相同）
  }
}
```

`data/extractioncities/lostcities/worldstyles/single_building_test.json`（全文）

```json
{
  "outsidestyle": "outside",
  "settings": {
    "railwayavoidance": "ignore",
    "railpartheight6": 1
  },
  "multisettings": {
    "areasize": 10,
    "minimum": 0,
    "maximum": 0,
    "correctstylefactor": 1.0,
    "attempts": 50
  },
  "scattered": {
    "areasize": 8,
    "chance": 0.0,
    "weightnone": 100,
    "list": []
  },
  "citystyles": [
    {
      "factor": 1.0,
      "citystyle": "extractioncities:single_building_test"
    }
  ]
}
```

### 7.2 结论

* 这两个文件**实际会被加载**：目录 `data/extractioncities/lostcities/...` 与 §1.2/§1.3 的规则一致，注册为 `extractioncities:single_building_test`（citystyles）与 `extractioncities:single_building_test`（worldstyles）。
* 跨命名空间引用**必须写全名**（第 23 行 `"citystyle": "extractioncities:single_building_test"`）——因为 `WorldStyle.getRandomCityStyle` 的返回值直接进 `City.getCityStyleInt` 第 301 行的 `AssetRegistries.CITYSTYLES.get(level, String)` → `DataTools.fromName`：不带冒号会解析成 `lostcities:single_building_test`（不存在，`get` 返回 `null`，随后 `CityStyle` 为 null → 后续 `getCityStyle().getStyle()` NPE）。
* 该 CityStyle **没有** `largeparts`，也没有 `tertiaryparts`：按 §4.3，它的 PRIMARY 路会退回 `StreetParts.DEFAULT`（小街部件）。这说明"只继承 `citystyle_config` 而不写 `largeparts`"是可行但会静默降级的路径——V2 必须避免同类静默降级。

### 7.3 让自定义 CityStyle 可被选中，附属模组至少要交付

1. `data/<ns>/lostcities/citystyles/<id>.json`（`CityStyleRE.CODEC`；建议 `inherit` 一个内置风格以保证 border/wall/park/corridor/sphere 等字符齐全）。
2. `data/<ns>/lostcities/worldstyles/<id>.json`，其 `citystyles[].citystyle` 写 `<ns>:<citystyle-id>`。
3. 一个 Lost Cities profile 把 `worldStyle` 指向 `<ns>:<worldstyle-id>`（`config/lostcities/*.json` profile 文件或 profile TOML 的 `worldStyle` 键）。
4. 该 profile 绑定到目标维度：COMMON 配置 `dimensionsWithProfiles = ["<dimid>=<profilename>"]`，或代码调用 `ILostCities.registerDimension`。
5. （可选）若该 CityStyle 用 `"style"` 指向自定义 `styles/*.json`，则还要交付该 style 及其引用的所有 `palettes/*.json`。

---

## 8. V2 街道部件的实操配方（Q8）

### 8.1 交付文件清单（citylines 命名空间）

```
src/main/resources/data/citylines/lostcities/
├── parts/            v2r_s_000s.json … v2r_p_pppp.json          # 28 个（+1 可选封口件）
├── palettes/         v2_street.json                              # 路面材质
│                     v2_road_damage.json                         # 仅为 damaged 规则（可选）
├── styles/           v2_standard.json                            # 复制 standard + 追加 v2_road_damage 组
├── citystyles/       v2_standard.json                            # inherit citystyle_common/config
├── worldstyles/      v2_standard.json                            # citystyles: [{factor, citystyle:"citylines:v2_standard"}]
└── v2roadparts/…     （仅当自建注册表时；目录名 = 自建注册表 key 的 path）
```

外加：

* `config/lostcities/*.json`（或 profile TOML）里的 `worldStyle = "citylines:v2_standard"`，以及 `dimensionsWithProfiles` 绑定。
* 附属模组的 Java：`(roadType, edgeClass[N/E/S/W]) → (part, Transform)` 表 + §4.6 的 Mixin。
* **不改动任何 V1 文件**；V2 部件全部是新文件、新 ID。

### 8.2 28（+1）个键的精确枚举

由 `roadType ∈ {SECONDARY, PRIMARY}`、`edgeClass ∈ {0, SECONDARY, PRIMARY}`、按四次旋转合并得到 24 个旋转类（含全 0），去掉全 0 得 23 个非空代表；其中 18 个含主干臂（只能有 PRIMARY 中心），5 个只含集散臂（各有 SECONDARY/PRIMARY 两种中心）→ **28 个非空中心/边代表**；再加一个"四臂全 0 的 PRIMARY 封口"= **29**。下表是可直接当文件名用的规范形（`N E S W` 顺序，`0`=无边、`s`=SECONDARY、`p`=PRIMARY；取四旋转中字典序最小者）：

| # | 规范形 NESW | 旋转类大小 | 需要的中心 |
| --- | --- | --- | --- |
| 1 | `000s` | 4 | s, p |
| 2 | `00ss` | 4 | s, p |
| 3 | `0s0s` | 2 | s, p |
| 4 | `0sss` | 4 | s, p |
| 5 | `ssss` | 1 | s, p |
| 6 | `000p` | 4 | p |
| 7 | `00sp` | 4 | p |
| 8 | `00ps` | 4 | p |
| 9 | `00pp` | 4 | p |
| 10 | `0s0p` | 4 | p |
| 11 | `0ssp` | 4 | p |
| 12 | `0sps` | 4 | p |
| 13 | `0spp` | 4 | p |
| 14 | `0p0p` | 2 | p |
| 15 | `0pss` | 4 | p |
| 16 | `0psp` | 4 | p |
| 17 | `0pps` | 4 | p |
| 18 | `0ppp` | 4 | p |
| 19 | `sssp` | 4 | p |
| 20 | `sspp` | 4 | p |
| 21 | `spsp` | 2 | p |
| 22 | `sppp` | 4 | p |
| 23 | `pppp` | 1 | p |
| 24 | `0000` | 1 | p（可选封口件） |

合计 = 18 + 5×2 = 28（+1）。旋转类大小之和 = 80 = `3^4 - 1`，与设计文档一致。
每个键生成 4 个（或 k 个）有向条目，共 80 个有向条目 → `(part, Transform)`；建议命名 `v2r_<中心>_<nesw>.json`（例如 `v2r_p_0sp0.json`），中心用 `s`/`p`。

### 8.3 部件 JSON 模式（V2 断面示例）

以 `PART` = 车行面字符、`F` = 步行边带、`K` = 路缘、`b` = 保持原样：

```json
{
  "xsize": 16,
  "zsize": 16,
  "refpalette": "citylines:v2_street",
  "slices": [
    [
      "FFKKPPPPPPPPKKFF",
      ... 共 16 行，每行 16 字符 ...
    ]
  ]
}
```

* 只能是 **1 层**（`getSliceCount() == 1`），与 V1 街道部件一致；层内行优先 `z*16+x`。
* PRIMARY 断面（左右对称）= `F`×2 + `K`×1 + `P`×10 + `K`×1 + `F`×2 = 16；SECONDARY = `F`×3 + `K`×1 + `P`×8 + `K`×1 + `F`×3 = 16（`road/core/RoadSection.java:24-27` 已冻结这两个候选）。
* "无连接"侧：车行面必须在本格内被 `K`/`F` 收束；**不要用空格以外的"未定义字符"**，`compiledPalette.get(c) == null` 会直接抛 `RuntimeException`（`LostCityTerrainFeature.java:1769-1771`）。
* 推荐用 `" "`（整列跳过）或 `b`（`structure_void` + `HardAirSetting.VOID` → 保持世界原样）表达"不动"。V1 街道部件用 `b`；V2 用空格更省（`getVslices` 会跳过整列，不查 palette、不写方块）。
* **不要**在部件 JSON 的 `palette` 里寄望 `damaged` 生效（§3.4）；V2 材料的损坏行为必须在 `styles/v2_standard.json` 引用的 palette 里声明。

### 8.4 调色板

`palettes/v2_street.json` 示例（字段全部来自 `PaletteEntry.CODEC`）：

```json
{
  "palette": [
    { "char": "P", "block": "minecraft:gray_concrete" },
    { "char": "F", "block": "minecraft:light_gray_concrete" },
    { "char": "K", "block": "minecraft:polished_andesite" },
    { "char": "b", "block": "minecraft:structure_void" }
  ]
}
```

（`smooth_stone` / `cut_sandstone` 等可作为 `F`/`K` 的替代；但见 §3.6：这些状态目前都不在损坏表里。）

若要控制损坏行为，追加一份只含 damaged 的 palette 并把它作为一个**新的 randompalettes 组**加进 V2 Style：

```json
{
  "palette": [
    { "char": "P", "block": "minecraft:gray_concrete", "damaged": "minecraft:andesite" }
  ]
}
```

`damaged` 的值经 `Tools.stringToState`（`varia/Tools.java:62-78`）解析：不含 `[` 时走 `BlockStateData.upgradeBlock` + `ForgeRegistries.BLOCKS.getValue`，**找不到方块直接抛 `RuntimeException("Cannot find block: '…'!")`**。因此该 id 必须在目标版本实测存在；同时它应当是**满高度、非重力、无随机 tick** 的方块（示例用 `minecraft:andesite` 仅示范写法，需在兼容构建中实测），否则路面在爆炸后会出现台阶、突起或塌落。

注意：该组会成为 chunk palette 的一部分，同名状态后写覆盖先写（`CompiledPalette.java:100-103`）；如果同一状态也出现在 `common`/`default`/`bricks_*` 中，需要保证语义一致（或者选用不与内置 palette 冲突的状态）。

### 8.5 选择与放置

```text
V2 激活条件（三者同时成立）：
  provider.getStreetGenerationMode() == HIERARCHICAL_GRID_V1
  && 本维度 V2 开关（Citylines 自持久化）为真
  && info.isPlannedRoad()                     // BuildingInfo.java:1385-1387

键计算（二选一，推荐 (a)）：
  (a) 读 V2 自己的 plan（Citylines 已把 getStreetInfo 委派给 V2 规划器）：
      PlannedStreetInfo self = provider.getStreetPlanner().getStreetInfo(cx, cz);
      centre = self.roadType();                         // PRIMARY | SECONDARY
      edge[N/S/W/E] = self.north()/self.south()/self.west()/self.east()
                      ? min(centre, planner.getRoadType(neighbour)) : 0
      // north/east/west/south 的布尔值定义天然对称（HierarchicalStreetPlanner.java:64-67）
  (b) 用 BuildingInfo 反推：
      centre = info.plannedRoadType
      对 d ∈ {XMIN(西), XMAX(东), ZMIN(北), ZMAX(南)}：
          n     = d.get(info)
          both  = info.isPlannedRoad() && n.isPlannedRoad()
                  && BuildingInfo.hasRoadConnection(info, n)   // 双向，BuildingInfo.java:1803
          edge[d] = both ? min(info.plannedRoadType, n.plannedRoadType) : 0
  两种算法都必须保证两侧格对同一条边算出同一个值（min 对称 + 双向判定 ⇒ 一致）

放置：
  (name, transform) = TABLE[centre][edge[N],edge[E],edge[S],edge[W]]
  part = AssetRegistries.PARTS.getOrWarn(world, name)      // 建议改成 getOrThrow 或预先校验
  feature.generatePart(info, part, transform, 0, info.getCityGroundLevel(), 0, HardAirSetting.VOID)
  ci.cancel()                                              // 跳过 V1 变体与单列 connector
```

### 8.6 必须校验的项（含具体判据）

1. **端口/旋转逐格匹配**（放置前静态表校验）：
   对每个有向键 `K = (c, N, E, S, W)` 与其每个非 0 方向 `d`，取 `(part, rot)`，把部件按 `rot` 旋转后取该方向的边缘 16 格角色串；取邻居的有向键 `K'`（其 `d` 反向）同样取边缘串；断言两串**逐格相等**（角色 = 由 palette 字符归约出的 `车行/路缘/步行/空`）。这是 §5.6 "同级相邻不写边界" 的前提，也是设计文档"旋转后的端口签名必须兼容"的机器可判定形式。
2. **无连接边不泄露车行面**：对每条 `edge[d] == 0` 的边，断言该边 16 格角色串中**不含车行面角色**（必须被路缘/步行带封口）。
3. **`b`/空格的语义正确**：`v2r_p_0000` 封口件在四边都不得出现车行面角色。
4. **palette 字符可解析**：对每个部件执行一次 `computePalette` 等价的合并检查（或直接遍历字符并调用 `compiledPalette.get(c)`），任何 `null` 都会在生成时抛 `Could not find entry '…' in the palette for part '…'`（`LostCityTerrainFeature.java:1769-1771`）。
5. **damaged 冲突**：加载后取 chunk 级 `CompiledPalette`，对每个 V2 状态检查 `canBeDamagedToIronBars(state)`；若为 `null`，明确记入"允许被打成空气"清单；若非 null，检查目标方块与路面同高（否则会突起），并检查是否有两个 palette 对同一状态写不同值（后写覆盖）。
6. **CityStyle 预检（维度初始化前）**：
   * `AssetRegistries.CITYSTYLES.get(world, "citylines:v2_standard") != null`
   * `AssetRegistries.WORLDSTYLES.get(world, profile.getWorldStyle()) != null`
   * `AssetRegistries.STYLES.get(world, cityStyle.getStyle()) != null`
   * 表里**每一个**部件名 `AssetRegistries.PARTS.get(world, name) != null`
   * 每个部件引用的 palette `AssetRegistries.PALETTES.get(world, part.getRefPaletteName()) != null`，且 `getLocalPalette(world)` 非 null
   任一失败 → **不启用 V2 且不初始化该维度**。注意（已按源码更正，见 §1.4）：TLC 街道部件虽然调用 `getOrWarn`（`LostCityTerrainFeature.java:1570/1578/1593/1595/1608/1619/1632/1642/1664`），但缺件时 `RegistryAssetRegistry.get` 会把 `null` RE 的 NPE 包成 `RuntimeException("Error getting resource …")` 抛出，所以那**不是**"打一条 warning 然后不铺路面"的静默失败，而是**区块生成中途崩溃**。因此本预检是强制项：必须在**任何 V2 区块生成之前**完成，任一 id 缺失即让该维度保持 V1（`CitylinesRoadState.attach` 里一次性校验并记一条 ERROR 列出缺件）。
7. **植被/前饰预检**：确认 V2 步行边带列（PRIMARY x=0,1；SECONDARY x=0,1,2）在同一格不会被子系统覆写：
   * `generateRandomVegetation`（`LostCityTerrainFeature.java:1420-1500`，默认厚度 2）→ V2 分支必须跳过或收窄；
   * `generateFrontPart`（`:1230-1233`，`building_front1/2/3.json` 占 x=0..1 / 0..2，y = ground+1）→ 按设计文档做加载期审计或按需抑制。
8. **边界**：只在确实无计划边、且 `doBorder` 为真（邻居非同级城市街道，`LostCityTerrainFeature.java:2109-2129`）时才会写 `border`/`wall`（`:1329-1389`）。校验"计划开放端口零堵墙"要在最终方块阵列上做：对每个有向连接边，断言该边 16 格在 `getCityGroundLevel()` 与 `+1` 都没有被写成 `border`/`wall` 字符对应的方块。
9. **V1 回归**：V2 未启用的维度必须**不 cancel**，V1 结果逐位一致（`docs/WORKFLOW.md` 的硬约束）。

### 8.7 明确"不支持、需要自己实现"的清单

* ❌ 用 V1 JSON 表达 `(roadType, edgeClass)` —— 无此字段，必须代码层实现（§4.5）。
* ❌ 期望 part 内联/`refpalette` 的 `damaged` 在爆炸/废墟阶段生效 —— 用的是 chunk palette（§3.4）。
* ❌ 期望缺失部件只报错不静默 —— 街道路径虽然写的是 `getOrWarn`，但它在缺件时会抛 `RuntimeException`（§1.4 更正、§8.6 第 6 条）：既不静默，也不只是 warning。
* ❌ 期望 `CityStyle.streetblocks.width` 缩放路面 —— 铺设路径不读它（`getStreetWidth()` 仅 API 暴露）。
* ❌ 期望 V2 部件自动避开 V1 的 `generateMinorStreetConnectors` —— 只有 cancel 整个 `generateNormalStreetSection` 才会跳过（`:1647`）。
* ❌ 期望派生的 128 权重随机表可以不满 —— 不足 128 会抛异常（`CompiledPalette.java:61-63`）。

---

## 附录 A：关键证据索引

| 主题 | 位置 |
| --- | --- |
| 注册表 key 定义 | `reference/lostcities-src/src/main/java/mcjty/lostcities/setup/CustomRegistries.java:15-52` |
| 数据包注册表登记 | 同上 `:70-84`；`LostCities.java:42,56` |
| 目录规则（Forge 补丁） | `forge-1.20.1-47.3.0-patched.jar!/net/minecraft/resources/RegistryDataLoader.java:125-131`；`…/net/minecraftforge/common/ForgeHooks.java:1533-1536` |
| file→id（保留命名空间） | `forge-1.20.1-47.3.0-decomp.jar!/net/minecraft/resources/FileToIdConverter.java:22-34`；`…/net/minecraft/server/packs/PathPackResources.java:74-88` |
| 注册表遍历/惰性 get | `worldgen/lost/cityassets/RegistryAssetRegistry.java:68-114`；`AssetRegistries.java:53-86` |
| 名字解析 | `worldgen/lost/regassets/data/DataTools.java:22-36` |
| parts codec | `worldgen/lost/regassets/BuildingPartRE.java:18-26`；`regassets/data/PartMeta.java:14-22` |
| part 索引 | `cityassets/BuildingPart.java:108-136, 159-192` |
| palette codec | `regassets/PaletteRE.java:17-20`；`regassets/data/PaletteEntry.java:18-30`；`regassets/data/BlockEntry.java:11-15` |
| palette 解析 | `cityassets/Palette.java:65-119` |
| 损坏表 | `cityassets/CompiledPalette.java:18, 42-112, 184-186`；`DamageArea.java:22, 73-95` |
| 街道部件选择 | `worldgen/LostCityTerrainFeature.java:1568-1699`；`cityassets/CityStyle.java:31-33, 189-199` |
| 放置 | `worldgen/LostCityTerrainFeature.java:1739-1853, 1957-1973`；`ChunkDriver.java:91-94, 281-292` |
| 渲染次序/覆写者 | `LostCityTerrainFeature.java:1162-1238, 1240-1276, 1329-1389, 1411-1500, 2109-2129` |
| CityStyle JSON 模式 | `regassets/CityStyleRE.java:14-31`；`regassets/data/StreetSettings.java:24-37`；`regassets/data/StreetParts.java:10-44`；`regassets/data/Selectors.java:23-34` |
| WorldStyle JSON 模式 | `regassets/WorldStyleRE.java:15-29`；`regassets/data/PartSelector.java:11-21`；`regassets/data/HighwayParts.java:10-29`；`regassets/data/CityStyleSelector.java:11-18` |
| CityStyle 选择 | `worldgen/lost/City.java:232-302`；`worldgen/lost/BuildingInfo.java:422-442`；`cityassets/WorldStyle.java:137-152` |
| 逐方向 plan mask | `worldgen/street/HierarchicalStreetPlanner.java:57-71`；`worldgen/street/PlannedStreetInfo.java:5-33`；`worldgen/IDimensionInfo.java:41`；`worldgen/lost/BuildingInfo.java:407-411` |
| 第三方案例 | `/mnt/d/Workspace/ExtractionCities-forge-1.20.1/src/main/resources/data/extractioncities/lostcities/{citystyles,worldstyles}/single_building_test.json` |
