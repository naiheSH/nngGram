# nngGram · 聊天与媒体

桌面客户端：Windows x64、macOS x64/arm64、Linux x64/arm64。Kotlin Multiplatform / Compose / Miuix `0.9.4-rc01` / Nucleus `2.5.18` + Tao / 官方 TDLib Java JNI。Windows 另有 GraalVM Native Image 与 MSI。Android 不是这个桌面入口。

应用默认显示真实 TDLib 运行状态：校验并加载原生库，读取版本与提交，再读取本地配置、准备数据目录并初始化单个客户端。正式构建已内置应用身份，开始页点击“开始使用”后可提交手机号、验证码和二步验证密码。登录后读取账号已有的聊天文件夹、主列表与归档，可批量阅读历史、回复、编辑、删除和转发消息，收发文本、照片、贴纸、视频和文件，并同步已读状态。视频在聊天区域内播放。引擎、账号授权和网络连接分别订阅并显示，支持本地 SOCKS5 / HTTP 代理、分类诊断、安全事件记录、停止和重启。

当前还支持全局及聊天内搜索、发送者/日期/媒体筛选、真实最近搜索与常用联系人、群简介和管理员、置顶消息浏览及定位。未读聊天按真实已读边界打开，可继续向较新消息翻页；列表右键提供归档、置顶、通知、已读标记和加入普通文件夹。消息菜单可快捷回应、搜索可用普通表情，并按原生权限复制消息链接。文件夹标签位置与外观仅保存在本机，页面切换、选中指示和菜单使用过渡动画。这些是当前接入范围，尚不覆盖官方客户端全部功能。

## 运行

默认入口是 Windows、macOS、Linux 共用的 JVM 桌面程序，分发包自带 Java 运行时。平台、CPU 架构、TDLib 目录、媒体工具名称、剪贴板、音频和本地保护文件提交均按当前主机选择，不再回退到 Windows。Windows Native Image 是可选的专用后端。

先按“从源码构建”准备当前主机的依赖，再执行：

```sh
# macOS / Linux；Windows 将 python3 换成 python
python3 scripts/desktop.py check
python3 scripts/desktop.py build --installer
# 正式构建要求自己的 Telegram API 身份
python3 scripts/desktop.py build --release --installer
```

产物统一位于 `build/distributions/<平台-架构>/`，包括应用压缩包、DMG/MSI/DEB、SHA256 和构建信息。打包后实际运行应用的 TDLib 离线验收及主界面离屏渲染；Mac 还会挂载最终 DMG，校验其中应用的原生初始化和签名。无凭据预览包可以构建和检查界面，不能登录真实账号。Mac 包目前为临时签名，未做 Developer ID 签名和公证；最低系统版本从实际原生依赖读取，不冒充兼容旧版系统。

`check` 默认执行完整桌面模块与共享 JVM 测试，并启用 TDLib/native/media 验收。业务状态测试使用可注入的模拟后端；界面测试统一合成滚轮尺度，并按主机使用 Command/Ctrl 快捷键。依赖真实 Windows API 的集成测试仅在 Windows 执行，其他平台会明确标为跳过；模拟状态测试在所有平台执行。`--full-tests` 保留为兼容参数，完整回归已经是默认行为。

应用图标统一使用 `desktopApp/src/main/resources/branding/` 的纸飞机资源，分别提供 ICNS、ICO、PNG；可在 macOS 用 `swift scripts/generate-app-icons.swift build/app-icons` 重新生成，再将 PNG/ICNS/ICO 复制到资源目录。macOS 的内容界面仍使用 Compose/Miuix，外层 Tao 窗口使用系统红黄绿按钮；这与 JVM 分发或 Windows 可选 Native Image 编译方式无关。全屏时系统会隐藏窗口按钮，移到屏幕顶部可显示。

`./scripts/build-macos.sh [--release]` 是上述通用入口的 Mac 便捷包装，会在缺少本机 TDLib 或媒体包时调用准备脚本。JVM 开发启动为 `./gradlew :desktopApp:run`（Windows 使用 `gradlew.bat`）。

### Windows Native Image 可选入口

先按下方“从源码构建”准备 TDLib、FFmpeg、应用凭据与工具链，然后构建独立原生程序：

```powershell
.\scripts\build-desktop-native.ps1 -GraalVm '.\build\toolchains\graalvm-ce-25i3\graalvm-community-25.3.4.1+1.1' -MsvcRoot '.\build\toolchains\msvc-portable' -BuildName rich-stream -RunTests -RequireTelegramApi
```

