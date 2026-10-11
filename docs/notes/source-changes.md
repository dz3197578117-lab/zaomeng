# zaomeng 源码改动清单（真实代码）

源码已下载解包至 `zaomeng-main/`（1070 文件）。以下为针对「第三方模型接入」做的实际修改。

## 一、改了什么

### 1. 补齐 Provider 预设目录（含商汤）
文件：`kmp/feature/settings/.../SettingsViewModel.kt` 的 `modelCatalogs`

- 新增 5 家：**商汤 SenseTime**（`https://api.sensenova.cn/compatible-mode/v2`，含 SenseChat-Character 角色对话模型）、硅基流动、月之暗面 Kimi、智谱 GLM、MiniMax。
- 修复 **Ollama** baseUrl：`http://127.0.0.1:11434` → `http://127.0.0.1:11434/v1`（OpenAI 兼容端点需 `/v1`，原值会拼成错误的 `/chat/completions`）。

### 2. 按接口动态拉取模型清单
新增一条端到端链路 `GET {baseUrl}/models` → 填充模型下拉：

| 层 | 文件 | 改动 |
|---|---|---|
| 契约 | `core/contracts/.../ModelContracts.kt` | 新增 `ModelListResponseDto` |
| 服务端 | `server/llm/.../LlmClient.kt` | 新增 `listModels(baseUrl, apiKey)` + `ModelListResponse`/`ModelRef` |
| 服务层 | `server/.../SettingsManagementService.kt` | 新增 `listModels()` |
| 路由 | `server/.../SettingsManagementRoute.kt` | 新增 `POST /api/web/settings/model/models` |
| 客户端 | `data/remote/.../KtorModelSettingsClient.kt` | 新增 `fetchModels()` |
| 仓库 | `data/repository/.../RuntimeFacades.kt` + `RuntimeRepositories.kt` | 接口 + 实现 `fetchModels()` |
| UI | `feature/settings/.../ModelProfileEditorViewModel.kt` | 新增 `fetchingModels`/`availableModels` 状态 + `fetchModels()` |
| UI | `feature/settings/.../ModelSettingsScreen.kt` | 模型选择走动态拉取结果 + 「从接口拉取模型」按钮 |

拉取失败自动回退内置 catalog 预设，UI 永远有得选。

## 二、还没做（仍是主要欠缺）

- **Anthropic / Gemini / Azure 真实适配器**：当前 `provider` 字段只用于 UI 与 logo，`LlmClient` 一律走 OpenAI `/chat/completions`。catalog 里 `anthropic` 的 baseUrl 是空的、选它会落到 OpenAI 默认地址并失败。这是下一步最大的改动点（需要按 provider 分支请求/响应解析）。

## 三、验证

- 10 个改动文件均已通过括号配平粗校验。
- **无法在本沙箱编译**（无 JDK 17+/Gradle/联网拉依赖）。请在你本机验证：

```bash
cd zaomeng-main/kmp
.\gradlew.bat :server:llm:jvmTest :server:compileKotlinJvm :app:shared:compileKotlinJvm
```

改动的 Kotlin 文件若需逐行 diff，直接对比 `zaomeng-main/` 与上游 `wkbin/zaomeng@main` 即可。
