# Android 代码阅读地图

这份地图面向要亲自理解 Starglean Android 实现的作者。建议按“应用入口 → 四个主页面 → 领域与基础设施 → 测试和构建变体”的顺序阅读；先看数据怎样流动，再深入单个视觉组件。

## 1. 从应用入口开始

1. [`MainActivity.kt`](../app/src/main/app/MainActivity.kt) 是 Android Activity 入口。它只在 `DEBUG` 构建中解析 `demo_*`、`start_destination` 等预览参数；普通启动会创建默认的 `DemoLaunch`。
2. [`StargleanApp.kt`](../app/src/main/app/StargleanApp.kt) 先套上访问闸门，再创建四页主导航、底栏、页面状态保存和回应通知观察。这里最适合看清四个 `AppDestination` 怎样映射到页面。
3. [`AppEntryGate.kt`](../app/src/main/app/ui/screens/AppEntryGate.kt) 决定主界面能否被组合：加载或复核登录状态、处理离线已验证会话，并为当前账户建立隔离的本地存储上下文。阅读时同时打开：
   - [`OnlineRuntime.kt`](../app/src/main/app/core/online/OnlineRuntime.kt)：在线能力的进程内组合根。
   - [`AppAccessController.kt`](../app/src/main/app/core/account/AppAccessController.kt)：访问状态机。
   - [`LegacyLibraryClaimStore.kt`](../app/src/main/app/core/storage/LegacyLibraryClaimStore.kt)：旧设备资料认领或创建独立资料库。
   - [`AccountStorageContext.kt`](../app/src/main/app/core/storage/AccountStorageContext.kt)：隔离当前账户的文件目录；SharedPreferences 不会自动隔离，使用方必须显式带账户标识。
4. [`AppLocationGate.kt`](../app/src/main/app/ui/screens/AppLocationGate.kt) 在登录并确定资料归属后，统一解释定位用途、请求精确前台权限；通过 `LocalAppLocationAccess` 提供授权状态和设置入口。选择按账户保存，不保存坐标。Find、Write、罗盘和活动检查不再各自请求系统定位权限；天气与附近定位只在对应页面处于前台且已授权时运行。

## 2. 四个主页面的真实功能

### 首页：Planet

- [`PlanetScreen.kt`](../app/src/main/app/ui/screens/PlanetScreen.kt) 是首页组合与交互入口；0.4.4 移除了首页的 My entries / My constellations 快捷入口，私人日记和星座管理仍在第四页。
- [`CelestialScene.kt`](../app/src/main/app/ui/space/CelestialScene.kt)、[`CelestialRenderer.kt`](../app/src/main/app/ui/space/CelestialRenderer.kt) 负责星空场景和绘制；[`CelestialGeometry.kt`](../app/src/main/app/ui/space/CelestialGeometry.kt) 放几何计算。
- `ui/space` 中的 `HomeSkySnapshot`、`ConstellationFilaments` 等文件承载首页快照和各星座通用粒子效果。天鹅座也使用通用实现，不再保留三份旧专用代码。

### 发现：真实 Nearby 流程

- [`DiscoverScreen.kt`](../app/src/main/app/ui/screens/DiscoverScreen.kt) 是变体分流层：`release`/本机版进入真实流程，debug 预览仍可进入旧样例界面。
- [`LiveDiscoverScreen.kt`](../app/src/main/app/ui/screens/LiveDiscoverScreen.kt) 是真实发现页 UI。
- [`LiveDiscoveryController.kt`](../app/src/main/app/ui/screens/LiveDiscoveryController.kt) 协调定位、移动状态、发现会话、阅读资格和回应提交。
- 继续向下追踪时，先看 [`NearbyReaderLocationSource.kt`](../app/src/main/app/core/discovery/NearbyReaderLocationSource.kt)、[`NearbyReadingGate.kt`](../app/src/main/app/core/discovery/NearbyReadingGate.kt)，再看 [`NearbyRepository.kt`](../app/src/main/app/core/network/NearbyRepository.kt) 与 [`SupabaseNearbyClient.kt`](../app/src/main/app/core/network/SupabaseNearbyClient.kt)。