上述 Windows 命令的原生产物写入 `desktopApp/build/rich-stream/compose/binaries/main/graalvm-app/nngGram/nngGram.exe`。移动或分发时保留整个 `nngGram` 目录，包括 TDLib、媒体工具及运行依赖；用户无需安装 JVM。这里生成的是应用目录，不是 MSI 安装包。原生构建与自动测试通过不等于真实账号、界面或 GPU 验收通过。

JVM 开发启动与单独测试仍可使用：

```powershell
.\gradlew.bat :desktopApp:run
.\gradlew.bat :desktopApp:assemble :desktopApp:test :shared:jvmTest --console=plain
.\gradlew.bat :shared:jvmTest -PnativeSmoke --console=plain
.\gradlew.bat :shared:jvmTest -PmediaSmoke --console=plain
.\scripts\verify-tdlib.ps1 -Jdk E:\Java\Java_21
```

照片、WebP 贴纸和 TGS 动画使用随应用携带的 Skia；WebM 视频贴纸、视频信息检查及聊天内播放使用 FFmpeg 工具。可用 `scripts/prepare-media.ps1 -FfmpegDirectory '已有 FFmpeg 的 bin 目录'` 准备 `ffmpeg.exe` 和 `ffprobe.exe`，脚本将 EXE、DLL、许可证与版本哈希清单复制到 `native/windows-x64/media`，打包时一并携带。聊天播放器用 FFmpeg 解码画面和音频，在 Compose 内绘制并通过 JNA 调用 Windows WinMM 输出 PCM 声音，无需 FFplay。`-PmediaSmoke` 使用这些工具生成并解码合成媒体，不读取个人照片。

隔离构建可能使用独立目录，产物路径以本次 BuildName 为准。若旧窗口仍开着，请先正常关闭再运行新版。

桌面聊天页采用自适应三栏：导航栏宽 72–88 dp，会话列表默认宽 260–354 dp，其余空间留给消息区。列表与会话之间可拖动调整，最窄为 72 dp 头像栏，最宽 480 dp，并为聊天区保留可用空间；搜索会展开列表，宽度仅存于本次界面内存。默认 1160×840 窗口启用紧凑间距，最小可缩至 380×540，低于 980 dp 切换列表与会话单栏。低于 600 dp 时收起导航栏，账号入口移至列表标题旁，分类与归档通过可横向滚动的顶部标签访问，并保留真实未读计数。Miuix 悬浮会话头保持固定高度与控件尺寸，首选宽度上限为 940 dp，按可用空间收窄。列表顶部搜索入口打开真实搜索页，列表采用独立圆角卡片，置顶标记来自原生各列表状态；滚动条与回到顶部按钮随列表状态显示。底部为单条灰色输入面板，附件菜单提供图片、视频、文件和文字格式，右侧保留贴纸与发送，回复摘要及缩略图整合在同一面板。“未读”筛选当前列表随自动加载到达的会话，再次点击可取消；不会改变原生列表顺序。

聊天区使用本地打包的官方 Telegram Desktop 浅色渐变/涂鸦和配套深色背景，[资源来源、原始文件与许可](third_party/telegram-wallpaper/README.md)随项目保存。同一真实发送者的连续消息间距为 3 dp，分组间为 10 dp；跨日期、未读边界、方向或原生发送者身份变化会分组。群操作使用原生 Miuix 弹出列表的定位与开合过渡，媒体分类使用原生可滚动 TabRow，鼠标竖向滚轮也可横向浏览标签。

可用 `:desktopApp:run --args='--desktop-main-preview'` 查看默认 1160×840 的显式离线主界面；`--slim` 为 380×840，`--compact` 为 780×600，`--design-size` 为 1494×993，三者互斥，均可附加 `--dark`。该入口只使用合成资料与聊天，不创建账号引擎或读取本地配置。群资料的离线媒体索引包含聊天区尚未显示的合成图片和视频缩略图，支持分类、分页及原消息跳转；样例视频不含可播放文件。合成图片位于自有临时目录，关闭时清理。`--desktop-main-render <绝对路径的新 PNG> [--slim | --compact | --design-size] [--dark]` 使用相同尺寸规则和实际 Compose 布局离屏渲染；默认尺寸还可加 `--info-open` 检查首开资料页。输出目录必须已存在，文件不能已存在。离屏 PNG 用于布局检查，不能代替实际 Tao 窗口的 DPI、字体锐度或中文输入法验收。

