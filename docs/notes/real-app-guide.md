# 在本机跑造梦（zaomeng）+ 多模型接入改造指南

> **本文是设计期的历史资料，记录「一改」那一轮的方案。** 当时的做法是「先拿上游源码，
> 再把改造打进去」；现在本仓库本身就是打完补丁的版本，所以：
> - 文中的 clone / 安装地址已全部改为**本仓库**；
> - 第四节要移植的那两个 Kotlin 文件**已经在本仓库里**（见下），且已通过编译；
> - 保留本文是为了说明改动意图与后续可做项，不代表当前还需要手工移植。

> 沙箱内无法完整下载/编译该 Kotlin Multiplatform 工程（无网络拉依赖、无 JDK/Gradle），
> 以下步骤在你本机执行即可。

## 一、本机环境准备
- **Git**：用于克隆仓库。
- **JDK 21+**：KMP 工程要求（所有模块 `jvmTarget = JVM_21`）；CI 用 21。
- **Android SDK**（仅当你要打 Android APK）：含 build-tools 与 platform。
- 桌面端**不需要** Android SDK，最省事，优先用它验证。

## 二、获取源码
```bash
git clone https://github.com/dz3197578117-lab/zaomeng.git
cd zaomeng
```
> 注意：本仓库 `src/`、`src/web/` 是**不再维护**的旧 Python/Web 实现，仅作行为对照；
> 主力实现在 `kmp/`。另：2.0.0 为**阻断性升级**，旧版（1.5.0 及更早）本地数据不兼容，
> 升级前请先在旧版导出书卷包 `.zaomeng-run.zip` 备份。

## 三、运行方式（按平台选）

### 桌面端（推荐，无需 Android SDK）

桌面端只支持 **macOS 与 Linux**（仓库不再维护 Windows 构建）：

```bash
./gradlew :app:desktopApp:run
```
启动后在界面内：**设置 → 模型配置** 填 API Key / base URL / 模型名；导入书卷（TXT/EPUB/书卷包）；开聊。

### Android
```bash
./gradlew :app:androidApp:assembleDebug   # 生成 APK（需 signing + SDK）
```

### 旧版 Web UI（不再维护，仍可用）
```bash
curl -fsSL https://raw.githubusercontent.com/dz3197578117-lab/zaomeng/main/scripts/install.sh | bash
source ~/.bashrc
zaomeng web --reload      # 访问 http://127.0.0.1:8000
```

### Skill 包（OpenClaw / ClawHub，命令行/代理环境）
```bash
# 本仓库自带 zaomeng-skill/，直接装本地的
python scripts/install_skill.py --skills-dir <你的-skills-根目录>
# 或按名称从注册表安装
npx clawhub@latest install zaomeng-skill
```

## 四、多模型接入改进的落地情况

两个 Kotlin 文件**已经在仓库里**，且已通过 `:server:llm` 的 JVM / Android 编译：

- `kmp/server/llm/src/commonMain/kotlin/top/wkbin/zaomeng/ktor/providers/ProviderPresets.kt`
- `kmp/server/llm/src/commonMain/kotlin/top/wkbin/zaomeng/ktor/providers/ModelFetcher.kt`

但它们**尚未接入 UI**：界面上的提供方预设走 `feature/settings` 的 `modelCatalogs`，
「从接口拉取模型」走 `LlmClient.listModels()`。若要改走这两个文件，需要做的是
（对齐 `kmp/AGENTS.md` 的模块边界）：
1. **模型配置 UI**（定位 `kmp/feature/` 下与设置/模型配置相关的模块，如 `settings` / `modelConfig`）：
   - Provider 下拉绑定 `ProviderPresets.all`，选完自动带出 `defaultBaseUrl` 与模型快速选项；
   - 「刷新模型」按钮调用 `ModelFetcher.fetch(preset, apiKey, client)`；
   - `LlmProfile` 增加 `providerId` 字段（现有按 profile 存密钥的 `ModelApiKeyService` 不用动）。
2. **LlmClient 收口**：把现有 OpenAI 兼容请求构造抽到 `OpenAiCompatibleProvider`；
   新增 `AnthropicProvider` / `GeminiProvider` / `AzureOpenAiProvider`（形态适配器）；
   由 `ProviderRegistry` 按 `providerId` 分派。Anthropic/Gemini/Azure 当前在 `ModelFetcher` 里是**非流式兜底**，
   若要真实流式需补各自的 SSE 解析（见 `zaomeng-model-integration-analysis.md` 的架构图）。
3. **输出侧不动**：`DialogueResponseParser` / `DialogueStreamParser` 是造梦自有 NDJSON 协议，provider 无关，保持原样。
4. **验证**（从 `kmp/` 目录）：
   ```bash
   ./gradlew :server:llm:jvmTest :server:llm:compileKotlinJvm :app:shared:compileKotlinJvm
   ```

## 五、商汤（SenseTime）接入要点（已核实 2026-10）
- 兼容模式 `baseUrl = https://api.sensenova.cn/compatible-mode/v2`，Bearer 鉴权 → **现有 LlmClient 改 baseUrl 即可用**，无需适配器。
- 另有 `https://token.sensenova.cn/v1` 也走 OpenAI 兼容，择一。
- 自带 `SenseChat-Character` / `SenseChat-Character-Pro` 拟人对话模型，与角色扮演场景高度契合，建议作造梦默认推荐。
- 推理模型会先吐 `delta.reasoning` 思考增量；`SenseNova-V6.5` / `SenseChat-Vision` 支持多模态。
- 模型 ID 与单价以官网为准（`https://www.sensecore.cn/help/.../compatible-mode`）。

## 六、附：相关设计文档与示例（均已入库）
- `docs/notes/model-integration-analysis.md`：第三方模型接入诊断 + Provider 抽象层设计（含架构对比图）。
- `kmp/server/llm/src/commonMain/.../providers/ProviderPresets.kt`：14 个 Provider 预设（国内 8 + 海外 6，含商汤）。
- `kmp/server/llm/src/commonMain/.../providers/ModelFetcher.kt`：按接口动态拉取模型清单（失败回退预设）。
- `examples/zaomeng-lite/`（可选）：纯 Python + 前端的轻量网页版，`python server.py` 即可运行，用于无依赖快速体验。
