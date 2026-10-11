# 本版源码清单：相对上游改了什么

本文件回答一个问题：**本仓库里哪些源码是我们改的、上游没有的？**

- 上游基线：`wkbin/zaomeng`，提交 `f178b4f83bbd152e2f82c50c2b7835d8b823c03a`（导出包共 1070 个文件）。
- 对照对象：本仓库 `main` 已跟踪文件，共 1091 个。
- 比对方式：逐文件 SHA-256，分「新增 / 内容修改 / 删除」三类；新增文件再逐条查上游是否存在（404 = 本版独有）。
- 核对时间：基线上游 `main` 当天仍为 1070 个文件、内容与上述提交一致，因此下表中「修改」的文件**全部**是本版改动，不含上游自身的后续修改。

## 汇总

| 类别 | 文件数 | 说明 |
|---|---:|---|
| 新增 | 21 | 上游无此路径，逐条 HTTP 404 已核实 |
| 内容修改 | 63 | 与基线逐字节不同；上游未动过这些文件 |
| 删除 | 0 | 无 |
| **合计** | **84** | 文本改动合计 +2998 / −316 行 |

下面按功能分组。有些文件同时服务多个功能（例如 `LlmClient.kt` 既是提供方适配也是端侧推理的调用层），会在多组里重复出现，因此各组数量之和大于 84。

---

## 一、端侧本机推理（llama.cpp 子进程）

在手机上离线跑推理，对上层而言只是「一个不需要 API Key 的本机端点」。

### 新增（15）

| 文件 | 作用 |
|---|---|
| `kmp/app/androidApp/src/main/jniLibs/arm64-v8a/libllama-server.so` | llama.cpp 运行时，Release + `llvm-strip --strip-all` 后 10.5 MB |
| `scripts/build-llama-server-android.sh` | 交叉编译脚本（NDK 28.2.13676358） |
| `kmp/server/src/commonMain/.../platform/OnDeviceRuntime.kt` | 端侧运行时接口 |
| `kmp/server/src/androidMain/.../platform/AndroidOnDeviceRuntime.kt` | Android 实现：起子进程、探活、拉起/停止 |
| `kmp/server/src/jvmMain/.../platform/JvmOnDeviceRuntime.kt` | 桌面端实现 |
| `kmp/server/src/commonMain/.../ktor/services/OnDeviceModelService.kt` | 进程/模型清单管理，按 `dataRoot` 做进程内单例 |
| `kmp/server/src/commonMain/.../ktor/services/GgufMetadataReader.kt` | 流式读 GGUF 头，拿上下文长度等元信息 |
| `kmp/server/src/commonMain/.../ktor/routes/OnDeviceModelRoute.kt` | `/api/web/on-device/*` 路由 |
| `kmp/core/contracts/.../data/api/OnDeviceModelContracts.kt` | 端侧状态/模型 DTO |
| `kmp/feature/settings/.../OnDeviceModelSection.kt` | 模型编辑页内的端侧区块 |
| `kmp/feature/settings/.../OnDeviceModelsScreen.kt` | 「本机模型（端侧）」页面 |
| `kmp/feature/settings/.../OnDeviceModelsViewModel.kt` | 页面状态与轮询 |
| `kmp/ui/shared/src/commonMain/.../platform/ModelFileImporter.kt` | 导入 GGUF：FileKit 选文件 + 流式分片拷贝 |
| `kmp/server/src/jvmTest/.../GgufMetadataReaderTest.kt` | 单测 |
| `kmp/ui/shared/src/jvmTest/.../ModelFileImporterTest.kt` | 单测 |

### 修改（12）

