# Android `core` 逐文件说明

本页覆盖 `app/src/main/app/core` 的生产源码，并给出当前手机版 JVM 与设备测试入口。说明来自实际声明；测试范围描述的是覆盖意图，不表示本次已经运行或通过。

## 主代码

### `account`：账户、会话与访问门禁

| 文件 | 实际用途 | 关联入口或阅读场景 |
|---|---|---|
| [AccountController.kt](../../app/src/main/app/core/account/AccountController.kt) | 管理登录/注册表单、OTP 操作、忙碌与错误状态，并只保存经过归一化且不含验证码的表单草稿。 | 从账户界面事件如何变成认证调用开始读。 |
| [AndroidKeystoreSessionStore.kt](../../app/src/main/app/core/account/AndroidKeystoreSessionStore.kt) | 用 Android Keystore 保护密钥，并把加密会话原子地存入本地；损坏或存储异常会显式失败而非冒充“未登录”。 | 排查会话持久化、密钥失效或损坏数据。 |
| [AppAccessController.kt](../../app/src/main/app/core/account/AppAccessController.kt) | 把本地会话、刷新结果和登出过程汇总成应用的加载/解锁/锁定状态，并防止过期异步结果重新解锁。 | 理解冷启动和回到前台时为何允许或阻止进入应用。 |
| [AuthModels.kt](../../app/src/main/app/core/account/AuthModels.kt) | 定义认证会话、结果、错误、身份、访问状态、操作租约及 `SessionStore` 接口等共享协议。 | 查认证层的数据契约和安全状态枚举。 |
| [AuthTransport.kt](../../app/src/main/app/core/account/AuthTransport.kt) | 定义认证 HTTP 传输接口，并以 `HttpsURLConnection` 实现受限请求、响应读取和安全异常包装。 | 排查认证请求的 HTTPS、超时、响应大小或网络失败。 |
| [SessionJsonCodec.kt](../../app/src/main/app/core/account/SessionJsonCodec.kt) | 在 `AuthSession` 与严格 JSON 字节格式之间编解码，校验必需字段而不负责加密。 | 阅读 Keystore 存储内容的逻辑格式。 |
| [SupabaseAuthClient.kt](../../app/src/main/app/core/account/SupabaseAuthClient.kt) | 实现 Supabase 邮箱 OTP、会话刷新/登出、改邮箱和删号流程，用互斥、令牌栅栏与操作租约防止竞态。未知网络结果不会被当作成功，只有明确的认证拒绝才按相应路径撤销本地访问；客户端不应也未以 `service_role` 密钥运行。 | 认证策略、离线已验证会话、刷新竞态和远端撤销语义的核心入口。 |

### `design`：共享视觉主题

| 文件 | 实际用途 | 关联入口或阅读场景 |
|---|---|---|
| [StargleanTheme.kt](../../app/src/main/app/core/design/StargleanTheme.kt) | 集中定义应用颜色、Compose Material 主题及“减少动态效果”系统设置的读取。 | 调整全局配色、状态栏外观或动效可访问性。 |

### `discovery`：附近阅读资格与移动判定

| 文件 | 实际用途 | 关联入口或阅读场景 |
|---|---|---|
| [EncounterPolicy.kt](../../app/src/main/app/core/discovery/EncounterPolicy.kt) | 按账户时区管理阅读开启请求、服务端回执和每日配额显示，以请求身份/代次/时限过滤迟到或重复回执。 | 排查“今日剩余次数”和一次开启为何被接受或忽略。 |
| [NearbyMovementGate.kt](../../app/src/main/app/core/discovery/NearbyMovementGate.kt) | 累积定位样本，通过 `MovementWindow` 判断静止/慢速、快速或不确定，并让危险证据立即撤销已稳定状态。 | 理解附近阅读对移动状态的本地门禁。 |
| [NearbyReaderLocationMonitor.kt](../../app/src/main/app/core/discovery/NearbyReaderLocationMonitor.kt) | 协调多个定位源的生命周期、样本时序、精度/新鲜度/模拟位置校验和定时过期，输出不泄露坐标的状态。 | 排查前台附近阅读为何没有合格定位或为何突然失效。 |
| [NearbyReaderLocationSource.kt](../../app/src/main/app/core/discovery/NearbyReaderLocationSource.kt) | 把 Android Fused/系统定位与桌面预览来源适配成监视器可订阅的定位源，并负责注册和清理。 | 查看真实设备定位提供者如何接入监视器。 |
| [NearbyReadingGate.kt](../../app/src/main/app/core/discovery/NearbyReadingGate.kt) | 将身份、前台、网络、精确定位、移动状态和服务端验证租约组合为最终阅读资格，严格绑定请求与会话。 | 追踪“可读/不可读/已结束”状态机及租约截止时间。 |