IDE 的普通 Gradle 运行与 `hotRun` 均会自动传入项目当前主机对应的 `native/<平台-架构>` 绝对路径。Hot Reload 会复制绑定 JAR，但不复制其旁边的 DLL，因此不能依赖热重载目录查找原生库。修改 Gradle 配置后，请重新同步 Gradle、正常关闭旧窗口并重新运行；热更新无法替换已启动 JVM 的参数。若设置了 `nnggram.tdlib.dir` 或 `NNGGRAM_TDLIB_DIR`，仍优先使用显式目录。

发布包在构建时注入 Telegram 应用身份，开始页无需填写 API。自行构建时复制 `telegram-api.example.properties` 为忽略的 `telegram-api.local.properties` 并在本机填写自己的凭据，或由 CI Secrets 注入 `NNGGRAM_API_ID` / `NNGGRAM_API_HASH`。构建、CI 与安装方法见下文。旧本地凭据配置仍兼容；不要把真实凭据、验证码、会话或数据库提交到仓库。

“查看诊断”展示真实原生版本、提交、加载目录、运行时信息和安全事件日志位置。最近事件显示 UTC 时间、运行轮次、三个独立状态及错误分类，不记录 API 凭据、代理密码、TDLib 原始错误或完整响应。日志有大小上限和轮转，写入失败不会阻止客户端运行。

停止、重启或关闭窗口会等待 TDLib 的关闭事件，再释放目录锁。超时后保留窗口和客户端，可继续等待；不会将超时显示为成功退出。正常停止后可以在同一窗口重新启动。

### 登录现有账号

1. 初始化完成后，在“登录 Telegram”中输入带国家或地区代码的手机号，例如 `+8613800000000`，点击“获取验证码”。
2. 按界面显示的渠道查看验证码，可能来自已登录的 Telegram、短信或电话。验证码也可能是单词或短语，请按收到的内容填写。
3. 若账号启用了二步验证，继续输入 Telegram 二步验证密码。密码使用遮罩显示，保留输入中的大小写和空格。
4. 收到 TDLib 的已授权状态后，界面才显示“账号已登录”。正常关闭会保留 TDLib 的本地会话，下次启动可恢复已有授权。

请求处理中禁止重复提交；验证码或密码错误可修正后重试，不会关闭引擎。可通过“更换手机号”切换号码。验证码与密码提交后立即从输入框清空，登录输入不写入配置、运行快照或安全事件日志；会话和数据库仍应作为私人数据保管。

当前不包含账号注册、登录邮箱验证、二维码/其他设备确认、Premium 购买、验证码重发和密码找回；遇到这些额外要求会给出明确提示。验证采用模拟登录响应及真实原生离线初始化，没有自动提交个人手机号或验证真实账号登录。

### 聊天文件夹与消息

1. 登录且连接方式应用完成后自动加载完整主聊天列表，无须点击“加载更多”。左侧窄栏读取账号在 Telegram 中已有的聊天文件夹，保留名称与顺序，显示未读聊天数；点击文件夹按其真实分类显示聊天。侧栏不预设“联系人、群组、频道”入口；这些仅是文件夹编辑器里的可选规则。主列表、归档和文件夹打开后都会自动串行加载至原生明确结束，各列表状态独立；切换会话不会打断加载，只有失败时显示重试入口。
2. 有已知已读边界的未读聊天会定位到第一条真实收到的未读消息，并冻结本次打开的未读分界；无可靠边界时读取最近历史，不推算虚假锚点。可加载更早历史，接近当前片段末尾时补取较新消息，或明确回到最新。每批最多补齐 50 条有效、不重复消息；短缓存页会继续串行补取。远离最新位置时，新消息不会直接拼接到不连续的旧历史片段。正在打开的聊天仍接收内容修改、删除和发送状态更新，列表最近消息及未读数由 TDLib 更新。
3. 在底部输入文本，点击发送。“文字格式”支持选区粗体、斜体、下划线、删除线、隐藏文字、代码、带语言的代码块、引用块和链接，媒体说明也可使用。输入三反引号围栏（例如 python）、行内反引号、`**粗体**`、`_斜体_`、`__下划线__`、`~~删除线~~`、`||隐藏文字||`、`[标题](https://example.org)`，发送时转换为 Telegram 原生实体；格式面板可预览。请求处理中禁重复提交；请求接受后只清空与本次提交匹配的草稿，消息继续等待 TDLib 确认发送成功或失败。
4. 会话可见且窗口获得焦点时，停留片刻后自动提交可见收到消息的阅读状态；切到后台、最小化或打开遮挡对话框时停止自动已读。也可手动“标为已读”或“标为未读”；手动未读保留到重新选择该聊天或明确标为已读。发出消息的已读状态跟随服务端更新。
5. 账号菜单“管理文件夹”可新建、重命名、调整普通文件夹的包含/排除规则并删除分类，操作会同步到 Telegram。删除分类不会退出群组或删除聊天；共享文件夹保持只读。标签可放左侧或顶部，并选择默认、纯文本、文字和图标、仅图标；这两个本地偏好写入配置同目录的 `ui-folders.properties`，不包含账号或聊天内容。列表右键的“添加到文件夹”保留原生规则和置顶设置，免打扰状态同时考虑聊天覆盖及原生类别默认值。

