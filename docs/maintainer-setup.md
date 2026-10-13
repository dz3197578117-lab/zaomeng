# 换机器维护指南

本文件面向**维护者**：把仓库换到一台新机器后，如何恢复到能构建、能发版的状态。

它记录的是「这台机器要有什么」和「以前踩过的环境坑」。
发布流程本身见 [`release-android.md`](release-android.md)；日常构建见 [`kmp/README.md`](../kmp/README.md)。

---

## 一、新机器检查清单

按顺序做，每步都有验证命令。

### 1. 基础环境

| 需要 | 说明 |
| --- | --- |
| JDK 21+（CI 用 21） | 所有模块 `jvmTarget = JVM_21` 且未配 `jvmToolchain`，低版本 JDK 编不出来。机器上可能装了多套，**其中一套可能是坏的**，脚本会实测筛选 |
| Android SDK | 需含 `platforms/android-37`（compileSdk 37）与任一 `build-tools` |
| git 身份 | 新机器通常没配，提交会直接失败 |

```bash
git config user.name  "你的名字"
git config user.email "你的邮箱"
```

一条命令自检环境（缺什么会明确告诉你）：

```bash
./scripts/check-env.sh
```

### 2. 恢复发布签名密钥（**最关键**）

GitHub secret 是**只写不可读**的，换机器无法从它取回密钥。必须有一份离线备份。

```bash
# 从备份的 .jks 或 .b64 恢复，并自动校验指纹是否与仓库里配置的一致
./scripts/restore-release-keystore.sh /path/to/zaomeng-release.jks
```

脚本会写入 `kmp/keystore.properties`（已被 gitignore）并打印指纹；
若与 [`.github/workflows/release.yml`](../.github/workflows/release.yml) 的
`EXPECTED_SIGNER_SHA256` 不一致，会明确告警——**指纹不一致就不能发版**，
否则用户装不上（详见 `release-android.md` 的签名章节）。

> ⚠️ 没有离线备份会怎样：只能重新生成密钥。那样所有已安装用户都无法覆盖安装，
> 必须卸载重装（丢书卷与会话）——2.1.4 就是这么被迫重装一次。
> **密钥备份要做两处，互相独立。**

### 3. 克隆与构建

```bash
git clone https://github.com/dz3197578117-lab/zaomeng.git
cd zaomeng

# 在仓库根目录执行，不是 kmp/ 里
./scripts/build-android-apk.sh            # debug 包
```

脚本会自动处理 JDK / SDK / aapt2 / sqlite 的探测，正常情况下**不需要手动设置任何环境变量**。

### 4. 发布前自检

```bash
./scripts/check-release-ready.sh
```

---

## 二、换机器最容易踩的坑

这些都是实际踩过的，脚本已尽量自动化，但知道原因能省很多时间。

### 1. aapt2：arm64 上跑不了 AGP 自带的版本

AGP 从 Maven 取的 `aapt2` 是 **x86-64 ELF**，在 arm64 机器上直接执行失败。
解决办法是 `-Pandroid.aapt2FromMavenOverride=<可执行的 aapt2>`，
`build-android-apk.sh` 会自动探测。

**但系统自带的 aapt2 未必够新**：例如 Debian 打包的 `2.19` 读不了 compileSdk 37 的
`android.jar` 资源表，报：

```text
LoadedArsc.cpp:94 RES_TABLE_TYPE_TYPE entry offsets overlap actual entry data.
Failed to load resources table in APK '.../android-37.0/android.jar'
```

所以脚本不猜路径，而是**逐个实测**能否解析当前平台的 `android.jar`，取第一个可用的。
如果你有特定版本要指定：

```bash
AAPT2_OVERRIDE=/path/to/aapt2 ./scripts/build-android-apk.sh
```

### 2. `LD_LIBRARY_PATH` 不能全局导出

Android/Termux 下的 aapt2 需要 `LD_LIBRARY_PATH` 指向它自带的 `.so`。
但**不能**把它 export 给整个构建——Android 的 bionic `libc.so` 会被 JVM 捡走：