| 文件 | 改动 |
|---|---|
| `kmp/app/androidApp/build.gradle.kts` | 开 `jniLibs { useLegacyPackaging = true }`——否则 `.so` 留在 APK 内被 mmap，**无法执行** |
| `kmp/server/src/commonMain/.../ktor/KtorServiceGraph.kt` | 挂载端侧服务（`OnDeviceModelService.getOrCreate`），并挂载章节索引服务 |
| `kmp/server/src/commonMain/.../platform/Platform.kt` | 新增 `onDeviceRuntime(): OnDeviceRuntime? = null` 默认实现，不支持时其余功能不受影响 |
| `kmp/server/src/androidMain/.../platform/AndroidServerPlatform.kt` | 提供 Android 端侧运行时 |
| `kmp/server/src/jvmMain/.../platform/JvmServerPlatform.kt` | 提供桌面端端侧运行时 |
| `kmp/feature/settings/.../SettingsHomeScreen.kt` | 设置首页新增「本机模型（端侧）」入口 |
| `kmp/app/shared/.../di/SharedViewModelModule.kt` | 注册 `OnDeviceModelsViewModel` |
| `kmp/app/shared/.../navigation/AppDestination.kt` | 新增 `OnDeviceModelsDestination` |
| `kmp/app/shared/.../navigation/ZaomengNavHost.kt` | 接上端侧页面；另新增 `onOpenPersona`，把关系图节点接到人物档案（见第三节） |
| `kmp/data/remote/.../KtorModelSettingsClient.kt` | 新增 `onDeviceStatus()` 等调用 |
| `kmp/data/repository/.../RuntimeFacades.kt` | 端侧接口 |
| `kmp/data/repository/.../RuntimeRepositories.kt` | 端侧实现 |

> `.gitignore` 里末尾几行是**反向排除**，专门把上面那个 `.so`（会被 `*.so` 误伤）和 `dist/*.apk`（会被 `dist/` 误伤）留在版本库内，不要删。

---

## 二、模型提供方预设 + 按接口动态拉取模型清单

把「手填 base URL + key + 模型名」变成「选一个提供方 + 填 key」；模型清单不再硬编码，改成按接口拉。

### 修改（7）

| 文件 | 改动 |
|---|---|
| `kmp/feature/settings/.../SettingsViewModel.kt` | `modelCatalogs` 扩到 16 条，新增商汤 SenseTime、硅基流动、月之暗面 Kimi、智谱 GLM、MiniMax；修正 Ollama 地址为 `…:11434/v1` |
| `kmp/feature/settings/.../ModelProfileEditorViewModel.kt` | `fetchModels()` + `fetchingModels` / `availableModels` 状态 |
| `kmp/feature/settings/.../ModelSettingsScreen.kt` | 模型选择走动态拉取结果 + 「从接口拉取模型」按钮 |
| `kmp/server/llm/.../LlmClient.kt` | 新增 `listModels()`；`ProviderKind`（OpenAI / Anthropic / Gemini / Azure）四路分支；system message 处理 |
| `kmp/server/.../ktor/services/SettingsManagementService.kt` | `listModels()`：取 Key、建 `LlmClient`、失败回退 |
| `kmp/server/.../ktor/routes/SettingsManagementRoute.kt` | `POST /api/web/settings/model/models` |
| `kmp/server/llm/.../DialoguePromptBuilder.kt` | 提示词拼装适配 |

### 修改（4，与上一组共用）

`kmp/core/contracts/.../ModelContracts.kt`（新增 `ModelListResponseDto`、`api_key_required`）、`kmp/data/remote/.../KtorModelSettingsClient.kt`（`fetchModels()`）、`kmp/data/repository/.../RuntimeFacades.kt` + `RuntimeRepositories.kt`（接口与实现）。

另新增单测 `kmp/server/llm/src/commonTest/.../LlmClientSystemMessageTest.kt`。

无 Key 端点（Ollama、自建、端侧内嵌）由 `apiKeyRequired = false` 放行——见 `kmp/server/storage/.../models/DataModels.kt`。

---

## 三、关系图谱补上关系连线

上游的 `RelationsScreen` 只画节点、不画关系。本版补上连线与语义，全部在

`kmp/feature/relations/.../RelationsScreen.kt`（+186 / −35）：

- 新增 `RelationGraphEdge` 与 `relationGraphEdges()`：由关系条目连出有向边，跳过不在节点集合里的名字；
- 新增 `relationStrength()`：线宽 `1.2 → 5.0 dp`、透明度 `0.5 → 0.95` 随强度变化；
- 新增 `relationEdgeColor()`：绿=高信任、红=高敌意、紫=高情感；
- 连线中点挂关系类型标签（边 ≤6 条时显示 6 个，否则 4 个）；
- 节点可点击，经新增的 `onOpenPersona` 进入该人物档案校对；
- 附一句图例文案说明线粗与配色。

