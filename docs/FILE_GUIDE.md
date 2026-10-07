# 拾星：每个文件是做什么的

核对日期：2026-09-28。说明对象是当前磁盘上的工程，不只限于已提交到 Git 的文件。

这份手册解释当前源码、配置、测试、素材和文档的用途，并另列本机 IDE 配置、安装包与自动生成文件。为方便对照代码，分为本页和三个章节。文档中的测试说明只表示覆盖意图，不等于本轮已经运行或通过。

## 1. 先从这几个文件理解整个 App

| 你想理解的内容 | 先看哪里 | 用容易理解的话说 |
| --- | --- | --- |
| App 从哪里启动 | [MainActivity.kt](../app/src/main/app/MainActivity.kt) | Android 打开应用时进入的地方，创建 Compose 界面。 |
| 登录、首页和底部四个按钮怎样串起来 | [StargleanApp.kt](../app/src/main/app/StargleanApp.kt) | 应用外壳，组织入口、导航、页面与共享状态。 |
| 每个页面与动画 | [界面逐文件说明](file-guide/ANDROID_UI.md) | 四个主页面、登录和账户、天气背景、星球、星座与发现动画。 |
| 页面背后的实际功能 | [业务逻辑逐文件说明](file-guide/ANDROID_CORE.md) | 日记存储、账号、位置、天气、200 米阅读、罗盘、震动音效与云端协议。 |
| 后端与维护工具 | [后端与工具逐文件说明](file-guide/BACKEND_TOOLS_WEB.md) | 数据库迁移、私有照片接口、举报 CLI 和拒绝面检查。 |
| 版本、依赖、打包方式 | [app/build.gradle.kts](../app/build.gradle.kts) | 决定 debug/release 手机构建，以及如何登记源码、隐私 assets 和图片生成任务。 |

建议按“入口 → 感兴趣的页面 → 页面调用的 core 类 → 对应测试”阅读，不必一开始就从几千行 SQL 或构建缓存读起。`ui` 主要负责如何显示与交互；`core` 主要负责数据、规则、设备能力与联网；服务端仍独立执行访问控制，不能只靠 UI 隐藏按钮保证安全。

### 两种构建都面向 Android 手机

同一个工程可以编译成不同版本，不是每份代码都出现在正式手机界面上。

| 构建类型 | 安装身份 / 名称 | 用途与区别 |
| --- | --- | --- |
| `debug` | `com.starglean.app` / Starglean Preview | 手机调试版；可保留受控预览分支，但实际生产源码和手机功能必须可构建。 |
| `release` | `com.starglean.app.local` / Starglean | 手机发布版，使用真实账号和真实附近阅读流程。`.local` 是沿用的包名后缀，不表示只能离线使用。 |

旧 `desktop` source set 和 Mac 定位助手已删除。`LOCAL_DEVICE_EDITION` 等旧命名不能仅凭名字推断行为，仍应看实际分支和调用链。

## 2. 工程根目录与构建文件

