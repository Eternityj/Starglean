# Android UI 逐文件指南

本文覆盖 Android 启动入口与 `ui` 目录生产源码，并说明当前手机版测试入口。说明来自文件中的实际声明；测试范围描述的是覆盖意图，不代表当前环境已执行或全部通过。

## 先理解两条主线

- 正常入口是 `MainActivity → StargleanApp → AppEntryGate → AppLocationGate → 四个主页面`。`AppLocationGate` 是近日统一后的应用级位置入口：先记录用途同意，再按平台处理精确位置权限；天气地点、附近阅读和本地记忆位置仍各自有独立的数据使用选择。
- `DEBUG` 可接受 Intent 中的旧演示参数，用于截图、动效和流程样例；正式版不接受这些样例内容。debug 与 release 都是手机构建。附近阅读的真实实现集中在 `LiveDiscoverScreen`/`LiveDiscoveryController`，并由同一个 `NearbyReadingShell` 承载“可读、复核中、恢复”等协议状态。

## 启动与四页面框架

| 文件 | 实际用途 | 关联入口或什么时候看 |
|---|---|---|
| [MainActivity.kt](../../app/src/main/app/MainActivity.kt) | Android Activity 入口，固定英文 locale、启用 edge-to-edge，并把启动参数转换成 `DemoLaunch`。只有 debug 构建读取旧演示参数，release 使用空参数。 | 查应用如何启动、为何界面固定英文，或区分 debug 样例与真实页面时。 |
| [StargleanApp.kt](../../app/src/main/app/StargleanApp.kt) | 定义 `DemoLaunch`，串起账户/资料库门禁与统一位置门禁，再用底栏和淡入淡出切换 Planet、Discover、Write、Constellations 四页；还维护草稿/画廊保存状态与反应未读标记。 | 查全局导航、门禁顺序、四页参数来源或页面状态为何能跨切换保留时。 |

## screens：入口门禁与四个主页面

| 文件 | 实际用途 | 关联入口或什么时候看 |
|---|---|---|
| [AppEntryGate.kt](../../app/src/main/app/ui/screens/AppEntryGate.kt) | 账户入口总门禁：等待在线运行时、登录，并在账户变化时选择认领旧本地资料库或创建新的账户隔离资料库。 | 启动后卡在登录/资料库选择，或排查账户切换与本地数据归属时。 |
| [AppLocationGate.kt](../../app/src/main/app/ui/screens/AppLocationGate.kt) | 统一管理应用级位置披露、持久化 consent 版本与 Android 精确位置权限；披露明确附近记忆会把精确位置发送给服务端检查 200 m 规则。 | 查统一位置入口及首次进入、拒绝、稍后启用时。 |
| [PlanetScreen.kt](../../app/src/main/app/ui/screens/PlanetScreen.kt) | 首页星球与星座天空；本地版读取真实星集合，普通 release 不用样例天空，debug 才可打开 Cygnus/星座旧预览和样例日记。 | 首页星体、点星打开日记、真实集合与 debug 样例分流时。 |
| [DiscoverScreen.kt](../../app/src/main/app/ui/screens/DiscoverScreen.kt) | “发现”页的外壳与旧本地演示流程；本地设备版或非 debug 会转入真实 `LiveDiscoverScreen`，debug 才能预览样例日记、天气与反应动画。 | 判断当前看到的是旧样例还是真实附近阅读，或查揭示/关闭/反应 UI 时。 |
| [WriteScreen.kt](../../app/src/main/app/ui/screens/WriteScreen.kt) | 写作主页面：编辑标题正文、纸张字体、天气/地点、照片、预览、本地保存与星位放置；用来源标记阻止 debug 天气/固定坐标样例进入真实资料库。 | 写作、预览恢复、保存屏障、地点/天气文案和 debug 样例隔离时。 |
| [ConstellationsScreen.kt](../../app/src/main/app/ui/screens/ConstellationsScreen.kt) | 星座画廊主页面，分页展示集合、工具面板、日记纸张、反应动画和记忆罗盘；tour、指定星/页/面板仅在 debug 自动驱动。 | 画廊导航、星座英文说明、未读反应和旧演示 tour 时。 |

## screens：附近阅读、账户、资料库与辅助页面