> `RelationGraphTest.kt` 是上游就有的，本次未改动，覆盖的是 `relationGraphNodes()` 去重；上述新增的 `relationGraphEdges()` / `relationStrength()` 目前没有测试。

---

## 四、角色质量总览

从「角色档案有没有缺字段 / 待修」聚合成一张总览卡。

| 文件 | 改动 |
|---|---|
| `kmp/core/contracts/.../PersonaContracts.kt` | `PersonaQualityOverviewDto` / `…ItemDto` |
| `kmp/server/.../ktor/services/PersonaService.kt` | `qualityOverview(runId)` |
| `kmp/server/.../ktor/routes/PersonaRoutes.kt` | 对应路由 |
| `kmp/data/remote/.../KtorPersonaClient.kt` | 客户端调用 |
| `kmp/data/repository/.../PersonaFacades.kt` / `PersonaRepositories.kt` | 接口与实现 |
| `kmp/feature/rundetail/.../RunDetailScreen.kt` | `PersonaQualityOverviewCard` / `QualityMetric` / `GradeBadge` / `qualityHint` |
| `kmp/feature/rundetail/.../RunDetailViewModel.kt` | 状态装载 |

---

## 五、从正文自动提取主要角色

| 文件 | 改动 |
|---|---|
| `kmp/core/contracts/.../RunContracts.kt` | `ExtractMainCharactersRequest` / `…Dto` / `ExtractRunCharactersRequest` |
| `kmp/server/.../ktor/services/DistillExecutor.kt` | `extractMainCharacters()` + 长文抽样 + 结果解析 |
| `kmp/server/.../ktor/services/RunOperationsService.kt` | `extractMainCharactersFromRun()` |
| `kmp/server/.../ktor/routes/RunOperationsRoute.kt` | 对应路由 |
| `kmp/data/remote/.../KtorRunOpsClient.kt` | `extractMainCharacters` / `extractRunCharacters` |
| `kmp/data/repository/.../RunFacades.kt` / `RunRepositories.kt` | 接口与实现 |
| `kmp/feature/redistill/.../RedistillViewModel.kt` + `RedistillScreen.kt` | 重蒸馏流程里的入口 |
| `kmp/feature/importbook/.../ImportBookViewModel.kt` + `ImportBookScreen.kt` | 导入书籍流程里的入口 |

---

## 六、对话起始章选择

| 文件 | 改动 |
|---|---|
| `kmp/core/contracts/.../DialogueContracts.kt` | `start_chapter` / `start_chapter_title` |
| `kmp/core/domain/.../CreateDialogueSessionUseCase.kt` | `startChapter` 参数 |
| `kmp/server/src/commonMain/.../ktor/services/NovelChapterIndexService.kt` | **新增**：章节索引 |
| `kmp/server/src/commonMain/.../ktor/routes/NovelChapterRoute.kt` | **新增** |
| `kmp/core/contracts/.../NovelChapterContracts.kt` | **新增** |
| `kmp/server/src/jvmTest/.../NovelChapterIndexServiceTest.kt` | **新增**单测 |
| `kmp/feature/sessions/.../SessionsScreen.kt` | `NovelChapterPicker` |
| `kmp/feature/sessions/.../SessionsViewModel.kt` | `ensureNovelChapters()` / `selectStartChapter()` |
| `kmp/server/.../ktor/services/SessionManagementService.kt` | 会话携带起章信息 |
| `kmp/server/.../ktor/routes/SessionManagementRoute.kt` | 透传 |
| `kmp/data/repository/.../SessionFacades.kt` / `SessionRepositories.kt` | 透传 |
| `kmp/server/src/commonMain/.../ktor/KtorServiceGraph.kt` | 挂载 `NovelChapterIndexService` |

---

## 七、站点与 App 内引用改指向本仓库

原版的后台/官网/更新地址全部指向上游，会把「原版的包」当成自己的新版本发下来。

