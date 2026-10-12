# 变更记录

本文件记录造梦（本仓库改版）的逐版本变更。版本号与
[`kmp/app/androidApp/build.gradle.kts`](kmp/app/androidApp/build.gradle.kts) 的 `versionName` 保持一致。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。
每个 tag 对应的 GitHub Release 正文取自 [`.github/release-notes/`](.github/release-notes/)。

## [2.1.4] - 未发布

本次聚焦「蒸馏/生成不再因为超时和瞬断而失败」与「出问题能自己拿到日志」。

### 新增

- **运行日志采集与导出**：内嵌服务持续采集本进程 `logcat`
  （`logcat -v threadtime --pid=<pid>`，含崩溃记录；2 MiB 滚动，保留最近 1 MiB）。
  设置内新增「运行日志」页可查看与导出，诊断导出包内嵌整段日志，
  排查线上问题不再需要用户自己抓 logcat。
- **独立运行日志导出**：`GET /api/web/diagnostics/runtime-log?lines=N`。
- **按档案配置生成超时**：模型档案新增 `timeout_seconds`
  （30..3600 秒，越界回退默认 300 秒）。该字段不经由 UI 写入，直接编辑设置文件即可，
  保存常规设置时会原样保留。见 [`docs/server-api.md`](docs/server-api.md)。

### 修复

- **蒸馏等非流式生成请求超时 60 秒 → 300 秒**：此前默认 60 秒不足以跑完一次内容提取，
  现场表现为随机失败，且异常栈里只有不可读的 `Software caused connection abort`。
  现在生成类请求与流式读取共用 300 秒档位。
- **瞬时断连/超时不再直接失败**：`retryWithBackoff` 此前只重试 429/5xx，
  现在对本端超时与服务端/网关主动断连也会退避重试。
- **失败原因改为可读中文**：区分「模型响应超时：N 秒内模型未返回结果」与
  「与模型服务的连接被中断（网关或网络断开…）」，不再抛出 Ktor 的英文原始消息。
- **诊断报告「是否配置 API Key」不再永远为 false**：改为查询安全存储的实际结果。
  此前 `ModelProfile.apiKeyConfigured` 从不被保存路径写入，导致已配好 Key 的档案
  在诊断里也显示「未配置」，排查时会直接走错方向。

### 变更

- 诊断导出 `schemaVersion` 2 → 3（新增内嵌运行日志与崩溃记录字段）。
- 运行日志、崩溃记录与诊断事件在导出前统一走 [脱敏规则](kmp/server/src/commonMain/kotlin/top/wkbin/zaomeng/ktor/services/LogRedactor.kt)：
  JSON 键值 → `Bearer` → URL 查询串 → 通用 `key=value` → 裸密钥。

### 仓库与发布流程

- Release 附件改由 GitHub Actions 在推送 `v*` tag 时**自动构建**，
  不再依赖仓库里手工提交的二进制；`dist/*.apk` 移出版本控制
  （此前每发一版仓库体积 +39 MB）。
- 新增 [`CHANGELOG.md`](CHANGELOG.md) 与 [`docs/release-android.md`](docs/release-android.md)。
- 新增 **Actions → Build APK** 工作流：推送到 `main`（改动 `kmp/**`）或手动触发即可构建，
  产物在该次运行的 **Artifacts** 区域下载，**不需要配置任何 secret**。
- 新增 [`scripts/build-android-apk.sh`](scripts/build-android-apk.sh)：一条命令本地构建，
  自动探测 JDK 与 Android SDK，并用「实测能否解析当前平台 `android.jar`」的方式挑选可用
  aapt2（arm64 主机上 AGP 自带的 aapt2 是 x86-64 ELF，无法执行）。

### ⚠️ 破坏性变更：需要卸载重装一次

2.1.2 / 2.1.3 使用的签名密钥（Android SDK 自动生成的 debug 密钥，证书指纹
`3c5cb56d…`）已经丢失，无法再签出与它们签名一致的包，因此
**2.1.4 不能覆盖安装 2.1.3**。升级步骤：

1. 先在 2.1.3 里把每个书卷导出为 `.zaomeng-run.zip`，**存到「下载」目录**
   （存在应用目录里会在卸载时被一并删除）；
2. 卸载 2.1.3；
3. 安装 2.1.4，重新导入书卷包。