### 写作：Write 与草稿

- [`WriteScreen.kt`](../app/src/main/app/ui/screens/WriteScreen.kt) 组合编辑、天气、地点和照片；真实日记进入 [`OnlineSaveSheet.kt`](../app/src/main/app/ui/screens/OnlineSaveSheet.kt) 的单页预发布流程，底部固定确认按钮。确认保存后进入 [`PublishedStarPage.kt`](../app/src/main/app/ui/components/PublishedStarPage.kt) 点亮页：有可用当前星座时自动放置，无当前或已满时选择星座。
- [`WritingDraftState.kt`](../app/src/main/app/ui/screens/WritingDraftState.kt) 管理页面级草稿会话；[`WritingWeatherState.kt`](../app/src/main/app/ui/screens/WritingWeatherState.kt) 复用统一位置授权并管理天气加载状态；[`WritingPhotoControls.kt`](../app/src/main/app/ui/screens/WritingPhotoControls.kt) 管理相机和照片入口。
- `core/draft` 中 [`WritingDraft.kt`](../app/src/main/app/core/draft/WritingDraft.kt)、[`WritingDraftCodec.kt`](../app/src/main/app/core/draft/WritingDraftCodec.kt)、[`WritingDraftStore.kt`](../app/src/main/app/core/draft/WritingDraftStore.kt) 分别定义规则、序列化和持久化。
- 保存后的私人内容进入 [`OwnDiaryStore.kt`](../app/src/main/app/core/memory/OwnDiaryStore.kt)；公开发布由 [`PublicationCoordinator.kt`](../app/src/main/app/core/online/PublicationCoordinator.kt) 协调。

### 第四页：本地星座收藏

- [`ConstellationsScreen.kt`](../app/src/main/app/ui/screens/ConstellationsScreen.kt) 是画廊与真实本地集合的入口；debug 和 release 都是手机构建，真实集合由 [`LocalConstellationsScreen.kt`](../app/src/main/app/ui/screens/LocalConstellationsScreen.kt) 承载。
- [`ConstellationStore.kt`](../app/src/main/app/core/stars/ConstellationStore.kt) 持久化收藏；[`StarCollectionState.kt`](../app/src/main/app/core/stars/StarCollectionState.kt) 定义星座、星点和日记放置规则；[`StarCollectionCodec.kt`](../app/src/main/app/core/stars/StarCollectionCodec.kt) 负责编码。
- [`OwnDiariesSheet.kt`](../app/src/main/app/ui/screens/OwnDiariesSheet.kt) 提供私人日记选择；[`MemoryCompassCard.kt`](../app/src/main/app/ui/components/MemoryCompassCard.kt) 与 `core/memory` 提供记忆罗盘。
- 云端图库和跨设备星座同步属于另一层能力，可从 [`CloudLibraryScreen.kt`](../app/src/main/app/ui/screens/CloudLibraryScreen.kt) 和 [`ConstellationCloudController.kt`](../app/src/main/app/core/online/ConstellationCloudController.kt) 继续阅读。
- 第四页顶部以 Sky / Entries 分页，Entries 提供私人日记、回应、未放置日记与云端入口；星座菜单保留星座管理。设置移至首页齿轮，统一进入 [`SettingsScreen.kt`](../app/src/main/app/ui/screens/SettingsScreen.kt)；[`BackupScreen.kt`](../app/src/main/app/ui/screens/BackupScreen.kt) 负责加密备份与导入确认，[`FeedbackSettingsScreen.kt`](../app/src/main/app/ui/screens/FeedbackSettingsScreen.kt) 负责独立声音/震动开关及试播。私人日记支持标题/正文搜索和月份筛选。

## 3. `core` 包职责

