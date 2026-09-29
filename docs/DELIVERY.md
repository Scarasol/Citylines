# Citylines 初版整改交接

更新：2026-09-28。本文件是项目唯一的当前交接入口，包含实现、接口、验证及人工验收边界。其他旧交付、V3 移交、核阅回复均是历史依据，不再作为当前完成度或配置说明。

状态：初版整改与版本 4 的历史验证见下文。最新道路规则为版本 7：站台/道路路权与跨街区三级路已编译、做定点抽象检查并打包；**版本 7 尚未启动游戏或做视觉/世界生成性能验收**。请新建世界由用户验收。

## 1. 当前行为与使用

- 模组从未发布，无历史数据兼容义务。Citylines 只有一个默认开启的生成规则，不再让玩家选择 LEGACY/UNIFORM。
- 支持 TLC DEFAULT 景观的 NoiseBasedChunkGenerator 维度。适用城市固定为该 profile 的 GROUNDLEVEL、cityLevel=0；非城市保留 TLC 原生层高与城边削填。
- 受管维度旁路 LC2H CityShiftField 密度位移、选择性跳过地形整形和 GPU 城边整形接管；LC2H 其他优化保留。不是全局修改 LC2H 配置，也不是把荒野压平。
- 保留 V3 的 K=3 和全部选定主次路/深度参数；道路身份现为版本 7。三级路允许有限折线和单个高级路节点跨面贯通，跨面路线优先在多个 10x10 街区内形成有长度的路段；路口中心不画主干中线。桥、短下穿、超大建筑足迹及 EC 适配继续存在。站台与主次轴冲突时按第 9 节让路。
- 代价：受管维度不再使用 LC2H 山地密度混合效果，TLC 城边可有较深削填和断崖。自然峭壁与城边差高不等于本次孤立区块错误。
- **请新建验收世界。** 不修改、迁移或修复过去的测试世界。非空维度没有当前记录时拒绝接管，避免新旧区块混用规则。
- 当前配置仅两个根级键；详见下表。普通新世界无需调整配置。旧键没有别名映射；本轮没有改写 run/ 中的任何玩家配置。
- 已生成的当前世界正常关闭再打开，继续相同规则。修改总开关或冻结输入导致规则冲突时拒绝生成，不能静默切回原生。
- 不适用或明确关闭且从未激活的维度使用原模组路径。上游 TLC/LC2H 的 LEGACY 字面量仅用于能力识别，不是 Citylines 保留的旧算法。

### 当前 Citylines 配置

配置文件为 `config/citylines-common.toml`，以下两个键均在文件根级，而非 `[roads]` 或 `[flattening]` 下：

| 键 | 默认值 | 作用与限制 |
|---|---|---|
| `enabled` | `true` | 为新建且受支持的空维度同时启用 V3 道路和统一城市 G。`false` 仅让尚未绑定的维度走原生；已绑定的维度改为 `false` 会拒绝继续生成，不是回滚开关。 |
| `debugLogging` | `false` | 输出有数量上限的道路和地形诊断日志；不改生成规则。设为 `true` 会增加日志量。 |

项目 `run/config/citylines-common.toml` 仍可能显示早期测试留下的以下键。**当前代码均不读取，不能通过它们改变路网或高程**；隔离客户端启动生成的新配置只含上面的两个根级键。

| 旧键 | 原意；当前状态 |
|---|---|
| `roads.enableConnectedDistrict` | 旧 V2 路网开关；已失效。 |
| `roads.allowAutoEnableOnNewWorlds` | 旧版新维度自动启用许可；已失效。 |
| `roads.districtSize` | 旧 V2 分区宽度；已失效，不控制现行 K=3。 |
| `roads.debugLogging` | 旧分组诊断开关；已失效，使用根级 `debugLogging`。 |
| `roads.lc2hPolicy` | 旧版 LC2H 策略选择；已失效，现按实际能力自动判定。 |
| `flattening.enableTerrainFlattening` | 旧版独立地形开关；已失效，现由根级 `enabled` 一起控制。 |
| `flattening.cellSize` | 旧高度场单元尺度；算法已删除。 |
| `flattening.smoothing` | 旧高度场邻域平滑；算法已删除。 |
| `flattening.mode` | 旧 LEGACY/UNIFORM 模式；已失效，现无模式选择。 |

