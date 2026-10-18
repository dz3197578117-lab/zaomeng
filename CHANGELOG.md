# 变更记录

本文件记录造梦（本仓库改版）的逐版本变更。版本号唯一来源是
[`kmp/gradle.properties`](kmp/gradle.properties) 的 `zaomeng.versionName`。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。
每个 tag 对应的 GitHub Release 正文取自 [`.github/release-notes/`](.github/release-notes/)。

## [2.11.0] - 2026-10-19

### 新增：人机协作写作链路（核心 → 扩写 → 重写 → 设定库 → 终审）

写作助手扩展为五个页签：设定 / 类型 / 扩写 / 救援 / 检查。

- **人定核心**：新增 `story_core.json` 与 `prompts/chapters/story_core.yaml`，
  记录主题、人物欲望（want 与 obstacle 分离）、关键转折、结局；生成后先预览，确认才保存，
  保存后自动注入后续大纲、正文与检查。
- **场景卡**：新增 `scene_cards` 章节字段与 `prompts/chapters/scene_cards.yaml`，
  把一章拆成 2~5 场戏（地点/时间/人物/目标/阻力/变化/离场）。
- **多版本初稿**：`prompts/chapters/chapter_versions.yaml` 按场景卡写两版差异明显的初稿，
  不落盘；用户展开对比后采用一版，覆盖前二次确认。
- **局部重写**：`prompts/chapters/rewrite_selection.yaml` 对一段正文给三个改写版本
  （克制 / 有性格 / 有画面），不改剧情与视角。
- **设定库扩展**：设定集新增 `timeline`（时间线）与 `foreshadowings`（伏笔表，含埋设、回收与状态）。
- **全书一致性**：`prompts/chapters/consistency_check.yaml` 跨章检查时间线、人物、伏笔与设定，
  每条问题标注涉及章节。
- **人工终审**：`prompts/chapters/final_review.yaml` 输出逻辑、情感、敏感内容、原创性四组问题，
  只给建议；「可以发布」由人勾选，且每次打开重置。
- 新增 `WritingToolParser` 对上述结构化输出的容错解析，并新增 6 条单测。

### 验证

`testDebugUnitTest`（632 用例）、`lintDebug`（0 error）、`assembleDebug` 全部通过。

## [2.10.0] - 2026-10-19

### 新增：写作助手（设定集 / 类型模版 / 卡文救援 / 润色检查）

章节工作台顶部新增「写作助手」，四块能力共用同一个弹层：

- **设定集**：生成世界观、人物小传、势力关系、冲突设计；先预览、确认后才保存，
  保存后自动注入后续大纲与正文。新增 `story_bible.json` 与 `prompts/chapters/story_bible.yaml`。
- **类型模版**：网文日更 / 短篇 / 同人 / 儿童故事 / 剧本杀 / 营销故事六种体裁，
  每种带结构节奏与目标章长；保存到 `writing_profile.json`，生成时注入。
- **卡文救援**：按情节 / 对话 / 场景 / 反转四类卡点各给三个方案，带可复制片段，不自动改稿。
- **润色与检查**：检查语病、语气、前后矛盾、设定冲突，生成章节摘要；确认后按建议改写并保存。

新增 `WritingToolParser` 容错解析（代码块、前后说明、裸数组、字符串内大括号），
并新增 8 条单测。

### 修复：未蒸馏的书卷不再被挡在创作之外

- 创作页列出所有书卷（跨书卷共演除外），`ready` 优先；蒸馏中 / 失败 / 停止的书卷标注状态后仍可进入；
- 新增「新建原创书卷」：只填书名与主角，不导入原文、不蒸馏，建好直接写；
- `RunManifestDto` 增加 `origin`，原创书卷建卷即 `ready`，书架与详情页显示「原创」，
  「推荐下一步」直达章节工作台；
- 生成大纲与正文时，无人物档案会回退使用创建时记录的人物名单；
- 修复书架与书卷详情对 `pending` 状态显示英文原文的问题。

### 验证

`testDebugUnitTest`（626 用例）、`lintDebug`（0 error）、`assembleDebug` 全部通过。

## [2.9.0] - 2026-10-19

### 导航：底栏「会话」替换为「创作」

会话有两个入口（书卷架顶栏、书卷详情），却占着底栏一个位置；而 AI 小说创作发布后
只能从章节工作台右上角进入，等于没有入口。这一版交换了两者。

- 底栏与宽屏侧栏统一为：**书卷架 / 创作 / 卡库 / 跨书卷 / 设置**；
- 新增顶级「创作」页：列出 `ready` 状态的书卷，显示封面色块、人物数、更新时间与主角身份；
- 点书卷直接进入章节工作台，并**自动展开 AI 创作弹层**，少一步找按钮；
- 没有可写书卷时给「去书卷架」，读取失败给「重试」；
- 会话降为内容页：`SessionsDestination` 不再判定为顶级，底栏不会出现在会话列表上。

只列 `ready` 是刻意的：蒸馏中/失败的书卷没有人物档案，生成出来也只会是空大纲。

### 验证

`compileDebugKotlin`、`testDebugUnitTest`（610 用例）、`assembleDebug` 全部通过。

## [2.8.0] - 2026-10-18

### 新增：AI 小说创作（设定 → 分章大纲 → 逐章成稿）

此前章节只有两条来路：把聊天会话改写成小说，或进入一个续写会话继续对话。
两者都依赖已有内容，无法「从一个想法开始写」。这一版补上这条链路。

**流程**

1. 章节工作台右上角进入「AI 小说创作」；
2. 填写故事设定、类型、叙事视角、章节数与文风；
3. 模型产出分章大纲（标题 + 每章目标），**先预览、不落盘**；
4. 确认后逐章创建空白章节，保留已有章节；
5. 在章节卡上点「AI 写这一章」逐章生成正文；已有正文时显示「AI 重写」，覆盖前二次确认。

**实现**

- 新增 `NovelOutlineParser`：模型输出常带 ```json 代码块、前后说明、对象包裹或截断后的编号列表，
  解析器按三层降级处理；三层都失败时明确报错，不会把垃圾大纲写进书卷。
- `ChapterManagementService.generateOutline`：只调用模型、不写盘，避免「生成不满意」留下垃圾章节。
- `ChapterManagementService.draftChapter`：上下文包含全书大纲、前两章的摘要与结尾（各 1000 字）、
  人物简报（身份/角色/说话风格）与本章目标；正文上限 3500 字，并写入 `context_summary` 供后续章节使用。
- 章节新增 `perspective` 字段：大纲创建时写入，成稿时沿用，保证同一本书视角一致。

### 验证

新增 9 条解析用例；`compileDebugKotlin`、`testDebugUnitTest`（610 用例）、`assembleDebug` 全部通过。

## [2.7.0] - 2026-10-18

### 界面：全部页面接入统一组件库

2.6.1 只完成了五个高频页，这一版把剩下的页面全部收尾。**用户可见的每一张卡片现在都来自同一个
`AppCard`**，不再存在「有的页 12dp 圆角、有的页 16dp，颜色也不一样」的情况。

覆盖范围：

- **跨书卷**：Beta 提示、可选人物区、创建按钮统一层级；顶栏补「独立快照，不写回原书卷」。
- **在线书卷包**：错误提示、书卷卡、空列表统一；顶栏显示可下载数量。
- **检查更新**：分组容器改用无内边距的 `AppCard`，保持分组行分隔线贴合边缘；顶栏显示当前版本。
- **原文证据中心**：搜索卡、提示条、证据卡统一；空态改为带「换关键词 / 重建索引」指引。
- **继续蒸馏**：换书段、采样预估、推荐区、片段选择卡统一；顶栏说明「补充而非覆盖」。
- **剧情复盘**：头部、各分区、下一拍、小说转换卡统一；顶栏显示事件/幕/伏笔统计。
- **插件工坊**：AI 草稿、介绍、分区、内层规则/设置、状态提示统一；顶栏显示「描述 → 生成 → 校验」。
- **导入、会话列表、书卷详情、书架、人物档案、设置全部子页**：卡片、空态、间距同步。
- **聊天工具与搜索**：分区卡与结果卡改用组件。

### 组件能力

`AppCard` 新增 `contentColor` 参数。语义色容器（错误 / 次要 / 强调）必须显式传
`onXxxContainer`，否则正文会落回 `onSurface`，在色块上对比不足——这是全量替换时最容易出现、
又最不容易在编译期暴露的问题。

### 一致性

- 页面内边距统一 16，卡片间距统一 12；
- 空状态统一 `AppEmptyState`（图标 + 标题 + 说明 + 可选操作）；
- 顶栏统一为「标题 + 一行副标题」，副标题只放当前页最有信息量的一项数据。

### 验证

`compileDebugKotlin`、`testDebugUnitTest`（601 用例）、`assembleDebug`、`lintDebug`（0 errors）
全部通过；全仓库残留基础 `Card` 仅 2 处，位于 `AppCard` 自身实现。

## [2.6.1] - 2026-10-18

### 界面：五个高频页统一到同一套卡片与间距语言

这一版没有新功能，只把上次没来得及重排的页面补齐。全部改用 `AppCard` 的三档层级
（填充 / 浮起 / 描边）与 `AppSpacing` 栅格，不再各页各写一套内边距。

- **人物关系**：顶栏加「N 位人物 · M 组关系」；关系卡加首字圆牌，一眼看出「谁和谁」；
  新增「关系明细」分组头；图谱卡与编辑卡分层；读取失败与空态都带重试。
- **创作资料库**：卡片加类型色条（场景 / 自我 / 开场）；空态改为带「新建一张」的完整空状态；
  提示条统一容器，不再用裸 `Card`。
- **故事时间线**：时间提示从元数据行提出来做成标签；一致性警告保留语义色；
  锁定事实用主色容器；加载态补上「正在读取故事记忆…」。
- **章节工作台**：顶栏加「N 章 · 约 X 字」；章号做成独立小牌，标题不再被「第 N 章 ·」挤成一行；
  章节卡改描边卡；空态给「新建章节 / 转为小说」双操作。
- **人物档案**：字段分组加完成度进度条，折叠状态也能看出还差多少；展开态用反色，避免低对比。

### 一致性

- 五个页面的列表内边距统一为 16 / 16 / 16 / 32（`AppSpacing`），卡片间距统一为 12。
- 空状态统一收敛到 `AppEmptyState`，不再各页手写「图标 + 两行字 + 按钮」。

## [2.6.0] - 2026-10-18

### 新增：音色库——为每个角色挑引擎音色

上一版只能「选中 NekoSpeak」，但真正要给它分配音色时，用户得自己知道音色标识
（`zh-CN-yunxi`、`neko:clone-xxx`）并手填进人物包的 `voice_name`。这一版把它做成界面动作：

- 人物页「对白声音」新增**音色库**入口；
- 弹出层里列出当前引擎通过标准 `TextToSpeech.voices` 报出的**全部音色**，中文优先排序，可按名称/语言搜索；
- 每个音色都能**用该角色自己的代表句试听**，点「用这个」直接写回 `voice_name`，不需要知道标识长什么样；
- 空列表区分「引擎报错」与「引擎没有音色」两种情况；NekoSpeak 场景直接提示「先到它自己的界面下载音色」。

试听与聊天朗读共用同一条链路（同一份 `voice_name`、同一套音高/语速/情绪推导），
所以不会出现「试听满意、进聊天变了个声音」。

### 修复：音色匹配可能命中前缀相同的另一个音色

`PlatformTts` 原来用 `name.contains(voiceName)` 匹配，`zh-CN-xiaobei` 会命中 `zh-CN-xiaobei-v2`，
表现为「选了 A 听起来像 B」。现在按「精确 → 忽略大小写精确 → 包含」的顺序匹配；
完全没匹配上时写一行日志（含引擎音色总数），不再静默使用默认声音。

### 测试

新增 6 个纯逻辑用例：中文优先排序、名称/语言检索、展示名去引擎前缀、简繁语言标签、需联网标记。

## [2.5.0] - 2026-10-18

### 修复：连崩三次，诊断包里却一条堆栈都没有

真机（realme RMX5010 / Android 17）覆盖安装 2.4.0 后，`01:00:12`、`01:00:40`、`01:00:43`
连续三次 `CRASH（Java 崩溃）`，第四次启动才成功。但导出的诊断包里
`crashEntries=0`、`crashBytes=0`，全文一条 `FATAL EXCEPTION` 都没有——**现场完全丢失**。

两个原因，都已修：

1. **捕获链装得太晚**。完整的 `installCrashHandler()` 在 `Application.onCreate`，
   而那几次崩溃发生在更早的阶段（Application 类初始化 / `attachBaseContext`）。
   新增 `EarlyCrashRecorder`，在 `attachBaseContext` 里装一个**只依赖 `filesDir`** 的
   最小记录器（不碰 Koin、协程、日志框架），写到同一个 `crashes/crash-*.log` 目录，
   格式与 `parseCrash` 对齐，诊断导出能直接读到。完整链装好后它会自动让位，不会重复记录。
2. **写盘失败被静默吞掉**。`writeUncaught` 原来用 `runCatching { … }` 包住写盘，
   失败时什么痕迹都不留。现在失败会往运行日志写一行
   `崩溃记录写盘失败：<异常>`，至少能知道「为什么没有堆栈」。

⚠️ 这次修的是**下次能抓到**，崩溃根因仍未定位——它没有留下任何证据。

### 修复：分支会话的 `transcript_start` 残留父会话偏移

自检探针 `chat.advanced_actions` 报了唯一一条 FAIL：
`sess-7bc1ce5174d0` 的分支会话 `transcript_start=101`（应为 0）。
分支保留的是父会话的**完整前缀**，沿用父会话的归档偏移会在读取时把父会话的归档拼到前缀前面，
拼出错误的全量历史。

写入侧从 2.2.2 起已经归零，但**升级前产生的分支会话仍残留旧值**。
新增 `TranscriptBookkeeping.effectiveTranscriptStart()`：只要 manifest 带分支标记
（`branch_origin` 或 `branch_meta.is_mainline=false`）就按 0 读取。这是个**纯内存归一**，
不改写磁盘——旧版本还会读这些文件，悄悄改写会让两个版本行为不一致。
`DomainStore`、`SessionResponseUtils`（两处）、`ChatProbes`（两处）统一走这个入口，补 5 个回归用例。

### 语音：新增 Kokoro 高音质模型（8 个精调中文音色）

原有的三个 VITS 模型都是 2022–2023 年的社区模型，804 个音色里质量并不均匀
（代码注释里自己写过「有清晰好听的，也有发闷、含糊、带噪声的」），而角色音色是按名字哈希
**随机**落进去的——落到一个含糊音色上就无从更换。

新增 `kokoro-multi-lang-v1_1` 作为第四个模型：24 kHz、中英混读，103 个音色里
**45–52 是中文**（`zf_xiaobei` / `zf_xiaoni` / `zf_xiaoxiao` / `zf_xiaoyi` /
`zm_yunjian` / `zm_yunxi` / `zm_yunxia` / `zm_yunyang`，4 女 4 男），每个都经过精调。
代价是约 403 MB，且合成速度约为 VITS 的一半。

配套改动：
- 引擎层按 `NeuralEngineKind` 分派 VITS / Kokoro 两套 `OfflineTtsModelConfig`；
- 下载器支持**相对子路径**（`espeak-ng-data/phontab`、`dict/jieba.dict.utf8`）与递归完整性检查；
- 音色分配加白名单：Kokoro 只会在 45–52 里挑，否则会拿到英文音色、读中文变成乱码音；
- `espeak-ng-data` 只取中文/英文音素化必需的 11 个文件（官方整目录 355 个文件 / 18 MB，
  其中 90% 是其它语言的词典）。

### 语音：可选系统朗读引擎（NekoSpeak）

造梦原先只会绑定系统默认 TTS 引擎。现在「设置 → 朗读引擎与音色」顶部可以**指定引擎**：
枚举 `android.intent.action.TTS_SERVICE` 列出设备上已安装的引擎，选中后用
`TextToSpeech(context, listener, packageName)` 绑定。

这样 NekoSpeak（`com.nekospeak.tts`，支持 Pocket-TTS 零样本克隆、名人音色、100+ Piper 社区音色）
就能作为第二个方案接入——它走标准 TTS API，因此造梦既有的「按角色分配 voice」逻辑
（`voice_name` → `engine.voices` 匹配 → `KEY_PARAM_VOICE_NAME`）直接作用在它的音色上，
不需要复刻克隆能力。

三种方案并存：系统 TTS（跟随系统）/ 系统 TTS + 指定引擎 / 内置神经 TTS。

### 性能：首屏 285ms 主线程阻塞

真机诊断里唯一一条 `mainThreadBlocks`：

```
主线程阻塞约 285ms：
  at androidx.compose.material3.TopAppBarState.<clinit>(AppBar.kt:2213)
  at …rememberTopAppBarState
  at …BookshelfScreenKt.BookshelfScreen(BookshelfScreen.kt:111)
