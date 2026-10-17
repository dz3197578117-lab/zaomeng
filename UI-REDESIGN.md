# 造梦 UI 现代化改造：「书卷 · 暖墨」

> 本次改造把「Compose 脚手架默认紫粉色」换成一套有题材气质的设计语言，并补齐了工程上完全缺失的设计系统。
> 风格取意：**宣纸为底、墨为字、朱砂为印**。

---

## 一、为什么改

改造前的三个硬伤（已核实，非主观判断）：

| 问题 | 证据 |
| --- | --- |
| 主题是脚手架默认值 | `Color.kt` 只有 `Purple80 / PurpleGrey80 / Pink80` 三组，是 Android Studio 新建 Compose 项目的原样 |
| 字体没有层级 | `Type.kt` 全文 18 行，只定义了 `bodyLarge` 一个样式 |
| 形状与间距各自为政 | 无 `Shapes` 文件，`MaterialTheme` 未传 `shapes`；97 处手写圆角有 10 种取值；1120 个 `.dp` 字面量，`AppDimens` 覆盖率 2.5% |

另一面是资产：颜色纪律其实很好——741 处用 `MaterialTheme.colorScheme`，硬编码颜色仅 13 处，无 Material2 遗留。所以这次是「补设计系统 + 重建层级」，不是推倒重来。

---

## 二、设计系统（本次核心）

### 2.1 配色 `ui/theme/Color.kt`

明暗各一套完整 `ColorScheme`（28 个角色全部显式赋值，不留 baseline 紫）：

| 角色 | 浅色（宣纸） | 深色（夜墨） | 用途 |
| --- | --- | --- | --- |
| primary | `#A03B2E` 朱砂 | `#FFB4A4` | 主操作、进度、选中 |
| onPrimary | `#FFFFFF` | `#5C160D` | |
| primaryContainer | `#FFDAD3` | `#7E2A1F` | 用户气泡、Hero 渐变 |
| secondary | `#5B6B67` 黛青 | `#B9CBC6` | 次级状态、底栏指示器 |
| tertiary | `#7A5B33` 赭石 | `#E4C08D` | 旁白、Beta 标 |
| background / surface | `#FBF7F0` 宣纸 | `#16120E` | 页面底色 |
| onSurface | `#1F1B16` 墨 | `#EAE1D6` | 正文 |
| surfaceContainer 五阶 | `#FFFFFF → #E8E0D3` | `#110D09 → #39332D` | 卡片/列表分层 |
| outline | `#8C8076` | `#9B8F84` | 描边 |

冷启动窗口底色（`values/themes.xml`、`values-night/themes.xml`）同步改为 `#FBF7F0` / `#16120E`，避免启动瞬间闪一下旧底色。

**动态取色优先级保持不变**：用户种子色 > Android 12+ 壁纸取色 > 本套静态色板。

### 2.2 字体 `ui/theme/Type.kt`

从 1 个样式扩到完整 15 档。两条针对中文的调整：

- **标题用衬线**（`FontFamily.Serif`，Android 映射 Noto Serif CJK）——书名、页面标题、统计数字才有「书」的质感；
- **正文行高放到 1.62 倍、`letterSpacing` 归零**——M3 默认的 `16/24 + 0.5sp` 是按拉丁文调的，中文照搬会「散架」。

### 2.3 形状 `ui/theme/Shapes.kt`（新建）

`extraSmall 6 / small 10 / medium 14 / large 20 / extraLarge 28`，并在 `Theme.kt` 里 `shapes = AppShapes` 真正接上（此前从未传过）。

另提供两个具名形状：

- `AppPillShape`：全圆角（胶囊、状态标）；
- `appBubbleShape(isMine)`：对话气泡，三角收成 6dp 形成轻微指向感，不画真三角尾巴。

### 2.4 间距 / 透明度 / 动效

| 文件 | 内容 |
| --- | --- |
| `Dimens.kt` | `AppSpacing`（4dp 栅格 2/4/8/12/16/24/32/48）、`AppElevation`（0/1/3/6）、`AppSizes`（图标/头像/48dp 触达区）；旧 `AppDimens` 字段保留并映射到新档位，23 处调用零改动 |
| `Alpha.kt`（新建） | 5 档：`Disabled .38 / Subtle .55 / Muted .72 / Strong .88 / Full 1`，替代原先 15 种散落取值 |
| `Motion.kt`（并入 Dimens.kt） | `Instant 90 / Fast 150 / Base 220 / Slow 320`，三条标准缓动曲线 |

---

## 三、公共组件库 `ui/components/`（新建）

改造前整个 `ui/components/` 只有 1 个文件。新增：

| 组件 | 作用 |
| --- | --- |
| `AppCard` | 三档层级：`Filled`（分区）/ `Elevated`（需要浮起的卡）/ `Outlined`（密集列表）。改造前 92 处 `Card` 全是基础款，没有层级差异 |
| `AppListItem` | 统一列表行：圆形图标底、48dp 触达区、副标题与右侧值、可选箭头 |
| `AppSectionHeader` | 分组标题（主色小标题 + 说明 + 右侧动作） |
| `AppEmptyState` | 空态/失败态：圆形图标底 + 标题 + 说明 + 动作 |

原则：只吃 `Modifier` 与内容槽，不引入 ViewModel 或业务状态。

---

## 四、页面改造

### 4.1 书卷架（首屏）`feature/bookshelf/BookshelfScreen.kt`

