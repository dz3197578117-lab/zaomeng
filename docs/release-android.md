# Android 发布流程

本文件说明如何发布 Android 版本、如何配置签名密钥，以及
[`.github/workflows/release.yml`](../.github/workflows/release.yml) 的前置条件。

> 换机器或在新环境搭环境（JDK / SDK / aapt2 / 恢复密钥）见
> [`maintainer-setup.md`](maintainer-setup.md)；一条命令自检用 `scripts/check-env.sh`。

## 当前状态：2.1.4 是一次性重装版本

2.1.2 / 2.1.3 用的是 Android SDK **自动生成的 debug 密钥**，它只存在于当时构建的
那台机器上，如今已经找不到，所以 **2.1.4 无法覆盖安装 2.1.3**。从 2.1.4 起改用
一把**项目专用密钥**并妥善备份，之后所有版本都能正常覆盖安装。

历史指纹（已丢失，仅作记录）：

| 版本 | 证书 SHA-256 | 状态 |
| --- | --- | --- |
| 2.1.2 / 2.1.3 | `3c5cb56d7221ed35ef03240cda3d718dfade061a9614b92aa62b4f299733ee48` | ❌ 私钥已丢失 |
| 2.1.4 起 | `09426ef9eafc96a37ea16c4293d93966eb055a116ef778a598acc359ba8e9049` | ✅ 已生成，**必须离线备份** |

> **为什么不能从旧 APK 里提取私钥**：APK 里只有公开材料（证书、公钥、签名值、内容摘要）。
> 数字签名就是用私钥签、用公钥验，从公钥或签名反推私钥等价于分解 2048 位 RSA 模数。

## 为什么必须用专用密钥

Android 只允许**签名相同**的 APK 覆盖安装；签名不同时系统会拒绝，用户只能卸载重装，
而卸载会清掉应用私有目录里的书卷、会话和端侧模型。

debug 包默认用 `$HOME/.android/debug.keystore` 签名，这把密钥由开发工具**在每台机器上
随机生成**：换机器、换 CI runner，签名就变了。这正是 2.1.2/2.1.3 丢掉签名密钥的根因。

## 一次性配置

### 1. 生成专用密钥

```bash
keytool -genkeypair -v \
  -keystore zaomeng-release.jks \
  -alias zaomeng \
  -keyalg RSA -keysize 4096 -validity 10000
```

口令请使用**字母数字组合**：`keystore.properties` 走 Java `Properties` 解析，
含 `\` 等字符需要转义，容易出错。

`-validity 10000` 约 27 年；Android 要求应用签名在应用生命周期内有效，不要设太短。

### 2. 立刻做离线备份（最关键的一步）

把 `zaomeng-release.jks` 与口令一起存到**至少两处**互相独立的地方，
例如密码管理器 + 离线磁盘。这一步偷懒，将来就会重演 2.1.4 这次被迫重装。
备份完成后可以删掉工作目录里的明文副本。

### 3. 取证书指纹

```bash
keytool -list -v -keystore zaomeng-release.jks -alias zaomeng | grep SHA256
```

得到形如 `31:3C:27:33:…:F7:3D` 的字符串，去掉冒号并转小写后备用。

### 4. 填入工作流（需要一次提交）

编辑 [`.github/workflows/release.yml`](../.github/workflows/release.yml) 顶部的 `env`：

```yaml
  EXPECTED_SIGNER_SHA256: "把上一步的小写无冒号指纹填这里"
```

这是一道**刻意设置的闸门**：签名密钥变更会让所有已安装用户无法覆盖安装，
所以必须走一次有审计的代码提交，而不是随手在 GitHub 界面上改。

### 5. 配置 4 个仓库 secret

仓库 **Settings → Secrets and variables → Actions → New repository secret**：

| 名称 | 值 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 zaomeng-release.jks` 的输出（单行，无换行） |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 口令 |
| `ANDROID_KEY_ALIAS` | `zaomeng` |
| `ANDROID_KEY_PASSWORD` | 密钥口令（与 keystore 口令相同即可） |

注意事项：

- 只粘贴文件内容，不要包含 `base64 …` 命令本身，也不要加引号。
- secret 保存后无法再读回，请以离线备份为准。
- 导出后删除临时文件：`shred -u release-keystore.b64`。
- **绝不**把密钥或口令提交进仓库（`.gitignore` 与 `kmp/.gitignore` 已挡
  `*.jks` / `*.keystore` / `keystore.properties`）。

CI 会在构建前把密钥还原到 `~/.android/zaomeng-release.jks`，并据此生成
`kmp/keystore.properties`（该文件不入库）。`debug` 与 `release` 两个 build type
都会用这把密钥签名，所以现有的 `assembleDebug` 流程不用改。