### `draft`：写作草稿

| 文件 | 实际用途 | 关联入口或阅读场景 |
|---|---|---|
| [WritingDraft.kt](../../app/src/main/app/core/draft/WritingDraft.kt) | 定义草稿、版本状态、校验规则和基于期望 revision 的保存/条件清除，防止旧写入覆盖新内容。 | 修改草稿字段、冲突语义或文本/照片约束。 |
| [WritingDraftCodec.kt](../../app/src/main/app/core/draft/WritingDraftCodec.kt) | 用带版本、长度边界和校验和的二进制格式编解码草稿及墓碑，并支持旧格式迁移。 | 排查草稿文件兼容性、损坏或大小限制。 |
| [WritingDraftStore.kt](../../app/src/main/app/core/draft/WritingDraftStore.kt) | 在账户文件目录中通过锁和原子文件读写草稿，保留损坏/歧义证据并执行乐观并发检查。 | 了解草稿跨进程重启持久化和写入冲突。 |

### `feedback`：相遇反馈效果

| 文件 | 实际用途 | 关联入口或阅读场景 |
|---|---|---|
| [AndroidEncounterFeedback.kt](../../app/src/main/app/core/feedback/AndroidEncounterFeedback.kt) | 在 Android 上播放合成提示音并触发触觉反馈，处理音频焦点、资源释放和失败回退。 | 排查发现内容时的声音/震动实际执行。 |
| [EncounterFeedback.kt](../../app/src/main/app/core/feedback/EncounterFeedback.kt) | 定义反馈播放器最小接口：播放、停止和释放。 | 替换平台实现或为调用方注入测试替身。 |
| [EncounterFeedbackSignal.kt](../../app/src/main/app/core/feedback/EncounterFeedbackSignal.kt) | 生成固定 PCM 提示音，并依据静音、触觉设置、耳机和其他音乐等环境决定是否发声/震动。 | 调整提示信号或反馈策略。 |
| [RunEffectOwnership.kt](../../app/src/main/app/core/feedback/RunEffectOwnership.kt) | 用运行编号记录某次异步流程是否仍拥有可取消副作用，避免旧流程停止新流程的效果。 | 阅读反馈或动画的并发取消保护。 |

### `io`：受限输入

| 文件 | 实际用途 | 关联入口或阅读场景 |
|---|---|---|
| [BoundedInputStream.kt](../../app/src/main/app/core/io/BoundedInputStream.kt) | 将输入流读取到严格字节上限，超过限制即失败，并避免依赖不可靠的 `available()`。 | 网络响应、图片或持久化数据的内存边界。 |

### `library`：日记库删除与串行化

| 文件 | 实际用途 | 关联入口或阅读场景 |
|---|---|---|
| [DiaryDeletionStore.kt](../../app/src/main/app/core/library/DiaryDeletionStore.kt) | 以删除意图、墓碑和可恢复步骤协调日记、星图、草稿与照片清理，确保崩溃后继续前滚且旧写入不能复活记录。 | 研究跨多个本地存储的安全删除事务。 |
| [LibraryStorageCoordinator.kt](../../app/src/main/app/core/library/LibraryStorageCoordinator.kt) | 提供账户资料库共享互斥锁，使草稿、日记、星图、照片及删除操作在同一临界区协调。 | 新增会同时触碰多个资料库文件的操作时。 |

### `location`：手机前台定位基础设施