| 文件 | 具体职责 | 什么时候需要看 |
| --- | --- | --- |
| [README.md](../README.md) | 整个项目的入口说明：目录、阅读顺序、构建命令、安装包与注意事项。 | 第一次打开工程先读。 |
| [.editorconfig](../.editorconfig) | 约定编码、缩进、换行和末尾空白规则；Kotlin 默认 4 空格，网页相关文件 2 空格。 | 文件排版不统一时；它不改变应用功能。 |
| [.gitignore](../.gitignore) | 告诉 Git 不收录构建缓存、本机设置、密钥和大部分交付产物等。 | 判断文件为何没出现在 Git 中；“被忽略”不等于“无用”，签名文件就是反例。 |
| [settings.gradle.kts](../settings.gradle.kts) | 定义工程名 `Starglean`、唯一 Android 模块 `:app`、插件与依赖仓库、JDK 工具链解析插件。 | Android Studio 导入或依赖仓库出问题时。 |
| [build.gradle.kts](../build.gradle.kts) | 声明全工程共用的 Android Gradle Plugin 和 Kotlin Compose 插件版本。 | 升级构建工具时；不是界面代码。 |
| [app/build.gradle.kts](../app/build.gradle.kts) | Android 模块核心构建配置：应用 ID、SDK、版本、依赖、签名来源、debug/release 手机构建、非标准 Kotlin/assets 路径和素材生成任务。 | 打包、改版本、加库或排查源码/资源是否入包时。签名私钥仍从项目外配置读取。 |
| [gradle.properties](../gradle.properties) | 配置 Gradle 内存、并行构建、AndroidX 与 Kotlin 风格等通用选项。 | 构建性能与兼容性设置；不是本机 SDK 路径文件。 |
| [gradle/gradle-daemon-jvm.properties](../gradle/gradle-daemon-jvm.properties) | 指定 Gradle 守护进程的 JVM 工具链与平台下载信息，当前为 JetBrains JDK 21。 | 构建用的 Java 运行环境问题；不同于本项目 Java 编译目标 17，也不同于 Android SDK 版本。 |
| [gradle/wrapper/gradle-wrapper.properties](../gradle/wrapper/gradle-wrapper.properties) | 固定 Gradle 分发版本（当前 9.5.0）、下载地址、超时和本机缓存位置。 | 保证不同电脑使用相同构建工具版本。 |
| [gradle/wrapper/gradle-wrapper.jar](../gradle/wrapper/gradle-wrapper.jar) | Gradle Wrapper 启动程序的二进制文件，负责加载指定 Gradle。 | `gradlew` 依赖它；不是可随意删除的编译垃圾，也不是可直接阅读的 Kotlin 源码。 |
| [gradlew](../gradlew) | macOS / Linux 使用的 Gradle 启动脚本。 | 终端运行 `./gradlew …` 时使用。 |
| [gradlew.bat](../gradlew.bat) | Windows 使用的同一套 Gradle 启动脚本。 | 在 Windows 构建时使用；Mac 不用不代表项目不需要。 |

## 3. Android 系统配置与隐私内容

| 文件 | 具体职责 | 谁使用 |
| --- | --- | --- |
| [app/src/main/AndroidManifest.xml](../app/src/main/AndroidManifest.xml) | 向 Android 声明应用入口、图标、主题、网络/前台定位/震动权限、非必需的硬件能力、相机临时文件共享及禁用备份。没有申请后台定位。 | Android 系统与构建工具；权限声明本身不等于用户已经授权。 |
| [app/src/main/privacyPolicy/privacy-notice.json](../app/src/main/privacyPolicy/privacy-notice.json) | 隐私说明正文的单一来源：账号、定位、天气、照片、公开阅读、举报、删除及额度说明；构建脚本把该目录登记为 assets。 | App 隐私页读取。文字需要与实际功能保持一致，但 JSON 不是权限执行代码。 |
| [app/src/main/res/values/themes.xml](../app/src/main/res/values/themes.xml) | Android 窗口基础主题：深色背景、状态栏/导航栏、无系统标题栏等。 | Activity 和启动窗口；Compose 内部按钮/文字配色另在 `ui/theme/`。 |
| [app/src/main/res/values-v31/splash-theme.xml](../app/src/main/res/values-v31/splash-theme.xml) | Android 12 / API 31 及以上的系统启动屏背景和图标。 | 支持该系统版本的启动画面；不是首页星球动画。 |
| [app/src/main/res/xml/backup_rules.xml](../app/src/main/res/xml/backup_rules.xml) | 旧版 Android 完整备份规则，排除文件、数据库、偏好设置等应用数据。 | 与 Manifest 的禁用备份配合，避免私有日记和会话进入系统备份。 |
| [app/src/main/res/xml/data_extraction_rules.xml](../app/src/main/res/xml/data_extraction_rules.xml) | 新版 Android 云备份与设备迁移的排除规则。 | Android 系统读取；不是应用自己的云端日记同步规则。 |
| [app/src/main/res/xml/camera_paths.xml](../app/src/main/res/xml/camera_paths.xml) | 定义 FileProvider 可以临时分享的缓存目录，包括拍照目录与按账号隔离的缓存目录。 | 拍照时向系统相机授予限定 URI 访问，不公开整个文件系统。 |