```text
java: error while loading shared libraries: /lib/aarch64-linux-gnu/libc.so: invalid ELF header
```

构建脚本的做法是包一层 wrapper，让这个变量只作用于 aapt2 进程。

### 3. aapt2 的文件名有要求

AGP 会校验 override 的文件名，不符合就报：

```text
Custom AAPT2 location does not point to an AAPT2 executable: /path/to/aapt2-xxx
```

所以 wrapper 必须叫 `aapt2`，不能叫 `aapt2-test` 之类。

### 4. proot / Termux 下 Room3 的 KSP 会加载错 sqlite

在「Android 设备上跑 glibc JVM」时，Room3 的 KSP 处理器会挑到 jar 内
Android/bionic 版的 sqlite 原生库，加载失败。脚本会在检测到
`/system/build.prop` 时自动改指 glibc 版：

```bash
KSP_SQLITE_DIR=/path/containing/libsqlitejdbc.so ./scripts/build-android-apk.sh
```

### 5. `apksigner` 输出格式随 build-tools 版本变化

同一份证书，前缀不一样：

| build-tools | 输出 |
| --- | --- |
| 36.0.0 及更早 | `Signer #1 certificate SHA-256 digest: …` |
| 37.0.0 | `V2 Signer: certificate SHA-256 digest: …` |

任何解析都**不能锚定行首**。工作流现在会在日志里打印 `apksigner --version`、
build-tools 路径与 `--print-certs` 原始输出，解析失败时据此直接定位。

### 6. 网络：GitHub 连不上或 DNS 解析失败

本类环境（尤其国内网络 / proot）常见现象：

```text
Failed to connect to github.com port 443
GnuTLS recv error (-110): The TLS connection was non-properly terminated.
Recv failure: Software caused connection abort
```

处理办法：探测一个可用 IP 写进 `/etc/hosts`，然后**重试**（大文件下载要支持续传）：

```bash
# 探测
for ip in 140.82.112.3 140.82.113.3 140.82.114.3 140.82.121.3; do
  code=$(curl -sS --connect-timeout 6 --resolve "github.com:443:$ip" \
    https://github.com -o /dev/null -w "%{http_code}")
  [ "$code" = "200" ] && echo "$ip 可用"
done

# 写入（api.github.com 同类处理）
echo "140.82.113.3 github.com"     >> /etc/hosts
echo "140.82.113.6 api.github.com" >> /etc/hosts
```

GitHub 的 Release 附件下载经常被中途掐断，**用 `-C -` 续传**而不是从头重来：

```bash
curl -L -C - --retry 10 --retry-all-errors -o out.apk "<url>"
```

### 7. 提交时提示 Author identity unknown

```text
fatal: unable to auto-detect email address
```

新机器没配 `user.name` / `user.email`，按上面「基础环境」配一下。

---

## 三、仓库里哪些东西**不在**版本控制里

换机器时最容易漏的就是这些——它们被 gitignore 挡住，`git clone` 拿不到：

| 文件 | 作用 | 怎么恢复 |
| --- | --- | --- |
| `kmp/keystore.properties` | 本地构建用发布密钥签名 | `scripts/restore-release-keystore.sh` |
| `kmp/local.properties` | SDK 路径（可选） | 设 `ANDROID_HOME` 即可，通常不需要 |
| `*.jks` / `*.keystore` | 签名密钥本体 | **只能从离线备份恢复** |

