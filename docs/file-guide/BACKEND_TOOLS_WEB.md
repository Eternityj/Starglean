# 后端与维护工具逐文件说明

本文覆盖当前保留的 `supabase/` 与 `tools/`。它不读取或记录本机私密配置的值；历史上存在的 desktop、Mac helper、QA runner 与 website 已不再列作当前文件。

## Supabase 后端

### 入口、配置与运维

| 文件 | 实际用途 | 谁使用或阅读注意 |
| --- | --- | --- |
| [supabase/README.md](../../supabase/README.md) | 后端目录总索引，解释迁移、函数、测试、审查汇总和邮件模板各自的地位。 | 维护者先读；已部署迁移仍是历史记录，不应删除、改名或合并。 |
| [supabase/OPERATIONS.md](../../supabase/OPERATIONS.md) | 线上服务开关、人工举报处置、条目/账号删除、免费额度与 Edge Function 核验边界的运维手册。 | 运营与后端维护者使用；举报 RPC 和 CLI 确实已实现且有已授权操作员，但人工下架/恢复全流程尚未演练，不能写成已完成运营验收。 |
| [supabase/client-config.properties](../../supabase/client-config.properties) | 保存 Android 与本地工具共用的公开项目 URL、公开客户端密钥类别。 | 客户端、operator 与 QA runner 读取；只能放 publishable/公开客户端配置，绝不能放 service-role、管理员密钥或本机能力令牌。 |

### Auth 邮件模板

| 文件 | 实际用途 | 谁使用或阅读注意 |
| --- | --- | --- |
| [supabase/email-templates/README.md](../../supabase/email-templates/README.md) | 说明三个 HTML 模板在 Supabase Dashboard 中的映射、OTP 意图及 Secure Email Change 差异。 | 部署 Auth 邮件的维护者阅读；本地文件不证明线上已配置，托管免费项目的自定义模板还可能要求自有 SMTP。 |
| [supabase/email-templates/confirm-sign-up.html](../../supabase/email-templates/confirm-sign-up.html) | 首次注册确认邮件，显示 `{{ .Token }}` 验证码并提醒勿分享。 | 映射到 Confirm sign up；只确认邮箱/注册，不授予操作员权限。 |
| [supabase/email-templates/email-change.html](../../supabase/email-templates/email-change.html) | 已登录账号绑定或更换邮箱时使用的验证码邮件。 | 映射到 Change Email Address；若启用安全邮箱变更，旧、新邮箱可能分别收到并需完成各自验证码。 |
| [supabase/email-templates/magic-link.html](../../supabase/email-templates/magic-link.html) | 把 Magic Link 流程呈现为应用内输入的 OTP，用于登录或找回已有账号。 | 客户端恢复流程使用；调用方应禁止未知邮箱顺便创建账号。 |

### Edge Function：私有照片

| 文件 | 实际用途 | 谁使用或阅读注意 |
| --- | --- | --- |
| [supabase/functions/nearby-private-media/index.ts](../../supabase/functions/nearby-private-media/index.ts) | 私有照片 Edge Function 主入口：限流读取请求体，校验 JPEG、哈希/尺寸，完成上传，签发并复核读取租约，以及协调 Storage 后的条目/账号删除。 | Android 通过用户 JWT 调用，内部以服务端权限调用窄化 RPC；读取返回 `no-store`，`import.meta.main` 使其被测试导入时不会启动监听。 |
| [supabase/functions/nearby-private-media/index_test.ts](../../supabase/functions/nearby-private-media/index_test.ts) | 针对请求体边界、字段白名单、JPEG 结构/EXIF 拒绝和 Android 兼容图片的 Deno 单元测试。 | 后端维护者本地运行；它验证解析与安全边界，不等于真实 Storage、Auth 或线上函数验收。 |

### 数据库迁移（按时间顺序）