## 4. 图片素材：每张图分别负责什么

这些原图目录名不是标准 Android drawable 目录，但并非放错了。`app/build.gradle.kts` 中的 `PrepareArtwork` 会把它们复制到生成的 `drawable-nodpi`，保留原图并建立对应 `R.drawable` 标识。它们是任务输入，不要再登记成额外 `res` 根，也不要直接修改生成目录。

| 文件 | 画面内容与用途 | 直接使用它的代码 |
| --- | --- | --- |
| [app-icon/icon.png](../app/src/main/res/app-icon/icon.png) | 应用图标原图，构建时命名为 `ic_starglean`。 | 主 Manifest 和 Android 12+ 启动屏。 |
| [home-pic/home_planet.png](../app/src/main/res/home-pic/home_planet.png) | 首页星球的原始视觉素材。动态、深度和叠加效果不只由这张图决定。 | `ui/space/CelestialRenderer.kt`。 |
| [apparition-pic/cassiopeia_apparition.png](../app/src/main/res/apparition-pic/cassiopeia_apparition.png) | 仙后座的虚影插画，让星点形状对应可辨认的星座意象。 | `ui/components/GalleryConstellationScene.kt` 中 Cassiopeia。 |
| [apparition-pic/cygnus_apparition.png](../app/src/main/res/apparition-pic/cygnus_apparition.png) | 天鹅座虚影插画。 | 同上，Cygnus。 |
| [apparition-pic/delphinus_apparition.png](../app/src/main/res/apparition-pic/delphinus_apparition.png) | 海豚座虚影插画。 | 同上，Delphinus。 |
| [apparition-pic/leo_apparition.png](../app/src/main/res/apparition-pic/leo_apparition.png) | 狮子座虚影插画。 | 同上，Leo。 |
| [apparition-pic/lyra_apparition.png](../app/src/main/res/apparition-pic/lyra_apparition.png) | 天琴座虚影插画。 | 同上，Lyra。 |
| [apparition-pic/orion_apparition.png](../app/src/main/res/apparition-pic/orion_apparition.png) | 猎户座虚影插画。 | 同上，Orion。 |
| [apparition-pic/pegasus_apparition.png](../app/src/main/res/apparition-pic/pegasus_apparition.png) | 飞马座虚影插画。 | 同上，Pegasus。 |
| [apparition-pic/scorpius_apparition.png](../app/src/main/res/apparition-pic/scorpius_apparition.png) | 天蝎座虚影插画。 | 同上，Scorpius。 |

虚影图片、星点坐标、连接线、粒子和动画是不同部分：换 PNG 只换插画；要调整“星星是否突出、线条是否柔和、虚影怎样呼吸”，还要看界面章节的星座场景代码。

## 5. 文档文件

| 文件 | 具体职责 | 阅读注意 |
| --- | --- | --- |
| [docs/CODE_MAP.md](CODE_MAP.md) | 精简代码地图，按页面和业务模块概括调用关系。 | 适合快速查“这个功能主要在哪”；本手册进一步展开到每个文件。 |
| [docs/IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) | 当前实现状态、修复历史、验证范围与仍需真机/运营验收的部分。 | 判断“是否全部做完”时读它；文件存在不等于功能已经实机验收。 |
| [docs/CLEANUP_REPORT.md](CLEANUP_REPORT.md) | 历史清理的范围、移动/恢复方式及当时测试结果。 | 是整理记录，不是本轮又删除了这些文件，也不是永久有效的当前测试结果。 |
| [docs/requirements/README.md](requirements/README.md) | 解释原始需求文件的来源和地位，区分初始设想与后来确认的变化。 | 原始需求只作参考，不能覆盖后续已经明确的产品决定。 |
| [拾星_Android_AI开发总提示词_2026-09-12.md](requirements/拾星_Android_AI开发总提示词_2026-09-12.md) | 最初的产品/视觉/功能需求存档。 | 用来回溯目标；不是每一条都代表当前版本的最终方案。 |
| [docs/FILE_GUIDE.md](FILE_GUIDE.md) | 你正在看的逐文件手册总入口，以及构建、素材、文档和生成产物说明。 | 本轮新增。 |
| [docs/file-guide/ANDROID_UI.md](file-guide/ANDROID_UI.md) | 应用入口、页面、组件、图形渲染和当前手机版测试说明。 | 按界面模块阅读。 |
| [docs/file-guide/ANDROID_CORE.md](file-guide/ANDROID_CORE.md) | Android 核心逻辑及当前手机版测试说明。 | 按功能模块阅读。 |
| [docs/file-guide/BACKEND_TOOLS_WEB.md](file-guide/BACKEND_TOOLS_WEB.md) | 后端、举报工具与 Supabase 检查工具逐文件表。 | 数据库迁移逐个说明；已删除工具不再作为现行入口。 |
| [delivery/README.md](../delivery/README.md) | 本机保留的历史安装包与验证证据索引。 | 安装包归档不等于当前源码，也不证明本轮已验收。 |