GitHub 侧的 4 个 secret（`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、
`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`）由 CI 使用，**只写不可读**，
所以它们不能当作备份。

---

## 四、版本号的唯一来源

发版只需改**一处**：

```properties
# kmp/gradle.properties
zaomeng.versionName=2.1.5
zaomeng.versionCode=18
```

以下位置全部由它推导，**不要手写**：

- `:app:androidApp` 的 `versionName` / `versionCode`
- `:app:desktopApp` 的 `packageVersion`
- 应用内「版本信息」与桌面端更新检查所用的 `AppMetadata`（构建期生成）
- `/api/web/health` 返回的 `version`（构建期生成）

> 曾经这些是手写常量，结果应用内显示 2.1.2 而 APK 已是 2.1.4，桌面端更新检查
> 也一直误判。`scripts/check-release-ready.sh` 会检查一致性，防止复发。

---

## 五、发一个新版本

> **同步规矩**：每次改动都要**提交并推送**，不要留只在本地的东西；
> 一旦改了 `kmp/gradle.properties` 的版本号，就必须把 tag 与 Release 一起补上——
> 否则会出现「仓库写着 2.1.6、最新发布还是 2.1.5」的错位，用户拿不到修复。
> 版本号 / tag / Release / CHANGELOG 四者必须始终一致。
> 自检：`./scripts/check-release-ready.sh` 会检查前四者的一致性。

```bash
# 1) 改版本号（只改这一处）
$EDITOR kmp/gradle.properties

# 2) 写发布说明（缺了会被 CI 拦下）
$EDITOR .github/release-notes/v2.1.6.md
#    并在 CHANGELOG.md 顶部加一节

# 3) 自检
./scripts/check-release-ready.sh