| 包 | 职责 |
| --- | --- |
| `core/account` | 登录、会话、安全存储、认证传输和应用访问闸门。 |
| `core/backup` | 分块认证加密、本机日记与照片导出、完整验证后导入、临时文件清理。备份不包含登录凭据或星座布局。 |
| `core/design` | Compose 主题、颜色和系统动效偏好。 |
| `core/discovery` | Nearby 阅读资格、相遇配额、移动状态与读者定位质量。 |
| `core/draft` | 写作草稿模型、规则、编解码、冲突保护与持久化；DraftRecovery 在保留原始文件和可用照片后，按用户选择恢复草稿。 |
| `core/feedback` | 发现日记时的有限星光音效与震动；账户独立偏好、真实试播、本地信号合成、系统静音/通话策略和生命周期取消。 |
| `core/io` | 对网络或文件输入执行有上限的读取。 |
| `core/library` | 日记删除事务以及跨日记、照片、星点存储的协调。 |
| `core/location` | 手机前台定位、授权与定位可靠性校验。 |
| `core/media` | 日记照片模型、相机缓存、导入租约、存储和孤立照片清理。 |
| `core/memory` | 私人日记库、编解码、记忆位置、朝向和罗盘计算。 |
| `core/model` | 页面共享的日记、天气、导航、星座领域模型及 debug 样例。 |
| `core/network` | Nearby/Supabase 数据协议、HTTP 客户端、仓库和发现会话状态机。 |
| `core/online` | 在线运行时、公开发布、云图库、云星座同步和回应通知。 |
| `core/sensor` | 有意摇动识别与位置移动窗口判断。 |
| `core/stars` | 本地星座收藏状态、星点放置规则、编解码和存储。 |
| `core/storage` | 按账户隔离本地存储，并安全处理旧版未分账户资料。 |
| `core/weather` | 天气查询位置、Android 地名、Open-Meteo 响应和当前天气规则。 |

UI 代码另分为：`ui/screens`（页面和流程）、`ui/components`（可复用视觉与交互）、`ui/space`（星空几何、渲染和动效）。

## 4. 资源、隐私文本与测试现状

- [`app/build.gradle.kts`](../app/build.gradle.kts) 中的 `PrepareArtwork` 会读取 `src/main/res/apparition-pic`、`home-pic`、`app-icon` 的作者原图，并复制为生成的 `drawable-nodpi`。这些目录是任务输入，不要额外登记成 Android `res` 根；正式资源根仍是 `src/main/res`。
- `res/values` 和 `res/values-v31` 定义基础主题与 Android 12+ 启动画面；`res/xml` 包含备份、数据提取和相机 `FileProvider` 规则。
- [`privacy-notice.json`](../app/src/main/privacyPolicy/privacy-notice.json) 是隐私详情页读取的结构化正文，不应当作未引用素材删除。
- [`src/test`](../app/src/test) 是手机版 JVM 单元测试，[`src/androidTest`](../app/src/androidTest) 是手机版设备 / 模拟器测试。desktop 专用测试没有恢复；历史验收结果只在 `delivery/` 留档，不代表本轮源码已测试通过。

## 5. 两种手机 build type

定义见 [`app/build.gradle.kts`](../app/build.gradle.kts)：

- `debug`：手机调试构建。仍可能保留受控预览入口，但不能把整个变体当作 desktop 或纯演示应用。
- `release`：手机发布构建，使用真实登录、公开发布、附近阅读、本地收藏和云图库流程。

生产 Kotlin 源码保留在非标准路径 `src/main/app`，由构建脚本显式登记；隐私 JSON 位于 `src/main/privacyPolicy`，登记为 assets。旧 desktop source set 与 Mac 桥接已删除。

## 6. 不要按名字清理旧 demo

`DemoLaunch`、`DemoContent`、`GallerySamples`、`DiscoverScreen` 中的样例分支以及若干 `*Preview` 函数可能仍被 debug 预览路径使用。删除前至少逐项确认 `MainActivity` intent 参数、`StargleanApp` 路由和生产调用链，并验证 debug、release 两种手机构建；不要只凭“demo”“preview”“sample”文件名判断。