| 文件 | 实际用途 | 关联入口或阅读场景 |
|---|---|---|
| [AppLocationConsent.kt](../../app/src/main/app/core/location/AppLocationConsent.kt) | 定义应用内定位披露版本、保存决定的解码，以及“应用同意且系统精确权限”双重授权规则。 | 定位同意弹窗或权限门禁。 |
| [ForegroundLocationRequest.kt](../../app/src/main/app/core/location/ForegroundLocationRequest.kt) | 封装 Android 前台单次定位请求及权限检查；读取附近/天气所需的是 `ACCESS_FINE_LOCATION` 精确权限。 | 真实设备发起合格定位或解释权限错误。 |
| [QualifiedLocationRequest.kt](../../app/src/main/app/core/location/QualifiedLocationRequest.kt) | 提供通用的多来源定位订阅、超时、候选资格判断及统一失败类型。 | Android、桌面或测试定位源共享的请求算法。 |

### `media`：日记照片导入、保存与清理

| 文件 | 实际用途 | 关联入口或阅读场景 |
|---|---|---|
| [AndroidPhotoStore.kt](../../app/src/main/app/core/media/AndroidPhotoStore.kt) | 导入选择或下载的图片，限制输入、解码缩放、移除元数据并重新编码，使用不透明引用保存且仅安全删除自有文件。 | 图片导入、下载完整性和磁盘清理。 |
| [DiaryPhoto.kt](../../app/src/main/app/core/media/DiaryPhoto.kt) | 定义照片元数据、不透明引用语法、数量/尺寸/摘要校验，并让调试字符串隐藏引用。 | 草稿和日记共用的照片数据规则。 |
| [LibraryPhotoCleanupStore.kt](../../app/src/main/app/core/media/LibraryPhotoCleanupStore.kt) | 持久化待清理照片候选，并在确认没有日记、草稿或租约引用后恢复/执行删除。 | 日记删除或草稿变更后的延迟照片回收。 |
| [PhotoImportLeaseStore.kt](../../app/src/main/app/core/media/PhotoImportLeaseStore.kt) | 用持久租约把“已导入但尚未写入草稿”的照片保护起来，并管理相机临时文件与上次进程恢复。 | 处理选图到草稿落盘之间的崩溃和竞态。 |
| [PhotoStore.kt](../../app/src/main/app/core/media/PhotoStore.kt) | 定义图片导入、读取、展示路径、存在性和“仅在无引用时删除”的存储接口。 | 为编辑器/发布流程替换或模拟照片存储。 |

### `memory`：自己的日记、位置与罗盘

| 文件 | 实际用途 | 关联入口或阅读场景 |
|---|---|---|
| [MemoryCompassMath.kt](../../app/src/main/app/core/memory/MemoryCompassMath.kt) | 计算两点距离、方位角、相对角度、方向名称和对齐强度，并规范化角度。 | “记忆罗盘”箭头方向与距离。 |
| [MemoryHeading.kt](../../app/src/main/app/core/memory/MemoryHeading.kt) | 定义朝向读数与可靠性，平滑角度并结合传感器精度、设备倾斜和时间判断是否可信。 | 排查指南针抖动、校准提示或不可用状态。 |
| [MemoryHeadingTracker.kt](../../app/src/main/app/core/memory/MemoryHeadingTracker.kt) | 注册 Android 旋转/磁场相关传感器，选择可用来源并输出经筛选的北向读数，负责生命周期清理。 | 真实设备罗盘采样入口。 |
| [MemoryLocationClient.kt](../../app/src/main/app/core/memory/MemoryLocationClient.kt) | 取得用于记忆方向的精确位置，在桌面预览与 Android 请求之间切换并映射失败。 | 日记详情中的当前位置获取。 |
| [MemoryLocationFix.kt](../../app/src/main/app/core/memory/MemoryLocationFix.kt) | 定义记忆位置、失败类型及精度/墙钟和单调时钟新鲜度校验；调试输出隐藏坐标。 | 保存/使用位置前的资格检查。 |
| [OwnDiaryCodec.kt](../../app/src/main/app/core/memory/OwnDiaryCodec.kt) | 定义自己的日记记录与文本/身份规则，并用带版本、边界和校验和的二进制格式编解码。 | 修改本地日记格式、迁移或内容限制。 |
| [OwnDiaryStore.kt](../../app/src/main/app/core/memory/OwnDiaryStore.kt) | 在账户目录中加锁、原子读写自己的日记集合，并通过规则实现幂等追加而非覆盖。 | 日记保存与重启加载。 |