```

是 2.4.0 给书卷架加的大标题折叠引入的类首次初始化。改为在 `MainActivity.onCreate` 里
起后台线程预热，主线程不等它，冷启动不变慢。

## [2.4.0] - 2026-10-18

### 界面：换掉脚手架默认配色，补齐整条设计系统

改造前的主题是 Compose 脚手架原样：`Purple80 / PurpleGrey80 / Pink80`；`Type.kt` 全文只定义了
`bodyLarge` 一个样式；没有 `Shapes` 文件，`MaterialTheme` 从未传过 `shapes`；97 处手写圆角有
10 种取值，1120 个 `.dp` 字面量里只有 2.5% 走 token。

本版按「书卷 · 暖墨」重建：宣纸暖白打底、朱砂作主色、黛青与赭石作二三级；明暗两套
`ColorScheme` 的 28 个角色全部显式赋值，不再残留 baseline 紫。字阶从 1 档扩到 15 档，
标题走衬线；中文行高放到 1.62 倍、`letterSpacing` 归零——M3 默认的 `16/24 + 0.5sp`
是按拉丁文调的，中文照搬会「散架」。

新增 `Shapes`（6/10/14/20/28）、`Alpha`（5 档）、`Motion`（3 档时长 + 3 条缓动）三组 token，
并在 `Theme.kt` 里真正接入 `shapes`。冷启动窗口底色同步改为新背景，避免启动瞬间闪一下旧底色。

### 界面：手机端终于有底部导航

此前窄屏**没有** `NavigationBar`：书卷架、会话、卡库三个入口挤在 TopAppBar 的三个图标里，
跨书卷与在线书库藏在二级页。现在底部固定 5 项（书卷架 / 会话 / 卡库 / 跨书卷 / 设置），
宽屏继续用 `NavigationRail`，两处共用同一份 `topLevelRailItems`，不会出现入口不一致。

底栏只在顶级目的地显示；进入书卷详情、对话、设置子页后自动隐藏，把垂直空间还给内容。
在线书库仍可从「导入小说 → 在线书库」进入。

### 界面：书卷架、详情、对话、会话、导入

- **书卷架**：`LargeTopAppBar` 随滚动折叠；书卷卡加「书脊色块」，按标题哈希稳定取色，
  同一本书永远同色，书架从「一排一样的卡片」变成可以靠颜色扫读；空态改为居中的 `AppEmptyState`。
- **书卷详情**：Hero 从整块主色大卡改为三色渐变（主色容器 → 纸面），右下方自然过渡到列表底色；
  统计数字提升字阶。圆角统一到 `shapes.extraLarge`。
- **对话**：气泡改为 20dp 圆角 + 6dp 指向角，不再画真三角尾巴；角色气泡加一道极淡描边
  （宣纸底上 `surfaceContainerHigh` 对比偏弱，描边把边界「托」出来）。
- **会话列表 / 导入页**：改用 `AppCard`，描边替代阴影，长列表滚动不再是一堆浮起的白块。
- **设置模块**：`SettingsRow` 内部委托 `AppListItem`，8200 行里所有调用点**零改动**即获得
  圆形图标底、token 间距与 48dp 触达区。

### 工程：新增公共组件库与预览

新增 `AppCard`（三档层级）/ `AppListItem` / `AppSectionHeader` / `AppEmptyState` 四个公共组件，
全部只吃 `Modifier` 与内容槽，不引入 ViewModel 或业务状态。补 3 个 `@Preview`
（色板 + 组件库明暗）——此前全库预览数为 0，任何视觉调整都只能靠真机截图验证。

### 说明

本次是**纯视觉层改动**：没有触碰业务逻辑、数据流与提示词，589 个单元测试全绿
（`testDebugUnitTest` 0 失败 0 错误）。仍约有 18 个页面的布局骨架未重写，
但它们已自动获得新配色与字阶，后续可按同一套 token 渐进。

## [2.3.3] - 2026-10-17

### 修复：「开始下载」返回的状态会撒谎（竞态），CI 上真实偶发

`startDownload` 的契约是「立刻返回，且返回体的 `download.active=true`」，进度靠轮询 `status()`。
但它原来在返回前**重新读了一次 `stateFlow`**：下载跑在另一调度器上，小文件（测试里的 16 字节、
或很快的本地网络）可能在 `scope.launch` 与 `return` 之间就**已经跑完**，`stateFlow` 被改成
`active=false`，于是返回体自称「没开始」。

2.3.2 的 CI 正是挂在这里：`VoiceModelDownloadServiceTest > download writes every file and reports
completion`，`expected:<true> but was:<false>`；同一份代码在较慢的机器上永远看不到——
是**固有竞态**，不是随机抖动。

现在返回**「已开始」那一刻的快照**（`status(modelId).copy(download = started)`），
与下载多快无关；真实进度仍由轮询 `status()` 提供（设置页本来就无条件开始轮询，
所以用户可见行为不变，只是不再出现自相矛盾的返回体）。
新增回归用例 `startDownload always reports the download as started`，重复 5 轮钉住这条契约，
并同时断言轮询后必须如实看到「已结束」——起始快照不能被当成真实进度。

### CI：失败时要能看到是哪个测试挂了

`android.yml` 原来失败只 grep 到任务级 `testDebugUnitTest FAILED`，拿不到具体测试名，
只能重跑或靠猜（2.3.2 的这次失败就卡在这里）。现在额外打印 JUnit 逐条 `FAILED` 行与
`tests completed` 汇总行，并把 `test-results` / `tests` 报告与 lint 报告作为 artifact 保留
（`if: failure()`），定位不必再猜。

## [2.3.2] - 2026-10-17

### 修复：试听与对话音色不一致、「所有角色一个声音」的两个真 bug，以及「读音含糊」的成因可见化

现象：用户反馈**所有角色听起来是同一个声音**、并且**读音含糊不清**。

先纠正一个容易误判的方向：从用户导出的诊断日志可以确认，神经引擎当时**正在工作**——

```
PlatformTts: 使用离线神经语音引擎：vits-zh-hf-eula
SherpaNeuralTts: 神经语音引擎已加载：vits-zh-hf-eula，音色 804 个，采样率 22050 Hz
SherpaNeuralTts: 朗读：说话人=「陈香兰」音色#624/804 语速=1.13 字符数=20 候选角色=0
```

引擎加载正常、7 个模型文件齐全（否则会降级到系统朗读），音频参数也正确
（22050 Hz / 单声道 / 16-bit）。所以既不是「开关没开」，也不是「FST 缺失」。
关键线索是 `候选角色=0`。

**根因一：人设页试听从来没有把同场角色名单交给引擎。**
`setCast` 在全仓库只有一个调用点（聊天页 `Transcript.kt`），人设页
`PersonaScreen` 复用 `rememberPlatformTts` 却**从未调用它**。于是试听走的是
`cast.ifEmpty { listOf(speaker) }` 的兜底分支：只按单个名字算一个编号——
既失去「同场不撞车」的保证，算出的编号还可能和聊天页里该角色的**不是同一个**
（聊天页用 `session.characterAvatars` 的全量名单），表现为「试听一个声、进对话又变一个」。
现在人设页新增 `refreshCast()` 取全卷名单（`getPersonaQualityOverview`）并
`setCast`，与聊天页同源；名单取不到时**不报错**（只影响「是否撞车」，
仍应出声），且当前角色始终入选。

**根因二：人物包的 `voice_name` 在神经引擎上是死参数。**
`SherpaNeuralTts.speak()` 声明了 `voiceName` 却从未读取——作者/用户为此去改
「声线音色」时**改了没用**，而这正是唯一能手动换音色的入口。
新增 `explicitSpeakerId(voiceName, speakerCount)`：填数字即锁定该音色（`0`–`803`），
优先级高于名单分配。解析刻意保守——`低沉沙哑`、`12 号偏快`、`1.2`、越界值一律不猜、
回落自动值，避免把系统 TTS 的自定义音色名误当编号。

### 「读音含糊」：说明成因，并让下载截断可见

804 音色的多说话人模型里音色质量并不均匀；按名字派生的编号稳定，但**稳定地落在哪一个纯属运气**，
而此前没有任何手段能换——这才是「含糊」时用户无从自救的原因。现在可用 `voice_name` 换。

另一类真实成因是**文件名对、内容被截断**（下载中断 / 存储写满 / 上游重发）：
以前只看「文件在不在」，照样当成就绪。现在服务端回报每个文件的实际字节数：

- `VoiceModelStatusDto` 新增 `present_file_sizes`（服务端如实上报，判定仍在客户端，
  与既有「服务端不判 ready」的约定一致）；
- 新增纯函数 `NeuralVoiceModel.mismatchedFileNames(actualSizes)`：清单 `sizeBytes <= 0`
  或实际大小未知（`-1`）时**不判为不匹配**，避免误报；
- 设置页对所选模型显示「文件大小与预期不一致：… 建议点『重新下载』后再试听」。
  重新下载天然生效：服务端只在「已存在且大小一致」时跳过，大小不符会被重下。

缺文件的原因也不再只说「缺 N 个」，而是点名哪一类：缺必需文件
（`.onnx` / `lexicon.txt` / `tokens.txt`，引擎根本起不来、完全没声音）与
缺文本正则化规则（`.fst`，能出声但**数字、日期与多音字读错**）。
同一段文案同时用于设置页提示与引擎加载失败信息，避免两处措辞漂移。

### 可诊断性

每次朗读的日志新增 `音色来源=`：`explicit(voice_name=N)` / `cast` / `auto`。
「是不是同一个声音」不必再猜——对比两行的 `音色#N` 即可定位是名单缺失、
编号撞车，还是模型音色本身相近。