## 用户侧迁移（只此一次）

已装 2.1.3 的用户需要重装一次。发布前请务必在说明里写清楚：

1. **先在 2.1.3 里把每个书卷导出为 `.zaomeng-run.zip`，存到「下载」目录**——
   存在应用目录里会在卸载时一起被删掉；
2. 卸载 2.1.3；
3. 安装 2.1.4；
4. 重新导入 `.zaomeng-run.zip`。

从 2.1.4 起，后续版本恢复正常的覆盖安装。

## 发布一个版本

1. **改版本号**：编辑
   [`kmp/app/androidApp/build.gradle.kts`](../kmp/app/androidApp/build.gradle.kts)，
   `versionCode` 必须**严格大于**上一版，`versionName` 与要打的 tag 对应：

   ```kotlin
   versionCode = 18
   versionName = "2.1.5"
   ```

2. **写发布说明**：新增 `.github/release-notes/v<版本>.md`，Release 正文会引用它；
   文件不存在时只生成一句占位文字。同步更新 [`CHANGELOG.md`](../CHANGELOG.md)。

3. **提交并推送**到 `main`，等 `Android CI` 与 `Tests` 变绿。

4. **打 tag 并推送**：

   ```bash
   git tag v2.1.5
   git push origin v2.1.5
   ```

5. 工作流自动构建、校验、上传附件。完成后在
   [Releases](https://github.com/dz3197578117-lab/zaomeng/releases) 确认附件名形如
   `zaomeng-2.1.5-arm64-v8a-debug.apk`。

### 手动重跑

`workflow_dispatch` 支持指定一个**已存在**的 tag 重跑：

```bash
gh workflow run release.yml -f tag=v2.1.5
```

## 工作流会拦截什么

任一不通过即中止，不会发出有问题的包：

1. **4 个 secret 是否齐全**——缺失直接失败并列出缺哪个，附配置文档路径。
2. **密钥指纹**（构建前）——密钥本身必须等于 `EXPECTED_SIGNER_SHA256`，
   避免白等一次编译。
3. **产物签名**（构建后）——用 `apksigner` 复核产物确实由该密钥签名，
   防止 `keystore.properties` 没生效却照常发版。
4. **版本号与 tag 一致**——tag `v2.1.5` 必须配 `versionName = "2.1.5"`。
5. **附件名含 `arm64-v8a`**——应用内更新检查依赖它挑选正确的包
   （见 `AppUpdateManager.parseLatestRelease`）。

## 关于 `dist/` 与手工预览包

- `dist/*.apk` 已移出版本控制，Release 附件由 CI 构建。
- **不想配 secret 也能拿到 APK**：推送到 `main`（改动 `kmp/**`）或手动触发
  **Actions → Build APK**，在该次运行的 **Artifacts** 区域下载即可。
  该工作流不需要任何 secret：
  - 已配置 4 个签名 secret 时，用专用密钥签名，与正式发布包一致；
  - 未配置时回退到**缓存住的** debug 密钥，产物可反复覆盖安装，
    但不能覆盖安装正式版；缓存被清除后会生成新密钥，届时需卸载重装一次。
- 本地构建：`./scripts/build-android-apk.sh`（在仓库根目录执行）。
  自动探测 JDK / Android SDK，并实测挑选可用的 aapt2。
  若本地存在 `kmp/keystore.properties`，产物会用专用密钥签名（可覆盖安装已发布版本）；
  没有该文件时会退回机器 debug 密钥，**那种包不能用于发布**。

本地配置示例（`kmp/keystore.properties`，已被 gitignore）：

```properties
storeFile=/绝对路径/zaomeng-release.jks
storePassword=你的口令
keyAlias=zaomeng
keyPassword=你的口令
```

## 故障排查

| 现象 | 原因与处理 |
| --- | --- |
| `缺少签名 secret` | 4 个 secret 未配齐或名称拼错，见上文表格 |
| `签名密钥不匹配` | secret 里的密钥不是预期那把；若是有意换密钥，先改 `EXPECTED_SIGNER_SHA256` |
| `产物签名不匹配` | `keystore.properties` 没生效：检查 `storeFile` 路径与 `keyAlias` |
| `无法读取产物签名` | 解析 `apksigner --print-certs` 输出失败。前缀随 build-tools 版本变化：**37.0.0 用 `V2 Signer:`**，36.0.0 及更早用 `Signer #1`——所以解析不能锚定行首。原始输出会打印在日志里，据此核对 |
| `版本号不匹配` | 忘记改 `versionName`，或 tag 与 gradle 版本不一致 |
| 用户反馈「应用未安装」 | 签名不一致；确认本次发布确实用了专用密钥 |
| 用户反馈「版本降级」 | `versionCode` 没有递增 |