“状态与设置”可返回连接、诊断、停止和重启页面。群组和频道分别按当前成员的文字、照片、贴纸、视频和文档权限启用发送；服务端仍可能因限制拒绝请求。

搜索包含已有/公开聊天、频道、机器人，以及照片、视频、文件、链接、音乐和语音等消息分类；“应用”当前检索机器人，不启动小程序。全局与聊天内消息支持发送者和日期筛选，按原生游标分页，部分组合需在每页原生结果中过滤，无法取得准确总数时不虚构计数。频道/应用会话标题列表不支持日期或作者筛选。公开贴文搜索仅请求免费额度（`starCount=0`），额度不足显示限制，不尝试付费。管理员姓名来自真实用户解析；会话信息首次打开即独立查询照片/视频、文件、链接、音乐和语音索引及服务端近似计数，按原生游标分页，不依赖当前已加载历史。资料页仅请求原生缩略图，点击后定位原消息并复用现有预览/播放入口；置顶列表另由原生查询取得。

### 回复与谁已读

右键消息选择“回复”，输入框上方显示引用目标，可取消；文字、照片、贴纸、视频和文件发送都会携带该引用。提交失败保留回复，成功回执仅清除当时提交的引用，不会误清新选择的消息。已收到消息中的回复摘要会按需补全；暂时无法读取原消息时保留引用提示。

在消息正文或媒体说明中划选文字，再右键选择“引文回复”，输入区立即显示选中片段并聚焦编辑器，无需在弹窗内再次选择。重复文字、emoji 与跨代码块选区按原文 UTF-16 位置定位，并保留 Telegram 允许的原文格式；原有草稿不变。发送前重新核验原消息、回复权限和服务端引文长度限制，原文已变更时保留草稿并提示重新选择。收到的文字及媒体说明按富文本显示；代码块带语言标题、等宽代码区和复制按钮，长行可横向滚动；引用块有独立底色与边线，链接通过默认浏览器打开。

消息右键菜单上方可选择快捷回应，更多面板支持分类及关键词搜索服务端允许的普通表情；消息下方显示回应数量、自己的选中状态，以及原生提供的近期回应者头像，点击已选普通表情可取消。添加前重新检查消息和可用表情，不启用付费回应；自定义和付费表情仅显示已有计数。复制消息链接前查询原生 `canGetLink` 并核验消息身份，不拼接或猜测地址。

点击回复摘要可跳到本聊天已载入的原消息并高亮，随后可返回引用前的位置；尚未载入或跨聊天的原文会明确提示。`Enter` 发送，`Shift/Ctrl+Enter` 换行，`Ctrl+Shift+Enter` 发送，输入法组合期间不触发发送；空输入框按 `↑` 查询原生权限后编辑最近有文字的已发送消息，`Ctrl+O` 打开文件附件，`Esc` 取消回复或编辑。

右键已发出的消息选择“谁已读”，查询服务端提供的成员及阅读时间。Telegram 只对符合条件的近期群消息或私聊提供这类信息；不可获取、尚未阅读、消息过旧或双方隐私设置限制会分别显示可用的服务端结果，不把未读数推算成成员名单。

### 编辑、删除与转发

右键菜单会先查询这条消息的实际权限，仅展示可用操作。编辑支持正文、媒体说明、格式和链接；内容未改时保留完整原生实体，修改时保留未受影响的格式，允许只改格式。媒体说明原有的上下显示位置保留。提交失败保留编辑内容，迟到回执不会覆盖新收到的内容更新。

删除会明确显示服务端允许的“仅为自己删除”或“为所有人删除”，确认后才提交；不把仅为自己删除自动扩大成所有人删除。转发从已加载且可发送的聊天中选择目标并再次确认，保留原发送者引用和说明。受保护消息不能绕过转发限制，所有操作均不启用付费发送。