# 4) 提交、推送、打 tag
git add -A && git commit -m "release: 2.1.6"
git push origin main
git tag -a v2.1.6 -m "造梦 2.1.6" && git push origin v2.1.6
```

tag 推送后 [`.github/workflows/release.yml`](../.github/workflows/release.yml)
自动构建、校验签名指纹与版本号、上传 Release 附件。

**不需要**本地构建 APK 再提交——`dist/*.apk` 已移出版本控制。

## 七、仓库 About（不在仓库里，换机器要重建）

GitHub 仓库页顶部的 **About 区（描述 / 主页 / topics）不随仓库同步**——它只存在 GitHub 上，
换机器 clone 不会带过来。当前设定的值如下，需要时用命令重建。

描述：

> 造梦 — 把中文小说蒸馏成可对话的人物。KMP + Compose Multiplatform：多角色对话、逐章人设弧线与时间线推进、剧情导演与第四面墙、插件系统、端侧 llama.cpp 推理（GGUF，支持 Adreno GPU 加速与内置模型一键下载），可完全离线运行

主页：<https://dz3197578117-lab.github.io/zaomeng/>

topics：`android` `compose-multiplatform` `gguf` `kotlin-multiplatform` `llama-cpp`
`local-llm` `novel` `offline` `persona` `roleplay`

重建命令（需要一个有 `repo` 权限的令牌）：

```bash
REPO=dz3197578117-lab/zaomeng
TOKEN=<有 repo 权限的令牌>

curl -sS -X PATCH -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/$REPO" \
  -d '{"description":"造梦 — 把中文小说蒸馏成可对话的人物。KMP + Compose Multiplatform：多角色对话、逐章人设弧线与时间线推进、剧情导演与第四面墙、插件系统、端侧 llama.cpp 推理（GGUF，支持 Adreno GPU 加速与内置模型一键下载），可完全离线运行","homepage":"https://dz3197578117-lab.github.io/zaomeng/"}' >/dev/null

curl -sS -X PUT -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/$REPO/topics" \
  -d '{"names":["android","compose-multiplatform","gguf","kotlin-multiplatform","llama-cpp","local-llm","novel","offline","persona","roleplay"]}' >/dev/null
```

两个我实际踩到的坑：

- **写操作必须带 `Authorization` 头**。读仓库信息是公开接口、不带令牌也能成功，
  所以只测「读」会误以为令牌没问题，直到 PATCH 返回 401 才发现。
- **`topics` 不能用 PATCH 设**，要用专门的 `PUT /repos/{owner}/{repo}/topics`
  （用 PATCH 传 `topics` 会被静默忽略，返回体里 topics 仍是空）。

### 加功能 / 发版时必须一起更新的「对外描述面」

这几处的共同点：**它们都在说「造梦现在能做什么」，因此每次加能力或发版都会过期**。
漏掉任何一处，就会出现「仓库里写了、用户看到的地方还没有」或者反过来的错位。

| 面 | 位置 | 能否离线校验 |
|---|---|---|
| 仓库页 About 描述 | GitHub 设定；**权威文案在本文件上方**，改文案后必须重新 PATCH | ❌ 只存在 GitHub 上 |
| About 之外：`topics` | 同上（`PUT …/topics`） | ❌ |
| README「本版改动」表 | [`README.md`](../README.md)（英文版 [`README.en.md`](../README.en.md) 同步） | ⚠️ 只能人工看 |
| 能力域清单 | [`docs/features.md`](features.md)（按域汇总，每项标「起始版本」） | ⚠️ 只能人工看 |
| 逐版本细节 | [`CHANGELOG.md`](../CHANGELOG.md) + [`.github/release-notes/`](../.github/release-notes/) | ✅ `scripts/check-release-ready.sh` |

两条经验：

- **不要在这几处写死版本号**——README 已经这么做了（「当前版本以 CHANGELOG 顶部为准」）。
  同理，`docs/changes-from-upstream.md` 那个「新增 / 修改 / 删除」计数是**某一轮的快照**，
  README 不再把它当成实时数字，只指向该文档。
- **未发布的能力**在「起始版本」栏写「未发布」，等真正切版本号时再改成版本号——
  提前写一个还没发的号，会让这三处和 CHANGELOG 立刻互相矛盾。

## 八、新增功能时必须动两处：`KnownFeatures` 与探针注册

「运行诊断」页的**功能自检**不是写死的清单：每个功能自己注册一个探针，
诊断页与诊断导出会自动列出所有已注册探针。因此**漏注册在界面上表现为「一片安静」**
（看起来全绿，其实那项根本没被检查过）。

所以新增功能时，必须在**两处各加一条**：

1. **`kmp/core/contracts/.../data/api/KnownFeatures.kt`** 的 `KnownFeatures.all`
   里加一条 `KnownFeature(id, title, domain)`——它是「期望存在的功能」的**单一来源**，
   服务端（算未注册）与设置页（显示未注册）共用同一份；
2. **探针注册处**加一个引用该 `id` 的探针：
   - 通用入口：`kmp/server/.../ktor/features/SystemProbes.kt` 的 `registerAllFeatureProbes`；
   - 分域实现：`BookshelfProbes.kt` / `ChatProbes.kt` / `PluginProbes.kt` / `ModelProbes.kt`，
     各自有 `registerXxxProbes(registry, ...)`；
   - 纯逻辑判定放到 `FeatureAssessments.kt`（`assessXxx(...)`），并补 `jvmTest`。

只加一处会怎样：只加清单 → 诊断页显示「1 个功能未注册」并列出它的名字；
只加探针 → 诊断页显示「探针引用了未知功能 id」（`unknownFeatureRefs`）。

探针的硬性约束（写在 `FeatureProbe` 的 KDoc 里，改代码前先读）：

- **只读**：上下文 `FeatureProbeContext` 没有任何写入口，不许「顺手修复」业务数据；
- **廉价可缓存**：读小文件/清单即可，禁止跑模型；缓存由 `FeatureProbeRunner` 统一负责
  （默认通过结论缓存 30 秒、失败结论缓存 3 分钟、单探针 5 秒超时）；
- **独立失败**：抛异常/超时只转成自己这条 `FAIL`，不影响其它探针或整页诊断；
- **没数据 ≠ 坏了**：没有样本时返回 `NO_DATA`，已知的环境性原因返回 `SKIPPED`，
  **绝不返回 `FAIL`**；
- **与运行日志采集开关无关**：探针不得依赖 `RuntimeCaptureState`
  （`FeatureProbeCaptureIndependenceTest` 会拦住这条）。

改完跑一次 `./scripts/check-prompt-sync.sh` 与 `kmp` 下的
`:server:jvmTest :feature:settings:jvmTest`。
