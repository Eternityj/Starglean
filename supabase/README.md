# 后端文件说明

此目录保存后端的可维护源码，**不是缓存目录**。整理本地文件不会修改线上项目；不要为了清理文件重新执行迁移、清空数据库或删除 Storage。

| 路径 | 负责什么 | 是否可删 |
| --- | --- | --- |
| `migrations/` | 正式数据库迁移，按文件名前的时间戳排序；包含权限、发布、200 米、额度、容量及锁规则 | 不可按“已经部署”删除 |
| `functions/nearby-private-media/` | 私有照片校验/读取和删除的 Edge Function 及测试 | 保留 |
| `tests/` | 数据库契约和边界断言 | 保留；执行前确认环境和事务清理范围 |
| `schema/` | 用于理解协议的审查汇总，`complete_public_app_contract.sql` 按顺序引用迁移文件 | 保留；不是第二套迁移，不重复部署 |
| [email-templates/](email-templates/README.md) | 注册、登录、改绑邮箱验证码的 HTML 模板 | 保留 |
| `client-config.properties` | 公共项目 URL 和 publishable key，Android 构建及工具会读取 | 必需；禁止加入 service-role/管理员密钥 |
| [OPERATIONS.md](OPERATIONS.md) | 服务开关、举报处置、删除和免费额度运维流程 | 保留 |
| `.temp/`、`.branches/` | CLI 产生的本机状态 | 已忽略；本次仅移除了实际存在的 `.temp/` |

阅读建议：先看运维说明了解系统边界，再读 `schema/` 和客户端 `core/network/` 的协议，最后按迁移顺序和 `tests/` 理解历史及断言。正式迁移不要改名、重排、合并或删除已部署文件。

当前实现与尚未完成的验收见 [实现状态](../docs/IMPLEMENTATION_STATUS.md)。