| 文件 | 实际用途 | 关联入口或什么时候看 |
|---|---|---|
| [LiveDiscoverScreen.kt](../../app/src/main/app/ui/screens/LiveDiscoverScreen.kt) | 真实附近阅读 Compose 页面：接入前台位置、动作感应、账户状态、到达反馈与临时媒体；`nearbyReadingPresentation` 决定壳层状态，`NearbyReadingShell` 在可读、复核中和恢复时保持同一阅读界面。 | 排查真实 200 m 阅读体验、协议状态切换时是否闪退壳层、到达反馈播放时。 |
| [LiveDiscoveryController.kt](../../app/src/main/app/ui/screens/LiveDiscoveryController.kt) | 单次前台 encounter 的协议控制器：注册账户、摇动请求、位置/移动门控、服务端验证、租约续期、解封内容、反应/举报/屏蔽；用 epoch、账户 operation lease 和请求 envelope 做 fail-closed 校验。 | 网络协议、重试/续期、旧响应串入、账户切换或 200 m 授权边界时。 |
| [ForegroundNearbyState.kt](../../app/src/main/app/ui/screens/ForegroundNearbyState.kt) | 管理前台附近位置源与移动状态，只有“统一门禁已管理且允许、前台激活、精确权限就绪、无现存 source”才自动启动；每 200 ms 轮询的是移动状态，不是把 200 ms 当距离授权。 | 附近定位为何未启动/停止、前后台切换和 movement gate 时。 |
| [NearbyLocationMessage.kt](../../app/src/main/app/ui/screens/NearbyLocationMessage.kt) | 把附近定位的不同不可靠原因转换成具体英文指引，并对精度值向上取整，避免宣称虚假误差上界。 | 用户看到定位提示或数值异常时。 |
| [AccountScreen.kt](../../app/src/main/app/ui/screens/AccountScreen.kt) | 可复用的账户表单 UI 与状态/action 模型，覆盖登录、注册、邮箱变更验证、在线/仅本机模式和删除确认。 | 调整账户控件或状态到操作的映射时。 |
| [MandatoryLoginScreen.kt](../../app/src/main/app/ui/screens/MandatoryLoginScreen.kt) | 启动强制登录页，提供注册/已有账户模式、凭据输入、状态提示和可达的隐私入口。 | 在线版尚未进入四页前的登录体验。 |
| [OnlineAccountScreen.kt](../../app/src/main/app/ui/screens/OnlineAccountScreen.kt) | 从 `OnlineRuntime` 收集认证状态，将认证动作委托给 `AccountScreen` 的薄适配层。 | 查主 UI 如何接在线认证，而不是表单布局本身。 |
| [PrivacyDetailsScreen.kt](../../app/src/main/app/ui/screens/PrivacyDetailsScreen.kt) | 登录门禁可打开的隐私说明页，解释账户、位置、内容、安全操作和删除。 | 未登录仍需查看隐私信息时。 |
| [CloudLibraryScreen.kt](../../app/src/main/app/ui/screens/CloudLibraryScreen.kt) | 云端“我的内容/反应收件箱”页，读取自己的条目、按授权 lease 解密/展示媒体，并支持删除确认。 | 在线资料库、云媒体或反应摘要时。 |
| [ConstellationCloudScreen.kt](../../app/src/main/app/ui/screens/ConstellationCloudScreen.kt) | 星座页进入云资料库的包装页面，组合标题/返回/账户入口与 `CloudLibraryScreen`。 | 从画廊进入云端内容的导航层。 |
| [OnlineSaveSheet.kt](../../app/src/main/app/ui/screens/OnlineSaveSheet.kt) | 在线发布底部表单：取得发布位置、冻结待发布内容，使用账户 operation lease 与 `PublicationCoordinator` 处理可恢复的上传步骤。 | 在线保存失败恢复、账户中途变化或发布位置卡片时。 |
| [PublishingLocationCard.kt](../../app/src/main/app/ui/screens/PublishingLocationCard.kt) | 展示发布定位的采集中、粗略、不可靠与就绪状态，并提供重试/权限操作。 | 发布页位置卡的文案和按钮状态。 |
| [LocalConstellationsScreen.kt](../../app/src/main/app/ui/screens/LocalConstellationsScreen.kt) | 本地集合管理：显示星座实例、放置/撤回日记星、打开筛选后的自有日记，并确保变更结果得到确认。 | 本地版集合菜单和星位维护时。 |
| [OwnDiariesSheet.kt](../../app/src/main/app/ui/screens/OwnDiariesSheet.kt) | 真实本地日记列表/筛选/选择/删除表单；星位标签来自已验证的集合实例，删除文案说明可能撤回星与首页实例。 | 从集合选择日记，或核对删除影响时。 |
| [GalleryUtilityPanel.kt](../../app/src/main/app/ui/screens/GalleryUtilityPanel.kt) | 画廊的分页工具面板，承载星座网格、日记条目、云/账户/设备等快捷入口和返回操作。 | 小屏画廊菜单布局、分页与快捷入口。 |
| [DeviceActivityPanel.kt](../../app/src/main/app/ui/screens/DeviceActivityPanel.kt) | 展示设备运动传感器和位置活动诊断。 | 设备能力说明或定位/运动诊断，不是业务授权判断。 |
| [WritingWeatherState.kt](../../app/src/main/app/ui/screens/WritingWeatherState.kt) | 写作天气控制器：在统一 consent/权限条件满足后获取位置、天气和地点名称，按 epoch/lifecycle 拒绝陈旧结果，并限制二次街道改善在另一定位且 100 m 内。 | 天气状态机、定位 consent、反向地理编码和地点改善规则。 |
| [WritingDraftState.kt](../../app/src/main/app/ui/screens/WritingDraftState.kt) | 写作草稿 session 与 UI 状态，串行持久化编辑，处理照片导入/移除 lease、丢失确认、外部 revision 和提交后的精确清理。 | 草稿并发、照片成员关系或“不确定时不能覆盖”逻辑。 |
| [WritingPhotoControls.kt](../../app/src/main/app/ui/screens/WritingPhotoControls.kt) | 连接系统选图器/相机与 `WritingDraftSession`，显示添加、拍摄、删除和导入状态。 | 写作页照片操作入口。 |