从 2.1.4 起改用**项目专用密钥**签名（`debug` 与 `release` 两个 build type 共用，
由 `kmp/keystore.properties` 指定、CI 从 4 个 secret 注入），后续版本恢复正常覆盖安装。

新密钥证书指纹（公开信息，CI 会校验产物必须由它签名）：

```text
09426ef9eafc96a37ea16c4293d93966eb055a116ef778a598acc359ba8e9049
```

## [2.1.3] - 2026-10-12

**Android 调试版**（`zaomeng-2.1.3-arm64-v8a-debug.apk`）。

### 修复

- **修复「对话完全不可用」（`Model reply did not contain usable character responses`）**：
  模型提取人物名单时经常返回**内嵌引号**的名字（例如把主角写成 `"李慧英"`），
  引号混进会话参与者列表后，模型输出的干净名字与其对不上，整轮回复被全部过滤。
  现在从人物名清洗、蒸馏、建 run 到回复解析全链路统一剥离包裹引号，并在解析层做了
  兼容归一化——**已经导出的旧书卷无需重新蒸馏即可继续对话**。
- **修复端侧模型下载失败会误删已有模型**：下载过程始终写 `.part` 半成品，
  异常时只清理 `.part`，不再把覆盖前的可用旧模型一起删掉。
- **修复网络瞬断导致整轮对话失败**：流式请求在读首个字节之前遇到
  429/5xx/连接失败会自动退避重试（429 按限流建议退避）；
  一旦开始输出正文则不再重试，避免重复输出与重复计费。
- **修复长对话越聊越卡**：流式回复解析器改为增量投影，
  消除随回复长度呈平方增长的性能开销。
- **修复诊断导出空白、事件日志重启后丢失、地址脱敏把 `https://host/v1` 误截成 `https:`**。

### 变更

- **强化「剧情导演」指令**：导演选中的方案被当作本轮必须落实的舞台指示处理——
  覆盖「剧情推进 / 第四面墙 / 旁白」三种输入；明确不得改写、弱化或推迟；
  人物可保留性格化的抗拒与谈判，但必须给出可执行的回应，且本回合要产生可指认的剧情变化。
- **诊断能力增强**：新增关键事件日志（对话解析失败含模型输出摘录、插件动作成败、
  插件安装/启停、端侧推理生命周期），随「导出诊断」一并给出。

### 安装

- 与 2.1.2 调试版**签名一致**，可直接覆盖安装。

## [2.1.2] - 2026-10-12

**Android 调试版**（`zaomeng-2.1.2-arm64-v8a-debug.apk`）。

### 新增

- **端侧本机推理**：APK 内嵌 llama.cpp 运行时，设置内新增「本机模型（端侧）」入口，
  可用系统文件选择器导入 GGUF 模型（分片流式写入，不留半成品）。
  章节摘要、对话单轮续写、关系与时间线抽取、蒸馏主线都可以走本机，模型与推理数据不出设备。
- **新增 5 家提供方预设**：商汤 SenseNova、硅基流动、月之暗面 Kimi、智谱 GLM、MiniMax。
  模型下拉共 16 项（14 家提供方 + 本机模型 + 自定义）。
- **可按接口动态拉取模型清单**：模型配置页可「从接口拉取模型」，
  走 `GET {baseUrl}/models` 填充下拉；拉取失败自动回退内置预设。

### 修复

- **修好 Ollama 地址**：`http://127.0.0.1:11434` 补上缺失的 `/v1`，
  否则会拼出错误的 `/chat/completions`。
- **修掉端侧推理 500**：归一化对话消息形状（最多一条 `system` 且必须位于首位），
  修开放权重模型 GGUF 内置 Jinja 模板报 `System message must be at the beginning.` 的问题。
- **自动更新源改指向本仓库**：原先指向上游，会把原版的包当成自己的新版本发下来。

## 2.1.1 及更早

早期版本（含从上游造梦 2.0 继续改动的部分）逐文件清单见
[`docs/changes-from-upstream.md`](docs/changes-from-upstream.md)。

[2.1.4]: https://github.com/dz3197578117-lab/zaomeng/compare/v2.1.3...HEAD
[2.1.3]: https://github.com/dz3197578117-lab/zaomeng/compare/v2.1.2...v2.1.3
[2.1.2]: https://github.com/dz3197578117-lab/zaomeng/releases/tag/v2.1.2