- `TopAppBar` → **`LargeTopAppBar` + 折叠行为**：首屏是大标题的仪式感，向下滚动时收成普通高度，把空间还给内容；
- 书卷卡改为 **书脊色块**：按标题哈希从 5 组配色里稳定取色，同一本书永远同色——书架从「一排一样的卡片」变成可以靠颜色扫读；
- 空书架从「一张卡片里堆三行字」换成 `AppEmptyState`（圆形图标底 + 居中排版）；
- 状态胶囊统一 `AppPillShape`，间距走 token。

### 4.2 手机端底部导航 `navigation/AppBottomBar.kt`（新建）

改造前手机端**没有底部导航**：书卷架、会话、卡库三个入口挤在 TopAppBar 的三个图标里，跨书卷与在线书库藏在二级页。

现在窄屏固定底部 5 项（书卷架 / 会话 / 卡库 / 跨书卷 / 设置），宽屏继续用 `NavigationRail`，两者共用 `AppTopLevelRail.kt` 里的同一份 `topLevelRailItems`，不会出现两处入口不一致。

只在**顶级目的地**显示底栏；进入书卷详情、对话、设置子页后自动隐藏，把垂直空间还给内容。TopAppBar 上原来的三个图标随之移除（`showTopBarActions = false`），在线书库仍可从「导入小说 → 在线书库」进入。

### 4.3 对话 `feature/chat/transcript/TranscriptBubble.kt`

- 气泡形状改用 `appBubbleShape`（20dp + 6dp 指向角），旁白气泡改用 `shapes.small`；
- 角色气泡加一道 `outlineVariant` 极淡描边——宣纸底上 `surfaceContainerHigh` 对比偏弱，描边把边界「托」出来；
- 透明度收敛到 `AppAlpha`。

### 4.4 书卷详情 `feature/rundetail/RunDetailScreen.kt`

- Hero 卡从「整块 primaryContainer 大色卡」改为 **三色线性渐变**（主色容器 → 纸面），右下方自然过渡到列表底色，避免大色块压迫；
- 圆角统一到 `shapes.extraLarge`；统计数字从 `titleSmall` 提到 `titleMedium`，三个指标终于读得清。

### 4.5 设置模块 `feature/settings/SettingsComponents.kt`

只改了一个函数——`SettingsRow` 内部委托给 `AppListItem`。设置模块 8200 行里所有调用点**零改动**即获得：圆形图标底、token 间距、48dp 触达区、统一箭头与次要文字色。

### 4.6 会话列表 `feature/sessions/SessionsScreen.kt`

- 会话卡改为 `AppCard(Outlined)`：描边分隔替代阴影，长列表滚动时不再有「一堆浮起的白块」；
- 多选态用 `containerColor = secondaryContainer` 覆盖（`AppCard` 为此新增了可选容器色参数），选中反馈保持原语义；
- 删除中禁用点击的行为原样保留（`onClick = null`）。

### 4.7 导入页 `feature/importbook/ImportBookScreen.kt`

- 三个导入来源卡改用 `AppCard(Elevated)`，与书卷卡的层级语言一致；
- 按钮圆角接入 `shapes.small`，间距走 token。

### 4.8 `@Preview` 补齐 `ui/components/ComponentPreviews.kt`（新建）

改造前全库 `@Preview` 为 **0**。新增 3 个预览：色板（主色/容器/surfaceContainer 三阶）、组件库浅色、组件库深色。此后调 token 先看预览，不必每次都装 APK。

---

## 五、验证

```bash
export JAVA_HOME=/opt/jdk21
export ANDROID_HOME=/opt/android-sdk
cd kmp
/opt/gradle-9.6.1/bin/gradle :app:androidApp:compileDebugKotlin
/opt/gradle-9.6.1/bin/gradle :app:androidApp:assembleDebug
/opt/gradle-9.6.1/bin/gradle :app:androidApp:testDebugUnitTest
```

| 项 | 结果 |
| --- | --- |
| `compileDebugKotlin` | ✅ 通过（每轮改动后均单独验证） |
| `assembleDebug` | ✅ 通过，产出 APK |
| `testDebugUnitTest` | ✅ **589 个用例，0 失败 / 0 错误 / 0 跳过** |
| 新增编译告警 | 0（仅原有 3 条 `Elvis operator` / `!!` 警告） |

产物：`build-artifacts/zaomeng-ui-debug.apk`

> ⚠️ 该 APK 是 **debug 签名**，可直接安装体验；不要用于覆盖你手机上正式版的数据目录（包名相同，debug 与 release 可共存但会争用同一份数据，首次安装建议先导出备份）。

---

## 六、本次尚未覆盖

按「先让全局设计系统生效，再逐屏精修」的顺序，以下仍是原样，但**已被新主题自动覆盖配色与字体**：

- 角色、关系、时间线、章节、卡库、插件、跨书卷等约 18 个页面的**布局重写**（它们已自动获得新配色与字阶，但骨架仍是旧的）；
- 987 处 `.dp` 字面量、剩余手写圆角、25 处 alpha 的逐屏收敛；
- `@Preview` 目前只覆盖「色板 + 组件库」，尚未覆盖具体页面；
- 手机端底栏暂未处理「再次点击当前 tab 回到该 tab 根页」的滚动复位。

## 七、回滚

```bash
cd kmp/app/androidApp/src/main/kotlin/top/wkbin/zaomeng
git checkout -- ui/theme ui/components
git checkout -- feature/bookshelf/BookshelfScreen.kt
git checkout -- feature/rundetail/RunDetailScreen.kt
git checkout -- feature/chat/transcript/TranscriptBubble.kt
git checkout -- feature/settings/SettingsComponents.kt
git checkout -- navigation
```
