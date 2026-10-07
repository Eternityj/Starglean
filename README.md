# 拾星 · Starglean

Android 星光日记应用。先读本页了解工程，再按 [代码地图](docs/CODE_MAP.md) 阅读代码；功能完成度与尚未验收的部分见 [实现状态](docs/IMPLEMENTATION_STATUS.md)。

当前源码版本为 `0.5.4`（versionCode 23，Release versionName `0.5.4-en`），目标是 Android 手机。本轮安装包为 `delivery/0.5.4/Starglean-0.5.4.apk`；旧版本目录保留为历史归档。本轮改动及验证范围见 [交付验证](delivery/0.5.4/verification/README.md)；整体功能状态仍见 [实现状态](docs/IMPLEMENTATION_STATUS.md)。

## 从哪里开始

- **看界面代码**：[四个页面对应文件](docs/CODE_MAP.md#2-四个主页面的真实功能)。
- **看全部业务模块**：[core 包职责](docs/CODE_MAP.md)。
- **安卓手机安装包**：[0.5.4 APK](delivery/0.5.4/Starglean-0.5.4.apk)。包名及签名与 0.5.3 相同，可直接覆盖安装，不需要先卸载。
- **了解这次整理**：[清理记录与恢复方法](docs/CLEANUP_REPORT.md)。

## 目录说明

```text
mobile app/
├── app/                 安卓应用
│   ├── src/main/app/    Kotlin 生产源码（由构建脚本显式登记）
│   ├── src/main/res/    Android 资源及素材生成任务的图片输入
│   ├── src/main/privacyPolicy/ 隐私 JSON（由构建脚本登记为 assets）
│   ├── src/test/        手机版 JVM 单元测试
│   ├── src/androidTest/ 手机版设备 / 模拟器测试
│   └── build.gradle.kts 安卓版本、debug/release 手机构建、依赖和资源生成规则
├── docs/                中文代码地图、当前状态、清理记录、原始需求
├── supabase/            后端迁移、接口、回归测试及运维说明
├── tools/               Supabase 拒绝面检查与举报处理工具
├── delivery/            仅本机保留的已审核安装包和测试证据
├── gradle/wrapper/      固定 Gradle 版本的启动组件，不能当缓存删
├── build.gradle.kts     全工程插件版本
├── settings.gradle.kts  工程名称、模块和依赖仓库
├── gradle.properties    通用构建选项，不放本机 SDK 路径
├── gradlew / gradlew.bat macOS/Linux 与 Windows 构建入口
└── local.properties     本机 Android SDK 路径，不进 Git
```

`app/build/`、根目录 `build/`、`.gradle/` 和 `.kotlin/` 是运行构建后重新生成的目录；整理后可能暂时不存在，重新出现是正常的。`.idea/` 是现有 Android Studio 工作区设置，本次保留，不影响源码阅读。

当前目标只有 Android 手机及必要后端。旧 `desktop` source set、Mac 定位助手、`tools/qa/` 和 `website/` 已不在当前工程中；历史文档中提到它们时，只表示过去的验收背景，不是可执行入口。debug 与 release 都是手机版，不能再按旧文档把 debug 当纯演示版或把 desktop 当现存变体。

## 阅读顺序

1. `MainActivity.kt` → `StargleanApp.kt` → `AppEntryGate.kt` → `AppLocationGate.kt`：启动、导航、登录、账户资料归属和统一前台定位授权。
2. `ui/screens/`：先看你想修改的页面，再看它用到的 `ui/components/` 和 `ui/space/`。
3. `core/`：理解页面背后的存储、天气、定位、传感器与联网规则。
4. `src/test/` 和 `src/androidTest/`：查看手机版规则、存储、权限和界面回归覆盖。
5. [后端目录说明](supabase/README.md)、[举报工具](tools/operator/README.md)和 [Supabase 检查工具](tools/supabase-check/README.md)：理解客户端之外仍保留的部分。

## 构建与本地测试

用 Android Studio 打开**本目录**，不是只打开 `app/`。项目使用 Gradle Wrapper 9.5.0、AGP 9.3.1、Android SDK 37（支持 Android 8 / API 26 及以上），Java 编译目标为 17。当前 Mac 使用 Android Studio 随附的 JBR。

在本机终端进入此目录后：

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest :app:lintRelease
./gradlew :app:assembleRelease
```

手机构建输出为 `app/build/outputs/apk/release/app-release.apk`。构建输出不自动成为已审核发布包，也不自动覆盖 `delivery/` 或网站清单。

`./gradlew :app:testDebugUnitTest` 运行当前手机版 JVM 测试；设备测试需在明确选择的 Android 设备或模拟器上运行。本页只提供入口，不代表本轮测试已经通过。

全部设备测试可用 `tools/device-tests/run-device-tests.sh` 一条命令运行：使用 debug 构建，需要名为 `Starglean_QA` 和 `Starglean_Location_QA` 的模拟器，脚本会清空数据后启动、逐组运行并关闭它们，结果在 `app/build/device-test-results/`。脚本不会开启访问真实后端或天气服务的测试。

`tools/operator/starglean.test.mjs` 是当前仍保留的离线工具测试。`tools/supabase-check/check-api.mjs` 会访问指定 Supabase 项目，只能在明确授权、提供公共客户端配置后运行；它不是普通离线测试，也不要向命令行或文档写入密钥。

## 哪些不能随便删

- `app/src/main/app/` 的生产源码，以及 `app/src/main/res/app-icon`、`home-pic`、`apparition-pic` 的图片输入：均有构建用途。后三者由素材准备任务读取，但不要登记为额外 Android `res` 根。
- `gradle/wrapper/gradle-wrapper.jar`：启动 Gradle 必需，不是编译垃圾。
- `supabase/migrations/`：已部署数据库的历史顺序；`schema/` 是审查聚合，不是另一套待部署脚本。
- `supabase/client-config.properties`：应用和工具使用的公共客户端配置，不是服务端密钥。
- `local.properties` 和 `.idea/`：本机 SDK / 编辑器配置，移动到别的电脑时按当地环境设置。
- 本机原有签名密钥：在项目外保存；换密钥会影响覆盖安装，不能重新生成来代替原密钥。
- `delivery/` 是历史交付与证据归档；其中旧 desktop 记录若仍被历史文档提及，也不能作为安卓手机下载包或当前构建入口。

## 整理规则

文档集中在 `docs/`；源码继续保持 Android 标准目录与 Kotlin 包路径，避免为改文件夹名称破坏导入和构建。`.editorconfig` 统一以后编辑时的缩进与换行，本次不批量重排已有业务代码。原始需求只作历史参考，后来确认的规则以实现状态和当前明确决定为准。