| 文件 | 改动 |
|---|---|
| `kmp/app/androidApp/.../update/AppUpdateManager.kt` | 更新源指向本仓库；下载链接白名单同步改（路径 `/dz3197578117-lab/zaomeng/releases/download/`） |
| `kmp/data/remote/.../update/ReleaseUpdateChecker.kt` | 桌面端更新源与发布页 |
| `kmp/feature/settings/.../AppSupportSettingsScreen.kt` | 关于页的官网 / 项目链接 |
| `website/index.html` | 20+ 处链接改指向；`softwareVersion` → 2.1.2；新增 `#changes`「本版改动」区块 |
| `website/plugin-development.html` | 8 处链接改指向 |
| `README.md` / `README.en.md` | 新增「本版改动」表；安装与投稿入口改指向 |
| `examples/declarative-plugin-template/README.md` | 插件开发教程链接 |
| `scripts/install.sh` | `ZAOMENG_REPO_SLUG` 默认值 |
| `src/web/service_facades/system_update.py` | `DEFAULT_REPO_SLUG`（需与 `install.sh` 一致） |
| `src/web/review/persona_completion.py` | User-Agent 中的仓库地址 |

**刻意保留指向原版的**：在线书卷包 `zaomeng-library`（无等价替代）、QQ 群号、AGPL 授权署名链接。**刻意不动**：包命名空间 `top.wkbin.zaomeng`（改了等于换 applicationId + 数据目录）。

---

## 八、跨端与 CI 修复

| 文件 | 改动 |
|---|---|
| `kmp/server/src/commonMain/.../backend/LocalBackendController.kt` | JVM 专有 API 泄漏到 commonMain：`@Volatile` → `kotlin.concurrent.Volatile`，`synchronized` 双检锁 → `kotlin.concurrent.atomics.AtomicBoolean`（带 `@OptIn`）。否则 iOS 目标编译失败 |
| `kmp/server/src/commonMain/.../ktor/services/GgufMetadataReader.kt` | 同上：okio `Source.use {}`（上界 `AutoCloseable`）→ `try/finally { close() }` |
| `kmp/server/src/commonMain/.../ktor/services/OnDeviceModelService.kt` | 同上 |
| `tests/test_plugin_system.py` | 2 个用例断言的路径随 KMP 模块拆分失效（UI 从 `:app:shared` 迁到 `:feature:*`）；断言随实现迁移但**语义不变**，且把两条反向断言的范围从单文件扩到整个 `feature/chat` 源码树（更严） |

---

## 怎么自己复核这份清单

```bash
# 1) 取上游基线（归档注释即基线提交号）
unzip zaomeng-main.zip -d /tmp/upstream-base     # → /tmp/upstream-base/zaomeng-main/

# 2) 逐文件 SHA-256 比对「基线」与「仓库已跟踪文件」
#    新增 = 只在仓库 / 修改 = 两边都有但内容不同 / 删除 = 只在基线
python3 - <<'PY'
import os, hashlib, subprocess
BASE, REPO = "/tmp/upstream-base/zaomeng-main", "."
def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for c in iter(lambda: f.read(1 << 20), b""): h.update(c)
    return h.hexdigest()
base = {os.path.relpath(os.path.join(r, f), BASE).replace("\\", "/"): sha(os.path.join(r, f))
        for r, _, fs in os.walk(BASE) for f in fs}
repo = {p: sha(p) for p in subprocess.run(["git", "ls-files"], capture_output=True, text=True)
        .stdout.split()}
print("新增", sorted(set(repo) - set(base)))
print("修改", sorted(k for k in set(repo) & set(base) if repo[k] != base[k]))
print("删除", sorted(set(base) - set(repo)))
PY

# 3) 确认某个新增文件上游确实没有（404 = 本版独有）
curl -s -o /dev/null -w '%{http_code}\n' \
  https://api.github.com/repos/wkbin/zaomeng/contents/scripts/build-llama-server-android.sh
```

## 对应的安装包

`dist/zaomeng-2.1.2-debug.apk`（arm64-v8a 调试版，versionName 2.1.2 / versionCode 11）由本仓库 `main` 的源码构建。校验：

```
sha256  9692be0e4af53606b6f4e5a22ea8e32eccfc494227b38d0325f181b13b320299
size    40275926 bytes
```