当前 profile 的 `GROUNDLEVEL` 属于 TLC 配置/数据，不是 Citylines 的第三个设置键。玩家不需要手调旧文件；Forge 在正常加载当前配置定义后会按两个有效键重写该文件，但本轮未主动改动原 `run/`。

## 2. 环境与构建

| 项目 | 实际值 |
|---|---|
| Minecraft / Forge / Java | 1.20.1 / 47.4.0 / 17 |
| 必需 TLC | 1.20-7.5.5，CurseMaven 文件 8862717 |
| 开发运行 LC2H | 4.2.4-LTS，文件 8928853 |
| LC2H 前置 | Quantified API，文件 8823897，runtimeOnly、不打入本模组 |
| 开发运行 EC | libs/ 中的 extractioncities 1.0.0，重映射使用、不打入本模组 |
| Gradle 缓存 | D:/Library/.gradle |

正常开发入口为 `gradlew.bat build --offline`、`gradlew.bat runClient --offline`，道路资产校验为 `python tools/generate_road_parts.py --check`。环境需 JAVA_HOME 指向 JDK 17、GRADLE_USER_HOME 指向上述缓存。

本机 Gradle worker 的 Unix socket 临时路径过长，验证进程使用 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:/Workspace/Citylines-forge-1.20.1/build/sockets`。只设置在所调用进程中，未改系统环境。

LC2H 三处运行期合入方法由 Mixin 动态选择器定位，不能在 Java 编译期看到。构建只对 `Lc2hTerrainOwnershipMixin.java` 使用单独的 `compileRuntimeTerrainMixin`、`-proc:none`；该类全为 remap=false，不需要自身的 refmap 条目。其他源文件正常运行 Mixin AP、生成 refmap。`installRuntimeTerrainMixin` 在 IDE 运行准备和 JAR 打包前把单独编译的类安装到 `build/classes/java/main`，与 IntelliJ 的 `MOD_CLASSES` 路径一致。编译产生的两个临时 refmap 文件显式登记为 `compileJava` 输出；丢失时自动重编，不再静默打出缺映射的 JAR。不能扩大为全项目关闭 AP。

## 3. 本次实际整改

| 范围 | 改动及依据 |
|---|---|
| 单一规则 | 删除旧高程模式和道路分离开关；维度绑定冻结当前身份与 G |
| 生命周期 | Level.Load 对新主世界只登记；在 TLC 初始化街道模式之后、其可能的出生点寻路之前绑定。修复启动过早读到上游临时 LEGACY 而永久退回原生的问题 |
| LC2H 地形 | Context 工厂在受管维度返回其既有空契约；三处准确指令包装保证后期由 TLC 削填，不修改 LC2H 全局静态字段 |
| 高度图 | TLC 每次真实提交只标记实际变动列；一次下降扫描独立计算四个原生谓词并写回，停止本作用域内假 BEDROCK 更新 |
| 非城市层高 | 删除 10x10 平衡回退，直接使用原生层高，不额外聚合邻域 |
| 能力门 | 允许已接入的 fastMultiChunk；保留 concurrentBuildingInfo、parity.auto/worldparity.auto、实际街道模式和强制模式冲突检查 |
| 旧代码与命名 | 删除旧原型及专属测试闭包、旧参数携带链、旧高程缓存、旧配置/持久格式解析；现行入口与资源去除 V2 前缀 |
| 诊断 | /citylines chunk 增加真实 city/level/G 和已加载块的四图只读核对；不借诊断修改世界 |
| 分析工具 | scan_world 不再按旧高度场分区验收；只报告道路材质候选，不能替代城市资格判断 |

资产现名包括 `citylines:standard`、`citylines:street`、`citylines:road_damage`、`citylines:road_*`、`citylines:bridge_deck`，不保留旧 ID 转发。RoadType 使用 NONE/TERTIARY/SECONDARY/PRIMARY，表键同步改为每格 2 bit。道路断面仍为主干 1/1/12、集散 2/1/10、本地 4/1/6（单侧步行/路缘/车行总宽），几何没有重新设计。

全部 13 个 `road/core/V3*.java` 生产文件与本次修改前快照逐字节相同；38 个资产 JSON 在仅替换上述资源名称后结构一致。删除旧桥原型测试后补充当前 V3BridgePlannerTest，不把删除旧测试等同于删除现行桥功能。

## 4. 接口说明

| 入口 | 契约与调用方 |
|---|---|
| CitylinesRoadState.isEligible() | 构造阶段的能力与资产资格，不等于已放行生成 |
| CitylinesRoadState.isActive() / TerrainOwnership.isManaged(provider或dimension) | 当前维度接管身份；只读已登记绑定，不采样地形，不在 worker 初始化存储 |
| TerrainFlattening.onSpawnProvider / onLevelLoad / onLevelUnload | 服务端生命周期入口。新主世界在 TLC 模式初始化后绑定；既有当前世界及动态维度在 Level.Load 解析；卸载清理 |
| CityGroundBinding | NOT_READY 初始化中；READY 当前规则生效；NATIVE 从未激活的原生路径；BROKEN 契约失败。后两者不可混同 |
| CityGroundState | 每维度 data/citylines_generation.dat；唯一 schema=1、identity、ground。无旧解析；首次生成前确认持久化成功 |
| TerrainFlattening.flattenedLevel(coord, provider) | 适用城市为 0，否则 NO_LEVEL，调用方继续原生层高 |
| TerrainFlattening.withContext(generator, sampling, operation) | 区分噪声填充与 raw 高度采样，嵌套/异常均恢复原线程上下文 |
| ChunkHeightmapRepair.commit(driver, chunk, write) | 同步提交作用域，验证 primer 归属；仅作用于受管维度；两处 TLC 提交都覆盖 |
| ChunkHeightmapRepair.changed(x,z) / owns(chunk) | 写入包装标记局部 0..15 列；原假更新仅在当前同块提交中跳过；不加载邻块 |
| V3Planner / V3FactSource | 纯算法和抽象事实接口，不依赖 Minecraft；K=3 与主次轴盐保持不变，最新道路身份为 7，跨面路线以有效街区深度排序 |
| V3RoadGraph / V3RoadRights | 统一有效图、显式边、三级映射、整足迹路权与断面选择；不能用“邻块也是路”替代显式边 |
| Lc2hTerrainSelector | 类转换时扫描 opcode/owner/name/descriptor，必须恰好一条指令且在实例方法内；冻结 MethodNode，min/max=1，无逐块扫描、无全局 MethodNode 缓存 |
| ExtractionCitiesDomainCheck | 保留当前 EC override 域校验和经验证后的身份更新；删除历史高度模式豁免 |

Mixin 落点：Context 工厂 require=allow=1；三处地形包装各 1；两处 TLC 提交合计 2；SectionCache 真实 setBlockState 写点 1；假高度更新 4。任何缺失/多匹配拒绝启动，不静默失效。

当前诊断命令（权限 2）：`/citylines roads`、`/citylines chunk 11 43`、`/citylines sweep x0 z0 x1 z1`。chunk 的高度图部分只读已加载块；未加载明确报告，城市/规划查询本身可填充规划缓存。

## 5. 初版整改时的真实验证

以下游戏启动、地形及性能记录来自本轮路网改动之前；它们不能证明版本 4 的游戏内观感或生成性能。

所有服务端运行均使用 `build/uniform-smoke/` 下隔离目录，没有启动原 run/ 世界。种子 8218742260916520910、onlycities、G=71、optimizedHeightmap=false、heightSampleSize=3。不是用此种子重新选道路参数。

| 项目 | 结果 |
|---|---|
| JUnit | 105 tests，0 failure / error / skipped；包含当前图、桥、资产、身份、四谓词高度扫描、动态选择器 |
| 道路生成工具 | 20,596 断言；32 街道件、1 桥面、106 有向键 |
| TLC-only 新世界 | initial-tlc-0928b：READY、实际道路 Y=71、Done 40.926s，正常保存关闭 |
| 联合 TLC+LC2H+Quantified+EC | initial-compat-0928：三个选择器准确匹配；READY；实际道路生成；Done 26.177s，正常关闭 |
| 联合世界重开 | 同一当前记录再次 READY；Done 4.429s；目标高度图保持正确 |
| 最终构建的 TLC-only 重开 | LC2H 钩子正确跳过；READY；Done 2.782s；正常保存关闭 |
| 联合依赖客户端 | 隔离 `build/uniform-smoke/client-initial-0928` 启动：通过原先 Mixin 缺类点，进入 Render thread、声音与贴图 atlas 初始化；无 FATAL/ERROR/崩溃报告。随后主动终止仅此测试客户端，故 Gradle 的 runClient 任务返回非零；未使用原 run/ |
| 城市样本 (0,3) | 城市 level=0、ground=71，主干部件存在；四图误差 0 |
| 问题块及四邻 | 每块 256 列、四类型共 5,120 次比较，全部 mismatches=0/maxError=0 |

问题块实体共享边界（不是比较各块最高点）：

| 与 (11,43) 的边 | 旧报告落差 | 本次新世界落差 |
|---|---:|---:|
| 西 | 70 | 1 |
| 东 | 76 | 2 |
| 北 | 73 | 1 |
| 南 | 64 | 1 |

新目标实体地表 min/median/max 为 90/103/116；四邻中位数依次西96、东101、北139.5、南81。读存档时扫描全部区段，按旧诊断方法过滤植被，并额外排除树干/竹等非地表；四图检查不做这种过滤，而使用真实 Minecraft 谓词。北邻仍有自然山体与树冠，不能据此声称整个地图无高差。旧目标历史上到底命中哪条分支仍未被日志回溯证明；这里证明的是整改后同坐标的结果。

详细输出：`build/initial-target-surface.txt`、`build/initial-target-heightmaps.txt`；运行日志与导出的实际变换类在各隔离目录中。

### 性能检查

同种子、相同 8192/8192 起点、8x8=64 个新目标区块，3GB 最大堆，同一联合依赖环境、JFR 开启、逐行等所有目标 loaded：整改组 10.092s，enabled=false 原生路径参考组 18.848s。两者都会生成周边依赖块，225/64 是目标数量，不是全部流水线任务数。记录见 build/initial-managed-workload-8192.txt 与 build/initial-native-workload-8192.txt。

这是单次粗粒度检查：参考组仍加载 Citylines 空操作钩子，不是物理移除 JAR 的纯 TLC+LC2H；JIT、缓存热度与此前区域访问不完全一致，不能外推固定提速比例或宣告性能边界全面达标。该样本未见明显退化。

相同 64 块工作负载的 JFR 为整改组 debug/server-2026-09-28-003747.jfr、参考组 debug/server-2026-09-28-003358.jfr，各在对应隔离目录。报告使用 2048 栈深；整改组 CityShiftField 栈命中 0，修复高度图扫描命中 1/1556。0 样本不是绝对无开销，单样本也不适合定量估计扫描成本；确定性保证来自 Context 工厂旁路和实际变换类。withContext 包含整个原生噪声调用，其栈占比不能算作 Citylines 的独占 CPU 开销。LC2H 有非 Worker-Main 线程，不能仅按该线程前缀计全体生成成本。

### 构建产物

build、test、reobfJar、jarJar、reobfJarJar 已成功执行。用于实际安装的是 **build/libs/citylines-1.0.0-all.jar**（本次约 410 KB），内含 MixinExtras 0.3.6；不要与无内嵌依赖的普通 JAR 同时安装。JAR manifest 含构建时间戳，每次重建 SHA-256 会变化；需要核对时对当前文件重新计算，不引用旧交付哈希。

两个 JAR 均核实包含动态选择器、单独编译的地形适配器、Heightmap Invoker 和 citylines.refmap.json，不含旧高度场/平衡/原型类或 v2 资源。开发服务端的 refmap 缺失警告来自开发输出布局；正式 JAR 已核实含有映射。实际启动验证使用 ForgeGradle 开发服务端，未运行独立安装的重混淆 JAR 客户端。

## 6. 实施中纠正的错误与共识边界

1. 最初 `method="*"` 无法命中 LC2H 合入的 MixinMerged 方法。真实联合启动失败，不能用静态看见指令证明注入成功。与 DeepSeek 第四轮明确覆盖需求、地形效果、逻辑、性能、功能边界、实现难度、兼容、验证、交付九方面后，改为当前精确动态选择器。
2. 编译期 AP 不会加载运行期插件，因此仅把该 remap=false 适配器隔离编译；最初只把新目录算作 Gradle classesDirs，遗漏 IntelliJ 生成配置实际仅加载 `classes/java/main`。客户端提前报缺类；现在由准备任务复制到主目录。不是关闭所有 AP、require=0 或猜合入方法名。
3. 早期两次 TLC-only 冒烟被过早读取街道模式误判 NATIVE，不能算通过。只有修复生命周期后的 initial-tlc-0928b 计入。
4. 小规模计时最初用 say 作为 RCON 完成标记，广播不回传给 RCON，产生无效等待；该 2048 当前模式计时弃用。修正为 time query 后重新测未生成的 4096 区域。无效记录保留，不冒充性能数据。
5. 原生参考组 15x15 批量请求超过 RCON 30 秒等待上限，2048/4096 两次不构成有效对照。最终缩为双方都未生成的 8192 区域、8x8；每次只移除工具自己的 force-load 票据，全部隔离服务端均正常停止。
6. 用户实际 IntelliJ 客户端启动时，`MOD_CLASSES` 只包含主类目录；首次交付的单独 Mixin 类仅在另一目录，启动在配置加载前报 `ClassNotFoundException`。现将该类安装到主类目录；隔离客户端已越过故障点。另发现编译临时 refmap 可能在增量构建中丢失，导致新 JAR 不含映射；对临时输出登记跟踪并实测移走临时副本后重编恢复。旧 refmap 缺失 JAR 不可交付，以当前重新构建的 `-all.jar` 为准。

设计依据：[初版整改方案与共识公示](<D:/Workspace/Knowledge Base/Lost Cities/路网优化方案/初版整改-2026-09-27/初版整改方案与共识公示.md>)。第四轮原始回复在同目录 审议记录/04-DeepSeek选择器补充回复.md。已有主线共识没有授权重新调参，本轮没有这么做。

## 7. 初版整改变更审计

本次修改前快照：`backup/initial-remediation-before-20260927-231947.zip`。仅用它审计本轮差异，不用于整体恢复，也没有对照更早备份覆盖别人代码。完整逐文件增删改见 [初版整改-变更清单](初版整改-变更清单.md)；资源更名在清单中表现为删除旧文件、增加新文件，不代表删掉相应道路功能。

实际删除对象包括 HeightField、FlatteningMath、BalancedCityLevels、PlannerParams、旧原型和独占基础类型/测试；可从上述 ZIP 恢复单个历史文件。没有删除 run/ 存档、玩家配置、用户 JFR、依赖或参考源码。

初版整改时的客户端隔离启动已通过模组加载与渲染初始化，但未代替后续道路版本的可玩性或完整性能验收。

## 8. 版本 4 路网改动与交付

以下是当时的版本 4 历史交付，不是当前规则。依据：[V3 路口标线与三级道路实现方案](<D:/Workspace/Knowledge Base/Lost Cities/路网优化方案/V3-拟真优先/V3-路口标线与三级道路-实现方案.md>)。当轮只动路网规划和必需的路面资源，不改桥、地形、高程、站口和 TLC/LC2H 接入分支。

| 范围 | 当前实现 |
|---|---|
| 路口中心 | 相向主干臂遇垂直接入时中心铺装不画白线，入口引线与边缘截面保留；纯直路仍有中线。Java 角色网格、生成器与 JSON 同步。 |
| 三级路线 | 保留 K=3 与原深度阈值。优先直线双端，直线不可用时尝试最多两折的双端窄路，短尽端仍是回退；至多穿过一个同层高级路节点。入口先做廉价有效性过滤再按每侧预算截断。 |
| 图与建筑 | 路线的新格和完整路径分开记录；只增加自己的边，稀有建筑 7x7/9x9 需要让位时整条撤销。中间高级路格的等级和原有边不变；提交前重验占用。 |
| 资源 | 新增纯三级转角 `road_t_00tt`，共 33 街道件、110 有向键；不新增入口加转角复合件。 |
| 身份 | `V3Params.CURRENT_VERSION=4`。没有旧世界迁移或 LEGACY 回退；旧测试世界不要与新图混合生成。 |

同一版本 3 身份下的抽象诊断曾测到旧直路/尽端方案 2104 三级格，当前候选 2926 格；其中关闭跨面后为 2905，增长主要来自双端折线。直路与折线全量竞争的试算仅把三级格从 2914 提到 3047，却将精确深度评分从 4793 推至 7587 次，因此最终保留直线优先、不可用时再试折线，以控制冷建预算。这些都是抽象图数据，不是游戏帧率或现实感验收。

版本 4 的 120x120 开阔城、四组确定性输入：主/次/三级为 3776/3074/3184 格，路面占比 17.4%，闭合面 355 个，面积中位数 133；TLC 对照路面占比为 26.9%。普通足迹清单下 3x3 地块为 576/576；加入 7x7、9x9 足迹的抽象检查仍保留可行超单元。事实采样 65536 次/64 单元，没有额外跨单元事实查询；候选校验与评分在单元快照上完成。抽象性能不能替代实际游戏生成性能。

本轮仅执行道路资产生成器 `--check`（21415 断言）、`RoadPartAssetsTest`、`V3AbstractCasesTest`、`V3RoadGraphTest`、拓扑默认行的两个定点断言，以及 `build -x test`。未跑全量 JUnit、游戏客户端或生成世界，最终视觉与性能验收由用户执行。当前安装产物为 `build/libs/citylines-1.0.0-all.jar`；不要与普通 JAR 同时安装。

## 9. 版本 7 铁路冲突与跨街区三级路

共识与边界见[知识库方案](<D:/Workspace/Knowledge Base/Lost Cities/路网优化方案/V3-拟真优先/V3-铁路冲突与跨街区三级路-共识及交付.md>)。此次不修改水面桥、地形高程、主次轴 K=3、跨三个闭合面或多 host 路由；不增加玩家配置、迁移/LEGACY 分支、铁路高架/隧道或楼梯搬迁。

| 改动 | 当前契约 |
|---|---|
| 站台路权 | `Railway.getStationType` 返回前的单点 Mixin 只读纯几何主次轴与铁路前的原始城市事实，绝不请求 V3 最终 plan。站台本体占可铺主次轴时改为同层数的普通地下 `HORIZONTAL` 轨，站台取消但轨道贯通；只有可能的地表延伸/首段坡道命中时改为 `STATION_UNDERGROUND`，保留其楼梯。站台决策由 TLC 自己的相邻段递推传播。 |
| 其余铁路件 | TLC 在街道之后写出的地下站楼梯、地表站台延伸与足以碰到路面的下坡/同高铁路是 V3 硬障碍；不逐格删除铁路。地下站楼梯整区块保守让路，三级路不得被后写楼梯覆盖。 |
| 跨面三级路 | 单 host / 双闭合面、建筑保护、同层、两折、每侧残余不劣和已选 K=3 保持。移除仅在残余持平时要求新路线总格数不少于两条基线的门槛；跨面专用比较器优先真实街区深度，每个 10x10 区域至少 4 个三级格才算有效。每侧最多 6 个模板、每对面最多 5 个 host。主次轴布局盐仍为 4。 |
| 身份与交付 | `V3Params.CURRENT_VERSION=7`。新建验收世界；不做旧测试存档迁移。安装产物为 `build/libs/citylines-1.0.0-all.jar`（约 433 KB），包含新 Railway Mixin、裁决器和 refmap。 |

四组抽象输入、每组 16 个 30x30 单元的对照：旧规则 P/S/T 为 3776/3074/3184，跨面 32 条，模板检查 9840、深度评分 4731；旧规则当时没有记录“每街区至少 4 格”的指标，不能回填为 20。版本 7 采用 6 模板后为 3776/3074/3194，跨面 37 条，其中 25 条在所穿过的三个 10x10 街区内各铺至少 4 格；本轮改评分前的中间版本相应为 20 条。模板检查 12680、深度评分 5478，事实采样仍为 65536，丢失可行超大建筑为 0。7x7 可保留单元为 62/64，9x9 为 55/64。8 模板虽升到跨面 39/有效 27，但检查增加到 15520，未采用。**这些指标只证明有限的抽象拓扑改善，不证明实景观感或 TLC+LC2H 总生成开销达标。**

本轮仅运行铁路事实/站台裁决及 V3 抽象相关定点测试、`compileJava`、`reobfJarJar -x test`，均通过；没有运行游戏、全量测试或新的世界生成性能对照。用户验收时重点看新世界区块 (0,9) 对应的铁路进出站关系、主路是否仍被铁路覆盖、地下站楼梯是否被三级路压住，以及三级路是否真正贯穿多个街区。该区块在旧世界已生成，不能靠重开旧存档验证新规则。