## components：通用 UI、日记纸张与反应

| 文件 | 实际用途 | 关联入口或什么时候看 |
|---|---|---|
| [AppChrome.kt](../../app/src/main/app/ui/components/AppChrome.kt) | 绘制四目的底部导航、图形 glyph 与星座未读提示。 | 全局底栏样式或点击目的地。 |
| [DiaryPaper.kt](../../app/src/main/app/ui/components/DiaryPaper.kt) | 日记纸张主体，组合纸色、字体、正文、天气背景、照片和反应层；`DemoBadge` 明确标识本地样例。 | 所有阅读/预览纸张的共同外观。 |
| [DiaryPhotos.kt](../../app/src/main/app/ui/components/DiaryPhotos.kt) | 按照片数量排版本地日记图片，并支持点开全尺寸查看。 | 日记附件显示。 |
| [DiaryWeatherBackground.kt](../../app/src/main/app/ui/components/DiaryWeatherBackground.kt) | 根据天气种类、昼夜、动效偏好绘制纸张背后的云、雨、雪、雾、光束和颗粒层。 | 天气氛围的整体 Compose 绘制。 |
| [WeatherAtmosphereTexture.kt](../../app/src/main/app/ui/components/WeatherAtmosphereTexture.kt) | 用确定性噪声生成远近两层天气位图材质，控制不同天气/昼夜的密度、色板与透明边界。 | 查天气纹理算法、性能预算或像素级测试。 |
| [DiaryReactions.kt](../../app/src/main/app/ui/components/DiaryReactions.kt) | 定义纸张反应类型及持续时间，并把类型路由到对应图标/播放组件。 | 反应类型入口和统一时长。 |
| [PaperReactionIllustration.kt](../../app/src/main/app/ui/components/PaperReactionIllustration.kt) | 绘制通用花朵式纸张反应及静态小图标，调用者控制播放进度。 | 通用反应视觉。 |
| [PaperReactionMotion.kt](../../app/src/main/app/ui/components/PaperReactionMotion.kt) | 把归一化进度拆成花朵缩放、茎叶显现和各花瓣错峰展开的纯数学帧。 | 调整通用反应时序或跑纯单测。 |
| [PaperThanksReaction.kt](../../app/src/main/app/ui/components/PaperThanksReaction.kt) | 绘制感谢纸片、墨迹线与星形；注释明确它是纸张插画，不是系统 feedback 卡片。 | “Thanks”反应画面。 |
| [PaperThanksMotion.kt](../../app/src/main/app/ui/components/PaperThanksMotion.kt) | 计算感谢纸片的进入、旋转、墨迹逐行显现与离开帧。 | “Thanks”动效曲线。 |
| [PaperEmpathyReaction.kt](../../app/src/main/app/ui/components/PaperEmpathyReaction.kt) | 绘制手掌/星形等共情反应图形，适配明暗纸张。 | “I hear you”类反应画面。 |
| [PaperEmpathyMotion.kt](../../app/src/main/app/ui/components/PaperEmpathyMotion.kt) | 计算共情反应的手、星、光与整体透明度帧。 | 共情动效曲线。 |
| [PaperApproachingHug.kt](../../app/src/main/app/ui/components/PaperApproachingHug.kt) | Canvas 绘制两个人靠近并拥抱的姿态、手臂和配色。 | 拥抱反应画面。 |
| [PaperApproachingHugMotion.kt](../../app/src/main/app/ui/components/PaperApproachingHugMotion.kt) | 把进度转换为两人位置、倾斜、拥抱臂与光晕帧。 | 拥抱时序/边界测试。 |
| [EncounterArrivalEffect.kt](../../app/src/main/app/ui/components/EncounterArrivalEffect.kt) | 对每个 encounter 只消费一次到达效果；可读且持续稳定 200 ms 后才播放视效/触觉音频，失活或撤销即停止，续期同一 location lease 不会重播。 | 真实附近日记出现时的 200 ms 感官反馈边界。 |
| [DiscoveryArrivalVisual.kt](../../app/src/main/app/ui/components/DiscoveryArrivalVisual.kt) | 绘制从指定星点飞近的星与到达雾光，进度由外层 encounter 效果控制。 | 到达反馈的视觉部分。 |
| [DiscoverStarfield.kt](../../app/src/main/app/ui/components/DiscoverStarfield.kt) | 发现页背景星场，使用确定性星尘、慢速漂移和中心留白。 | 发现页背景，而非服务端发现逻辑。 |
| [GalleryConstellationScene.kt](../../app/src/main/app/ui/components/GalleryConstellationScene.kt) | 画廊星座主场景，绘制星、连线、粒子、apparition/expanded 动效并处理命中。 | 画廊星图渲染和交互。 |
| [GallerySceneMath.kt](../../app/src/main/app/ui/components/GallerySceneMath.kt) | 提供画廊点归一化与最近可点星选择的纯函数。 | 命中范围或坐标换算。 |
| [GalleryStarDust.kt](../../app/src/main/app/ui/components/GalleryStarDust.kt) | 按星座实际连线生成稳定的 haze motes 与不规则 flecks，避开主星锚点。 | 画廊星尘分布。 |
| [GalleryApparitionMotion.kt](../../app/src/main/app/ui/components/GalleryApparitionMotion.kt) | 为不同 apparition 类型维护可复用的粒子 mesh，并随时间更新。 | 画廊显现粒子运动。 |
| [GalleryExpandedMotion.kt](../../app/src/main/app/ui/components/GalleryExpandedMotion.kt) | 根据星座图形与真实点位生成展开态粒子 mesh。 | 画廊展开动画。 |
| [DiaryStarLighting.kt](../../app/src/main/app/ui/components/DiaryStarLighting.kt) | 计算并绘制某颗日记星到达时的目标光、邻星脉冲与连线显现。 | 保存后点亮星位或反应定位。 |
| [DiaryStarRequestGate.kt](../../app/src/main/app/ui/components/DiaryStarRequestGate.kt) | 小型 token 所有权门，保证旧的异步星位请求不能结束或释放新的请求。 | 放星并发/取消竞态。 |
| [SavedDiaryConstellationCard.kt](../../app/src/main/app/ui/components/SavedDiaryConstellationCard.kt) | 保存成功后的星座卡，读取真实集合并发起/恢复星位放置，结合生命周期和 request gate 接收结果。 | 写作保存后放星体验。 |
| [MemoryCompassCard.kt](../../app/src/main/app/ui/components/MemoryCompassCard.kt) | 显示指向日记保存位置的罗盘、距离/方位和故障；无真实地点的演示按钮只在 debug 出现。 | 画廊日记的本地记忆罗盘。 |
| [MemoryCompassState.kt](../../app/src/main/app/ui/components/MemoryCompassState.kt) | 组合传感器朝向、当前位置与目标点，产生罗盘 delta、强度、距离和错误状态，并随 active/lifecycle 启停。 | 罗盘状态来源与权限/传感器故障。 |