### `model`：跨功能领域模型与演示数据

| 文件 | 实际用途 | 关联入口或阅读场景 |
|---|---|---|
| [AppModels.kt](../../app/src/main/app/core/model/AppModels.kt) | 定义导航目的地、发现/阅读状态、纸张/字体/可见性及明确标记为演示内容的样例。 | UI 共享枚举、稳定存储键和预览数据。 |
| [ConstellationGallery.kt](../../app/src/main/app/core/model/ConstellationGallery.kt) | 定义八种星座的几何、连线、展示实例与图库选择/主页草稿规则，并提供稳定样例。 | 星座图库绘制、目标选择和主页编排。 |
| [DiaryWeather.kt](../../app/src/main/app/core/model/DiaryWeather.kt) | 定义天气类别、昼夜及写入日记后冻结的天气快照。 | 日记展示和发布协议中的天气字段。 |

### `network`：附近服务协议与 HTTP 客户端

| 文件 | 实际用途 | 关联入口或阅读场景 |
|---|---|---|
| [DiscoverySessionMachine.kt](../../app/src/main/app/core/network/DiscoverySessionMachine.kt) | 以纯状态机约束发现会话的前台、同意、定位、预备/提交及身份变化，产出待执行副作用。 | 理解坐标何时允许发送，以及迟到回调为何不能复活会话。 |
| [JsonValue.kt](../../app/src/main/app/core/network/JsonValue.kt) | 在 Kotlin 值与 JSON 之间做严格、递归且有深度/数量边界的转换。 | RPC/Edge 请求响应序列化的基础层。 |
| [NearbyProtocol.kt](../../app/src/main/app/core/network/NearbyProtocol.kt) | 汇总附近服务的请求、响应、结果、媒体、日记、会话、反应和举报等线协议模型及严格解析。 | 查某个后端 action 的字段契约；服务端/RLS 是否正确不由此客户端文件单独证明。 |
| [NearbyRepository.kt](../../app/src/main/app/core/network/NearbyRepository.kt) | 把底层客户端 action 封装为账户、发布、媒体、阅读会话、反应、举报、屏蔽等领域操作。 | 业务控制器调用网络功能的主要门面。 |
| [SupabaseNearbyClient.kt](../../app/src/main/app/core/network/SupabaseNearbyClient.kt) | 实现 Supabase RPC、Edge Function、Storage 上传/下载的 HTTPS 调用、大小限制、取消和令牌绑定的 401 处理；不自动重放交付结果未知的写请求。 | 排查网络线格式、认证拒绝和私有媒体传输；客户端校验不能替代服务端 RLS/授权。 |
| [TransportFailureMessage.kt](../../app/src/main/app/core/network/TransportFailureMessage.kt) | 将已知网络异常链分类为固定、安全的用户消息，并保留“请求可能已送达”的不确定性而不回显异常细节。 | 将底层失败展示给 UI。 |

### `online`：云端发布、资料库与同步控制