### 回归守卫

- `NeuralVoiceCatalogTest`：`explicitSpeakerId` 的 11 个边界（含描述文本、越界、负数、小数）。
- `NeuralSpeechPathsTest`：缺单个 FST 必须判为不完整；截断的 `lexicon.txt` 必须被识别；
  未知大小不得误报。
- `SpeechEngineSelectionTest`：缺必需文件与缺 FST 的降级原因分别断言（点名文件、含「多音字」）。
- `VoiceModelDownloadServiceTest`：`present_file_sizes` 如实回报。
- `VoiceModelUiStateTest`：截断告警文案可操作（含文件名与「重新下载」）。
- `PersonaVoiceCastTest`：名单归一化 + 当前角色兜底入选。

## [2.3.1] - 2026-10-17

### 修复：点朗读 / 点试听**立刻闪退**——2.1.19 起的根因，这次找到并修掉了

现象：下载好语音模型、打开「离线神经朗读」后，点人设页的「声线实时试听」（或聊天里的朗读）
**瞬间闪退**。

根因不在朗读逻辑，而在**一行构造参数**。模型是按需下载到**文件系统**的
（`runtimeDir/tts/<id>/`），而构造 sherpa-onnx 的 `OfflineTts` 时传了
`assetManager = context.assets`。于是引擎走 `newFromAsset` → `ReadFile(AAssetManager*, path)`，
而它对**绝对路径**的 `AAssetManager_open` 必然失败，紧接着执行 `SHERPA_ONNX_EXIT(-1)`
——那是 native 层的 `exit()`，**Java 侧连异常都收不到**，`runCatching` 一律无效，
所以只能表现为闪退。sherpa-onnx 自己也会打印
`You are using an absolute path ... but assetManager is NOT set to null`，
并指向 [k2-fsa/sherpa-onnx#2562](https://github.com/k2-fsa/sherpa-onnx/issues/2562)。

传 `null` 才走 `newFromFile`：它用 `std::ifstream` 读绝对路径，外层还有 `SafeJNI` 捕获 C++ 异常，
加载失败只返回 0 → Kotlin 抛异常 → 被现有 `runCatching` 收住 → **优雅降级到系统朗读**，而不是闪退。

这条因果链解释了整段历史：2.1.19 引入时就是这个写法 → 一直闪退 → 2.1.20 去修了合成串行化与
`AudioTrack` 释放（都是真问题，但**不对症**）→ 2.1.21 只好整体回退 → 2.3.0 移植时又被原样搬了回来。

### 顺带加固（其余几处同类原生崩溃入口）

- **释放原生引擎不再与正在进行的合成并发**：`shutdown()` 原来直接 `engine.release()`，
  而 `stop()` 取消不了已进入 JNI 的 `generate()`；现在释放排在**同一把播放锁**之后，
  并放在进程级 IO 作用域上等锁（不能阻塞 `onDispose` 所在的主线程）。
- **页面销毁后不再加载引擎**：加载要几秒，用户可能中途返回；现在加载前后各判一次，
  已销毁就不加载，也不留下刚加载出来的几百 MB 原生内存。
- **系统 TTS 路径也加了兜底**：`engine.voice = targetVoice` 在音色不属于当前引擎时抛
  `IllegalArgumentException`，而它可能发生在 `OnInitListener` 回调里——主线程未捕获异常同样闪退。
- **只持有 application context**：这个实例常驻几百 MB 原生引擎，拿 Activity context 会把 Activity 一起钉住。
- **文件大小与清单不符时只记日志**：上游重发文件时硬校验会让所有用户静默降级，
  而真正的坏文件现在也不会再崩（`newFromFile` 的 `SafeJNI` 兜住了），所以留给日志更有用。

### 回归守卫

新增 `SherpaNeuralEngineLoadingTest`：读源码断言构造引擎时**没有** `assetManager`
（先剥离注释再断言，避免把解释性注释当成违规）。刻意做成源码级——构造是 native 调用，
JVM 单测跑不到，而写错一次的代价是「再闪退一次、再回退一版」。已双向验证：
对该错误写法会失败，对正确写法通过。

## [2.3.0] - 2026-10-17

### 新增：多角色语音（重新移植 2.1.19 / 2.1.20 的语音特性）

2.1.19 / 2.1.20 引入过「每个角色不同的声音」与「离线神经朗读」，2.1.21 因真机问题
（试听闪退、所有人一个声音）**整体回退**；随后 2.2.0 把 33 个模块合并成单模块，
那批代码没有跟着搬过来，于是**设置里连入口都没有**。本版把它移植回来。

移植不是 `git revert`：旧实现依赖已经消失的多模块结构与内嵌 HTTP 层，需要逐层改写：

| 层 | 旧形态 | 新形态 |
| --- | --- | --- |
| 声线档案 / 情绪 / 引擎选择 | `ui:shared` 的 `commonMain` + `expect/actual` | 单模块 `platform/`，去掉 `expect`（模块已无 jvm 目标） |
| 神经引擎 | `androidMain` + 构建期从 `ui:shared` 下载 AAR | `platform/`，AAR 由 `:app:androidApp` 的 `downloadSherpaOnnx` 下载并校验 sha256 |
| 语音模型下载 | `ktor/services` + **4 条 HTTP 路由** | 只留服务，路由删除——客户端经 `ModelSettingsRepository` **进程内直连**（2.2.0 已去掉内嵌 HTTP 层） |
| 设置页 | `feature:settings` 模块 | 并入 `:app:androidApp` 的 `feature/settings` |
| 偏好 | `AppPreferencesRepository`（data:remote） | 同一份 `chatDisplay` 偏好，字段原样恢复 |

**保留 2.1.20 的两处原生崩溃修复**（它们是 2.1.19/2.1.20 回退的直接原因之一）：

- sherpa-onnx 的 `OfflineTts` **不是线程安全的**，而 `stop()` 只能取消协程、打断不了已进入 JNI
  的 `generate()`——连点两次会让两次原生调用撞在同一个对象上（原生崩溃，Java 侧 `try/catch`
  兜不住）。现在**合成与播放整段串行化**（互斥锁覆盖「加载引擎 → 合成 → 播放」）。
- `stop()` 不再直接释放正在被写入的 `AudioTrack`，**音轨只由持有它的那个协程释放**
  （同一轨道双重释放会崩）。
- 另加：切换引擎时释放旧实例（否则旧的原生引擎会一直留在内存里）、加载前检查可用内存并给出
  可读原因、`setCast` 一次性分配互不相同的说话人编号（同场不撞车、同一角色永远同一音色）。

### 修复：朗读在 Android 11+ 完全没声音（回退时被一起删掉的清单声明）

`AndroidManifest.xml` 缺 `<queries>` 里的 `android.intent.action.TTS_SERVICE`。targetSdk 30+
的包可见性限制会让 App **枚举不到任何 TTS 引擎**，`TextToSpeech` 初始化直接回调 ERROR，
表现为「点朗读完全没声音、也没有任何报错」。本项目的 targetSdk 是 **36**，所以这条是必需的。
回退语音特性时它被一并删除，**2.1.21 起的所有版本（含 2.2.x）都受影响**，本版补回。

顺带把系统 TTS 的初始化失败也**记进日志**（引擎初始化非 SUCCESS / 缺中文语音数据两种情况），
把「没声音」从「无从查起」变成一条可排查的记录。

### 修复：更新说明在弹窗里显示成一堆原始标记

更新弹窗把发布说明**逐行原样**渲染，只剥掉行首的 `- `/`* `。于是正文里的 `**加粗**`、
反引号、`### 标题`、表格竖线全部**连标记一起显示**，加上 2.2.2 那份面向维护者的技术记录
（`179/178`、`transcript_count`、探针编号…），对用户完全不可读。

- 弹窗改为解析一个很小的 Markdown 子集：标题 / 圆点条目 / 序号条目 / 引用 / 分隔线分行成块，
  行内 `**粗体**` 与 `` `代码` `` 按样式渲染，表格去掉竖线按 ` · ` 连接；**认不出来的行退化为
  普通段落**，不丢内容。解析是纯函数 `parseReleaseNotes`，配 6 个单测。
- 发布说明的写法也改了：**面向用户的一句话 + 要点列表**，技术细节留在 CHANGELOG。
  2.2.2 的 Release 正文已就地更新（用户当前装的是 2.2.1，弹窗读的是远端 Release body）。

### 验证

```
./gradlew :app:androidApp:compileDebugKotlin     BUILD SUCCESSFUL（无新增警告）
./gradlew :app:androidApp:testDebugUnitTest      114 测试类 / 576 用例 / 0 失败 0 错误
```

新增 95 个语音相关用例（声线派生与撞车、情绪与标点韵律、引擎降级四种原因、模型目录与路径
拼装、下载服务、UI 状态机），另 6 个更新说明排版用例。其中 `SpeechVoiceTest` 里那条
「8 个中文角色名必须派生出 8 条不同声线」的定点回归仍在——它当初抓出的是
「李慕白与顾青声线完全相同」的真 bug。

## [2.2.2] - 2026-10-17

### 修复：`transcript_count` 与真实条数脱钩（2.1.6 起存在）

**症状**：会话的 `transcript_count` 声明值比实际条数多（真机诊断导出里 2/4 个会话分别是
`179 / 178` 与 `27 / 26`）。这个字段决定「还有更多历史」的显示、向上翻页的 offset
与增量补齐的判据——声明偏大就会一直显示「还有更多」，并去请求一页并不存在的数据。

**根因**：全仓库有 6 处写 `transcript`，只有 2 处同步写 `transcript_count`。
纠错（删 N 条插 M 条）、分支（截断为前缀）、场景切换（追加 1 条）、临时 NPC 插件（追加 2 条）
这 4 条路径直接 `put("transcript", …)` 就落盘，而 `saveSession()` 只做原样合并、**从不重算**。

**修复**（写入侧收口 + 读取侧自愈）：

| 层 | 位置 | 作用 |
| --- | --- | --- |
| 写入侧 | `StorageService.updateSessionManifest` | 会话清单的**唯一写入口**，写回前统一对齐计数 |
| 写入侧 | `DialogueAdvancedService.saveSession`、`PluginHostImpl` | 绕过上面入口直接落盘的路径同样经过对齐 |
| 分支 | `DialogueAdvancedService.forkSession` | 额外把 `transcript_start` 归零——分支保留的是**完整前缀**，不是「归档窗口 + 最近 N 条」 |
| 读取侧 | `SessionResponseUtils.transcriptSize` | 以 `transcript_start + 数组长度` 为准，不再信可能陈旧的声明值 |
| 读取侧自愈 | `StorageService.getDialogueSession` / `loadSessionManifest` | 发现存量数据不一致时就地修正并落盘（幂等，只在真不一致时写，**不改 `updated_at`**，不影响会话排序） |

自愈同时覆盖「`transcript_start > 0` 却找不到任何归档条目」的情况（分支会话沿用父会话偏移的
典型形态），把它归零——这不改变用户可见内容，`materializeSessionTranscript` 本来也只能给出最近这一段。

### 新增：`chat.advanced_actions` 探针

33 个已知特性里**唯一没有探针**的一个，而上面那批缺陷恰好全部落在它上面（纠错 / 分支 / 场景切换）。
新探针读持久化状态判定共同不变量：高级动作改写 transcript 之后计数必须与实际条数一致、
分支会话的归档偏移必须归零。内容质量不做模型评审，不在判定范围内。

### 修复：TTS 引擎泄漏

`rememberPlatformTts()` 在**进入对话页**时就构造 `TextToSpeech`（绑定系统服务），
而 `onDispose` 只调 `stop()`、**从不调 `shutdown()`**：

- 每次进出对话页泄漏一个 TextToSpeech 实例与它的 ServiceConnection；
- 用户从没点过朗读也会绑定一次系统 TTS 服务，且因为绑定是异步的，离开页面时
  `stop()` 会打出 `TextToSpeech: stop failed: not bound to TTS engine`。

改为**首次朗读时才绑定**、页面销毁时 `shutdown()`。朗读功能本身不变。

> 说明：2.1.21 回退的是 2.1.19 / 2.1.20 的「每个角色不同的声音」与「离线神经朗读」，
> 系统 TTS 朗读按钮一直都在，不是残留。

### 修复：帧采样的假卡顿

应用切后台/息屏期间 Choreographer 不再回调，恢复后的第一帧会把整段挂起时间算成一帧——
真机导出里出现过 `longestFrameMs = 1080010`（1080 秒的「单帧」），看门狗也报过一次
「主线程阻塞约 2086682ms」。

现在超过 1 秒的帧间隔、超过 10 秒的帧停顿都判为**采样间断**：不计入帧统计与阻塞统计，
但在报告里单独给出次数与总时长（`samplingGaps` / `samplingGapMs`），
让人能区分「真的卡」与「采样断了」。

### 修复：可访问性审计的假阳性

真机上「运行诊断」页 8 个可点击节点全被判成「无语义标签 + 点击目标 < 48dp」，
而那 8 个其实都是 Material3 标准组件（3 个 IconButton、3 个 OutlinedButton、1 个 FilterChip）。
问题在采样口径，不在界面：

| 项 | 旧口径（假阳性来源） | 新口径 |
| --- | --- | --- |
| 标签 | 只看节点自身的 `contentDescription` / `text` | 看**整棵子树**——Compose 的 Button / IconButton / FilterChip 把可读文本放在子节点 |
| 尺寸 | `boundsInRoot`（视觉尺寸：Button 40dp、FilterChip 32dp） | `touchBoundsInRoot`（Material 的 `minimumInteractiveComponentSize` 扩展的正是触控区域） |

两项都已修正，真实的可访问性问题仍然会被报出。

## [2.2.1] - 2026-10-17

### 修复：角色回复完成后，本轮消息从界面上消失

**症状**：一轮对话生成结束后，该轮除最后一条外的消息全部从聊天界面消失——用户消息与
部分角色的回复都不见了。**退出会话重新进入，它们又全部出现**（数据本身完整，只是前台列表被覆盖）。

**根因**：`mergeTranscript()` 用「同一 turnId 只保留最后一条」做去重，但一个 turnId 实际对应
**一整轮**的多条消息（用户消息 + 该轮每个说话人的回复，见 `DialogueService.updateSessionManifest`
中用户消息与每条 response 都 `put("turn_id", turnId)`）。于是每轮生成完成（`Complete` 事件）时，
除最后一条外的条目全被从本地 transcript 中删除。

该逻辑 2.1.6 为修「同一句回复冒出多个气泡」而引入，2.1.10 又复制到 `prependTranscript`（向上翻页）。

**修复**：去重单位从「条」改为按 `TranscriptItemDto.transcriptKey` 逐条比对
（key = `turnId|speaker|timestamp|正文哈希`）。同一轮里不同说话人、不同台词 key 互不相同，
整轮完整保留；只有真正的重复（整轮重试、`missing` 与 `appended` 拼接重叠）才会撞 key。

顺带修掉两个同源的连带隐患：

| 隐患 | 后果 |
| --- | --- |
| `base.filterNot { it.turnId in appendedTurnIds }` 按 turnId **整块移除** | `append` 只覆盖该轮一部分时，会误删 base 里该轮的其余条目 |
| `prependTranscript` 里 `current.filterNot { it.turnId in earlierTurnIds }` | 分页边界**切开同一轮**时，向上翻页会把后半段直接丢掉 |

**测试**：改写 2 个把「同一 turnId 只保留一条」这一错误假设断言成规格的用例，
新增 3 个回归用例（一轮多角色不压缩、整轮重试、分页边界切开同一轮）。
单测总数 **470，全部通过**。

## [2.2.0] - 2026-10-16

### 重构：单模块 Android 工程，去掉内嵌 HTTP 层

本版是**架构重构**，不是功能版本。对使用者的唯一可见变化：**桌面端不再提供**（Android 功能不变）。

| 项 | 说明 |
| --- | --- |
| 工程结构 | 33 个 KMP 模块 → **1 个 Android 模块**（`kmp/app/androidApp`）。Compose Multiplatform → Jetpack Compose；`expect/actual` 全部消除；`app/shared`、`app/desktopApp` 与各 `feature/*`、`core/*`、`data/*`、`server/*` 模块合并进单模块 |
| 内嵌 HTTP 层 | 以前客户端在进程内起一个 Ktor CIO 服务端（监听 `127.0.0.1` 随机端口），再经 localhost HTTP/SSE 调回自己。现在改为**进程内直接调用**：路由、鉴权、SSE 编解码、本地 HTTP 客户端与端口监听整体删除，`LocalBackendController` → `InProcessBackendController` |
| 对话流式 | SSE 文本层消失，直接消费服务内部的 `Flow`；事件序列（status / delta / reset / complete / failure）与原先逐项一致 |
| 依赖 | 移除 Ktor **服务端**依赖（6 项 build + 7 条版本目录条目）。保留 Ktor **客户端**：模型 API、在线书库、更新检查、端侧 llama-server 仍需对外网络。APK 40.2 MB → 37.7 MB |
| 桌面端 | **不再构建**（此前只支持 macOS / Linux；Windows 支持已于 2.1.14 移除） |
| 数据兼容 | Room schema 与 2.1.21 **逐字节一致**（version 2，identityHash `305fe8fbcc5f78d3`）→ **可直接覆盖安装，不会丢数据** |

顺带修正：删路由后暴露出两个**从未被客户端调用**的死端点（非流式 `/reply`、`/suggestions` 与 `/associations`）；
构建期生成物 `GeneratedAppVersion` 与 `AppMetadata` 内容重复，已合并为后者。

验证：干净构建 + **467/467** 单元测试通过；从远程全新克隆后构建产物字节数与本地完全一致。

## [2.1.21] - 2026-10-14

### 回退：语音朗读特性（撤回 2.1.19 / 2.1.20 的内容）

2.1.19 与 2.1.20 引入了「每个角色不同的声音」与「离线神经朗读」，并且**已经发布**。
这两个版本在真机上出现未解决的问题（试听闪退、所有人一个声音），因此本版把**代码回退到 2.1.18 的状态**，
用一个**新版本号**重新发布，先交出一个可用版本；问题定位留到之后再做（用户会补充具体现象）。

| 项 | 说明 |
| --- | --- |
| 回退方式 | **revert 提交**，不改写历史。回退后 `git diff v2.1.18` **为空**（逐字节一致，已核对） |
| 语音的工作 | **完整保留在 git 历史里**；恢复只需 `git revert <本次回退提交>`，不需要重做 |
| 2.1.19 / 2.1.20 | 那两个版本及其 Release 说明仍可在 GitHub Releases 页面查到；本 CHANGELOG 不再单列它们的条目（代码里已不存在对应能力） |
| 本版**不含** | 每个角色不同的声音、离线神经朗读、语音模型按需下载、服务端语音模型路由 |

### 未撤回（仍然保留）

- 2.1.17：设置聊天背景闪退（大图解码未下采样，216 MB 超过 Android `Canvas` 的 100 MB 硬上限）
- 2.1.18：端侧推理整个起不来（随包二进制多了非系统依赖 `libOpenCL.so`，且 spawn 未设 `LD_LIBRARY_PATH`）
- 2.1.18：端侧二进制退回**自包含**（从随包产物移除 OpenCL 后端）

也就是说：**装本版不会丢掉 2.1.17 / 2.1.18 的修复**，只是没有语音朗读。

### 为什么回退（如实记录）

用户报告的两条症状是「试听闪退」与「所有人一个声音」。但开发环境无法验证声音，
且用户提供的诊断导出**两次都是 2.1.16 的旧文件**（`versionName 2.1.16`、`generatedAt 2026-10-13T16:37`，
与更早逐字节相同），因此 2.1.20 里那批崩溃修复是**基于代码分析**的、**未经真机确认**。
在「拿到可用版本」与「继续叠加未验证改动」之间，回退是更稳妥的选择。

## [2.1.18] - 2026-10-14

### 修复：端侧推理整个起不来（2.1.16 引入的回归，P0）

2.1.16 换掉随包二进制之后，**本机推理完全无法启动**——不是"用不上 GPU"，是加载阶段就失败：

```
CANNOT LINK EXECUTABLE "/data/app/.../lib/arm64/libllama-server.so":
library "libOpenCL.so" not found: needed by main executable
```

根因是「**随包二进制多了一条非系统依赖**」+「**起子进程时没有设置库搜索路径**」两件事叠加：

- 2.1.16 的构建把 OpenCL 后端编了进来，于是 `libllama-server.so` 的 `NEEDED` 里多了一条
  **`libOpenCL.so`**（2.1.15 那版 `NEEDED` 只有 `libm/libdl/libc`，全是系统库，所以没暴露问题）；
- 而 `AndroidOnDeviceRuntime.spawn()` 用的是裸 `ProcessBuilder(...).start()`，**没有设置任何环境变量**。
  Android 的 linker **不搜索「可执行文件所在目录」**，应用的 linker namespace 只对 `dlopen` 生效，
  所以子进程找不到同目录的 `libOpenCL.so`。
- 注意 `libOpenCL.so` 本身**确实在 APK 里**（`androidApp` 开了 `useLegacyPackaging = true`，它被解压到了
  `nativeLibraryDir`）——缺的只是**搜索路径**。

修复分两步，**两手都做了**：

1. **随包二进制退回自包含**（`GGML_OPENCL=0`，保留 KleidiAI）：`NEEDED` 回到只剩
   `libm/libdl/libc`，与 2.1.15 一样不可能因为缺库而加载失败。同时从 jniLibs 移除已无引用的
   `libOpenCL.so`（50,272 B，白占体积）。产物 13,591,072 → **10,683,016 B**。
2. **spawn 时显式设置 `LD_LIBRARY_PATH`** 指向 `applicationInfo.nativeLibraryDir`（保留调用方原有的
   值），让子进程能找到与可执行文件同目录的非系统依赖。这一步**也是将来任何多 `.so` 形态的前提**
   ——否则「动态后端」架构（`BUILD_SHARED_LIBS=ON` + `GGML_BACKEND_DL=ON`）同样会整体起不来。

### 关于 OpenCL（撤回，不是放弃）

OpenCL 后端**仍然可以编**（`GGML_OPENCL=1`），构建脚本的解析与随包逻辑都保留着。它被撤回随包，
是因为**它在真机上从未被验证过**，而它带来的却是一个「整个端侧起不来」的风险——这个代价
和收益完全不成比例。等满足两个条件再考虑随包：① spawn 的 `LD_LIBRARY_PATH` 修复在真机上确认有效；
② 确认 ICD loader 能在 App 沙箱内枚举到厂商驱动。

### 已知残留

- 端侧 GPU（OpenCL）**不随包**；「GPU 层数」填了不会有效果（与 2.1.15 行为一致）。
- 桌面端 `ImageDecoding.jvm.kt` 仍整幅解码（见 2.1.17）。
- 背景图目标文件名固定，换同扩展名的图时不刷新（见 2.1.17）。

## [2.1.17] - 2026-10-14

### 修复：设置聊天背景后闪退（大图未下采样）

把一张大照片设为聊天背景后，App 在**绘制期**必崩。真机诊断导出的原文：

```
RuntimeException: Canvas: trying to draw too large(226492416bytes) bitmap.
    at android.graphics.RecordingCanvas.throwIfCannotDraw(RecordingCanvas.java:267)
    at androidx.compose.ui.graphics.painter.BitmapPainter.onDraw(BitmapPainter.kt:89)
```

原因是解码**没有下采样**：9216×6144 的照片按 ARGB_8888（每像素 4 字节）解码是
`9216 × 6144 × 4 = 226,492,416 B ≈ 216 MB`，而 Android 的 `Canvas` 有 **100 MB 的硬上限**
（`RecordingCanvas` 的 `MAX_BITMAP_SIZE`），超过就直接抛 `RuntimeException`。

**关键在于它抛在绘制期，所以加 `try` 没有用**——解码那一步的异常早就被 `runCatching` 兜住了，
兜不住的是之后的绘制。只能把解码尺寸降下来。

诊断的面包屑也印证了触发点（同一次会话崩了两次）：进入「对话显示设置」后 0.6 秒崩在第 1 次
（设置页的背景预览绘制它），进入「对话」后 0.5 秒崩在第 2 次（聊天页绘制同一张）。

- 新增纯函数 `computeImageSampleSize`（`ui/graphics/ImageSampling.kt`，**不依赖任何平台 API**，
  因此可在 `jvmTest` 直接回归）：按目标长边（默认 2048）与字节上限（32 MB，离 100 MB 硬线留足
  余量）算出 **2 的幂**的 `inSampleSize`；两个约束同时满足，字节数按**向上取整**保守估算。
- Android 的 `decodeImageBitmap` 改成**两趟解码**：先 `inJustDecodeBounds` 只读原始尺寸
  （不分配像素），再按算出的倍数真正解码。那张图 **216 MB → 3,538,944 B（约 3.4 MB）**。
- 修在**唯一的解码入口**上，所以**人物头像、会话封面、运行详情图**一并受益。
- 新增 **12 个回归用例**。其中一个用例**先断言崩溃成因**（`9216×6144×4 == 226,492,416` 且
  超过 100 MB 硬线）**再断言修复**，兼作「不许改回全分辨率」的锁；其余覆盖小于目标 / 恰好等于 /
  超一像素 / 极端长条（`100000×1 → 64`）/ 非法尺寸 / 上限回落 / 字节兜底 / 循环终止 /
  以及「任何输入下都返回 2 的幂且双约束满足」的性质测试。

### 已知残留（本版**未**处理）

- **桌面端仍整幅解码**：桌面没有 Android 那条 100 MB 的硬线，而该崩溃只在 Android 端出现过；
  桌面加载 56 MP 图仍会分配约 216 MB。
- 背景图的目标文件名固定为 `chat-background.<扩展名>`，换**同扩展名**的图时路径不变 →
  `remember(uri)` 不重算 → 显示旧图。
- 解码仍在主线程（composition 内同步读整文件 + 解码）。

### 未做真机验证

崩溃条件（位图 > 100 MB）已从 216 MB 降到 3.4 MB，逻辑上消除；真机确认需要把那张照片设为背景。

## [2.1.16] - 2026-10-14

端侧推理的二进制**真正重编了**。2.1.15 交付的是「构建侧就绪」——设置里出现了「GPU 层数」，
但包里那个 `libllama-server.so` 还是旧构建，填了会被 llama-server 忽略（打印
`no usable GPU found` 后继续用 CPU）。这一版把二进制换成带 KleidiAI 与 Adreno OpenCL 的新构建。

### 端侧：Arm KleidiAI CPU 内核

按**运行时**检测到的 CPU 特性派发微内核（dotprod / fp16 / i8mm），老机型自动回退。
**编译基线仍是 armv8-a**，不是抬高全局 `-march` —— 后者会让 armv8.0 机型 SIGILL，而本仓库只发
一个 arm64 `.so` 给所有设备。本机实测证据：`build.ninja` 里 290 条含 `-march` 的规则中，
**241 条非 `armv8-a` 的全部在 KleidiAI 内部**，属于本仓库自身代码的 **0 条**；`armv8.7a` 出现 **0 次**。

### 端侧：Adreno OpenCL 后端

llama.cpp 的 OpenCL 后端已编入（含 Adreno 专用 matmul 内核，以及嵌入的 OpenCL 内核源码），
并把 Khronos ICD loader 随包放在同一目录：

- `libOpenCL.so` 50,272 B，NEEDED 只有 `libdl/libm/libc` —— **不需要 `libc++_shared.so`**
  （用 `ANDROID_STL=c++_static` 编译且关闭 layer 支持）
- **「GPU 层数」现在真的生效**：默认仍是 `0`（全 CPU），要手动调高才会把层放到 GPU 上

### 已知：本版的端侧 GPU 与 KleidiAI **尚未在真机上验证**

开发机没有设备；能离线验的都验了，运行期表现一项没验：

- **已验**：ELF 架构 / PIE / NEEDED 依赖闭包 / KleidiAI 与 OpenCL 确实被链进来
  （对照旧包：KleidiAI 运行期常量 20 个 vs **0 个**）
- **未验**：真机上能否用到 GPU、实际提速多少、KleidiAI 收益多大
- 若 OpenCL 找不到厂商驱动，后端会**注册 0 个设备并回退 CPU**，不会失败（已读
  `ggml/src/ggml-opencl/ggml-opencl.cpp` 的注册路径确认）
- 若端侧表现异常，**2.1.15 是可回退的版本**（它是纯 CPU 构建）

### 构建与仓库（对使用者不可见）

- 构建脚本新增 `CMAKE_BIN` / `BUILD_DIR` / `SHARED_BACKENDS` / `HEXAGON_BACKEND_DIR` /
  `EXTERNAL_BACKENDS_OUT`；支持编成「动态后端」形态（`BUILD_SHARED_LIBS=ON` + `GGML_BACKEND_DL=ON`），
  含多共享库收集、非 aarch64（Hexagon ELF32）跳过 strip、NEEDED 闭包自检与外部后端导入。
  **本版随包仍是单文件形态**，外部 Hexagon 后端没有入库。
- `PYTHONUTF8=1`：上游 `embed_kernel.py` 用平台默认编码读 `.cl`，中文 Windows（默认 GBK）下编
  OpenCL 必然 `UnicodeDecodeError` 而失败。
- `EXTRA_CMAKE_ARGS`：逃生口。KleidiAI 默认由 FetchContent 拉 release archive（带 MD5 校验）；
  网络不可达时可自己下、核对 MD5、解压后用 `FETCHCONTENT_SOURCE_DIR_KLEIDIAI` 指过去，configure 阶段零联网。
- 修 **Windows 宿主**上 `build-android-apk.sh` 直接失败的问题：aapt2 覆盖的 wrapper 是 POSIX 脚本，
  AGP 在 Windows 上无法把非 `.exe` 当可执行文件；而该覆盖本就只为 arm64 宿主准备。
- `docs/maintainer-setup.md` 记入 SDK 自带 CMake 3.22.1 在 Windows 上配置 llama.cpp 会**原生崩溃**
  （`0xC0000409`，停在 `CMakeFindBinUtils` 的 `find_program`）的坑与解法（`pip install cmake`）。

## [2.1.15] - 2026-10-13

### 新增

- **内置模型清单：点模型名即自动下载并选中。** 端侧页新增「可用的模型」区，4 个 Qwen GGUF 预设；
  点一下自动填好直链与文件名、立刻下载，并**同时把它设为选中项**（下载完成、刷新出文件后不用再点一次）。
  已经下过同名文件时**不重下**（那会白花几个 GB），只把它选中。
- **下载源可选**：HuggingFace 官方（默认）与 hf-mirror 镜像。两者 `resolve/main` 直链同构，
  换源只换主机名；换源入口就放在清单正上方（开发机实测 `huggingface.co` 不可达、`hf-mirror.com` 可达，
  国内不挂代理时官方站多半连不上）。
- **每个模型都标注「能力来源」**：确切仓库、发布者（及是否官方）、上游基座、元数据读取日期与下载量、
  上游模型卡地址。清单里的说明**只写能从文件大小推出的东西**（体积、建议可用内存），
  **不写跑分、不写「谁更强」**——能力以模型卡为准，界面上也明说「本项目没有评测过这些模型」。
- **「GPU 层数」可在设置里调**：端侧运行控制区新增输入框（`0` = 全 CPU，`99` = 尽量全放 GPU），
  真正传进 `StartOnDeviceRequest`。没编 GPU 后端时 llama-server 会忽略它并打印
  `no usable GPU found` 后继续用 CPU——不会失败。

### 构建侧就绪（**本版 APK 内尚未生效**）

- **端侧 GPU 加速（Adreno / OpenCL）与 Arm KleidiAI 的构建支持已进 `build-llama-server-android.sh`**，
  但**随包的 `kmp/app/androidApp/src/main/jniLibs/arm64-v8a/libllama-server.so` 仍是旧构建**，
  重新编译需要 llama.cpp 源码树 + NDK（OpenCL 还需 Khronos 的 OpenCL-Headers 与 ICD loader）。
  因此**本版 APK 的端侧推理仍然只走 CPU**，设置里的「GPU 层数」要等重新构建 `.so` 之后才会生效。
  输入框提前放出来，是为了让重编后的包可以直接用；它的说明文字也如实写了「只有编译时带上 GPU 后端才生效」。
- 构建脚本的具体行为：默认 `GGML_OPENCL=1`，自动解析 OpenCL 头与 `libOpenCL.so`
  （优先 `OPENCL_ROOT`，其次 NDK sysroot），并把 ICD loader 随包放进 `jniLibs`；
  **缺 OpenCL 时直接报错退出**，不静默编出「有 `NEEDED libOpenCL.so` 却没库可加载」的可执行文件
  ——那种包在真机上是整个端侧起不来，比慢严重得多。`GGML_OPENCL=0` 可编纯 CPU 版本。
- CPU 侧改用上游官方 Android 配方的 **`-DGGML_CPU_KLEIDIAI=ON`**：运行时按检测到的 CPU 特性派发
  （dotprod / fp16 / i8mm），老机型自动回退。此前用抬高全局 `-march` 提速的做法已废弃——
  那会让 armv8.0 机型 SIGILL，而本仓库只发一个 arm64 `.so` 给所有设备
  （`+i8mm` 更危险：`-march` 会无条件定义 `__ARM_FEATURE_MATMUL_INT8`，社区有 Pixel 6/7 实测 SIGILL 的记录）。

### 修复

- **内容审核拒绝被漏判成普通错误**（2.1.14 的归类只认文案，漏了机器可读的 `code`）。
  真机 `tier=full` 导出显示上游返回的是
  `{"message":"inference request is invalid","code":"data_inspection_failed"}` ——
  `code` 说明它是商汤的**数据审查（内容审核）拒绝**，而那句 message 读起来完全像参数错误。
  因为只认 message 措辞，这类拒绝继续走普通错误路径：聊天页显示成应用故障、出现注定无效的
  「重试」按钮、对话生成探针报 ❌。现新增 `CONTENT_MODERATION_CODES`（机器可读 code，
  用词干 `data_inspection` 以覆盖后续变体），判据改为**先 code 后措辞**；
  `max_tokens must be ...` / `model not found` 这类既无 code 又无审核措辞的 4xx 仍走普通路径。
- **`model_configured` 恒为 false**：保存路径从不写 `ModelProfile.configured`，而诊断读的正是那个
  存储字段——真机导出里三个档案 provider/model/key 齐全却全部 `configured=false`。
  现按 `putProfileFields` 的口径从实际字段计算（与仓库此前修 `apiKeyConfigured` 的做法一致），
  启动自检与 `model.profiles[]` 两处都改用它。
- **本地（端侧）档案的 `base_url` 会指向死端口**：端侧每次启动换端口，而「本机」档案的 `base_url`
  是启用那一刻抓下来持久化的；端侧重启后那份就过期，表现为 `ConnectException`
  （真机：对话生成 ❌ 连 `127.0.0.1:34699`、能力探测连 `127.0.0.1:43495`）。
  现由端侧服务给出**实时**端口覆盖持久化值。
  ⚠️ 只修了「在跑但端口变了」这一类；端侧已停止时仍指向旧端口（那时 provider 确实不可用，失败是如实的）。

### 文档与维护

- `docs/server-api.md` 的审核判据段改写为「先 code 后措辞」，并说明为什么必须有 code 这一层。
- **校正对外描述面里的「起始版本」标注**：**移除 Windows 支持**、**提示词守卫接入开发自检**、
  **桌面端只支持 macOS / Linux** 其实都在 **2.1.14** 就交付了（tag `v2.1.14` 里
  `desktopApp/build.gradle.kts` 的 `windows { }` 与 `Main.kt` 的 JNA/DWM 均为 0 处），
  此前被误标为「未发布」。
- 新增「**对外描述面同步**」规则（`AGENTS.md` 与 `docs/maintainer-setup.md`）：列出仓库页 About、
  topics、README「本版改动」表、`docs/features.md`、CHANGELOG 这五个必须一起更新的面，
  标明哪些能自动校验，并约定「**未发布的能力在起始版本栏就写未发布**，切版本号时统一替换」
  ——提前写一个还没发布的号，会让这几处与 CHANGELOG 立刻互相矛盾。
- README 不再引用 `docs/changes-from-upstream.md` 里那个会持续过期的「新增 / 修改 / 删除」计数，
  改为注明**那是某一轮的快照**（与 README 早已声明的「不写死版本号」同一口径）。
- 仓库页 topics 增加 `opencl`、`adreno`。

### 已知限制

- **真机上尚未验证**：OpenCL 构建能否在设备上跑起来、`libOpenCL.so` 在 App 沙箱内能否发现
  厂商（高通）ICD、实际提速幅度，以及「点模型名」的实际下载能否成功
  （开发机 `huggingface.co` 不可达，只有镜像通）。
- **内置模型清单没有 SHA256 校验**：拿不到可信的官方摘要，因此没有凭空写入校验值；
  目前只靠服务端原有的 `.part` 原子改名 + `.gguf` 后缀校验。
- **`ModelProbes` 仍把「端侧未启动」判成 ❌**：`provider = local` 且端侧停止时，对话生成探针报故障
  而不是「未启动」。要区分需要给探针传入端侧可用性，尚未做。
- 桌面端 `packageReleaseDistributionForCurrentOS`（ProGuard + jpackage）仍未在开发机验证：
  删除 JNA / FileKit-Windows 的 ProGuard 保留规则对 release 打包的影响未经实机验证。

## [2.1.14] - 2026-10-13

### 新增

- **把「模型提供方内容审核拒绝」从普通请求错误里分出来**：此前所有失败都报成
  `dialogue.stream_error`，用户无法分辨「我的请求有问题」与「内容被提供方按策略拒绝」。
  现在命中审核措辞的上游错误会单独归为 `dialogue.content_rejected`，问题清单里是独立分组
  （`kind=provider-policy`，信息级，**不排在崩溃/缺陷前面**），标题写明
  「**内容被模型提供方拒绝（非应用故障）**」并带上提供方、模型、端点与上游原文。
  聊天页显示该文案且**不出现「重试」按钮**（内容不变，重试只会再被拒一次）。
- 判据保守可解释：只认明确审核措辞（`Inappropriate input/output rejected`、`content policy`、
  `moderation`、`safety`、`敏感/违规/审核/不合规/内容安全` 等）；
  单个英文单词按**词边界**匹配，所以 `safety_settings` 这类**参数名**不会被误判成审核。
  经编译产物实测：真机那两条原文分别走**不同**路径——
  `Inappropriate input/output rejected for security reasons` → 审核拒绝；
  `inference request is invalid` → 普通错误（仍记 4xx 留证）。

  > ⚠️ **更正（2.1.14 发布后由真机 `tier=full` 导出定案）**：上一句断言**是错的**。
  > `inference request is invalid` 的完整上游错误体是
  > `{"error":{"message":"inference request is invalid","type":"invalid_request_error",
  > "code":"data_inspection_failed"}}`——`code` 说明它是商汤的**数据审查（内容审核）拒绝**，
  > 那句 message 只是措辞像参数错误。因为只认 message，2.1.14 把这类拒绝继续判成普通错误：
  > 用户看到「应用故障」、拿到注定无效的「重试」按钮，对话生成探针还报 ❌。
  > 判据已改为**同时认机器可读 code**（`LlmClient.CONTENT_MODERATION_CODES`），
  > 并补了 `LlmContentModerationTest` 回归测试；`docs/server-api.md` 同段已改写。
  > **已发布的 `v2.1.14` 不改动**（本节保留原文 + 本更正，不重写已发布历史）。

### 修复

- **修正 2.1.13 里被误提交的半成品**：`1de1d65`（CHANGELOG 标日期的提交）因误用 `git add -A`
  扫进了当时正在编写的 `LlmClient.kt`（首版分类器 + 新异常类型），导致 2.1.13 的发布包含
  「异常会被抛出、但没有专门的捕获路径」的**半接线**状态——该功能在 2.1.13 中不产生可见效果。
  本版补齐捕获侧、问题分组、中文标签与文档，使其真正生效。
  **已发布的 `v2.1.13` tag 未改动**（不重写已发布历史）。
- 分类用新异常携带 `statusCode` / `providerKind` / `endpoint` / `model` / `upstreamMessage`；
  端点先过 `sanitizeEndpoint`（Gemini 的 key 在 query 里，绝不进异常与事件）。
- 重试逻辑显式排除该异常（原 400 本就不重试，现加显式分支并注释原因），
  并给超时文案翻译加保护，避免准确文案被改写成「模型响应超时」。
- **桌面端「查人设档案」会崩掉整轮对话生成**（`DocumentStore`）：okio 的 `metadataOrNull`
  在 Windows 上**先做路径转换**，遇到本平台无法表示的路径会抛异常；而 `DialoguePayloadBuilder`
  是用 `artifact_index` 里的**原始**角色名拼路径（为兼容旧书卷，注释写明「文件路径继续用原始写法」），
  所以旧书卷里角色名带引号时（`artifacts/characters/"陆城"/PROFILE.md`）会直接崩。
  该字符在 Linux 上合法，于是同一份数据变成**静默找不到人设**——两种坏法。
  现把 `exists` / `isFile` / `isDirectory` / `updatedAtMillis` / `listFiles` 的路径异常
  收敛为 `false`/`null`（「本平台无法表示的路径当然不存在」），两端行为一致。
- **诊断日志的两个字段上限没生效**（`DiagnosticsEventLog`）：`clip()` 把截断标记拼在 `limit`
  之外，400 字上限实际落盘 412 字——而这些上限的意义正是界定磁盘占用；
  `maxRegularEntries = maxEntries.coerceAtLeast(maxCrashEntries)` 让传 `maxEntries=5`
  实际生效 120，与 KDoc「常规与崩溃**分别设限**」矛盾（改为把崩溃配额压到不超过常规配额）。
- **修复 14 个失败的 Kotlin 测试**（`:server:jvmTest` 此前 297 用例中 14 失败）：
  `ExitReasonMappingTest` 的码值表整体错位一位（正是 `ExitReasonMapping` KDoc 明确警告过的错位：
  8 应是 `PERMISSION_CHANGE` 而非 `EXCESSIVE_RESOURCE_USAGE`）、`schemaVersion` 断言 4→6、
  `FeatureProbeRegistryTest` 用 `error(...)` 当 `promptLoader`（构造即抛，所有用例开头就挂）、
  诊断断言未传 `tier=full`（compact 档刻意清空 `recentEvents` / `runtimeLog`）、
  `unregisteredFeatures` 断言与实现的集合差语义相反。
- `JvmPromptSourceTest` 的蒸馏 md 用例名与注释都声称覆盖 `zaomeng-skill` 回退，实际只走
  `prompts/` 路径；已改名并**新增真正覆盖回退的用例**（自建临时根目录，不依赖本机仓库布局）。

### 移除

- **Windows 支持**。仓库此前已宣布「不再维护 Windows 构建」，本次把残留补齐：
  - `.github/workflows/android.yml` 仍在调用**早已删除**的 `kmp/gradlew.bat`，且是唯一
    `windows-latest` 作业 → 改为 `ubuntu-latest` + `./gradlew`。**它同时也是唯一执行 Kotlin
    测试的 workflow**，坏掉之后 `jvmTest` 在 CI 里长期无人运行（这正是上面那 14 个失败
    能积累下来的原因）。
  - Windows 专属代码：JNA/DWM 深色标题栏（该函数在非 Windows 上本就 early-return，删除对
    macOS/Linux 行为零影响）、Windows TTS（PowerShell SAPI）、llama-server 的 `.exe` 查找分支。
  - `jna` 依赖与 version catalog bundle、`nativeDistributions` 的 `windows { }` 安装包配置、
    仅被它引用的 `packaging/zaomeng_logo.ico`、ProGuard 里给 JNA / FileKit-Windows 的保留规则。
  - 网站三处桌面平台声明与插件文档的 PowerShell 打包标签页、开发指南里两条完全相同的
    Windows/macOS-Linux 命令分支、发布回归门禁的 `windows` 必签平台（含签署 JSON 与其测试）。
  - **桌面端发布目标一直是 `targetFormats(Dmg, Deb)`**——Windows 从未真正打包过，
    这些是「声称支持」的残留。
- **刻意保留**与宿主平台无关的 Windows 路径处理：`RunPackageService` 的
  `WINDOWS_ABSOLUTE_PATH` / `WINDOWS_RESERVED_NAMES`（防恶意 ZIP 的**安全**校验）、
  `PathSafety` / `DocumentStore` / `RelationsService` 的路径规范化与 BOM 容忍。
  这些删掉是回归，不是「去掉 Windows」。

### 文档与维护

- 提示词实际是**三处**副本（`prompts/**`、`kmp/server/src/androidMain/assets/**`、
  `kmp/app/shared` 的 Compose Resources），根 `AGENTS.md`、`prompts/AGENTS.md`、
  `CONTRIBUTING.md`、`docs/architecture.md` 与 ADR-0005 此前都只写两处——第三处由桌面端
  经 classpath 读取，漏同步就是「改了提示词但不生效」。
- `scripts/check-prompt-sync.sh` 接入 `scripts/dev_checks.py`：此前它不在 `dev_checks.py`、
  不在任何 workflow、也不在 `check-release-ready.sh`，只是一个「记得手动跑」的门禁。
- `docs/server-api.md` 补上 12 个已注册但未文档化的端点（`/api/web/on-device/*` 7 条、
  `novel-chapters` 3 条、`extract-characters` 2 条），并去掉会随每次新增路由过期的计数。
- JDK 要求统一为 **21+**：此前 5 处写 17+，但所有模块 `jvmTarget = JVM_21` 且未配 `jvmToolchain`。
- 其余「文档与实现不符」：`data-dictionary` 的更新时间与结构数、`module-migration` 把 4 个设置
  屏幕的归属写成 `ui:shared`（实际都在 `feature:settings`，`ui:shared` 内无任何 `*Screen*`）、
  `features.md` 的「密钥只经 Android Keystore」（桌面端实为明文 properties）与「把已交付能力
  列在还在路上」、`.gitignore` 的失效规则与重复 `.DS_Store`、网站 JSON-LD 的版本号。

### 已知限制

- 探针 `ChatGenerationProbe` 与 `RuleEngineProbe` 只统计 `dialogue.stream_error`，
  审核拒绝不再计入——这是「不再混为一谈」的必然结果，本版未改探针判定逻辑。
- 非流式路径（蒸馏/章节/建议等）也会抛同一异常且文案一致，但未逐个审计各调用方对错误的包装。
- 旧书卷 `artifact_index` 里带引号的角色名（如 `"李慧英"`）仍是历史数据问题：
  本版只保证它**不再崩掉整轮生成**，没有把这类名字改成规范写法。收录新书卷时名字已过规范化。
- 桌面端 `packageReleaseDistributionForCurrentOS`（ProGuard + jpackage 打包）**未在本机验证**：
  它需要 macOS/Linux 工具链。删除 JNA / FileKit-Windows 的 ProGuard 保留规则对 release
  打包的影响未经实机验证——编译、单测与 CI 的 Desktop 作业（只编译）不受影响。

## [2.1.13] - 2026-10-13

### 新增

- **功能自检（健康探针）**：运行诊断新增「功能自检」区块，由 **27 个探针**检查各功能是否真的在正常运行，
  每项给出 ✅/⚠️/❌ + 一句话结论 + **可核对的数字证据**（如「ok 章 12/15」「期望时间点 5、实际 3」）。
  覆盖：书卷导入/列表/整理/人物档案/人设弧线/弧线索引/关系图/章节索引/原文证据、会话创建/生成/
  首字延迟/转录完整性/长期记忆/世界记忆/场景进度/派生状态、插件生命周期/内置插件动作/规则引擎/
  声明式校验、模型配置/端侧推理/密钥存储、提示词加载完整性/更新检查/TTS。
- **探针注册机制 + 「未注册」可见**：探针由注册表统一登记，诊断页与导出自动列出；
  并用 `KnownFeatures`（33 项）算出**有哪些功能还没有探针**并在界面上显示——
  以后新增功能若忘了注册，界面会直接暴露，而不是靠人记得。
- **画面与流畅度**（R7）：帧指标（掉帧率分档 >16.7/32/100ms、最长帧、按页面分桶）、
  主线程长任务（次数 + 当时栈）、**过度重组 + 过度重绘计数**、图片同 URL 重复请求、布局跳动；
  截图差分「疑似闪烁」**默认关闭**，且**只在内存中比较——不落盘、不上传、不进导出**，
  措辞固定为「疑似」而非「检测到 bug」。
- **问题清单可用性**：**面包屑**（每条问题自动附带发生之前的上下文；
  未捕获异常与 ANR 时**同步把面包屑落盘**，所以崩溃重启后仍能还原现场）、
  **聚合去重**（同堆栈/同签名归为一组，显示次数与首次/最近时间）、
  严重度分级与默认排序（正常退出恒排末尾）、**崩溃类与常规事件分别设保留上限**并暴露占用。
- **导出分级**：`tier=compact`（默认，概览 + 问题摘要 + 探针结论）/ `tier=full`（含日志正文、
  完整堆栈、完整载荷）；报告自带 `tier` 字段说明档位；新增「诊断自身状态」区块
  （丢事件/写入失败/读取失败/采样开销/当前占用）。
- **通用功能事件上报 + 统一入口兜底**：新增 `report(功能, 操作, 成功/降级/失败, 耗时, 详情)`，
  **成功也上报**；在已有统一入口（Ktor 路由拦截器、`repositoryRequest`、`runTool`）接入，
  于是**新增功能不改诊断代码也会被记录**；关键路径上被 `runCatching` 吞掉的静默失败改走该通道。
- **诊断覆盖面如实标注**：进程模型、是否有 WebView、以及**未覆盖的面**（native 完整堆栈、
  被系统 `kill -9` 的场景）逐条列出，不省略。
- **操作轨迹与数据损坏审计**：导航历史 + 关键操作序列；关键文件可解析性检查与写入审计。

### 变更

- **诊断允许记录完整提示词与完整模型输出**（用户明确要求）：只保留**工程上限**
  （单字段 200000 字符 + 文件字节上限 + 超限轮转），且**只在 `tier=full` 导出中出现**。
  **API Key / 令牌 / 签名口令仍然绝不记录**——这是凭证安全而非隐私偏好；脱敏在**写入侧**完成
  （每个 detail 值过 `LogRedactor`），不依赖导出时遮蔽。相关规则已同步进两份 `AGENTS.md`。
- **4xx 时记录实际发出的请求体**（此前只记解析失败，上游拒收时反而没有证据）：
  `llm.http_error_payload` 记录 http 状态、endpoint（**整段剥离 query**，避免 key 泄漏）、
  provider、**线上真实顶层字段名 `request_keys`**、model/stream/token 参数/temperature/
  `reasoning_effort`/`thinking`/`response_format`、messages 的 role 序列与字符数、字节数、
  上游原始错误体（截断）。**留证函数签名里没有 headers 参数**，调用点从不传 `spec.headers`，
  因此鉴权信息在类型层面就进不来。**决定性摘要同时写进事件标题**，
  所以**精简档导出也能直接看到发了什么**。
- **模型运行时长与启动耗时**：后端服务图启动从 `init` 的**同步调用**移到 `Dispatchers.Default`，
  消除启动期主线程约 1.4 秒的阻塞。

### 修复

- **商汤 SenseNova 内置清单按官方文档校对**：移除文档中不存在的 `deepseek-v4.1-flash`；
  `deepseek-flash` 的显示名修正为「DeepSeek V4.1 Flash」；顺序对齐官方模型总览；
  服务端预设补入文档证实的 5 个对话模型（此前一个都没有）；图片生成模型继续排除。
- **`reasoning_effort` 改为逐模型白名单**：商汤各模型允许值不同（`glm-5.2` 有 `xhigh/minimal`，
  `6.8-flash-lite` 没有），此前只按端点判断、原值透传；现在**不在白名单的值不发**，
  并按文档为 `deepseek-flash` 实现兼容映射（`minimal→low`、`medium/xhigh→high`、`ultra→max`）。
- **`kimi-k3` 改用 `max_completion_tokens`**：其官方请求参数表里没有 `max_tokens`。
- **流式思考增量兼容 `reasoning` 字段**：商汤 `sensenova-6.8-flash-lite` 把思考吐在 `reasoning`，
  DeepSeek 系吐在 `reasoning_content`，此前只解析后者会丢思考增量（正文不受影响）。
- **保存模型档案时校验 `reasoning_effort`**：此前校验了 maxTokens/tokenParameter/responseFormatMode，
  唯独漏了它，绕开编辑器的写入路径能把非法值送到线上。
- **修正三个探针的误报**（它们此前报出的问题并不存在）：
  - **关系图 front-matter「解析失败」**：探针用 JSON 解析器去解 YAML，正常文件必然失败；
    生成侧与真实解析侧本就一致（且对话侧根本不解析 front-matter，关系上下文从未丢失）。
    解析侧另补：失败留痕（区分 read_failed / no_front_matter / yaml_parse_failed / body_fallback_used）、
    正文兜底提取、兼容 **BOM / 开头空行 / CRLF** 的旧文件。
  - **「8/8 人物档案缺必需字段」**：探针要求的 `identity`/`personality` 在真实 schema 里不存在
    （真实字段是 `core_identity`/`core_traits`/`temperament_type`/`speech_style`）。
  - **「场景进度未写入」**：真实落点是会话 manifest 的 `state`（`state.scene/progression`），
    探针却在顶层找 `scene_progress`；张力是读时计算、本就不落盘的。
    这也解释了探针证据 `state_written=1` 却 `present=0` 的自相矛盾。
- **人设弧线阶段补留痕**：此前阶段抛错只写 `PlatformLog`，且清单已被标成 `stage=completed`，
  用户无从知道弧线只做了一部分。现在失败/未完成都记事件，并把 `progress.persona_arc`
  （status/已完成章数/失败章数/缺哪些角色）写进书卷 manifest。
- **弧线索引以磁盘为准**：此前只在收尾成功路径重算一次，进程被中途杀死就永远滞后
  （出现「arc.json 有 174 章、索引却写 has_arc=false」）。现在逐章每 5 章刷当前角色，
  阶段结束/抛错后无条件全量重算。
- **正常生命周期事件不再被当成「问题」**：`backend.ready`、`ondevice.*`、
  `dialogue.parse_retry_ok` 等此前会进问题清单（真机导出里 `backend.ready` 出现 17 次），
  现已从问题聚合中排除（仍保留在「最近事件 / 事件统计」里）。
- **模型配置探针不再依赖 `configured` 标志**（该标志的计算有缺陷、会误报），
  改为直接检查字段本身，并新增「**模型名与端点是否般配**」的检查。
- **诊断看门狗栈不再截掉应用帧**：此前 `stackTrace.take(24)` 取的是栈顶（框架帧），
  真正触发卡顿的应用调用点位于栈底、被裁掉了——这正是「定位不到谁触发的」的原因。
  现在保留最内层 24 帧 + 应用包名帧，并标注省略帧数。
- **诊断导出的 `model.profiles[]` 补上三个决定请求形状的字段**：
  `reasoning_effort` / `token_parameter` / `response_format_mode`（均非密钥）。

### 已知限制

- **每轮自动一致性审校尚未实现**（上游 Python 的 `update_monitor_state` 未移植）；
  目前只有用户显式点「深度审校」才写 `consistency_monitor`。探针已如实标注，未伪造写入。
- 知识台账按「披露句」正则过滤，**本次会话没有匹配句子时为空属正常**。
- **书卷标题缺失（1 个）** 属旧数据缺口：判据已接受 `novel_name` 等四个键，仍报缺说明四个键全空。
- **截图差分默认关闭**，阈值与采样率（500ms / 2fps）**需要真机校准**。
- 可访问性对比度检测**未做**（只做语义标签缺失与点击目标尺寸）。
- **无真机验证的部分**：帧指标、主线程长任务、重绘计数、布局跳动、闪烁采样、
  可访问性反射、崩溃上下文落盘、悬浮窗拖动——本版仅经编译 + JVM 单测 + APK 打包验证。

## [2.1.12] - 2026-10-13

### 新增

- **运行诊断与运行日志分成两页**，并给运行日志加上采集控制。
  - **运行日志页**：新增「持续采集」开关（启动/关闭采集，**状态持久化**，重启保持上次选择）；
    关闭**不清除**已采集内容；新增「清除旧日志」（二次确认，只清运行日志与崩溃/卡顿记录，
    不动书卷、会话、记忆、模型配置）。关闭瞬间会在日志里留一条标记行。
    **未捕获异常与卡顿始终记录**，不因采集关闭而丢失，并标注「采集已关闭期间（上下文不完整）」。
  - **运行诊断页**（独立新页）：概览（版本/机型/ABI/内存/后端自检/采集状态/当前会话）+
    启动自检（**真实填充**，此前是空对象 `{}`）+ 模型连通性（最近测试结果）+
    结构化事件统计 + **可读问题清单**（按类型筛选、可展开看完整堆栈）+ 复制摘要 + 导出 JSON。
  - **应用内悬浮窗**（开关在运行诊断页）：胶囊显示采集状态，浮在本应用界面之上、可拖动、
    点击跳运行日志页；采集关闭时显示红色「未采集」，读不到状态时不谎报。
    **不申请 `SYSTEM_ALERT_WINDOW`、不使用系统悬浮窗 API**。

### 修复

- **问题捕获从「只读 logcat」升级为三层**（logcat 会被轮转冲掉、崩溃后可能已拿不到）：
  - **进程内**：全局未捕获异常处理器（完整堆栈、**同步落盘到独立崩溃文件**、
    **链回原处理器不吞崩溃**）、协程未捕获异常（挂 5 个长生命周期 scope，记录后转发）、
    ANR 看门狗（10s 探测 / 5s 阈值 / 60s 冷却，dump 全线程栈）；
  - **进程外**：`ApplicationExitInfo`（API 30+）覆盖 **ANR / native 崩溃 / 被系统杀 /
    启动期崩溃** 的进程死因归因（API<30 有降级说明）；
  - **结构化事件**：关键路径上 10 个「静默失败」点补记账（蒸馏读写、会话读取、对话派生更新、
    书卷导入复制、模型能力探测等），只记录、不改控制流、不吞异常。
- **契约与文档**：新增 `GET /diagnostics/overview`、`GET|PUT /diagnostics/runtime-log/capture`、
  `DELETE /diagnostics/runtime-log`；`runtime-log` 与 `export` 增加 `run_id`/`session_id`；
  诊断报告 `schemaVersion` 3→4；`docs/server-api.md` 已同步。
- 诊断新增**现场元信息**：versionName + versionCode（复用构建期生成的单一来源，**未手写**）、
  机型 / ABI / 是否 64 位 / CPU 核数 / 堆与 native 内存 / 进程运行时长 / 当前 run·session。

### 已知限制（本版**未做**，下一版继续）

- **功能自检（健康探针）整块未做**：人设弧线覆盖率与「是否真被注入」、人物时间线推进的
  期望值 vs 实际值、会话重复 key 校验、关系图悬空引用、8 个内置插件动作、
  记忆体系（长期记忆 / 世界记忆 / 场景进度）、提示词加载完整性等。
- **画面与流畅度整块未做**：帧指标（掉帧率 / 最长帧）、过度重组与重绘计数、图片重复加载、
  布局跳动、截图差分「疑似闪烁」。**因此本版代码中没有任何像素采集路径。**
- 操作轨迹、多进程 / WebView 标注、诊断自身健康、可访问性、数据损坏审计未做。
- **「失败但不抛异常」的功能不会被自动抓到**：需下一版的通用上报 + 统一入口兜底机制。
- **无真机验证**：采集线程、状态文件持久化、ExitInfo 解析、ANR 看门狗、崩溃文件、
  悬浮窗拖动与层级，均只经「编译 + JVM 单测 + APK 打包」验证。

## [2.1.11] - 2026-10-13

### 变更

- **「本机模型（端侧）」从模型档案编辑页拆成独立页**：以前新增/编辑 API 档案时，
  表单中部会整块出现端侧配置（导入 .gguf、下载模型、上下文长度、线程数、启动、运行日志），
  看起来像是档案的一部分。
  - 端侧状态与轮询原先在模型档案编辑页的 ViewModel 里另存了一份，现已删除，
    统一由 `OnDeviceModelsViewModel` 持有；编辑页只剩档案表单、API Key、推理参数与能力检测。
  - 模型档案列表页顶部新增「本机模型（端侧）」入口（设置首页原有入口保留）；
    启动模型后在端侧页点「启用本机推理」，即创建/更新 `provider = local` 的档案并设为当前模型——
    原先编辑页里的「填入本机地址」由这个动作取代。

### 修复

- **修复角色名混入字面引号**（`"李慧英"` / `"陆城"`）。诊断里的形状是同一角色同时出现在允许与禁止名单：
  `allowed_responders: "李慧英"、"陆城"、旁白、场景提示`，而 `forbidden_responders: 陆城、作者`
  —— 带引号的 `"陆城"` 被判合法、干净的 `陆城` 被判非法，模型输出的正常名字于是被拒，
  反复 `dialogue.parse_failed` 重试，严重时两次都失败变成 `dialogue.stream_error`；
  界面上说话人标签也直接显示成 `"李慧英"`。
  - **根因**：会话创建、名单拼装、解析器、读取展示**四处都不做名字归一化**，
    只要上游（模型提取主角时会返回 `["\"李慧英\"", …]` 这种「字符串里再套一层引号」的元素）
    带进引号，就会一路存活到落盘与展示。
  - **修复**：新增 `CharacterNameNormalizer`（只剥**首尾成对**的引号/括号，
    单侧引号与名字中间的引号保留），写入口归一化；
    允许/禁止名单改为按**身份键**取差集，保证同一角色不会同时出现在两个名单里；
    解析器与流式链路共用同一身份键；读取侧归一化会话响应与消息分页的说话人，
    并**同步改名头像键**（先按原始名字注入再归一化，旧书卷头像不丢）。
  - 新增 12 + 4 个回归测试用例，其中含**实机形状**（`participants` 带引号、`controlled_character` 干净）
    下「两个名单身份不相交、且人物档案不丢」。

### 已知限制

- 只覆盖说话人/参与者这条线：`state.presence`、`scene_progress`、`event_signals`、`speaker_activity`、
  关系图键等**次要界面**在**旧数据**上仍可能显示带引号的名字（生成链路已安全）。
- `redistill` / `resumeDistill` **故意不归一化**旧名字：否则会在带引号的旧目录旁新建一个干净目录，
  同一角色被扫描成两条，比原 bug 更糟。需要时另行做数据迁移。

## [2.1.10] - 2026-10-13

### 修复

- **修复「对话闪退」与「对方重复说同一句」——同一个 bug**。
  线上崩溃：`IllegalArgumentException: Key "…|李慧英|…" was already used`（Compose 列表 key 重复）。
  - **根因**：向上加载历史消息时把拉到的一页**直接拼到现有列表前面、完全没有去重**；
    而分页位置是**发请求前**算的，往返期间列表一旦变化（新的一轮提交、流式完成、失败恢复刷新），
    这一页就会与已有内容重叠 → 同一条消息出现两次。
  - 一条消息出现两次会同时造成：**界面重复**（看起来像角色又把同一句说了一遍）与
    **直接闪退**（列表项 key 由 `turnId|说话人|时间戳|正文哈希` 组成，两条相同消息 key 也相同，
    Compose 在主线程抛异常）。崩溃时间点正好紧跟在一次历史消息请求之后。
  - **修复三层**：①渲染层按 key 去重（最后一道防线，任何上游重复都不再崩）；
    ②分页前插去重（按 turnId 去重页内，并把页里已有的从当前列表移除，从源头消除重叠）；
    ③采纳服务端整份列表时（失败恢复 / 刷新两条路径）兜底去重。
  - 新增 4 个回归测试并做对照实验：把分页前插还原成修复前的裸拼接后，
    `prepending an overlapping page does not duplicate items` 会失败，恢复后通过；
    断言同时校验「key 必须唯一」这一崩溃相关的不变量。

## [2.1.9] - 2026-10-13

### 新增

- **人物时间线随剧情推进**：每轮提交后服务端推导当前时间点并写回会话
  （`persona_chapter`），从会话起始章起每 4 轮推进一章。
  - 推进是**推导**而非累加：同一 `turn_count` 永远算出同一个值，
    因此断线重连、重试同一轮都不会重复推进（与 2.1.6 的 turnId 幂等叠加）；
  - 会话上方展示**当前时间点**（如「时间点：第 5 章」），
    可随时看到角色此刻按哪一章的身份与认知说话；
  - 不分章、或没有人设弧线时不展示，行为与之前一致。

## [2.1.8] - 2026-10-13

### 新增

- **人物身份与性格跟随时间线**：蒸馏额外产出**逐章人设弧线**
  （`artifacts/characters/<novelId>/<角色>/arc.json`），对话时**静态人设取人物档案、
  活跃状态取当前章那一段**，从第几章开局角色就是那个时间点的样子。
  - 每章记录该时间点的身份与处境、目标、信条、应激反应、关系阶段、喜恶，
    以及**该章结束前确实已经知道的事**（`known_facts`）；
  - 稳定特征（说话风格、外貌、习惯等）**不进弧线**，仍取人物档案；
  - 新增提示词 [`time_point_persona_rule`](prompts/dialogue/turn_system.yaml)：
    身份与关系阶段以当前时间点为准，`known_facts` 之外的事要表现为不知道，
    不得引用该时间点之后的事件，也不要预告未来走向；
  - 逐章生成（本机模型同样逐章）、逐章幂等落盘，可中断可续跑可单章重试；
  - 旧书卷没有 `arc.json` 时**完全退回原行为**，无需迁移。

## [2.1.7] - 2026-10-13

### 新增

- **导演「强制执行」**：导演与第四面墙弹窗新增开关
  「强制执行：角色必须落实，不得拒绝或推迟」，对**所有**导演动作
  （推进剧情 / 放慢情绪 / 加强冲突 / 切换视角 / 第四面墙）都生效。
  角色仍可保留情绪化表达（不满、抱怨、讲条件、带着怨气照做），但**结果必须发生**：
  不接受用闲聊、铺垫或泛泛接话把指令稀释掉，也不接受改写成别的走向；
  方案的 `beat` 必须写出「指令已经生效」的那一幕。
  默认关闭，不开启时行为与此前完全一致；旧客户端不发 `force_execute` 也不受影响。
  请求字段见 [`docs/server-api.md`](docs/server-api.md)。

### 仓库加固

- 新增提示词副本同步守卫 [`scripts/check-prompt-sync.sh`](scripts/check-prompt-sync.sh)：
  提示词在 `prompts/`、`kmp/server/src/androidMain/assets/`、
  `kmp/app/shared/.../composeResources/files/prompts/` 三处必须逐字节一致，
  只改一处会导致「改了提示词但运行时不生效」。已接入 CI 每次推送执行。

## [2.1.6] - 2026-10-13

### 修复

- **修复「插件一用就闪退」「剧情导演一用就闪退」**：两者同一个原因——
  `ChatToolsDelegate` 里的成员扩展 `ToolRequest.updateState` 体内**未限定**地调用了
  `updateState { ... }`，被解析到扩展**自身**而非同类属性 `updateState`，
  造成无限递归 → 主线程 `StackOverflowError` → 进程崩溃重启。
  实际上**所有工具流程**（插件动作、剧情导演、续写建议、场景切换、记忆保存、分支等）
  都会触发。已改为 `this@ChatToolsDelegate.updateState { ... }`。
  该缺陷来自初始基线导入，2.1.4 / 2.1.5 均受影响。
- **修复「同一句回复冒出多个气泡」（角色重复说话）**：服务端
  `DialogueService.commitTurn` 没有按 turnId 做幂等检查，同一轮会被追加两次。
  turnId 就是客户端传入的 operationId，而「重试上一次发送」会**复用**同一个 operationId：
  发送后连接中断、但服务端其实已提交时，重试会让同一轮再提交一次。
  客户端 `mergeTranscript` 只做 base 与 append 之间的去重，挡不住 append 自身的重复，
  于是两条都渲染出来（每重试一次多一份）。
  修复：`commitTurn` 在已有同 turnId 的「带内容助手回复」时直接返回既有 manifest，
  不再追加；`mergeTranscript` 补上 append 自身的按 turnId 去重（保留最后一条、保持顺序）。

### 仓库加固

- 新增源码守卫 [`scripts/check-kotlin-shadowing.sh`](scripts/check-kotlin-shadowing.sh)：
  扫描「成员扩展遮蔽同类属性并自调用」这一编译期无感知、运行期才崩的写法，
  已接入 CI（每次推送都跑），并用修复前的代码做过对照实验确认能命中。
- 两个修复都补了回归测试，并做了对照实验：禁用服务端幂等判断后
  `DialogueCommitIdempotencyTest` 会失败，恢复后通过。

## [2.1.5] - 2026-10-13

### 修复

- **修复应用内「版本信息」显示旧版本号**：2.1.4 的 APK 里 `versionName` 已是 2.1.4，
  但设置页仍显示 2.1.2。原因是应用内版本号来自 `ui/shared` 里一个**手写常量**
  `AppMetadata`（`VERSION_NAME=2.1.2`、`VERSION_CODE=11`），发版时只改了 Gradle
  版本号。桌面端更新检查也读该常量，因此一直误判自己落后于最新版。
- **修复 `/api/web/health` 上报写死的版本号**：该接口此前固定返回
  `"version":"0.1.0"`，与应用版本无关，排查时会误导。现在同样由
  `kmp/gradle.properties` 构建期生成。

### 变更

- **版本号收敛为唯一来源**：`kmp/gradle.properties` 的 `zaomeng.versionName` /
  `zaomeng.versionCode`。Android 的 `versionName`/`versionCode`、桌面打包版本、
  以及构建期生成的 `AppMetadata` 全部由它推导；手写常量文件已删除。
- `kmp/ui/shared` 新增 `generateAppMetadata` 任务生成 `AppMetadata`。
  只生成版本号、不生成构建时间——构建时间每次都会变，会让该任务永远不是
  UP-TO-DATE，导致 `ui/shared` 及其全部下游模块每次构建都重新编译。
- 设置页版本信息副标题去掉「构建于 …」。

## [2.1.4] - 2026-10-13

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

[2.1.14]: https://github.com/dz3197578117-lab/zaomeng/compare/v2.1.13...HEAD
[2.1.13]: https://github.com/dz3197578117-lab/zaomeng/compare/v2.1.12...v2.1.13
[2.1.12]: https://github.com/dz3197578117-lab/zaomeng/compare/v2.1.11...v2.1.12
[2.1.11]: https://github.com/dz3197578117-lab/zaomeng/compare/v2.1.10...v2.1.11
[2.1.10]: https://github.com/dz3197578117-lab/zaomeng/compare/v2.1.9...v2.1.10
[2.1.9]: https://github.com/dz3197578117-lab/zaomeng/compare/v2.1.8...v2.1.9
[2.1.8]: https://github.com/dz3197578117-lab/zaomeng/compare/v2.1.7...v2.1.8
[2.1.7]: https://github.com/dz3197578117-lab/zaomeng/compare/v2.1.6...v2.1.7
[2.1.6]: https://github.com/dz3197578117-lab/zaomeng/compare/v2.1.5...v2.1.6
[2.1.5]: https://github.com/dz3197578117-lab/zaomeng/compare/v2.1.4...v2.1.5
[2.1.4]: https://github.com/dz3197578117-lab/zaomeng/compare/v2.1.3...v2.1.4
[2.1.3]: https://github.com/dz3197578117-lab/zaomeng/compare/v2.1.2...v2.1.3
[2.1.2]: https://github.com/dz3197578117-lab/zaomeng/releases/tag/v2.1.2