## 6. 本机配置：不属于产品功能，但也不应乱删

下面是当前工作区的本机文件，不计入上面的受维护文件。没有把配置中的个人状态、令牌或签名内容复制到文档中。

| 文件 | 用途 |
| --- | --- |
| [local.properties](../local.properties) | 本机 Android SDK 路径等环境配置。换电脑需重新设置，不应上传为所有人共用配置。 |
| [.idea/.name](../.idea/.name) | Android Studio / IntelliJ 显示的项目名称。 |
| [.idea/.gitignore](../.idea/.gitignore) | IDE 目录内部的忽略规则，例如个人 workspace 和 shelved changes。 |
| [.idea/AndroidProjectSystem.xml](../.idea/AndroidProjectSystem.xml) | 指明使用 Android 的 Gradle 项目系统。 |
| [.idea/compiler.xml](../.idea/compiler.xml) | IDE 编译器设置；真正的 Android 构建配置仍以 Gradle 为准，不要只改这里来改变 APK 的 Java 目标。 |
| [.idea/gradle.xml](../.idea/gradle.xml) | IDE 关联的 Gradle 根目录、模块和测试运行器选择。 |
| [.idea/misc.xml](../.idea/misc.xml) | IDE 项目类型、JDK 与语言级别等通用元信息。 |
| [.idea/migrations.xml](../.idea/migrations.xml) | Android Studio 已执行的项目配置迁移记录；与 Supabase 数据库迁移没有关系。 |
| [.idea/runConfigurations.xml](../.idea/runConfigurations.xml) | 调整 IDE 自动生成测试运行配置的方式，并非正式应用页面。 |
| [.idea/deploymentTargetSelector.xml](../.idea/deploymentTargetSelector.xml) | 上次选择的部署设备/模拟器。选错设备可检查这里或在 IDE 中重新选择。 |
| [.idea/deviceManager.xml](../.idea/deviceManager.xml) | 设备管理器列表显示与排序设置。 |
| [.idea/caches/deviceStreaming.xml](../.idea/caches/deviceStreaming.xml) | Android Studio 云设备目录/选择信息的缓存，不代表你已经连接或测试了这些手机。 |
| [.idea/markdown.xml](../.idea/markdown.xml) | Markdown 预览面板设置。 |
| [.idea/vcs.xml](../.idea/vcs.xml) | IDE 与 Git 仓库的目录映射；本项目映射到上级仓库。 |
| [.idea/workspace.xml](../.idea/workspace.xml) | 个人工作状态，例如窗口、编辑器、运行目标、任务和改动列表。会频繁变化，不属于应用源码。 |

签名私钥和签名属性存放在工程之外，因此不在这份工程文件表中。它们不属于“可重新编译恢复”的东西，不能按缓存删除；文档也不会记录具体秘密值。

## 7. 自动生成文件与本机交付产物

Android Studio 能展示的文件远多于你需要手写维护的源码。下面按生成规则解释，不逐项列出每次构建都会变化的大量 `.class`、`.dex` 和缓存索引。

