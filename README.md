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

逐文件的完整清单（相对上游：新增 35 / 修改 67 / 删除 2，含复核方法）见 [`docs/changes-from-upstream.md`](docs/changes-from-upstream.md)；逐版本细节见 [`CHANGELOG.md`](CHANGELOG.md)。

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
