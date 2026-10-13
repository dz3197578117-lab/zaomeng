<p align="center">
  <img src="docs/images/zaomeng_logo.png" alt="造梦" width="120">
</p>

# 造梦

> “有些角色不是被写完了，只是还没被真正叫醒。”

把中文小说人物蒸馏成可复用的人物包，抽取关系图谱，再让角色按自己的性格、立场、关系和记忆重新开口说话。

[![License: AGPL-3.0-only](https://img.shields.io/badge/License-AGPL--3.0--only-8A2BE2.svg)](LICENSE) · [官网](https://dz3197578117-lab.github.io/zaomeng/) · [English](README.en.md) · [**造梦能做什么 →**](docs/features.md)

> 本仓库是在上游造梦 2.0 基础上继续改的版本。
> **想知道它现在能做什么，看 [造梦能做什么](docs/features.md)**（按能力域汇总，并标注每项从哪版开始有）；
> 逐版本变更记录见 [`CHANGELOG.md`](CHANGELOG.md)，**当前版本以 CHANGELOG 顶部为准**（这里不再写死版本号，避免与实际 APK 不一致）。

## 本版改动

相比上游 2.0，本仓库持续在补三件事：**不联网也能跑**、**国内模型开箱可用**、**把「整理」与「演出」做扎实**。

**想一次看全它现在能做什么，看 [造梦能做什么](docs/features.md)**（按能力域汇总，每项标注从哪版开始有）。下表只列本仓库相对上游的主要改动：

| 改动 | 说明 | 起始版本 |
| --- | --- | --- |
| 端侧本机推理 | APK 内嵌 llama.cpp 运行时，可导入 GGUF；章节摘要 / 单轮续写 / 关系与时间线抽取 / 蒸馏主线均可走本机，不填 API Key 也能用 | 2.1.2 |
| 提供方预设 +5 家 | 商汤 SenseNova、硅基流动、月之暗面 Kimi、智谱 GLM、MiniMax | 2.1.2 |
| 动态拉取模型清单 | 模型配置页走 `GET {baseUrl}/models` 填充下拉，失败回退内置预设 | 2.1.2 |
| 关系图谱连线 · 角色质量总览 · 对话起始章 | 关系页补连线（线宽/透明度随强度）；运行详情页加质量总览；创建对话可选起始章 | 2.1.2 – 2.1.3 |
| 运行日志采集与导出 | 内嵌服务持续采集本进程日志，应用内可查看/复制/导出；导出前统一脱敏 | 2.1.5 |
| 生成超时与重试 | 蒸馏等非流式请求超时 60 秒 → 300 秒（可按档案微调），瞬时断连自动重试，失败文案改中文 | 2.1.5 |
| **修复插件 / 剧情导演一用就闪退** | 成员扩展自递归导致主线程栈溢出（**所有工具流程都受影响**） | 2.1.6 |
| 导演「强制执行」 | 所有导演动作（含第四面墙）可要求角色必须落实指令，不得拒绝/弱化/推迟 | 2.1.7 |
| **逐章人设弧线 + 按时间点注入** | 蒸馏产出逐章状态；对话按当前时间点取用身份与认知，**不再从第一章开局就带着全书认知** | 2.1.8 |
| **人物时间线随剧情推进** | 每 4 轮推进一章（由 `start_chapter` 与轮次推导，重试同一轮不会多推进），会话上方显示当前时间点 | 2.1.9 |
| **修复对话闪退与「同一句重复出现」** | 分页前插不去重 + 服务端缺 turnId 幂等，导致同一消息进列表两次；两层相同消息的列表 key 撞车直接闪退 | 2.1.10 |
| 角色名引号归一化 · 本机模型独立页 | 修 `"李慧英"` 同时进允许与禁止名单；端侧配置从模型档案编辑页拆出 | 2.1.11 |
| **运行诊断与运行日志分页 + 采集控制 + 三层问题捕获** | 采集启停与清除旧日志；应用内悬浮窗；进程内 handler / `ApplicationExitInfo` / 结构化事件三层捕获 | 2.1.12 |
| **功能自检（27 项探针）** | 各能力域自己注册探针并报告是否正常，同时列出「哪些功能还没有任何探针覆盖」 | 2.1.13 |
| 画面与流畅度信号 | 掉帧率、过度重组、布局跳动、闪烁检测（阈值仍需真机校准） | 2.1.13 |
| **内容审核拒绝与请求出错分开** | 上游按内容策略拒收时单独归类（`dialogue.content_rejected`），聊天页不再给注定无效的「重试」 | 2.1.14 |
| **移除 Windows 支持** | 桌面端只发布 macOS / Linux：删掉 JNA 深色标题栏、Windows TTS、`.exe` 分支、Windows 安装包配置与网站上的 Windows 声明（Windows 桌面包从未真正发布过） | 2.1.14 |
| 存储层容忍无法表示的路径 | 旧书卷里角色名带引号时，档案路径在部分系统上无法表示 → 读取抛异常会带走整轮对话生成；现在按「不存在」处理，其余角色不受影响 | 2.1.14 |
| **内置模型清单：点名字一键下载并选中** | 4 个 Qwen GGUF 预设；下载源可在 HuggingFace 官方与 hf-mirror 镜像间切换；每个模型都标注**能力来源**（确切仓库 / 发布者是否官方 / 上游基座 / 元数据读取时间 / 上游模型卡） | 2.1.15 |
| **端侧 CPU 内核（Arm KleidiAI）** | 随包二进制含 KleidiAI 运行时派发内核（按 CPU 特性选 dotprod / fp16 / i8mm，老机型自动回退），**编译基线仍是 armv8-a**；2.1.15 及更早的 APK 是旧构建、不含它 | 2.1.16 |
| **端侧 GPU（Adreno / OpenCL）：构建可编，默认不随包** | 构建脚本能编出 OpenCL 后端（上游已验机型含 Snapdragon 8 Elite / Adreno 830），但它会引入非系统依赖 `libOpenCL.so`。2.1.16 / 2.1.17 曾随包，**导致端侧在加载阶段就起不来**（见 2.1.18）；现默认不随包，等真机验证后再定 | — |
| 三处真机诊断查实的缺陷修复 | 内容审核判据只认文案、漏掉上游机器可读的 `code`；`model_configured` 恒为 false；本地（端侧）档案的 `base_url` 指向死端口 | 2.1.15 |
| **修复「设置聊天背景后闪退」** | 大图解码没有下采样：9216×6144 的照片是 216 MB，超过 Android `Canvas` 的 100 MB 硬上限，且抛在**绘制期**（`try` 兜不住）。现按目标长边与字节上限两趟解码，216 MB → 3.4 MB；修在唯一解码入口，头像/封面/详情图一并受益 | 2.1.17 |

相对上游的逐文件清单见 [`docs/changes-from-upstream.md`](docs/changes-from-upstream.md)：**该文档是某一轮的快照**，其中的「新增 / 修改 / 删除」计数按当时统计、不随仓库继续增长（复核方法也写在里面）；逐版本细节见 [`CHANGELOG.md`](CHANGELOG.md)。

## 维护

换机器或在新环境继续维护、发版，请看：

- [`docs/maintainer-setup.md`](docs/maintainer-setup.md) —— 环境准备、**恢复发布签名密钥**、换机器清单、以及一堆实际踩过的环境坑（aapt2 架构、JVM 库污染、网络与 DNS 等）
- [`docs/release-android.md`](docs/release-android.md) —— Android 发布流程与签名密钥管理

自检与构建脚本（都在仓库根目录执行）：

```bash
./scripts/check-env.sh            # 环境自检：缺什么会明确告诉你
./scripts/build-android-apk.sh    # 一条命令构建 APK
./scripts/check-release-ready.sh  # 发版前自检
```

## 项目构成

| 目录 | 说明 | 维护状态 |
| --- | --- | --- |
| [`kmp/`](kmp/README.md) | Compose Multiplatform 客户端：Android / 桌面两端共享 UI，内嵌 Ktor + Room 后端 | ✅ 当前主力 |
| `zaomeng-skill/` | OpenClaw / ClawHub skill 包（命令行 / 代理环境使用） | ✅ 保留 |
| `src/`、`src/web/` | 早期 Python 后端与 Web UI | ⛔ **不再维护**，仅作行为对照与测试基线 |

## 安装

### 客户端

预编译的 Android 包见 [Releases](https://github.com/dz3197578117-lab/zaomeng/releases/latest)（`arm64-v8a` 调试版，随包内嵌 llama.cpp 运行时；**版本以最新 tag 为准**）。Release 附件由 GitHub Actions 在打 tag 时自动构建，不再依赖仓库里手工提交的二进制。桌面的构建说明见 [`kmp/README.md`](kmp/README.md)。

> ⚠️ **2.1.4 是一次性重装版本**：2.1.2 / 2.1.3 使用的签名密钥已丢失，2.1.4 无法覆盖安装 2.1.3。正在用 2.1.3 的用户请先在旧版把书卷导出为 `.zaomeng-run.zip`（**存到「下载」目录**），再卸载重装。从 2.1.4 起改用妥善备份的专用密钥签名，后续版本恢复正常覆盖安装。详见 [`docs/release-android.md`](docs/release-android.md)。

> ⚠️ 从旧版 Android（1.5.0 及更早）升级到 **2.0.0** 为**阻断性升级**，本地数据不兼容、不会自动迁移；升级前请先在旧版导出书卷包（`.zaomeng-run.zip`）备份。

### skill 包

```bash
# 本地 skills 目录（本仓库自带 zaomeng-skill/）
python scripts/install_skill.py --skills-dir <你的-skills-根目录>

# 或装进某个项目：会落到 <项目根>/skills/zaomeng-skill
python scripts/install_skill.py --project-root <项目根>

# ClawHub（按名称从注册表安装）
npx clawhub@latest install zaomeng-skill
```

### Web UI（旧版，不再维护，仍可安装使用）

```bash
# 一键安装（Linux / macOS / WSL2 / Termux）
curl -fsSL https://raw.githubusercontent.com/dz3197578117-lab/zaomeng/main/scripts/install.sh | bash
source ~/.bashrc
zaomeng
```

安装后常用命令：`zaomeng web --reload`（启动 Web UI，访问 `http://127.0.0.1:8000`）、`zaomeng uninstall`、`zaomeng update`。手动方式：`pip install -r requirements.runtime.txt` 后运行 `python scripts/run_webui.py --reload`。依赖：完整开发/测试用 `requirements.txt`，Termux 用 `requirements.termux.txt`（含 `httpx2` 等 Web 测试依赖；EPUB 解析为可选能力）。

## 截图

| 手机端 | 桌面端 |
| --- | --- |
| <img src="docs/images/mobile.jpg" height="360" alt="手机端"> | <img src="docs/images/desktop.png" height="360" alt="桌面端"> |

## 许可证

[AGPL-3.0](LICENSE)