| 路径 / 常见文件 | 是什么 | 如何对待 |
| --- | --- | --- |
| `app/build/generated/` | 自动生成代码、构建常量、资源标识以及 artwork 复制后的图片。 | 不直接修改。`BuildConfig`、`R` 等类没有对应的手写业务文件。 |
| `app/build/intermediates/` | 合并 Manifest/资源、编译类、Dex、打包等中间产物。 | 出问题时可看诊断；不是源代码。 |
| `app/build/outputs/apk/` | 本次构建出来的 APK 和 `output-metadata.json`。 | APK 是可安装二进制，JSON 是版本/输出信息。构建成功并不自动等于已验收可发布。 |
| `app/build/outputs/androidTest-results/`、`app/build/test-results/` | 设备测试/JVM 测试机器可读结果，例如 XML。 | 保存需要的证据后可以重新运行生成；不能当作业务数据。 |
| `app/build/reports/` | 人可以阅读的 HTML/文本测试和 lint 报告。 | 用来理解失败、警告和测试覆盖，不在这里修改功能。 |
| `app/build/tmp/`、其他 `app/build/` 子目录 | 编译器工作文件、临时文件及特定插件产物。 | 通常可由构建重建；删之前确认没有唯一 APK 或未归档证据。 |
| 根目录 `build/` | 根工程生成的报告和输出。 | 与 `app/build/` 一样，不是应用源码。 |
| `.gradle/` | Gradle 本机项目状态、缓存、锁与增量构建信息。 | 可重建，但清理会增加下次构建耗时；构建进行中不要删除。 |
| `.kotlin/` | Kotlin 构建会话/编译缓存等。 | 可重建，不是 `.kt` 源码所在目录。 |
| `.DS_Store`（Finder 可能生成） | macOS 文件夹显示设置。 | 不参与 App 编译。 |
| `delivery/<版本>/app-release.apk` | 已归档的手机安装包。 | 不是缓存；按版本保留便于比对。当前包见下表。 |
| `delivery/<版本>/verification/` | 当时保留的测试 XML/HTML、日志、截图、签名/摘要和可能的音效样本。 | 历史证据，不保证与之后修改过的代码一致；不能仅因“不是源码”就删除。 |

### 当前交付文件的位置

| 文件 / 索引 | 用途与状态 |
| --- | --- |
| [delivery/0.4.4/app-release.apk](../delivery/0.4.4/app-release.apk) | 当前归档手机版 0.4.4-en / versionCode 13。 |
| [delivery/README.md](../delivery/README.md) | 历史包与验证目录索引；内容保持为当时记录。 |

## 8. 看后缀就能先判断它是哪一类

| 后缀 | 在这个项目中通常意味着什么 |
| --- | --- |
| `.kt` | Kotlin：Android 页面、业务逻辑或测试。根据所在的 main/test/androidTest 目录区分。 |
| `.gradle.kts` | 用 Kotlin 语法编写的构建配置，不是手机运行时业务代码。 |
| `.xml` | Android 系统/资源规则、IDE 配置，或自动生成的测试结果；必须结合目录判断。 |
| `.png` | 图片素材或验证截图。图片放在素材目录和 evidence/verification 目录中的作用不同。 |
| `.sql` | 数据库迁移、服务端规则或数据库测试。 |
| `.ts` | 后端 Edge Function 与 Deno 测试源码。 |
| `.mjs`、`.js` | Node 维护或检查脚本，具体取决于目录。 |
| `.html`、`.css` | 邮件模板，或生成的可视化测试报告。 |
| `.json`、`.properties` | 结构化数据与配置；并非全部安全可公开，按实际内容区分。 |
| `.sh`、`.rb`、`.bat` | 构建、启动或配置脚本，运行前看用途与授权范围。 |
| `.md` | 可以直接阅读的说明文档。 |
| `.jar`、`.apk` | 二进制包；本项目分别包括 Gradle 启动组件和 Android 安装包，不能仅按后缀判断是否可删除。 |

这份手册回答的是“文件负责什么”，不是“每一行代码都已经审计过”或“全部功能都在你的手机通过验收”。需要理解某个细节时，先定位到表中的文件，再沿它调用的类与对应测试继续读。
