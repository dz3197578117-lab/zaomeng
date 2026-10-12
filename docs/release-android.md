# Android 发布流程

本文件说明如何发布一个 Android 版本，以及
[`.github/workflows/release.yml`](../.github/workflows/release.yml) 的发布密钥前置条件。

## 为什么必须有发布密钥

Android 只允许**签名相同**的 APK 覆盖安装。签名不同时，系统会拒绝安装，
用户只能卸载重装——而卸载会清掉应用私有目录里的书卷、会话和端侧模型。

Android 的 debug 包默认用 `$HOME/.android/debug.keystore` 签名，这把密钥由开发工具
在**每台机器上随机生成**：换一台机器、换一个 CI runner，签名就变了。
所以「在 CI 里直接 `assembleDebug`」会产出与上一版签名不同的包，
用户点「检查更新」下载后**装不上**。

结论：CI 必须用**与 2.1.2 / 2.1.3 相同的那把发布密钥**签名。该密钥不在仓库里
（也不应该在），只能通过 GitHub Actions secret 注入。

当前发布密钥的证书指纹（SHA-256）：

```text
3c5cb56d7221ed35ef03240cda3d718dfade061a9614b92aa62b4f299733ee48
```

工作流会在打包后校验这个指纹，不匹配直接失败，不会发出装不上的包。

## 一次性配置

### 1. 导出发布密钥

在**当年构建 2.1.2 / 2.1.3 的那台机器**上执行：

```bash
ls -l ~/.android/debug.keystore
keytool -list -v -keystore ~/.android/debug.keystore -storepass android -alias androiddebugkey \
  | grep -E 'SHA256:|Alias name:'
```

`SHA256:` 一行必须等于上面的指纹（忽略大小写和冒号）。

- **匹配** → 继续第 2 步。
- **不匹配**（例如输出 `5A:7E:3A:FC:…`）→ 这台机器不是发布机。
  请换到真正发布过 2.1.3 的机器，或在有备份的机器上找这把密钥。
  **找不到就无法覆盖安装**，只能让用户卸载重装（可先用
  `GET /api/web/runs/{run_id}/export` 导出书卷备份）。

导出成 base64（单行，不要换行）：

```bash
base64 -w0 ~/.android/debug.keystore > /tmp/release-keystore.b64
wc -c /tmp/release-keystore.b64
```

### 2. 写入仓库 secret

在仓库 **Settings → Secrets and variables → Actions → New repository secret**
新建：

| 名称 | 值 |
| --- | --- |
| `ANDROID_RELEASE_KEYSTORE_BASE64` | 上一步 `/tmp/release-keystore.b64` 的完整内容 |

注意事项：

- 只粘贴文件内容，不要包 `base64 …` 命令本身，也不要带引号。
- secret 一旦保存就无法再读回，请自行保留密钥原件与离线备份。
- 导出后删除临时文件：`shred -u /tmp/release-keystore.b64`。
- **绝不**把密钥提交进仓库（`.gitignore` 已挡 `*.jks` / `*.keystore` / `keystore.properties`）。

## 发布一个版本

1. **改版本号**：编辑
   [`kmp/app/androidApp/build.gradle.kts`](../kmp/app/androidApp/build.gradle.kts)，
   `versionCode` 必须**严格大于**上一版（覆盖安装要求），`versionName` 与要打的 tag 对应。

   ```kotlin
   versionCode = 17
   versionName = "2.1.4"
   ```

2. **写发布说明**：新增 `.github/release-notes/v<版本>.md`（例如 `v2.1.4.md`），
   Release 正文会直接引用它；文件不存在时只生成一句占位文字。
   同步更新 [`CHANGELOG.md`](../CHANGELOG.md)。

3. **提交并推送**到 `main`，等 `Android CI` 与 `Tests` 变绿。

4. **打 tag 并推送**：

   ```bash
   git tag v2.1.4
   git push origin v2.1.4
   ```

5. 工作流会自动构建、校验、上传附件。完成后在
   [Releases](https://github.com/dz3197578117-lab/zaomeng/releases) 页面确认附件名形如
   `zaomeng-2.1.4-arm64-v8a-debug.apk`。

### 手动重跑

`workflow_dispatch` 支持指定一个**已存在**的 tag 重跑（用于发布失败后重试）：

```bash
gh workflow run release.yml -f tag=v2.1.4
```

## 工作流会拦截什么

发布前会依次校验，任一不通过就中止（不会发出有问题的包）：

1. **发布密钥是否存在**——未配置 `ANDROID_RELEASE_KEYSTORE_BASE64` 直接失败，
   并提示配置路径；这是防止「CI 随机签名」这类事故的第一道闸。
2. **签名指纹**——必须等于 `3c5cb56d…`，否则该包无法覆盖安装。
3. **版本号与 tag 一致**——tag `v2.1.4` 必须配上 `versionName = "2.1.4"`，
   避免发出内容与版本错位的包。
4. **附件名含 `arm64-v8a`**——应用内更新检查依赖它挑选正确的包
   （见 `AppUpdateManager.parseLatestRelease`）。

## 关于 `dist/` 与手工预览包

- `dist/*.apk` 已移出版本控制，Release 附件由 CI 构建。
- 仍想手工验证时，本地 `./gradlew :app:androidApp:assembleDebug` 即可，
  但**只有用发布密钥签名的那份**才能覆盖安装已发布的版本。

## 故障排查

| 现象 | 原因与处理 |
| --- | --- |
| `缺少发布签名密钥` | 仓库未配置 secret，或名称拼错（见上文表格） |
| `签名不匹配` | secret 里的密钥不是发布密钥，核对 `keytool` 指纹 |
| `版本号不匹配` | 忘记改 `versionName`，或 tag 与 gradle 版本不一致 |
| 用户反馈「应用未安装」 | 十有八九是签名不一致；让用户核对已装版本的签名，或确认本次发布确实用了发布密钥 |
| 用户反馈「版本降级」 | `versionCode` 没有递增 |