| 文件 | 实际用途 | 关联入口或阅读场景 |
|---|---|---|
| [AndroidPendingPublicationStore.kt](../../app/src/main/app/core/online/AndroidPendingPublicationStore.kt) | 用 Android Keystore 加密并原子保存待发布请求及进度，损坏时保留证据。 | 发布崩溃恢复和幂等请求栅栏。 |
| [CloudEntryLinkStore.kt](../../app/src/main/app/core/online/CloudEntryLinkStore.kt) | 持久化账户限定的“本地日记 ID ↔ 云端 entry UUID”映射，校验重复和跨账户冲突。 | 云资料库、星座云状态与本地记录对接。 |
| [CloudLibraryController.kt](../../app/src/main/app/core/online/CloudLibraryController.kt) | 加载云端自己的条目和反应收件箱，并协调可见性、删除、恢复、照片下载及本地链接状态。 | 在线资料库页面的状态与用户操作。 |
| [ConstellationCloudCodec.kt](../../app/src/main/app/core/online/ConstellationCloudCodec.kt) | 在本地星位（本地日记 ID）与云状态（云 entry UUID）之间转换，并严格解析/生成线格式。 | 星座同步前后的身份映射和数据校验。 |
| [ConstellationCloudController.kt](../../app/src/main/app/core/online/ConstellationCloudController.kt) | 管理星座云同步启停、版本冲突、保留设备副本/恢复云副本及后续推送策略。 | 星座同步设置和冲突解决界面。 |
| [ConstellationCloudPreferenceStore.kt](../../app/src/main/app/core/online/ConstellationCloudPreferenceStore.kt) | 保存按账户限定的星座同步开关与最后版本。 | 启动同步控制器时恢复本地偏好。 |
| [ConstellationCloudRuntime.kt](../../app/src/main/app/core/online/ConstellationCloudRuntime.kt) | 组装星座本地存储、云偏好、链接、认证和网络依赖，提供可复用运行时对象。 | UI 创建星座同步控制器前的依赖入口。 |
| [OnlineRuntime.kt](../../app/src/main/app/core/online/OnlineRuntime.kt) | 单例组装认证、网络客户端/仓库、发布存储、链接和在线控制器所需共享依赖。 | 应用级在线功能依赖图入口。 |
| [PublicationCoordinator.kt](../../app/src/main/app/core/online/PublicationCoordinator.kt) | 冻结发布载荷和身份，持久化幂等请求，协调状态查询、取消替换、媒体上传/完成及崩溃恢复；未知结果保留栅栏而不盲目重试。 | 研究“一次发布”从本地草稿到云端完成的事务流程。 |
| [ReactionNoticeController.kt](../../app/src/main/app/core/online/ReactionNoticeController.kt) | 按云条目聚合未读反应、映射回本地日记、维护确认水位并保证到达动画只消费一次。 | 主页/资料库反应提示与轮询竞态。 |

### `sensor`：摇动和运动窗口

| 文件 | 实际用途 | 关联入口或阅读场景 |
|---|---|---|
| [IntentionalShake.kt](../../app/src/main/app/core/sensor/IntentionalShake.kt) | 从加速度峰谷识别有意的往返摇动，带时间窗、冷却和乱序样本防护。 | 调整摇一摇手势灵敏度。 |
| [MovementAssessment.kt](../../app/src/main/app/core/sensor/MovementAssessment.kt) | 在有界定位窗口内根据距离、时间、精度和重复样本判断慢/静止、快速或不确定。 | `NearbyMovementGate` 的底层运动证据算法。 |
| [ShakeDetector.kt](../../app/src/main/app/core/sensor/ShakeDetector.kt) | Compose 副作用中按生命周期注册加速度传感器，并在识别手势时回调。 | 页面接入摇一摇功能。 |

### `stars`：星座收藏持久化与规则

| 文件 | 实际用途 | 关联入口或阅读场景 |
|---|---|---|
| [ConstellationStore.kt](../../app/src/main/app/core/stars/ConstellationStore.kt) | 加锁并原子持久化星座收藏，支持目标、实例、主页、日记放置/删除和云状态替换，同时与真实日记引用核对。 | 星座收藏的本地事务入口。 |
| [StarCollectionCodec.kt](../../app/src/main/app/core/stars/StarCollectionCodec.kt) | 用带版本、边界与校验和的二进制格式保存星座实例、星位映射、目标及主页选择。 | 星座文件兼容性、损坏和严格解析。 |
| [StarCollectionState.kt](../../app/src/main/app/core/stars/StarCollectionState.kt) | 定义星座收藏状态与纯规则：校验、创建实例、放置/移除日记、设目标和主页、判断完成。 | 理解最多实例数、稳定星位和幂等放置等领域约束。 |

### `storage`：账户文件空间与旧资料认领