### 照片、贴纸与附件

- 可见聊天头像、消息发送者头像、照片与贴纸按需下载，展示进度及重试入口。发送者头像来自原生用户或聊天身份，已下载头像在菜单打开或窗口失焦时仍保持显示；无可用头像时才使用首字回退。照片点开后在当前聊天区域内按比例适配，支持放大、缩小、重置、滚轮缩放及拖动，关闭后返回原会话位置；带剧透标记的照片需先主动揭示。
- “图片”打开本地选择器，支持静态 JPEG、PNG、WebP，发送前可预览并添加说明。发送失败保留选图和说明；发送图片或贴纸不会清空另外正在编辑的文字草稿。
- “贴纸”读取最近使用及已安装的贴纸包，可预览并发送 WebP、TGS 动画和 WebM 视频贴纸。动画在不可见或窗口失焦时停止。
- “视频”和“文件”可选择本地附件并填写说明。视频消息使用经过检查的 H.264 MP4，其他格式可作为文件发送；不会把“按文件发送”的图片或视频自动转换成媒体消息。单个本地附件检查上限为 4 GB，服务端仍会按账号权限及限制决定是否接受。
- 视频画面至少一半进入聊天可视区域时自动静音边加载边播放，可通过音量按钮恢复声音，同一时间仅一个视频播放；带剧透标记的视频需主动播放。仍可点击气泡播放按钮；点视频画面、缩略图或播放器“展开”在聊天区域内打开大图，按比例居中，底部浮动控制条显示播放/暂停、音量、时间和进度，说明文字浮在画面下方。加载或缓冲时也可点击画面展开；播放、音量与进度控件独立操作。展开时自动恢复查看器的正音量；返回聊天前清掉排队音频，再恢复聊天原有的静音/音量和播放/暂停状态，播放进度保持连续。时间轴使用 Miuix ProgressBar，无滑块圆点，支持点击/拖动跳转；播放进度按播放时钟平滑更新，浅色底条为已下载字节比例（并非精确的可播放时间区间）。后台持续预取数据，只读取 TDLib 确认下载完成的区间。
- 播放前只探测一次音视频信息，先显示可用首帧；音频启动超过 750 ms 时继续静音播放。流式探测与解码限制分析量，无数据进展 15 秒后结束等待并显示重试入口，实际首帧时间仍取决于网络和源文件。
- 查看器支持 K/空格播放暂停、J/L 前后 10 秒、Esc 返回。窗口失焦或打开其他操作时暂停；视频画面完全滚出、停止播放、切换会话或删除视频消息会释放解码和音频资源。手动暂停不会被自动播放覆盖，手动停止后需滚出该消息再进入才会重新自动播放。空格切换播放、Esc 收起大图或停止气泡播放。文件需主动下载，下载完成后按原生保存权限另存为。受保护内容的播放不改变保存与转发限制，通用文件不会自动打开或执行。

暂不包含任意新建聊天、论坛话题导航、跨聊天引用回复、语音录制、视频转码、独立音频消息播放、付费消息及秘密聊天媒体。照片发送限制为 10 MB、宽高和不超过 10000、长宽比不超过 20；说明最多 1024 个 Unicode 字符。

草稿、搜索条件、消息链接及附件选择仅保留在内存；消息、文件夹名称、聊天标题、文件路径与发送者姓名不进入安全事件日志或模型的字符串诊断。TDLib 仍管理私人数据库、原生最近搜索记录和下载文件；最近搜索仅在明确点击清除时提交清除请求。合成界面和自动化检查不代表真实账号联网功能、中文输入法、闲置 GPU 或本轮 Native Image EXE 已通过验收。

### 可选代理

默认 `proxy_type=none` 直接连接。已有配置文件不会被改写；需要代理时可在本地自行添加以下内容，将端口改成自己的代理监听端口：

```properties
proxy_type=socks5
proxy_host=127.0.0.1
proxy_port=1080
proxy_username=
proxy_password=
```

`proxy_type` 可选 `none`、`socks5`、`http`（HTTP CONNECT）。代理配置先应用，再启用网络；应用失败时保持网络关闭并显示单独的恢复提示。直连只禁用当前代理，不删除 TDLib 已保存的代理。网络等待或连接失败不会触发重建客户端，也不会被误记为初始化失败。

验收脚本使用合成参数、`NetworkTypeNone` 和独立测试目录，验证 10 个独立 JVM 启停、同一引擎重启及故障恢复；不会读取个人配置，也不代表真实账号登录或代理连通验证。