## space：首页 OpenGL 天空与纯几何

| 文件 | 实际用途 | 关联入口或什么时候看 |
|---|---|---|
| [CelestialScene.kt](../../app/src/main/app/ui/space/CelestialScene.kt) | Compose 到 `GLSurfaceView` 的桥接，创建 `CelestialView`、传递快照/活动状态，并把触摸交给渲染器命中。 | 首页 3D 场景生命周期与 Android View 互操作。 |
| [CelestialRenderer.kt](../../app/src/main/app/ui/space/CelestialRenderer.kt) | OpenGL ES 渲染器，管理星球、大气、背景星、星座、尘埃/丝线/锚点着色器和触摸投影。 | GPU 绘制、shader、帧循环或资源释放。 |
| [CelestialGeometry.kt](../../app/src/main/app/ui/space/CelestialGeometry.kt) | 定义八个星座模板、3D 向量/轨道变换、遮挡与投影相关几何。 | 首页星座形状与空间数学。 |
| [HomeSkySnapshot.kt](../../app/src/main/app/ui/space/HomeSkySnapshot.kt) | 把真实集合实例转换成不可变首页天空快照，保留实例/日记身份、限制显示条件，并提供最近可见星命中。 | 为什么某星座/日记出现在首页，或重复星座身份。 |
| [ConstellationStyle.kt](../../app/src/main/app/ui/space/ConstellationStyle.kt) | 按深度和朝向计算主星、次星、连线的尺寸与渐隐，钳制异常输入。 | 远近星座视觉层级和防跳变。 |
| [ConstellationFilaments.kt](../../app/src/main/app/ui/space/ConstellationFilaments.kt) | 为所有星座（包括天鹅座）生成通用的浅层尘丝、侧光和主星数据。 | 通用首页星座粒子几何。 |
| [DiscoverSkyField.kt](../../app/src/main/app/ui/space/DiscoverSkyField.kt) | 生成发现页远近星层及连续漂移/闪烁位置，处理超长时间与非法输入。 | `DiscoverStarfield` 的纯数据与运动算法。 |
| [DiscoveryStardust.kt](../../app/src/main/app/ui/space/DiscoveryStardust.kt) | 生成到达星周围前后两层、避开中心的椭圆星尘云。 | 到达场景粒子布局。 |
| [DiscoveryArrival.kt](../../app/src/main/app/ui/space/DiscoveryArrival.kt) | 计算飞来星的透视位置/尺寸，以及日记与天气层半秒交叉淡入。 | 到达转场的纯数学。 |
| [DiscoveryLight.kt](../../app/src/main/app/ui/space/DiscoveryLight.kt) | 计算到达近景雾光和曝光预算，避免亮度跳变/非法值闪烁。 | 到达光效边界。 |


## 手机版测试入口

- [`app/src/test`](../../app/src/test) 保存 UI 状态、几何、动效和展示规则的 JVM 单元测试。
- [`app/src/androidTest`](../../app/src/androidTest) 保存需要 Android 运行时、Compose 或设备能力的测试。
- desktop 专用测试没有恢复。历史测试结果仅可在 `delivery/` 的既有验收文档中回看，不能据此宣称当前源码已通过测试。

## 阅读建议与边界

- 想理解正式运行链路，先看 `MainActivity.kt`、`StargleanApp.kt`、`AppEntryGate.kt`、`AppLocationGate.kt`，再按四个主页面进入；不要从 debug Intent 样例反推 release 行为。
- 想理解附近阅读，按 `LiveDiscoverScreen.kt → ForegroundNearbyState.kt → LiveDiscoveryController.kt → NearbyReadingPresentationTest.kt/LiveDiscoveryEnvelopeTest.kt` 阅读。200 m 是服务端阅读规则；200 ms 是 encounter 到达反馈的本地稳定等待，两者不是同一概念。
- 测试文件不是生产能力；是否通过以当前代码上的实际 JVM / 设备测试结果为准。