| 文件 | 实际用途 | 关联入口或阅读场景 |
|---|---|---|
| [AccountStorageContext.kt](../../app/src/main/app/core/storage/AccountStorageContext.kt) | 将 `filesDir`、`noBackupFilesDir` 和 `cacheDir` 定向到规范化账户子目录，并保留基础 Context 供系统服务使用。它**不会自动隔离**默认 `SharedPreferences`：实现委托给基础 Context，需由各存储自行使用账户键或专用文件。 | 检查文件是否按账户隔离，或排查误以为 Context 包装会隔离 prefs 的问题。 |
| [LegacyLibraryClaimStore.kt](../../app/src/main/app/core/storage/LegacyLibraryClaimStore.kt) | 识别旧的全局资料，要求用户明确“新建”或“认领”，以分阶段文件操作迁入账户目录并在中断时失败关闭。 | 升级旧版本资料、账户切换及跨账户泄漏防护。 |

### `weather`：精确位置、地名与实时天气

| 文件 | 实际用途 | 关联入口或阅读场景 |
|---|---|---|
| [AndroidWeatherPlaceClient.kt](../../app/src/main/app/core/weather/AndroidWeatherPlaceClient.kt) | 可选地调用 Android 反向地理编码，将结构化地址清洗为不含门牌/坐标的街道或区域名，并限制并发、超时和迟到回调。 | 在天气旁显示地点名称；地点仅是展示元数据。 |
| [CoarseWeatherLocationClient.kt](../../app/src/main/app/core/weather/CoarseWeatherLocationClient.kt) | **文件名是历史遗留，当前并非粗略定位。**实现通过 `ForegroundLocationRequest` 要求精确权限和合格的 precise fix，保留原始授权坐标供天气请求，可选补充地名。 | 获取写作时天气查询位置；不要因类名误判为网格化或 `ACCESS_COARSE_LOCATION`。 |
| [CurrentWritingWeather.kt](../../app/src/main/app/core/weather/CurrentWritingWeather.kt) | 定义当前天气、固定安全错误和 Open-Meteo 映射规则，严格校验时间、温度、单位、时区、昼夜及 WMO 类别。 | 将服务响应冻结为日记天气快照前。 |
| [OpenMeteoWeatherClient.kt](../../app/src/main/app/core/weather/OpenMeteoWeatherClient.kt) | 在 IO 线程发起受限 HTTPS 天气请求，限制响应大小并严格解析当前天气 JSON。未知网络/解析结果只映射为定义好的失败，不凭空生成天气。 | 实时天气网络入口。 |
| [WeatherLocationValidation.kt](../../app/src/main/app/core/weather/WeatherLocationValidation.kt) | 定义天气定位失败并校验坐标、精度、模拟标记及墙钟/单调时钟新鲜度。 | 解释某次位置为何不能用于天气。 |
| [WeatherQueryLocation.kt](../../app/src/main/app/core/weather/WeatherQueryLocation.kt) | 封装经过授权的精确坐标和可选显示地点，规范化敏感值、生成 Open-Meteo HTTPS URL；精度/地名不进入查询身份。 | 检查天气 URL、坐标保真和日志脱敏。 |


## 手机版测试入口

- [`app/src/test`](../../app/src/test) 包含账户、发现、草稿、媒体、网络、存储、天气等纯规则和协议测试。
- [`app/src/androidTest`](../../app/src/androidTest) 包含 Keystore、文件存储、Activity / Compose、相机媒体和手机版流程测试。
- desktop 专用测试没有恢复；`delivery/` 中的旧报告只证明当时对应版本的结果，不证明当前工作树已验证。

## 覆盖与边界

- 当前清单共 **134** 个文件：主代码 **75**、JVM 单元测试 **44**、Android 设备/仪器测试 **15**。
- 职责通过 `rg --files` 的真实清单、源码中的类/对象/函数声明、关键读写/网络实现以及测试方法名交叉核对；不是按文件名猜测。
- 本页只说明客户端仓库中可见的行为。线上 Supabase 配置、数据库 RLS、Edge Function 实现、密钥轮换和真实网络可用性不在这些文件内，无法仅凭此代码确定；遇到传输失败或交付结果未知时，应保留代码区分的“不确定”状态，不能解释为成功或确定失败。
- 本次按约束未运行构建、测试、网络或后端；所有测试条目均是“测试意图”，不是执行结果。