## 窗口

- 由 Nucleus/Tao 创建窗口，JVM 开发与 Native Image 使用相同窗口入口。文件选择和保存使用 FileKit 的 Windows 原生对话框并绑定当前窗口，剪贴板通过 Win32 API 接入。
- 默认 1160 × 840，最小 380 × 540，并适应屏幕可用区域。
- 状态页小于 980 dp 时纵向排列；聊天页使用左侧文件夹栏，宽屏并排显示列表与会话，窄屏在列表和会话间切换。支持系统、浅色、深色主题和滚动。
- “查看界面预览”保留任务 1–4 的状态/网络/中文输入演示，明确标注演示来源；不改变后台引擎。
- 连接状态包括尚未获知、等待网络、连接代理中、连接中、更新中和已连接。

编译及合成测试不能代替真实安装、账号登录和性能验收。

## 从源码构建

共同需要 JDK 21、Python 3.9+、Git。原生 TDLib 还需要 CMake、Ninja、gperf、C++ 编译器、OpenSSL 和 zlib。当前支持 Windows x64、macOS x64/arm64、Linux x64/arm64；不能把其他平台的 JAR/动态库混装到当前包。CI 安装步骤见 [.github/actions/prepare-native/action.yml](.github/actions/prepare-native/action.yml)。

macOS 使用 `brew install cmake ninja gperf openssl ffmpeg`，设置 `JAVA_HOME` 为 JDK 21 后运行 `sh scripts/build-tdlib.sh` 和 `sh scripts/prepare-media-macos.sh`。脚本复制依赖、许可证并重写动态库路径，不依赖收件人的 Homebrew 或构建者的 JDK 目录。

Ubuntu 24.04 使用 `sudo apt-get install cmake ninja-build g++ gperf libssl-dev zlib1g-dev ffmpeg`，设置 JDK 21 后运行 `sh scripts/build-tdlib.sh`。Linux 包使用系统 FFmpeg/OpenSSL；DEB 声明运行依赖，使用 tar.gz 的主机需自行安装这些依赖。无显示器的验收需要 `xvfb xauth` 并通过 `xvfb-run -a python3 scripts/desktop.py build --installer` 执行。

Windows 使用 PowerShell 7.2+ 和 MSYS2 UCRT64。只有可选 Native Image 还需要独立 GraalVM、MSVC 和 Windows SDK：

