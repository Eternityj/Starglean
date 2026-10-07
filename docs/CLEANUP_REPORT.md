# 工程整理记录 · 2026-09-27

> 这是 2026-09-27 当时的清理与验证记录，不按当前目录重写历史。此后 desktop、Mac 定位助手、独立 QA runner 与 website 已从工作树移除；下文说它们“保留”或可运行，仅描述当时状态，不能作为当前路径或本轮测试结论。

本次范围仅为当前 Android 工程 `MCSP/mobile app/`。没有修改线上数据库、公开网站、真实账号、手机/模拟器资料或项目外的签名与定位凭据，也没有提交或推送 Git。现有未提交的业务改动和先前删除记录均保留。

## 整理结果

- 工程目录占用由约 **720 MiB** 降至约 **79 MiB**（`du -sk`，含保留的手机包、电脑包和验证证据）。这是工作目录减少的占用；因文件还在废纸篓中，**不等于已释放同等磁盘空间**。
- 编译输出、旧 APK、重复的网站输出和一次性脚本移入废纸篓，没有永久擦除。
- 手机已审核 APK、私有桌面包及最近的关键截图/测试证据独立保存在 `delivery/`，不再混在可以随时清理的 `app/build/` 中。
- 根目录新增 [README](../README.md)，按代码、文档、后端、工具、网站、交付物说明；[CODE_MAP](CODE_MAP.md) 给出入口、四页和全部核心模块的阅读顺序。
- 原始需求原文移至 [requirements/](requirements/README.md)，SHA-256 比对确认原文未变；实现状态集中在 [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md)。没有恢复已被用户删除的旧设计档案。

## 移出的文件

| 路径（相对工程根目录） | 判断依据 |
| --- | --- |
| `app/build/` | Gradle 编译产物、旧安装包、重复生成的资源及旧 QA 输出；先保留了最新已审核手机/桌面包与重要证据 |
| `build/`、`.gradle/`、`.kotlin/` | 可重新生成的报告和项目级构建缓存；未清理用户全局 Gradle 缓存或 Android SDK |
| `website/dist/` | 从网站源码、隐私正文、已审核 APK 生成；其中 APK 与归档完全相同 |
| `tools/mac-location-helper/.build/` | 助手二进制和协议测试程序，可由现有 build/test 脚本再生；源码和配置保留 |
| `supabase/.temp/` | 本机 CLI 临时状态，不是数据库源码 |
| `.DS_Store` | Finder 目录元数据 |
| `tools/qa/compose-backend-migration.mjs` | 对特定已部署迁移的一次性拼接工具，无现行调用 |
| `tools/qa/compose-backend-followup.mjs` | 对特定后续已部署迁移的一次性拼接工具，无现行调用 |
| `tools/qa/email-otp-check.mjs` | 已完成用途的独立网页邮箱诊断工具；Android 登录代码及其测试未删除 |
| `app/src/test/java/com/starglean/app/core/i18n/` | 已无文件的空目录，其原文件早已被用户删除 |

清理后为验证重新生成的构建缓存、网站 staging 和 Mac 测试二进制，在证据留存后也移入同一废纸篓目录。此前由本任务启动的本机网站预览服务器已结束，不影响公开下载站。

## 保留和小幅修正

- 全部实际使用的星座/星球/图标资源、核心源码、单元测试、设备测试、正式 SQL 迁移及运维工具保留。未发现足以安全整文件删除的 Android 源码/资源；没有按 `demo`、`preview` 或“未跟踪”名字机械删除。
- `StargleanTheme.kt` 只移除了全仓无引用的 `StargleanSpacing` 常量对象及对应 import；实际颜色、排版、布局和动效未改。
- 通用 `gradle.properties` 移除重复的本机 SDK 路径；原有被忽略的 `local.properties` 继续提供该路径。
- `.gitignore` 分组说明，并增加 `.kotlin/`、`website/dist/` 和本机交付物的忽略；`.idea/` 现有工作区设置保留。
- `.editorconfig` 统一以后编辑时的缩进、换行和文件结尾规则，没有全量重排现有业务代码。
- 网站 README 的过期 `pending` 说明已修正；清单改为读取 `delivery/0.4.0/app-release.apk`。没有重新发布网站或更换下载字节。
- 后端目录按 Supabase 技能核查：迁移历史、审查聚合 SQL、权限测试和公共配置保留，本轮没有执行线上 SQL。

## 本轮验证

| 检查 | 结果 |
| --- | --- |
| 无项目缓存的 Debug / Release / Desktop Kotlin 编译 | 全部成功，Gradle 离线执行 |
| Debug JVM 单元测试 | **671 项，0 失败、0 错误、0 跳过** |
| Release 完整 lint | **0 errors、22 warnings、4 hints**，未宣称全部警告清除 |
| operator 与 QA runner 隔离测试 | **21 项通过**，未调用真实后端 |
| 网站测试 | **5 项通过** |
| 网站 staging | 通过签名、包名、版本、摘要检查；改路径后生成 APK 与已审核原件完全一致 |
| Mac 定位协议 | **642 项断言通过**，没有启动定位服务 |
| 文档、Git 与保留文件 | 相对链接、`git diff --check`、源文件哈希差异复核通过 |

手机 APK SHA-256：`6e4a8d23870b33926be165a3289b58d5810d74129243b4e9287ed0cd882cc17e`。当前下载版本不变；清理后编译的源码只比已审核包少一个未使用的常量对象，本轮不另发版本。

验证日志、XML、HTML 测试报告在 `delivery/0.4.0/verification/after-cleanup/`。这次不是新的实地 GPS、传感器、弱网或真人举报验收，原有未完成验收项继续以实现状态为准。

## 如何恢复

废纸篓目录：`/Users/yangjiesen/.Trash/starglean-cleanup-20260927-JWLLs3/`。

- `initial/` 按原相对路径保存清理前文件。
- `verification-generated/` 保存本轮验证重新产生后再次移出的产物。
- `cleanup-manifest.json` 记录来源、目标、文件数、字节数及保留去向。

需要恢复时，先根据清单选择具体文件，比较当前同名文件后再移回；**不要整目录覆盖现有工程，也不要恢复旧 `.gradle` 缓存来替代重新构建**。废纸篓未被清空前可恢复。本次没有自动清空废纸篓。

两项独立审计及文档复核由 GPT-5.6-sol 子任务执行，主任务完成删除判断、整合与验证。平台未提供本流程实际 Token 用量，无法获取实际用量。