| 文件 | 实际用途 | 谁使用或阅读注意 |
| --- | --- | --- |
| [20260926155229_nearby_foundation.sql](../../supabase/migrations/20260926155229_nearby_foundation.sql) | 建立受限 `nearby_private` 基础：测试者、条目、坐标锚点、Unicode 有效长度与 200 米判定，并以认证会话守卫的命令包装器封住原始表。 | 数据库迁移历史；这是私有测试基础，不是匿名读内容接口，且不导入本地日记。 |
| [20260926162734_public_nearby_service.sql](../../supabase/migrations/20260926162734_public_nearby_service.sql) | 加入账号、发布请求/状态、阅读租约与日额度、回应、屏蔽、举报、申诉、审核审计及 public/staff RPC，形成文字版附近发现协议。 | 正式迁移；同时撤销旧 `nearby_command` 客户端入口。文件头称“source is not a migration”与其实际位于迁移链的事实有张力，理解时以仓库迁移顺序及后续迁移依赖为准。 |
| [20260926163012_nearby_query_indexes.sql](../../supabase/migrations/20260926163012_nearby_query_indexes.sql) | 增加审核人员审计索引，并替换 public dispatcher，用 GiST 范围预筛选加精确距离守卫优化附近候选查询。 | 后端维护者；它是对上一迁移函数的性能/查询修订，不是独立服务。 |
| [20260926180757_complete_public_app_contract.sql](../../supabase/migrations/20260926180757_complete_public_app_contract.sql) | 补齐公开应用契约：天气/星座快照不可变、私有媒体与上传约束、发布取消、后发布审核、账号状态、删除操作/墓碑、媒体访问票据和服务端校验删除 RPC。 | Android、Edge Function 与数据库共同依赖；迁移故意不改变全局发现开关，是否开放由运维单独决定。 |
| [20260926195340_fix_post_publication_restore_and_fk_indexes.sql](../../supabase/migrations/20260926195340_fix_post_publication_restore_and_fk_indexes.sql) | 修正后发布内容被下架再恢复时的人审时间约束，使自动发布时间与人工恢复时间可同时保留；并补齐删除/媒体外键侧索引。 | 对审核恢复语义和级联性能的增量修复，不能回填成对旧迁移的直接改写。 |
| [20260926195430_add_private_qa_read_allowlist.sql](../../supabase/migrations/20260926195430_add_private_qa_read_allowlist.sql) | 在严格校验目标函数版本后，加入带过期时间的私有 QA 阅读白名单及判定函数，让特定测试账号可在全局开关之外验证读取。 | 仅受控 QA 使用；不是面向用户的正式接口或长期功能开关。 |
| [20260926200545_enforce_cloud_entry_limit.sql](../../supabase/migrations/20260926200545_enforce_cloud_entry_limit.sql) | 精确替换旧 dispatcher 中“累计发布请求上限”，改为每账号最多 1,000 条仍存在的云端条目，删除完成后释放名额。 | 依赖预期旧函数文本并失败关闭；永久幂等回执/墓碑不占容量。 |
| [20260926200608_normalize_media_deletion_lock_order.sql](../../supabase/migrations/20260926200608_normalize_media_deletion_lock_order.sql) | 调整媒体校验和删除服务函数的锁顺序：先取得每用户 advisory lock，再锁可变行，避免校验与账号删除互相等待。 | 并发安全增量迁移；通过函数源码精确匹配修补，目标版本不符会拒绝执行。 |

### 审查汇总（不是第二套部署）

| 文件 | 实际用途 | 谁使用或阅读注意 |
| --- | --- | --- |
| [supabase/schema/public_nearby_service.sql](../../supabase/schema/public_nearby_service.sql) | 供审查理解的公开附近服务汇总版，包含主要表、索引、触发器和 public/staff dispatcher；相较初始迁移已吸收查询索引修订。 | 代码审查者阅读；它明确不是迁移，不应在正式迁移链之外再部署一次。 |
| [supabase/schema/complete_public_app_contract.sql](../../supabase/schema/complete_public_app_contract.sql) | 用 `psql \ir` 顺序引用完整契约及其五个后续修复迁移，避免复制 SQL 后漂移。 | 审查/隔离数据库重建时使用；这是聚合入口，引用的仍是同一批迁移，绝非“双份 schema”。 |