1. 在 MSYS2 UCRT64 安装 `git gperf mingw-w64-ucrt-x86_64-gcc mingw-w64-ucrt-x86_64-cmake mingw-w64-ucrt-x86_64-ninja mingw-w64-ucrt-x86_64-openssl mingw-w64-ucrt-x86_64-zlib`。
2. 运行 `./scripts/build-tdlib.ps1 -MsysRoot 'MSYS2目录' -Jdk 'JDK21目录'`，从官方固定提交构建完整 Java/JNI 绑定、DLL、依赖与 JNI 元数据。
3. 运行 `./scripts/prepare-media.ps1 -FfmpegDirectory 'FFmpeg的bin目录'`。该目录应包含 `ffmpeg.exe`、`ffprobe.exe` 和所需 DLL，其上一级保留 `LICENSE.txt`。CI 使用固定 SHA256 的 shared 发行包。
4. 在 [Telegram API development tools](https://my.telegram.org) 申请自己的应用身份。复制 `telegram-api.example.properties` 为 `telegram-api.local.properties`，仅在本机填写 `api_id`、`api_hash`，不要提交该文件。
5. 开发运行用 `./gradlew.bat :desktopApp:run --no-configuration-cache --no-build-cache`；原生发布使用上文 `build-desktop-native.ps1`，必须带 `-RequireTelegramApi`。

构建也可从环境变量 `NNGGRAM_API_ID` / `NNGGRAM_API_HASH` 读取，整对环境变量优先于本地文件；不拼接两个不同来源的字段。正式发布缺少或误填凭据会失败，错误不打印值。原有运行时本地配置中完整的 API 身份仍可覆盖内置身份。普通无凭据源码检查可编译和运行离线测试，但不能登录。

## GitHub Actions

[Desktop CI](.github/workflows/ci.yml) 支持 push、PR 和手动运行，使用同一构建入口覆盖以下五个目标。runner 标签依据 [GitHub 官方说明](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)。

| 目标 | Runner | 应用及安装格式 |
| --- | --- | --- |
| Windows x64 | `windows-2022` | ZIP、MSI |
| macOS arm64 | `macos-15` | APP ZIP、DMG |
| macOS x64 | `macos-15-intel` | APP ZIP、DMG |
| Linux x64 | `ubuntu-24.04` | tar.gz、DEB |
| Linux arm64 | `ubuntu-24.04-arm` | tar.gz、DEB |

macOS CI 固定使用 macOS 15 runner 与 `MACOSX_DEPLOYMENT_TARGET=15.0`，打包时逐个检查原生依赖；依赖要求高于 15.0 会直接失败，不会静默提高最低系统版本。产物的 `build-info.json` 记录实际最低系统要求。

默认 `checks` 矩阵不接触应用 Secrets：五个目标都执行脚本与安装器契约、完整 JVM/UI 回归、打包后真实 TDLib 验收，并上传明确标为 `preview-` 的无凭据预览包。测试失败会阻止正式打包，不通过 `continue-on-error` 隐藏。Linux 使用 Xvfb；各原生缓存按 OS、架构、runner 镜像、源码 pin 和构建脚本隔离。

正式 `distributions` 矩阵须由仓库管理员设置：

| 类型 | 名称 | 用途 |
| --- | --- | --- |
| Repository Variable | `DISTRIBUTION_REPOSITORY` | 填写自己的 `owner/repository`，本仓库为 `badnng/nngGram` |
| Repository Secret | `NNGGRAM_API_ID` | 自己申请的 Telegram API ID |
| Repository Secret | `NNGGRAM_API_HASH` | 与之配套的 API Hash |

只有匹配仓库的默认分支或 `dev` 的非 PR 构建，且五个平台的检查全部通过，正式打包步骤才注入应用身份，生成相应平台的应用、安装包、校验和和验证记录。产物保存 14 天，不自动创建 Release。缺失身份会明确失败，不把预览包当正式包。源码中不公开真实凭据；客户端二进制内的应用身份可被提取，不能当作服务端秘密。

原 Windows GraalVM/MSVC 流水线保留为可选 `native` 作业，额外设置 `ENABLE_WINDOWS_NATIVE=true` 才运行，不再作为其他平台构建的前置条件。系统 Toast、Windows 电源恢复回调和按原生句柄精确删除的自动保存仍是 Windows 专用增强；其他平台保留应用内通知、声音、应用活动自动锁定和手动保存，不调用 Win32 伪装为已支持。

可选 Telegram 分发沿用自动回退：设 `TELEGRAM_ENABLED=true`，提供 `TELEGRAM_BOT_TOKEN` 和 `TELEGRAM_CHAT_ID` Secrets。可选的 `TELEGRAM_API_ID` / `TELEGRAM_API_HASH` 用于临时 Bot API 服务，和桌面应用身份分开。任一为空时跳过本地服务；50,000,000 字节以内发附件，超限发送 Actions 下载链接并提示需要登录 GitHub。未启用时不发送通知。

构建方式参考了 [Kotatogram Windows CI](https://github.com/kotatogram/kotatogram-desktop/blob/dev/.github/workflows/win.yml) 与 [Forkgram 构建说明](https://github.com/forkgram/TelegramAndroid/blob/dev/BUILDING.md)，保留本项目的 Kotlin/Nucleus/Tao 工具链，不使用其他客户端的应用凭据。

## 安装与数据目录

安装器提供两个位置选择：程序本体目录，以及统一的聊天/文件/数据目录。默认分别是 `%LOCALAPPDATA%\Programs\nngGram` 与 `%LOCALAPPDATA%\nngGram\data`。数据库、TDLib 媒体缓存、账号隔离目录和默认下载均在数据根目录中；少量启动路径配置与界面偏好仍放在 `%LOCALAPPDATA%\nngGram`。

两个目录不能相同或互相包含。已有配置时保留原数据位置，不自动迁移账号；升级、卸载不删除账号数据。首次安装的默认保存目录为数据目录下的 `downloads`，应用内明确修改的下载偏好仍优先。

用已有 WiX 3.11.2 工具包打包：

```powershell
.\scripts\package-desktop.ps1 -AppDirectory '.\desktopApp\build\rich-stream\compose\binaries\main\graalvm-app\nngGram' -Version 0.1.0 -BuildName release-package -WixDirectory 'WiX工具目录'
```

`build/release-package` 内包含 MSI、ZIP、`SHA256SUMS.txt`、`build-info.json` 和逐文件哈希。Native Image 使用体积优化，不附带完整 JVM；打包副本排除未使用的 ffplay 和独立调试符号，使用高压缩 ZIP/CAB，保留完整运行 DLL、JNI 声明及许可证。实际大小见每次构建报告。

## 更新 TDLib

可以随时更新，但不会在每次 CI 中无条件跟随上游 HEAD：

1. 从 [tdlib/td](https://github.com/tdlib/td) 选定官方提交，将完整 40 位 SHA 写入 `native/tdlib-source.properties` 的 `commit`。
2. 在新检出目录或 CI 干净 runner 中运行 `scripts/build-tdlib.ps1`。脚本同步生成 Java/JNI 绑定与 Native Image 元数据，禁止新 DLL 配旧 JAR。
3. 修复上游 API 变化引起的编译问题，跑 JVM/native/media 检查，再运行生成 EXE 的离线烟雾测试，通过后才分发。

现有本地 TDLib 源码目录若与新 pin 不一致，构建脚本会停下，避免悄悄复用旧文件。TDLib 接口变化可能需要修改客户端代码，CI 不能保证任意新版直接兼容。应用凭据不因 TDLib 升级而重新申请。

CI 会在 TDLib 编译完成后、应用测试之前立即保存完整依赖缓存，并上传以 `tdlib-windows-x64-` 开头的独立 ZIP 产物，保留 90 天；后面的应用测试失败也不影响已保存的依赖。包内含对应版本的 `tdjni.dll`、Java 绑定、依赖 DLL、许可证和逐文件校验信息，不含 FFmpeg、应用凭据或账号数据。

后续构建只在源码提交、实际工具链及构建脚本完全匹配时复用缓存；恢复时核验哈希、架构和 Java/JNI 来源，并重新生成 Native Image 元数据。缓存可能被 GitHub 淘汰，未命中时仍从源码构建。独立 ZIP 在该次 Actions 的 Artifacts 中下载，不提交到源码仓库。JVM 测试结果另外保留 14 天，方便失败时查看完整断言。

## 源码与许可

nngGram 原创代码按 **GNU GPL v3（GPL-3.0-only）** 发布，见 [LICENSE](LICENSE)。第三方代码、字体、音效、壁纸和安装器源文件保留各自的许可证与来源声明；`third_party/` 和必要资源许可证随源码保留。

仓库保留源码、源代码测试、Gradle Wrapper、CI 和安装脚本、必要资源与许可证。`docs/`、本地测试报告、构建输出、`dist/`、工具链、IDE/Agent 文件、真实凭据和账号数据均不上传。发布 ZIP/MSI 由 CI 生成，不提交到源码仓库。

## 工程位置

- `desktopApp/src/main/kotlin/com/badnng/moe/`：窗口、启动、关闭及本地编辑器入口。
- `shared/src/commonMain/kotlin/com/badnng/moe/`：Miuix UI、运行快照及状态展示。
- `shared/src/jvmMain/kotlin/com/badnng/moe/tdlib/`：原生校验、配置、目录锁、官方绑定适配与单客户端流程。
- `shared/src/jvmMain/kotlin/com/badnng/moe/tdlib/OfficialTdConversationTools.kt`、`OfficialTdChatListActions.kt`：真实搜索、群详情、置顶及历史定位、列表操作和消息链接请求。
- `shared/src/commonMain/kotlin/com/badnng/moe/ConversationToolsUi.kt`、`ChatListActionsUi.kt`：搜索、会话信息与列表右键菜单；`ChatFolderUi.kt`、`FolderNavigationUi.kt`、`ChatFolderPreferences.kt`：文件夹设置、连续移动指示与本地标签偏好。
- `shared/src/jvmMain/kotlin/com/badnng/moe/media/`：附件校验、保存、Skia 动画、FFmpeg 贴纸解码与聊天内视频播放。
- `shared/src/jvmTest/`：配置、原生文件校验、初始化流程及可选真实原生测试。
- `scripts/build-tdlib.ps1`：可重复执行的 Windows x64 构建脚本。
- `scripts/build-desktop-native.ps1`：隔离构建目录、GraalVM 与便携 MSVC/Windows SDK 的 Native Image 构建入口。
- `scripts/GenerateTdlibNativeMetadata.java`：从固定绑定生成并校验 JNI 元数据与 JAR 哈希。
- `scripts/verify-tdlib.ps1`：可重复执行的隔离原生验收。
- `scripts/prepare-media.ps1`：准备本地媒体解码/检查工具及许可证。
- `native/tdlib-source.properties`：固定官方 TDLib 提交。
