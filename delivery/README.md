# 本机交付文件

这里与可再生的 `app/build/` 分开。除本说明外，安装包和验证证据都被 Git 忽略；它们不是源码，不自动同步到远端。

## 当前安卓手机安装包

[Starglean 0.5.4](0.5.4/Starglean-0.5.4.apk)，versionName `0.5.4-en`、versionCode 23，Android 8.0 及以上。Discover 移除重复的 Account 入口及其过时本地状态；保留右侧额度显示，账户仍可从 Moon 首页 Settings → Account & location 打开。

验证记录见 [0.5.4/verification/README.md](0.5.4/verification/README.md)。

## 历史交付记录

以下均为历史交付说明，其中“当前”指当时的版本。部分历史文件已移除，不能据此选择本轮手机安装包。

| 路径 | 作用 |
| --- | --- |
| `0.5.3/Starglean-0.5.3.apk` | 0.5.3-en / versionCode 22；切换应用保留已发现笔记 |
| `0.5.3/verification/` | 0.5.3 的验证记录 |
| `0.4.8/Starglean-0.4.8.apk` | 历史手机包，versionCode 17；修复日记闪现、地点文字和空列表提示 |
| `0.4.8/verification/` | 0.4.8 的验证记录 |
| `0.4.4/app-release.apk` | 当前手机安装包，0.4.4-en / versionCode 13；统一位置入口、精简首页、修复 fresh validate 协议与阅读/反馈闪切；网站不更新 |
| `0.4.4/verification/` | 768 项 JVM、API 30/35 各 13 项设备回归、线上协议只读核对及截图 |
| `private-desktop/0.4.4/app-desktop.apk` | 当前电脑预览包，含本机桥接配置，禁止分发 |
| `0.4.3/app-release.apk` | 上一手机包，0.4.3-en / versionCode 12；仍有 fresh validate 响应误判，本轮已修复 |
| `0.4.3/verification/` | 755 项 JVM、API 30/35 各 11 项设备测试、界面截图、音效样本与签名记录 |
| `private-desktop/0.4.3/app-desktop.apk` | 上一电脑预览包，含本机桥接配置，禁止分发 |
| `0.4.2/app-release.apk` | 中间修复包归档，0.4.2-en / versionCode 11 |
| `0.4.2/verification/` | 定位/注册/地址回归、真实 Auth 协议探针、API 30/35 检查及截图；详见其中报告 |
| `private-desktop/0.4.2/app-desktop.apk` | 中间电脑预览包，含本机桥接配置，禁止分发 |
| `0.4.1/app-release.apk` | 上一修复版，0.4.1-en / versionCode 10；未改动的网站清单仍指向这份归档 |
| `0.4.1/verification/` | 0.4.1 的 JVM、lint、API 30/35 网络与导航回归、定位提示截图 |
| `private-desktop/0.4.1/app-desktop.apk` | 上一电脑预览包，含本机桥接配置，禁止分发 |
| `0.4.0/app-release.apk` | 上一版本归档，保留作比对，不再推荐安装 |
| `0.4.0/verification/android/` | 整理前保留的 API 30 / 35 截图，含排错截图，不能把每张都当作通过证据 |
| `0.4.0/verification/jvm-before-cleanup/` | 整理前的 JVM 测试报告 |
| `0.4.0/verification/lint-before-cleanup.txt` | 整理前的完整 Release lint 结果 |
| `0.4.0/verification/after-cleanup/` | 本次整理后重新构建与测试的证据 |
| `private-desktop/0.4.0/app-desktop.apk` | 仅这台 Mac 配套的电脑预览包，含本机桥接配置，禁止分发 |

各包的 SHA-256 与验收范围见对应版本的 `verification/`。0.4.1 手机 SHA-256 保留：`caa66d4488d39bbc3a91ae8760ffce34af36d4e20c178800c7a12e61e35b4f61`。

0.4.4 包含前版修复，并核对线上真实返回字段修正反复阅读失效；首次统一授权后，Find/Write/罗盘/活动面板不再各自请求系统定位。声音与震动在 200ms 稳态后与约 2.3 秒的星光飞行同步。包名及签名与旧版相同，可覆盖安装，不要先卸载。后续有代码修改时，先构建、验收和核验签名，再按新版本归档；不要覆盖已审核归档。只有用户要求更新网站时才调整 `website/release-manifest.json` 并部署。

不要把整个 `delivery/` 上传到下载站。网站打包器只读取清单指定的手机 APK。清理和恢复细节见 [清理记录](../docs/CLEANUP_REPORT.md)。