### 数据库契约测试

| 文件 | 实际用途 | 谁使用或阅读注意 |
| --- | --- | --- |
| [supabase/tests/nearby_foundation.sql](../../supabase/tests/nearby_foundation.sql) | 在事务中用合成用户、会话与坐标验证私有表权限、文本/距离边界、幂等和会话要求，最后回滚。 | 仅隔离测试库由迁移所有者运行；不证明 Auth HTTP、并发、GPS 或匿名客户端已经可用。 |
| [supabase/tests/public_nearby_service.sql](../../supabase/tests/public_nearby_service.sql) | 覆盖注册、公开提交/取消、阅读额度与租约、回应、屏蔽、举报审核、照片、删除、容量及后发布恢复的完整数据库功能路径。 | 后端集成测试；使用临时特权探针观察私有状态并回滚，Storage API 清理另需 HTTP 集成验证。 |
| [supabase/tests/complete_public_app_contract.sql](../../supabase/tests/complete_public_app_contract.sql) | 对所有迁移后的最终对象、约束、函数权限和契约存在性做小型冒烟断言。 | 在隔离数据库完成全迁移后运行；只验证静态/基础契约。 |
| [supabase/tests/qa_read_allowlist.sql](../../supabase/tests/qa_read_allowlist.sql) | 验证 QA 白名单的过期、账号绑定和读取开关隔离，合成数据最后回滚。 | 只在 QA allowlist 迁移之后运行；它验证临时准入机制，不把 QA 探针变成产品接口。 |

## 开发与维护工具

当前只保留两组工具；旧 Mac 定位助手、`tools/qa/` 和下载网站源码已经从当前工程移除。相关名称若出现在历史验收记录中，仅说明当时范围，不是现在可运行的路径。

| 文件 | 实际用途 | 谁使用或阅读注意 |
| --- | --- | --- |
| [tools/supabase-check/README.md](../../tools/supabase-check/README.md) | Supabase 拒绝面检查的前置条件、运行方式与结果边界。 | 会访问明确指定的后端；只使用公开客户端配置，不读取或输出服务端密钥。 |
| [tools/supabase-check/check-api.mjs](../../tools/supabase-check/check-api.mjs) | 对匿名 RPC、伪造 JWT、私有 schema 访问和 Auth 设置做拒绝面检查。 | 只对已授权目标运行；结果不替代 Android 端到端验收。 |
| [tools/operator/README.md](../../tools/operator/README.md) | 说明列举/查看举报、下架、驳回与恢复命令，以及 OTP、脱敏和逐项确认边界。 | 已授权操作员阅读；工具不接受 service-role，也不能自行授予 staff。 |
| [tools/operator/starglean.mjs](../../tools/operator/starglean.mjs) | 交互式 operator 客户端，调用窄化 staff RPC 并在本地登出。 | 查看正文及所有状态变更都需目标特定确认，会真实影响线上状态。 |
| [tools/operator/starglean.test.mjs](../../tools/operator/starglean.test.mjs) | 用 fake client 验证登录身份、脱敏、终端注入防护、确认门槛、操作映射、响应上限和超时。 | 本地离线测试；不联网，也不替代真实举报队列演练。 |

## 当前边界

- `supabase/schema/` 是审查与按序聚合迁移的入口，不是与 `supabase/migrations/` 并行部署的第二套数据库定义。
- 举报 RPC 和 operator CLI 已存在，但历史文档记载的运维验收状态不应自动视为当前工作树已复验。
- 当前工程只面向 Android 手机与必要后端；已删除的 desktop、Mac helper、QA runner 和 website 不应被构建或操作指南继续引用。
