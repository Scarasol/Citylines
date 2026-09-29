# Citylines 当前开发约束

更新：2026-09-28。唯一当前交接文档为 [DELIVERY.md](DELIVERY.md)；本文件只保留开发约束，不再维护第二套完成度清单。

## 环境与方案

- Minecraft 1.20.1、Forge 47.4.0、Java 17、Gradle 8.8。
- TLC 7.5.5（必需）；LC2H 4.2.4-LTS、Quantified API、Extraction Cities 已进入开发运行依赖，发布依赖关系见 mods.toml。
- Gradle 缓存：D:/Library/.gradle。
- 当前整改依据：[初版整改方案与共识公示](<D:/Workspace/Knowledge Base/Lost Cities/路网优化方案/初版整改-2026-09-27/初版整改方案与共识公示.md>)，含第四轮选择器补充共识。
- 项目从未发布，不实现历史模式、资源别名、配置迁移或测试存档升级。保留当前版本正常重开所必需的输入记录。
- V3 是唯一现行算法代号。K=3；不得借地形整改调整拓扑、参数、盐或道路形制。

## 实施边界

- 使用附属模组与 Mixin，不修改依赖 JAR，不改 reference/ 快照。
- 允许 Inject / WrapOperation / Unique / Shadow / Accessor / Invoker / Implements。禁止 Redirect、Overwrite、自研反射。
- 可选模组由 CitylinesMixinPlugin 用 Forge 模组列表门控，仍使用单一 citylines.mixins.json。
- LC2H 三个地形落点使用只读 ITargetSelectorDynamic，精确指令唯一匹配，require=allow=1。不得退回通配方法或随机合入方法名。
- 只有 Lc2hTerrainOwnershipMixin 因运行期选择器而单独无 AP 编译；其他源文件的注解处理与 refmap 保留。单独类必须经 installRuntimeTerrainMixin 安装至 classes/java/main（IDE MOD_CLASSES 指向此处），两个临时 refmap 文件必须作为编译输出追踪。构建、开发运行和 JAR 均检查两者存在。
- 正式生成前在服务端线程冻结维度身份；worker 只读，不临时读取/写入 SavedData。
- 不扩大为通用地形生成器，不增加 profile 特判，不调整荒野到统一 G，不删除当前桥、下穿、站口和建筑足迹适配。

## 验证与数据保护

- 轻量开发检查：JUnit、道路资产自检、TLC-only 与联合依赖启动、问题坐标高度图/共享边界、当前世界重开。
- 最终视觉、游玩和完整性能验收归用户；不能用单次计时或包含原生调用的栈占比代替性能结论。
- 自动运行只使用 build/uniform-smoke/ 下的隔离目录。不得修改、迁移或删除 run/ 的配置、世界及用户火焰图。
- 无 Git 时只能对照本次修改前快照审计自己的改动，不得拿较早备份恢复整个项目或覆盖他人的工作。
